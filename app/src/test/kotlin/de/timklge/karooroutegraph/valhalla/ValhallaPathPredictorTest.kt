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

package de.timklge.karooroutegraph.valhalla

import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValhallaPathPredictorTest {
    @Test
    fun `keeps well spaced points`() {
        val points = listOf(point(51.0), point(51.001), point(51.002))

        assertEquals(points, deduplicate(points))
    }

    @Test
    fun `drops a current position that repeats the last ridden point`() {
        val ridden = listOf(point(51.0), point(51.001), point(51.002))
        val current = point(51.0020001)

        val result = deduplicate(ridden + current)

        assertEquals(listOf(point(51.0), point(51.001), current), result)
    }

    @Test
    fun `always keeps the current position as the last point`() {
        val points = listOf(point(51.0), point(51.000001), point(51.000002))

        val result = deduplicate(points)

        assertEquals(listOf(point(51.000002)), result)
    }

    @Test
    fun `no two points remain closer than the minimum distance`() {
        val points = (0..20).map { point(51.0 + it * 0.00002) }

        val result = deduplicate(points)

        assertTrue(result.size > 1)
        assertTrue(result.zipWithNext().all { (a, b) -> TurfMeasurement.distance(a, b, TurfConstants.UNIT_METERS) >= 1.0 })
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList(), deduplicate(emptyList()))
    }

    @Test
    fun `stationary rider never produces repeated points for map matching`() {
        val points = listOf(point(51.0), point(51.0), point(51.0), point(51.0), point(51.0))

        val result = deduplicate(points)

        assertTrue(result.size < 2)
    }

    @Test
    fun `general heading ignores travel further back than the lookback distance`() {
        val points = travel(START, 90.0 to 1_000.0, 0.0 to 150.0)

        assertBearing(0.0, trailingHeading(points, GENERAL_HEADING_LOOKBACK_METERS))
    }

    @Test
    fun `general heading is the average heading over the lookback distance`() {
        // 60 m east followed by 40 m north within the lookback window: length-weighted average heading is atan2(60, 40)
        val points = travel(START, 0.0 to 60.0, 90.0 to 60.0)

        assertBearing(Math.toDegrees(atan2(60.0, 40.0)), trailingHeading(points, GENERAL_HEADING_LOOKBACK_METERS))
    }

    @Test
    fun `general heading averages headings across north without wrapping to the wrong side`() {
        val points = travel(START, 350.0 to 50.0, 10.0 to 50.0)

        assertBearing(0.0, trailingHeading(points, GENERAL_HEADING_LOOKBACK_METERS))
    }

    @Test
    fun `candidate heading only uses the first lookback distance`() {
        val points = travel(START, 0.0 to 150.0, 90.0 to 100.0)

        assertBearing(0.0, leadingHeading(points, GENERAL_HEADING_LOOKBACK_METERS))
    }

    @Test
    fun `path behind the rider ends at the rider and excludes the edge ahead`() {
        val shape = travel(START, 0.0 to 200.0)
        val rider = moved(START, 0.0, 150.0)

        val behind = shapeBehind(shape, rider)

        assertEquals(rider, behind.last())
        assertEquals(shape.take(2), behind.dropLast(1))
    }

    @Test
    fun `rider just past a junction is on the road they moved onto even if the trace still names the crossing road`() {
        val eastWest = crossingRoad(START, 90.0)
        val northSouth = crossingRoad(START, 0.0)
        val rider = moved(START, 90.0, 10.0)

        val (edge, projection) = chooseCurrentEdge(
            listOf(candidate("north-south", northSouth, START), candidate("east-west", eastWest, rider)),
            rider, userHeading = 90.0, traceWayId = "north-south",
        )!!

        assertEquals("east-west", edge.wayId)
        assertEquals(rider.longitude(), projection.longitude(), 1e-9)
    }

    @Test
    fun `trace way is kept at a junction when the heading agrees with it`() {
        val eastWest = crossingRoad(START, 90.0)
        val northSouth = crossingRoad(START, 0.0)
        val rider = moved(START, 0.0, 2.0)

        val (edge, _) = chooseCurrentEdge(
            listOf(candidate("north-south", northSouth, START), candidate("east-west", eastWest, START)),
            rider, userHeading = 5.0, traceWayId = "north-south",
        )!!

        assertEquals("north-south", edge.wayId)
    }

    @Test
    fun `current edge is oriented in the direction of travel`() {
        val eastWest = crossingRoad(START, 90.0)
        val rider = moved(START, 270.0, 10.0)

        val (edge, _) = chooseCurrentEdge(
            listOf(candidate("east-west", eastWest, rider)),
            rider, userHeading = 270.0, traceWayId = "east-west",
        )!!

        assertTrue(edge.shape.first().longitude() > START.longitude(), "expected the edge to start east of the rider when heading west")
    }
}

private val START = Point.fromLngLat(13.7, 51.0)

private fun candidate(wayId: String, shape: List<Point>, projection: Point) = CurrentEdgeCandidate(wayId, shape, projection)

/** A road of 400 m in the given bearing through [center], with a vertex at the center. */
private fun crossingRoad(center: Point, bearingDegrees: Double): List<Point> {
    val backward = (bearingDegrees + 180.0) % 360.0
    return travel(moved(center, backward, 200.0), bearingDegrees to 200.0, bearingDegrees to 200.0)
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Point [meters] away from [from] in the direction [bearingDegrees] (0 = north, 90 = east). */
private fun moved(from: Point, bearingDegrees: Double, meters: Double): Point {
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * Math.PI / 180.0
    val bearing = Math.toRadians(bearingDegrees)
    val north = meters * cos(bearing)
    val east = meters * sin(bearing)

    return Point.fromLngLat(
        from.longitude() + east / (metersPerDegreeLatitude * cos(Math.toRadians(from.latitude()))),
        from.latitude() + north / metersPerDegreeLatitude,
    )
}

/** Polyline starting at [start] that follows each (bearing, meters) leg in turn. */
private fun travel(start: Point, vararg legs: Pair<Double, Double>): List<Point> {
    var current = start
    val points = mutableListOf(start)

    for ((bearing, meters) in legs) {
        current = moved(current, bearing, meters)
        points += current
    }

    return points
}

private fun assertBearing(expected: Double, actual: Double, toleranceDegrees: Double = 0.5) {
    val difference = abs((actual - expected + 540.0) % 360.0 - 180.0)
    assertTrue(difference <= toleranceDegrees, "expected bearing $expected but was $actual")
}

private fun point(latitude: Double) = Point.fromLngLat(13.7, latitude)
