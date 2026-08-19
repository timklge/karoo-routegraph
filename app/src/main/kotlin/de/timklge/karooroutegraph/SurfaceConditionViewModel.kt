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