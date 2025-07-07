package de.timklge.karooroutegraph

import com.mapbox.geojson.Point
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocationViewModelProvider(private val karooSystemServiceProvider: KarooSystemServiceProvider) {
    private val observableStateFlow = MutableStateFlow<Point?>(null)
    val viewModelFlow = observableStateFlow.asStateFlow()

    fun update(action: (Point?) -> Point?){
        observableStateFlow.update(action)
    }

    private var updateJob: Job? = null

    fun startUpdateJob() {
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystemServiceProvider.stream<OnLocationChanged>()
                .map { newLocation ->
                    Point.fromLngLat(newLocation.lng, newLocation.lat)
                }
                .collect { newLocation ->
                    update { newLocation }
                }
        }
    }
}