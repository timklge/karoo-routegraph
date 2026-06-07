package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceCondition
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceConditionSegment
import de.timklge.karooroutegraph.throttle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Streams the current route surface:
 *   0 = paved / unknown
 *   1 = compacted (sett, paving stones, bricks, …)
 *   2 = gravel / dirt
 *   3 = loose (sand, grass, mud, snow, ice)
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class SurfaceConditionDataType(
    private val karooSystemProvider: KarooSystemServiceProvider,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val surfaceConditionRetrievalService: SurfaceConditionRetrievalService
) : DataTypeImpl("karoo-routegraph", "surfacetype") {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    data class StreamData(
        val distanceAlongRoute: Double?,
        val routeDistance: Double?,
        val surfaceConditions: List<SurfaceConditionSegment>?
    )

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            combine(
                viewModelProvider.viewModelFlow,
                surfaceConditionRetrievalService.flow
            ) { viewModel, surfaceConditions ->
                StreamData(
                    distanceAlongRoute = viewModel.distanceAlongRoute.toDouble(),
                    routeDistance = viewModel.routeDistance?.toDouble(),
                    surfaceConditions = surfaceConditions
                )
            }.throttle(1_000L).collect { streamData ->
                val classification = classifySurface(streamData)

                if (classification == null) {
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                Log.d(TAG, "Surface condition at ${streamData.distanceAlongRoute} m: $classification")

                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to classification.toDouble()))
                    )
                )
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting surface condition view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig())
            awaitCancellation()
        }

        val flow = if (config.preview) {
            previewClassificationFlow()
        } else {
            combine(
                viewModelProvider.viewModelFlow,
                surfaceConditionRetrievalService.flow
            ) { viewModel, surfaceConditions ->
                classifySurface(
                    StreamData(
                        distanceAlongRoute = viewModel.distanceAlongRoute.toDouble(),
                        routeDistance = viewModel.routeDistance?.toDouble(),
                        surfaceConditions = surfaceConditions
                    )
                )
            }
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = null))

            flow.throttle(1_000L).collect { classification ->
                val label = classification?.let { getSurfaceTypeLabel(context, it) } ?: ""
                Log.d(TAG, "Surface type: $label")

                emitter.onNext(ShowCustomStreamState(null, null))

                emitter.updateView(glance.compose(context, DpSize.Unspecified) {
                    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center){
                        val textAlign = when (config.alignment) {
                            ViewConfig.Alignment.LEFT -> androidx.glance.text.TextAlign.Left
                            ViewConfig.Alignment.CENTER -> androidx.glance.text.TextAlign.Center
                            ViewConfig.Alignment.RIGHT -> androidx.glance.text.TextAlign.Center
                        }
                        Text(label, GlanceModifier.fillMaxWidth(), TextStyle(ColorProvider(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.White), fontSize = 24.sp, textAlign = textAlign, fontFamily = androidx.glance.text.FontFamily.Monospace))
                    }
                }.remoteViews)
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }

    companion object {
        /**
         * Maps a [StreamData] snapshot to the integer classification streamed
         * to consumers. Returns `null` when no route is loaded or the rider
         * position is unknown, signalling `NotAvailable`.
         *
         * Exposed for unit-testing the segment lookup logic.
         */
        fun classifySurface(streamData: StreamData): Int? {
            val currentDistance = streamData.distanceAlongRoute
            val routeDistance = streamData.routeDistance

            if (currentDistance == null || routeDistance == null) return null

            val currentCondition = streamData.surfaceConditions
                ?.firstOrNull { segment ->
                    currentDistance >= segment.startMeters && currentDistance < segment.endMeters
                }
                ?.condition

            return currentCondition?.classificationValue
                ?: SurfaceCondition.PAVED_CLASSIFICATION_VALUE
        }

        /**
         * Returns the localized label for a surface-type classification.
         * Exposed for unit-testing.
         */
        fun getSurfaceTypeLabelRes(classification: Int): Int = when (classification) {
            SurfaceCondition.COMPACTED.classificationValue -> R.string.surfacetype_compacted
            SurfaceCondition.GRAVEL.classificationValue -> R.string.surfacetype_gravel
            SurfaceCondition.LOOSE.classificationValue -> R.string.surfacetype_loose
            else -> R.string.surfacetype_asphalt
        }

        fun getSurfaceTypeLabel(context: Context, classification: Int): String =
            context.getString(getSurfaceTypeLabelRes(classification))

        private fun previewClassificationFlow(): Flow<Int> = flow {
            val previewValues = intArrayOf(0, 2, 1, 3, 0)
            var i = 0
            while (true) {
                emit(previewValues[i % previewValues.size])
                i++
                delay(2_000L)
            }
        }
    }
}
