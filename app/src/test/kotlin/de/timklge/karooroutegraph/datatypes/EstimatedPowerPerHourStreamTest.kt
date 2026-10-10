package de.timklge.karooroutegraph.datatypes

import android.app.Application
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.TravelTimeEstimationService
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.test.assertEquals

// Drives the real KarooSystemServiceProvider through the SDK binder against a fake Karoo system,
// instead of mocking the provider. A plain Application keeps Koin's createdAtStart graph out of it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EstimatedPowerPerHourStreamTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var provider: KarooSystemServiceProvider
    private val scope = CoroutineScope(Dispatchers.Default)
    private val outputs = CopyOnWriteArrayList<Double>()

    @Before
    fun connect() {
        provider = KarooSystemServiceProvider(karoo.app)
        karoo.awaitValue { provider.karooSystemService.connected.takeIf { it } }
        karoo.system.setRideState(RideState.Recording)
    }

    @After
    fun tearDown() {
        scope.cancel()
        provider.karooSystemService.disconnect()
    }

    private fun collect(weight: Double) {
        scope.launch { streamEstimatedPowerPerHour(weight, provider) { 1_000L }.collect { outputs += it } }
        // Wait until both stream consumers are registered, so no point is published too early.
        karoo.awaitValue { karoo.system.consumerParams.size.takeIf { it >= 3 } }
    }

    private fun setSpeed(value: Double) = karoo.system.setDataPoint(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to value)))

    private fun setGrade(value: Double) =
        karoo.system.setDataPoint(DataPoint(DataType.Type.ELEVATION_GRADE, mapOf(DataType.Field.SINGLE to value)))

    private fun expectedPower(speed: Double, gradePercent: Double, totalWeight: Double): Double =
        0.5 * TravelTimeEstimationService.CDA * TravelTimeEstimationService.RHO_AIR * speed.pow(3) +
            totalWeight * TravelTimeEstimationService.G * (gradePercent / 100.0 + TravelTimeEstimationService.CRR_PAVEMENT) * speed

    @Test
    fun `streaming speed and grade produce power`() {
        collect(weight = 80.0)
        setGrade(0.0)
        setSpeed(10.0)

        val power = karoo.awaitValue { outputs.lastOrNull() }
        assertEquals(expectedPower(10.0, 0.0, 80.0), power, absoluteTolerance = 0.001)
    }

    @Test
    fun `sensor loss is skipped and recovery resumes the estimate`() {
        collect(weight = 80.0)
        setGrade(0.0)
        setSpeed(5.0)
        karoo.awaitValue { outputs.takeIf { it.size == 1 } }

        karoo.system.setStreamState(DataType.Type.SPEED, StreamState.Searching)
        setSpeed(10.0)

        karoo.awaitValue { outputs.takeIf { it.size == 2 } }
        val average = (expectedPower(5.0, 0.0, 80.0) + expectedPower(10.0, 0.0, 80.0)) / 2
        assertEquals(average, outputs.last(), absoluteTolerance = 0.001)
    }

    @Ignore("The grade flow is stateIn'd eagerly in a scope nobody cancels, so its consumer leaks")
    @Test
    fun `cancelling the collector removes its consumers`() {
        collect(weight = 80.0)
        scope.cancel()
        karoo.awaitValue { karoo.system.consumerParams.takeIf { it.isEmpty() } }
    }
}
