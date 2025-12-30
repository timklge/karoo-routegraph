package de.timklge.karooroutegraph

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.datatypes.minimap.MinimapZoomLevel
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.incidents.IncidentResult
import de.timklge.karooroutegraph.incidents.IncidentsResponse
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.pois.getStartAndEndPoiIfNone
import de.timklge.karooroutegraph.pois.processPoiName
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PlayBeepPattern
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
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class RouteGraphUpdateManager(
    private val karooSystem: KarooSystemServiceProvider,
    private val routeGraphViewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val incidentProvider: HereMapsIncidentProvider,
    private val context: Context,
    private val offlineNearbyPOIProvider: OfflineNearbyPOIProvider,
    private val autoAddedPOIsViewModelProvider: AutoAddedPOIsViewModelProvider
) {
    companion object {
        const val TAG = "karoo-routegraph"
        const val INCIDENT_GROUPING_RADIUS = 500.0 // in meters
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    data class NavigationStreamState(
        val settings: RouteGraphSettings,
        val state: OnNavigationState.NavigationState,
        val userProfile: UserProfile,
        val pois: OnGlobalPOIs,
        val locationAndRemainingRouteDistance: LocationAndRemainingRouteDistance,
        val temporaryPOIs: RouteGraphTemporaryPOIs,
        val onRoute: Boolean,
        val poiSettings: RouteGraphPoiSettings
    )

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
    fun start(){
        Log.d(TAG, "Starting graph updater")

        var knownRoute: LineString? = null
        var knownPois: Set<POI> = mutableSetOf()
        var knownSettings: RouteGraphSettings? = null
        var knownIncidents: IncidentsResponse? = null
        var knownClimbs: List<Climb>? = null
        var knownIncidentWarningShown = false
        var poiDistances: Map<POI, List<NearestPoint>>? = null
        var lastKnownPositionAlongRoute: Double? = null
        var lastAutoAddedPoisByOsmId: Map<Long, Symbol.POI> = emptyMap()
        var lastAutoAddedPoisRequestedAtPosition: Point? = null
        var lastAutoAddedPoisCategories: Set<NearbyPoiCategory> = emptySet()

        scope.launch {
            combine(
                karooSystem.streamSettings(),
                karooSystem.stream<OnNavigationState>(),
                karooSystem.stream<UserProfile>(),
                streamLocationAndRemainingRouteDistance(),
                karooSystem.stream<OnGlobalPOIs>(),
                karooSystem.streamTemporaryPOIs(),
                karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION),
                karooSystem.streamViewSettings()
            ) { data ->
                val settings = data[0] as RouteGraphSettings
                val navigationState = data[1] as OnNavigationState
                val userProfile = data[2] as UserProfile
                val locationAndRemainingRouteDistance = data[3] as LocationAndRemainingRouteDistance
                val pois = data[4] as OnGlobalPOIs
                val temporaryPOIs = data[5] as RouteGraphTemporaryPOIs
                val onRoute = (data[6] as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ON_ROUTE) == 0.0
                val viewSettings = data[7] as RouteGraphPoiSettings

                NavigationStreamState(settings, navigationState.state, userProfile, pois, locationAndRemainingRouteDistance, temporaryPOIs, onRoute, viewSettings)
            }.distinctUntilChanged().transformLatest { value ->
                while(true){
                    emit(value)
                    delay(60.seconds)
                }
            }
            .throttle(5_000L)
            .collect { (settings, navigationStateEvent: OnNavigationState.NavigationState, userProfile, globalPOIs, locationAndRemainingRouteDistance, temporaryPOIs: RouteGraphTemporaryPOIs, onRoute, poiSettings) ->
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
                } else if (routeDistance != null && locationAndRemainingRouteDistance.remainingRouteDistance != null){
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
                            scope.launch {
                                delay(10_000L) // Wait for 10 seconds before showing the alert

                                karooSystem.karooSystemService.dispatch(InRideAlert(
                                    "incident-update-${System.currentTimeMillis()}",
                                    R.drawable.bx_info_circle,
                                    context.getString(R.string.incidents),
                                    context.getString(R.string.traffic_incidents_found, incidents.results.size),
                                    10_000L,
                                    R.color.eleLightRed,
                                    R.color.black
                                ))

                                val intent = Intent("de.timklge.HIDE_POWERBAR").apply {
                                    putExtra("duration", 11_000L)
                                    putExtra("location", "top")
                                }

                                context.sendBroadcast(intent)

                                karooSystem.karooSystemService.dispatch(PlayBeepPattern(
                                    listOf(PlayBeepPattern.Tone(3000, 300), PlayBeepPattern.Tone(3000, 300), PlayBeepPattern.Tone(3000, 300))
                                ))
                            }
                        }

                        Log.i(TAG, "Incident data updated at ${incidents.sourceUpdated} with ${incidents.results?.size} incidents")
                    } catch(e: Exception){
                        if (!knownIncidentWarningShown){
                            scope.launch {
                                delay(10_000L) // Wait for 10 seconds before showing the alert

                                val intent = Intent("de.timklge.HIDE_POWERBAR").apply {
                                    putExtra("duration", 11_000L)
                                    putExtra("location", "top")
                                }

                                context.sendBroadcast(intent)

                                karooSystem.karooSystemService.dispatch(InRideAlert(
                                    "incident-update-${System.currentTimeMillis()}",
                                    R.drawable.bx_info_circle,
                                    context.getString(R.string.incidents),
                                    context.getString(R.string.failed_to_request_incidents),
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

                val lastKnownAutoAddedPoisRequestedAtPosition = lastAutoAddedPoisRequestedAtPosition
                val refreshAutoAddedPois = routeChanged || lastKnownAutoAddedPoisRequestedAtPosition == null || (
                        locationAndRemainingRouteDistance.lat != null && locationAndRemainingRouteDistance.lon != null && TurfMeasurement.distance(
                            Point.fromLngLat(locationAndRemainingRouteDistance.lon, locationAndRemainingRouteDistance.lat),
                            lastKnownAutoAddedPoisRequestedAtPosition,
                            TurfConstants.UNIT_METERS
                        ) > 1_500
                ) || lastAutoAddedPoisCategories != poiSettings.autoAddPoiCategories

                if (refreshAutoAddedPois && poiSettings.autoAddPoiCategories.isNotEmpty()) {
                    Log.i(TAG, "Route changed, updating auto added POIs")

                    val currentLocation = if (locationAndRemainingRouteDistance.lat != null && locationAndRemainingRouteDistance.lon != null) {
                        Point.fromLngLat(locationAndRemainingRouteDistance.lon, locationAndRemainingRouteDistance.lat)
                    } else {
                        null
                    }

                    val newAutoAddedPois = buildMap {
                        if (routeLineString != null){
                            // Request POIs along the route
                            offlineNearbyPOIProvider.requestNearbyPOIs(
                                poiSettings.autoAddPoiCategories.map { it.osmTag }.flatten(),
                                routeLineString.coordinates(),
                                settings.poiDistanceToRouteMaxMeters.toInt(),
                                200
                            ).forEach { poi ->
                                val symbol = Symbol.POI(
                                    id = "autoadded-${poi.id}",
                                    lat = poi.lat,
                                    lng = poi.lon,
                                    name = processPoiName(poi.tags["name"]),
                                    type = NearbyPoiCategory.fromTag(poi.tags)?.hhType ?: Symbol.POI.Types.GENERIC,
                                )

                                put(poi.id, symbol)
                            }
                        } else if (currentLocation != null) {
                            // Request POIs around the current location
                            offlineNearbyPOIProvider.requestNearbyPOIs(
                                poiSettings.autoAddPoiCategories.map { it.osmTag }.flatten(),
                                listOf(currentLocation),
                                2_000,
                                200
                            ).forEach { poi ->
                                val symbol = Symbol.POI(
                                    id = "autoadded-${poi.id}",
                                    lat = poi.lat,
                                    lng = poi.lon,
                                    name = processPoiName(poi.tags["name"]),
                                    type = NearbyPoiCategory.fromTag(poi.tags)?.hhType
                                        ?: Symbol.POI.Types.GENERIC,
                                )

                                put(poi.id, symbol)
                            }
                        }
                    }

                    lastAutoAddedPoisByOsmId = newAutoAddedPois
                    lastAutoAddedPoisRequestedAtPosition = currentLocation
                    lastAutoAddedPoisCategories = poiSettings.autoAddPoiCategories

                    autoAddedPOIsViewModelProvider.update {
                        it.copy(autoAddedPoisByOsmId = newAutoAddedPois)
                    }

                    Log.i(TAG, "Auto added POIs: ${newAutoAddedPois.values.map { it.name }}")
                }

                val autoAddToElevationProfileAndMinimap = poiSettings.autoAddToElevationProfileAndMinimap
                val poiSum = if (autoAddToElevationProfileAndMinimap) {
                    temporaryPOIs.poisByOsmId + lastAutoAddedPoisByOsmId
                } else {
                    temporaryPOIs.poisByOsmId
                }
                val tempPoiSymbols = poiSum.map { (_, poi) -> POI(poi) }
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
                            context,
                            isNavigatingToDestination
                        )
                    )

                    addAll(incidentPois)
                }

                val poisChanged = knownPois != pois.toSet()
                knownPois = pois.toSet()

                Log.d(TAG, "Received navigation state: $navigationStateEvent")
                Log.d(TAG, "Current known climbs: $knownClimbs")

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

    fun stop() {
        scope.cancel()
    }
}
