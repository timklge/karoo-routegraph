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

import kotlin.math.ceil

class SparseElevationData(val distances: FloatArray, val elevations: FloatArray){
    fun toSampledElevationData(interval: Float): SampledElevationData {
        val sampledElevations = FloatArray(ceil(distances.last() / interval).toInt() + 1)
        var j = 0
        for (i in sampledElevations.indices) {
            val distance = i * interval
            while (j < distances.size - 1 && distances[j + 1] < distance) {
                j++
            }
            val t = (distance - distances[j]) / (distances[j + 1] - distances[j])
            sampledElevations[i] = elevations[j] + t * (elevations[j + 1] - elevations[j])
        }

        return SampledElevationData(interval, sampledElevations)
    }
}