package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants.UNIT_METERS
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import de.timklge.karooroutegraph.ChangeMinimapZoomLevel
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.MinimapViewModel
import de.timklge.karooroutegraph.MinimapViewModelProvider
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.TARGET_TILE_SIZE
import de.timklge.karooroutegraph.TileDownloadService
import de.timklge.karooroutegraph.earthCircumference
import de.timklge.karooroutegraph.getOSMZoomLevelToFit
import de.timklge.karooroutegraph.getRequiredTiles
import de.timklge.karooroutegraph.latToTileY
import de.timklge.karooroutegraph.lonToTileX
import de.timklge.karooroutegraph.previewPolylineBase64
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.streamSettings
import de.timklge.karooroutegraph.streamUserProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class MinimapZoomLevel(val level: Float?) {
    CLOSEST(16.0f),
    CLOSE(14.0f),
    FAR(12.0f),
    FURTHEST(10.0f),
    COMPLETE_ROUTE(null);

    fun next(maxZoomLevel: Float): MinimapZoomLevel {
        if (this == COMPLETE_ROUTE) {
            return CLOSEST
        }

        val nextLevel = when (this) {
            CLOSEST -> CLOSE
            CLOSE -> FAR
            FAR -> FURTHEST
            FURTHEST -> COMPLETE_ROUTE
            else -> null
        }

        if (nextLevel?.level == null) {
            return COMPLETE_ROUTE
        }

        return if (nextLevel.level <     maxZoomLevel) {
            COMPLETE_ROUTE
        } else {
            nextLevel
        }
    }
}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class MinimapDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val minimapViewModelProvider: MinimapViewModelProvider,
    private val tileDownloadService: TileDownloadService,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "minimap") {
    private val glance = GlanceRemoteViews()

    // Constants for unit conversion
    private val METERS_PER_FOOT = 0.3048
    private val FEET_PER_MILE = 5280.0

    private fun isNightMode(): Boolean {
        val nightModeFlags =
            applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private val defaultMapCenter = Point.fromLngLat(13.3777, 52.5163)

    data class StreamData(
        val routeGraphViewModel: RouteGraphViewModel,
        val minimapViewModel: MinimapViewModel,
        val profile: UserProfile,
        val displayViewModel: RouteGraphDisplayViewModel,
        val settings: RouteGraphSettings
    )

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting minimap view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        val flow = if (config.preview) {
            val polyline = LineString.fromPolyline(String(Base64.decode(previewPolylineBase64, 0)), 5)
            val polylineLength = TurfMeasurement.length(polyline, UNIT_METERS).toFloat()
            val positionAlongRoute = TurfMeasurement.along(polyline, 3_000.0, UNIT_METERS)
            val bearing = TurfMeasurement.bearing(positionAlongRoute, TurfMeasurement.along(polyline, 3010.0, UNIT_METERS))

            flow {
                emit(StreamData(
                    RouteGraphViewModel(routeDistance = polylineLength, distanceAlongRoute = 3_000f, knownRoute = polyline,
                        locationAndRemainingRouteDistance = KarooRouteGraphExtension.LocationAndRemainingRouteDistance(positionAlongRoute.latitude(), positionAlongRoute.longitude(), bearing, polylineLength.toDouble() - 3_000)),
                    MinimapViewModel(),
                    UserProfile(
                        weight = 70.0f,
                        preferredUnit = UserProfile.PreferredUnit(
                            distance = UserProfile.PreferredUnit.UnitType.METRIC,
                            temperature = UserProfile.PreferredUnit.UnitType.METRIC,
                            elevation = UserProfile.PreferredUnit.UnitType.METRIC,
                            weight = UserProfile.PreferredUnit.UnitType.METRIC,
                        ),
                        maxHr = 190,
                        restingHr = 60,
                        heartRateZones = listOf(),
                        ftp = 200,
                        powerZones = listOf()
                    ),
                    displayViewModel = RouteGraphDisplayViewModel(),
                    settings = context.streamSettings(karooSystem).first()
                ))
            }
        } else {
            combine(
                viewModelProvider.viewModelFlow,
                minimapViewModelProvider.viewModelFlow,
                karooSystem.streamUserProfile(),
                displayViewModelProvider.viewModelFlow,
                context.streamSettings(karooSystem)
            ) { viewModel, minimapViewModel, profile, displayViewModel, settings ->
                StreamData(viewModel, minimapViewModel, profile, displayViewModel, settings)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            displayViewModelProvider.update { displayViewModel ->
                val width = config.viewSize.first
                val height = config.viewSize.second

                displayViewModel.copy(minimapWidth = width, minimapHeight = height)
            }

            flow.collectLatest { (viewModel, minimapViewModel, userProfile, displayViewModel, settings) ->
                val width = config.viewSize.first
                val height = config.viewSize.second

                val centerPosition = if (displayViewModel.minimapZoomLevel == MinimapZoomLevel.COMPLETE_ROUTE || viewModel.distanceAlongRoute == null || minimapViewModel.currentLat == null || minimapViewModel.currentLng == null) {
                    if (viewModel.rejoin != null) {
                        viewModel.rejoin.getCenterPoint() ?: defaultMapCenter
                    } else if (viewModel.routeToDestination != null) {
                        viewModel.routeToDestination.getCenterPoint() ?: defaultMapCenter
                    } else if (viewModel.knownRoute != null) {
                        viewModel.knownRoute.getCenterPoint() ?: defaultMapCenter
                    } else {
                        defaultMapCenter
                    }
                } else {
                    Point.fromLngLat(minimapViewModel.currentLng, minimapViewModel.currentLat)
                }
                val zoomLevel = if (displayViewModel.minimapZoomLevel == MinimapZoomLevel.COMPLETE_ROUTE){
                    if (viewModel.rejoin != null){
                        viewModel.rejoin.getOSMZoomLevelToFit(width, height)
                    } else if (viewModel.routeToDestination != null){
                        viewModel.routeToDestination.getOSMZoomLevelToFit(width, height)
                    } else if (viewModel.knownRoute != null){
                        viewModel.knownRoute.getOSMZoomLevelToFit(width, height)
                    } else {
                        16.0f
                    }
                } else {
                    displayViewModel.minimapZoomLevel.level ?: 16.0f
                }

                suspend fun update() {
                    val bitmap = createBitmap(width, height)

                    val canvas = Canvas(bitmap)
                    val nightMode = isNightMode()
                    val imperialUnits = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                    // --- Map Drawing Logic (Placeholder - Assuming map is drawn here) ---
                    // Clear canvas or draw map background if needed
                    canvas.drawColor(if (nightMode) Color.BLACK else Color.WHITE)

                    val intZoom = floor(zoomLevel).toInt()
                    val requiredTiles = getRequiredTiles(zoomLevel, width, height, centerPosition)
                    val downloadedTiles = requiredTiles.associateWith { tile -> tileDownloadService.getTileIfAvailableInstantly(tile) }
                    val nextTileToDownload = requiredTiles.firstOrNull { tile -> downloadedTiles[tile] == null } // Check if bitmap is null

                    // --- Draw Map Tiles ---
                    val centerTileX = lonToTileX(centerPosition.longitude(), intZoom)
                    val centerTileY = latToTileY(centerPosition.latitude(), intZoom)
                    val centerScreenX = width / 2.0
                    val centerScreenY = height / 2.0

                    downloadedTiles.forEach { (tile, tileBitmap) ->
                        if (tileBitmap != null) { // Ensure bitmap is not null
                            // Calculate the difference in tile coordinates from the center
                            val deltaTileX = tile.x - centerTileX
                            val deltaTileY = tile.y - centerTileY

                            // Convert tile difference to pixel difference using the target size
                            val deltaPixelX = deltaTileX * TARGET_TILE_SIZE
                            val deltaPixelY = deltaTileY * TARGET_TILE_SIZE

                            // Calculate the top-left screen coordinates for this tile
                            val tileScreenX = (centerScreenX + deltaPixelX).toFloat()
                            val tileScreenY = (centerScreenY + deltaPixelY).toFloat()

                            // Define source and destination rectangles for scaling
                            val srcRect = Rect(0, 0, tileBitmap.width, tileBitmap.height) // Source is the original tile bitmap
                            val dstRect = Rect(
                                tileScreenX.toInt(),
                                tileScreenY.toInt(),
                                (tileScreenX + TARGET_TILE_SIZE).toInt(), // Use target size for destination width
                                (tileScreenY + TARGET_TILE_SIZE).toInt()  // Use target size for destination height
                            )
                            val bitmapPaint = Paint().apply {
                                isFilterBitmap = true
                                isAntiAlias = true

                                // Grayscale
                                // val matrix = ColorMatrix().apply { setSaturation(0f) }
                                // colorFilter = ColorMatrixColorFilter(matrix)

                                if (nightMode) {
                                    val invertMatrix = ColorMatrix(floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f
                                    ))
                                    val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                                    invertMatrix.postConcat(grayMatrix)
                                    colorFilter = ColorMatrixColorFilter(invertMatrix)
                                }
                            }

                            canvas.drawBitmap(tileBitmap, srcRect, dstRect, bitmapPaint)
                        }
                    }

                    if (minimapViewModel.pastPoints != null) {
                        val lineString = LineString.fromLngLats(minimapViewModel.pastPoints)
                        drawPolyline(lineString, canvas, Color.GRAY, 8f, centerPosition, zoomLevel)
                    }
                    if (viewModel.rejoin != null) {
                        drawPolyline(viewModel.rejoin, canvas, Color.RED, 8f, centerPosition, zoomLevel)
                    } else if (viewModel.routeToDestination != null) {
                        drawPolyline(viewModel.routeToDestination, canvas, Color.rgb(1.0f, 0.0f, 1.0f), 5f, centerPosition, zoomLevel)
                    }

                    if (viewModel.knownRoute != null) {
                        if (viewModel.distanceAlongRoute != null) {
                            // Only draw the part of the route that is ahead of the current position
                            val endDistance = viewModel.routeDistance?.toDouble() ?: TurfMeasurement.length(viewModel.knownRoute, UNIT_METERS)
                            val startDistance = viewModel.distanceAlongRoute.toDouble().coerceIn(0.0, endDistance)
                            val routeSlice = try {
                                TurfMisc.lineSliceAlong(viewModel.knownRoute, startDistance, endDistance, UNIT_METERS)
                            } catch(e: Exception) {
                                Log.e(TAG, "Error slicing route: ${e.message}")
                                null
                            }
                            val pastRouteSlice = try {
                                TurfMisc.lineSliceAlong(viewModel.knownRoute, 0.0, startDistance, UNIT_METERS)
                            } catch(e: Exception) {
                                Log.e(TAG, "Error slicing route: ${e.message}")
                                null
                            }

                            if (pastRouteSlice != null) drawPolyline(pastRouteSlice, canvas, Color.LTGRAY, 8f, centerPosition, zoomLevel)
                            if (routeSlice != null) drawPolyline(routeSlice, canvas, Color.YELLOW, 8f, centerPosition, zoomLevel)
                        } else {
                            drawPolyline(viewModel.knownRoute, canvas, Color.YELLOW, 8f, centerPosition, zoomLevel)
                        }
                    }

                    if (viewModel.knownRoute != null) {
                        viewModel.climbs?.forEach { climb ->
                            try {
                                val polyline = TurfMisc.lineSliceAlong(viewModel.knownRoute, climb.startDistance.toDouble().coerceAtLeast(0.0), climb.endDistance.toDouble(), UNIT_METERS)

                                drawPolyline(polyline, canvas, applicationContext.getColor(climb.category.colorRes), 8f, centerPosition, zoomLevel)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error drawing climb polyline", e)
                            }
                        }
                    }

                    // Only draw POIs and scale bar if larger than 1x1 data field
                    if (config.gridSize.first > 15 && config.gridSize.second > 15) {
                        val pois = viewModel.poiDistances?.keys ?: emptySet()
                        pois.forEach { poi ->
                            // Label: poi.name, Lat: poi.lat, Long: poi.lng
                            drawPoi(canvas, poi, centerPosition, zoomLevel, settings.showPOILabelsOnMinimap)
                        }

                        // --- Draw Scale Bar ---
                        drawScaleBar(
                            canvas,
                            height,
                            centerPosition.latitude(),
                            zoomLevel,
                            imperialUnits
                        )
                    }

                    // --- Draw Copyright ---
                    drawCopyright(canvas, width, height, nightMode)

                    if (viewModel.locationAndRemainingRouteDistance?.lon != null && viewModel.locationAndRemainingRouteDistance.lat != null) {
                        drawCurrentPosition(
                            canvas,
                            viewModel.locationAndRemainingRouteDistance.lon,
                            viewModel.locationAndRemainingRouteDistance.lat,
                            centerPosition,
                            zoomLevel,
                            (viewModel.locationAndRemainingRouteDistance.bearing ?: 0.0)    ,
                            Color.YELLOW,
                            10f
                        )
                    }

                    val result = glance.compose(context, DpSize.Unspecified) {
                        var modifier = GlanceModifier.fillMaxSize()

                        if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<ChangeMinimapZoomLevel>(
                            parameters = actionParametersOf(
                                ActionParameters.Key<String>("action_type") to "minimap_zoom"
                            )
                        ))

                        Box(modifier = modifier) {
                            Image(ImageProvider(bitmap), "Minimap", modifier = GlanceModifier.fillMaxSize())
                        }
                    }

                    emitter.updateView(result.remoteViews)

                    if (nextTileToDownload != null) {
                        try {
                            tileDownloadService.getTile(nextTileToDownload)
                            update()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error downloading tile: ${nextTileToDownload}", e)
                        }
                    }
                }

                try {
                    update()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating minimap view", e)
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling minimap view")
            configJob.cancel()
            viewJob.cancel()
        }
    }

    // --- Draw POI ---
    private fun drawPoi(
        canvas: Canvas,
        poi: Symbol.POI,
        mapCenter: Point,
        zoomLevel: Float,
        showPOILabelsOnMinimap: Boolean
    ) {
        val poiPoint = Point.fromLngLat(poi.lng, poi.lat)

        val intZoom = floor(zoomLevel).toInt()

        // Calculate screen coordinates for the POI
        val centerTileX = lonToTileX(mapCenter.longitude(), intZoom)
        val centerTileY = latToTileY(mapCenter.latitude(), intZoom)
        val poiTileX = lonToTileX(poiPoint.longitude(), intZoom)
        val poiTileY = latToTileY(poiPoint.latitude(), intZoom)

        // Calculate pixel difference from center based on tile difference, using target size
        val deltaPixelX = (poiTileX - centerTileX) * TARGET_TILE_SIZE
        val deltaPixelY = (poiTileY - centerTileY) * TARGET_TILE_SIZE

        val screenX = (canvas.width / 2f + deltaPixelX).toFloat()
        val screenY = (canvas.height / 2f + deltaPixelY).toFloat()

        // --- Draw the 'X' marker ---
        val xSize = 15f // Half the size of the X
        val xStrokeWidth = 5f
        val outlineStrokeWidth = xStrokeWidth + 4f // Outline slightly thicker

        val xPaintWhite = Paint().apply {
            color = Color.WHITE
            strokeWidth = xStrokeWidth
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        val xPaintBlackOutline = Paint().apply {
            color = Color.BLACK
            strokeWidth = outlineStrokeWidth
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        // Draw outline first
        canvas.drawLine(screenX - xSize, screenY - xSize, screenX + xSize, screenY + xSize, xPaintBlackOutline) // Top-left to bottom-right
        canvas.drawLine(screenX - xSize, screenY + xSize, screenX + xSize, screenY - xSize, xPaintBlackOutline) // Bottom-left to top-right

        // Draw white X on top
        canvas.drawLine(screenX - xSize, screenY - xSize, screenX + xSize, screenY + xSize, xPaintWhite) // Top-left to bottom-right
        canvas.drawLine(screenX - xSize, screenY + xSize, screenX + xSize, screenY - xSize, xPaintWhite) // Bottom-left to top-right


        if (showPOILabelsOnMinimap) {
            // --- Prepare Paints for Label ---
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 30f // Adjust size as needed
                isAntiAlias = true
                textAlign = Paint.Align.CENTER // Center text horizontally
                typeface = Typeface.DEFAULT_BOLD // Set font to bold
            }

            val backgroundPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0) // Semi-transparent black background
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            // --- Measure Text ---
            val text = poi.name ?: "POI"
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)

            // --- Draw Background Box ---
            val padding = 5f // Padding around the text
            // Adjust label position slightly above the X marker
            val labelOffsetY = -xSize - 5f // Offset the label upwards from the center of the X
            val rectLeft = screenX - textBounds.width() / 2f - padding
            val rectTop =
                screenY + labelOffsetY - textBounds.height() - padding // Position box above the point's Y, considering offset
            val rectRight = screenX + textBounds.width() / 2f + padding
            val rectBottom =
                screenY + labelOffsetY + padding // Adjust bottom based on text baseline and offset

            val backgroundRect = RectF(rectLeft, rectTop, rectRight, rectBottom)
            val cornerRadius = 10f // Rounded corners for the box
            canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)

            // --- Draw Text ---
            // Adjust Y position for drawing text (Paint.Align.CENTER centers horizontally, need vertical adjustment)
            val textY =
                screenY + labelOffsetY // Center text vertically within the box, considering offset
            canvas.drawText(text, screenX, textY, textPaint) // Use adjusted Y
        }
    }

    private fun drawCurrentPosition(
        canvas: Canvas,
        lng: Double,
        lat: Double,
        mapCenter: Point, // Added mapCenter
        zoomLevel: Float, // Changed to Float and renamed from zoomLevel (Int)
        bearingInDegrees: Double,
        color: Int,
        strokeWidth: Float,
    ) {
        val chevronPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val chevronOutlinePaint = Paint().apply {
            this.color = Color.BLACK
            style = Paint.Style.STROKE
            isAntiAlias = true
            this.strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val triangleSize = 30f
        val triangleBaseAngleRad = (PI / 1.5).toFloat()

        val intZoom = floor(zoomLevel).toInt()
        val centerTileX = lonToTileX(mapCenter.longitude(), intZoom)
        val centerTileY = latToTileY(mapCenter.latitude(), intZoom)
        val centerScreenX = canvas.width / 2f
        val centerScreenY = canvas.height / 2f

        val pointTileX = lonToTileX(lng, intZoom)
        val pointTileY = latToTileY(lat, intZoom)

        val deltaPixelX = (pointTileX - centerTileX) * TARGET_TILE_SIZE
        val deltaPixelY = (pointTileY - centerTileY) * TARGET_TILE_SIZE

        val screenX = (centerScreenX + deltaPixelX).toFloat()
        val screenY = (centerScreenY + deltaPixelY).toFloat()

        val angleRad = Math.toRadians(bearingInDegrees + 90.0).toFloat()

        val leftBaseX = screenX + triangleSize * cos(angleRad + PI.toFloat() - triangleBaseAngleRad)
        val leftBaseY = screenY + triangleSize * sin(angleRad + PI.toFloat() - triangleBaseAngleRad)
        val rightBaseX = screenX + triangleSize * cos(angleRad + PI.toFloat() + triangleBaseAngleRad)
        val rightBaseY = screenY + triangleSize * sin(angleRad + PI.toFloat() + triangleBaseAngleRad)

        val trianglePath = Path()
        trianglePath.moveTo(screenX, screenY)
        trianglePath.lineTo(leftBaseX, leftBaseY)
        trianglePath.lineTo(rightBaseX, rightBaseY)
        trianglePath.close()

        canvas.drawPath(trianglePath, chevronPaint)
        canvas.drawPath(trianglePath, chevronOutlinePaint)
    }

    private fun drawPolyline(
        lineString: LineString,
        canvas: Canvas,
        color: Int,
        strokeWidth: Float,
        mapCenter: Point,
        zoomLevel: Float,
        drawDirectionIndicatorChevrons: Boolean = true
    ) {
        val points = lineString.coordinates()
        if (points.size < 2) {
            return
        }

        val linePaint = Paint().apply {
            this.color = color
            this.strokeWidth = strokeWidth
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val linePaintOutline = Paint().apply {
            this.color = Color.BLACK
            this.strokeWidth = strokeWidth + 3
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val chevronPaint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            isAntiAlias = true
            this.strokeWidth = strokeWidth * 0.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val chevronOutlinePaint = Paint().apply {
            this.color = Color.BLACK
            style = Paint.Style.STROKE
            isAntiAlias = true
            this.strokeWidth = strokeWidth * 0.6f + 3
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()

        val intZoom = floor(zoomLevel).toInt()
        val centerTileX = lonToTileX(mapCenter.longitude(), intZoom)
        val centerTileY = latToTileY(mapCenter.latitude(), intZoom)
        val centerScreenX = canvas.width / 2f
        val centerScreenY = canvas.height / 2f

        val screenPoints = mutableListOf<Pair<Float, Float>>()

        var firstPoint = true
        for (point in points) {
            val pointTileX = lonToTileX(point.longitude(), intZoom)
            val pointTileY = latToTileY(point.latitude(), intZoom)

            val deltaPixelX = (pointTileX - centerTileX) * TARGET_TILE_SIZE
            val deltaPixelY = (pointTileY - centerTileY) * TARGET_TILE_SIZE

            val screenX = (centerScreenX + deltaPixelX).toFloat()
            val screenY = (centerScreenY + deltaPixelY).toFloat()

            screenPoints.add(Pair(screenX, screenY))

            if (firstPoint) {
                path.moveTo(screenX, screenY)
                firstPoint = false
            } else {
                path.lineTo(screenX, screenY)
            }
        }

        canvas.drawPath(path, linePaintOutline)
        canvas.drawPath(path, linePaint)

        if (drawDirectionIndicatorChevrons && screenPoints.size >= 2) {
            val chevronSize = 20f
            val chevronAngleRad = (PI / 4).toFloat()

            val chevronIntervalPx = when {
                zoomLevel >= 16 -> 60f
                zoomLevel >= 14 -> 100f
                zoomLevel >= 12 -> 150f
                else -> 200f
            }

            var cumulativeDistance = 0f
            var nextChevronDistance = chevronIntervalPx

            for (i in 1 until screenPoints.size) {
                val startX = screenPoints[i - 1].first
                val startY = screenPoints[i - 1].second
                val endX = screenPoints[i].first
                val endY = screenPoints[i].second

                val dx = endX - startX
                val dy = endY - startY
                val segmentLength = sqrt(dx * dx + dy * dy)

                while (nextChevronDistance <= cumulativeDistance + segmentLength && segmentLength > 1e-6) {
                    val distanceIntoSegment = nextChevronDistance - cumulativeDistance
                    val interpolationFactor = distanceIntoSegment / segmentLength

                    val chevronX = startX + dx * interpolationFactor
                    val chevronY = startY + dy * interpolationFactor

                    val angle = atan2(dy, dx)

                    val leftX = chevronX + chevronSize * cos(angle + PI.toFloat() - chevronAngleRad)
                    val leftY = chevronY + chevronSize * sin(angle + PI.toFloat() - chevronAngleRad)
                    val rightX = chevronX + chevronSize * cos(angle + PI.toFloat() + chevronAngleRad)
                    val rightY = chevronY + chevronSize * sin(angle + PI.toFloat() + chevronAngleRad)

                    val chevronPath = Path()
                    chevronPath.moveTo(leftX, leftY)
                    chevronPath.lineTo(chevronX, chevronY)
                    chevronPath.lineTo(rightX, rightY)

                    canvas.drawPath(chevronPath, chevronOutlinePaint)
                    canvas.drawPath(chevronPath, chevronPaint)

                    nextChevronDistance += chevronIntervalPx
                }

                cumulativeDistance += segmentLength
            }
        }
    }

    private fun drawScaleBar(
        canvas: Canvas,
        height: Int,
        latitude: Double,
        zoomLevel: Float,
        imperialUnits: Boolean
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            strokeWidth = 3f
            textSize = 20f
            textAlign = Paint.Align.LEFT
        }
        val textPaint = Paint(paint).apply {
            strokeWidth = 1f
        }

        val padding = 5f

        val metersPerPixel = (earthCircumference * cos(Math.toRadians(latitude))) / (2.0.pow(zoomLevel.toDouble() + 8))

        val targetWidthPx = 75
        val targetDistanceMeters = targetWidthPx * metersPerPixel

        val bestDistanceMeters: Double
        val scaleText: String

        if (imperialUnits) {
            val niceDistancesFeet = listOf(
                50.0, 100.0, 200.0, 500.0, 1000.0,
                FEET_PER_MILE / 4, FEET_PER_MILE / 2, FEET_PER_MILE,
                2 * FEET_PER_MILE, 5 * FEET_PER_MILE, 10 * FEET_PER_MILE, 20 * FEET_PER_MILE,
                50 * FEET_PER_MILE, 100 * FEET_PER_MILE, 200 * FEET_PER_MILE, 500 * FEET_PER_MILE
            )
            val targetDistanceFeet = targetDistanceMeters / METERS_PER_FOOT

            var bestDistFeet = niceDistancesFeet.last()
            for (distFeet in niceDistancesFeet) {
                if (distFeet >= targetDistanceFeet) {
                    bestDistFeet = distFeet
                    break
                }
            }

            bestDistanceMeters = bestDistFeet * METERS_PER_FOOT

            scaleText = if (bestDistFeet < FEET_PER_MILE) {
                "${bestDistFeet.toInt()} ft"
            } else {
                val miles = bestDistFeet / FEET_PER_MILE
                if (miles == floor(miles)) {
                    "${miles.toInt()} mi"
                } else {
                    String.format("%.1f mi", miles)
                }
            }
        } else {
            val niceDistancesMeters = listOf(
                10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
                1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0,
                100000.0, 200000.0, 500000.0
            )
            var bestDistMeters = niceDistancesMeters.last()
            for (distMeters in niceDistancesMeters) {
                if (distMeters >= targetDistanceMeters) {
                    bestDistMeters = distMeters
                    break
                }
            }

            bestDistanceMeters = bestDistMeters

            scaleText = if (bestDistanceMeters >= 1000) {
                "${(bestDistanceMeters / 1000).toInt()} km"
            } else {
                "${bestDistanceMeters.toInt()} m"
            }
        }

        val scaleBarWidthPx = (bestDistanceMeters / metersPerPixel).toFloat()

        val startX = padding
        val endX = startX + scaleBarWidthPx
        val yPos = height - padding - 25f

        canvas.drawLine(startX, yPos, endX, yPos, paint)
        canvas.drawLine(startX, yPos - 5, startX, yPos + 5, paint)
        canvas.drawLine(endX, yPos - 5, endX, yPos + 5, paint)

        val textBounds = Rect()
        textPaint.getTextBounds(scaleText, 0, scaleText.length, textBounds)
        val textX = startX
        canvas.drawText(scaleText, textX, yPos - 10, textPaint)
    }

    private fun drawCopyright(canvas: Canvas, width: Int, height: Int, nightMode: Boolean) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = if (nightMode) Color.GRAY else Color.DKGRAY
            textSize = 18f
            textAlign = Paint.Align.RIGHT
        }
        val padding = 5f
        val copyrightText = "(c) OpenStreetMap"

        val xPos = width - padding
        val yPos = height - padding

        canvas.drawText(copyrightText, xPos, yPos, paint)
    }
}

fun LineString.getCenterPoint(): Point? {
    val points = this.coordinates()
    if (points.isEmpty()) {
        return null
    }

    var totalLat = 0.0
    var totalLng = 0.0

    for (point in points) {
        totalLat += point.latitude()
        totalLng += point.longitude()
    }

    val avgLat = totalLat / points.size
    val avgLng = totalLng / points.size

    return Point.fromLngLat(avgLng, avgLat)
}
