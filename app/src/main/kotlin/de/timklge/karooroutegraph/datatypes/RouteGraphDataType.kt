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
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import de.timklge.karooroutegraph.ChangeZoomLevelAction
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.NearestPoint
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SparseElevationData
import de.timklge.karooroutegraph.ZoomLevel
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

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

    data class ViewModels(val routeGraphViewModel: RouteGraphViewModel, val routeGraphDisplayViewModel: RouteGraphDisplayViewModel)

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
            viewModelProvider.viewModelFlow.combine(displayViewModelProvider.viewModelFlow) { viewModel, displayViewModel ->
                ViewModels(viewModel, displayViewModel)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            flow.collect { (viewModel, displayViewModel) ->
                val bitmap = createBitmap(config.viewSize.first, config.viewSize.second)

                val canvas = Canvas(bitmap)
                val nightMode = isNightMode()

                val graphBounds = RectF(35f, 15f, config.viewSize.first.toFloat() - 10f, config.viewSize.second.toFloat() - 30f)

                val poiLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

                val poiLinePaintDashed = Paint(poiLinePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                }

                val backgroundStrokePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.black else R.color.white)
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                }

                val backgroundStrokePaintDashed = Paint(backgroundStrokePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
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

                if (viewModel.sampledElevationData == null) {
                    emitter.onNext(ShowCustomStreamState("No elevation data downloaded", if (isNightMode()) Color.WHITE else Color.BLACK))
                    Log.d(TAG, "Not drawing route graph: No route loaded")
                    emitter.updateView(glance.compose(context, DpSize.Unspecified) { Box(modifier = GlanceModifier.fillMaxSize()){} }.remoteViews)
                    return@collect
                }

                emitter.onNext(ShowCustomStreamState("", null))

                Log.d(TAG, "Drawing route graph with ${viewModel.routeDistance} and ${viewModel.sampledElevationData.elevations.size} elevation points, min $minElevation, max $maxElevation")

                var lastPixelFromLeft = 0.0f
                var firstPixelFromLeft = 0.0f
                var previousDrawnPixelsFromLeft = 0.0f
                var firstPixelsFromTop: Float? = null

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

                if (viewModel.distanceAlongRoute != null){
                    val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                    canvas.withClip(0f, 0f, distanceAlongRoutePixelsFromLeft, config.viewSize.second.toFloat()){
                        canvas.withClip(filledPath) {
                            canvas.drawRect(graphBounds, elevationFillPaint)
                        }

                        canvas.drawPath(elevationProfilePath, upcomingLinePaint)
                    }
                }

                if (viewModel.distanceAlongRoute != null && viewModel.routeDistance != null){
                    val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                    canvas.drawLine(distanceAlongRoutePixelsFromLeft, 0f, distanceAlongRoutePixelsFromLeft, graphBounds.bottom, backgroundStrokePaint)
                    canvas.drawLine(distanceAlongRoutePixelsFromLeft, 0f, distanceAlongRoutePixelsFromLeft, graphBounds.bottom, currentLinePaint)
                }

                if (viewModel.poiDistances != null && viewModel.routeDistance != null){
                    val previousPOIs = mutableSetOf<RectF>()

                    viewModel.poiDistances.forEach { (poi, distances) ->
                        distances.filter { nearestPoint ->
                            nearestPoint.distanceFromRouteStart in viewRange
                        }.forEach { nearestPoint ->
                            val distanceFromRouteStart = nearestPoint.distanceFromRouteStart
                            val text = poi.name ?: ""
                            val textWidth = textPaintInv.measureText(text)
                            val pixelsFromLeft = remap(distanceFromRouteStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right)

                            val textStartFromLeft = (pixelsFromLeft - textWidth / 2)
                                .coerceIn(graphBounds.left, (graphBounds.right - textWidth - 5f))

                            val currentPOI = RectF(
                                textStartFromLeft - 5,
                                graphBounds.top,
                                textStartFromLeft + textWidth + 5,
                                30f + graphBounds.top
                            )

                            previousPOIs.forEach { previousPOI ->
                                if (RectF.intersects(currentPOI, previousPOI)){
                                    currentPOI.offset(0f, 35f)
                                }
                            }

                            canvas.drawLine(pixelsFromLeft, currentPOI.bottom,
                                pixelsFromLeft, graphBounds.bottom, backgroundStrokePaintDashed)
                            canvas.drawLine(pixelsFromLeft, currentPOI.bottom,
                                pixelsFromLeft, graphBounds.bottom, poiLinePaintDashed)

                            canvas.drawRoundRect(
                                currentPOI,
                                5f, 5f,
                                backgroundFillPaint
                            )
                            Log.i(TAG, "Drawing text $text at $textStartFromLeft, $currentPOI")

                            canvas.drawText(text,
                                textStartFromLeft, 20f + currentPOI.top + 2.5f, textPaintInv)

                            previousPOIs.add(currentPOI)
                        }
                    }
                }

                // Ticks on X axis
                if (viewModel.routeDistance != null){
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

                        val textPos = (remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.left, graphBounds.right).toFloat() + 5f)
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

                val result = glance.compose(context, DpSize.Unspecified) {
                    Box(modifier = GlanceModifier.fillMaxSize().clickable(actionRunCallback(ChangeZoomLevelAction::class.java))){
                        Image(ImageProvider(bitmap), "Route Graph", modifier = GlanceModifier.fillMaxSize())
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
                    Symbol.POI("checkpoint", 0.0, 0.0, name = "Checkpoint") to listOf(NearestPoint(null, 20.0f, 35_000.0f, null)),
                    Symbol.POI("test", 0.0, 0.0, name = "Toilet") to listOf(NearestPoint(null, 20.0f, 13_000.0f, null)),
                    Symbol.POI("refuel", 0.0, 0.0, name = "Refuel") to listOf(NearestPoint(null, 20.0f, 15_000.0f, null))
                ),
                sampledElevationData = SparseElevationData(
                    floatArrayOf(0f, 10_000f, 20_000f, 30_000f, 40_000f, 50_000f),
                    floatArrayOf(0f, 1000f, 500f, 400f, 450f, 0f)
                ).toSampledElevationData(100.0f)
            )
            val routeGraphDisplayViewModel = RouteGraphDisplayViewModel()
            val viewModels = ViewModels(routeGraphViewModel, routeGraphDisplayViewModel)

            emit(viewModels)

            delay(5_000)
        }
    }
}