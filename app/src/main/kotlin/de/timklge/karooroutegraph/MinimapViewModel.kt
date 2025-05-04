package de.timklge.karooroutegraph

import com.mapbox.geojson.Point
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MinimapViewModel(val pastPoints: List<Point>? = null,
                            val currentLat: Double? = null,
                            val currentLng: Double? = null,
                            val currentBearing: Double? = null)

class MinimapViewModelProvider {
    private val observableStateFlow = MutableStateFlow(MinimapViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    suspend fun update(vm: MinimapViewModel){
        observableStateFlow.emit(vm)
    }
}