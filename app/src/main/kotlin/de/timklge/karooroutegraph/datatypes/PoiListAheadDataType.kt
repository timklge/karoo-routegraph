package de.timklge.karooroutegraph.datatypes

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PoiAheadEntry(
    val name: String,
    val distanceMeters: Double
)

/**
 * Data field that shows the next 5 POIs ahead on the route.
 * Displays as a multi-line text view with POI name and distance.
 */
class PoiListAheadDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "poilistahead") {

    private var streamJob: Job? = null
    private var viewJob: Job? = null
    private val poisAheadFlow = MutableStateFlow<List<PoiAheadEntry>>(emptyList())

    override fun startStream(emitter: Emitter<StreamState>) {
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute
                val offlinePoiDistances = state.offlinePoiDistances

                if (currentDistanceAlongRoute == null || offlinePoiDistances.isNullOrEmpty()) {
                    poisAheadFlow.update { emptyList() }
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val poiDistances = offlinePoiDistances.entries.flatMap { (poi, list) ->
                    list.map { distance -> poi to distance }
                }

                // Filter to only POIs ahead of current position
                val poisAhead = poiDistances.filter { (poi, distance) ->
                    poi.type != PoiType.INCIDENT &&
                    distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                }

                // Sort by distance along route (nearest first) and take top 5
                val poisAheadSorted = poisAhead
                    .sortedBy { (_, distance) -> distance.distanceFromRouteStart }
                    .take(5)

                val entries = poisAheadSorted.map { (poi, distance) ->
                    val dist = distance.distanceFromRouteStart - currentDistanceAlongRoute
                    PoiAheadEntry(
                        name = poi.symbol.name ?: "Unnamed POI",
                        distanceMeters = dist.toDouble()
                    )
                }

                poisAheadFlow.update { entries }

                // Emit a dummy value to keep the stream alive
                val firstDist = entries.firstOrNull()?.distanceMeters ?: 0.0
                emitter.onNext(StreamState.Streaming(
                    DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to firstDist))
                ))
            }
        }
        emitter.setCancellable {
            streamJob?.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig())
            awaitCancellation()
        }

        viewJob = CoroutineScope(Dispatchers.Default).launch {
            poisAheadFlow.collect { pois ->
                val displayText = if (pois.isEmpty()) {
                    "No POIs ahead"
                } else {
                    pois.joinToString("\n") { entry ->
                        val distanceText = if (entry.distanceMeters >= 1000) {
                            "%.1f km".format(entry.distanceMeters / 1000.0)
                        } else {
                            "${entry.distanceMeters.roundToInt()} m"
                        }
                        "${entry.name}  $distanceText"
                    }
                }
                emitter.onNext(ShowCustomStreamState(displayText, null))
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob?.cancel()
        }
    }
}
