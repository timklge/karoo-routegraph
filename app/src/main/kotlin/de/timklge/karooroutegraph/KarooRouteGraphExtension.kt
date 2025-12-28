package de.timklge.karooroutegraph

import android.util.Log
import androidx.core.content.ContextCompat
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.datatypes.DistanceToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.ElevationToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.PoiButtonDataType
import de.timklge.karooroutegraph.datatypes.RouteGraphDataType
import de.timklge.karooroutegraph.datatypes.VerticalRouteGraphDataType
import de.timklge.karooroutegraph.datatypes.minimap.MinimapDataType
import de.timklge.karooroutegraph.datatypes.minimap.MinimapViewModelProvider
import de.timklge.karooroutegraph.incidents.IncidentsResponse
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.PoiApproachAlertService
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val minimapViewModelProvider: MinimapViewModelProvider by inject()
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


    override val types by lazy {
        listOf(
            RouteGraphDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, applicationContext, surfaceConditionRetrievalService),
            VerticalRouteGraphDataType(routeGraphViewModelProvider, displayViewModelProvider, karooSystem, surfaceConditionRetrievalService, applicationContext),
            DistanceToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            ElevationToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            MinimapDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, minimapViewModelProvider, tileDownloadService, locationViewModelProvider, applicationContext, surfaceConditionRetrievalService),
            PoiButtonDataType(karooSystem.karooSystemService, applicationContext),
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

    private var lastDrawnGradientIndicators = mutableSetOf<GradientIndicator>()
    private var lastDrawnIncidentSymbols = mutableSetOf<Symbol>()
    private var lastDrawnIncidentPolylines = mutableSetOf<String>()
    private var lastDrawnTemporaryPOIs = mutableSetOf<Symbol>()

    override fun startMap(emitter: Emitter<MapEffect>) {
        var currentSymbols: MutableSet<GradientIndicator>

        val mapScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        Log.d(TAG, "Starting map effect")
        emitter.onNext(HideSymbols(lastDrawnGradientIndicators.map { it.id }))
        lastDrawnGradientIndicators.clear()

        emitter.onNext(HideSymbols(lastDrawnIncidentSymbols.map { it.id }))
        lastDrawnIncidentSymbols.clear()

        emitter.onNext(HideSymbols(lastDrawnTemporaryPOIs.map { it.id }))
        lastDrawnTemporaryPOIs.clear()

        lastDrawnIncidentPolylines.forEach {
            emitter.onNext(HidePolyline(it))
        }
        lastDrawnIncidentPolylines.clear()

        emitter.onNext(HideSymbols(lastDrawnGradientIndicators.map { "incline-${it.distance}" }))
        lastDrawnGradientIndicators = mutableSetOf()

        mapScope.launch {
            combine(karooSystem.streamTemporaryPOIs(), autoAddedPOIsViewModelProvider.viewModelFlow) { temporaryPois, autoAddedPois ->
                Pair(temporaryPois, autoAddedPois)
            }.distinctUntilChanged().collect { (temporaryPOIs, autoAddedPois) ->
                Log.d(TAG, "Temporary POIs: ${temporaryPOIs.poisByOsmId.size}, Auto-added POIs: ${autoAddedPois.autoAddedPoisByOsmId.size}")

                emitter.onNext(HideSymbols(lastDrawnTemporaryPOIs.map { it.id }))
                lastDrawnTemporaryPOIs.clear()

                val newSymbols = (temporaryPOIs.poisByOsmId + autoAddedPois.autoAddedPoisByOsmId).values.toList()
                lastDrawnTemporaryPOIs += newSymbols
                emitter.onNext(ShowSymbols(newSymbols))
            }
        }

        mapScope.launch {
            var lastKnownIncidents: IncidentsResponse? = null

            routeGraphViewModelProvider.viewModelFlow.collect {
                val incidents = it.incidents
                if (incidents != lastKnownIncidents) {
                    lastKnownIncidents = incidents

                    emitter.onNext(HideSymbols(lastDrawnIncidentSymbols.map { it.id }))
                    lastDrawnIncidentSymbols.clear()

                    emitter.onNext(HideSymbols(lastDrawnIncidentPolylines.toList()))
                    lastDrawnIncidentPolylines.clear()

                    val incidentSymbols = incidents?.results?.mapNotNull { incident ->
                        val id = "incident-${incident.incidentDetails?.id}"
                        val points = incident.location?.shape?.links?.flatMap { link -> link.points ?: emptyList() }

                        // Average of points
                        val lat = points?.mapNotNull { point -> point.lat }?.average()
                        val lng = points?.mapNotNull { point -> point.lng }?.average()

                        if (lat != null && lng != null){
                            Symbol.POI(id, lat, lng, type = Symbol.POI.Types.CAUTION, incident.incidentDetails?.description?.value ?: "Unknown incident")
                        } else {
                            null
                        }
                    } ?: emptyList()

                    val incidentPolylines = incidents?.results?.flatMap { incident ->
                        val id = "incident-${incident.incidentDetails?.id}"
                        val lines = incident.location?.shape?.links?.map { link -> link.points ?: emptyList() }

                        lines?.mapIndexedNotNull { index, it ->
                            val points = it.mapNotNull { point ->
                                if (point.lat != null && point.lng != null) {
                                    Point.fromLngLat(point.lng, point.lat)
                                } else {
                                    null
                                }
                            }

                            if (points.isNotEmpty()) {
                                "${id}_${index}" to LineString.fromLngLats(points).toPolyline(5)
                            } else {
                                null
                            }
                        } ?: emptyList()
                    } ?: emptyList()

                    Log.d(TAG, "Drawing incident symbols: ${incidentSymbols.size}")
                    emitter.onNext(ShowSymbols(incidentSymbols))

                    Log.d(TAG, "Drawing incident polylines: ${incidentPolylines.size}")
                    incidentPolylines.forEach { (id, polyline) ->
                        emitter.onNext(ShowPolyline(id, polyline, ContextCompat.getColor(applicationContext, R.color.eleRed), 10))
                    }

                    lastDrawnIncidentSymbols = incidentSymbols.toMutableSet()
                    lastDrawnIncidentPolylines = incidentPolylines.map { it.first }.toMutableSet()
                }
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

                if (settings.showGradientIndicatorsOnMap) {
                    val boundingBox =
                        calculateBoundingBox(location.lat, location.lng, mapZoom.zoomLevel)
                    val mapDiagonal = TurfMeasurement.distance(
                        Point.fromLngLat(boundingBox.minLng, boundingBox.minLat),
                        Point.fromLngLat(boundingBox.maxLng, boundingBox.maxLat),
                        TurfConstants.UNIT_METERS
                    )

                    Log.d(TAG, "Drawing gradient indicators, Diagonal: $mapDiagonal")

                    val distanceAlongRoute = (viewModel.distanceAlongRoute?.minus(mapDiagonal))?.coerceAtLeast(0.0)?.toFloat() ?: 0.0f
                    val endDistance = (distanceAlongRoute + mapDiagonal * 2).toFloat()

                    if (viewModel.sampledElevationData != null && viewModel.knownRoute != null) {
                        Log.d(TAG, "Range: $distanceAlongRoute - $endDistance")

                        val wantedStepInMeters = mapDiagonal.toFloat() / settings.gradientIndicatorFrequency.stepsPerDisplayDiagonal

                        val calculatedSymbols = viewModel.sampledElevationData.getGradientIndicators(viewModel.knownRoute, wantedStepInMeters) { distance ->
                            distance in distanceAlongRoute..endDistance
                        }.toMutableSet()

                        val filteredSymbols = buildSet {
                            calculatedSymbols.forEach { symbol ->
                                val hasSymbolAtThatLocation = this.any { existingSymbol: GradientIndicator ->
                                    TurfMeasurement.distance(symbol.position, existingSymbol.position, TurfConstants.UNIT_METERS) < wantedStepInMeters * 0.9
                                }

                                if (!hasSymbolAtThatLocation) add(symbol)
                            }
                        }

                        currentSymbols = filteredSymbols.toMutableSet()
                    } else {
                        currentSymbols = mutableSetOf()
                    }

                    val removedSymbols = lastDrawnGradientIndicators - currentSymbols

                    if (removedSymbols.isNotEmpty()) {
                        Log.d(TAG, "Removing symbols: $removedSymbols")
                        emitter.onNext(HideSymbols(removedSymbols.map { it.id }))
                    }

                    if (currentSymbols.isNotEmpty()) {
                        Log.d(TAG, "Drawing symbols: $currentSymbols")

                    val icons = currentSymbols.mapNotNull { gradientIndicator ->
                        val knownRoute = viewModel.knownRoute ?: return@mapNotNull null

                        val position = TurfMeasurement.along(
                            knownRoute,
                            gradientIndicator.distance.toDouble(),
                            TurfConstants.UNIT_METERS
                        )

                        val nextPosition = TurfMeasurement.along(
                            viewModel.knownRoute,
                            gradientIndicator.distance.toDouble() + 10.0,
                            TurfConstants.UNIT_METERS
                        )

                        val bearing = TurfMeasurement.bearing(
                            position,
                            nextPosition
                        )

                        Symbol.Icon(
                            id = gradientIndicator.id,
                            lat = position.latitude(),
                            lng = position.longitude(),
                            iconRes = gradientIndicator.drawableRes,
                            orientation = bearing.toFloat(),
                        )
                    }
                    emitter.onNext(ShowSymbols(icons))
                }

                    lastDrawnGradientIndicators = currentSymbols
                } else {
                    emitter.onNext(HideSymbols(lastDrawnGradientIndicators.map { it.id }))
                    lastDrawnGradientIndicators = mutableSetOf()
                }
            }
        }

        emitter.setCancellable {
            emitter.onNext(HideSymbols(lastDrawnGradientIndicators.map { "incline-${it.distance}" }))
            emitter.onNext(HideSymbols(lastDrawnIncidentSymbols.map { it.id }))
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
