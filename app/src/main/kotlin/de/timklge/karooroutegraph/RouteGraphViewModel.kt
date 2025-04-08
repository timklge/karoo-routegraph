package de.timklge.karooroutegraph

import Climb
import com.mapbox.geojson.LineString
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RouteGraphViewModel(val routeDistance: Float? = null,
                               val distanceAlongRoute: Float? = null,
                               val knownRoute: LineString? = null,
                               val poiDistances: Map<Symbol.POI, List<NearestPoint>>? = null,
                               val sampledElevationData: SampledElevationData? = null,
                               val isImperial: Boolean = false,
                               val climbs: List<Climb>? = null)

class RouteGraphViewModelProvider {
    private val observableStateFlow = MutableStateFlow(RouteGraphViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    suspend fun update(vm: RouteGraphViewModel){
        observableStateFlow.emit(vm)
    }
}