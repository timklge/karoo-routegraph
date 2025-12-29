package de.timklge.karooroutegraph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SurfaceConditionViewModel(val knownFiles: Int = 0, val osmTiles: Int = 0, val tilesWithoutMapfile: Int = 0, val samples: Int = 0, val gravelSamples: Int = 0)

class SurfaceConditionViewModelProvider {
    private val observableStateFlow = MutableStateFlow(SurfaceConditionViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (SurfaceConditionViewModel) -> SurfaceConditionViewModel){
        observableStateFlow.update(action)
    }
}