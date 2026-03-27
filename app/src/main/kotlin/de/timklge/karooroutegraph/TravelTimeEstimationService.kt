package de.timklge.karooroutegraph

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TravelTimeEstimationService {

    companion object {
        /** Gravitational acceleration (m/s²) */
        const val G = 9.81
        /** Air density at sea level (kg/m³) */
        const val RHO_AIR = 1.225
        /** Drag coefficient × frontal area (m²) – typical road cyclist in hoods position */
        const val CDA = 0.4
        /** Rolling resistance coefficient on smooth pavement */
        const val CRR_PAVEMENT = 0.005
        /** Rolling resistance coefficient on gravel / compacted surface */
        const val CRR_GRAVEL = 0.015
        /** Rolling resistance coefficient on loose / off-road surface */
        const val CRR_LOOSE = 0.030
        /** Fallback power when neither avgPower nor avgSpeed is given (W) */
        const val DEFAULT_POWER_W = 150.0
        /** Maximum modelled speed – 60 km/h (m/s) */
        const val MAX_SPEED_MS = 60.0 / 3.6
        /** Minimum modelled speed – effectively a walk on steep terrain (m/s) */
        const val MIN_SPEED_MS = 0.5
    }

    /**
     * Returns the highest rolling resistance coefficient that applies to the
     * interval [segStart, segEnd], falling back to [CRR_PAVEMENT] when no
     * surface condition covers the segment.
     */
    private fun getCrrForSegment(
        segStart: Double,
        segEnd: Double,
        surfaceConditions: List<SurfaceConditionRetrievalService.SurfaceConditionSegment>
    ): Double {
        var crr = CRR_PAVEMENT
        for (condition in surfaceConditions) {
            if (condition.startMeters < segEnd && condition.endMeters > segStart) {
                val crrForCondition = when (condition.condition) {
                    SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL -> CRR_GRAVEL
                    SurfaceConditionRetrievalService.SurfaceCondition.LOOSE  -> CRR_LOOSE
                }
                if (crrForCondition > crr) crr = crrForCondition
            }
        }
        return crr
    }

    /**
     * Solves for the steady-state speed at which the rider's [power] (W) exactly
     * balances the total resistance, using the standard cycling power model:
     *
     *   P = ½ · CdA · ρ · v³  +  m · g · (gradient + Crr) · v
     *
     * On downhills the function has a minimum (the "coasting" speed) before
     * rising again; the search always starts from that minimum so that only the
     * physically correct high-speed root is returned.
     *
     * The result is clamped to [[MIN_SPEED_MS], [MAX_SPEED_MS]].
     */
    private fun solveSpeedFromPower(
        power: Double,
        gradient: Double,
        totalWeight: Double,
        crr: Double
    ): Double {
        val combined = gradient + crr

        fun powerAtSpeed(v: Double): Double =
            0.5 * CDA * RHO_AIR * v.pow(3) + totalWeight * G * combined * v

        // Gravity + rider power exceeds aerodynamic drag at max speed → cap
        if (powerAtSpeed(MAX_SPEED_MS) <= power) return MAX_SPEED_MS

        // On downhills the power function is non-monotonic; find its minimum
        // (coasting equilibrium speed) and search upward from there so only
        // the physically relevant root is found.
        val loSearch = if (combined < 0) {
            sqrt(-totalWeight * G * combined / (1.5 * CDA * RHO_AIR))
                .coerceIn(MIN_SPEED_MS, MAX_SPEED_MS)
        } else {
            MIN_SPEED_MS
        }

        // Gradient too steep for the given power even at minimum speed → cap
        if (powerAtSpeed(loSearch) >= power) return loSearch.coerceAtLeast(MIN_SPEED_MS)

        // Binary search on the monotonically increasing portion [loSearch, MAX_SPEED_MS]
        var lo = loSearch
        var hi = MAX_SPEED_MS
        repeat(64) {
            val mid = (lo + hi) / 2.0
            if (powerAtSpeed(mid) < power) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /**
     * Estimate the time it takes to ride from [startDistance] to [endDistance].
     *
     * @param routeElevationData Elevation profile of the route
     * @param startDistance start point of the segment, in meters from beginning of the route
     * @param endDistance end point of the segment, in meters from beginning of the route
     * @param totalWeight combined weight of rider + bike (kg)
     * @param lastHourAvgPower average power output over the last hour in watts (if known).
     *        Takes priority over [lastHourAvgSpeed] when both are supplied.
     * @param lastHourAvgSpeed average speed over the last hour in meters per second (if known).
     *        Used to back-calculate an effective flat-terrain power when [lastHourAvgPower] is null.
     * @param surfaceConditions known surface conditions along the route where the road is not smooth pavement
     */
    fun estimateTravelTime(
        routeElevationData: SampledElevationData?,
        startDistance: Double,
        endDistance: Double,
        totalWeight: Double,
        lastHourAvgPower: Double? = null,
        lastHourAvgSpeed: Double? = null,
        surfaceConditions: List<SurfaceConditionRetrievalService.SurfaceConditionSegment> = emptyList()
    ): Duration {
        require(totalWeight > 0) { "totalWeight must be positive" }

        if (endDistance <= startDistance) return Duration.ZERO

        // Determine effective power output
        val effectivePower: Double = when {
            lastHourAvgPower != null -> lastHourAvgPower
            lastHourAvgSpeed != null && lastHourAvgSpeed > 1 ->
                // Back-calculate power from flat-terrain average speed:
                // P_flat = Crr·m·g·v + ½·CdA·ρ·v³
                CRR_PAVEMENT * totalWeight * G * lastHourAvgSpeed +
                    0.5 * CDA * RHO_AIR * lastHourAvgSpeed.pow(3)
            else -> DEFAULT_POWER_W
        }

        val interval = routeElevationData?.interval?.toDouble()
        val elevations = routeElevationData?.elevations

        // No gradient data – assume flat
        if (elevations == null || interval == null || elevations.size < 2) {
            val crr   = getCrrForSegment(startDistance, endDistance, surfaceConditions)
            val speed = solveSpeedFromPower(effectivePower, 0.0, totalWeight, crr)
            return ((endDistance - startDistance) / speed).seconds
        }

        var totalTimeSeconds = 0.0

        val firstIdx = maxOf(0, (startDistance / interval).toInt())
        val lastIdx  = minOf(elevations.size - 2, (endDistance / interval).toInt())

        for (i in firstIdx..lastIdx) {
            val segStart = i * interval
            val segEnd   = (i + 1) * interval

            val clampedStart = maxOf(segStart, startDistance)
            val clampedEnd   = minOf(segEnd, endDistance)
            val segLength    = clampedEnd - clampedStart

            if (segLength <= 0.0) continue

            val gradient = (elevations[i + 1] - elevations[i]).toDouble() / interval
            val crr      = getCrrForSegment(clampedStart, clampedEnd, surfaceConditions)
            val speed    = solveSpeedFromPower(effectivePower, gradient, totalWeight, crr)

            totalTimeSeconds += segLength / speed
        }

        // Fallback – shouldn't normally be reached
        if (totalTimeSeconds == 0.0) {
            val speed = solveSpeedFromPower(effectivePower, 0.0, totalWeight, CRR_PAVEMENT)
            totalTimeSeconds = (endDistance - startDistance) / speed
        }

        return totalTimeSeconds.seconds
    }
}