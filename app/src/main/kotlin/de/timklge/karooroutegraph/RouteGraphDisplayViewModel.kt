package de.timklge.karooroutegraph

import de.timklge.karooroutegraph.datatypes.minimap.MinimapZoomLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ZoomLevel(val displayedUnits: Int?) {
    COMPLETE_ROUTE(null),
    TWO_UNITS(2),
    TWENTY_UNITS(20),
    FIFTY_UNITS(50),
    HUNDRED_UNITS(100);

    fun getDistanceInMeters(isImperial: Boolean): Float? {
        return displayedUnits?.let {
            if (isImperial) {
                it * 1609.34f
            } else {
                it * 1000f
            }
        }
    }

    fun next(routeLengthInMeters: Double, isImperial: Boolean): ZoomLevel {
        val nextZoomLevel = when (this) {
            COMPLETE_ROUTE -> TWO_UNITS
            TWO_UNITS -> TWENTY_UNITS
            TWENTY_UNITS -> FIFTY_UNITS
            FIFTY_UNITS -> HUNDRED_UNITS
            HUNDRED_UNITS -> COMPLETE_ROUTE
        }

        val nextLevelInMeters = nextZoomLevel.getDistanceInMeters(isImperial)

        return if (nextLevelInMeters != null && nextLevelInMeters >= routeLengthInMeters) {
            COMPLETE_ROUTE
        } else {
            nextZoomLevel
        }
    }
}

data class RouteGraphDisplayViewModel(val zoomLevel: ZoomLevel = ZoomLevel.COMPLETE_ROUTE,
                                      val minimapZoomLevel: MinimapZoomLevel = MinimapZoomLevel.COMPLETE_ROUTE,
                                      val minimapWidth: Int? = null, val minimapHeight: Int? = null,)

class RouteGraphDisplayViewModelProvider {
    private val observableStateFlow = MutableStateFlow(RouteGraphDisplayViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (RouteGraphDisplayViewModel) -> RouteGraphDisplayViewModel) {
        observableStateFlow.update(action)
    }
}