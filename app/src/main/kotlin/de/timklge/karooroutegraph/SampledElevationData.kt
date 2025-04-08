package de.timklge.karooroutegraph

import kotlin.math.max

class SampledElevationData(val interval: Float, val elevations: FloatArray) {
    fun getMinimumElevationInRange(startDistance: Float, endDistance: Float): Float {
        var minElevation = Float.MAX_VALUE

        for(i in elevations.indices) {
            val currentPosition = i * interval
            if (currentPosition in startDistance..endDistance) {
                minElevation = minOf(minElevation, elevations[i])
            }
        }

        return minElevation
    }

    fun getMaximumElevationInRange(startDistance: Float, endDistance: Float): Float {
        var maxElevation = Float.MIN_VALUE

        for(i in elevations.indices) {
            val currentPosition = i * interval
            if (currentPosition in startDistance..endDistance) {
                maxElevation = maxOf(maxElevation, elevations[i])
            }
        }

        return maxElevation
    }

    fun getTotalClimb(startDistance: Float, endDistance: Float): Double {
        var elevationSum = 0.0

        for(i in 1..elevations.size) {
            val currentPosition = i * interval
            if (currentPosition in startDistance..endDistance) {
                val addedElevation = elevations[i] - elevations[i-1]
                if (addedElevation > 0) {
                    elevationSum += addedElevation
                }
            }
        }

        return elevationSum
    }

    fun getGradientIndicators(step: Int, isInRange: (Float) -> Boolean): List<GradientIndicator> = buildList {
        for (i in 0..(elevations.size - 2) step step) {
            var gradientPercent = 0.0f
            for (j in 0..<step){
                val newGradientPercent = (elevations[i + 1] - elevations[i]) / interval * 100
                gradientPercent = max(gradientPercent, newGradientPercent)
            }

            val drawableRes = getInclineIndicator(gradientPercent)

            if (drawableRes != null) {
                val distance = i * interval
                val id = "${distance.toInt()}-${drawableRes}"

               if (isInRange(distance)) add(GradientIndicator(id, distance, gradientPercent, drawableRes))
            }
        }
    }

}