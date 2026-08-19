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

import de.timklge.karooroutegraph.datatypes.minimap.MinimapZoomLevel
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed class ZoomLevel {
    object CompleteRoute : ZoomLevel() {
        override fun getDistanceInMeters(viewModel: RouteGraphViewModel, settings: RouteGraphSettings): Float? {
            return viewModel.routeDistance
        }

        override fun next(
            viewModel: RouteGraphViewModel,
            settings: RouteGraphSettings,
        ): ZoomLevel {
            val routeLength = viewModel.routeDistance
            if (routeLength == null) {
                val minZoomLevel = settings.elevationProfileZoomLevels.minOrNull()

                return if (minZoomLevel != null) {
                    Units(minZoomLevel)
                } else {
                    CompleteRoute
                }
            }

            return settings.elevationProfileZoomLevels
                .map { Units(it) }.minByOrNull { it.displayedUnits } ?: CompleteRoute
        }
    }

    data class Units(val displayedUnits: Int) : ZoomLevel() {
        override fun getDistanceInMeters(viewModel: RouteGraphViewModel, settings: RouteGraphSettings): Float? {
            return displayedUnits.let {
                if (viewModel.isImperial) {
                    it * 1609.34f
                } else {
                    it * 1000f
                }
            }
        }

        // Zooms out, but at most to [maxZoomLevel]. Afterwards it will return COMPLETE_ROUTE
        override fun next(
            viewModel: RouteGraphViewModel,
            settings: RouteGraphSettings,
        ): ZoomLevel {
            val currentDistance = getDistanceInMeters(viewModel, settings)
            if (currentDistance == null) {
                return CompleteRoute
            }

            return settings.elevationProfileZoomLevels
                .map { Units(it) }
                .sortedBy { it.displayedUnits }
                .firstOrNull {
                    val d = it.getDistanceInMeters(viewModel, settings)
                    val routeDistance = viewModel.routeDistance

                    (routeDistance == null || d == null || d < routeDistance) && (d ?: Float.MAX_VALUE) > currentDistance
                } ?: CompleteRoute
        }
    }

    abstract fun getDistanceInMeters(viewModel: RouteGraphViewModel, settings: RouteGraphSettings): Float?
    abstract fun next(viewModel: RouteGraphViewModel, settings: RouteGraphSettings): ZoomLevel
}

data class RouteGraphDisplayViewModel(val zoomLevel: ZoomLevel = ZoomLevel.CompleteRoute,
                                      val verticalZoomLevel: ZoomLevel = ZoomLevel.CompleteRoute,
                                      val minimapZoomLevel: MinimapZoomLevel = MinimapZoomLevel.FAR,
                                      val minimapWidth: Int? = null, val minimapHeight: Int? = null,)

class RouteGraphDisplayViewModelProvider {
    private val observableStateFlow = MutableStateFlow(RouteGraphDisplayViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (RouteGraphDisplayViewModel) -> RouteGraphDisplayViewModel) {
        observableStateFlow.update(action)
    }
}