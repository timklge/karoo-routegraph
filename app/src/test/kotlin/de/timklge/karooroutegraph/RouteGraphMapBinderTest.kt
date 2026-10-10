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
import androidx.datastore.preferences.core.stringPreferencesKey
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RouteGraphTestApplication::class)
class RouteGraphMapBinderTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var host: FakeKarooHost
    private val temporaryPoisKey = stringPreferencesKey("temporaryPOIs")

    private val temporaryPoi = Symbol.POI(
        id = "poi-7",
        lat = 60.17,
        lng = 24.94,
        type = Symbol.POI.Types.SHOPPING,
        name = "Kiosk"
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

    private fun storeTemporaryPoi(vararg pois: Symbol.POI) {
        val stored = RouteGraphTemporaryPOIs(poisByOsmId = pois.associateBy { it.id.substringAfterLast('-').toLong() })
        val encoded = jsonWithUnknownKeys.encodeToString(stored)
        runBlocking { karoo.app.dataStore.edit { it[temporaryPoisKey] = encoded } }
    }

    @Test
    fun `the map session starts by hiding what it drew before`() {
        val map = host.startMap()

        map.await(10_000) { it is HideSymbols }
    }

    @Test
    fun `a stored temporary POI is drawn on the map`() {
        storeTemporaryPoi(temporaryPoi)

        val map = host.startMap()

        val drawn = karoo.awaitValue(20_000) { map.visibleSymbols()["poi-7"] }
        assertEquals("Kiosk", (drawn as Symbol.POI).name)
    }

    @Test
    fun `the drawn POI is removed from the map when the session stops`() {
        storeTemporaryPoi(temporaryPoi)
        val map = host.startMap()
        karoo.awaitValue(20_000) { map.visibleSymbols()["poi-7"] }

        host.stopMap(map)

        assertTrue(karoo.awaitValue(20_000) { true.takeIf { map.visibleSymbols().isEmpty() } })
    }

    @Test
    fun `a POI added while riding is drawn without restarting the map`() {
        val map = host.startMap()
        map.await(10_000) { it is HideSymbols }

        storeTemporaryPoi(temporaryPoi)

        val drawn = karoo.awaitValue(30_000) { map.visibleSymbols()["poi-7"] }
        assertEquals("Kiosk", (drawn as Symbol.POI).name)
    }

    @Test
    fun `the map draws no POIs when none are stored`() {
        val map = host.startMap()

        karoo.awaitValue(20_000) { true.takeIf { map.items.isNotEmpty() } }

        assertTrue(map.visibleSymbols().isEmpty(), "unexpected symbols: ${map.visibleSymbols()}")
    }
}
