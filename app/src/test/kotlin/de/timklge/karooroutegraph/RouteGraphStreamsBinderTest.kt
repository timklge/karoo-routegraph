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

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RouteGraphTestApplication::class)
class RouteGraphStreamsBinderTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var host: FakeKarooHost

    @Before
    fun startExtension() {
        // The DataStore file outlives the application of an earlier test in this class.
        runBlocking { karoo.app.dataStore.edit { it.clear() } }

        // The extension combines streams a test does not drive, which stay silent without this.
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

    /**
     * A route heading east from [lat], [meters] long, as the fake's lat to lng pairs. Segments stay
     * shorter than the POI distance window, or the synthetic end-of-route POI is dropped.
     */
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

    private fun setDistanceRemaining(meters: Double) {
        karoo.system.setDataPoint(
            DataPoint(
                DataType.Type.DISTANCE_TO_DESTINATION,
                mapOf(DataType.Field.DISTANCE_TO_DESTINATION to meters)
            )
        )
    }

    /**
     * The distance-to-POI stream has no throttle, so a Streaming point here means the route reached
     * the view model behind every data type. The throttled types would otherwise only see it after
     * their 20 s window, or after a second route change.
     */
    private fun awaitRouteLoaded() {
        val probe = host.startStream("distancetopoi")
        probe.await(20_000) { it is StreamState.Streaming }
        host.stopStream(probe)
    }

    private fun singleValue(state: StreamState?): Double {
        assertTrue(state is StreamState.Streaming, "expected a streaming state, got $state")
        return state.dataPoint.values.getValue(DataType.Field.SINGLE)
    }

    @Test
    fun `eta is NotAvailable while no route is loaded`() {
        val stream = host.startStream("eta")

        assertEquals(StreamState.NotAvailable, stream.await(20_000) { true })
    }

    @Test
    fun `eta streams an arrival time derived from the route and the rider profile`() {
        karoo.system.setRoute(routePoints(10_000.0), routeDistanceMeters = 10_000.0)
        awaitRouteLoaded()

        val stream = host.startStream("eta")
        val arrival = singleValue(stream.await(20_000) { it is StreamState.Streaming })

        val travelTime = TravelTimeEstimationService().estimateTravelTime(
            routeElevationData = null,
            startDistance = 0.0,
            endDistance = 10_000.0,
            totalWeight = 85.0,
            profileFtp = 250.0,
            lastHourAvgPower = null,
            surfaceConditions = emptyList()
        ).toLong(DurationUnit.MILLISECONDS)

        val expected = System.currentTimeMillis() + travelTime
        assertTrue(abs(arrival - expected) < 5_000, "arrival $arrival is not within 5 s of $expected")
    }

    @Test
    fun `eta falls back to NotAvailable when navigation ends`() {
        karoo.system.setRoute(routePoints(5_000.0), routeDistanceMeters = 5_000.0)
        awaitRouteLoaded()

        val stream = host.startStream("eta")
        stream.await(20_000) { it is StreamState.Streaming }

        // The surface stream turns NotAvailable exactly when the route leaves the view model, so it
        // is the cheap probe for the route being gone.
        val probe = host.startStream("surfacetype")
        probe.await(20_000) { it is StreamState.Streaming }
        val mark = probe.mark()
        karoo.system.setNavigation(OnNavigationState.NavigationState.Idle)
        probe.await(20_000, after = mark) { it is StreamState.NotAvailable }
        host.stopStream(probe)

        val restarted = host.startStream("eta")
        assertEquals(StreamState.NotAvailable, restarted.await(20_000) { true })
    }

    @Test
    fun `distance to next POI points at the route end and follows the rider`() {
        val points = routePoints(1_000.0)
        val routeLength = routeLengthMeters(points)
        karoo.system.setRoute(points, routeDistanceMeters = routeLength)
        awaitRouteLoaded()

        // Without a location the extension cannot turn the remaining distance into progress along
        // the route, so the rider would stay at the route start.
        karoo.system.setLocation(points.first().first, points.first().second)

        val stream = host.startStream("distancetopoi")
        assertEquals(routeLength, singleValue(stream.await(20_000) { it is StreamState.Streaming }), absoluteTolerance = 5.0)

        setDistanceRemaining(400.0)
        val ahead = karoo.awaitValue(30_000) {
            singleValueOrNull(stream.items.lastOrNull())?.takeIf { it < 700.0 }
        }

        assertEquals(400.0, ahead, absoluteTolerance = 5.0)
    }

    @Test
    fun `the eta at the next POI is earlier than the one at the route end`() {
        val points = routePoints(2_000.0)
        val routeLength = routeLengthMeters(points)
        val midpoint = points[points.size / 2]
        val waypoint = Symbol.POI(
            id = "waypoint",
            lat = midpoint.first,
            lng = midpoint.second,
            type = Symbol.POI.Types.GENERIC,
            name = "Waypoint"
        )
        karoo.system.setRoute(points, routeDistanceMeters = routeLength, pois = listOf(waypoint))
        awaitRouteLoaded()

        val toRouteEnd = singleValue(host.startStream("eta").await(20_000) { it is StreamState.Streaming })
        val toWaypoint = singleValue(host.startStream("etapoi").await(20_000) { it is StreamState.Streaming })

        assertTrue(
            toWaypoint < toRouteEnd,
            "arrival at the POI ($toWaypoint) should be before the route end ($toRouteEnd)"
        )
    }

    @Test
    fun `the eta at the next POI is NotAvailable without a route`() {
        val stream = host.startStream("etapoi")

        assertEquals(StreamState.NotAvailable, stream.await(20_000) { true })
    }

    @Test
    fun `distance to next POI is NotAvailable without a route`() {
        val stream = host.startStream("distancetopoi")

        assertEquals(StreamState.NotAvailable, stream.await(20_000) { true })
    }

    @Test
    fun `a POI on the route is nearer than the route end`() {
        val points = routePoints(1_000.0)
        val routeLength = routeLengthMeters(points)
        val midpoint = points[points.size / 2]
        val waypoint = Symbol.POI(
            id = "waypoint",
            lat = midpoint.first,
            lng = midpoint.second,
            type = Symbol.POI.Types.GENERIC,
            name = "Waypoint"
        )
        karoo.system.setRoute(points, routeDistanceMeters = routeLength, pois = listOf(waypoint))
        awaitRouteLoaded()

        val distanceToWaypoint = TurfMeasurement.distance(
            Point.fromLngLat(points.first().second, points.first().first),
            Point.fromLngLat(midpoint.second, midpoint.first),
            TurfConstants.UNIT_METERS
        )
        val stream = host.startStream("distancetopoi")
        val ahead = singleValue(stream.await(20_000) { it is StreamState.Streaming })

        assertEquals(distanceToWaypoint, ahead, absoluteTolerance = 10.0)
    }

    @Test
    fun `elevation to next POI is the climb remaining on the route`() {
        val points = routePoints(1_000.0)
        // 5 % grade, sampled on the interval the extension uses for a 1 km route.
        val profile = (0..16).map { step -> step * 60.0 to 100.0 + step * 3.0 }
        karoo.system.setRoute(
            points,
            routeDistanceMeters = routeLengthMeters(points),
            elevationProfile = profile
        )
        awaitRouteLoaded()

        val stream = host.startStream("elevationtopoi")
        val climb = singleValue(stream.await(20_000) { it is StreamState.Streaming })

        assertEquals(48.0, climb, absoluteTolerance = 4.0)
    }

    @Test
    fun `a flat elevation profile reports no climb to the next POI`() {
        val points = routePoints(1_000.0)
        val profile = (0..16).map { step -> step * 60.0 to 120.0 }
        karoo.system.setRoute(
            points,
            routeDistanceMeters = routeLengthMeters(points),
            elevationProfile = profile
        )
        awaitRouteLoaded()

        val stream = host.startStream("elevationtopoi")

        assertEquals(0.0, singleValue(stream.await(20_000) { it is StreamState.Streaming }), absoluteTolerance = 0.001)
    }

    @Test
    fun `surface type is paved on a route without map data`() {
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        awaitRouteLoaded()

        val stream = host.startStream("surfacetype")

        assertEquals(0.0, singleValue(stream.await(20_000) { it is StreamState.Streaming }))
    }

    @Test
    fun `surface type is NotAvailable without a route`() {
        val stream = host.startStream("surfacetype")

        assertEquals(StreamState.NotAvailable, stream.await(20_000) { true })
    }

    @Test
    fun `gravel remaining stays NotAvailable while surface conditions are switched off`() {
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        awaitRouteLoaded()

        val stream = host.startStream("gravelremaining")

        assertEquals(StreamState.NotAvailable, stream.await(20_000) { true })
    }

    @Test
    fun `gravel remaining is zero once surface conditions are computed with no mapfiles`() {
        karoo.app.updateStoredSettings { it.copy(indicateSurfaceConditionsOnGraph = true) }
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        awaitRouteLoaded()

        val stream = host.startStream("gravelremaining")

        assertEquals(0.0, singleValue(stream.await(20_000) { it is StreamState.Streaming }))
    }

    @Ignore("The grade flow is stateIn'd eagerly in a scope nobody cancels, so its consumer leaks")
    @Test
    fun `stopping a stream releases the consumers it registered`() {
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0)
        awaitRouteLoaded()

        val before = karoo.system.consumerCount
        val stream = host.startStream("eta")
        stream.await(20_000) { it is StreamState.Streaming }
        assertTrue(karoo.system.consumerCount > before, "the data type registered no consumer")

        host.stopStream(stream)

        karoo.awaitValue(20_000) { true.takeIf { karoo.system.consumerCount == before } }
    }

    private fun singleValueOrNull(state: StreamState?): Double? =
        (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SINGLE)
}
