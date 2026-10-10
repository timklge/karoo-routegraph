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

package de.timklge.karooroutegraph.pois

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.SampledElevationData
import de.timklge.karooroutegraph.screens.PoiSortOption
import io.hammerhead.karooext.models.Symbol
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiUtilsTest {

    private val metersPerDegree = 111_319.49

    /** A straight 1 km route along the equator from lng 0 to lng 1000 m. */
    private fun equatorRoute(): LineString = LineString.fromLngLats(
        listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(1000.0 / metersPerDegree, 0.0))
    )

    private fun poiAt(lat: Double, lng: Double, id: String = "poi", showOnlyAtRouteDistance: Float? = null) =
        POI(Symbol.POI(id = id, lat = lat, lng = lng), showOnlyAtRouteDistance = showOnlyAtRouteDistance)

    @Test
    fun `processPoiName translates the placeholder home name`() {
        assertEquals("Zu Hause", processPoiName("Startseite"))
        assertEquals("Cafe", processPoiName("Cafe"))
        assertNull(processPoiName(null))
    }

    @Test
    fun `nearest point on a segment is the projection onto it`() {
        val start = Point.fromLngLat(0.0, 0.0)
        val end = Point.fromLngLat(0.01, 0.0)

        val nearest = getNearestPointOnLine(Point.fromLngLat(0.005, 0.001), start, end)

        assertEquals(0.005, nearest.longitude(), absoluteTolerance = 1e-9)
        assertEquals(0.0, nearest.latitude(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `nearest point beyond the segment is clamped to its end`() {
        val start = Point.fromLngLat(0.0, 0.0)
        val end = Point.fromLngLat(0.01, 0.0)

        val beyond = getNearestPointOnLine(Point.fromLngLat(0.02, 0.0), start, end)
        val before = getNearestPointOnLine(Point.fromLngLat(-0.02, 0.0), start, end)

        assertEquals(0.01, beyond.longitude(), absoluteTolerance = 1e-9)
        assertEquals(0.0, before.longitude(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `nearest point of a degenerate segment is its start`() {
        val start = Point.fromLngLat(0.01, 0.02)

        val nearest = getNearestPointOnLine(Point.fromLngLat(0.0, 0.0), start, start)

        assertEquals(start.longitude(), nearest.longitude(), absoluteTolerance = 1e-9)
        assertEquals(start.latitude(), nearest.latitude(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `distance to a line is the closest segment distance`() {
        val line = equatorRoute().coordinates()

        val distance = getNearestPointOnLineDistance(Point.fromLngLat(0.0009, 0.001), line)

        assertEquals(0.001 * metersPerDegree, distance!!, absoluteTolerance = 1.0)
    }

    @Test
    fun `a line of fewer than two points has no distance`() {
        assertNull(getNearestPointOnLineDistance(Point.fromLngLat(0.0, 0.0), listOf(Point.fromLngLat(0.0, 0.0))))
    }

    @Test
    fun `a POI off the route keeps its distance to and its position along the route`() {
        val poi = poiAt(lat = 200.0 / metersPerDegree, lng = 500.0 / metersPerDegree)

        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 500.0)

        val nearest = distances.getValue(poi).single()
        assertEquals(500.0, nearest.distanceFromRouteStart.toDouble(), absoluteTolerance = 2.0)
        assertEquals(200.0, nearest.distanceFromPointOnRoute.toDouble(), absoluteTolerance = 2.0)
    }

    @Test
    fun `a POI further from the route than the limit has no nearest point`() {
        val poi = poiAt(lat = 1_000.0 / metersPerDegree, lng = 500.0 / metersPerDegree)

        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 500.0)

        assertTrue(distances.getValue(poi).isEmpty())
    }

    @Test
    fun `a POI restricted to another part of the route is dropped`() {
        val poi = poiAt(lat = 100.0 / metersPerDegree, lng = 500.0 / metersPerDegree, showOnlyAtRouteDistance = 900f)

        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 100.0)

        assertTrue(distances.getValue(poi).isEmpty())
    }

    @Test
    fun `distance ahead on the route is measured from the current position`() {
        val poi = poiAt(lat = 0.0, lng = 700.0 / metersPerDegree)
        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 500.0)

        val result = distanceToPoi(
            poi = poi.symbol,
            sampledElevationData = null,
            nearestPointsOnRouteToFoundPois = distances,
            currentPosition = null,
            selectedSort = PoiSortOption.AHEAD_ON_ROUTE,
            distanceAlongRoute = 400f
        )

        assertTrue(result is DistanceToPoiResult.AheadOnRouteDistance)
        assertEquals(300.0, result.distanceOnRoute, absoluteTolerance = 2.0)
        assertEquals(0.0, result.distanceFromPointOnRoute, absoluteTolerance = 2.0)
    }

    @Test
    fun `distance ahead on the route reports the climb to the POI`() {
        val poi = poiAt(lat = 0.0, lng = 750.0 / metersPerDegree)
        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 500.0)
        val elevation = SampledElevationData(100f, FloatArray(11) { 100f + it * 10f })

        val result = distanceToPoi(
            poi = poi.symbol,
            sampledElevationData = elevation,
            nearestPointsOnRouteToFoundPois = distances,
            currentPosition = null,
            selectedSort = PoiSortOption.AHEAD_ON_ROUTE,
            distanceAlongRoute = 0f
        )

        assertTrue(result is DistanceToPoiResult.AheadOnRouteDistance)
        assertEquals(70.0, result.elevationMetersRemaining!!, absoluteTolerance = 1.0)
    }

    @Test
    fun `linear sorting measures the straight line to the rider`() {
        val poi = poiAt(lat = 300.0 / metersPerDegree, lng = 400.0 / metersPerDegree)
        val rider = Point.fromLngLat(400.0 / metersPerDegree, 0.0)

        val result = distanceToPoi(
            poi = poi.symbol,
            sampledElevationData = null,
            nearestPointsOnRouteToFoundPois = null,
            currentPosition = rider,
            selectedSort = PoiSortOption.LINEAR_DISTANCE,
            distanceAlongRoute = 0f
        )

        assertEquals(300.0, (result as DistanceToPoiResult.LinearDistance).distance, absoluteTolerance = 2.0)
    }

    @Test
    fun `an unknown rider position yields no distance`() {
        val poi = poiAt(lat = 0.0, lng = 0.5)

        assertNull(
            distanceToPoi(
                poi = poi.symbol,
                sampledElevationData = null,
                nearestPointsOnRouteToFoundPois = null,
                currentPosition = null,
                selectedSort = PoiSortOption.LINEAR_DISTANCE,
                distanceAlongRoute = 0f
            )
        )
    }

    @Test
    fun `POIs ahead on the route sort before those behind it`() {
        val ahead = DistanceToPoiResult.AheadOnRouteDistance(100.0, 0.0, null)
        val behind = DistanceToPoiResult.AheadOnRouteDistance(-200.0, 0.0, null)

        assertTrue(ahead < behind)
    }

    @Test
    fun `POIs further behind the rider sort last among those behind`() {
        val close = DistanceToPoiResult.AheadOnRouteDistance(-50.0, 0.0, null)
        val far = DistanceToPoiResult.AheadOnRouteDistance(-100.0, 0.0, null)

        assertTrue(close < far)
    }

    @Test
    fun `POIs ahead on the route sort by distance ahead`() {
        val near = DistanceToPoiResult.AheadOnRouteDistance(100.0, 0.0, null)
        val far = DistanceToPoiResult.AheadOnRouteDistance(900.0, 0.0, null)

        assertTrue(near < far)
    }

    @Test
    fun `a POI off the route sorts after one on it`() {
        val onRoute = DistanceToPoiResult.AheadOnRouteDistance(900.0, 0.0, null)
        val offRoute = DistanceToPoiResult.LinearDistance(10.0)

        assertTrue(onRoute < offRoute)
    }

    @Test
    fun `formatDistance switches unit at a kilometre and a mile`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("999 m", formatDistance(999.0, isImperial = false))
            assertEquals("1.0 km", formatDistance(1_000.0, isImperial = false))
            assertEquals("1.5 km", formatDistance(1_500.0, isImperial = false))
            assertEquals("5249 ft", formatDistance(1_600.0, isImperial = true))
            assertEquals("1.0 mi", formatDistance(1_609.4, isImperial = true))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a POI on the route is at its own distance along it`() {
        val poi = poiAt(lat = 0.0, lng = 250.0 / metersPerDegree)

        val distances = calculatePoiDistances(equatorRoute(), listOf(poi), maxDistanceToRoute = 500.0)

        val nearest = distances.getValue(poi).single()
        assertEquals(250.0, nearest.distanceFromRouteStart.toDouble(), absoluteTolerance = 1.0)
        assertEquals(0.0, nearest.distanceFromPointOnRoute.toDouble(), absoluteTolerance = 1.0)
    }
}
