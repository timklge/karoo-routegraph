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

    // ------------------------------------------------------------------------
    // Internal State
    // ------------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val unnamedPoi = context.getString(R.string.unnamed_poi)

    /** Groups all mutable state used within the start() collect block. */
    private data class RouteState(
        val route: LineString? = null,
        val settings: RouteGraphSettings? = null,
        val climbs: List<Climb>? = null,
        val pois: Set<POI> = emptySet(),
        val poiDistances: Map<POI, List<NearestPoint>>? = null,
        val distanceAlongRoute: Float? = null,
        val lastKnownPositionOnRoute: Point? = null,
        val autoAddedPois: Map<Long, Symbol.POI> = emptyMap(),
        val autoAddedPoisPosition: Point? = null,
        val autoAddedPoisCategories: Set<NearbyPoiCategory> = emptySet()
    )

    private data class NavigationStreamState(
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

    data class LocationAndRemainingRouteDistance(
        val lat: Double?,
        val lon: Double?,
        val bearing: Double?,
        val remainingRouteDistance: Double?
    )

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    fun start() {
        Log.d(TAG, "Starting graph updater")

        var routeState = RouteState()

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
                NavigationStreamState(
                    settings = data[0] as RouteGraphSettings,
                    state = (data[1] as OnNavigationState).state,
                    userProfile = data[2] as UserProfile,
                    pois = data[4] as OnGlobalPOIs,
                    locationAndRemainingRouteDistance = data[3] as LocationAndRemainingRouteDistance,
                    temporaryPOIs = data[5] as RouteGraphTemporaryPOIs,
                    onRoute = (data[6] as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ON_ROUTE) == 1.0,
                    selectedProfileName = data[7] as String?,
                    poiSettings = null
                )
            }.distinctUntilChanged()
                .combine(karooSystem.streamViewSettings()) { navState, globalViewSettings ->
                    val viewSettings = navState.selectedProfileName?.let { profileName ->
                        try {
                            karooSystem.streamProfileViewSettings(profileName).first()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load profile settings for $profileName, using global", e)
                            globalViewSettings
                        }
                    } ?: globalViewSettings
                    navState.copy(poiSettings = viewSettings)
                }.distinctUntilChanged()
                .throttle(5_000L)
                .collect { navState ->
                    val poiSettings = navState.poiSettings ?: return@collect

                    // --- Extract frequently used values ---
                    val isImperial = navState.userProfile.preferredUnit.distance ==
                        UserProfile.PreferredUnit.UnitType.IMPERIAL
                    val navEvent = navState.state
                    val location = navState.locationAndRemainingRouteDistance

                    // --- Compute route LineString ---
                    val routeLineString = computeRouteLineString(navEvent)
                    val navigatingToDestination = navigatingToDestination(navEvent)

                    // --- Route changed detection ---
                    val routeChanged = routeState.route != routeLineString ||
                        routeState.settings != navState.settings

                    if (routeChanged) {
                        routeState = routeState.copy(
                            route = routeLineString,
                            settings = navState.settings,
                            climbs = null,
                            distanceAlongRoute = null
                        )
                    }

                    // --- Elevation data ---
                    val routeDistance = computeRouteDistance(routeLineString, navEvent)
                    val interval = routeDistance?.let { computeElevationInterval(it) } ?: 60f
                    val elevationPolyline = computeElevationPolyline(navEvent)
                    val sampledElevationData = elevationPolyline?.let {
                        SampledElevationData.fromSparseElevationData(it, interval)
                    }

                    // --- Distance along route ---
                    val currentDistanceAlongRoute = computeDistanceAlongRoute(
                        routeDistance, location.remainingRouteDistance, navEvent
                    )
                    val distanceAlongRoute = currentDistanceAlongRoute
                        ?: routeState.distanceAlongRoute

                    if (currentDistanceAlongRoute != null) {
                        routeState = routeState.copy(distanceAlongRoute = currentDistanceAlongRoute)
                    }

                    // --- Climbs ---
                    val newClimbs = computeClimbs(navEvent)
                    val shouldUpdateClimbs = navState.onRoute &&
                        (routeChanged || (newClimbs?.size ?: 0) > (routeState.climbs?.size ?: 0))
                    if (shouldUpdateClimbs) {
                        routeState = routeState.copy(climbs = newClimbs)
                    }

                    // --- Auto-added POIs ---
                    routeState = refreshAutoAddedPOIsIfNeeded(
                        routeState = routeState,
                        routeLineString = routeLineString,
                        currentLocation = location.asPoint(),
                        poiSettings = poiSettings,
                        settings = navState.settings,
                        routeChanged = routeChanged
                    )

                    // --- Build full POI list ---
                    val pois = buildPoiList(
                        navState = navState,
                        routeLineString = routeLineString,
                        settings = navState.settings,
                        isNavigatingToDestination = navigatingToDestination,
                        autoAddedPois = routeState.autoAddedPois
                    )

                    val poisChanged = routeState.pois != pois.toSet()
                    routeState = routeState.copy(pois = pois.toSet())

                    // --- POI distances ---
                    if (routeChanged || poisChanged) {
                        routeLineString?.let { line ->
                            val updatedPoiDistances = calculatePoiDistances(
                                line, pois, navState.settings.poiDistanceToRouteMaxMeters
                            )
                            routeState = routeState.copy(poiDistances = updatedPoiDistances)
                            logPoiDistances(updatedPoiDistances)
                        }
                    }

                    // --- Last known point along route ---
                    val lastKnownPointAlongRoute = computeLastKnownPointAlongRoute(
                        routeState.route, distanceAlongRoute, routeDistance, navEvent
                    )
                    if (lastKnownPointAlongRoute != null) {
                        routeState = routeState.copy(lastKnownPositionOnRoute = lastKnownPointAlongRoute)
                    }

                    // --- Update ViewModel ---
                    routeGraphViewModelProvider.update {
                        it.copy(
                            routeDistance = routeDistance?.toFloat(),
                            distanceAlongRoute = distanceAlongRoute?.toFloat(),
                            isOnRoute = currentDistanceAlongRoute != null,
                            knownRoute = routeState.route,
                            poiDistances = routeState.poiDistances,
                            knownPoiOpeningHours = navState.temporaryPOIs.poiIdOpeningHours,
                            sampledElevationData = sampledElevationData,
                            climbs = routeState.climbs,
                            isImperial = isImperial,
                            navigatingToDestination = navigatingToDestination,
                            rejoin = (navEvent as? OnNavigationState.NavigationState.NavigatingRoute)
                                ?.rejoinPolyline?.let { LineString.fromPolyline(it, 5) },
                            locationAndRemainingRouteDistance = location,
                            lastKnownPositionOnMainRoute = lastKnownPointAlongRoute
                                ?: it.lastKnownPositionOnMainRoute
                        )
                    }
                }
        }
    }

    fun stop() {
        scope.cancel()
    }

    // ------------------------------------------------------------------------
    // Stream helpers
    // ------------------------------------------------------------------------

    private fun streamLocationAndRemainingRouteDistance(): Flow<LocationAndRemainingRouteDistance> = flow {
        val throttle = if (karooSystem.karooSystemService.hardwareType == HardwareType.K2) {
            20_000L
        } else {
            10_000L
        }

        val locationFlow = karooSystem.stream<OnLocationChanged>().distinctUntilChanged()
        val remainingRouteDistanceFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .distinctUntilChanged()
            .map { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) }

        emit(LocationAndRemainingRouteDistance(null, null, null, null))

        locationFlow.combine(remainingRouteDistanceFlow) { locationChangedEvent, remainingRouteDistance ->
            LocationAndRemainingRouteDistance(
                locationChangedEvent.lat,
                locationChangedEvent.lng,
                locationChangedEvent.orientation,
                remainingRouteDistance
            )
        }.throttle(throttle).collect { emit(it) }
    }

    // ------------------------------------------------------------------------
    // Route computation helpers
    // ------------------------------------------------------------------------

    private fun computeRouteLineString(navEvent: OnNavigationState.NavigationState): LineString? {
        return when (navEvent) {
            is OnNavigationState.NavigationState.NavigatingRoute -> {
                val polyline = navEvent.routePolyline
                val lineString = LineString.fromPolyline(polyline, 5)
                if (navEvent.reversed) {
                    LineString.fromLngLats(lineString.coordinates().reversed())
                } else {
                    lineString
                }
            }
            is OnNavigationState.NavigationState.NavigatingToDestination -> {
                navEvent.polyline?.let { LineString.fromPolyline(it, 5) }
            }
            else -> null
        }
    }

    private fun navigatingToDestination(navEvent: OnNavigationState.NavigationState): Boolean {
        return navEvent is OnNavigationState.NavigationState.NavigatingToDestination
    }

    private fun computeRouteDistance(
        routeLineString: LineString?,
        navEvent: OnNavigationState.NavigationState
    ): Double? {
        return routeLineString?.let {
            try {
                TurfMeasurement.length(it, TurfConstants.UNIT_METERS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to calculate route distance", e)
                null
            }
        } ?: (navEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.routeDistance
    }

    private fun computeElevationInterval(routeDistance: Double): Float {
        return when (routeDistance.toFloat()) {
            in 0f..100_000f -> 60f
            in 100_000f..200_000f -> 80f
            in 200_000f..400_000f -> 100f
            else -> 120f
        }
    }

    private fun computeElevationPolyline(navEvent: OnNavigationState.NavigationState): LineString? {
        return when (navEvent) {
            is OnNavigationState.NavigationState.NavigatingRoute -> {
                navEvent.routeElevationPolyline?.let { LineString.fromPolyline(it, 1) }
            }
            is OnNavigationState.NavigationState.NavigatingToDestination -> {
                navEvent.elevationPolyline?.let { LineString.fromPolyline(it, 1) }
            }
            else -> null
        }
    }

    private fun computeDistanceAlongRoute(
        routeDistance: Double?,
        remainingRouteDistance: Double?,
        navEvent: OnNavigationState.NavigationState
    ): Float? {
        if (routeDistance == null || remainingRouteDistance == null) return null

        val result = routeDistance - remainingRouteDistance
        return if (navEvent is OnNavigationState.NavigationState.NavigatingRoute &&
            navEvent.rejoinDistance == null && navEvent.rejoinPolyline == null) {
            result.toFloat()
        } else {
            result.toFloat()
        }
    }

    private fun computeClimbs(navEvent: OnNavigationState.NavigationState): List<Climb>? {
        val karooClimbs = when (navEvent) {
            is OnNavigationState.NavigationState.NavigatingRoute -> navEvent.climbs
            is OnNavigationState.NavigationState.NavigatingToDestination -> navEvent.climbs
            else -> null
        } ?: return null

        Log.d(TAG, "Karoo reported climbs: $karooClimbs")
        return karooClimbs.mapNotNull { karooClimb ->
            val category = ClimbCategory.categorize(
                karooClimb.grade.toFloat() / 100.0f,
                karooClimb.length.toFloat()
            )
            val startDistance = karooClimb.startDistance.toFloat()
            category?.let {
                Climb(
                    it,
                    startDistance.roundToInt(),
                    startDistance.roundToInt() + karooClimb.length.roundToInt()
                )
            }
        }
    }

    private fun computeLastKnownPointAlongRoute(
        route: LineString?,
        distanceAlongRoute: Float?,
        routeDistance: Double?,
        navEvent: OnNavigationState.NavigationState
    ): Point? {
        if (route == null || distanceAlongRoute == null ||
            navEvent !is OnNavigationState.NavigationState.NavigatingRoute) return null

        return try {
            TurfMeasurement.along(
                route.coordinates(),
                distanceAlongRoute.toDouble().coerceIn(0.0, routeDistance ?: 0.0),
                TurfConstants.UNIT_METERS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate last known point along route", e)
            null
        }
    }

    // ------------------------------------------------------------------------
    // POI helpers
    // ------------------------------------------------------------------------

    private data class AutoPOIResult(
        val pois: Map<Long, Symbol.POI>,
        val requestPosition: Point?,
        val categories: Set<NearbyPoiCategory>
    )

    private suspend fun refreshAutoAddedPOIsIfNeeded(
        routeState: RouteState,
        routeLineString: LineString?,
        currentLocation: Point?,
        poiSettings: RouteGraphPoiSettings,
        settings: RouteGraphSettings,
        routeChanged: Boolean
    ): RouteState {
        val autoAddEnabled = poiSettings.autoAddPoisToMap
        if (!autoAddEnabled) {
            if (routeState.autoAddedPois.isNotEmpty()) {
                autoAddedPOIsViewModelProvider.update {
                    it.copy(autoAddedPoisByOsmId = emptyMap())
                }
                Log.i(TAG, "Auto add POIs disabled, clearing auto added POIs")
            }
            return routeState.copy(
                autoAddedPois = emptyMap(),
                autoAddedPoisPosition = null,
                autoAddedPoisCategories = emptySet()
            )
        }

        val distanceFromLastRequest = currentLocation?.let { loc ->
            routeState.autoAddedPoisPosition?.let { lastPos ->
                TurfMeasurement.distance(loc, lastPos, TurfConstants.UNIT_METERS)
            }
        } ?: Double.MAX_VALUE

        val shouldRefresh = routeChanged ||
            routeState.autoAddedPoisPosition == null ||
            distanceFromLastRequest > 1_500 ||
            routeState.autoAddedPoisCategories != poiSettings.autoAddPoiCategories

        if (!shouldRefresh) return routeState

        if (poiSettings.autoAddPoiCategories.isEmpty()) {
            autoAddedPOIsViewModelProvider.update {
                it.copy(autoAddedPoisByOsmId = emptyMap())
            }
            return routeState.copy(
                autoAddedPois = emptyMap(),
                autoAddedPoisPosition = null,
                autoAddedPoisCategories = emptySet()
            )
        }

        Log.i(TAG, "Route changed, updating auto added POIs")

        val newPois = buildAutoAddedPOIs(
            routeLineString = routeLineString,
            currentLocation = currentLocation,
            poiCategories = poiSettings.autoAddPoiCategories,
            maxDistance = settings.poiDistanceToRouteMaxMeters.toInt()
        )

        autoAddedPOIsViewModelProvider.update {
            it.copy(autoAddedPoisByOsmId = newPois)
        }

        Log.i(TAG, "Auto added POIs: ${newPois.values.map { it.name }}")

        return routeState.copy(
            autoAddedPois = newPois,
            autoAddedPoisPosition = currentLocation,
            autoAddedPoisCategories = poiSettings.autoAddPoiCategories
        )
    }

    private suspend fun buildAutoAddedPOIs(
        routeLineString: LineString?,
        currentLocation: Point?,
        poiCategories: Set<NearbyPoiCategory>,
        maxDistance: Int
    ): Map<Long, Symbol.POI> {
        val osmTags = poiCategories.map { it.osmTag }.flatten()
        val points = routeLineString?.coordinates() ?: currentLocation?.let { listOf(it) } ?: emptyList()
        val searchRadius = if (routeLineString != null) maxDistance else maxDistance * 4
        val limit = 200

        return buildMap {
            offlineNearbyPOIProvider.requestNearbyPOIs(osmTags, points, searchRadius, limit)
                .forEach { poi ->
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

    private fun buildPoiList(
        navState: NavigationStreamState,
        routeLineString: LineString?,
        settings: RouteGraphSettings,
        isNavigatingToDestination: Boolean,
        autoAddedPois: Map<Long, Symbol.POI>
    ): List<POI> {
        val tempPoiSymbols = navState.temporaryPOIs.poisByOsmId.map { (_, poi) -> POI(poi) }
        val localPois = (navState.state as? OnNavigationState.NavigationState.NavigatingRoute)
            ?.pois.orEmpty().map { symbol -> POI(symbol = symbol, type = PoiType.POI) }
        val globalPois = navState.pois.pois.map { poi ->
            POI(poi.copy(name = processPoiName(poi.name)))
        }
        val autoPOIs = autoAddedPois.values.map { POI(it) }

        return buildList {
            addAll(tempPoiSymbols)
            addAll(globalPois)
            addAll(autoPOIs)
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
    }

    private fun logPoiDistances(updatedPoiDistances: Map<POI, List<NearestPoint>>) {
        val debug = updatedPoiDistances.map { (key, value) ->
            "${key.symbol.name} (${key.symbol.type}): $value"
        }.joinToString(", ")
        Log.d(TAG, "POI distances: $debug")
    }

    // ------------------------------------------------------------------------
    // Extension helpers
    // ------------------------------------------------------------------------

    private fun LocationAndRemainingRouteDistance.asPoint(): Point? {
        return if (lat != null && lon != null) {
            Point.fromLngLat(lon, lat)
        } else null
    }
}
