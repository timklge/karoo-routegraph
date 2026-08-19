/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.POI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RouteGraphViewModel(val routeDistance: Float? = null,
                               val distanceAlongRoute: Float = 0.0f,
                               val isOnRoute: Boolean? = null,
                               val lastKnownPositionOnMainRoute: Point? = null,
                               val knownRoute: LineString? = null,
                               val poiDistances: Map<POI, List<NearestPoint>>? = null,
                               val knownPoiOpeningHours: Map<String, String> = mapOf(),
                               val sampledElevationData: SampledElevationData? = null,
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