package de.timklge.karooroutegraph

import androidx.annotation.ColorRes
import kotlin.math.roundToInt

enum class ClimbCategory(
    val minGradient: Float?,
    val number: Int,
    val importance: Int,
    @ColorRes val colorRes: Int,
    @ColorRes val minimapColorRes: Int,
) {
    LARGE_CLIMB(0.125f, 1, 9, R.color.eleRed, R.color.eleRed),
    MEDIUM_CLIMB(0.076f, 2, 2, R.color.eleYellow, R.color.eleDarkOrange),
    MEDIUM_SMALL_CLIMB(0.046f, 3, 2, R.color.eleLightGreen, R.color.eleLightGreen),
    SMALL_CLIMB(null, 4, 1, R.color.eleDarkGreen, R.color.eleDarkGreen);

    companion object {
        fun categorize(gradient: Float, length: Float): ClimbCategory? {
            return entries.firstOrNull { category ->
                gradient >= (category.minGradient ?: 0f)
            }
        }
    }
}

data class Climb(
    val category: ClimbCategory,
    val startDistance: Int,
    val endDistance: Int
) {
    val length: Float get() = (endDistance - startDistance).toFloat()

    fun totalGain(sampledElevationData: SampledElevationData): Double {
        return sampledElevationData.getTotalClimb(startDistance.toFloat(), endDistance.toFloat())
    }

    fun getAverageIncline(elevationData: SampledElevationData): Double {
        var totalIncline = 0.0
        var count = 0

        // Calculate index range directly instead of scanning entire array
        val startIndex = (startDistance.toFloat() / elevationData.interval).toInt().coerceIn(1, elevationData.elevations.size - 1)
        val endIndex = (endDistance.toFloat() / elevationData.interval).toInt().coerceAtMost(elevationData.elevations.size - 1)

        for (i in startIndex..endIndex) {
            val incline = (elevationData.elevations[i] - elevationData.elevations[i - 1]) / elevationData.interval
            totalIncline += incline
            count++
        }

        return if (count > 0) totalIncline / count else 0.0
    }

    data class MaxIncline(val start: Float, val end: Float, val incline: Int)

    fun getMaxIncline(elevationData: SampledElevationData): MaxIncline {
        // Optimized from O(n²) to O(n) using single-pass sliding window
        // Find indices corresponding to start and end distances
        val startIdx = maxOf(0, (startDistance / elevationData.interval).toInt())
        val endIdx = minOf(elevationData.elevations.size - 1, (endDistance / elevationData.interval).toInt())

        var maxIncline = -100
        var bestStart = startIdx
        var bestEnd = startIdx + 1

        // Single pass: track cumulative elevation gain from a starting point
        // Reset start when gradient decreases below current max
        var currentStart = startIdx
        var cumulativeGain = 0.0f

        for (i in startIdx + 1..endIdx) {
            val elevationChange = elevationData.elevations[i] - elevationData.elevations[i - 1]
            cumulativeGain += elevationChange.coerceAtLeast(0.0f) // Only count uphill sections

            val distance = (i - currentStart) * elevationData.interval
            if (distance > 0) {
                val incline = ((cumulativeGain / distance * 100) / 5.0f).toInt() * 5

                if (incline > maxIncline) {
                    maxIncline = incline
                    bestStart = currentStart
                    bestEnd = i
                }

                // Reset if gradient drops below threshold
                if (elevationChange < 0) {
                    cumulativeGain = 0.0f
                    currentStart = i
                }
            }
        }

        val startDistance = bestStart * elevationData.interval
        val endDistance = bestEnd * elevationData.interval

        val avgMaxIncline = if (endDistance - startDistance > 0) {
            (elevationData.getTotalClimb(startDistance, endDistance) / (endDistance - startDistance) * 100).roundToInt()
        } else {
            maxIncline
        }

        return MaxIncline(startDistance, endDistance, avgMaxIncline)
    }
}