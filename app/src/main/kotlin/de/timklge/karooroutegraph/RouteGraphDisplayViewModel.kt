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
            // Return first configured zoom level smaller than the route length
            val routeLength = viewModel.routeDistance
            if (routeLength == null) {
                val maxZoomLevel = settings.elevationProfileZoomLevels.maxOrNull()

                return if (maxZoomLevel != null) {
                    Units(maxZoomLevel)
                } else {
                    CompleteRoute
                }
            }

            return settings.elevationProfileZoomLevels
                .map { Units(it) }
                .sortedByDescending { it.displayedUnits }
                .firstOrNull {
                    (it.getDistanceInMeters(viewModel, settings) ?: Float.MAX_VALUE) < routeLength
                } ?: CompleteRoute
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
                .sortedByDescending { it.displayedUnits }
                .firstOrNull {
                    (it.getDistanceInMeters(viewModel, settings) ?: Float.MAX_VALUE) < currentDistance
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