package de.timklge.karooroutegraph.datatypes

import de.timklge.karooroutegraph.TravelTimeEstimationService
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.math.pow
import kotlin.test.assertEquals

class AveragePowerEstimationTest {

    private fun <T> Flow<T>.lastValue(): T? {
        var last: T? = null
        runBlocking { collect { last = it } }
        @Suppress("UNCHECKED_CAST")
        return last
    }

    private fun calculatePower(speed: Double, gradePercent: Double): Double {
        return 0.5 * TravelTimeEstimationService.CDA * TravelTimeEstimationService.RHO_AIR * speed.pow(3) +
                80.0 * TravelTimeEstimationService.G * (gradePercent / 100.0 + TravelTimeEstimationService.CRR_PAVEMENT) * speed
    }

    @Test
    fun `single reading returns that power`() {
        val now = 1000L
        val speed = 10.0
        val grade = 0.0
        val flow = flowOf(EstimatedPowerRecord(speed, grade, RideState.Recording))
        val result = flow.averagePowerOverHour(80.0) { now }.lastValue()
        assertEquals(calculatePower(speed, grade), result)
    }

    @Test
    fun `two readings return correct average`() {
        val now = 1000L
        val speed1 = 10.0
        val speed2 = 20.0
        val grade = 0.0
        val flow = flowOf(EstimatedPowerRecord(speed1, grade, RideState.Recording), EstimatedPowerRecord(speed2, grade,
            RideState.Recording))
        val result = flow.averagePowerOverHour(80.0) { now }.lastValue()
        val expectedPower = (calculatePower(speed1, grade) + calculatePower(speed2, grade)) / 2.0
        assertEquals(expectedPower, result)
    }

    @Test
    fun `old readings are excluded from average`() {
        val now = 1000L
        val oneHourAgo = now - 3600000L - 1
        val flow = flowOf(EstimatedPowerRecord(10.0, 0.0, RideState.Recording), EstimatedPowerRecord(20.0, 0.0, RideState.Recording))
        val timestamps = listOf(oneHourAgo, now)
        var idx = 0
        val result = flow.averagePowerOverHour(80.0) { timestamps[idx++] }.lastValue()
        assertEquals(calculatePower(20.0, 0.0), result ?: 0.0, 0.01)
    }

    @Test
    fun `readings just under hour boundary are included`() {
        val now = 1000L
        val almostOneHourAgo = now - 3600000L + 1
        val flow = flowOf(EstimatedPowerRecord(10.0, 0.0, RideState.Recording), EstimatedPowerRecord(20.0, 0.0, RideState.Recording))
        val timestamps = listOf(almostOneHourAgo, now)
        var idx = 0
        val result = flow.averagePowerOverHour(80.0) { timestamps[idx++] }.lastValue()
        val expectedPower = (calculatePower(10.0, 0.0) + calculatePower(20.0, 0.0)) / 2.0
        assertEquals(expectedPower, result)
    }

    @Test
    fun `multiple old readings expire leaving only recent ones`() {
        val now = 1000L
        val twoHoursAgo = now - 7200000L
        val flow = flowOf(EstimatedPowerRecord(5.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(5.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(5.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(10.0, 0.0, RideState.Recording))
        val timestamps = listOf(twoHoursAgo, twoHoursAgo, twoHoursAgo, now)
        var idx = 0
        val result = flow.averagePowerOverHour(80.0) { timestamps[idx++] }.lastValue()
        assertEquals(calculatePower(10.0, 0.0), result)
    }

    @Test
    fun `average calculation is correct for varied speeds`() {
        val now = 1000L
        val flow = flowOf(EstimatedPowerRecord(8.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(9.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(10.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(11.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(12.0, 0.0, RideState.Recording))
        val result = flow.averagePowerOverHour(80.0) { now }.lastValue()
        val expectedPower = listOf(8.0, 9.0, 10.0, 11.0, 12.0).map { calculatePower(it, 0.0) }.average()
        assertEquals(expectedPower, result!!, absoluteTolerance = 0.001)
    }

    @Test
    fun `reading at exactly one hour ago is expired`() {
        val now = 1000L
        val exactlyOneHourAgo = now - 3600000L
        val flow = flowOf(EstimatedPowerRecord(10.0, 0.0, RideState.Recording),
            EstimatedPowerRecord(20.0, 0.0, RideState.Recording))
        val timestamps = listOf(exactlyOneHourAgo, now)
        var idx = 0
        val result = flow.averagePowerOverHour(80.0) { timestamps[idx++] }.lastValue()
        assertEquals(calculatePower(20.0, 0.0), result ?: 0.0, 0.01)
    }

    @Test
    fun `grade affects power calculation`() {
        val now = 1000L
        val speed = 10.0
        val gradeFlat = 0.0
        val gradeUphill = 5.0
        val flowFlat = flowOf(EstimatedPowerRecord(speed, gradeFlat, RideState.Recording))
        val flowUphill = flowOf(EstimatedPowerRecord(speed, gradeUphill, RideState.Recording))
        val resultFlat = flowFlat.averagePowerOverHour(80.0) { now }.lastValue()
        val resultUphill = flowUphill.averagePowerOverHour(80.0) { now }.lastValue()
        assert(resultUphill!! > resultFlat!!)
    }
}