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
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the POI approach alert, which the extension starts as a Koin singleton at application
 * start and drives from the route, the rider position and the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RouteGraphTestApplication::class)
class RouteGraphAlertBinderTest {
    @get:Rule val karoo = FakeKarooRule()

    @Before
    fun prepare() {
        runBlocking { karoo.app.dataStore.edit { it.clear() } }
        karoo.system.initialStreamState = StreamState.NotAvailable
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setGlobalPois(emptyList())
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

    private fun routeLengthMeters(points: List<Pair<Double, Double>>): Double =
        TurfMeasurement.length(
            LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) }),
            TurfConstants.UNIT_METERS
        )

    /** Puts a POI halfway along a 2 km route and the rider 400 m short of it. */
    private fun approachAFountain() {
        val points = routePoints(2_000.0)
        val midpoint = points[points.size / 2]
        val fountain = Symbol.POI(
            id = "fountain",
            lat = midpoint.first,
            lng = midpoint.second,
            type = Symbol.POI.Types.WATER,
            name = "Fountain"
        )
        karoo.system.setRoute(points, routeDistanceMeters = routeLengthMeters(points), pois = listOf(fountain))
        karoo.system.setLocation(points.first().first, points.first().second)
        karoo.system.setDataPoint(
            DataPoint(
                DataType.Type.DISTANCE_TO_DESTINATION,
                mapOf(
                    DataType.Field.DISTANCE_TO_DESTINATION to 1_400.0,
                    DataType.Field.ON_ROUTE to 1.0
                )
            )
        )
    }

    @Test
    fun `a POI the rider is approaching raises an alert and a beep`() {
        karoo.host<KarooRouteGraphExtension>()
        approachAFountain()

        val alert = karoo.awaitEffect<InRideAlert>(30_000) { it.detail.orEmpty().contains("Fountain") }

        assertEquals(karoo.app.getString(R.string.poi_approach), alert.title)
        // 400 m plus the small difference between the route length and the polyline the host sent.
        assertTrue(alert.detail.orEmpty().matches(Regex("Fountain in 40\\d m")), "unexpected detail: ${alert.detail}")
        assertEquals(10_000L, alert.autoDismissMs)
        assertTrue(
            shadowOf(karoo.app).broadcastIntents.any { it.action == "de.timklge.HIDE_POWERBAR" },
            "the alert did not hide other ride fields"
        )

        val beep = karoo.awaitEffect<PlayBeepPattern>(10_000)
        assertEquals(
            listOf(PlayBeepPattern.Tone(2_500, 250), PlayBeepPattern.Tone(2_800, 250), PlayBeepPattern.Tone(2_500, 250)),
            beep.tones
        )
    }

    @Test
    fun `the alert is not repeated within the reminder interval`() {
        karoo.host<KarooRouteGraphExtension>()
        approachAFountain()

        karoo.awaitEffect<InRideAlert>(30_000) { it.detail.orEmpty().contains("Fountain") }
        val mark = karoo.system.effects.size

        karoo.assertNoEffect<InRideAlert>(forMs = 8_000, after = mark)
    }

    @Test
    fun `no alert is raised while approach alerts are switched off`() {
        karoo.app.updateStoredSettings { it.copy(poiApproachAlertAtDistance = null) }
        karoo.host<KarooRouteGraphExtension>()
        approachAFountain()
        // Give the alert scheduler its first pass plus the throttle window.
        karoo.awaitValue(8_000) { karoo.system.consumerParams.takeIf { it.isNotEmpty() } }

        karoo.assertNoEffect<InRideAlert>(forMs = 8_000)
    }

    @Test
    fun `a POI further ahead than the alert distance stays quiet`() {
        karoo.host<KarooRouteGraphExtension>()
        val points = routePoints(2_000.0)
        val midpoint = points[points.size / 2]
        val fountain = Symbol.POI(
            id = "fountain",
            lat = midpoint.first,
            lng = midpoint.second,
            type = Symbol.POI.Types.WATER,
            name = "Fountain"
        )
        karoo.system.setRoute(points, routeDistanceMeters = routeLengthMeters(points), pois = listOf(fountain))
        karoo.system.setLocation(points.first().first, points.first().second)
        // The rider is 1 km short of the POI, well beyond the 500 m alert distance.
        karoo.system.setDataPoint(
            DataPoint(
                DataType.Type.DISTANCE_TO_DESTINATION,
                mapOf(
                    DataType.Field.DISTANCE_TO_DESTINATION to 1_900.0,
                    DataType.Field.ON_ROUTE to 1.0
                )
            )
        )

        karoo.assertNoEffect<InRideAlert>(forMs = 8_000)
    }
}
