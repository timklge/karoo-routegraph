package de.timklge.karooroutegraph

import de.timklge.karooroutegraph.pois.POI
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AutoAddedPOIsViewModel(val autoAddedPoisByOsmId: Map<Long, Symbol.POI> = emptyMap())

class AutoAddedPOIsViewModelProvider {
    private val observableStateFlow = MutableStateFlow(AutoAddedPOIsViewModel())
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (AutoAddedPOIsViewModel) -> AutoAddedPOIsViewModel){
        observableStateFlow.update(action)
    }
}