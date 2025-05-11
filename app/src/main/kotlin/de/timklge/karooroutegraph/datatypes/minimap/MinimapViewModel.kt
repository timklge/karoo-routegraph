package de.timklge.karooroutegraph.datatypes.minimap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

data class MinimapViewModel(/* val pastPoints: List<Point>? = null, */
                            val currentLat: Double? = null,
                            val currentLng: Double? = null,
                            val currentBearing: Double? = null,
                            val lastTileDownloadedAt: Instant? = null)

class MinimapViewModelProvider {
    private val observableStateFlow = MutableStateFlow(MinimapViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (MinimapViewModel) -> MinimapViewModel) {
        observableStateFlow.update(action)
    }
}