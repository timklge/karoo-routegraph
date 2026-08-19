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

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Utility functions for Web Mercator tile conversions.
 */
object TileUtils {
    /**
     * Converts tile X/Y coordinates at zoom Z to latitude and longitude in degrees.
     * Returns Pair(latitude, longitude).
     *
     * Uses the Web Mercator (Spherical Mercator) equations commonly used by slippy maps.
     */
    fun tileXYToLatLon(x: Int, y: Int, z: Int): Pair<Double, Double> {
        val n = 1 shl z
        val lon = x.toDouble() / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y.toDouble() / n)))
        val lat = latRad * 180.0 / PI
        return Pair(lat, lon)
    }

    fun locationToTileXY(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat * PI / 180.0
        val y = ((1.0 - (ln(tan(latRad) + 1.0 / cos(latRad)) / PI)) / 2.0 * n).toInt()
        return Pair(x, y)
    }
}

