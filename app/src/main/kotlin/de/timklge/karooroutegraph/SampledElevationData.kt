package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class SampledElevationData(val interval: Float, val elevations: FloatArray) {
    fun getMinimumElevationInRange(startDistance: Float, endDistance: Float): Float {
        var minElevation = Float.MAX_VALUE

        // Calculate index range directly instead of scanning entire array
        val startIndex = floor(startDistance / interval).toInt().coerceIn(0, elevations.size - 1)
        val endIndex = ceil(endDistance / interval).toInt().coerceIn(0, elevations.size - 1)

        for (i in startIndex..endIndex) {
            minElevation = minOf(minElevation, elevations[i])
        }

        return minElevation
    }

    fun getMaximumElevationInRange(startDistance: Float, endDistance: Float): Float {
        var maxElevation = Float.MIN_VALUE

        // Calculate index range directly instead of scanning entire array
        val startIndex = floor(startDistance / interval).toInt().coerceIn(0, elevations.size - 1)
        val endIndex = ceil(endDistance / interval).toInt().coerceIn(0, elevations.size - 1)

        for (i in startIndex..endIndex) {
            maxElevation = maxOf(maxElevation, elevations[i])
        }

        return maxElevation
    }

    fun getMaximumInclineInRange(startDistance: Float, endDistance: Float): Float {
        var maxIncline = 0f

        // Calculate index range directly
        val startIndex = floor(startDistance / interval).toInt().coerceIn(0, elevations.size - 2)
        val endIndex = ceil(endDistance / interval).toInt().coerceIn(0, elevations.size - 1)

        for (i in startIndex until endIndex) {
            val incline = (elevations[i + 1] - elevations[i]) / interval

            if (abs(incline) > abs(maxIncline)) {
                maxIncline = incline
            }
        }

        return maxIncline
    }

    fun getTotalClimb(startDistance: Float, endDistance: Float): Double {
        var elevationSum = 0.0

        // Calculate index range directly
        val startIndex = floor((startDistance + 1) / interval).toInt().coerceIn(1, elevations.size - 1)
        val endIndex = ceil(endDistance / interval).toInt().coerceIn(0, elevations.size - 1)

        for (i in startIndex..endIndex) {
            val addedElevation = elevations[i] - elevations[i - 1]
            if (addedElevation > 0) {
                elevationSum += addedElevation
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

                // Binary search to find the bracketing points efficiently
                val searchResult = sparseData.binarySearch { it.first.compareTo(targetDistance) }

                val (leftIndex, rightIndex) = if (searchResult >= 0) {
                    // Exact match found
                    searchResult to searchResult
                } else {
                    // Insertion point is where the element would be, so left is insertionPoint - 1
                    val insertionPoint = -searchResult - 1
                    (insertionPoint - 1).coerceAtLeast(0) to insertionPoint.coerceAtMost(sparseData.size - 1)
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
