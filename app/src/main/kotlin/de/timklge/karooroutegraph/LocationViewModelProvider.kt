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

import com.mapbox.geojson.Point
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocationViewModelProvider(private val karooSystemServiceProvider: KarooSystemServiceProvider) {
    private val observableStateFlow = MutableStateFlow<Point?>(null)
    val viewModelFlow = observableStateFlow.asStateFlow()

    init {
        startUpdateJob()
    }

    fun update(action: (Point?) -> Point?){
        observableStateFlow.update(action)
    }

    private var updateJob: Job? = null

    fun startUpdateJob() {
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystemServiceProvider
                .streamDataFlow(DataType.Type.LOCATION)
                .filter { it is StreamState.Streaming }
                .mapNotNull {
                    val values = (it as StreamState.Streaming).dataPoint.values
                    val lat = values[DataType.Field.LOC_LATITUDE]
                    val lng = values[DataType.Field.LOC_LONGITUDE]

                    if (lat != null && lng != null) {
                        Point.fromLngLat(lng, lat)
                    } else {
                        null
                    }
                }
                .collect { newLocation ->
                    update { newLocation }
                }
        }
    }
}