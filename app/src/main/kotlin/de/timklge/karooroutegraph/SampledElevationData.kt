package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import kotlin.math.abs

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

    fun getMaximumInclineInRange(startDistance: Float, endDistance: Float): Float {
        var maxIncline = 0f

        for(i in 0 until elevations.size - 1) {
            val currentPosition = i * interval
            val nextPosition = (i + 1) * interval

            if (currentPosition < endDistance && nextPosition > startDistance) {
                val incline = (elevations[i + 1] - elevations[i]) / interval

                if (abs(incline) > abs(maxIncline)) {
                    maxIncline = incline
                }
            }
        }

        return maxIncline
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

    fun getGradientIndicators(route: LineString, stepInMeters: Float, isInRange: (Float) -> Boolean): List<GradientIndicator> = buildList {
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
                        val position = TurfMeasurement.along(route, distanceForIndicator.toDouble(), TurfConstants.UNIT_METERS)
                        add(GradientIndicator(id, distanceForIndicator, maxGradientInSegment, position, drawableRes))
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
                        val position = TurfMeasurement.along(route, distanceForIndicator.toDouble(), TurfConstants.UNIT_METERS)
                        add(GradientIndicator(id, distanceForIndicator, 0.0f, position, drawableRes))
                    }
                }
            }

            currentIndicatorPosition += stepInMeters
        }
    }

    companion object {
        fun fromSparseElevationData(lineString: LineString, interval: Float = 60f): SampledElevationData {
            // lineString contains elevation data with latitude representing the distance along the route in meters, longitude representing the elevation in meters
            val coordinates = lineString.coordinates()

            if (coordinates.isEmpty()) {
                return SampledElevationData(interval, floatArrayOf())
            }

            if (coordinates.size == 1) {
                // Single point - create array with just that elevation
                val elevation = coordinates[0].longitude().toFloat()
                return SampledElevationData(interval, floatArrayOf(elevation))
            }

            // Extract distance-elevation pairs from coordinates
            val sparseData = coordinates.map { coord ->
                val distance = coord.latitude().toFloat()  // Distance along route
                val elevation = coord.longitude().toFloat() // Elevation
                Pair(distance, elevation)
            }.sortedBy { it.first } // Ensure sorted by distance

            val totalDistance = sparseData.last().first - sparseData.first().first
            val numSamples = ((totalDistance / interval).toInt() + 1).coerceAtLeast(1)
            val elevations = FloatArray(numSamples)

            // Generate regularly spaced samples using linear interpolation
            for (i in 0 until numSamples) {
                val targetDistance = sparseData.first().first + i * interval

                // Find the two sparse points that bracket the target distance
                var leftIndex = 0
                var rightIndex = sparseData.size - 1

                // Binary search to find the bracketing points efficiently
                for (j in sparseData.indices) {
                    if (sparseData[j].first <= targetDistance) {
                        leftIndex = j
                    } else {
                        rightIndex = j
                        break
                    }
                }

                // Handle edge cases
                when {
                    leftIndex >= sparseData.size - 1 -> {
                        // Target is at or beyond the last point
                        elevations[i] = sparseData.last().second
                    }
                    rightIndex <= 0 -> {
                        // Target is at or before the first point
                        elevations[i] = sparseData.first().second
                    }
                    leftIndex == rightIndex -> {
                        // Exact match with a sparse point
                        elevations[i] = sparseData[leftIndex].second
                    }
                    else -> {
                        // Linear interpolation between two points
                        val leftPoint = sparseData[leftIndex]
                        val rightPoint = sparseData[rightIndex]

                        val distanceRange = rightPoint.first - leftPoint.first
                        val elevationRange = rightPoint.second - leftPoint.second

                        val ratio = (targetDistance - leftPoint.first) / distanceRange
                        elevations[i] = leftPoint.second + ratio * elevationRange
                    }
                }
            }

            return SampledElevationData(interval, elevations)
        }
    }
}
