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
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.encodePolyline
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.runBlocking
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [RouteGraphUpdateManager] directly, so the view model it produces can be asserted without
 * a throttled data type in the way. The manager is a Koin singleton in the app; here it is built by
 * hand around the fake system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RouteGraphUpdateManagerTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var provider: KarooSystemServiceProvider
    private lateinit var viewModel: RouteGraphViewModelProvider
    private lateinit var manager: RouteGraphUpdateManager

    @Before
    fun setUp() {
        // The DataStore file can outlive the application of an earlier test in the same sandbox.
        runBlocking { karoo.app.dataStore.edit { it.clear() } }
        karoo.system.initialStreamState = StreamState.NotAvailable
        karoo.system.setUserProfile(FakeKarooSystem.metricProfile())
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setGlobalPois(emptyList())

        provider = KarooSystemServiceProvider(karoo.app)
        karoo.awaitValue { provider.karooSystemService.connected.takeIf { it } }

        viewModel = RouteGraphViewModelProvider()
        manager = RouteGraphUpdateManager(
            provider,
            viewModel,
            RouteGraphDisplayViewModelProvider(),
            karoo.app,
            OfflineNearbyPOIProvider(karoo.app, NearbyPOIPbfDownloadService(karoo.app)),
            AutoAddedPOIsViewModelProvider()
        )
    }

    @After
    fun tearDown() {
        manager.stop()
        provider.karooSystemService.disconnect()
    }

    private fun routePoints(meters: Double, lat: Double = 60.0): List<Pair<Double, Double>> {
        val degreesPerMeter = 1.0 / (111_319.49 * cos(Math.toRadians(lat)))
        val segments = maxOf(2, ceil(meters / 200.0).toInt())
        return (0..segments).map { step -> lat to meters * step / segments * degreesPerMeter }
    }

    private fun setDistanceRemaining(meters: Double, onRoute: Double = 1.0) {
        karoo.system.setDataPoint(
            DataPoint(
                DataType.Type.DISTANCE_TO_DESTINATION,
                mapOf(
                    DataType.Field.DISTANCE_TO_DESTINATION to meters,
                    DataType.Field.ON_ROUTE to onRoute
                )
            )
        )
    }

    private fun awaitViewModel(timeoutMs: Long = 30_000, probe: () -> Boolean) {
        karoo.awaitValue(timeoutMs) { true.takeIf { probe() } }
    }

    @Test
    fun `no navigation leaves the route fields empty`() {
        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.routeDistance == null && viewModel.viewModelFlow.value.poiDistances == null }

        val state = viewModel.viewModelFlow.value
        assertNull(state.knownRoute)
        assertFalse(state.navigatingToDestination)
    }

    @Test
    fun `a route with a remaining distance gives the distance ridden so far`() {
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0)
        karoo.system.setLocation(60.0, 0.0)
        setDistanceRemaining(400.0)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.distanceAlongRoute > 0f }

        val state = viewModel.viewModelFlow.value
        assertEquals(1_000f, state.routeDistance!!, absoluteTolerance = 5f)
        assertEquals(600f, state.distanceAlongRoute, absoluteTolerance = 5f)
        assertEquals(true, state.isOnRoute)
        assertTrue(state.poiDistances?.isNotEmpty() == true, "no POIs were matched to the route")
    }

    @Test
    fun `a rider with no remaining distance is not on the route`() {
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.routeDistance != null }

        val state = viewModel.viewModelFlow.value
        assertEquals(false, state.isOnRoute)
        assertEquals(0f, state.distanceAlongRoute)
    }

    @Test
    fun `an imperial profile flips the unit flag`() {
        karoo.system.setUserProfile(FakeKarooSystem.imperialProfile())
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.routeDistance != null }

        val state = viewModel.viewModelFlow.value
        assertTrue(state.isImperial)
        assertTrue(state.isElevationImperial)
    }

    @Test
    fun `the route elevation profile becomes sampled elevation data`() {
        val profile = (0..16).map { step -> step * 60.0 to 100.0 + step * 5.0 }
        karoo.system.setRoute(
            routePoints(1_000.0),
            routeDistanceMeters = 1_000.0,
            elevationProfile = profile
        )

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.sampledElevationData != null }

        val sampled = viewModel.viewModelFlow.value.sampledElevationData!!
        assertEquals(60f, sampled.interval)
        assertEquals(17, sampled.elevations.size)
    }

    @Test
    fun `a reversed route is followed in the other direction`() {
        val points = routePoints(1_000.0)
        karoo.system.setRoute(points, routeDistanceMeters = 1_000.0, reversed = true)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.knownRoute != null }

        val route = viewModel.viewModelFlow.value.knownRoute!!
        // The polyline is rounded to five decimals on the wire.
        assertEquals(points.last().first, route.coordinates().first().latitude(), absoluteTolerance = 1e-4)
        assertEquals(points.last().second, route.coordinates().first().longitude(), absoluteTolerance = 1e-4)
    }

    @Test
    fun `climbs reported while on the route are kept and categorized`() {
        karoo.system.setRoute(
            routePoints(2_000.0),
            routeDistanceMeters = 2_000.0,
            climbs = listOf(
                OnNavigationState.NavigationState.Climb(startDistance = 200.0, length = 1_000.0, grade = 9.0, totalElevation = 90.0),
                // A descent is not a climb, so it is dropped.
                OnNavigationState.NavigationState.Climb(startDistance = 1_400.0, length = 400.0, grade = -5.0, totalElevation = -20.0)
            )
        )
        karoo.system.setLocation(60.0, 0.0)
        setDistanceRemaining(1_500.0)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.climbs?.isNotEmpty() == true }

        val climbs = viewModel.viewModelFlow.value.climbs!!
        assertEquals(1, climbs.size)
        assertEquals(ClimbCategory.MEDIUM_CLIMB, climbs.single().category)
        assertEquals(200, climbs.single().startDistance)
        assertEquals(1_200, climbs.single().endDistance)
    }

    @Test
    fun `a climb is only picked up while the rider is on the route`() {
        karoo.system.setRoute(
            routePoints(2_000.0),
            routeDistanceMeters = 2_000.0,
            climbs = listOf(
                OnNavigationState.NavigationState.Climb(startDistance = 200.0, length = 1_000.0, grade = 9.0, totalElevation = 90.0)
            )
        )
        setDistanceRemaining(1_500.0, onRoute = 0.0)

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.routeDistance != null }

        assertNull(viewModel.viewModelFlow.value.climbs)
    }

    @Test
    fun `navigating to a destination is flagged and uses its polyline`() {
        val destination = Symbol.POI(
            id = "destination",
            lat = 60.1,
            lng = 0.02,
            type = Symbol.POI.Types.GENERIC,
            name = "Destination"
        )
        karoo.system.setNavigation(
            OnNavigationState.NavigationState.NavigatingToDestination(
                destination = destination,
                polyline = encodePolyline(routePoints(2_000.0)),
                elevationPolyline = null,
                climbs = emptyList()
            )
        )

        manager.start()

        awaitViewModel { viewModel.viewModelFlow.value.knownRoute != null }

        val state = viewModel.viewModelFlow.value
        assertTrue(state.navigatingToDestination)
        val expectedLength = TurfMeasurement.length(
            LineString.fromLngLats(routePoints(2_000.0).map { Point.fromLngLat(it.second, it.first) }),
            TurfConstants.UNIT_METERS
        )
        assertEquals(expectedLength.toFloat(), state.routeDistance!!, absoluteTolerance = 5f)
    }

    @Test
    fun `ending navigation clears the route`() {
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0)

        manager.start()
        awaitViewModel { viewModel.viewModelFlow.value.knownRoute != null }

        karoo.system.setNavigation(OnNavigationState.NavigationState.Idle)

        awaitViewModel { viewModel.viewModelFlow.value.knownRoute == null }
        assertNull(viewModel.viewModelFlow.value.routeDistance)
    }

    @Test
    fun `going off the route keeps the last known position on it`() {
        karoo.system.setRoute(routePoints(2_000.0), routeDistanceMeters = 2_000.0)
        karoo.system.setLocation(60.0, 0.0)
        setDistanceRemaining(1_000.0)

        manager.start()
        awaitViewModel { viewModel.viewModelFlow.value.distanceAlongRoute > 0f }
        val onRoutePosition = viewModel.viewModelFlow.value.lastKnownPositionOnMainRoute
        assertTrue(onRoutePosition != null, "no position was recorded while on the route")

        // The rider leaves the route: no remaining distance is reported any more.
        karoo.system.setStreamState(DataType.Type.DISTANCE_TO_DESTINATION, StreamState.Searching)

        awaitViewModel { viewModel.viewModelFlow.value.isOnRoute == false }
        assertEquals(onRoutePosition, viewModel.viewModelFlow.value.lastKnownPositionOnMainRoute)
    }

    @Test
    fun `the rider profile drives the POI distance limit used for matching`() {
        // A POI 700 m off the route is dropped at the default 500 m limit.
        val poi = Symbol.POI(
            id = "far",
            lat = 60.0 + 700.0 / 111_319.49,
            lng = 0.005,
            type = Symbol.POI.Types.GENERIC,
            name = "Far"
        )
        karoo.system.setRoute(routePoints(1_000.0), routeDistanceMeters = 1_000.0, pois = listOf(poi))

        manager.start()
        awaitViewModel { viewModel.viewModelFlow.value.poiDistances != null }

        val far = viewModel.viewModelFlow.value.poiDistances!!.entries.single { it.key.symbol.id == "far" }

        assertTrue(far.value.isEmpty(), "a POI beyond the distance limit was matched to the route")
    }
}
