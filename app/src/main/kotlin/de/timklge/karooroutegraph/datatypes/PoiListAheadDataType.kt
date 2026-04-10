package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.pois.PoiType
import io.hammerhead.karooext.KarooSystemService
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Data field that shows POIs ahead on the route.
 * Displays the nearest POI ahead with its name and distance.
 */
class PoiListAheadDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "poilistahead") {

    private var streamJob: Job? = null
    private var viewJob: Job? = null
    private var lastPoiDisplayText: String? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute

                if (currentDistanceAlongRoute == null || state.poiDistances.isNullOrEmpty()) {
                    lastPoiDisplayText = null
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val poiDistances = state.poiDistances.entries.flatMap { (poi, list) ->
                    list.map { distance -> poi to distance }
                }

                // Filter to only POIs ahead of current position
                val poisAhead = poiDistances.filter { (poi, distance) ->
                    poi.type != PoiType.INCIDENT &&
                    distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                }

                // Sort by distance along route (nearest first)
                val poisAheadSorted = poisAhead.sortedBy { (_, distance) ->
                    distance.distanceFromRouteStart
                }

                val nextPoi = poisAheadSorted.firstOrNull()

                if (nextPoi != null) {
                    val nextPoiDistance = nextPoi.second.distanceFromRouteStart - currentDistanceAlongRoute
                    val distanceMeters = nextPoiDistance.toDouble()

                    val poiName = nextPoi.first.symbol.name ?: "POI"
                    val distanceKm = distanceMeters / 1000.0
                    lastPoiDisplayText = if (distanceKm >= 1.0) {
                        "$poiName: %.1f km".format(distanceKm)
                    } else {
                        "$poiName: ${distanceMeters.roundToInt()} m"
                    }

                    // Emit a dummy value to keep the stream alive
                    emitter.onNext(StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to distanceMeters))
                    ))
                } else {
                    lastPoiDisplayText = null
                    emitter.onNext(StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable {
            streamJob?.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting POI list ahead view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig())
            awaitCancellation()
        }

        viewJob = CoroutineScope(Dispatchers.Default).launch {
            // Poll for display text updates
            while (true) {
                val text = lastPoiDisplayText
                if (text != null) {
                    emitter.onNext(ShowCustomStreamState(text, null))
                } else {
                    emitter.onNext(ShowCustomStreamState("", null))
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob?.cancel()
        }
    }
}
