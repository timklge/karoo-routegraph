package de.timklge.karooroutegraph

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class TravelTimeEstimationTest {

    private val est = TravelTimeEstimationService()

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Flat route at constant elevation, with [count] samples at the given interval. */
    private fun flatRoute(
        distanceMeters: Float,
        interval: Float = 60f,
        elevation: Float = 100f
    ): SampledElevationData {
        val count = (distanceMeters / interval).toInt() + 1
        return SampledElevationData(interval, FloatArray(count) { elevation })
    }

    /** Route with a constant grade (rise/run, e.g. 0.05 = 5 %). */
    private fun gradedRoute(
        distanceMeters: Float,
        grade: Float,
        interval: Float = 60f
    ): SampledElevationData {
        val count = (distanceMeters / interval).toInt() + 1
        return SampledElevationData(interval, FloatArray(count) { i -> 100f + i * interval * grade })
    }

    private fun surfaceSegment(
        startM: Double,
        endM: Double,
        condition: SurfaceConditionRetrievalService.SurfaceCondition
    ) = SurfaceConditionRetrievalService.SurfaceConditionSegment(
        startMeters = startM,
        endMeters   = endM,
        condition   = condition,
        samples     = 10
    )

    // ─── basic edge cases ─────────────────────────────────────────────────────

    @Test
    fun `returns ZERO for equal distances`() {
        val route = flatRoute(1000f)
        assertEquals(Duration.ZERO, est.estimateTravelTime(route, 500.0, 500.0, 80.0))
    }

    @Test
    fun `returns ZERO when endDistance is less than startDistance`() {
        val route = flatRoute(1000f)
        assertEquals(Duration.ZERO, est.estimateTravelTime(route, 600.0, 200.0, 80.0))
    }

    @Test
    fun `empty elevation data falls back to flat estimate`() {
        val emptyRoute = SampledElevationData(60f, FloatArray(0))
        val time = est.estimateTravelTime(emptyRoute, 0.0, 1000.0, 80.0)
        assertTrue(time > Duration.ZERO, "Expected positive duration")
    }

    @Test
    fun `single-elevation-sample falls back to flat estimate`() {
        val onePoint = SampledElevationData(60f, floatArrayOf(100f))
        val time = est.estimateTravelTime(onePoint, 0.0, 1000.0, 80.0)
        assertTrue(time > Duration.ZERO, "Expected positive duration")
    }

    // ─── plausibility ─────────────────────────────────────────────────────────

    @Test
    fun `flat 10 km at default power takes a plausible time (15–60 min)`() {
        val route = flatRoute(10_000f)
        val time  = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0)
        // At 200 W + 80 kg on flat the model gives ~31 km/h ≈ 19 min; allow 15–60 min range
        assertTrue(time >= 15.minutes, "Too fast: $time")
        assertTrue(time <= 60.minutes, "Too slow: $time")
    }

    // ─── physics ordering ─────────────────────────────────────────────────────

    @Test
    fun `uphill is slower than flat`() {
        val flat    = flatRoute(5000f)
        val uphill  = gradedRoute(5000f, grade = 0.05f)
        val tFlat   = est.estimateTravelTime(flat,   0.0, 5000.0, 80.0)
        val tUphill = est.estimateTravelTime(uphill, 0.0, 5000.0, 80.0)
        assertTrue(tUphill > tFlat, "Uphill ($tUphill) should be slower than flat ($tFlat)")
    }

    @Test
    fun `downhill is faster than flat`() {
        val flat      = flatRoute(5000f)
        val downhill  = gradedRoute(5000f, grade = -0.05f)
        val tFlat     = est.estimateTravelTime(flat,     0.0, 5000.0, 80.0)
        val tDownhill = est.estimateTravelTime(downhill, 0.0, 5000.0, 80.0)
        assertTrue(tDownhill < tFlat, "Downhill ($tDownhill) should be faster than flat ($tFlat)")
    }

    @Test
    fun `steeper uphill is slower than shallower uphill`() {
        val shallow = gradedRoute(5000f, grade = 0.03f)
        val steep   = gradedRoute(5000f, grade = 0.10f)
        val tShallow = est.estimateTravelTime(shallow, 0.0, 5000.0, 80.0)
        val tSteep   = est.estimateTravelTime(steep,   0.0, 5000.0, 80.0)
        assertTrue(tSteep > tShallow, "Steep ($tSteep) should be slower than shallow ($tShallow)")
    }

    @Test
    fun `heavier rider is slower uphill`() {
        val uphill = gradedRoute(5000f, grade = 0.07f)
        val tLight  = est.estimateTravelTime(uphill, 0.0, 5000.0, totalWeight = 60.0)
        val tHeavy  = est.estimateTravelTime(uphill, 0.0, 5000.0, totalWeight = 100.0)
        assertTrue(tHeavy > tLight, "Heavy ($tHeavy) should be slower than light ($tLight)")
    }

    @Test
    fun `higher power means faster travel`() {
        val route = flatRoute(10_000f)
        val tLow  = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0, lastHourAvgPower = 100.0)
        val tHigh = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0, lastHourAvgPower = 350.0)
        assertTrue(tHigh < tLow, "Higher power ($tHigh) should be faster than lower power ($tLow)")
    }

    // ─── surface conditions ───────────────────────────────────────────────────

    @Test
    fun `gravel surface is slower than smooth pavement`() {
        val route  = flatRoute(5000f)
        val gravel = surfaceSegment(0.0, 5000.0, SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL)
        val tPave   = est.estimateTravelTime(route, 0.0, 5000.0, 80.0)
        val tGravel = est.estimateTravelTime(route, 0.0, 5000.0, 80.0, surfaceConditions = listOf(gravel))
        assertTrue(tGravel > tPave, "Gravel ($tGravel) should be slower than pavement ($tPave)")
    }

    @Test
    fun `loose surface is slower than gravel`() {
        val route  = flatRoute(5000f)
        val gravel = surfaceSegment(0.0, 5000.0, SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL)
        val loose  = surfaceSegment(0.0, 5000.0, SurfaceConditionRetrievalService.SurfaceCondition.LOOSE)
        val tGravel = est.estimateTravelTime(route, 0.0, 5000.0, 80.0, surfaceConditions = listOf(gravel))
        val tLoose  = est.estimateTravelTime(route, 0.0, 5000.0, 80.0, surfaceConditions = listOf(loose))
        assertTrue(tLoose > tGravel, "Loose ($tLoose) should be slower than gravel ($tGravel)")
    }

    @Test
    fun `partial gravel segment is slower than no gravel but faster than full gravel`() {
        val route     = flatRoute(10_000f)
        val halfGravel = surfaceSegment(0.0, 5000.0, SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL)
        val fullGravel = surfaceSegment(0.0, 10_000.0, SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL)
        val tNone = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0)
        val tHalf = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0, surfaceConditions = listOf(halfGravel))
        val tFull = est.estimateTravelTime(route, 0.0, 10_000.0, 80.0, surfaceConditions = listOf(fullGravel))
        assertTrue(tNone < tHalf && tHalf < tFull,
            "Expected tNone ($tNone) < tHalf ($tHalf) < tFull ($tFull)")
    }

    // ─── speed calibration ────────────────────────────────────────────────────

    @Test
    fun `lastHourAvgSpeed on flat reproduces approximate speed`() {
        // Back-calculated power from 30 km/h should yield ≈ 30 km/h on the same flat terrain
        val avgSpeedMs = 30.0 / 3.6          // 8.33 m/s
        val distanceM  = 10_000.0
        val route      = flatRoute(distanceM.toFloat())
        val time       = est.estimateTravelTime(route, 0.0, distanceM, 80.0, lastHourAvgSpeed = avgSpeedMs)

        // Expected seconds for 10 km at 30 km/h = 1200 s; allow 2 % tolerance
        val expectedS = distanceM / avgSpeedMs
        val actualS   = time.inWholeMilliseconds / 1000.0
        assertTrue(
            abs(actualS - expectedS) / expectedS < 0.02,
            "Expected ≈${expectedS.toInt()} s, got ${actualS.toInt()} s"
        )
    }

    @Test
    fun `lastHourAvgPower takes priority over lastHourAvgSpeed`() {
        val route = flatRoute(10_000f)
        // With a very fast speed but low explicit power, the power should dominate
        val tPowerDriven = est.estimateTravelTime(
            route, 0.0, 10_000.0, 80.0,
            lastHourAvgPower = 150.0,
            lastHourAvgSpeed = 50.0 / 3.6   // would imply very high power if used
        )
        val tPowerOnly = est.estimateTravelTime(
            route, 0.0, 10_000.0, 80.0,
            lastHourAvgPower = 150.0
        )
        assertEquals(tPowerOnly, tPowerDriven)
    }

    // ─── sub-segment additivity ───────────────────────────────────────────────

    @Test
    fun `two consecutive half-segments sum to the full segment time`() {
        val route       = flatRoute(10_000f)
        val routeLength = (route.elevations.size - 1) * route.interval.toDouble()

        val tFull  = est.estimateTravelTime(route, 0.0, routeLength, 80.0)
        val tFirst = est.estimateTravelTime(route, 0.0, routeLength / 2, 80.0)
        val tSecond= est.estimateTravelTime(route, routeLength / 2, routeLength, 80.0)

        val diff = (tFirst + tSecond - tFull).absoluteValue
        assertTrue(diff < 10.milliseconds, "Split segments ($tFirst + $tSecond) don't add up to full ($tFull); diff=$diff")
    }

    @Test
    fun `sub-segment is proportionally shorter (flat route)`() {
        val route       = flatRoute(9000f, interval = 100f)
        val routeLength = (route.elevations.size - 1) * route.interval.toDouble()
        val tFull  = est.estimateTravelTime(route, 0.0,              routeLength, 80.0)
        val tThird = est.estimateTravelTime(route, 0.0, routeLength / 3, 80.0)

        // On a flat route the time ratio should equal the distance ratio (1/3)
        val ratio = tThird.inWholeMilliseconds.toDouble() / tFull.inWholeMilliseconds
        assertTrue(abs(ratio - 1.0 / 3.0) < 0.01,
            "Expected ratio ≈0.333, got $ratio")
    }

    // ─── extreme inputs ───────────────────────────────────────────────────────

    @Test
    fun `very steep downhill clamps to maximum speed`() {
        // 30 % downhill – gravity overwhelms rider power → max speed
        val downhill  = gradedRoute(1000f, grade = -0.30f)
        val time      = est.estimateTravelTime(downhill, 0.0, 1000.0, 80.0)
        val routeLen  = (downhill.elevations.size - 1) * downhill.interval.toDouble()
        val minTime   = (routeLen / TravelTimeEstimationService.MAX_SPEED_MS)
        assertTrue(
            time.inWholeMilliseconds <= (minTime * 1000).toLong() + 50,
            "Expected clamped-at-max-speed time ≈${minTime.toInt()} s, got $time"
        )
    }

    @Test
    fun `extremely steep uphill clamps to minimum speed`() {
        // At 1 W on a 20 % grade the rider can barely move
        val uphill = gradedRoute(600f, grade = 0.20f)
        val time   = est.estimateTravelTime(uphill, 0.0, 600.0, 80.0, lastHourAvgPower = 1.0)
        val routeLen = (uphill.elevations.size - 1) * uphill.interval.toDouble()
        val maxTime  = routeLen / TravelTimeEstimationService.MIN_SPEED_MS
        assertTrue(
            time.inWholeMilliseconds >= (maxTime * 1000).toLong() - 50,
            "Expected clamped-at-min-speed time ≈${maxTime.toInt()} s, got $time"
        )
    }
}


