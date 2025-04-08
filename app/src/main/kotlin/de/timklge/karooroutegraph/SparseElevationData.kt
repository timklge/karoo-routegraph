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