package de.timklge.karooroutegraph

import androidx.annotation.ColorRes
import kotlin.math.roundToInt

enum class ClimbCategory(
    val minGradient: Float?,
    val minLength: Float?,
    val number: Int,
    val importance: Int,
    @ColorRes val colorRes: Int,
    @ColorRes val minimapColorRes: Int,
) {
    LARGE_CLIMB(0.075f, 500f, 1, 9, R.color.eleRed, R.color.eleRed),
    MEDIUM_CLIMB(0.046f, 500f, 2, 2, R.color.eleYellow, R.color.eleDarkOrange),
    SMALL_CLIMB(null, 0f, 3, 1, R.color.eleDarkGreen, R.color.eleDarkGreen);

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
    val startDistance: Float,
    val endDistance: Float
) {
    val length: Float get() = endDistance - startDistance

    fun totalGain(sampledElevationData: SampledElevationData): Double {
        return sampledElevationData.getTotalClimb(startDistance, endDistance)
    }

    fun getAverageIncline(elevationData: SampledElevationData): Double {
        var totalIncline = 0.0
        var count = 0

        for(i in 1 until elevationData.elevations.size) {
            val currentPosition = i * elevationData.interval
            if (currentPosition in startDistance..endDistance) {
                val incline = (elevationData.elevations[i] - elevationData.elevations[i-1]) / elevationData.interval
                totalIncline += incline
                count++
            }
        }

        return if (count > 0) totalIncline / count else 0.0
    }

    data class MaxIncline(val start: Float, val end: Float, val incline: Int)

    fun getMaxIncline(elevationData: SampledElevationData): MaxIncline {
        var maxIncline = -100
        var start = 0f
        var end = 0f

        // Find indices corresponding to start and end distances
        val startIdx = maxOf(0, (startDistance / elevationData.interval).toInt())
        val endIdx = minOf(elevationData.elevations.size - 1, (endDistance / elevationData.interval).toInt())

        // Check all possible segments within the range
        for (i in startIdx until endIdx) {
            val iDistance = i * elevationData.interval

            for (j in i + 1..endIdx) {
                val jDistance = j * elevationData.interval
                val distance = jDistance - iDistance

                val elevationChange = elevationData.elevations[j] - elevationData.elevations[i]
                val incline = ((elevationChange / distance * 100) / 5.0).toInt() * 5

                if (incline >= maxIncline) {
                    maxIncline = incline
                    start = iDistance
                    end = jDistance
                } else {
                    break
                }
            }
        }

        val avgMaxIncline = if (end - start > 0) {
            (elevationData.getTotalClimb(start, end) / (end - start) * 100).roundToInt()
        } else {
            maxIncline
        }

        return MaxIncline(start, end, avgMaxIncline)
    }
}