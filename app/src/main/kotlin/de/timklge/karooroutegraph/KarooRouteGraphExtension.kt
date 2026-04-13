package de.timklge.karooroutegraph

import android.util.Log
import de.timklge.karooroutegraph.datatypes.DistanceToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.ETAAtNextPOIDataType
import de.timklge.karooroutegraph.datatypes.ETADataType
import de.timklge.karooroutegraph.datatypes.ElevationToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.PoiButtonDataType
import de.timklge.karooroutegraph.datatypes.PoiListAheadDataType
import de.timklge.karooroutegraph.datatypes.RouteGraphDataType
import de.timklge.karooroutegraph.datatypes.VerticalRouteGraphDataType
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.PoiApproachAlertService
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

class KarooRouteGraphExtension : KarooExtension("karoo-routegraph", BuildConfig.VERSION_NAME) {
    companion object {
        const val TAG = "karoo-routegraph"
    }

    private val karooSystem: KarooSystemServiceProvider by inject()
    private val routeGraphViewModelProvider: RouteGraphViewModelProvider by inject()
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()
    private val tileDownloadService: TileDownloadService by inject()
    private val locationViewModelProvider: LocationViewModelProvider by inject()
    private val surfaceConditionRetrievalService: SurfaceConditionRetrievalService by inject()
    @Suppress("unused")
    private val poiApproachAlertService: PoiApproachAlertService by inject()
    @Suppress("unused")
    private val nearbyPoiPbfDownloadService: NearbyPOIPbfDownloadService by inject()
    private val routeGraphUpdateManager: RouteGraphUpdateManager by inject()
    private val autoAddedPOIsViewModelProvider: AutoAddedPOIsViewModelProvider by inject()
    private val travelTimeEstimationService: TravelTimeEstimationService by inject()
    private val offlineNearbyPOIProvider: OfflineNearbyPOIProvider by inject()

    override val types by lazy {
        listOf(
            RouteGraphDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, applicationContext, surfaceConditionRetrievalService),
            VerticalRouteGraphDataType(routeGraphViewModelProvider, displayViewModelProvider, karooSystem, surfaceConditionRetrievalService, travelTimeEstimationService, applicationContext),
            DistanceToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            ElevationToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            PoiButtonDataType(karooSystem.karooSystemService, applicationContext),
            ETAAtNextPOIDataType(karooSystem, routeGraphViewModelProvider, travelTimeEstimationService, surfaceConditionRetrievalService),
            ETADataType(karooSystem, routeGraphViewModelProvider, travelTimeEstimationService, surfaceConditionRetrievalService),
            PoiListAheadDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, karooSystem, offlineNearbyPOIProvider, applicationContext)
        )
    }

    private fun calculateBoundingBox(lat: Double, lng: Double, zoomLevel: Double): BoundingBox {
        val halfMapSpan = 180.0 / (2.0.pow(zoomLevel))
        val minLat = lat - halfMapSpan
        val maxLat = lat + halfMapSpan
        val minLng = lng - halfMapSpan
        val maxLng = lng + halfMapSpan
        return BoundingBox(minLat, maxLat, minLng, maxLng)
    }


    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double){
        fun contains(lat: Double, lng: Double): Boolean {
            return lat in minLat..maxLat && lng in minLng..maxLng
        }
    }

    private var lastDrawnTemporaryPOIs = setOf<Symbol.POI>()

    override fun startMap(emitter: Emitter<MapEffect>) {
        val mapScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        Log.d(TAG, "Starting map effect")

        emitter.onNext(HideSymbols(lastDrawnTemporaryPOIs.map { it.id }))
        lastDrawnTemporaryPOIs = setOf()

        mapScope.launch {
            data class TemporaryAndAutoAddedPOIs(
                val temporaryPois: RouteGraphTemporaryPOIs,
                val autoAddedPois: AutoAddedPOIsViewModel,
                val settings: RouteGraphPoiSettings
            )

            combine(karooSystem.streamTemporaryPOIs(), autoAddedPOIsViewModelProvider.viewModelFlow, karooSystem.streamActiveKarooProfileName(), karooSystem.streamViewSettings()) { temporaryPois, autoAddedPois, profileName, globalSettings ->
                val settings = if (profileName != null) {
                    try {
                        karooSystem.streamProfileViewSettings(profileName).first()
                    } catch (e: Exception) {
                        globalSettings
                    }
                } else {
                    globalSettings
                }
                TemporaryAndAutoAddedPOIs(
                    temporaryPois,
                    autoAddedPois,
                    settings
                )
            }.distinctUntilChanged().collect { (temporaryPOIs, autoAddedPois, settings) ->
                Log.d(TAG, "Temporary POIs: ${temporaryPOIs.poisByOsmId.size}, Auto-added POIs: ${autoAddedPois.autoAddedPoisByOsmId.size}")

                val newSymbols = buildSet {
                    addAll(temporaryPOIs.poisByOsmId.values)
                    if (settings.autoAddPoisToMap) addAll(autoAddedPois.autoAddedPoisByOsmId.values)
                }

                val removedSymbosl = lastDrawnTemporaryPOIs - newSymbols
                if (removedSymbosl.isNotEmpty()) {
                    Log.d(TAG, "Removing temporary/auto-added POI symbols: $removedSymbosl")
                    emitter.onNext(HideSymbols(removedSymbosl.map { it.id }))
                }

                emitter.onNext(ShowSymbols(newSymbols.toList()))

                lastDrawnTemporaryPOIs = newSymbols

            }
        }

        mapScope.launch {
            val zoomLevelFlow = karooSystem.stream<OnMapZoomLevel>()
            val locationFlow = karooSystem.stream<OnLocationChanged>()
            val distanceToDestinationFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)

            data class StreamData(
                val settings: RouteGraphSettings,
                val location: OnLocationChanged,
                val mapZoom: OnMapZoomLevel,
                val viewModel: RouteGraphViewModel,
                val isOnRoute: Boolean
            )

            val redrawInterval = if (karooSystem.karooSystemService.hardwareType == HardwareType.K2) {
                15.seconds
            } else {
                10.seconds
            }

            combine(applicationContext.streamSettings(karooSystem.karooSystemService), locationFlow, zoomLevelFlow, routeGraphViewModelProvider.viewModelFlow, distanceToDestinationFlow) { settings, location, mapZoom, viewModel, distanceToDestination ->
                val isOnRoute = (distanceToDestination as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ON_ROUTE) == 0.0

                StreamData(settings, location, mapZoom, viewModel, isOnRoute)
            }.throttle(redrawInterval.inWholeMilliseconds).collect { (settings, location, mapZoom, viewModel, isOnRoute) ->
                Log.d(TAG, "Location: $location, MapZoom: $mapZoom, Settings: $settings, IsOnRoute: $isOnRoute")
            }
        }

        emitter.setCancellable {
            emitter.onNext(HideSymbols(lastDrawnTemporaryPOIs.map { it.id }))

            Log.d(TAG, "Stopping map effect")

            mapScope.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        routeGraphUpdateManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        routeGraphUpdateManager.stop()
    }
}
