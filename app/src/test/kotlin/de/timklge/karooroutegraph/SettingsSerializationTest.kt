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

import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import io.hammerhead.karooext.models.Symbol
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSerializationTest {

    @Test
    fun `an empty object decodes to the default settings`() {
        assertEquals(RouteGraphSettings(), jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>("{}"))
        assertEquals(RouteGraphPoiSettings(), jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>("{}"))
        assertEquals(RouteGraphTemporaryPOIs(), jsonWithUnknownKeys.decodeFromString<RouteGraphTemporaryPOIs>("{}"))
    }

    @Test
    fun `the shipped default string matches the defaults`() {
        assertEquals(
            RouteGraphSettings(),
            jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(RouteGraphSettings.defaultSettings)
        )
    }

    @Test
    fun `settings survive a round trip`() {
        val settings = RouteGraphSettings(
            showGradientIndicatorsOnMap = true,
            gradientIndicatorFrequency = GradientIndicatorFrequency.MAX,
            poiDistanceToRouteMaxMeters = 250.0,
            poiApproachAlertAtDistance = null,
            elevationProfileZoomLevels = listOf(1, 5),
            indicateSurfaceConditionsOnGraph = true
        )

        val decoded = jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(
            jsonWithUnknownKeys.encodeToString(settings)
        )

        assertEquals(settings, decoded)
        assertNull(decoded.poiApproachAlertAtDistance)
    }

    @Test
    fun `a settings file from a newer version is still readable`() {
        val json = """{"showGradientIndicatorsOnMap":true,"aSettingFromTheFuture":42}"""

        val decoded = jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(json)

        assertTrue(decoded.showGradientIndicatorsOnMap)
    }

    @Test
    fun `POI categories survive a round trip in the view settings`() {
        val settings = RouteGraphPoiSettings(
            autoAddPoiCategories = setOf(NearbyPoiCategory.SUPERMARKETS, NearbyPoiCategory.DRINKING_WATER),
            autoAddPoisToMap = true
        )

        val decoded = jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>(
            jsonWithUnknownKeys.encodeToString(settings)
        )

        assertEquals(settings, decoded)
    }

    @Test
    fun `temporary POIs survive a round trip with their numeric keys and opening hours`() {
        val poi = Symbol.POI(
            id = "123",
            lat = 60.17,
            lng = 24.94,
            type = Symbol.POI.Types.GENERIC,
            name = "Kiosk"
        )
        val temporary = RouteGraphTemporaryPOIs(
            poisByOsmId = mapOf(123L to poi),
            poiIdOpeningHours = mapOf("123" to "Mo-Su 08-23")
        )

        val decoded = jsonWithUnknownKeys.decodeFromString<RouteGraphTemporaryPOIs>(
            jsonWithUnknownKeys.encodeToString(temporary)
        )

        assertEquals(poi, decoded.poisByOsmId[123L])
        assertEquals("Mo-Su 08-23", decoded.poiIdOpeningHours["123"])
    }

    @Test
    fun `POI categories are recognized from their OSM tags`() {
        assertEquals(NearbyPoiCategory.DRINKING_WATER, NearbyPoiCategory.fromTag(mapOf("amenity" to "drinking_water")))
        assertEquals(NearbyPoiCategory.GAS_STATIONS, NearbyPoiCategory.fromTag(mapOf("amenity" to "fuel")))
        assertEquals(NearbyPoiCategory.SUPERMARKETS, NearbyPoiCategory.fromTag(mapOf("shop" to "supermarket", "name" to "K")))
        assertEquals(NearbyPoiCategory.VIEWPOINT, NearbyPoiCategory.fromTag(mapOf("tourism" to "viewpoint")))
        assertNull(NearbyPoiCategory.fromTag(mapOf("amenity" to "bench")))
        assertNull(NearbyPoiCategory.fromTag(emptyMap()))
    }

    @Test
    fun `a POI category matches on the value of the tag, not just its key`() {
        assertNull(NearbyPoiCategory.fromTag(mapOf("amenity" to "restaurants")))
        assertEquals(NearbyPoiCategory.RESTAURANTS, NearbyPoiCategory.fromTag(mapOf("amenity" to "restaurant")))
    }
}
