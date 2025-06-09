package de.timklge.karooroutegraph.datatypes

import ClimbCategory
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
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
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.NearestPoint
import de.timklge.karooroutegraph.POI
import de.timklge.karooroutegraph.PoiType
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SparseElevationData
import de.timklge.karooroutegraph.ZoomLevel
import de.timklge.karooroutegraph.datatypes.minimap.ChangeZoomLevelAction
import de.timklge.karooroutegraph.datatypes.minimap.mapPoiToIcon
import de.timklge.karooroutegraph.getInclineIndicatorColor
import de.timklge.karooroutegraph.streamDatatypeIsVisible
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

fun remap(value: Float, fromLow: Float, fromHigh: Float, toLow: Float, toHigh: Float): Float {
    return toLow + (value - fromLow) / (fromHigh - fromLow) * (toHigh - toLow)
}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class RouteGraphDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "routegraph") {
    private val glance = GlanceRemoteViews()

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    data class ViewModels(val routeGraphViewModel: RouteGraphViewModel, val routeGraphDisplayViewModel: RouteGraphDisplayViewModel, val isVisible: Boolean)

    @OptIn(DelicateCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting route view with $emitter")

        val instanceId = UUID.randomUUID().toString()

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        val flow = if (config.preview){
            previewFlow()
        } else {
            combine(
                viewModelProvider.viewModelFlow,
                displayViewModelProvider.viewModelFlow,
                karooSystem.streamDatatypeIsVisible(dataTypeId)
            ) { viewModel, displayViewModel, visible ->
                ViewModels(viewModel, displayViewModel, visible)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            flow.filter { it.isVisible }.collect { (viewModel, displayViewModel) ->
                val bitmap = createBitmap(config.viewSize.first, config.viewSize.second)

                val canvas = Canvas(bitmap)
                val nightMode = isNightMode()

                val graphBounds = RectF(if (config.gridSize.first > 30) 35f else 0f, 15f, config.viewSize.first.toFloat() - 10f, config.viewSize.second.toFloat() - 30f)

                val poiLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }

                val incidentPaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.elevate4dark else R.color.elevate4)
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }

                val poiLinePaintDashed = Paint(poiLinePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)
                }

                val incidentLinePaintDashed = Paint(incidentPaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)
                }

                val backgroundStrokePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.black else R.color.white)
                    style = Paint.Style.STROKE
                    strokeWidth = poiLinePaint.strokeWidth + 5f
                }

                val backgroundStrokePaintDashed = Paint(backgroundStrokePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)
                }

                val axisStrokePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }

                val backgroundFillPaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.whiteBg else R.color.blackBg)
                    style = Paint.Style.FILL
                }

                val backgroundFillPaintInv = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.blackBg else R.color.whiteBg)
                    style = Paint.Style.FILL
                }

                val backgroundFillPaintInvSolid = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.black else R.color.white)
                    style = Paint.Style.FILL
                }

                val elevationFillPaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.elevDarkBg else R.color.elevBg)
                    style = Paint.Style.FILL
                }

                val currentLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

                val upcomingLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }

                val pastLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }

                val textPaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.FILL
                    textSize = 30f
                    textAlign = Paint.Align.LEFT
                }

                val textPaintInv = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.black else R.color.white)
                    style = Paint.Style.FILL
                    textSize = 30f
                    textAlign = Paint.Align.LEFT
                }

                val inversePaintFilter = Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { set(floatArrayOf(
                            -1f,  0f,  0f, 0f, 255f,
                            0f, -1f,  0f, 0f, 255f,
                            0f,  0f, -1f, 0f, 255f,
                            0f,  0f,  0f, 1f,   0f
                        )) }
                    )
                }

                val categoryPaints = ClimbCategory.entries.associateWith { category ->
                    Paint().apply {
                        color = applicationContext.getColor(category.colorRes)
                        style = Paint.Style.FILL
                    }
                }

                val zoomLevel = displayViewModel.zoomLevel

                val viewDistanceStart = if (zoomLevel == ZoomLevel.COMPLETE_ROUTE){
                    0.0f
                } else {
                    val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f
                    val displayedMeters = zoomLevel.getDistanceInMeters(viewModel.isImperial) ?: 0.0f

                    (distanceAlongRoute - displayedMeters * 0.1f).coerceAtLeast(0.0f)
                }
                val viewDistanceEnd = if (zoomLevel == ZoomLevel.COMPLETE_ROUTE){
                    viewModel.routeDistance ?: 0.0f
                } else {
                    val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f
                    val displayedMeters = zoomLevel.getDistanceInMeters(viewModel.isImperial) ?: 0.0f

                    (distanceAlongRoute + displayedMeters * 0.9f).coerceAtMost(viewModel.routeDistance ?: 0.0f)
                }
                val viewRange = viewDistanceStart..viewDistanceEnd

                val minElevation = (viewModel.sampledElevationData?.getMinimumElevationInRange(viewDistanceStart, viewDistanceEnd) ?: 0.0f).let { floor(it / 30.0f) * 30.0f }
                val maxElevation = (viewModel.sampledElevationData?.getMaximumElevationInRange(viewDistanceStart, viewDistanceEnd) ?: 0.0f).let { ceil(it / 100.0f) * 100.0f }

                if (viewModel.routeDistance == null) {
                    emitter.onNext(ShowCustomStreamState("No route loaded", if (isNightMode()) Color.WHITE else Color.BLACK))
                    Log.d(TAG, "Not drawing route graph: No route loaded")
                    emitter.updateView(glance.compose(context, DpSize.Unspecified) { Box(modifier = GlanceModifier.fillMaxSize()){} }.remoteViews)
                    return@collect
                }

                emitter.onNext(ShowCustomStreamState("", null))

                Log.d(TAG, "Drawing route graph with ${viewModel.routeDistance} and ${viewModel.sampledElevationData?.elevations?.size} elevation points, min $minElevation, max $maxElevation")

                var lastPixelFromLeft = 0.0f
                var firstPixelFromLeft = 0.0f
                var previousDrawnPixelsFromLeft = 0.0f
                var firstPixelsFromTop: Float? = null

                if (viewModel.sampledElevationData != null){
                    val elevationProfilePath = Path().apply {
                        for (i in 1 until viewModel.sampledElevationData.elevations.size){
                            val previousDistance = (i - 1) * viewModel.sampledElevationData.interval
                            val distance = i * viewModel.sampledElevationData.interval
                            if (distance !in viewRange) continue;

                            val pixelsFromLeft = remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                            val elevation = viewModel.sampledElevationData.elevations[i]

                            val pixelsFromTop = remap(elevation, maxElevation, minElevation, graphBounds.top, graphBounds.bottom)

                            if (firstPixelsFromTop == null){
                                val previousPixelsFromLeft = remap(previousDistance, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)
                                val previousElevation = viewModel.sampledElevationData.elevations[i - 1]
                                val previousPixelsFromTop = remap(previousElevation, maxElevation, minElevation, graphBounds.top, graphBounds.bottom)

                                moveTo(previousPixelsFromLeft, previousPixelsFromTop)
                                firstPixelFromLeft = previousPixelsFromLeft
                                firstPixelsFromTop = previousPixelsFromTop
                                previousDrawnPixelsFromLeft = pixelsFromLeft
                            }

                            if (pixelsFromLeft - previousDrawnPixelsFromLeft > 3){
                                lineTo(previousDrawnPixelsFromLeft, pixelsFromTop)
                                previousDrawnPixelsFromLeft = pixelsFromLeft
                            }

                            lastPixelFromLeft = pixelsFromLeft
                        }
                    }

                    canvas.drawPath(elevationProfilePath, pastLinePaint)

                    val filledPath = Path(elevationProfilePath)
                    filledPath.lineTo(lastPixelFromLeft, graphBounds.bottom)
                    filledPath.lineTo(firstPixelFromLeft, graphBounds.bottom)
                    filledPath.close()

                    if (displayViewModel.zoomLevel != ZoomLevel.TWO_UNITS){
                        if (viewModel.climbs != null){
                            // Sort climbs so that harder climbs will be drawn on top if they overlap
                            val climbsSortedByCategory = viewModel.climbs.sortedByDescending { it.category.number }

                            climbsSortedByCategory.forEach { climb ->
                                var climbStartPixelsFromLeft = remap(climb.startDistance, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)
                                var climbEndPixelsFromLeft = remap(climb.endDistance, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                                if (climbEndPixelsFromLeft > climbStartPixelsFromLeft){
                                    while(climbEndPixelsFromLeft - climbStartPixelsFromLeft < 6){
                                        climbStartPixelsFromLeft -= 1
                                        climbEndPixelsFromLeft += 1
                                    }
                                }

                                climbStartPixelsFromLeft = climbStartPixelsFromLeft.coerceIn(graphBounds.left, graphBounds.right)
                                climbEndPixelsFromLeft = climbEndPixelsFromLeft.coerceIn(graphBounds.left, graphBounds.right)

                                val clipRect = RectF(climbStartPixelsFromLeft, graphBounds.top, climbEndPixelsFromLeft, graphBounds.bottom)

                                canvas.withClip(clipRect){
                                    canvas.withClip(filledPath) {
                                        categoryPaints[climb.category]?.let { paint ->
                                            canvas.drawRect(clipRect, paint)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        for (i in 0 until viewModel.sampledElevationData.elevations.size-1){
                            val distance = i * viewModel.sampledElevationData.interval
                            if (distance !in viewRange) continue

                            val incline = (viewModel.sampledElevationData.elevations[i+1] - viewModel.sampledElevationData.elevations[i]) / viewModel.sampledElevationData.interval
                            val inclineIndicator = getInclineIndicatorColor(incline * 100) ?: continue

                            val inclineColor = applicationContext.getColor(inclineIndicator)

                            val clipRect = RectF(
                                remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right).roundToInt().toFloat(),
                                graphBounds.top,
                                remap(distance + viewModel.sampledElevationData.interval, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right).roundToInt() + 1f,
                                graphBounds.bottom
                            )

                            canvas.withClip(clipRect){
                                canvas.withClip(filledPath) {
                                    canvas.drawRect(clipRect, Paint().apply {
                                        color = inclineColor
                                        style = Paint.Style.FILL
                                    })
                                }
                            }
                        }
                    }

                    if (viewModel.distanceAlongRoute != null){
                        val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                        canvas.withClip(0f, 0f, distanceAlongRoutePixelsFromLeft, config.viewSize.second.toFloat()){
                            canvas.withClip(filledPath) {
                                canvas.drawRect(graphBounds, elevationFillPaint)
                            }

                            canvas.drawPath(elevationProfilePath, upcomingLinePaint)
                        }
                    }
                }

                if (viewModel.distanceAlongRoute != null){
                    val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                    canvas.drawLine(distanceAlongRoutePixelsFromLeft, 0f, distanceAlongRoutePixelsFromLeft, graphBounds.bottom, backgroundStrokePaint)
                    canvas.drawLine(distanceAlongRoutePixelsFromLeft, 0f, distanceAlongRoutePixelsFromLeft, graphBounds.bottom, currentLinePaint)
                }

                if (viewModel.poiDistances != null){
                    val previousPOIs = mutableSetOf<RectF>()

                    val allPoisInRange = viewModel.poiDistances.values.flatten().filter { nearestPoint ->
                        nearestPoint.distanceFromRouteStart in viewRange
                    }

                    viewModel.poiDistances.forEach { (poi, distances) ->
                        val poisInRange = distances.filter { nearestPoint ->
                            nearestPoint.distanceFromRouteStart in viewRange
                        }

                        poisInRange.forEach { nearestPoint ->
                            val distanceFromRouteStart = nearestPoint.distanceFromRouteStart
                            val text = poi.symbol.name ?: "X"
                            val textWidth = textPaintInv.measureText(text)
                            val pixelsFromLeft = remap(distanceFromRouteStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)
                            val maxPois = if (config.gridSize.first <= 30) 2 else 4
                            val drawLabel = allPoisInRange.size <= maxPois && config.gridSize.first > 30

                            val textStartFromLeft = (pixelsFromLeft - textWidth / 2)
                                .coerceIn(graphBounds.left, (graphBounds.right - textWidth - 5f).coerceAtLeast(graphBounds.left))

                            val currentPOI = RectF(
                                textStartFromLeft - 5,
                                graphBounds.top,
                                textStartFromLeft + textWidth + 5,
                                30f + graphBounds.top
                            )

                            val currentPOIBottomIcon = RectF(
                                pixelsFromLeft - 35f / 2 - 5f,
                                graphBounds.bottom - 40f,
                                pixelsFromLeft + 35f / 2 + 5f,
                                graphBounds.bottom
                            )

                            previousPOIs.forEach { previousPOI ->
                                if (RectF.intersects(currentPOI, previousPOI)){
                                    currentPOI.offset(0f, 35f)
                                }
                            }

                            canvas.drawLine(pixelsFromLeft, if (drawLabel) currentPOI.bottom else graphBounds.top,
                                pixelsFromLeft, graphBounds.bottom, backgroundStrokePaintDashed)
                            canvas.drawLine(pixelsFromLeft, if (drawLabel) currentPOI.bottom else graphBounds.top,
                                pixelsFromLeft, graphBounds.bottom, if (poi.type == PoiType.INCIDENT) incidentLinePaintDashed else poiLinePaintDashed)

                            if (drawLabel){
                                canvas.drawRoundRect(currentPOI, 5f, 5f, backgroundFillPaint)
                                Log.i(TAG, "Drawing text $text at $textStartFromLeft, $currentPOI")

                                canvas.drawText(text, textStartFromLeft, 20f + currentPOI.top + 5f, textPaintInv)
                            }

                            if (config.gridSize.first > 30){
                                canvas.drawRoundRect(currentPOIBottomIcon, 5f, 5f, backgroundFillPaint)

                                val icon = mapPoiToIcon(poi.symbol.type)
                                val sizeX = 35
                                val sizeY = 35
                                val bitmap = AppCompatResources.getDrawable(context, icon)?.toBitmap(sizeX, sizeY)

                                val iconPaint = if (!isNightMode()) inversePaintFilter else textPaint

                                if (bitmap != null) canvas.drawBitmap(bitmap, currentPOIBottomIcon.left + 5f, currentPOIBottomIcon.top, iconPaint)
                            }
                            

                            previousPOIs.add(currentPOI)
                        }
                    }
                }

                run  {
                    // Ticks on X axis
                    val unitFactor = if (!viewModel.isImperial) 1000.0f else 1609.344f
                    val ticks = if (config.gridSize.first == 60) 5 else 2
                    val tickInterval = (viewDistanceEnd - viewDistanceStart) / ticks

                    for (i in 0..ticks){
                        canvas.drawLine(
                            remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right),
                            graphBounds.bottom,
                            remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right),
                            config.viewSize.second.toFloat() - 5f,
                            axisStrokePaint
                        )

                        val progress = ((viewDistanceStart + tickInterval * i) / unitFactor)

                        val text = if (zoomLevel == ZoomLevel.TWO_UNITS){
                            String.format(Locale.US, "%.1f", progress)
                        } else "${progress.toInt()}"

                        val textPos = (remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right) + 5f)
                            .coerceAtMost(config.viewSize.first.toFloat() - textPaint.measureText(text) - 5f)

                        canvas.drawRoundRect(
                            textPos - 3f,
                            graphBounds.bottom + 3f,
                            textPos + textPaint.measureText(text) + 3f,
                            config.viewSize.second.toFloat() - 5f,
                            5f, 5f,
                            backgroundFillPaintInv
                        )

                        canvas.drawText(
                            text,
                            textPos,
                            config.viewSize.second - 10f + 5f,
                            textPaint
                        )
                    }
                }

                if (config.gridSize.first > 30) {
                    // Ticks on Y axis
                    val unitFactor = if (!viewModel.isImperial) 1.0f else (1 / 3.28084f)
                    val ticks = if (config.gridSize.second < 30) 2 else 4
                    val tickInterval = ceil((maxElevation - minElevation) / ticks / unitFactor / 50.0f) * 50.0f * unitFactor

                    for (i in 0..ticks){
                        val y = remap(minElevation + tickInterval * i, maxElevation, minElevation, graphBounds.top, graphBounds.bottom).toFloat()

                        canvas.drawLine(
                            0f,
                            y,
                            graphBounds.left,
                            y,
                            axisStrokePaint
                        )

                        val ele = (minElevation + tickInterval * i).toInt()
                        val eleText = if (maxElevation - minElevation > 1_500) "${ele / 1000}k" else ele.toString()
                        val textStartFromLeft = 10f
                        val textWidth = textPaint.measureText(eleText)

                        canvas.drawRoundRect(
                            textStartFromLeft - 5,
                            remap(minElevation + tickInterval * i, maxElevation, minElevation, graphBounds.top, graphBounds.bottom) - 17.5f,
                            textStartFromLeft + textWidth + 5,
                            remap(minElevation + tickInterval * i, maxElevation, minElevation, graphBounds.top, graphBounds.bottom) + 12.5f,
                            5f, 5f,
                            backgroundFillPaintInvSolid
                        )

                        canvas.drawText(
                            eleText,
                            10f,
                            remap(minElevation + tickInterval * i, maxElevation, minElevation, graphBounds.top, graphBounds.bottom) + 5f,
                            textPaint
                        )
                    }
                }

                val result = glance.compose(context, DpSize.Unspecified) {
                    var modifier = GlanceModifier.fillMaxSize()

                    if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<ChangeZoomLevelAction>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>("action_type") to "zoom"
                        )
                    ))

                    Box(modifier = GlanceModifier.fillMaxSize()){
                        Image(ImageProvider(bitmap), "Route Graph", modifier = modifier)
                    }
                }
                emitter.updateView(result.remoteViews)
            }
        }
        emitter.setCancellable {
            Log.d(TAG, "Stopping routegraph view with $emitter")
            configJob.cancel()
            viewJob.cancel()
        }
    }

    private fun previewFlow() = flow {
        while (true){
            val distanceAlongRoute = (0..50_000).random()
            val routeGraphViewModel = RouteGraphViewModel(50_000.0f, distanceAlongRoute.toFloat(), null,
                mapOf(
                    POI(Symbol.POI("checkpoint", 0.0, 0.0, name = "Checkpoint", type = "control")) to listOf(NearestPoint(null, 20.0f, 35_000.0f, null)),
                    POI(Symbol.POI("test", 0.0, 0.0, name = "Toilet", type = "restroom")) to listOf(NearestPoint(null, 20.0f, 5_000.0f, null)),
                    POI(Symbol.POI("refuel", 0.0, 0.0, name = "Refuel", type = "food")) to listOf(NearestPoint(null, 20.0f, 20_000.0f, null))
                ),
                sampledElevationData = SparseElevationData(
                    floatArrayOf(0f, 10_000f, 20_000f, 30_000f, 40_000f, 50_000f),
                    floatArrayOf(0f, 1000f, 500f, 400f, 450f, 0f)
                ).toSampledElevationData(100.0f)
            )
            val routeGraphDisplayViewModel = RouteGraphDisplayViewModel()
            val viewModels = ViewModels(routeGraphViewModel, routeGraphDisplayViewModel, true)

            emit(viewModels)

            delay(5_000)
        }
    }
}