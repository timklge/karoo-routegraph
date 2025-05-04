package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import java.lang.Math.pow
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

const val TARGET_TILE_SIZE = 512.0

fun LineString.getOSMZoomLevelToFit(
    screenWidthPx: Int,
    screenHeightPx: Int,
    paddingPx: Int = 15
): Float {
    val points = this.coordinates()
    if (points.isEmpty()) {
        return 18.0f // Default zoom for empty route as Float
    }

    // Max reasonable zoom for a single point or very small area
    val maxZoom = 18.0f // Use Float for maxZoom

    if (points.size == 1) {
        return maxZoom
    }

    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE

    for (point in points) {
        minLat = min(minLat, point.latitude())
        maxLat = max(maxLat, point.latitude())
        minLon = min(minLon, point.longitude())
        maxLon = max(maxLon, point.longitude())
    }

    val latSpan = maxLat - minLat
    val lonSpan = maxLon - minLon

    // Ensure effective dimensions are at least 1 pixel
    val effectiveWidth = (screenWidthPx - 2 * paddingPx).coerceAtLeast(1).toDouble()
    val effectiveHeight = (screenHeightPx - 2 * paddingPx).coerceAtLeast(1).toDouble()

    // Handle cases where the span is zero or negligible
    if (latSpan <= 1e-6 && lonSpan <= 1e-6) {
        return maxZoom
    }

    val zoomLon: Double = if (lonSpan > 1e-6) {
        // Calculate zoom level needed to fit longitude span using TARGET_TILE_SIZE
        log2((effectiveWidth * 360.0) / (lonSpan * TARGET_TILE_SIZE))
    } else {
        Double.POSITIVE_INFINITY // Effectively no constraint on width
    }

    val zoomLat: Double = if (latSpan > 1e-6) {
        // Calculate zoom level needed to fit latitude span using TARGET_TILE_SIZE
        // Note: This is an approximation ignoring Mercator projection distortion for latitude
        log2((effectiveHeight * 360.0) / (latSpan * TARGET_TILE_SIZE))
    } else {
        Double.POSITIVE_INFINITY // Effectively no constraint on height
    }

    // Choose the lowest zoom level that satisfies both constraints
    val zoom = min(zoomLat, zoomLon) // Removed floor()

    // Clamp the zoom level to a valid OSM range (e.g., 0-20)
    return zoom.toFloat().coerceIn(0.0f, maxZoom)
}

// Helper function: Longitude to Tile X coordinate (fractional)
fun lonToTileX(lon: Double, zoom: Int): Double {
    return ((lon + 180.0) / 360.0) * (1 shl zoom)
}

// Helper function: Latitude to Tile Y coordinate (fractional)
fun latToTileY(lat: Double, zoom: Int): Double {
    val latRad = Math.toRadians(lat)
    // Using the standard Mercator projection formula
    return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0) * (1 shl zoom)
}

fun getRequiredTiles(zoomLevel: Float, widthPx: Int, heightPx: Int, mapCenter: Point): Set<Tile> {
    val requiredTiles = mutableSetOf<Tile>()
    val intZoom = floor(zoomLevel).toInt() // Use the integer part for tile indexing

    // Calculate center tile coordinates using the float zoom level for accuracy
    val centerTileX = lonToTileX(mapCenter.longitude(), intZoom)
    val centerTileY = latToTileY(mapCenter.latitude(), intZoom)

    // Calculate how many *scaled* tiles are visible horizontally and vertically from the center
    // This calculation remains based on the visual scale, so float zoom is appropriate here
    val halfWidthInTiles = (widthPx / 2.0) / TARGET_TILE_SIZE
    val halfHeightInTiles = (heightPx / 2.0) / TARGET_TILE_SIZE

    // Calculate the fractional tile coordinates of the corners based on scaled tiles and float zoom
    val topLeftTileX = centerTileX - halfWidthInTiles
    val topLeftTileY = centerTileY - halfHeightInTiles
    val bottomRightTileX = centerTileX + halfWidthInTiles
    val bottomRightTileY = centerTileY + halfHeightInTiles

    // Determine the integer range of tiles needed based on the fractional coordinates
    // Use floor for min and ceil/floor for max to ensure full coverage at the integer zoom level
    val minTileX = floor(topLeftTileX).toInt()
    val maxTileX = floor(bottomRightTileX).toInt() // Use floor as tile indices are 0-based
    val minTileY = floor(topLeftTileY).toInt()
    val maxTileY = floor(bottomRightTileY).toInt() // Use floor as tile indices are 0-based

    // Iterate through the required tile range and add them to the set using the integer zoom level
    for (x in minTileX..maxTileX) {
        for (y in minTileY..maxTileY) {
            // Basic validation: Ensure tile coordinates are within valid range for the integer zoom level
            val maxTileIndex = pow(2.0, intZoom.toDouble()).toInt() - 1
            if (x in 0..maxTileIndex && y in 0..maxTileIndex) {
                requiredTiles.add(Tile(x, y, intZoom)) // Store tiles with integer zoom level
            }
        }
    }

    return requiredTiles
}

const val earthCircumference = 40075016.686 // meters

const val previewPolylineBase64 = "eXZwX0l3ZXlwQU5VYkBxQWxCfUNcS2BBfUF+QHxDZERiS3RAaENoQH5BYEB2QXZCfEdBUkNGZUBuQEdMQFhwQHBCTmxAekBsRmBAYEReekRyQ3JoQUJyQExkQU5oQ0h2QGxAbEV4QHRIWmpDYEBwQ3xAfkhUdkJAWEFsQGJAQmxBQEZgQUROUmZAfkBoQkhabkF2SU5iQmBBZk5oQWhNYkF8SmxBbE1uQG5IS2pQS3RJQXxEQGJBSn5DcEBsUT98QEN8QEt4QEl8QUR2QllKa0JaP2JBdUB4RUd6QElkQ0BmQ0F4QUd2QVFmQlN4QXVAfEJ3QXJEdUBiQ3lAbkRpQHJFWXpBQ1w/ekBGdkFKcEBWaEFUdEBGZEBCYENDeEBJbkBTdEBPUmNATG1BTl9BUl14QGNBSlZwSkpuQ2pAcFRGXlByQEpuREJiQUNOR0xJSEFMdkFqZ0BDbEBPWFdeUUxLQE1mQ0RuQkFgQEJSRlJkQHJAP0RCQUpCZkBeSkxIWkBSZkBsUkBAQGJBWHJKSGBCP2hAfEB0XEJoQlJmR1pmTUZ0QVxyTU5uRVJySWZAclBGVEZoQkVSQHhAekB+W3BBbGlAaEBuUkJsQmxAbFNoQGZUQVxLbEBDSk9US2RASWpBSXZCW3xDP1xDVExMXGxASD9OU0hFZEBWWGRBUHRGSmJGRHpAaEBgVmhAbFRUeEhAbEFKcERgQGBAXFBEP1pKaERiRnxQclZkQW5BYEF+QGZBckBmQWpAbkBWfFF8RmpNZEVyQXBAaEF2QHRAcEB2QH5AckFsQnBAbkFeeEBiQXJDdkBgRF52QlpiQ1R2Q3pAdk9UYERGaEBMfEBUYkFaZkFiQGxBZkB+QHRCdkNiQ0J2Q3hBTkRoQG5AYkJ8QmpCeENWZkBUYEJYdEREbkNBeEFPfkJHYkB9QHpFX0FiRz9OUWpCZ0B+QklyQUR0QVxsQ2RAcklmQWhMVmxESGJHQWpETWxHY0B0Sl9AfkRlQGZFYUNyUU1sQHVAcEdZbkNdaEZHdkQ/eENEdkNSZEZEUHRKc0NWQVZCWkpWUFBScExsUElWaUB1QGhAdEBIV35DaEVYZEBUakBSdkBKbEBmQHpLSnxAUHBAVGxAUk54QmJAekVHaFJpQnJDW3xAUXJHeUJiQ2VAZEt5Qn5HdUB+R29BaE59QmRAQk5GUE5UaEBoQHRERnRBQH5BRnBBSmpAbEB8QVxkQGBAWGJAUlxGbkFEZklEXEJeSGxAWlpWckBiQWBAckBiQH5AZEJyRW5DeEhefEBgQHZAZEBuQHZAbkBuQF5mQ3RAbkNoQHhCXmZDWmBDYEBmQVxURGRFckJsQFJ4SHBBfEFKbkBJVE1QU05XYkFfRGBAY0FUWWBAX0BkT31JbkBpQGBBY0FgQGdAfElhTXhAeUBmRmlDdFBfSURUekBmQ3tAZ0NFVW1BZ0JxQF9Ad0BxQHtASWlBdUBxQ31EZUFlQWFAY0FddUFbbUBTSV5fQkB5QEF5QERZYEBhQGpBZ0BMTUhRRls/X0BDWWNAc0NzQGVEV3dAX0BzQEV3QEBrQE5vQlZnQkxhQkpVYVZxXGNQX1R2S3dZZFN7aEB6QH1BZEBnQHZAaUFoQGtBckJjRWBAYUJ0QGFFeEBzQ2BBZ0NNT2NAc0BbXVdRZ0VfQ2NFYUN5QG1Ac0BtQGdAa0BfQnNCa0FnQm1BeUJtQGlBY0J1RG1AfUFrQWFFY0BpQntAfUVfQHVDeUN5X0BhQGlHV3NFS2dDRVdCUWRBd0J2QndEYkNnRGRBaUFPe0BvQGdGZUBxRll7RVFnRkl5RkF5RU53RUxjR0R7QF5zQmhAbVJMfUZBUWpAY0BiQE98QUhEe0JSd0ZCeUJ2QXtiQFJ3RUprQVhpQkhfQUBtQEF7QEVpQFNzQXVBbUVyQWtBakBzQHZBeUJYb0BPZUBNa0BFaUBFa0JFYUBLZUBbcUBPU01NcUBjQGBAdUBiQGNBbkB5QkplQExhQUp9QUJ7QT91Q0V9QFN5QUpPXV9BTlFnQHlBe0BhQmFAY0BjQXVAeUBzQGdAa0B7QGtBfUBfQmtAeUFzQm9IaUJrR2ZEbUhsR29Tb0JtR3JJX0tGSFhKREBKQWhAZUBuQndCTl1CW0lhQGRDc0NuRl9HaVJzekBvRXNSS1lrQ3NFZ0l1S3hGcVJ9R3VIbUF+REZIRU4="