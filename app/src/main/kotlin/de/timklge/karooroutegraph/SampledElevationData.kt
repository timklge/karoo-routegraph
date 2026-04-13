package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
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
