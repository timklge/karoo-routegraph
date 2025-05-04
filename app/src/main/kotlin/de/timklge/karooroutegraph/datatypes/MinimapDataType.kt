package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface // Import Typeface
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants.TurfUnitCriteria
import com.mapbox.turf.TurfConstants.UNIT_METERS
import com.mapbox.turf.TurfConversion
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import com.mapbox.turf.TurfTransformation
import de.timklge.karooroutegraph.ChangeZoomLevelAction
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.MinimapViewModel
import de.timklge.karooroutegraph.MinimapViewModelProvider
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.Tile
import de.timklge.karooroutegraph.TileDownloadService
import de.timklge.karooroutegraph.ZoomLevel
import de.timklge.karooroutegraph.streamUserProfile
import de.timklge.karooroutegraph.throttle
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.ceil

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
    private val METERS_PER_MILE = FEET_PER_MILE * METERS_PER_FOOT

    private fun isNightMode(): Boolean {
        val nightModeFlags =
            applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private val defaultMapCenter = Point.fromLngLat(13.3777, 52.5163)

    data class StreamData(
        val routeGraphViewModel: RouteGraphViewModel,
        val routeGraphDisplayViewModel: RouteGraphDisplayViewModel,
        val minimapViewModel: MinimapViewModel,
        val profile: UserProfile
    )

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting minimap view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        val flow = if (config.preview) {
            flowOf(StreamData(
                RouteGraphViewModel(),
                RouteGraphDisplayViewModel(),
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
                ))
            )
        } else {
            combine(
                viewModelProvider.viewModelFlow,
                displayViewModelProvider.viewModelFlow,
                minimapViewModelProvider.viewModelFlow,
                karooSystem.streamUserProfile()
            ) { viewModel, displayViewModel, minimapViewModel, profile ->
                StreamData(viewModel, displayViewModel, minimapViewModel, profile)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            flow.throttle(1_000L).collect { (viewModel, displayViewModel, minimapViewModel, userProfile) ->
                val width = config.viewSize.first
                val height = config.viewSize.second

                val centerPosition = if (displayViewModel.zoomLevel == ZoomLevel.COMPLETE_ROUTE || viewModel.distanceAlongRoute == null || minimapViewModel.currentLat == null || minimapViewModel.currentLng == null) {
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
                val zoomLevel = if (displayViewModel.zoomLevel == ZoomLevel.COMPLETE_ROUTE){
                    if (viewModel.rejoin != null){
                        viewModel.rejoin.getOSMZoomLevelToFit(width, height)
                    } else if (viewModel.routeToDestination != null){
                        viewModel.routeToDestination.getOSMZoomLevelToFit(width, height)
                    } else if (viewModel.knownRoute != null){
                        viewModel.knownRoute.getOSMZoomLevelToFit(width, height)
                    } else {
                        16
                    }
                } else {
                    when (displayViewModel.zoomLevel){
                        ZoomLevel.TWO_UNITS -> 16
                        ZoomLevel.TWENTY_UNITS -> 14
                        ZoomLevel.FIFTY_UNITS -> 12
                        ZoomLevel.HUNDRED_UNITS -> 10
                        else -> 16
                    }
                }

                suspend fun update() {
                    val bitmap = createBitmap(width, height)

                    val canvas = Canvas(bitmap)
                    val nightMode = isNightMode()
                    val imperialUnits = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                    // --- Map Drawing Logic (Placeholder - Assuming map is drawn here) ---
                    // Clear canvas or draw map background if needed
                    canvas.drawColor(if (nightMode) Color.BLACK else Color.WHITE)

                    val requiredTiles = getRequiredTiles(zoomLevel, width, height, centerPosition)
                    val downloadedTiles = requiredTiles.associateWith { tile -> tileDownloadService.getTileIfAvailableInstantly(tile) }
                    val nextTileToDownload = requiredTiles.firstOrNull { tile -> downloadedTiles[tile] == null } // Check if bitmap is null

                    // --- Draw Map Tiles ---
                    val centerTileX = lonToTileX(centerPosition.longitude(), zoomLevel)
                    val centerTileY = latToTileY(centerPosition.latitude(), zoomLevel)
                    val centerScreenX = width / 2.0
                    val centerScreenY = height / 2.0

                    downloadedTiles.forEach { (tile, tileBitmap) ->
                        if (tileBitmap != null) { // Ensure bitmap is not null
                            // Calculate the difference in tile coordinates from the center
                            val deltaTileX = tile.x - centerTileX
                            val deltaTileY = tile.y - centerTileY

                            // Convert tile difference to pixel difference
                            val deltaPixelX = deltaTileX * TILE_SIZE
                            val deltaPixelY = deltaTileY * TILE_SIZE

                            // Calculate the top-left screen coordinates for this tile
                            val tileScreenX = (centerScreenX + deltaPixelX).toFloat()
                            val tileScreenY = (centerScreenY + deltaPixelY).toFloat()

                            // Define source and destination rectangles for scaling
                            val srcRect = Rect(0, 0, tileBitmap.width, tileBitmap.height)
                            val dstRect = Rect(
                                tileScreenX.toInt(),
                                tileScreenY.toInt(),
                                (tileScreenX + TILE_SIZE).toInt(),
                                (tileScreenY + TILE_SIZE).toInt()
                            )

                            // Draw the tile bitmap onto the canvas, scaling if necessary
                            canvas.drawBitmap(tileBitmap, srcRect, dstRect, null)
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
                            val startDistance = viewModel.distanceAlongRoute.toDouble()
                            val endDistance = viewModel.routeDistance?.toDouble() ?: TurfMeasurement.length(viewModel.knownRoute, UNIT_METERS)
                            val routeSlice = TurfMisc.lineSliceAlong(viewModel.knownRoute, startDistance, endDistance, UNIT_METERS)

                            drawPolyline(routeSlice, canvas, Color.YELLOW, 8f, centerPosition, zoomLevel)
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

                    val pois = viewModel.poiDistances?.keys ?: emptySet()
                    pois.forEach { poi ->
                        // Label: poi.name, Lat: poi.lat, Long: poi.lng
                        drawPoi(canvas, poi, centerPosition, zoomLevel)
                    }

                    // --- Draw Scale Bar ---
                    drawScaleBar(
                        canvas,
                        height,
                        centerPosition.latitude(),
                        zoomLevel,
                        nightMode,
                        imperialUnits
                    )

                    // --- Draw Copyright ---
                    drawCopyright(canvas, width, height, nightMode)

                    val result = glance.compose(context, DpSize.Unspecified) {
                        var modifier = GlanceModifier.fillMaxSize()

                        if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<ChangeZoomLevelAction>())

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

                update()
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
        zoomLevel: Int
    ) {
        val poiPoint = Point.fromLngLat(poi.lng, poi.lat)

        // Calculate screen coordinates for the POI
        val centerTileX = lonToTileX(mapCenter.longitude(), zoomLevel)
        val centerTileY = latToTileY(mapCenter.latitude(), zoomLevel)
        val poiTileX = lonToTileX(poiPoint.longitude(), zoomLevel)
        val poiTileY = latToTileY(poiPoint.latitude(), zoomLevel)

        val deltaPixelX = (poiTileX - centerTileX) * TILE_SIZE
        val deltaPixelY = (poiTileY - centerTileY) * TILE_SIZE

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
        val rectTop = screenY + labelOffsetY - textBounds.height() - padding // Position box above the point's Y, considering offset
        val rectRight = screenX + textBounds.width() / 2f + padding
        val rectBottom = screenY + labelOffsetY + padding // Adjust bottom based on text baseline and offset

        val backgroundRect = RectF(rectLeft, rectTop, rectRight, rectBottom)
        val cornerRadius = 10f // Rounded corners for the box
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)

        // --- Draw Text ---
        // Adjust Y position for drawing text (Paint.Align.CENTER centers horizontally, need vertical adjustment)
        val textY = screenY + labelOffsetY - padding // Center text vertically within the box, considering offset
        canvas.drawText(text, screenX, textY, textPaint) // Use adjusted Y
    }

    private fun drawPolyline(
        lineString: LineString,
        canvas: Canvas,
        color: Int, // Use android.graphics.Color (Int)
        strokeWidth: Float,
        mapCenter: Point,
        zoomLevel: Int,
        drawDirectionIndicatorChevrons: Boolean = true
    ) {
        val points = lineString.coordinates()
        if (points.size < 2) {
            return // Need at least two points to draw a line
        }

        val linePaint = Paint().apply {
            this.color = color
            this.strokeWidth = strokeWidth
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND // Nicer line endings
            strokeJoin = Paint.Join.ROUND // Nicer line joins
        }

        val linePaintOutline = Paint().apply {
            this.color = Color.BLACK
            this.strokeWidth = strokeWidth + 3
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND // Nicer line endings
            strokeJoin = Paint.Join.ROUND // Nicer line joins
        }

        // Separate paint for chevrons (filled)
        val chevronPaint = Paint().apply {
            this.color = color // Use the same color for now
            style = Paint.Style.STROKE // Use STROKE for open chevrons
            isAntiAlias = true
            this.strokeWidth = strokeWidth * 0.6f // Make chevrons slightly thinner than line
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val chevronOutlinePaint = Paint().apply {
            this.color = Color.BLACK
            style = Paint.Style.STROKE
            isAntiAlias = true
            this.strokeWidth = strokeWidth * 0.6f + 3 // Slightly thicker for outline
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = android.graphics.Path() // Use Path for efficiency

        val centerTileX = lonToTileX(mapCenter.longitude(), zoomLevel)
        val centerTileY = latToTileY(mapCenter.latitude(), zoomLevel)
        val centerScreenX = canvas.width / 2f
        val centerScreenY = canvas.height / 2f
        val TILE_SIZE = 256.0 // Standard OSM tile size in pixels

        // Store screen points for chevron calculation
        val screenPoints = mutableListOf<Pair<Float, Float>>()

        var firstPoint = true
        for (point in points) {
            val pointTileX = lonToTileX(point.longitude(), zoomLevel)
            val pointTileY = latToTileY(point.latitude(), zoomLevel)

            // Calculate pixel difference from center based on tile difference
            val deltaPixelX = (pointTileX - centerTileX) * TILE_SIZE
            val deltaPixelY = (pointTileY - centerTileY) * TILE_SIZE

            // Calculate final screen coordinates
            val screenX = (centerScreenX + deltaPixelX).toFloat()
            val screenY = (centerScreenY + deltaPixelY).toFloat()

            screenPoints.add(Pair(screenX, screenY)) // Store screen coordinates

            if (firstPoint) {
                path.moveTo(screenX, screenY)
                firstPoint = false
            } else {
                path.lineTo(screenX, screenY)
            }
        }

        // Draw the main polyline path first
        canvas.drawPath(path, linePaintOutline)
        canvas.drawPath(path, linePaint)

        // --- Draw Chevrons at fixed intervals ---
        if (drawDirectionIndicatorChevrons && screenPoints.size >= 2) {
            val chevronSize = 20f // Size of the chevron arms in pixels (adjust as needed)
            val chevronAngleRad = (PI / 4).toFloat() // Angle of chevron arms (45 degrees)

            // Determine chevron interval based on zoom level
            val chevronIntervalPx = when {
                zoomLevel >= 16 -> 60f   // More frequent chevrons when zoomed in
                zoomLevel >= 14 -> 100f
                zoomLevel >= 12 -> 150f
                else -> 200f             // Less frequent when zoomed out
            }

            var cumulativeDistance = 0f
            var nextChevronDistance = chevronIntervalPx // Start drawing after the first interval

            for (i in 1 until screenPoints.size) {
                val startX = screenPoints[i - 1].first
                val startY = screenPoints[i - 1].second
                val endX = screenPoints[i].first
                val endY = screenPoints[i].second

                val dx = endX - startX
                val dy = endY - startY
                val segmentLength = sqrt(dx * dx + dy * dy)

                // Check if any chevrons fall within this segment
                while (nextChevronDistance <= cumulativeDistance + segmentLength && segmentLength > 1e-6) {
                    // Calculate how far into the current segment the chevron should be
                    val distanceIntoSegment = nextChevronDistance - cumulativeDistance
                    val interpolationFactor = distanceIntoSegment / segmentLength

                    // Calculate the chevron's position
                    val chevronX = startX + dx * interpolationFactor
                    val chevronY = startY + dy * interpolationFactor

                    // Calculate the angle of the segment for chevron orientation
                    val angle = atan2(dy, dx)

                    // Calculate chevron points relative to its position and angle
                    // Point 1: Left arm end
                    val leftX = chevronX + chevronSize * cos(angle + PI.toFloat() - chevronAngleRad)
                    val leftY = chevronY + chevronSize * sin(angle + PI.toFloat() - chevronAngleRad)
                    // Point 2: Tip (at the calculated position)
                    // Point 3: Right arm end
                    val rightX = chevronX + chevronSize * cos(angle + PI.toFloat() + chevronAngleRad)
                    val rightY = chevronY + chevronSize * sin(angle + PI.toFloat() + chevronAngleRad)

                    // Create and draw the chevron path (open V shape)
                    val chevronPath = android.graphics.Path()
                    chevronPath.moveTo(leftX, leftY)
                    chevronPath.lineTo(chevronX, chevronY) // Tip of the chevron
                    chevronPath.lineTo(rightX, rightY)

                    canvas.drawPath(chevronPath, chevronOutlinePaint) // Use chevronOutlinePaint
                    canvas.drawPath(chevronPath, chevronPaint) // Use chevronPaint

                    // Move to the next chevron distance
                    nextChevronDistance += chevronIntervalPx
                }

                // Add the length of the processed segment to the cumulative distance
                cumulativeDistance += segmentLength
            }
        }
    }

    // Helper function: Longitude to Tile X coordinate (fractional)
    private fun lonToTileX(lon: Double, zoom: Int): Double {
        return ((lon + 180.0) / 360.0) * (1 shl zoom)
    }

    // Helper function: Latitude to Tile Y coordinate (fractional)
    private fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat)
        // Using the standard Mercator projection formula
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0) * (1 shl zoom)
    }

    private val TILE_SIZE = 256.0 // Standard OSM tile size in pixels

    private fun getRequiredTiles(zoomLevel: Int, widthPx: Int, heightPx: Int, mapCenter: Point): Set<Tile> {
        val requiredTiles = mutableSetOf<Tile>()

        val centerTileX = lonToTileX(mapCenter.longitude(), zoomLevel)
        val centerTileY = latToTileY(mapCenter.latitude(), zoomLevel)

        // Calculate how many tiles are visible horizontally and vertically from the center
        val halfWidthInTiles = (widthPx / 2.0) / TILE_SIZE
        val halfHeightInTiles = (heightPx / 2.0) / TILE_SIZE

        // Calculate the fractional tile coordinates of the corners
        val topLeftTileX = centerTileX - halfWidthInTiles
        val topLeftTileY = centerTileY - halfHeightInTiles
        val bottomRightTileX = centerTileX + halfWidthInTiles
        val bottomRightTileY = centerTileY + halfHeightInTiles

        // Determine the integer range of tiles needed
        // Use floor for min and ceil for max to ensure full coverage
        val minTileX = floor(topLeftTileX).toInt()
        val maxTileX = floor(bottomRightTileX).toInt() // Use floor as tile indices are 0-based
        val minTileY = floor(topLeftTileY).toInt()
        val maxTileY = floor(bottomRightTileY).toInt() // Use floor as tile indices are 0-based

        // Iterate through the required tile range and add them to the set
        for (x in minTileX..maxTileX) {
            for (y in minTileY..maxTileY) {
                // Basic validation: Ensure tile coordinates are within valid range for the zoom level
                val maxTileIndex = (1 shl zoomLevel) - 1
                if (x in 0..maxTileIndex && y in 0..maxTileIndex) {
                    requiredTiles.add(Tile(x, y, zoomLevel))
                }
            }
        }

        return requiredTiles
    }

    val earthCircumference = 40075016.686 // meters

    private fun drawScaleBar(
        canvas: Canvas,
        height: Int,
        latitude: Double,
        zoomLevel: Int,
        nightMode: Boolean,
        imperialUnits: Boolean
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK // if (nightMode) Color.WHITE else Color.BLACK
            strokeWidth = 3f
            textSize = 20f // Adjust as needed
            textAlign = Paint.Align.LEFT
        }
        val textPaint = Paint(paint).apply {
            strokeWidth = 1f // Thinner for text
        }

        val padding = 5f

        // Calculate meters per pixel at the center latitude and zoom level
        val metersPerPixel = (earthCircumference * cos(Math.toRadians(latitude))) / (2.0.pow(zoomLevel + 8))

        // Find a "nice" distance (e.g., 10, 20, 50, 100, 200, 500, 1k, 2k, 5k...)
        // that corresponds to a reasonable pixel width (e.g., 50-100px)
        val targetWidthPx = 75 // Target width in pixels
        val targetDistanceMeters = targetWidthPx * metersPerPixel

        val bestDistanceMeters: Double
        val scaleText: String

        if (imperialUnits) {
            // Define nice distances in feet
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
                // Simple formatting for miles (e.g., 1 mi, 2.5 mi, 10 mi)
                if (miles == floor(miles)) {
                    "${miles.toInt()} mi"
                } else {
                    // Show one decimal place for fractional miles
                    String.format("%.1f mi", miles)
                }
            }
        } else { // Metric units
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

        // Position bottom-left
        val startX = padding
        val endX = startX + scaleBarWidthPx
        // Adjust Y position slightly higher to accommodate text below for copyright
        val yPos = height - padding - 25f // Move scale bar up a bit

        // Draw the scale bar line
        canvas.drawLine(startX, yPos, endX, yPos, paint)
        // Draw ticks at ends
        canvas.drawLine(startX, yPos - 5, startX, yPos + 5, paint)
        canvas.drawLine(endX, yPos - 5, endX, yPos + 5, paint)

        // Draw the text label above the bar
        val textBounds = Rect()
        textPaint.getTextBounds(scaleText, 0, scaleText.length, textBounds)
        // Center the text horizontally over the bar if possible, otherwise left-align
        val textX = startX // Keep it simple: left-aligned for now
        canvas.drawText(scaleText, textX, yPos - 10, textPaint) // Position text above the line
    }

    private fun drawCopyright(canvas: Canvas, width: Int, height: Int, nightMode: Boolean) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = if (nightMode) Color.GRAY else Color.DKGRAY // Less prominent color
            textSize = 18f // Smaller text size
            textAlign = Paint.Align.RIGHT
        }
        val padding = 5f
        val copyrightText = "(c) OpenStreetMap"

        // Position bottom-right
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

fun LineString.getOSMZoomLevelToFit(
    screenWidthPx: Int,
    screenHeightPx: Int,
    paddingPx: Int = 5
): Int {
    val points = this.coordinates()
    if (points.isEmpty()) {
        return 18 // Default zoom for empty route
    }

    // Max reasonable zoom for a single point or very small area
    val maxZoom = 18

    if (points.size == 1) {
        return maxZoom
    }

    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE

    for (point in points) {
        minLat = min(minLat, point.latitude())
        maxLat = max(maxLat, point.latitude())
        minLon = min(minLon, point.longitude())
        maxLon = max(maxLon, point.longitude())
    }

    val latSpan = maxLat - minLat
    val lonSpan = maxLon - minLon

    // Ensure effective dimensions are at least 1 pixel
    val effectiveWidth = (screenWidthPx - 2 * paddingPx).coerceAtLeast(1).toDouble()
    val effectiveHeight = (screenHeightPx - 2 * paddingPx).coerceAtLeast(1).toDouble()

    // Handle cases where the span is zero or negligible
    if (latSpan <= 1e-6 && lonSpan <= 1e-6) {
        return maxZoom
    }

    val zoomLon: Double = if (lonSpan > 1e-6) {
        // Calculate zoom level needed to fit longitude span
        log2((effectiveWidth * 360.0) / (lonSpan * 256.0))
    } else {
        Double.POSITIVE_INFINITY // Effectively no constraint on width
    }

    val zoomLat: Double = if (latSpan > 1e-6) {
        // Calculate zoom level needed to fit latitude span
        // Note: This is an approximation ignoring Mercator projection distortion for latitude
        log2((effectiveHeight * 360.0) / (latSpan * 256.0))
    } else {
        Double.POSITIVE_INFINITY // Effectively no constraint on height
    }

    // Choose the lowest zoom level that satisfies both constraints
    val zoom = floor(min(zoomLat, zoomLon))

    // Clamp the zoom level to a valid OSM range (e.g., 0-20)
    return zoom.toInt().coerceIn(0, maxZoom)
}
