package de.timklge.karooroutegraph

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

        for(i in 1 until elevations.size) { // Corrected loop range and removed redundant check
            val currentPosition = i * interval
            if (currentPosition in startDistance+1..endDistance) { // Original condition
                val addedElevation = elevations[i] - elevations[i-1]
                if (addedElevation > 0) {
                    elevationSum += addedElevation
                }
            }
        }

        return elevationSum
    }

    fun getGradientIndicators(stepInMeters: Float, isInRange: (Float) -> Boolean): List<GradientIndicator> = buildList {
        if (elevations.size < 2 || interval <= 0f || stepInMeters <= 0f) {
            return@buildList // Not enough data, invalid interval, or invalid step for processing
        }

        var currentIndicatorPosition = 0.0f
        val totalRouteDistance = (elevations.size - 1) * interval

        while (currentIndicatorPosition < totalRouteDistance) {
            val segmentStartDistance = currentIndicatorPosition
            val segmentEndDistance = minOf(currentIndicatorPosition + stepInMeters, totalRouteDistance)

            var maxGradientInSegment = Float.NEGATIVE_INFINITY

            // Iterate through original elevation intervals to find those overlapping the current segment
            for (k in 0 until elevations.size - 1) {
                val originalIntervalStart = k * interval
                val originalIntervalEnd = (k + 1) * interval

                // Check for overlap: max(start1, start2) < min(end1, end2)
                val overlapStart = maxOf(segmentStartDistance, originalIntervalStart)
                val overlapEnd = minOf(segmentEndDistance, originalIntervalEnd)

                if (overlapStart < overlapEnd) { // If there's a meaningful overlap
                    // Gradient is constant over the original interval [k*interval, (k+1)*interval]
                    val gradientOfOriginalInterval = (elevations[k + 1] - elevations[k]) / interval * 100f
                    maxGradientInSegment = maxOf(maxGradientInSegment, gradientOfOriginalInterval)
                }

                // Optimization: if the current original interval starts after our segment ends,
                // and our segment has positive length, we can stop checking further original intervals.
                if (originalIntervalStart >= segmentEndDistance && segmentStartDistance < segmentEndDistance) {
                    break
                }
            }

            if (maxGradientInSegment > Float.NEGATIVE_INFINITY) {
                val drawableRes = getInclineIndicator(maxGradientInSegment) // Assumed function
                if (drawableRes != null) {
                    val distanceForIndicator = segmentStartDistance // Indicator positioned at the start of its segment
                    if (isInRange(distanceForIndicator)) {
                        val id = "${distanceForIndicator.toInt()}-${drawableRes}"
                        add(GradientIndicator(id, distanceForIndicator, maxGradientInSegment, drawableRes))
                    }
                }
            } else if (segmentStartDistance < segmentEndDistance) {
                // If segment has length but no gradient was found (e.g. perfectly flat or single point data within segment)
                // A gradient of 0% is appropriate for a flat segment.
                // This case handles if all overlapping original intervals were flat.
                val drawableRes = getInclineIndicator(0.0f)
                if (drawableRes != null) {
                    val distanceForIndicator = segmentStartDistance
                    if (isInRange(distanceForIndicator)) {
                        val id = "${distanceForIndicator.toInt()}-${drawableRes}"
                        add(GradientIndicator(id, distanceForIndicator, 0.0f, drawableRes))
                    }
                }
            }

            currentIndicatorPosition += stepInMeters
        }
    }
}
