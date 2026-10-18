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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun distance(a: Point, b: Point): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude() - a.latitude())
    val dLng = Math.toRadians(b.longitude() - a.longitude())
    val sinLat = sin(dLat / 2)
    val sinLng = sin(dLng / 2)

    val h = sinLat * sinLat + cos(Math.toRadians(a.latitude())) * cos(Math.toRadians(b.latitude())) * sinLng * sinLng
    return 2 * earthRadius * Math.asin(sqrt(h))
}

internal fun bearing(from: Point, to: Point): Double {
    val fromLat = Math.toRadians(from.latitude())
    val toLat = Math.toRadians(to.latitude())
    val dLng = Math.toRadians(to.longitude() - from.longitude())

    val y = sin(dLng) * cos(toLat)
    val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(dLng)

    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

internal fun angularDifference(a: Double, b: Double): Double = abs((((a - b + 180.0) % 360.0) + 360.0) % 360.0 - 180.0)

internal fun interpolate(from: Point, to: Point, fraction: Double): Point =
    Point.fromLngLat(
        from.longitude() + (to.longitude() - from.longitude()) * fraction,
        from.latitude() + (to.latitude() - from.latitude()) * fraction,
    )
