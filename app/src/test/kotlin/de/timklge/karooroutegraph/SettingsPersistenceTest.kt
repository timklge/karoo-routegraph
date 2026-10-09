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
import androidx.datastore.preferences.core.edit
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsPersistenceTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var provider: KarooSystemServiceProvider

    @Before
    fun connect() {
        runBlocking { karoo.app.dataStore.edit { it.clear() } }
        provider = KarooSystemServiceProvider(karoo.app)
        karoo.awaitValue { provider.karooSystemService.connected.takeIf { it } }
    }

    @After
    fun tearDown() {
        provider.karooSystemService.disconnect()
    }

    private fun <T> call(block: suspend () -> T): T {
        val pending = CoroutineScope(Dispatchers.Default).async { block() }
        return karoo.awaitValue(20_000) { pending.takeIf { it.isCompleted }?.getCompleted() }
    }

    @Test
    fun `settings fall back to the defaults when nothing is stored`() {
        assertEquals(RouteGraphSettings(), call { provider.currentSettings() })
    }

    @Test
    fun `saved settings are read back`() {
        call { provider.saveSettings { it.copy(poiDistanceToRouteMaxMeters = 123.0, showGradientIndicatorsOnMap = true) } }

        val settings = call { provider.currentSettings() }

        assertEquals(123.0, settings.poiDistanceToRouteMaxMeters)
        assertTrue(settings.showGradientIndicatorsOnMap)
    }

    @Test
    fun `a later save builds on what is already stored`() {
        call { provider.saveSettings { it.copy(poiDistanceToRouteMaxMeters = 100.0) } }
        call { provider.saveSettings { it.copy(showGradientIndicatorsOnMap = true) } }

        val settings = call { provider.currentSettings() }

        assertEquals(100.0, settings.poiDistanceToRouteMaxMeters)
        assertTrue(settings.showGradientIndicatorsOnMap)
    }

    @Test
    fun `an unreadable settings blob falls back to the defaults`() {
        runBlocking { karoo.app.dataStore.edit { it[settingsKey] = "{ not json" } }

        assertEquals(RouteGraphSettings(), call { provider.currentSettings() })
    }

    @Test
    fun `the settings stream falls back to the defaults as well`() {
        runBlocking { karoo.app.dataStore.edit { it[settingsKey] = "not json at all" } }

        val settings = call { provider.streamSettings().take(1).toList().first() }

        assertEquals(RouteGraphSettings(), settings)
    }

    @Test
    fun `the settings stream emits the stored change`() {
        call { provider.saveSettings { it.copy(poiDistanceToRouteMaxMeters = 100.0) } }

        val seen = CopyOnWriteArrayList<RouteGraphSettings>()
        val job = CoroutineScope(Dispatchers.Default).launch { provider.streamSettings().collect { seen += it } }
        try {
            karoo.awaitValue(20_000) { true.takeIf { seen.isNotEmpty() } }

            call { provider.saveSettings { it.copy(poiApproachAlertAtDistance = null) } }

            karoo.awaitValue(20_000) { seen.lastOrNull()?.takeIf { it.poiApproachAlertAtDistance == null } }
            assertEquals(100.0, seen.first().poiDistanceToRouteMaxMeters)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `view settings and settings are stored apart`() {
        call { provider.saveSettings { it.copy(poiDistanceToRouteMaxMeters = 100.0) } }
        call {
            provider.saveViewSettings {
                it.copy(autoAddPoiCategories = setOf(NearbyPoiCategory.SUPERMARKETS))
            }
        }

        val settings = call { provider.currentSettings() }
        val viewSettings = call { provider.currentViewSettings() }

        assertEquals(100.0, settings.poiDistanceToRouteMaxMeters)
        assertEquals(setOf(NearbyPoiCategory.SUPERMARKETS), viewSettings.autoAddPoiCategories)
    }

    @Test
    fun `view settings fall back to their own defaults`() {
        assertEquals(RouteGraphPoiSettings(), call { provider.currentViewSettings() })
    }

    @Test
    fun `temporary POIs are stored and streamed back`() {
        val poi = Symbol.POI(
            id = "99",
            lat = 60.17,
            lng = 24.94,
            type = Symbol.POI.Types.GENERIC,
            name = "Kiosk"
        )

        call { provider.saveTemporaryPOIs { it.copy(poisByOsmId = mapOf(99L to poi)) } }

        val stored = call { provider.streamTemporaryPOIs().take(1).toList().first() }

        assertEquals(poi, stored.poisByOsmId[99L])
    }
}
