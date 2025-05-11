package de.timklge.karooroutegraph

import Climb
import com.mapbox.geojson.LineString
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RouteGraphViewModel(val routeDistance: Float? = null,
                               val distanceAlongRoute: Float? = null,
                               val knownRoute: LineString? = null,
                               val poiDistances: Map<Symbol.POI, List<NearestPoint>>? = null,
                               val sampledElevationData: SampledElevationData? = null,
                               val isImperial: Boolean = false,
                               val climbs: List<Climb>? = null,
                               val rejoin: LineString? = null,
                               val routeToDestination: LineString? = null,
                               val locationAndRemainingRouteDistance: KarooRouteGraphExtension.LocationAndRemainingRouteDistance? = null
)

class RouteGraphViewModelProvider {
    private val observableStateFlow = MutableStateFlow(RouteGraphViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (RouteGraphViewModel) -> RouteGraphViewModel){
        observableStateFlow.update(action)
    }
}