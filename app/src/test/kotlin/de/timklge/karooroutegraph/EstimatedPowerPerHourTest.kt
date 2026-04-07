package de.timklge.karooroutegraph

import de.timklge.karooroutegraph.datatypes.buildEstimatedPowerFlow
import de.timklge.karooroutegraph.datatypes.streamEstimatedPowerPerHour
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.mockk.every
import io.mockk.mockk
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimatedPowerPerHourTest {

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun <T> Flow<T>.collectAll(): List<T> = runBlocking { toList() }

    private fun calculateExpectedPower(speed: Double, gradePercent: Double, totalWeight: Double): Double {
        return 0.5 * TravelTimeEstimationService.CDA * TravelTimeEstimationService.RHO_AIR * speed.pow(3) +
                totalWeight * TravelTimeEstimationService.G * (gradePercent / 100.0 + TravelTimeEstimationService.CRR_PAVEMENT) * speed
    }

    private fun streamingState(value: Double, dataTypeId: String): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))

    private fun mockProvider(
        speedStates: Flow<StreamState>,
        gradeStates: Flow<StreamState>
    ): KarooSystemServiceProvider = mockk<KarooSystemServiceProvider>().also { mock ->
        every { mock.streamDataFlow(DataType.Type.SPEED) } returns speedStates
        every { mock.streamDataFlow(DataType.Type.ELEVATION_GRADE) } returns gradeStates
        every { mock.streamRideState() } returns flowOf(RideState.Recording)
    }

    // ─── buildEstimatedPowerFlow tests ────────────────────────────────────────

    @Test
    fun `single flat reading returns aero plus rolling power`() {
        val speed = 10.0  // m/s
        val grade = 0.0
        val weight = 80.0
        val now = 1000L

        val result = buildEstimatedPowerFlow(weight, flowOf(speed), flowOf(grade)) { now }
            .collectAll()

        assertEquals(1, result.size)
        assertEquals(calculateExpectedPower(speed, grade, weight), result[0], absoluteTolerance = 0.001)
    }

    @Test
    fun `zero speed produces zero power`() {
        val now = 1000L

        val result = buildEstimatedPowerFlow(80.0, flowOf(0.0), flowOf(0.0)) { now }.collectAll()

        assertEquals(1, result.size)
        assertEquals(0.0, result[0], absoluteTolerance = 0.0001)
    }

    @Test
    fun `heavier rider produces more power at same speed and grade`() {
        val speed = 8.0
        val grade = 0.0
        val now = 1000L

        val lightResult = buildEstimatedPowerFlow(60.0, flowOf(speed), flowOf(grade)) { now }
            .collectAll().first()
        val heavyResult = buildEstimatedPowerFlow(100.0, flowOf(speed), flowOf(grade)) { now }
            .collectAll().first()

        assertTrue(heavyResult > lightResult, "Heavier rider should require more power")
    }

    @Test
    fun `uphill grade requires more power than flat`() {
        val speed = 8.0
        val weight = 80.0
        val now = 1000L

        val flatPower = buildEstimatedPowerFlow(weight, flowOf(speed), flowOf(0.0)) { now }
            .collectAll().first()
        val uphillPower = buildEstimatedPowerFlow(weight, flowOf(speed), flowOf(5.0)) { now }
            .collectAll().first()

        assertTrue(uphillPower > flatPower, "Uphill grade should require more power than flat")
    }

    @Test
    fun `downhill grade reduces required power`() {
        val speed = 8.0
        val weight = 80.0
        val now = 1000L

        val flatPower = buildEstimatedPowerFlow(weight, flowOf(speed), flowOf(0.0)) { now }
            .collectAll().first()
        val downhillPower = buildEstimatedPowerFlow(weight, flowOf(speed), flowOf(-5.0)) { now }
            .collectAll().first()

        assertTrue(downhillPower < flatPower, "Downhill grade should require less power than flat")
    }

    @Test
    fun `two readings produce correct average`() {
        val speed1 = 8.0
        val speed2 = 12.0
        val grade = 0.0
        val weight = 80.0
        val now = 1000L

        val result = buildEstimatedPowerFlow(weight, flowOf(speed1, speed2), flowOf(grade, grade)) { now }
            .collectAll()

        val expected = (calculateExpectedPower(speed1, grade, weight) + calculateExpectedPower(speed2, grade, weight)) / 2.0
        assertEquals(2, result.size)
        assertEquals(expected, result.last(), absoluteTolerance = 0.001)
    }

    @Test
    fun `multiple readings produce correct running average`() {
        val speeds = listOf(5.0, 7.0, 9.0, 11.0, 13.0)
        val grade = 2.0
        val weight = 75.0
        val now = 1000L

        val result = buildEstimatedPowerFlow(
            weight,
            flowOf(*speeds.toTypedArray()),
            flowOf(*List(speeds.size) { grade }.toTypedArray())
        ) { now }.collectAll()

        assertEquals(speeds.size, result.size)
        val expectedAvg = speeds.map { calculateExpectedPower(it, grade, weight) }.average()
        assertEquals(expectedAvg, result.last(), absoluteTolerance = 0.001)
    }

    @Test
    fun `reading older than one hour is excluded from average`() {
        val oldTime = 1000L
        val newTime = oldTime + 3_600_001L  // just over 1 hour later
        val times = listOf(oldTime, newTime)
        var idx = 0

        val result = buildEstimatedPowerFlow(
            80.0, flowOf(5.0, 10.0), flowOf(0.0, 0.0)
        ) { times[idx++] }.collectAll()

        // The last result should only average the second reading
        val expectedFromNew = calculateExpectedPower(10.0, 0.0, 80.0)
        assertEquals(expectedFromNew, result.last(), absoluteTolerance = 0.001)
    }

    @Test
    fun `reading at exactly one hour boundary is excluded`() {
        val oldTime = 1000L
        val newTime = oldTime + 3_600_000L  // exactly 1 hour
        val times = listOf(oldTime, newTime)
        var idx = 0

        val result = buildEstimatedPowerFlow(
            80.0, flowOf(5.0, 20.0), flowOf(0.0, 0.0)
        ) { times[idx++] }.collectAll()

        val expectedFromNew = calculateExpectedPower(20.0, 0.0, 80.0)
        assertEquals(expectedFromNew, result.last(), absoluteTolerance = 0.001)
    }

    @Test
    fun `readings within the hour window are all included in average`() {
        val now = 1000L
        val almostOneHourAgo = now - 3_599_999L

        val times = listOf(almostOneHourAgo, now)
        var idx = 0

        val result = buildEstimatedPowerFlow(
            80.0, flowOf(5.0, 10.0), flowOf(0.0, 0.0)
        ) { times[idx++] }.collectAll()

        val expected = (calculateExpectedPower(5.0, 0.0, 80.0) + calculateExpectedPower(10.0, 0.0, 80.0)) / 2.0
        assertEquals(expected, result.last(), absoluteTolerance = 0.001)
    }

    // ─── streamEstimatedPowerPerHour tests (StreamState filtering) ─────────────

    @Test
    fun `streaming state produces a power output`() = runBlocking {
        val speed = 10.0
        val weight = 80.0
        val now = 1000L
        val provider = mockProvider(
            speedStates = flowOf(streamingState(speed, DataType.Type.SPEED)),
            gradeStates = flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
        )

        val result = streamEstimatedPowerPerHour(weight, provider) { now }
            .take(1).toList()

        assertEquals(1, result.size)
        assertEquals(calculateExpectedPower(speed, 0.0, weight), result[0], absoluteTolerance = 0.001)
    }

    @Test
    fun `non-streaming speed state is filtered and does not produce output`() = runBlocking {
        val weight = 80.0
        val now = 1000L
        // First emission is NotAvailable (should be ignored), second is Streaming
        val provider = mockProvider(
            speedStates = flowOf(StreamState.NotAvailable, streamingState(10.0, DataType.Type.SPEED)),
            gradeStates = flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
        )

        // We only expect exactly 1 power value to appear, not 2
        val result = streamEstimatedPowerPerHour(weight, provider) { now }
            .take(1).toList()

        assertEquals(1, result.size)
    }

    @Test
    fun `power output is higher for higher speed`() = runBlocking {
        val weight = 80.0
        val now = 1000L

        val lowSpeedResult = streamEstimatedPowerPerHour(
            weight,
            mockProvider(
                flowOf(streamingState(5.0, DataType.Type.SPEED)),
                flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
            )
        ) { now }.take(1).toList().first()

        val highSpeedResult = streamEstimatedPowerPerHour(
            weight,
            mockProvider(
                flowOf(streamingState(15.0, DataType.Type.SPEED)),
                flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
            )
        ) { now }.take(1).toList().first()

        assertTrue(highSpeedResult > lowSpeedResult, "Higher speed should require more power")
    }

    @Test
    fun `power output scales with rider weight`() = runBlocking {
        val speed = 8.0
        val now = 1000L

        val lightResult = streamEstimatedPowerPerHour(
            60.0,
            mockProvider(
                flowOf(streamingState(speed, DataType.Type.SPEED)),
                flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
            )
        ) { now }.take(1).toList().first()

        val heavyResult = streamEstimatedPowerPerHour(
            100.0,
            mockProvider(
                flowOf(streamingState(speed, DataType.Type.SPEED)),
                flowOf(streamingState(0.0, DataType.Type.ELEVATION_GRADE))
            )
        ) { now }.take(1).toList().first()

        assertTrue(heavyResult > lightResult, "Heavier rider weight should yield higher power")
    }
}