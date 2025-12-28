package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.incidents.IncidentsResponse
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.POI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RouteGraphViewModel(val routeDistance: Float? = null,
                               val distanceAlongRoute: Float? = null,
                               val isOnRoute: Boolean? = null,
                               val lastKnownPositionOnMainRoute: Point? = null,
                               val knownRoute: LineString? = null,
                               val poiDistances: Map<POI, List<NearestPoint>>? = null,
                               val sampledElevationData: SampledElevationData? = null,
                               val incidents: IncidentsResponse? = null,
                               val isImperial: Boolean = false,
                               val climbs: List<Climb>? = null,
                               val rejoin: LineString? = null,
                               val navigatingToDestination: Boolean = false,
                               val locationAndRemainingRouteDistance: RouteGraphUpdateManager.LocationAndRemainingRouteDistance? = null
)

class RouteGraphViewModelProvider {
    private val observableStateFlow = MutableStateFlow(RouteGraphViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (RouteGraphViewModel) -> RouteGraphViewModel){
        observableStateFlow.update(action)
    }
}