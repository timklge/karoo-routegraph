package de.timklge.karooroutegraph.datatypes

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import de.timklge.karooroutegraph.ClimbCategory
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.NearestPoint
import de.timklge.karooroutegraph.POI
import de.timklge.karooroutegraph.POIActivity
import de.timklge.karooroutegraph.PoiType
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphDisplayViewModel
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SparseElevationData
import de.timklge.karooroutegraph.ZoomLevel
import de.timklge.karooroutegraph.datatypes.minimap.ChangeVerticalZoomLevelAction
import de.timklge.karooroutegraph.datatypes.minimap.mapPoiToIcon
import de.timklge.karooroutegraph.distanceIsZero
import de.timklge.karooroutegraph.distanceToString
import de.timklge.karooroutegraph.getInclineIndicatorColor
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.streamDatatypeIsVisible
import de.timklge.karooroutegraph.streamSettings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class VerticalRouteGraphDataType(
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "verticalroutegraph") {
    private val glance = GlanceRemoteViews()

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    // Extension function to check if two ranges overlap
    private fun ClosedFloatingPointRange<Float>.overlaps(other: ClosedFloatingPointRange<Float>): Boolean {
        return this.start < other.endInclusive && this.endInclusive > other.start
    }

    /**
     * Wraps text to fit within the specified maximum width
     * @param text The text to wrap
     * @param paint The paint object used to measure text
     * @param maxWidth Maximum width in pixels
     * @param maxLines Maximum number of lines to return (if text exceeds, it will be truncated)
     * @return List of wrapped text lines
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (paint.measureText(text) <= maxWidth) {
            return listOf(text)
        }

        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"

            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = word
                } else {
                    // Single word is too long, break it character by character
                    var partialWord = ""
                    for (char in word) {
                        val testChar = "$partialWord$char"
                        if (paint.measureText(testChar) <= maxWidth) {
                            partialWord = testChar
                        } else {
                            if (partialWord.isNotEmpty()) {
                                lines.add(partialWord)
                                partialWord = char.toString()
                            } else {
                                // Even single character is too wide, just add it
                                lines.add(char.toString())
                            }
                        }
                    }
                    if (partialWord.isNotEmpty()) {
                        currentLine = partialWord
                    }
                }
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        if (lines.size > maxLines) {
            val capped = lines.take(maxLines).toMutableList()
            capped[maxLines - 1] = capped[maxLines - 1].trimEnd() + "..." // Add ellipsis to last line
            return capped
        }

        return lines.ifEmpty { listOf(text) }
    }

    data class StreamData(val routeGraphViewModel: RouteGraphViewModel,
                          val routeGraphDisplayViewModel: RouteGraphDisplayViewModel,
                          val profile: UserProfile,
                          val settings: RouteGraphSettings,
                          val isVisible: Boolean,
                          val radarLaneIsVisible: Boolean)

    data class TextDrawCommand(val x: Float, val y: Float, val text: String, val paint: Paint, val importance: Int = 10,
                               /** If set, draws this text over the original text */
                               val overdrawText: String? = null,
                               val overdrawPaint: Paint = paint,
                               @DrawableRes val leadingIcon: Int? = null,
                               /** Maximum width for text wrapping in pixels */
                               val maxWidth: Float? = null)

    data class TextDrawCommandGroup(val commands: List<TextDrawCommand>)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting route view with $emitter")

        val viewId = UUID.randomUUID()

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
                karooSystemServiceProvider.karooSystemService.streamUserProfile(),
                context.streamSettings(karooSystemServiceProvider.karooSystemService),
                karooSystemServiceProvider.karooSystemService.streamDatatypeIsVisible(dataTypeId),
                karooSystemServiceProvider.streamRadarSwimLaneIsVisible()
            ) { data ->
                val viewModel = data[0] as RouteGraphViewModel
                val displayViewModel = data[1] as RouteGraphDisplayViewModel
                val profile = data[2] as UserProfile
                val settings = data[3] as RouteGraphSettings
                val isVisible = data[4] as Boolean
                val radarLaneIsVisible = data[5] as Boolean

                StreamData(viewModel, displayViewModel, profile, settings, isVisible, radarLaneIsVisible)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            flow.throttle(1_000L).filter { it.isVisible }.collect { (viewModel, displayViewModel, userProfile, settings, _, radarLaneIsVisibleValue) ->
                val bitmap = createBitmap(config.viewSize.first, config.viewSize.second)

                val canvas = Canvas(bitmap)
                val nightMode = isNightMode()

                val radarLaneIsVisible = radarLaneIsVisibleValue && settings.shiftForRadarSwimLane
                val left = 5f + (if (radarLaneIsVisible) 30f else 0f)
                val right = 80f + (if (radarLaneIsVisible) 30f else 0f)
                val graphBounds = RectF(left, 15f, right, config.viewSize.second.toFloat() - 25f)

                val poiLinePaint = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.white else R.color.black)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

                val incidentPaint = Paint().apply {
                    color = applicationContext.getColor(R.color.eleRed)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

                val poiLinePaintDashed = Paint(poiLinePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                }

                val incidentLinePaintDashed = Paint(incidentPaint).apply {
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

                val backgroundFillPaintInv = Paint().apply {
                    color = applicationContext.getColor(if(nightMode) R.color.blackBg else R.color.whiteBg)
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
                    textSize = 35f
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT_BOLD
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

                val zoomLevel = displayViewModel.verticalZoomLevel
                val viewDistanceStart = if (zoomLevel == ZoomLevel.CompleteRoute){
                    0.0f
                } else {
                    val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f
                    val displayedMeters = zoomLevel.getDistanceInMeters(viewModel, settings) ?: 0.0f

                    (distanceAlongRoute - displayedMeters * 0.1f).coerceAtLeast(0.0f)
                }
                val viewDistanceEnd = if (zoomLevel == ZoomLevel.CompleteRoute){
                    viewModel.routeDistance ?: 0.0f
                } else {
                    val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f
                    val displayedMeters = zoomLevel.getDistanceInMeters(viewModel, settings) ?: 0.0f

                    (distanceAlongRoute + displayedMeters * 0.9f).coerceAtMost(viewModel.routeDistance ?: 0.0f)
                }
                val viewRange = viewDistanceStart..viewDistanceEnd

                val minElevation = (viewModel.sampledElevationData?.getMinimumElevationInRange(viewDistanceStart, viewDistanceEnd) ?: 0.0f).let { floor(it / 30.0f) * 30.0f }
                val maxElevation = (viewModel.sampledElevationData?.getMaximumElevationInRange(viewDistanceStart, viewDistanceEnd) ?: 0.0f).let { ceil(it / 100.0f) * 100.0f }

                if (viewModel.routeDistance == null) {
                    emitter.onNext(ShowCustomStreamState(context.getString(R.string.no_route), if (isNightMode()) Color.WHITE else Color.BLACK))
                    Log.d(TAG, "Not drawing route graph: No route loaded")

                    emitter.updateView(glance.compose(context, DpSize.Unspecified) {
                        Box(modifier = GlanceModifier.fillMaxSize()){
                            if (config.gridSize.first > 30 && settings.showNavigateButtonOnGraphs) {
                                MapPinButton(config, isNightMode())
                            }
                        }
                    }.remoteViews)
                    return@collect
                }

                emitter.onNext(ShowCustomStreamState("", null))

                Log.d(TAG, "Drawing route graph with ${viewModel.routeDistance} and ${viewModel.sampledElevationData?.elevations?.size} elevation points, min $minElevation, max $maxElevation")

                var lastProgressPixels = 0.0f
                var firstProgressPixels = 0.0f
                var previousDrawnProgressPixels = 0.0f
                var firstElevationPixels: Float? = null

                val displayedViewRange = displayViewModel.verticalZoomLevel.getDistanceInMeters(viewModel, settings)
                val onlyHighlightClimbsAtZoomLeveLMeters = if (viewModel.isImperial) {
                    settings.elevationProfileZoomLevels.getOrNull(settings.onlyHighlightClimbsAtZoomLevel ?: Int.MAX_VALUE)?.let { it * 1609.344f }
                } else {
                    settings.elevationProfileZoomLevels.getOrNull(settings.onlyHighlightClimbsAtZoomLevel ?: Int.MAX_VALUE)?.let { it * 1000f }
                }
                val isZoomedIn = onlyHighlightClimbsAtZoomLeveLMeters == null || (displayedViewRange != null && displayedViewRange < onlyHighlightClimbsAtZoomLeveLMeters)

                val textDrawCommands = mutableListOf<TextDrawCommandGroup>()

                val unitFactor = if (!viewModel.isImperial) 1000.0f else 1609.344f
                val ticks = if (config.gridSize.first == 60) 5 else 2
                val tickInterval = (viewDistanceEnd - viewDistanceStart) / ticks

                var maxTickLabelWidth = 0f
                for (i in 0..ticks) {
                    val progress = ((viewDistanceStart + tickInterval * i) / unitFactor)
                    val text = if (displayedViewRange == null || displayedViewRange <= 2000.0f) {
                        String.format(Locale.US, "%.1f", progress)
                    } else "${progress.toInt()}"
                    maxTickLabelWidth = maxTickLabelWidth.coerceAtLeast(textPaint.measureText(text))
                }

                val axisLabelOffset = 15f
                val labelStartX = graphBounds.right + axisLabelOffset + maxTickLabelWidth + 15f

                if (viewModel.sampledElevationData != null){
                    val firstIndexInRange = floor(viewDistanceStart / viewModel.sampledElevationData.interval).toInt().coerceIn(0, viewModel.sampledElevationData.elevations.size - 1)
                    val lastIndexInRange = ceil(viewDistanceEnd / viewModel.sampledElevationData.interval).toInt().coerceIn(0, viewModel.sampledElevationData.elevations.size - 1)
                    val indexRange = firstIndexInRange until lastIndexInRange

                    val elevationProfilePath = Path().apply {
                        for (i in indexRange.first+1..<indexRange.last){
                            val previousDistance = (i - 1) * viewModel.sampledElevationData.interval
                            val distance = i * viewModel.sampledElevationData.interval

                            val progressPixels = remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)

                            val elevation = viewModel.sampledElevationData.elevations[i]

                            val elevationPixels = remap(elevation, maxElevation, minElevation, graphBounds.right, graphBounds.left)

                            if (firstElevationPixels == null){
                                val previousProgressPixels = remap(previousDistance, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)
                                val previousElevation = viewModel.sampledElevationData.elevations[i - 1]
                                val previousElevationPixels = remap(previousElevation, maxElevation, minElevation, graphBounds.right, graphBounds.left)

                                moveTo(previousElevationPixels, previousProgressPixels)
                                firstProgressPixels = previousProgressPixels
                                firstElevationPixels = previousElevationPixels
                                previousDrawnProgressPixels = progressPixels
                            }

                            if (abs(progressPixels - previousDrawnProgressPixels) > 3){
                                lineTo(elevationPixels, progressPixels)
                                // lineTo(previousDrawnProgressPixels, elevationPixels)
                                previousDrawnProgressPixels = progressPixels
                            }

                            lastProgressPixels = progressPixels
                        }
                    }

                    canvas.drawPath(elevationProfilePath, pastLinePaint)

                    val filledPath = Path(elevationProfilePath)
                    filledPath.lineTo(graphBounds.left, lastProgressPixels)
                    filledPath.lineTo(graphBounds.left, firstProgressPixels)
                    filledPath.close()

                    if (viewModel.climbs != null){
                        // Sort climbs so that harder climbs will be drawn on top if they overlap
                        val climbsSortedByCategory = viewModel.climbs.sortedByDescending { it.category.number }

                        climbsSortedByCategory.forEach { climb ->
                            var climbStartProgressPixels = remap(climb.startDistance.toFloat(), viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)
                            var climbEndProgressPixels = remap(climb.endDistance.toFloat(), viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)

                            while(abs(climbStartProgressPixels - climbEndProgressPixels) < 5){
                                climbStartProgressPixels -= 1
                                climbEndProgressPixels += 1
                            }

                            val clampedClimbStartProgressPixels = climbStartProgressPixels.coerceIn(graphBounds.top, graphBounds.bottom)
                            val clampedClimbEndProgressPixels = climbEndProgressPixels.coerceIn(graphBounds.top, graphBounds.bottom)

                            val clipRect = RectF(graphBounds.left, clampedClimbEndProgressPixels, graphBounds.right, clampedClimbStartProgressPixels)

                            if (!isZoomedIn) {
                                canvas.withClip(clipRect) {
                                    canvas.withClip(filledPath) {
                                        categoryPaints[climb.category]?.let { paint ->
                                            canvas.drawRect(clipRect, paint)
                                        }
                                    }
                                }
                            }

                            val isImperial = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                            val climbGain = distanceToString(climb.totalGain(viewModel.sampledElevationData).toFloat(), isImperial, true)
                            val climbLength = distanceToString(climb.length, isImperial, false)

                            val climbAverageIncline = (climb.getAverageIncline(viewModel.sampledElevationData) * 100).roundToInt()
                            val climbMaxIncline = climb.getMaxIncline(viewModel.sampledElevationData)
                            val maxInclineString = if (climbMaxIncline.incline > climbAverageIncline) ", ${climbMaxIncline.incline}% ${distanceToString(climbMaxIncline.end - climbMaxIncline.start, isImperial, false)}" else ""

                            if (climb.category.number < 3){
                                val availableWidth = config.viewSize.first.toFloat() - (labelStartX) - 20f
                                textDrawCommands.add(TextDrawCommandGroup(listOf(
                                    TextDrawCommand(labelStartX, climbStartProgressPixels + 15f, "⛰ $climbGain, $climbLength", textPaint, climb.category.importance, "⛰", Paint(textPaint).apply {
                                        color = applicationContext.getColor(climb.category.colorRes)
                                    }, maxWidth = availableWidth),
                                    TextDrawCommand(labelStartX, climbStartProgressPixels + 16f, "⌀ ${climbAverageIncline}%${maxInclineString}", textPaint, climb.category.importance-1, maxWidth = availableWidth)
                                )))
                            }
                        }
                    }

                    if (isZoomedIn) {
                        val viewedDistance = viewDistanceEnd - viewDistanceStart
                        val inclineStep = viewedDistance / (config.gridSize.second * 1.5f)
                        val steps = viewedDistance / inclineStep

                        for (i in 0..floor(steps).toInt()){
                            val distance = viewDistanceStart + i * inclineStep

                            val incline = viewModel.sampledElevationData.getMaximumInclineInRange(distance, distance + inclineStep)
                            val inclineIndicator = getInclineIndicatorColor(incline * 100) ?: continue

                            val inclineColor = applicationContext.getColor(inclineIndicator)

                            val clipRect = RectF(
                                graphBounds.left,
                                remap(distance, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top).roundToInt().toFloat(),
                                graphBounds.right,
                                remap(distance + inclineStep, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top).roundToInt().toFloat(),
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
                        val distanceAlongRouteProgressPixels = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)

                        canvas.withClip(0f, distanceAlongRouteProgressPixels, config.viewSize.first.toFloat(), config.viewSize.second.toFloat()){
                            canvas.withClip(filledPath) {
                                canvas.drawRect(graphBounds, elevationFillPaint)
                            }

                            canvas.drawPath(elevationProfilePath, upcomingLinePaint)
                        }
                    }
                }

                if (viewModel.distanceAlongRoute != null){
                    val distanceAlongRoutePixelsFromLeft = remap(viewModel.distanceAlongRoute, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)

                    canvas.drawLine(0f, distanceAlongRoutePixelsFromLeft, config.viewSize.first.toFloat(), distanceAlongRoutePixelsFromLeft, backgroundStrokePaint)
                    canvas.drawLine(0f, distanceAlongRoutePixelsFromLeft, config.viewSize.first.toFloat(), distanceAlongRoutePixelsFromLeft, currentLinePaint)
                }

                if (viewModel.poiDistances != null){
                    viewModel.poiDistances.entries.flatMap { (poi, distances) ->
                        distances.map { poi to it }.filter { it.second.distanceFromRouteStart in viewRange }
                    }.sortedBy { (_, distance) ->
                        distance.distanceFromRouteStart
                    }.forEach { (poi, nearestPoint) ->
                        val distanceFromRouteStart = nearestPoint.distanceFromRouteStart
                        val text = poi.symbol.name ?: ""
                        val progressPixels = remap(distanceFromRouteStart, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)
                        val labelPriority = if (poi.type == PoiType.INCIDENT) 10 else 11

                        canvas.drawLine(graphBounds.left, progressPixels, config.viewSize.first.toFloat(), progressPixels, backgroundStrokePaintDashed)
                        canvas.drawLine(graphBounds.left, progressPixels, config.viewSize.first.toFloat(), progressPixels, if (poi.type == PoiType.INCIDENT) incidentLinePaintDashed else poiLinePaintDashed)

                        val poiCommands = mutableListOf<TextDrawCommand>()
                        val availableWidth = config.viewSize.first.toFloat() - (labelStartX + 40f) - 20f
                        poiCommands.add(TextDrawCommand(labelStartX + 40f, progressPixels + 15f, text, textPaintBold, labelPriority, leadingIcon = mapPoiToIcon(poi.symbol.type), maxWidth = availableWidth))

                        val isImperial = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                        if (viewModel.distanceAlongRoute != null && nearestPoint.distanceFromRouteStart > viewModel.distanceAlongRoute){
                            val distanceMeters = nearestPoint.distanceFromRouteStart - viewModel.distanceAlongRoute
                            var distanceStr = "In ${distanceToString(distanceMeters, isImperial, false)}"

                            val elevationMetersRemaining = viewModel.sampledElevationData?.getTotalClimb(viewModel.distanceAlongRoute, nearestPoint.distanceFromRouteStart)
                            if (elevationMetersRemaining != null && !distanceIsZero(elevationMetersRemaining.toFloat(), userProfile)) {
                                distanceStr += " ↗ ${distanceToString(elevationMetersRemaining.toFloat(), isImperial, true)}"
                            }

                            val distanceAvailableWidth = config.viewSize.first.toFloat() - (labelStartX) - 20f
                            poiCommands.add(TextDrawCommand(labelStartX, progressPixels + 15f, distanceStr, textPaint, labelPriority, maxWidth = distanceAvailableWidth))
                        }

                        textDrawCommands.add(TextDrawCommandGroup(poiCommands))
                    }

                    canvas.drawRect(
                        graphBounds.right + 1f, 0f,
                        config.viewSize.first.toFloat(), config.viewSize.second.toFloat(),
                        backgroundFillPaintInv
                    )
                }

                // Group commands by their original Y position and importance
                data class TextGroup(val commands: List<TextDrawCommand>, val originalY: Float, val priority: Int, val originalIndex: Int)

                val textGroups = textDrawCommands.mapIndexed { index, group ->
                    // For groups with multiple commands, use the first command's Y and highest importance
                    val originalY = group.commands.firstOrNull()?.y ?: 0f
                    val priority = group.commands.maxOfOrNull { it.importance } ?: 0
                    TextGroup(group.commands, originalY, priority, index)
                }

                val occupiedRanges = mutableListOf<ClosedFloatingPointRange<Float>>() // Just track occupied ranges
                val textHeight = 40f
                val groupSpacing = 5f // Minimum spacing between groups

                // Process groups in original order to maintain label sequence
                textGroups
                    .filter { group -> group.commands.any { it.y >= 0 && it.y < config.viewSize.second } }
                    .sortedBy { it.originalIndex } // Maintain original order
                    .forEach { group ->
                        // Calculate the total height needed for this group considering text wrapping
                        var totalGroupHeight = 0f
                        group.commands.forEach { cmd ->
                            val availableWidth = cmd.maxWidth ?: (config.viewSize.first.toFloat() - cmd.x - 20f)
                            val wrappedLines = wrapText(cmd.text, cmd.paint, availableWidth)
                            totalGroupHeight += (wrappedLines.size * textHeight)
                        }

                        // Try different positions starting from the original position
                        var bestY = group.originalY
                        var foundPosition = false

                        // First, try the original position
                        val originalRange = bestY..(bestY + totalGroupHeight)
                        if (originalRange.start >= 0 && originalRange.endInclusive <= config.viewSize.second &&
                            !occupiedRanges.any { it.overlaps(originalRange) }) {
                            foundPosition = true
                        } else {
                            // Try moving up in small steps to find a free position
                            var testY = group.originalY
                            val step = textHeight / 2f // Move in smaller steps
                            var attempts = 0
                            val maxAttempts = ((group.originalY + totalGroupHeight) / step).toInt()

                            while (attempts < maxAttempts && !foundPosition) {
                                testY -= step
                                val testRange = testY..(testY + totalGroupHeight)

                                // Check if this position is valid (within bounds and no conflicts)
                                if (testRange.start >= 0 && testRange.endInclusive <= config.viewSize.second + textHeight / 2 &&
                                    !occupiedRanges.any { it.overlaps(testRange) }) {
                                    bestY = testY
                                    foundPosition = true
                                }
                                attempts++
                            }

                            // If moving up didn't work, try moving down
                            if (!foundPosition) {
                                testY = group.originalY
                                attempts = 0
                                val maxDownwardAttempts = ((config.viewSize.second - group.originalY) / step).toInt()

                                while (attempts < maxDownwardAttempts && !foundPosition) {
                                    testY += step
                                    val testRange = testY..(testY + totalGroupHeight)

                                    // Check if this position is valid (within bounds and no conflicts)
                                    if (testRange.start >= 0 && testRange.endInclusive <= config.viewSize.second &&
                                        !occupiedRanges.any { it.overlaps(testRange) }) {
                                        bestY = testY
                                        foundPosition = true
                                    }
                                    attempts++
                                }
                            }
                        }

                        // Additional check for climb labels vs POI lines (existing logic)
                        if (foundPosition && group.priority < 10 && viewModel.poiDistances != null) {
                            val climbStartProgressPixels = group.originalY - 15f

                            poiLoop@ for ((_, nearestPointList) in viewModel.poiDistances.entries) {
                                for (nearestPoint in nearestPointList) {
                                    val poiDistanceOnRoute = nearestPoint.distanceFromRouteStart
                                    if (poiDistanceOnRoute in viewRange) {
                                        val poiLineYOnGraph = remap(poiDistanceOnRoute, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top)

                                        // If the climb starts after the POI line and the label would be drawn above it
                                        if (climbStartProgressPixels > poiLineYOnGraph && bestY < poiLineYOnGraph) {
                                            foundPosition = false
                                            break@poiLoop
                                        }
                                    }
                                }
                            }
                        }

                        // Draw the group if we found a valid position
                        if (foundPosition) {
                            var currentCommandY = bestY

                            group.commands.forEach { cmd ->
                                // Calculate available width for text wrapping
                                val availableWidth = cmd.maxWidth ?: (config.viewSize.first.toFloat() - cmd.x - 20f)
                                val wrappedLines = wrapText(cmd.text, cmd.paint, availableWidth)

                                var lineY = currentCommandY
                                wrappedLines.forEachIndexed { lineIndex, line ->
                                    val textX = if (lineIndex > 0 && cmd.leadingIcon != null) {
                                        cmd.x - 40f // Icon starts at cmd.x - 40f, so text aligns with icon left edge
                                    } else {
                                        cmd.x
                                    }
                                    canvas.drawText(line, textX, lineY, cmd.paint)
                                    lineY += textHeight
                                }

                                if (cmd.overdrawText != null) {
                                    val overdrawLines = wrapText(cmd.overdrawText, cmd.overdrawPaint, availableWidth)
                                    lineY = currentCommandY
                                    overdrawLines.forEachIndexed { lineIndex, line ->
                                        val textX = if (lineIndex > 0 && cmd.leadingIcon != null) {
                                            cmd.x - 40f // Icon starts at cmd.x - 40f, so text aligns with icon left edge
                                        } else {
                                            cmd.x
                                        }
                                        canvas.drawText(line, textX, lineY, cmd.overdrawPaint)
                                        lineY += textHeight
                                    }
                                }

                                if (cmd.leadingIcon != null) {
                                    val sizeX = 35
                                    val sizeY = 35
                                    val iconPaint = if (isNightMode()) inversePaintFilter else textPaint
                                    val bitmap = AppCompatResources.getDrawable(context, cmd.leadingIcon)?.toBitmap(sizeX, sizeY)
                                    if (bitmap != null) canvas.drawBitmap(bitmap, cmd.x - 40f, currentCommandY - sizeY + 2.5f, iconPaint)
                                }

                                // Move Y position down by the height of all wrapped lines plus spacing
                                currentCommandY += (wrappedLines.size * textHeight)
                            }

                            // Mark this range as occupied (with some padding)
                            val occupiedRange = (bestY - groupSpacing)..(bestY + totalGroupHeight + groupSpacing)
                            occupiedRanges.add(occupiedRange)
                        }
                    }

                for (i in 0..ticks){
                    canvas.drawLine(
                        graphBounds.right,
                        remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top),
                        graphBounds.right + 10,
                        remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top),
                        axisStrokePaint
                    )

                    val progress = ((viewDistanceStart + tickInterval * i) / unitFactor)
                    val text = if (displayedViewRange == null || displayedViewRange <= 2000.0f) {
                        String.format(Locale.US, "%.1f", progress)
                    } else "${progress.toInt()}"

                    val textPos = (remap(tickInterval * i + viewDistanceStart, viewDistanceStart, viewDistanceEnd, graphBounds.bottom, graphBounds.top))

                    canvas.drawText(
                        text,
                        graphBounds.right + 15f,
                        (textPos - (textPaint.ascent() + textPaint.descent()) / 2f).coerceAtMost(graphBounds.bottom - 5f),
                        textPaint
                    )
                }

                val result = glance.compose(context, DpSize.Unspecified) {
                    var modifier = GlanceModifier.fillMaxSize()

                    if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<ChangeVerticalZoomLevelAction>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>("action_type") to "zoom",
                            ActionParameters.Key<String>("view_id") to viewId.toString()
                        )
                    ))

                    Box(modifier = modifier) {
                        Image(ImageProvider(bitmap), "Route Graph", modifier = GlanceModifier.fillMaxSize())
                    }

                    if (config.gridSize.first > 30 && settings.showNavigateButtonOnGraphs) {
                        MapPinButton(config, isNightMode())
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
        val settings = applicationContext.streamSettings(karooSystemServiceProvider.karooSystemService).first()

        while (true){
            val distanceAlongRoute = (0..50_000).random()
            val routeGraphViewModel = RouteGraphViewModel(50_000.0f, distanceAlongRoute.toFloat(), true, null, null,
                mapOf(
                    POI(Symbol.POI("checkpoint", 0.0, 0.0, name = "Very Long Checkpoint Name That Should Wrap", type = "control")) to listOf(NearestPoint(null, 20.0f, 35_000.0f, null)),
                    POI(Symbol.POI("test", 0.0, 0.0, name = "Toilet", type = "restroom")) to listOf(NearestPoint(null, 20.0f, 5_000.0f, null)),
                    POI(Symbol.POI("refuel", 0.0, 0.0, name = "Refuel Station", type = "food")) to listOf(NearestPoint(null, 20.0f, 20_000.0f, null))
                ),
                sampledElevationData = SparseElevationData(
                    floatArrayOf(0f, 10_000f, 20_000f, 30_000f, 40_000f, 50_000f),
                    floatArrayOf(0f, 1000f, 500f, 400f, 450f, 0f)
                ).toSampledElevationData(100.0f)
            )
            val routeGraphDisplayViewModel = RouteGraphDisplayViewModel()
            val streamData = StreamData(routeGraphViewModel, routeGraphDisplayViewModel, karooSystemServiceProvider.karooSystemService.streamUserProfile().first(), settings,
                isVisible = true,
                radarLaneIsVisible = false
            )

            emit(streamData)

            delay(5_000)
        }
    }
}

@Composable
fun MapPinButton(config: ViewConfig, isNightMode: Boolean){
    Box(modifier = GlanceModifier.fillMaxSize()){
        Box(modifier = GlanceModifier.fillMaxSize().padding(5.dp), contentAlignment = Alignment.TopEnd) {
            val imgModifier = if (config.preview) GlanceModifier.size(50.dp) else GlanceModifier.size(50.dp).clickable(
                actionStartActivity(POIActivity::class.java)
            )

            Image(
                provider = if (isNightMode) ImageProvider(R.drawable.bxs_map_pin_night) else ImageProvider(R.drawable.bxs_map_pin),
                modifier = imgModifier,
                contentDescription = "Map Pin",
            )
        }
    }
}
