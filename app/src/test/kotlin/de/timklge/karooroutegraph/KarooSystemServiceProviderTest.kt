/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.timklge.karooroutegraph

import android.app.Application
import de.timklge.karooroutegraph.datatypes.streamPowerPerHour
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.SensorPoints
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drives [KarooSystemServiceProvider] straight against the fake system, without the extension. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KarooSystemServiceProviderTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var provider: KarooSystemServiceProvider
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun connect() {
        // Data types the extension does not drive have no state, so a stream of theirs would stay
        // silent and hold a combine closed.
        karoo.system.initialStreamState = StreamState.NotAvailable
        provider = KarooSystemServiceProvider(karoo.app)
        karoo.awaitValue { provider.karooSystemService.connected.takeIf { it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        provider.karooSystemService.disconnect()
    }

    @Test
    fun `a late consumer still gets the latest stream point`() {
        karoo.system.setDataPoint(DataType.Type.SPEED, 7.5)
        val values = CopyOnWriteArrayList<StreamState>()

        scope.launch { provider.streamDataFlow(DataType.Type.SPEED).collect { values += it } }

        val state = karoo.awaitValue { values.lastOrNull() } as StreamState.Streaming
        assertEquals(7.5, state.dataPoint.values.getValue(DataType.Field.SINGLE))
    }

    @Test
    fun `cancelling a collector releases its consumer`() {
        val before = karoo.system.consumerCount
        val job = scope.launch { provider.streamDataFlow(DataType.Type.SPEED).collect {} }
        karoo.awaitValue { true.takeIf { karoo.system.hasStreamConsumer(DataType.Type.SPEED) } }

        job.cancel()

        karoo.awaitValue { true.takeIf { karoo.system.consumerCount == before } }
    }

    @Test
    fun `the ride state is replayed to a new consumer`() {
        karoo.system.setRideState(RideState.Paused(false))
        val states = CopyOnWriteArrayList<RideState>()

        scope.launch { provider.streamRideState().collect { states += it } }

        assertEquals(RideState.Paused(false), karoo.awaitValue { states.lastOrNull() })
    }

    @Test
    fun `the radar swim lane appears with a target and hides once it is gone`() {
        val visible = CopyOnWriteArrayList<Boolean>()
        scope.launch { provider.streamRadarSwimLaneIsVisible().collect { visible += it } }
        karoo.awaitValue { true.takeIf { visible.isNotEmpty() } }
        assertEquals(false, visible.first())

        karoo.system.setDataPoint(SensorPoints.radar(0, 30.0))
        karoo.awaitValue { visible.lastOrNull()?.takeIf { it } }

        karoo.system.setStreamState(DataType.Type.RADAR, StreamState.NotAvailable)

        // The lane stays for a moment after the target disappears, so it does not flicker.
        karoo.awaitValue(15_000) { visible.lastOrNull()?.takeIf { !it } }
    }

    @Test
    fun `the measured hourly power is preferred over the estimate`() {
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        val power = CopyOnWriteArrayList<Double?>()

        scope.launch { streamPowerPerHour(provider).collect { power += it } }
        karoo.awaitValue { true.takeIf { power.isNotEmpty() } }

        karoo.system.setDataPoint(DataType.Type.SMOOTHED_1HR_AVERAGE_POWER, 210.0)

        assertEquals(210.0, karoo.awaitValue { power.lastOrNull() })
    }

    @Test
    fun `the estimate is used while no measured power is available`() {
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        val power = CopyOnWriteArrayList<Double?>()

        scope.launch { streamPowerPerHour(provider).collect { power += it } }
        karoo.awaitValue { true.takeIf { power.isNotEmpty() } }

        karoo.system.setDataPoint(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to 10.0)))
        karoo.system.setDataPoint(DataPoint(DataType.Type.ELEVATION_GRADE, mapOf(DataType.Field.SINGLE to 0.0)))

        val estimated = karoo.awaitValue { power.lastOrNull() }
        val expected = 0.5 * TravelTimeEstimationService.CDA * TravelTimeEstimationService.RHO_AIR * 10.0.pow(3) +
            85.0 * TravelTimeEstimationService.G * TravelTimeEstimationService.CRR_PAVEMENT * 10.0

        assertEquals(expected, estimated!!, absoluteTolerance = 0.001)
    }

    @Test
    fun `no power is reported before any input arrives`() {
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        val power = CopyOnWriteArrayList<Double?>()

        scope.launch { streamPowerPerHour(provider).collect { power += it } }
        karoo.awaitValue { true.takeIf { power.isNotEmpty() } }

        assertNull(power.first())
    }

    @Test
    fun `a paused ride produces no power estimate`() {
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Paused(false))
        val power = CopyOnWriteArrayList<Double?>()

        scope.launch { streamPowerPerHour(provider).collect { power += it } }
        karoo.awaitValue { true.takeIf { power.isNotEmpty() } }

        karoo.system.setDataPoint(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to 10.0)))

        karoo.awaitValue(1_000) { true }
        assertNull(power.last())
    }
}
