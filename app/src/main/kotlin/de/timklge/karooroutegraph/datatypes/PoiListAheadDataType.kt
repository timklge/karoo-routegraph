package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.NearbyPOI
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
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
 * Fetches POIs independently from the offline database.
 */
class PoiListAheadDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val offlineNearbyPOIProvider: OfflineNearbyPOIProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "poilistahead") {

    private var streamJob: Job? = null
    private var viewJob: Job? = null
    private val poisAheadFlow = MutableStateFlow<List<PoiAheadEntry>>(emptyList())
    private var lastPoiDistances: Map<POI, List<NearestPoint>>? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            // Fetch POIs independently using the same categories as the offline POI provider
            val allCategories = NearbyPoiCategory.entries.map { it.osmTag }.flatten()

            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute
                val routeLineString = state.knownRoute

                Log.d(TAG, "PoiListAhead: distance=$currentDistanceAlongRoute, route=${routeLineString != null}")

                if (currentDistanceAlongRoute == null || routeLineString == null) {
                    poisAheadFlow.update { emptyList() }
                    lastPoiDistances = null
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                // Fetch offline POIs independently
                val offlinePois = offlineNearbyPOIProvider.requestNearbyPOIs(
                    allCategories,
                    routeLineString.coordinates(),
                    1000, // 1km radius
                    200
                )

                val poiSymbols = offlinePois.map { poi ->
                    val poiName = poi.tags["name"]
                        ?: NearbyPoiCategory.fromTag(poi.tags)?.let { applicationContext.getString(it.labelRes) }
                        ?: "Unnamed POI"
                    POI(
                        symbol = io.hammerhead.karooext.models.Symbol.POI(
                            id = "ahead-${poi.id}",
                            lat = poi.lat,
                            lng = poi.lon,
                            name = poiName,
                            type = NearbyPoiCategory.fromTag(poi.tags)?.hhType
                                ?: io.hammerhead.karooext.models.Symbol.POI.Types.GENERIC
                        ),
                        type = PoiType.POI
                    )
                }

                Log.d(TAG, "PoiListAhead: fetched ${poiSymbols.size} offline POIs")

                // Calculate distances
                val poiDistances = calculatePoiDistances(
                    routeLineString,
                    poiSymbols,
                    1000.0
                )

                lastPoiDistances = poiDistances

                if (poiDistances.isEmpty()) {
                    poisAheadFlow.update { emptyList() }
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val entries = poiDistances.entries
                    .flatMap { (poi, list) -> list.map { distance -> poi to distance } }
                    .filter { (_, distance) ->
                        distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                    }
                    .sortedBy { (_, distance) -> distance.distanceFromRouteStart }
                    .take(5)
                    .map { (poi, distance) ->
                        val dist = distance.distanceFromRouteStart - currentDistanceAlongRoute
                        PoiAheadEntry(
                            name = poi.symbol.name ?: "Unnamed POI",
                            distanceMeters = dist.toDouble()
                        )
                    }

                poisAheadFlow.update { entries }
                Log.d(TAG, "PoiListAhead: ${entries.size} POIs ahead")

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
                emitter.onNext(io.hammerhead.karooext.models.ShowCustomStreamState(displayText, null))
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob?.cancel()
        }
    }
}
