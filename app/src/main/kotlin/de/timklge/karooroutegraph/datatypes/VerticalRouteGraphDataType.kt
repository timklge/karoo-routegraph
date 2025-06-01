package de.timklge.karooroutegraph.datatypes

import ClimbCategory
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.DrawableRes
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
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SparseElevationData
import de.timklge.karooroutegraph.ZoomLevel
import de.timklge.karooroutegraph.datatypes.minimap.ChangeZoomLevelAction
import de.timklge.karooroutegraph.datatypes.minimap.mapPoiToIcon
import de.timklge.karooroutegraph.distanceIsZero
import de.timklge.karooroutegraph.distanceToString
import de.timklge.karooroutegraph.getInclineIndicatorColor
import de.timklge.karooroutegraph.streamDatatypeIsVisible
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class VerticalRouteGraphDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "verticalroutegraph") {
    private val glance = GlanceRemoteViews()

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    data class StreamData(val routeGraphViewModel: RouteGraphViewModel, val routeGraphDisplayViewModel: RouteGraphDisplayViewModel, val profile: UserProfile, val isVisible: Boolean)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting route view with $emitter")

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
                karooSystem.streamUserProfile(),
                karooSystem.streamDatatypeIsVisible(dataTypeId)
            ) { viewModel, displayViewModel, profile, isVisible ->
                StreamData(viewModel, displayViewModel, profile, isVisible)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            flow.filter { it.isVisible }.collect { (viewModel, displayViewModel, userProfile) ->
                val bitmap = createBitmap(config.viewSize.first, config.viewSize.second)

                val canvas = Canvas(bitmap)
                val nightMode = isNightMode()

                val graphBounds = RectF(15f, 5f, 90f, config.viewSize.second.toFloat() - 25f)

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
                    textSize = 35f
                    textAlign = Paint.Align.LEFT
                }

                val textPaintBold = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.FILL
                    textSize = 40f
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT_BOLD
                }

                val textPaintInv = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.black else R.color.white)
                    style = Paint.Style.FILL
                    textSize = 40f
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

                var lastProgressPixels = 0.0f
                var firstProgressPixels = 0.0f
                var previousDrawnProgressPixels = 0.0f
                var firstElevationPixels: Float? = null

                data class TextDrawCommand(val x: Float, val y: Float, val text: String, val paint: Paint, val importance: Int = 10,
                                           /** If set, draws this text over the original text */
                                           val overdrawText: String? = null,
                                           val overdrawPaint: Paint = paint,
                                           @DrawableRes val leadingIcon: Int? = null,)

                val textDrawCommands = mutableListOf<TextDrawCommand>()

                if (viewModel.sampledElevationData != null){
                    val elevationProfilePath = Path().apply {
                        for (i in 1 until viewModel.sampledElevationData.elevations.size){
                            val previousDistance = (i - 1) * viewModel.sampledElevationData.interval
                            val distance = i * viewModel.sampledElevationData.interval
                            if (distance !in viewRange) continue;

                            val progressPixels = remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                            val elevation = viewModel.sampledElevationData.elevations[i]

                            val elevationPixels = remap(elevation, maxElevation, minElevation, graphBounds.right, graphBounds.left)

                            if (firstElevationPixels == null){
                                val previousProgressPixels = remap(previousDistance, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)
                                val previousElevation = viewModel.sampledElevationData.elevations[i - 1]
                                val previousElevationPixels = remap(previousElevation, maxElevation, minElevation, graphBounds.right, graphBounds.left)

                                moveTo(previousElevationPixels, previousProgressPixels)
                                // moveTo(previousDrawnProgressPixels, elevationPixels)
                                firstProgressPixels = previousProgressPixels
                                firstElevationPixels = previousElevationPixels
                                previousDrawnProgressPixels = progressPixels
                            }

                            if (progressPixels - previousDrawnProgressPixels > 3){
                                lineTo(elevationPixels, previousDrawnProgressPixels)
                                // lineTo(previousDrawnProgressPixels, elevationPixels)
                                previousDrawnProgressPixels = progressPixels
                            }

                            lastProgressPixels = progressPixels
                        }
                    }

                    canvas.drawPath(elevationProfilePath, pastLinePaint)

                    val filledPath = Path(elevationProfilePath)
                    filledPath.lineTo(graphBounds.left, lastProgressPixels)
                    filledPath.lineTo(graphBounds.top, firstProgressPixels)
                    filledPath.close()

                    if (viewModel.climbs != null){
                        // Sort climbs so that harder climbs will be drawn on top if they overlap
                        val climbsSortedByCategory = viewModel.climbs.sortedByDescending { it.category.number }

                        climbsSortedByCategory.forEach { climb ->
                            var climbStartProgressPixels = remap(climb.startDistance, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)
                            var climbEndProgressPixels = remap(climb.endDistance, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                            if (climbEndProgressPixels > climbStartProgressPixels){
                                while(climbEndProgressPixels - climbStartProgressPixels < 6){
                                    climbStartProgressPixels -= 1
                                    climbEndProgressPixels += 1
                                }
                            }

                            val clampedClimbStartProgressPixels = climbStartProgressPixels.coerceIn(graphBounds.top, graphBounds.bottom)
                            val clampedClimbEndProgressPixels = climbEndProgressPixels.coerceIn(graphBounds.top, graphBounds.bottom)

                            val clipRect = RectF(graphBounds.left, clampedClimbStartProgressPixels, graphBounds.bottom, clampedClimbEndProgressPixels)

                            if (displayViewModel.zoomLevel != ZoomLevel.TWO_UNITS) {
                            canvas.withClip(clipRect) {
                                canvas.withClip(filledPath) {
                                    categoryPaints[climb.category]?.let { paint ->
                                        canvas.drawRect(clipRect, paint)
                                    }
                                }
                            }
                        }

                        val climbGain = distanceToString(climb.totalGain(viewModel.sampledElevationData).toFloat(), userProfile, true)
                        val climbLength = distanceToString(climb.length, userProfile, false)

                        val climbAverageIncline = (climb.getAverageIncline(viewModel.sampledElevationData) * 100).roundToInt()   // String.format(Locale.getDefault(), "%.1f", climb.getAverageIncline(viewModel.sampledElevationData) * 100)
                        val climbMaxIncline = climb.getMaxIncline(viewModel.sampledElevationData)
                        val climbMaxInclineLength = distanceToString(climbMaxIncline.end - climbMaxIncline.start, userProfile, false)

                        if (climb.category.number <= 3){
                            textDrawCommands.add(TextDrawCommand(graphBounds.right + 75, climbStartProgressPixels + 15f, "⛰ $climbGain, $climbLength", textPaint, climb.category.importance, "⛰", Paint(textPaint).apply {
                                color = applicationContext.getColor(climb.category.colorRes)
                            }))

                            val maxInclineString = if (climbMaxIncline.incline > climbAverageIncline) ", ${climbMaxIncline.incline}% $climbMaxInclineLength" else ""
                            textDrawCommands.add(TextDrawCommand(graphBounds.right + 75, climbStartProgressPixels + 16f, "⌀ ${climbAverageIncline}%${maxInclineString}", textPaint, climb.category.importance))
                        }
                    }
                }

                if (displayViewModel.zoomLevel == ZoomLevel.TWO_UNITS) {
                    for (i in 0 until viewModel.sampledElevationData.elevations.size-1){
                        val distance = i * viewModel.sampledElevationData.interval
                        if (distance !in viewRange) continue

                        val incline = (viewModel.sampledElevationData.elevations[i+1] - viewModel.sampledElevationData.elevations[i]) / viewModel.sampledElevationData.interval
                        val inclineIndicator = getInclineIndicatorColor(incline * 100) ?: continue

                        val inclineColor = applicationContext.getColor(inclineIndicator)

                        val clipRect = RectF(
                            graphBounds.left,
                            remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom).roundToInt().toFloat(),
                            graphBounds.right,
                            remap(distance + viewModel.sampledElevationData.interval, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom).roundToInt() + 1f,
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
                        val distanceAlongRouteProgressPixels = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                        canvas.withClip(0f, 0f, config.viewSize.second.toFloat(), distanceAlongRouteProgressPixels){
                            canvas.withClip(filledPath) {
                                canvas.drawRect(graphBounds, elevationFillPaint)
                            }

                            canvas.drawPath(elevationProfilePath, upcomingLinePaint)
                        }
                    }
                }

                if (viewModel.distanceAlongRoute != null && viewModel.routeDistance != null){
                    val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                    canvas.drawLine(0f, distanceAlongRoutePixelsFromLeft, config.viewSize.first.toFloat(), distanceAlongRoutePixelsFromLeft, backgroundStrokePaint)
                    canvas.drawLine(0f, distanceAlongRoutePixelsFromLeft, config.viewSize.first.toFloat(), distanceAlongRoutePixelsFromLeft, currentLinePaint)
                }

                if (viewModel.poiDistances != null && viewModel.routeDistance != null){
                    viewModel.poiDistances.entries.flatMap { (poi, distances) ->
                        distances.map { poi to it }.filter { it.second.distanceFromRouteStart in viewRange }
                    }.sortedBy { (_, distance) ->
                        distance.distanceFromRouteStart
                    }.forEach { (poi, nearestPoint) ->
                        val distanceFromRouteStart = nearestPoint.distanceFromRouteStart
                        val text = poi.name ?: ""
                        val progressPixels = remap(distanceFromRouteStart, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                        canvas.drawLine(graphBounds.left, progressPixels, config.viewSize.first.toFloat(), progressPixels, backgroundStrokePaintDashed)
                        canvas.drawLine(graphBounds.left, progressPixels, config.viewSize.first.toFloat(), progressPixels, poiLinePaintDashed)

                        textDrawCommands.add(TextDrawCommand(graphBounds.right + 75f + 40f, progressPixels + 15f, text, textPaintBold, 11, leadingIcon = mapPoiToIcon(poi.type)))

                        if (viewModel.distanceAlongRoute != null && nearestPoint.distanceFromRouteStart > viewModel.distanceAlongRoute){
                            val distanceMeters = nearestPoint.distanceFromRouteStart - viewModel.distanceAlongRoute
                            var distanceStr = "In ${distanceToString(distanceMeters, userProfile, false)}"

                            val elevationMetersRemaining = viewModel.sampledElevationData?.getTotalClimb(viewModel.distanceAlongRoute, nearestPoint.distanceFromRouteStart)
                            if (elevationMetersRemaining != null && !distanceIsZero(elevationMetersRemaining.toFloat(), userProfile)) {
                                distanceStr += " ↗ ${distanceToString(elevationMetersRemaining.toFloat(), userProfile, true)}"
                            }

                            textDrawCommands.add(TextDrawCommand(graphBounds.right + 75, progressPixels + 15f, distanceStr, textPaint, 11))
                        }
                    }

                    canvas.drawRect(
                        graphBounds.right + 1f, graphBounds.top,
                        config.viewSize.first.toFloat(), config.viewSize.second.toFloat(),
                        backgroundFillPaintInv
                    )
                }

                val occupiedRanges = mutableListOf<ClosedFloatingPointRange<Float>>()
                val textHeight = 40f

                textDrawCommands
                    .filter { it.y < config.viewSize.second }
                    .sortedWith(compareByDescending<TextDrawCommand> { it.importance }.thenBy { it.y })
                    .forEach { cmd ->
                        var currentY = cmd.y
                        // While there is an overlap and text still fits, shift text down.
                        while (occupiedRanges.any { range -> currentY < range.endInclusive && (currentY + textHeight) > range.start }
                            && currentY + textHeight < config.viewSize.second) {
                            val maxConflictEnd = occupiedRanges
                                .filter { range -> currentY < range.endInclusive && (currentY + textHeight) > range.start }
                                .maxOf { it.endInclusive }
                            currentY = maxConflictEnd
                        }

                        var shouldDrawLabel = true
                        // Assuming climb labels have importance < 10 and POI labels have importance 11 or higher.
                        if (cmd.importance < 10 && viewModel.poiDistances != null) {
                            val climbStartProgressPixels = cmd.y - 15f

                            poiLoop@ for ((poi, nearestPointList) in viewModel.poiDistances.entries) {
                                for (nearestPoint in nearestPointList) {
                                    val poiDistanceOnRoute = nearestPoint.distanceFromRouteStart
                                    if (poiDistanceOnRoute in viewRange) {
                                        val poiLineYOnGraph = remap(poiDistanceOnRoute, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom)

                                        // If the climb starts before the POI line (smaller y-value on graph)
                                        // and the label's current top (currentY) is now below the POI line (larger y-value on graph)
                                        if (climbStartProgressPixels < poiLineYOnGraph && currentY > poiLineYOnGraph) {
                                            shouldDrawLabel = false
                                            break@poiLoop // Exit all POI checks for this command
                                        }
                                    }
                                }
                            }
                        }

                        if (shouldDrawLabel && currentY + textHeight <= config.viewSize.second) {
                            canvas.drawText(cmd.text, cmd.x, currentY, cmd.paint)
                            if (cmd.overdrawText != null){
                                canvas.drawText(cmd.overdrawText, cmd.x, currentY, cmd.overdrawPaint)
                            }
                            if (cmd.leadingIcon != null){
                                val sizeX = 35
                                val sizeY = 35

                                val iconPaint = if (isNightMode()) inversePaintFilter else textPaint;

                                val bitmap = AppCompatResources.getDrawable(context, cmd.leadingIcon)?.toBitmap(sizeX, sizeY)
                                if (bitmap != null) canvas.drawBitmap(bitmap, cmd.x - 40f, currentY - sizeY + 2.5f, iconPaint)
                            }
                            occupiedRanges.add(currentY..(currentY + textHeight))
                        }
                    }

                val unitFactor = if (!viewModel.isImperial) 1000.0f else 1609.344f
                val ticks = if (config.gridSize.first == 60) 5 else 2
                val tickInterval = (viewDistanceEnd - viewDistanceStart) / ticks

                for (i in 0..ticks){
                    canvas.drawLine(
                        graphBounds.right,
                        remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom),
                        graphBounds.right + 10,
                        remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom),
                        axisStrokePaint
                    )

                    val progress = ((viewDistanceStart + tickInterval * i) / unitFactor)
                    val text = if (zoomLevel == ZoomLevel.TWO_UNITS){
                        String.format(Locale.US, "%.1f", progress)
                    } else "${progress.toInt()}"

                    val textPos = (remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.top, graphBounds.bottom))

                    canvas.drawText(
                        text,
                        graphBounds.right + 15f,
                        (textPos + 15f).coerceAtMost(graphBounds.bottom - 5f),
                        textPaint
                    )
                }

                val result = glance.compose(context, DpSize.Unspecified) {
                    var modifier = GlanceModifier.fillMaxSize()

                    if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<ChangeZoomLevelAction>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>("action_type") to "zoom"
                        )
                    ))

                    Box(modifier = modifier) {
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
                    Symbol.POI("checkpoint", 0.0, 0.0, name = "Checkpoint", type = "control") to listOf(NearestPoint(null, 20.0f, 35_000.0f, null)),
                    Symbol.POI("test", 0.0, 0.0, name = "Toilet", type = "restroom") to listOf(NearestPoint(null, 20.0f, 5_000.0f, null)),
                    Symbol.POI("refuel", 0.0, 0.0, name = "Refuel", type = "food") to listOf(NearestPoint(null, 20.0f, 20_000.0f, null))
                ),
                sampledElevationData = SparseElevationData(
                    floatArrayOf(0f, 10_000f, 20_000f, 30_000f, 40_000f, 50_000f),
                    floatArrayOf(0f, 1000f, 500f, 400f, 450f, 0f)
                ).toSampledElevationData(100.0f)
            )
            val routeGraphDisplayViewModel = RouteGraphDisplayViewModel()
            val streamData = StreamData(routeGraphViewModel, routeGraphDisplayViewModel, karooSystem.streamUserProfile().first(), true)

            emit(streamData)

            delay(5_000)
        }
    }
}

