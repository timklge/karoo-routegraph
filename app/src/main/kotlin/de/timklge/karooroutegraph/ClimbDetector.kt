import androidx.annotation.ColorRes
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.SampledElevationData
import kotlin.math.roundToInt

enum class ClimbCategory(
    val minGradient: Float,
    val minLength: Float,
    val number: Int,
    val importance: Int,
    @ColorRes val colorRes: Int,
) {
    CAT1(0.08f, 1000f, 1, 10, R.color.eleRed),
    CAT2(0.06f, 750f, 2, 5, R.color.eleDarkOrange),
    CAT3(0.04f, 500f, 3, 2, R.color.eleYellow),
    CAT4(0.02f, 250f, 4, 1, R.color.eleDarkGreen);

    companion object {
        fun categorize(gradient: Float, length: Float): ClimbCategory? {
            return entries.firstOrNull { category ->
                gradient >= category.minGradient && length >= category.minLength
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

    fun overlapsWith(other: Climb, maxGap: Float): Boolean {
        return (startDistance <= other.endDistance + maxGap &&
                endDistance + maxGap >= other.startDistance)
    }

    fun merge(other: Climb): Climb {
        return Climb(
            // Take the more difficult category (they are ordered from hardest to easiest in the enum)
            category = minOf(category, other.category),
            startDistance = minOf(startDistance, other.startDistance),
            endDistance = maxOf(endDistance, other.endDistance)
        )
    }

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

fun SampledElevationData.identifyClimbs(
    minClimbLength: Float = 250f,    // Minimum climb length in meters
    minGradient: Float = 0.02f,      // Minimum gradient (2%) to consider as a climb
    maxMergeGap: Float = 250f        // Maximum gap between climbs to merge them
): List<Climb> {
    val initialClimbs = findInitialClimbs(minClimbLength, minGradient)
    return mergeCloseClimbs(initialClimbs, maxMergeGap)
}

private fun SampledElevationData.findInitialClimbs(
    minClimbLength: Float,
    minGradient: Float
): List<Climb> {
    val climbs = mutableListOf<Climb>()
    var climbStart = -1
    var currentClimb = false

    // Calculate gradients between consecutive points
    for (i in 0 until elevations.size - 1) {
        val elevationDiff = elevations[i + 1] - elevations[i]
        val gradient = elevationDiff / interval

        if (gradient >= minGradient && !currentClimb) {
            climbStart = i
            currentClimb = true
        } else if ((gradient < minGradient || i == elevations.size - 2) && currentClimb) {
            val climbLength = (i - climbStart) * interval
            if (climbLength >= minClimbLength) {
                val avgGradient = (elevations[i] - elevations[climbStart]) / climbLength
                val category = ClimbCategory.categorize(avgGradient, climbLength)
                if (category != null) {
                    climbs.add(
                        Climb(
                            category = category,
                            startDistance = climbStart * interval,
                            endDistance = i * interval
                        )
                    )
                }
            }
            currentClimb = false
        }
    }

    return climbs
}

private fun SampledElevationData.mergeCloseClimbs(
    climbs: List<Climb>,
    maxMergeGap: Float
): List<Climb> {
    if (climbs.isEmpty()) return emptyList()

    val sortedClimbs = climbs.sortedBy { it.startDistance }
    val mergedClimbs = mutableListOf<Climb>()

    var currentClimb = sortedClimbs.first()

    for (i in 1 until sortedClimbs.size) {
        val nextClimb = sortedClimbs[i]

        if (currentClimb.overlapsWith(nextClimb, maxMergeGap)) {
            // Merge the climbs
            currentClimb = currentClimb.merge(nextClimb)

            // Recategorize the merged climb based on its new characteristics
            val avgGradient = getClimbGradient(currentClimb)
            val newCategory = ClimbCategory.categorize(avgGradient, currentClimb.length)
            if (newCategory != null) {
                currentClimb = currentClimb.copy(category = newCategory)
            }
        } else {
            mergedClimbs.add(currentClimb)
            currentClimb = nextClimb
        }
    }

    // Don't forget to add the last climb
    mergedClimbs.add(currentClimb)

    return mergedClimbs
}

fun SampledElevationData.getClimbGradient(climb: Climb): Float {
    val startIndex = (climb.startDistance / interval).toInt()
    val endIndex = (climb.endDistance / interval).toInt()

    if (startIndex >= elevations.size || endIndex >= elevations.size || startIndex >= endIndex) {
        return 0f
    }

    val elevationGain = elevations[endIndex] - elevations[startIndex]
    val horizontalDistance = climb.length

    return elevationGain / horizontalDistance
}