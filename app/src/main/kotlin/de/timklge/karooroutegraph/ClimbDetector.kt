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

        for(i in 1 until elevationData.elevations.size) {
            val currentPosition = i * elevationData.interval
            if (currentPosition in startDistance.toFloat()..endDistance.toFloat()) {
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

fun cleanupClimbs(climbs: Set<Climb>): Set<Climb> {
    if (climbs.isEmpty()) return emptySet()

    // Sort climbs by start distance
    val sorted = climbs.sortedBy { it.startDistance }

    // Helper to choose category when merging: prefer higher importance, then lower number
    val chooseCategory: (ClimbCategory, ClimbCategory) -> ClimbCategory = { a, b ->
        when {
            a.importance != b.importance -> if (a.importance > b.importance) a else b
            else -> if (a.number <= b.number) a else b
        }
    }

    // Merge climbs that are closer than 100 meters
    val merged = mutableListOf<Climb>()
    var current = sorted.first()

    for (next in sorted.drop(1)) {
        val gap = next.startDistance - current.endDistance
        if (gap < 100) {
            val newStart = minOf(current.startDistance, next.startDistance)
            val newEnd = maxOf(current.endDistance, next.endDistance)
            val newCategory = chooseCategory(current.category, next.category)
            current = Climb(newCategory, newStart, newEnd)
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)

    // Remove climbs that are fully contained in another climb
    val filtered = merged.filter { c ->
        merged.none { other ->
            other !== c && other.startDistance <= c.startDistance && other.endDistance >= c.endDistance
        }
    }.toSet()

    return filtered
}