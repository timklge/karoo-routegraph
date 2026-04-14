package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
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
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class RouteGraphUpdateManager(
    private val karooSystem: KarooSystemServiceProvider,
    private val routeGraphViewModelProvider: RouteGraphViewModelProvider,
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider,
    private val context: Context,
    private val offlineNearbyPOIProvider: OfflineNearbyPOIProvider,
    private val autoAddedPOIsViewModelProvider: AutoAddedPOIsViewModelProvider
) {
    companion object {
        const val TAG = "karoo-routegraph"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val unnamedPoi = context.getString(R.string.unnamed_poi)

    data class NavigationStreamState(
        val settings: RouteGraphSettings,
        val state: OnNavigationState.NavigationState,
        val userProfile: UserProfile,
        val pois: OnGlobalPOIs,
        val locationAndRemainingRouteDistance: LocationAndRemainingRouteDistance,
        val temporaryPOIs: RouteGraphTemporaryPOIs,
        val onRoute: Boolean,
        val selectedProfileName: String?,
        val poiSettings: RouteGraphPoiSettings?
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

    fun start(){
        Log.d(TAG, "Starting graph updater")

        var knownRoute: LineString? = null
        var knownPois: Set<POI> = mutableSetOf()
        var knownSettings: RouteGraphSettings? = null
        var knownClimbs: List<Climb>? = null
        var poiDistances: Map<POI, List<NearestPoint>>? = null
        var knownOpeningHours: Map<POI, String> = emptyMap()
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
                karooSystem.streamActiveKarooProfileName()
            ) { data ->
                val settings = data[0] as RouteGraphSettings
                val navigationState = data[1] as OnNavigationState
                val userProfile = data[2] as UserProfile
                val locationAndRemainingRouteDistance = data[3] as LocationAndRemainingRouteDistance
                val pois = data[4] as OnGlobalPOIs
                val temporaryPOIs = data[5] as RouteGraphTemporaryPOIs
                val onRoute = (data[6] as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ON_ROUTE) == 1.0
                val profileName = data[7] as String?

                NavigationStreamState(settings, navigationState.state, userProfile, pois, locationAndRemainingRouteDistance, temporaryPOIs, onRoute, profileName, null)
            }.distinctUntilChanged().combine(karooSystem.streamViewSettings()) { navState, globalViewSettings ->
                // Use profile-specific settings if a profile is selected, otherwise fall back to global
                val viewSettings = if (navState.selectedProfileName != null) {
                    try {
                        karooSystem.streamProfileViewSettings(navState.selectedProfileName).first()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load profile settings for ${navState.selectedProfileName}, using global", e)
                        globalViewSettings
                    }
                } else {
                    globalViewSettings
                }

                navState.copy(poiSettings = viewSettings)
            }.distinctUntilChanged()
            .throttle(5_000L)
            .collect { (settings, navigationStateEvent: OnNavigationState.NavigationState, userProfile, globalPOIs, locationAndRemainingRouteDistance, temporaryPOIs: RouteGraphTemporaryPOIs, onRoute, _, poiSettings) ->
                val poiSettings = poiSettings ?: return@collect
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
                    Log.d(TAG, "Karoo reported climbs: $karooClimbs")

                    karooClimbs.mapNotNull { karooClimb ->
                        val category = ClimbCategory.categorize(
                            karooClimb.grade.toFloat() / 100.0f,
                            karooClimb.length.toFloat()
                        )
                        val startDistance = karooClimb.startDistance.toFloat()

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
                    lastKnownPositionAlongRoute = null
                    knownSettings = settings
                    knownClimbs = null

                    true
                } else false

                val shouldUpdateClimbs = onRoute && (routeChanged || (newClimbs?.size ?: 0) > (knownClimbs?.size ?: 0))
                if (shouldUpdateClimbs) {
                    knownClimbs = newClimbs
                }

                val lastKnownAutoAddedPoisRequestedAtPosition = lastAutoAddedPoisRequestedAtPosition
                val autoAddPoisEnabled = poiSettings.autoAddPoisToMap
                val refreshAutoAddedPois = (routeChanged || lastKnownAutoAddedPoisRequestedAtPosition == null || (
                        locationAndRemainingRouteDistance.lat != null && locationAndRemainingRouteDistance.lon != null && TurfMeasurement.distance(
                            Point.fromLngLat(locationAndRemainingRouteDistance.lon, locationAndRemainingRouteDistance.lat),
                            lastKnownAutoAddedPoisRequestedAtPosition,
                            TurfConstants.UNIT_METERS
                        ) > 1_500
                ) || lastAutoAddedPoisCategories != poiSettings.autoAddPoiCategories) && autoAddPoisEnabled

                if (refreshAutoAddedPois) {
                    if (poiSettings.autoAddPoiCategories.isNotEmpty()) {
                        Log.i(TAG, "Route changed, updating auto added POIs")

                        val currentLocation =
                            if (locationAndRemainingRouteDistance.lat != null && locationAndRemainingRouteDistance.lon != null) {
                                Point.fromLngLat(
                                    locationAndRemainingRouteDistance.lon,
                                    locationAndRemainingRouteDistance.lat
                                )
                            } else {
                                null
                            }

                        val newAutoAddedPois = buildMap {
                            if (routeLineString != null) {
                                // Request POIs along the route
                                offlineNearbyPOIProvider.requestNearbyPOIs(
                                    poiSettings.autoAddPoiCategories.map { it.osmTag }.flatten(),
                                    routeLineString.coordinates(),
                                    settings.poiDistanceToRouteMaxMeters.toInt(),
                                    200
                                ).forEach { poi ->
                                    val poiName = poi.tags["name"]
                                        ?: NearbyPoiCategory.fromTag(poi.tags)?.let { context.getString(it.labelRes) }
                                        ?: unnamedPoi
                                    val symbol = Symbol.POI(
                                        id = "autoadded-${poi.id}",
                                        lat = poi.lat,
                                        lng = poi.lon,
                                        name = processPoiName(poiName),
                                        type = NearbyPoiCategory.fromTag(poi.tags)?.hhType
                                            ?: Symbol.POI.Types.GENERIC,
                                    )

                                    put(poi.id, symbol)
                                }
                            } else if (currentLocation != null) {
                                // Request POIs around the current location
                                offlineNearbyPOIProvider.requestNearbyPOIs(
                                    poiSettings.autoAddPoiCategories.map { it.osmTag }.flatten(),
                                    listOf(currentLocation),
                                    settings.poiDistanceToRouteMaxMeters.toInt() * 4,
                                    200
                                ).forEach { poi ->
                                    val poiName = poi.tags["name"]
                                        ?: NearbyPoiCategory.fromTag(poi.tags)?.let { context.getString(it.labelRes) }
                                        ?: unnamedPoi
                                    val symbol = Symbol.POI(
                                        id = "autoadded-${poi.id}",
                                        lat = poi.lat,
                                        lng = poi.lon,
                                        name = processPoiName(poiName),
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
                    } else {
                        lastAutoAddedPoisByOsmId = emptyMap()
                        lastAutoAddedPoisRequestedAtPosition = null
                        lastAutoAddedPoisCategories = emptySet()

                        autoAddedPOIsViewModelProvider.update {
                            it.copy(autoAddedPoisByOsmId = emptyMap())
                        }

                        Log.i(TAG, "Auto add POIs disabled, clearing auto added POIs")
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
                            context,
                            isNavigatingToDestination
                        )
                    )
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
                        knownPoiOpeningHours = temporaryPOIs.poiIdOpeningHours,
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
