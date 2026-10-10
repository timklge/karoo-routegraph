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

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileUtilsTest {

    @Test
    fun `tile zero at zoom one covers the north west corner`() {
        val (lat, lon) = TileUtils.tileXYToLatLon(0, 0, 1)

        assertEquals(-180.0, lon, absoluteTolerance = 1e-9)
        assertEquals(85.0511287, lat, absoluteTolerance = 1e-6)
    }

    @Test
    fun `the tile below the first is the equator and prime meridian`() {
        val (lat, lon) = TileUtils.tileXYToLatLon(1, 1, 1)

        assertEquals(0.0, lat, absoluteTolerance = 1e-9)
        assertEquals(0.0, lon, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the equator and prime meridian fall in the tile after the corner`() {
        assertEquals(1 to 1, TileUtils.locationToTileXY(0.0, 0.0, 1))
    }

    @Test
    fun `northern and eastern coordinates land in later tiles`() {
        val north = TileUtils.locationToTileXY(60.0, 0.0, 8)
        val south = TileUtils.locationToTileXY(-60.0, 0.0, 8)
        val east = TileUtils.locationToTileXY(0.0, 60.0, 8)
        val west = TileUtils.locationToTileXY(0.0, -60.0, 8)

        assertTrue(north.second < south.second, "north should have a smaller y than south")
        assertTrue(east.first > west.first, "east should have a larger x than west")
    }

    @Test
    fun `a point just inside a tile lands in that tile`() {
        for (zoom in 1..14) {
            val tileX = zoom * 7 % (1 shl zoom)
            val tileY = zoom * 3 % (1 shl zoom)
            val (lat, lon) = TileUtils.tileXYToLatLon(tileX, tileY, zoom)
            // Nudged inward: the corner itself is on the boundary, where rounding decides the tile.
            val (x, y) = TileUtils.locationToTileXY(lat - 1e-6, lon + 1e-6, zoom)

            assertEquals(tileX, x)
            assertEquals(tileY, y)
        }
    }

    @Test
    fun `a finer zoom splits a tile into more of them`() {
        val coarse = TileUtils.locationToTileXY(60.17, 24.94, 8)
        val fine = TileUtils.locationToTileXY(60.17, 24.94, 12)

        assertTrue(fine.first in (coarse.first * 16)..(coarse.first * 16 + 15))
        assertTrue(fine.second in (coarse.second * 16)..(coarse.second * 16 + 15))
    }

    @Test
    fun `moving a fraction of a tile keeps the tile index`() {
        val (x, y) = TileUtils.locationToTileXY(60.17, 24.94, 12)
        val (lat, lon) = TileUtils.tileXYToLatLon(x, y, 12)
        val (xEdge, yEdge) = TileUtils.locationToTileXY(lat - 1e-4, lon + 1e-4, 12)

        assertEquals(x, xEdge)
        assertEquals(y, yEdge)
        assertTrue(abs(lat - 60.17) < 1.0)
    }
}
