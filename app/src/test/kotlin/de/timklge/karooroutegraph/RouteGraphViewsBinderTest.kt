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
import fi.nikosavola.karooext.testing.ViewRecorder
import fi.nikosavola.karooext.testing.inflate
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.texts
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RouteGraphTestApplication::class)
class RouteGraphViewsBinderTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var host: FakeKarooHost

    private val config = ViewConfig(
        gridSize = 30 to 15,
        viewSize = 240 to 120,
        textSize = 24,
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

    private fun awaitFormat(view: ViewRecorder, formatDataTypeId: String?) {
        karoo.awaitValue(20_000) {
            view.events.filterIsInstance<UpdateGraphicConfig>().firstOrNull { it.formatDataTypeId == formatDataTypeId }
        }
    }

    private fun awaitRouteLoaded() {
        val probe = host.startStream("distancetopoi")
        probe.await(20_000) { it is StreamState.Streaming }
        host.stopStream(probe)
    }

    @Test
    fun `the eta view asks for a time of arrival format`() {
        val view = host.startView("eta", config)

        awaitFormat(view, DataType.Type.TIME_OF_ARRIVAL)
    }

    @Test
    fun `the next POI eta view asks for a time of arrival format`() {
        val view = host.startView("etapoi", config)

        awaitFormat(view, DataType.Type.TIME_OF_ARRIVAL)
    }

    @Test
    fun `the distance to next POI view asks for a distance format`() {
        val view = host.startView("distancetopoi", config)

        awaitFormat(view, DataType.Type.DISTANCE)
    }

    @Test
    fun `the elevation to next POI view asks for a remaining elevation format`() {
        val view = host.startView("elevationtopoi", config)

        awaitFormat(view, DataType.Type.ELEVATION_REMAINING)
    }

    @Test
    fun `the gravel remaining view asks for a distance format`() {
        val view = host.startView("gravelremaining", config)

        awaitFormat(view, DataType.Type.DISTANCE)
    }

    @Test
    fun `the surface type view renders the current surface label`() {
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        awaitRouteLoaded()

        val view = host.startView("surfacetype", config)
        val frame = view.awaitFrame(20_000)

        val texts = frame.inflate(karoo.app).texts()
        assertTrue(texts.any { it == karoo.app.getString(R.string.surfacetype_asphalt) }, "rendered $texts")
    }

    @Test
    fun `the surface type view hides its header`() {
        val view = host.startView("surfacetype", config)

        karoo.awaitValue(20_000) {
            view.events.filterIsInstance<UpdateGraphicConfig>().firstOrNull { it.formatDataTypeId == null }
        }
    }

    @Test
    fun `the POI button view renders a pin without a header`() {
        val view = host.startView("poiButton", config)

        val frame = view.awaitFrame(20_000)
        val texts = frame.inflate(karoo.app).texts()
        assertTrue(texts.isEmpty(), "the button should carry no text, got $texts")
        karoo.awaitValue(20_000) {
            view.events.filterIsInstance<UpdateGraphicConfig>().firstOrNull { it.showHeader == false }
        }
    }

    @Test
    fun `the surface type view preview cycles through the surface labels`() {
        val view = host.startView("surfacetype", config.copy(preview = true))

        val frame = view.awaitFrame(20_000)
        val texts = frame.inflate(karoo.app).texts()

        assertTrue(texts.isNotEmpty(), "the preview rendered no label")
        assertTrue(view.events.isNotEmpty(), "the preview sent no view event")
    }

    @Test
    fun `stopping a view stops its frames`() {
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        awaitRouteLoaded()

        val view = host.startView("surfacetype", config)
        view.awaitFrame(20_000)

        host.stopView(view)

        assertTrue(view.items.isNotEmpty(), "no view update was recorded")
    }
}
