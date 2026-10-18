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

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point

private const val MIN_TRACE_POINT_DISTANCE_METERS = 25.0

internal fun decodePolyline(encoded: String): List<Point> = LineString.fromPolyline(encoded, 6).coordinates()

internal fun deduplicate(points: List<Point>): List<Point> {
    if (points.isEmpty()) return points

    val deduped = mutableListOf(points.first())
    for (point in points.dropLast(1)) {
        if (distance(deduped.last(), point) >= MIN_TRACE_POINT_DISTANCE_METERS) deduped += point
    }

    while (deduped.isNotEmpty() && distance(deduped.last(), points.last()) < MIN_TRACE_POINT_DISTANCE_METERS) {
        deduped.removeAt(deduped.size - 1)
    }

    return deduped + points.last()
}

/** The part of an oriented edge shape that lies ahead of the given point. */
internal fun remainderFrom(shape: List<Point>, startPoint: Point): List<Point> =
    listOf(startPoint) + shape.subList(nearestPointIndex(shape, startPoint) + 1, shape.size)

/** The part of an oriented edge shape that lies behind the given point, ending at that point. */
internal fun shapeBehind(shape: List<Point>, point: Point): List<Point> =
    shape.subList(0, nearestPointIndex(shape, point) + 1) + point

internal fun pathLength(points: List<Point>): Double =
    points.zipWithNext { a, b -> distance(a, b) }.sum()

internal fun nearestPointIndex(shape: List<Point>, point: Point): Int {
    var bestIndex = 0
    var bestDistance = Double.MAX_VALUE

    for (i in shape.indices) {
        val distance = distance(shape[i], point)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
        }
    }

    return bestIndex
}

internal fun headingNear(shape: List<Point>, index: Int): Double =
    if (index < shape.size - 1) bearing(shape[index], shape[index + 1]) else bearing(shape[index - 1], shape[index])

/** Bearing from the point [lookbackMeters] before the end of [points] to its end (or from its start if shorter). */
internal fun trailingHeading(points: List<Point>, lookbackMeters: Double): Double =
    bearing(pointAtDistance(points.asReversed(), lookbackMeters), points.last())

/** Bearing from the start of [points] to the point [lookbackMeters] along it (or to its end if shorter). */
internal fun leadingHeading(points: List<Point>, lookbackMeters: Double): Double =
    bearing(points.first(), pointAtDistance(points, lookbackMeters))

/** The point [meters] along [points] from its first point, or its last point if the path is shorter. */
private fun pointAtDistance(points: List<Point>, meters: Double): Point {
    var travelled = 0.0

    for (i in 1 until points.size) {
        val segmentLength = distance(points[i - 1], points[i])

        if (segmentLength > 0.0 && travelled + segmentLength >= meters) {
            return interpolate(points[i - 1], points[i], (meters - travelled) / segmentLength)
        }

        travelled += segmentLength
    }

    return points.last()
}

internal fun trimToDistance(points: List<Point>, maxDistance: Double): List<Point> {
    val trimmed = mutableListOf(points.first())
    var travelled = 0.0

    for (i in 1 until points.size) {
        val segmentLength = distance(points[i - 1], points[i])

        if (travelled + segmentLength >= maxDistance) {
            trimmed += interpolate(points[i - 1], points[i], (maxDistance - travelled) / segmentLength)
            return trimmed
        }

        travelled += segmentLength
        trimmed += points[i]
    }

    return trimmed
}
