package de.timklge.karooroutegraph

import android.util.Log
import androidx.annotation.DrawableRes
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.datatypes.DistanceToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.ElevationToNextPOIDataType
import de.timklge.karooroutegraph.datatypes.RouteGraphDataType
import de.timklge.karooroutegraph.datatypes.VerticalRouteGraphDataType
import de.timklge.karooroutegraph.datatypes.minimap.MinimapDataType
import de.timklge.karooroutegraph.datatypes.minimap.MinimapViewModelProvider
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import identifyClimbs
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Duration.Companion.seconds

class GradientIndicator(val id: String, val distance: Float, val gradientPercent: Float, @DrawableRes val drawableRes: Int){
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GradientIndicator

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        return result
    }

    override fun toString(): String {
        return "GradientIndicator(id='$id', distance=$distance, $gradientPercent)"
    }
}

class KarooRouteGraphExtension : KarooExtension("karoo-routegraph", BuildConfig.VERSION_NAME) {
    companion object {
        const val TAG = "karoo-routegraph"
    }

    private val karooSystem: KarooSystemServiceProvider by inject()
    private val routeGraphViewModelProvider: RouteGraphViewModelProvider by inject()
    private val minimapViewModelProvider: MinimapViewModelProvider by inject()
    private val valhallaAPIElevationProvider: ValhallaAPIElevationProvider by inject()
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()
    private val tileDownloadService: TileDownloadService by inject()

    private var graphUpdaterJob: Job? = null
    private var pastRouteUpdateJob: Job? = null

    override val types by lazy {
        listOf(
            RouteGraphDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, applicationContext),
            VerticalRouteGraphDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, applicationContext),
            DistanceToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            ElevationToNextPOIDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, applicationContext),
            MinimapDataType(karooSystem.karooSystemService, routeGraphViewModelProvider, displayViewModelProvider, minimapViewModelProvider, tileDownloadService, applicationContext),
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

    private var lastDrawnSymbols = mutableSetOf<GradientIndicator>()

    override fun startMap(emitter: Emitter<MapEffect>) {
        var currentSymbols: MutableSet<GradientIndicator>

        Log.d(TAG, "Starting map effect")
        emitter.onNext(HideSymbols(lastDrawnSymbols.map { it.id }))
        lastDrawnSymbols.clear()

        emitter.onNext(HideSymbols(lastDrawnSymbols.map { "incline-${it.distance}" }))
        lastDrawnSymbols = mutableSetOf()

        val gradientIndicatorJob = CoroutineScope(Dispatchers.IO).launch {
            val zoomLevelFlow = karooSystem.stream<OnMapZoomLevel>()
            val locationFlow = karooSystem.stream<OnLocationChanged>()

            data class StreamData(
                val settings: RouteGraphSettings,
                val location: OnLocationChanged,
                val mapZoom: OnMapZoomLevel,
                val viewModel: RouteGraphViewModel
            )

            val redrawInterval = if (karooSystem.karooSystemService.hardwareType == HardwareType.K2) {
                3.seconds
            } else {
                1.seconds
            }

            combine(applicationContext.streamSettings(karooSystem.karooSystemService), locationFlow, zoomLevelFlow, routeGraphViewModelProvider.viewModelFlow) { settings, location, mapZoom, viewModel ->
                StreamData(settings, location, mapZoom, viewModel)
            }.throttle(redrawInterval.inWholeMilliseconds).collect { (settings, location, mapZoom, viewModel) ->
                Log.d(TAG, "Location: $location, MapZoom: $mapZoom")
                if (settings.showGradientIndicatorsOnMap) {
                    val boundingBox =
                        calculateBoundingBox(location.lat, location.lng, mapZoom.zoomLevel)
                    val mapDiagonal = TurfMeasurement.distance(
                        Point.fromLngLat(boundingBox.minLng, boundingBox.minLat),
                        Point.fromLngLat(boundingBox.maxLng, boundingBox.maxLat),
                        TurfConstants.UNIT_METERS
                    ) * 3
                    val currentPosition = Point.fromLngLat(location.lng, location.lat)
                    Log.d(TAG, "Drawing gradient indicators, Diagonal: $mapDiagonal")

                    val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f
                    val startDistance =
                        (distanceAlongRoute - mapDiagonal).toFloat().coerceAtLeast(0.0f)
                    val endDistance = (distanceAlongRoute + mapDiagonal).toFloat()

                    if (viewModel.sampledElevationData != null) {
                        Log.d(TAG, "Range: $startDistance - $endDistance")

                        val wantedStepInMeters = mapDiagonal / 20.0
                        val steps = round(wantedStepInMeters / viewModel.sampledElevationData.interval).toInt().coerceAtLeast(1)

                        currentSymbols = viewModel.sampledElevationData.getGradientIndicators(steps) { distance ->
                            val targetPosition = TurfMeasurement.along(
                                viewModel.knownRoute!!,
                                distance.toDouble(),
                                TurfConstants.UNIT_METERS
                            )

                            boundingBox.contains(targetPosition.latitude(), targetPosition.longitude()) || TurfMeasurement.distance(targetPosition, currentPosition, TurfConstants.UNIT_METERS) <= 500
                        }.toMutableSet()
                    } else {
                        currentSymbols = mutableSetOf()
                    }

                    val removedSymbols = lastDrawnSymbols - currentSymbols

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
                            orientation = bearing.toFloat() - (location.orientation ?: 0.0).toFloat(),
                        )
                    }
                    emitter.onNext(ShowSymbols(icons))
                }

                    lastDrawnSymbols = currentSymbols
                } else {
                    emitter.onNext(HideSymbols(lastDrawnSymbols.map { it.id }))
                    lastDrawnSymbols = mutableSetOf()
                }
            }
        }

        emitter.setCancellable {
            emitter.onNext(HideSymbols(lastDrawnSymbols.map { "incline-${it.distance}" }))

            Log.d(TAG, "Stopping map effect")

            gradientIndicatorJob.cancel()
        }
    }

    data class NavigationStreamState(val state: OnNavigationState.NavigationState,
                                     val userProfile: UserProfile,
                                     val pois: OnGlobalPOIs,
                                     val locationAndRemainingRouteDistance: LocationAndRemainingRouteDistance)

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
            .map { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) ?: 0.0 }

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
        var knownRouteElevation: SampledElevationData? = null
        var poiDistances: Map<Symbol.POI, List<NearestPoint>>? = null

        graphUpdaterJob = CoroutineScope(Dispatchers.IO).launch {
            combine(
                karooSystem.stream<OnNavigationState>(),
                karooSystem.stream<UserProfile>(),
                streamLocationAndRemainingRouteDistance(),
                karooSystem.stream<OnGlobalPOIs>()
            ) { navigationState, userProfile, locationAndRemainingRouteDistance, pois ->
                NavigationStreamState(navigationState.state, userProfile, pois, locationAndRemainingRouteDistance)
            }.distinctUntilChanged().transformLatest { value ->
                while(true){
                    emit(value)
                    delay(60.seconds)
                }
            }
            .collect { (navigationStateEvent, userProfile, globalPOIs, locationAndRemainingRouteDistance) ->
                val isImperial = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                val pois = globalPOIs.pois + (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.pois.orEmpty()
                val navigatingToDestinationPolyline = (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingToDestination)?.polyline?.let { LineString.fromPolyline(it, 5) }
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
                val distanceAlongRoute = if (routeDistance != null && locationAndRemainingRouteDistance.remainingRouteDistance != null && navigationStateEvent is OnNavigationState.NavigationState.NavigatingRoute){
                    if (navigationStateEvent.rejoinDistance != null) {
                        if (navigationStateEvent.reversed){
                            routeDistance
                        } else {
                            routeDistance - locationAndRemainingRouteDistance.remainingRouteDistance
                        }
                    } else {
                        val lastKnownRouteDistance = routeGraphViewModelProvider.viewModelFlow.first().distanceAlongRoute
                        lastKnownRouteDistance
                    }
                } else if (routeDistance != null && locationAndRemainingRouteDistance.remainingRouteDistance != null && navigationStateEvent is OnNavigationState.NavigationState.NavigatingToDestination){
                    routeDistance - locationAndRemainingRouteDistance.remainingRouteDistance
                } else null

                Log.d(TAG, "Received navigation state: $navigationStateEvent")

                val routeLineString = if (navigationStateEvent is OnNavigationState.NavigationState.NavigatingRoute) {
                    val polyline = navigationStateEvent.routePolyline

                    // Log.d(TAG, "Route polyline: ${Base64.encodeToString(polyline.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}")

                    LineString.fromPolyline(polyline, 5)
                } else if (navigationStateEvent is OnNavigationState.NavigationState.NavigatingToDestination) {
                    navigatingToDestinationPolyline
                } else {
                    null
                }

                val routeChanged =  if (knownRoute == null || routeLineString != knownRoute){
                    knownRoute = routeLineString
                    knownRouteElevation = null

                    true
                } else false

                if (routeChanged){
                    if (routeLineString != null){
                        Log.i(TAG, "Route changed, recalculating POI distances")

                        poiDistances = calculatePoiDistances(routeLineString, pois)

                        val poiDistancesDebug = poiDistances?.map { (key, value) ->
                            "${key.name} (${key.type}): $value"
                        }?.joinToString(", ")
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

                routeGraphViewModelProvider.update {
                    it.copy(
                        routeDistance = routeDistance?.toFloat(),
                        distanceAlongRoute = distanceAlongRoute?.toFloat(),
                        knownRoute = knownRoute,
                        poiDistances = poiDistances,
                        sampledElevationData = knownRouteElevation,
                        isImperial = isImperial,
                        navigatingToDestination = navigationStateEvent is OnNavigationState.NavigationState.NavigatingToDestination,
                        rejoin = (navigationStateEvent as? OnNavigationState.NavigationState.NavigatingRoute)?.rejoinPolyline?.let { LineString.fromPolyline(it, 5) },
                        locationAndRemainingRouteDistance = locationAndRemainingRouteDistance
                    )
                }

                // Request elevation data
                if (knownRouteElevation == null && routeLineString != null && routeDistance != null){
                    Log.i(TAG, "Route changed, recalculating elevation data")

                    try {
                        val elevations = valhallaAPIElevationProvider.requestValhallaElevations(routeLineString)

                        Log.i(TAG, "Elevation data: $elevations")
                        knownRouteElevation = elevations

                        val climbs = elevations.identifyClimbs()
                        Log.i(TAG, "Identified climbs: $climbs")

                        routeGraphViewModelProvider.update {
                            it.copy(sampledElevationData = elevations, climbs = climbs)
                        }
                    } catch(e: Exception){
                        Log.e(TAG, "Failed to sample route elevation")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        startGraphUpdater()
        startMinimapUpdater()
        tileDownloadService.startDownloadJob()
    }

    private fun startMinimapUpdater() {
        pastRouteUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            var pastPoints = listOf<Point>()
            val tempPoints = mutableListOf<Point>()

            data class StreamData(
                val location: OnLocationChanged,
                val rideState: RideState
            )

            val flow = combine(karooSystem.stream<OnLocationChanged>(), karooSystem.stream<RideState>()) { location, rideState ->
                StreamData(location, rideState)
            }

            flow.throttle(30_000L).collect { (locationEvent, rideState) ->
                if (rideState is RideState.Idle) {
                    pastPoints = emptyList()
                    tempPoints.clear()
                    return@collect
                }

                /* TODO store ridden path

                val location = Point.fromLngLat(locationEvent.lng, locationEvent.lat)

                tempPoints.add(location)

                val newPointList = pastPoints + tempPoints

                if (tempPoints.size >= 30) {
                    pastPoints = TurfTransformation.simplify(newPointList)
                    tempPoints.clear()
                } */

                minimapViewModelProvider.update {
                    it.copy(
                        // pastPoints = newPointList,
                        currentLat = locationEvent.lat,
                        currentLng = locationEvent.lng,
                        currentBearing = locationEvent.orientation
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        graphUpdaterJob?.cancel()
        graphUpdaterJob = null

        pastRouteUpdateJob?.cancel()
        pastRouteUpdateJob = null

        super.onDestroy()
    }
}