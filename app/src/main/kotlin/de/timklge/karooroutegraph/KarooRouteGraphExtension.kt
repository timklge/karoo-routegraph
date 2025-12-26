package de.timklge.karooroutegraph

import android.content.Intent
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
import de.timklge.karooroutegraph.datatypes.minimap.MinimapZoomLevel
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.incidents.IncidentResult
import de.timklge.karooroutegraph.incidents.IncidentsResponse
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.PoiApproachAlertService
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.pois.getStartAndEndPoiIfNone
import de.timklge.karooroutegraph.pois.processPoiName
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class KarooRouteGraphExtension : KarooExtension("karoo-routegraph", BuildConfig.VERSION_NAME) {
    companion object {
        const val TAG = "karoo-routegraph"

        const val INCIDENT_GROUPING_RADIUS = 500.0 // in meters
    }

    private val karooSystem: KarooSystemServiceProvider by inject()
    private val routeGraphViewModelProvider: RouteGraphViewModelProvider by inject()
    private val minimapViewModelProvider: MinimapViewModelProvider by inject()
    private val incidentProvider: HereMapsIncidentProvider by inject()
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()
    private val tileDownloadService: TileDownloadService by inject()
    private val locationViewModelProvider: LocationViewModelProvider by inject()
    private val poiApproachAlertService: PoiApproachAlertService by inject()
    private val surfaceConditionRetrievalService: SurfaceConditionRetrievalService by inject()
    private val nearbyPoiPbfDownloadService: NearbyPOIPbfDownloadService by inject()

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val point1 = Point.fromLngLat(lon1, lat1)
        val point2 = Point.fromLngLat(lon2, lat2)
        return TurfMeasurement.distance(point1, point2, TurfConstants.UNIT_METERS)
    }

    private fun getAverageLocation(incident: IncidentResult): Pair<Double, Double>? {
        val points = incident.location?.shape?.links?.flatMap { link -> link.points ?: emptyList() }
        if (points.isNullOrEmpty()) return null

        val lats = points.mapNotNull { point -> point.lat }
        val lngs = points.mapNotNull { point -> point.lng }

        if (lats.isEmpty() || lngs.isEmpty()) return null

        val avgLat = lats.average()
        val avgLng = lngs.average()

        return if (!avgLat.isNaN() && !avgLng.isNaN()) Pair(avgLat, avgLng) else null
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
            karooSystem.streamTemporaryPOIs().collect { temporaryPOIs ->
                Log.d(TAG, "Temporary POIs: ${temporaryPOIs.poisByOsmId.size}")

                emitter.onNext(HideSymbols(lastDrawnTemporaryPOIs.map { it.id }))
                lastDrawnTemporaryPOIs.clear()

                emitter.onNext(ShowSymbols(temporaryPOIs.poisByOsmId.values.toList()))
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

    data class NavigationStreamState(val settings: RouteGraphSettings,
                                     val state: OnNavigationState.NavigationState,
                                     val userProfile: UserProfile,
                                     val pois: OnGlobalPOIs,
                                     val locationAndRemainingRouteDistance: LocationAndRemainingRouteDistance,
                                     val temporaryPOIs: RouteGraphTemporaryPOIs,
                                     val onRoute: Boolean)

    data class LocationAndRemainingRouteDistance(val lat: Double?, val lon: Double?, val bearing: Double?, val remainingRouteDistance: Double?)

    private fun streamLocationAndRemainingRouteDistance(): Flow<LocationAndRemainingRouteDistance> = flow {
        val throttle = if (karooSystem.karooSystemService.hardwareType == HardwareType.K2) {
            20_000L
        } else {
            10_000L
        }

        val locationFlow = karooSystem.stream<OnLocationChanged>()
            .distinctUntilChanged()

        val remainingRouteDistanceFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .distinctUntilChanged()
            .map { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) }

        emit(LocationAndRemainingRouteDistance(null, null, null, null))

        locationFlow.combine(remainingRouteDistanceFlow) { locationChangedEvent, remainingRouteDistance ->
            LocationAndRemainingRouteDistance(locationChangedEvent.lat, locationChangedEvent.lng,locationChangedEvent.orientation, remainingRouteDistance)
        }.throttle(throttle).collect {
            emit(it)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startGraphUpdater(){
        Log.d(TAG, "Starting graph updater")

        var knownRoute: LineString? = null
        var knownPois: Set<POI> = mutableSetOf()
        var knownSettings: RouteGraphSettings? = null
        var knownIncidents: IncidentsResponse? = null
        var knownClimbs: List<Climb>? = null
        var knownIncidentWarningShown: Boolean = false
        var poiDistances: Map<POI, List<NearestPoint>>? = null
        var lastKnownPositionAlongRoute: Double? = null

        extensionScope.launch {
            combine(
                karooSystem.streamSettings(),
                karooSystem.stream<OnNavigationState>(),
                karooSystem.stream<UserProfile>(),
                streamLocationAndRemainingRouteDistance(),
                karooSystem.stream<OnGlobalPOIs>(),
                karooSystem.streamTemporaryPOIs(),
                karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            ) { data ->
                val settings = data[0] as RouteGraphSettings
                val navigationState = data[1] as OnNavigationState
                val userProfile = data[2] as UserProfile
                val locationAndRemainingRouteDistance = data[3] as LocationAndRemainingRouteDistance
                val pois = data[4] as OnGlobalPOIs
                val temporaryPOIs = data[5] as RouteGraphTemporaryPOIs
                val onRoute = (data[6] as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ON_ROUTE) == 0.0

                NavigationStreamState(settings, navigationState.state, userProfile, pois, locationAndRemainingRouteDistance, temporaryPOIs, onRoute)
            }.distinctUntilChanged().transformLatest { value ->
                while(true){
                    emit(value)
                    delay(60.seconds)
                }
            }
            .throttle(5_000L)
            .collect { (settings, navigationStateEvent: OnNavigationState.NavigationState, userProfile, globalPOIs, locationAndRemainingRouteDistance, temporaryPOIs: RouteGraphTemporaryPOIs, onRoute: Boolean) ->
                val isImperial = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                val navigatingToDestinationPolyline = (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingToDestination)?.polyline?.let { LineString.fromPolyline(it, 5) }
                val elevationPolyline: LineString? = when (navigationStateEvent) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        navigationStateEvent.routeElevationPolyline?.let { LineString.fromPolyline(it, 1) }
                    }
                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        navigationStateEvent.elevationPolyline?.let { LineString.fromPolyline(it, 1) }
                    }
                    else -> null
                }
                val karooClimbs = when (navigationStateEvent) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        navigationStateEvent.climbs
                    }
                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        navigationStateEvent.climbs
                    }
                    else -> null
                }
                val routeDistance = if (navigatingToDestinationPolyline != null) {
                    try {
                        TurfMeasurement.length(navigatingToDestinationPolyline, TurfConstants.UNIT_METERS)
                    } catch(e: Exception) {
                        Log.e(TAG, "Failed to calculate route distance", e)
                        null
                    }
                } else {
                    (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.routeDistance
                }
                val interval = if(routeDistance != null) {
                    when (routeDistance) {
                        in 0f..100_000f -> 60f
                        in 100_000f..200_000f -> 80f
                        in 200_000f..400_000f -> 100f
                        else -> 120f
                    }
                } else 60f
                val sampledElevationData = elevationPolyline?.let {
                    SampledElevationData.fromSparseElevationData(it, interval)
                }

                val currentDistanceAlongRoute = if (routeDistance != null && locationAndRemainingRouteDistance.remainingRouteDistance != null && navigationStateEvent is OnNavigationState.NavigationState.NavigatingRoute){
                    if (navigationStateEvent.rejoinDistance == null && navigationStateEvent.rejoinPolyline == null) {
                        routeDistance - locationAndRemainingRouteDistance.remainingRouteDistance
                    } else {
                        null
                    }
                } else if (routeDistance != null && locationAndRemainingRouteDistance.remainingRouteDistance != null && navigationStateEvent is OnNavigationState.NavigationState.NavigatingToDestination){
                    routeDistance - locationAndRemainingRouteDistance.remainingRouteDistance
                } else null

                val distanceAlongRoute = currentDistanceAlongRoute ?: lastKnownPositionAlongRoute

                if (currentDistanceAlongRoute != null) {
                    lastKnownPositionAlongRoute = currentDistanceAlongRoute
                }

                val newClimbs = karooClimbs?.let {
                    val offsetDistance = 0
                    Log.d(TAG, "Karoo reported climbs: $karooClimbs, Offset Distance: $offsetDistance")

                    karooClimbs.mapNotNull { karooClimb ->
                        val category = ClimbCategory.categorize(
                            karooClimb.grade.toFloat() / 100.0f,
                            karooClimb.length.toFloat()
                        )
                        val startDistance = (karooClimb.startDistance + offsetDistance).toFloat()

                        category?.let {
                            Climb(
                                category,
                                startDistance.roundToInt(),
                                startDistance.roundToInt() + karooClimb.length.roundToInt()
                            )
                        }
                    }
                }

                val isNavigatingToDestination = navigatingToDestinationPolyline != null

                val routeLineString = when (navigationStateEvent) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        val polyline = navigationStateEvent.routePolyline
                        val lineString = LineString.fromPolyline(polyline, 5)

                        if (navigationStateEvent.reversed) {
                            // Reverse the polyline if we are navigating in reverse
                            LineString.fromLngLats(lineString.coordinates().reversed())
                        } else {
                            lineString
                        }
                    }

                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        navigatingToDestinationPolyline
                    }

                    else -> {
                        null
                    }
                }

                val routeChanged =  if (knownRoute == null || routeLineString != knownRoute || knownSettings != settings){
                    knownRoute = routeLineString
                    knownIncidents = null
                    knownIncidentWarningShown = false
                    lastKnownPositionAlongRoute = null
                    knownSettings = settings
                    knownClimbs = null

                    true
                } else false

                if (onRoute) {
                    knownClimbs = newClimbs
                }

                if (routeChanged) {
                    displayViewModelProvider.update {
                        it.copy(minimapZoomLevel = if (knownRoute != null) MinimapZoomLevel.COMPLETE_ROUTE else MinimapZoomLevel.FAR)
                    }
                }

                // Request incidents
                if (settings.enableTrafficIncidentReporting && knownIncidents == null && routeLineString != null && routeDistance != null){
                    try {
                        val incidents = incidentProvider.requestIncidents(settings.hereMapsApiKey, routeLineString)

                        incidents.results?.forEach {
                            Log.d(TAG, "Incident: ${it.incidentDetails?.id} ${it.incidentDetails?.hrn} ${it.incidentDetails?.startTime} ${it.incidentDetails?.endTime} ${it.incidentDetails?.description}")
                        }

                        knownIncidents = incidents

                        routeGraphViewModelProvider.update {
                            it.copy(
                                incidents = incidents,
                            )
                        }

                        if (incidents.results?.isNotEmpty() == true){
                            extensionScope.launch {
                                delay(10_000L) // Wait for 10 seconds before showing the alert

                                karooSystem.karooSystemService.dispatch(InRideAlert(
                                    "incident-update-${System.currentTimeMillis()}",
                                    R.drawable.bx_info_circle,
                                    applicationContext.getString(R.string.incidents),
                                    applicationContext.getString(R.string.traffic_incidents_found, incidents.results.size),
                                    10_000L,
                                    R.color.eleLightRed,
                                    R.color.black
                                ))

                                val intent = Intent("de.timklge.HIDE_POWERBAR").apply {
                                    putExtra("duration", 11_000L)
                                    putExtra("location", "top")
                                }

                                applicationContext.sendBroadcast(intent)

                                karooSystem.karooSystemService.dispatch(PlayBeepPattern(
                                    listOf(PlayBeepPattern.Tone(3000, 300), PlayBeepPattern.Tone(3000, 300), PlayBeepPattern.Tone(3000, 300))
                                ))
                            }
                        }

                        Log.i(TAG, "Incident data updated at ${incidents.sourceUpdated} with ${incidents.results?.size} incidents")
                    } catch(e: Exception){
                        if (!knownIncidentWarningShown){
                            extensionScope.launch {
                                delay(10_000L) // Wait for 10 seconds before showing the alert

                                val intent = Intent("de.timklge.HIDE_POWERBAR").apply {
                                    putExtra("duration", 11_000L)
                                    putExtra("location", "top")
                                }

                                applicationContext.sendBroadcast(intent)

                                karooSystem.karooSystemService.dispatch(InRideAlert(
                                    "incident-update-${System.currentTimeMillis()}",
                                    R.drawable.bx_info_circle,
                                    applicationContext.getString(R.string.incidents),
                                    applicationContext.getString(R.string.failed_to_request_incidents),
                                    10_000L,
                                    R.color.eleLightRed,
                                    R.color.black
                                ))
                            }
                            knownIncidentWarningShown = true
                        }

                        Log.e(TAG, "Failed to request incidents", e)
                    }
                }

                val incidentsToProcess = knownIncidents?.results?.toMutableList() ?: mutableListOf()
                val groupedIncidentsLists = mutableListOf<List<IncidentResult>>()

                while (incidentsToProcess.isNotEmpty()) {
                    val currentIncident = incidentsToProcess.removeAt(0)
                    val currentGroup = mutableListOf(currentIncident)
                    val currentAvgLoc = getAverageLocation(currentIncident)

                    if (currentAvgLoc != null) {
                        val (currentAvgLat, currentAvgLng) = currentAvgLoc
                        var i = incidentsToProcess.size - 1
                        while (i >= 0) {
                            val otherIncident = incidentsToProcess[i]
                            val otherAvgLoc = getAverageLocation(otherIncident)

                            if (otherAvgLoc != null) {
                                val (otherAvgLat, otherAvgLng) = otherAvgLoc
                                if (calculateDistance(currentAvgLat, currentAvgLng, otherAvgLat, otherAvgLng) < INCIDENT_GROUPING_RADIUS) {
                                    currentGroup.add(otherIncident)
                                    incidentsToProcess.removeAt(i)
                                }
                            }
                            i--
                        }
                    }
                    groupedIncidentsLists.add(currentGroup)
                }

                val incidentPois = groupedIncidentsLists.mapNotNull { group: List<IncidentResult> ->
                    if (group.isEmpty()) {
                        null
                    } else {
                        val representativeIncident = group.first()

                        val allPointsInGroup = group.flatMap { incident: IncidentResult ->
                            (incident.location?.shape?.links?.flatMap { link -> link.points ?: emptyList() } ?: emptyList())
                        }

                        if (allPointsInGroup.isEmpty()) {
                            null
                        } else {
                            val validLats = allPointsInGroup.mapNotNull { point -> point.lat }
                            val validLngs = allPointsInGroup.mapNotNull { point -> point.lng }

                            if (validLats.isEmpty() || validLngs.isEmpty()) {
                                null
                            } else {
                                val avgLat = validLats.average()
                                val avgLng = validLngs.average()

                                if (avgLat.isNaN() || avgLng.isNaN()) {
                                    null
                                } else {
                                    val baseId = representativeIncident.incidentDetails?.id ?: "unknown_${System.currentTimeMillis()}"
                                    val id = "incident-${baseId}${if (group.size > 1) "-group${group.size}" else ""}"

                                    val baseType: String? = representativeIncident.incidentDetails?.type
                                    val typeDescription = when (baseType) {
                                        "accident" -> "Accident"
                                        "construction" -> "Construction"
                                        "disabledVehicle" -> "Disabled Vehicle"
                                        "massTransit" -> "Mass Transit"
                                        "plannedEvent" -> "Planned Event"
                                        "roadHazard" -> "Road Hazard"
                                        "roadClosure" -> "Road Closure"
                                        "weather" -> "Weather"
                                        else -> "Unknown Incident"
                                    }

                                    POI(
                                        Symbol.POI(
                                            id,
                                            avgLat,
                                            avgLng,
                                            type = Symbol.POI.Types.CAUTION,
                                            typeDescription
                                        ),
                                        PoiType.INCIDENT
                                    )
                                }
                            }
                        }
                    }
                }

                val tempPoiSymbols = temporaryPOIs.poisByOsmId.map { (_, poi) -> POI(poi) }
                val localPois = (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.pois.orEmpty().map { symbol ->
                    POI(
                        symbol = symbol,
                        type = PoiType.POI
                    )
                }
                val globalPois = globalPOIs.pois.map { poi ->
                    POI(poi.copy(name = processPoiName(poi.name)))
                }

                val pois = buildList {
                    addAll(tempPoiSymbols)
                    addAll(globalPois)
                    addAll(localPois)

                    addAll(
                        getStartAndEndPoiIfNone(
                            routeLineString,
                            map { it.symbol },
                            settings,
                            applicationContext,
                            isNavigatingToDestination
                        )
                    )

                    addAll(incidentPois)
                }

                val poisChanged = knownPois != pois.toSet()
                knownPois = pois.toSet()

                Log.d(TAG, "Received navigation state: $navigationStateEvent")
                Log.d(TAG, "Current known climbs: ${knownClimbs}")

                if (routeChanged || poisChanged) {
                    if (routeLineString != null){
                        Log.i(TAG, "Route changed, recalculating POI distances")

                        val updatedPoiDistances = calculatePoiDistances(
                            routeLineString,
                            pois,
                            settings.poiDistanceToRouteMaxMeters
                        )
                        poiDistances = updatedPoiDistances

                        val poiDistancesDebug = updatedPoiDistances.map { (key, value) ->
                            "${key.symbol.name} (${key.symbol.type}): $value"
                        }.joinToString(", ")
                        Log.d(TAG, "POI distances: $poiDistancesDebug")
                    }
                    knownRoute = routeLineString
                }

                when(navigationStateEvent){
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        Log.d(TAG, "Navigating ${navigationStateEvent.name}")
                    }
                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        Log.d(TAG, "Navigating to destination")
                    }
                    is OnNavigationState.NavigationState.Idle -> {
                        Log.d(TAG, "Navigation idle")
                    }
                }

                val lastKnownPointAlongRoute = knownRoute?.let { knownRoute ->
                    if (distanceAlongRoute != null && navigationStateEvent is OnNavigationState.NavigationState.NavigatingRoute) {
                        try {
                            TurfMeasurement.along(knownRoute, distanceAlongRoute.coerceIn(0.0, routeDistance), TurfConstants.UNIT_METERS)
                        } catch(e: Exception){
                            Log.e(TAG, "Failed to calculate last known point along route", e)
                            null
                        }
                    } else {
                        null
                    }
                }

                routeGraphViewModelProvider.update {
                    it.copy(
                        routeDistance = routeDistance?.toFloat(),
                        distanceAlongRoute = distanceAlongRoute?.toFloat(),
                        isOnRoute = currentDistanceAlongRoute != null,
                        knownRoute = knownRoute,
                        poiDistances = poiDistances,
                        sampledElevationData = sampledElevationData,
                        climbs = knownClimbs,
                        isImperial = isImperial,
                        navigatingToDestination = navigationStateEvent is OnNavigationState.NavigationState.NavigatingToDestination,
                        rejoin = (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.rejoinPolyline?.let { LineString.fromPolyline(it, 5) },
                        locationAndRemainingRouteDistance = locationAndRemainingRouteDistance,
                        lastKnownPositionOnMainRoute = lastKnownPointAlongRoute ?: it.lastKnownPositionOnMainRoute
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        startGraphUpdater()

        tileDownloadService.startDownloadJob()
        locationViewModelProvider.startUpdateJob()
        poiApproachAlertService.startAlertJob()
        surfaceConditionRetrievalService.startMapScanJob()
        surfaceConditionRetrievalService.startSurfaceConditionUpdateJob()
        nearbyPoiPbfDownloadService.startDownloadJob()
    }

    override fun onDestroy() {
        super.onDestroy()
        extensionScope.cancel()
    }
}
