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

