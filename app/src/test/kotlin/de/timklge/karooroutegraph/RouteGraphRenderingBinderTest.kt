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

import androidx.datastore.preferences.core.edit
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers that the elevation graph views render a frame, with and without a route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RouteGraphTestApplication::class)
class RouteGraphRenderingBinderTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var host: FakeKarooHost

    private val config = ViewConfig(
        gridSize = 60 to 15,
        viewSize = 480 to 200,
        textSize = 30,
        alignment = ViewConfig.Alignment.CENTER,
        boundariesEnabled = true,
        preview = false
    )

    @Before
    fun startExtension() {
        runBlocking { karoo.app.dataStore.edit { it.clear() } }
        karoo.system.initialStreamState = StreamState.NotAvailable
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setGlobalPois(emptyList())

        host = karoo.host<KarooRouteGraphExtension>()
    }

    @After
    fun tearDown() {
        karoo.close()
        stopKoin()
    }

    private fun routePoints(meters: Double, lat: Double = 60.0): List<Pair<Double, Double>> {
        val degreesPerMeter = 1.0 / (111_319.49 * cos(Math.toRadians(lat)))
        val segments = maxOf(2, ceil(meters / 200.0).toInt())
        return (0..segments).map { step -> lat to meters * step / segments * degreesPerMeter }
    }

    /** A ride page is what makes a data field visible; without one the graphs draw nothing. */
    private fun showField(typeId: String) {
        karoo.system.showPage(listOf(DataType.dataTypeId("karoo-routegraph", typeId)))
    }

    private fun loadRoute() {
        val points = routePoints(4_000.0)
        karoo.system.setRoute(
            points,
            routeDistanceMeters = 4_000.0,
            elevationProfile = (0..40).map { step -> step * 100.0 to 100.0 + step * 4.0 }
        )
        val probe = host.startStream("distancetopoi")
        probe.await(20_000) { it is StreamState.Streaming }
        host.stopStream(probe)
    }

    @Test
    fun `the graph draws nothing while its field is off the ride page`() {
        val view = host.startView("verticalroutegraph", config)

        // A few draw ticks, then still nothing: the field is not on the active page.
        karoo.awaitValue(3_000) { true.takeIf { view.frames.isEmpty() } }
        assertTrue(view.frames.isEmpty(), "the graph drew although its field is not on the page")
    }

    @Test
    fun `the vertical route graph asks for a hint while no route is loaded`() {
        showField("verticalroutegraph")
        val view = host.startView("verticalroutegraph", config)

        val hint = karoo.awaitValue(20_000) {
            view.events.filterIsInstance<ShowCustomStreamState>()
                .firstOrNull { it.message == karoo.app.getString(R.string.no_route) }
        }

        assertEquals(karoo.app.getString(R.string.no_route), hint.message)
        view.awaitFrame(20_000)
    }

    @Test
    fun `the vertical route graph drops the hint once a route is loaded`() {
        showField("verticalroutegraph")
        loadRoute()

        val view = host.startView("verticalroutegraph", config)
        view.awaitFrame(20_000)

        karoo.awaitValue(20_000) {
            view.events.filterIsInstance<ShowCustomStreamState>().firstOrNull { it.message?.isEmpty() == true }
        }
        assertTrue(
            view.events.filterIsInstance<ShowCustomStreamState>().none { it.message == karoo.app.getString(R.string.no_route) },
            "the no-route hint was shown with a route loaded"
        )
    }

    @Test
    fun `the route graph renders a frame`() {
        showField("routegraph")
        val view = host.startView("routegraph", config)

        view.awaitFrame(20_000)
    }

    @Test
    fun `the route graph renders a frame with a route loaded`() {
        showField("routegraph")
        loadRoute()

        val view = host.startView("routegraph", config)

        view.awaitFrame(20_000)
    }
}
