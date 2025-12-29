package de.timklge.karooroutegraph.datatypes.minimap

import android.util.Log
import androidx.annotation.DrawableRes
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants.UNIT_METERS
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.Tile
import java.lang.Math.pow
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

const val TARGET_TILE_SIZE = 700.0

@DrawableRes
fun mapPoiToIcon(poiType: String): Int {
    return when (poiType) {
        "aid_station" -> R.drawable.bx_first_aid // Closest to aid station
        "atm" -> R.drawable.bx_money_withdraw
        "bar" -> R.drawable.bx_beer
        "bike_parking" -> R.drawable.bxs_parking
        "bike_share" -> R.drawable.bx_share_alt
        "bike_shop" -> R.drawable.bx_store
        "camping" -> R.drawable.bxs_tree
        "caution" -> R.drawable.bx_info_circle
        "coffee" -> R.drawable.bx_coffee
        "control" -> R.drawable.bxs_flag_alt
        "convenience_store" -> R.drawable.bx_store
        "ferry" -> R.drawable.bxs_ship
        "first_aid" -> R.drawable.bx_first_aid
        "food" -> R.drawable.bx_baguette
        "gas_station" -> R.drawable.bx_gas_pump
        "generic" -> R.drawable.bx_info_circle
        "geocache" -> R.drawable.bx_package
        "home" -> R.drawable.bx_home
        "hospital" -> R.drawable.bx_first_aid
        "library" -> R.drawable.bx_library
        "lodging" -> R.drawable.bx_hotel
        "monument" -> R.drawable.bxs_tree
        "park" -> R.drawable.bxs_tree
        "parking" -> R.drawable.bxs_parking
        "rest_stop" -> R.drawable.bx_hotel
        "restroom" -> R.drawable.bx_walk
        "shopping" -> R.drawable.bx_store
        "shower" -> R.drawable.bx_shower
        "summit" -> R.drawable.bx_landscape
        "swimming" -> R.drawable.bx_swim
        "trailhead" -> R.drawable.trip
        "transit_center" -> R.drawable.bx_train
        "viewpoint" -> R.drawable.bxs_tree
        "water" -> R.drawable.bx_water
        "winery" -> R.drawable.bxs_wine
        else -> R.drawable.bxmap // Default icon
    }
}

fun LineString.getCenterPoint(): Point {
    if (this.coordinates().isEmpty()) {
        return Point.fromLngLat(0.0, 0.0) // Default point for empty LineString
    }

    var minLng = Double.MAX_VALUE
    var maxLng = Double.MIN_VALUE
    var minLat = Double.MAX_VALUE
    var maxLat = Double.MIN_VALUE

    for (point in this.coordinates()) {
        val lng = point.longitude()
        val lat = point.latitude()

        minLng = minOf(minLng, lng)
        maxLng = maxOf(maxLng, lng)
        minLat = minOf(minLat, lat)
        maxLat = maxOf(maxLat, lat)
    }

    return Point.fromLngLat((minLng + maxLng) / 2, (minLat + maxLat) / 2)
}

fun LineString.getOSMZoomLevelToFit(
    screenWidthPx: Int,
    screenHeightPx: Int,
    paddingPx: Int = 10
): Float {
    if (this.coordinates().isEmpty()) {
        return 15f // Default zoom level for empty LineString
    }

    // Find the bounding box of the LineString
    var minLng = Double.MAX_VALUE
    var maxLng = Double.MIN_VALUE
    var minLat = Double.MAX_VALUE
    var maxLat = Double.MIN_VALUE

    for (point in this.coordinates()) {
        val lng = point.longitude()
        val lat = point.latitude()

        minLng = minOf(minLng, lng)
        maxLng = maxOf(maxLng, lng)
        minLat = minOf(minLat, lat)
        maxLat = maxOf(maxLat, lat)
    }

    // Effective screen dimensions accounting for padding
    val effectiveWidth = screenWidthPx - (2 * paddingPx)
    val effectiveHeight = screenHeightPx - (2 * paddingPx)

    if (effectiveWidth <= 0 || effectiveHeight <= 0) {
        return 15f // Default zoom if effective dimensions are invalid
    }

    // Calculate the aspect ratios
    val bboxWidthDegrees = maxLng - minLng
    val bboxHeightDegrees = maxLat - minLat
    val bboxAspect = bboxWidthDegrees / bboxHeightDegrees
    val screenAspect = effectiveWidth.toDouble() / effectiveHeight.toDouble()

    // Determine which dimension is the limiting factor
    val widthLimited = bboxAspect > screenAspect

    // Binary search for optimal zoom level
    var low = 1f
    var high = 19f
    var bestZoom = 1f
    val threshold = 0.01 // 1% threshold for stopping the search

    while (high - low > threshold) {
        val mid = (low + high) / 2
        val zoom = mid

        // Convert geographic coordinates to tile coordinates at current zoom
        val minTileX = lonToTileX(minLng, zoom)
        val maxTileX = lonToTileX(maxLng, zoom)
        val minTileY = latToTileY(maxLat, zoom) // Note the swap: max lat → min y
        val maxTileY = latToTileY(minLat, zoom) // Note the swap: min lat → max y

        // Calculate tile width and height
        val tileWidth = maxTileX - minTileX
        val tileHeight = maxTileY - minTileY

        // Convert to pixels (each tile is 256px at OSM standard)
        val pixelWidth = tileWidth * TARGET_TILE_SIZE
        val pixelHeight = tileHeight * TARGET_TILE_SIZE

        // Check if this zoom level fits within the effective screen dimensions
        val fitsWidth = pixelWidth <= effectiveWidth
        val fitsHeight = pixelHeight <= effectiveHeight

        if (fitsWidth && fitsHeight) {
            // We can try a higher zoom level
            bestZoom = zoom
            low = mid
        } else {
            // We need to zoom out more
            high = mid
        }
    }

    return bestZoom.coerceIn(1f, 19f)
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

// Helper function: Longitude to Tile X coordinate with fractional zoom
fun lonToTileX(lon: Double, zoom: Float): Double {
    return ((lon + 180.0) / 360.0) * (1 shl zoom.toInt()) * (2.0f).pow(zoom - zoom.toInt())
}

// Helper function: Latitude to Tile Y coordinate with fractional zoom
fun latToTileY(lat: Double, zoom: Float): Double {
    val latRad = Math.toRadians(lat)
    val mercator = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0
    return mercator * (1 shl zoom.toInt()) * (2.0f).pow(zoom - zoom.toInt())
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

fun LineString.previewRemainingRoute(zoomLevel: Float, distanceAlongRoute: Float?, screenWidthPixels: Int, screenHeightPixels: Int): Point? {
    val points = this.coordinates()
    if (points.isEmpty()) {
        return null
    }

    val remainingPath = if (distanceAlongRoute != null) {
        try {
            TurfMisc.lineSliceAlong(this, distanceAlongRoute.toDouble(), TurfMeasurement.length(this, UNIT_METERS), UNIT_METERS)
        } catch(e: Exception) {
            Log.e(TAG, "Error slicing route: ${e.message}")
            this
        }
    } else {
        this
    }

    // If no remaining path, return null
    val remainingPoints = remainingPath.coordinates()
    if (remainingPoints.isEmpty()) {
        return null
    }

    // Calculate the viewport dimensions in meters based on zoom level
    val standardTileSize = 256.0
    val tileScaleFactor = TARGET_TILE_SIZE / standardTileSize
    val metersPerPixel = 156543.03392 * cos(Math.toRadians(remainingPoints[0].latitude())) /
            (2.0.pow(zoomLevel.toDouble()) * tileScaleFactor)
    val viewportWidthMeters = screenWidthPixels * metersPerPixel
    val viewportHeightMeters = screenHeightPixels * metersPerPixel
    val shortAxisMeters = min(viewportWidthMeters, viewportHeightMeters)

    // Get the start point (must always be visible)
    val startPoint = remainingPoints[0]

    // If remaining path is very short, just return a point that keeps the start visible
    val pathLength = TurfMeasurement.length(remainingPath, UNIT_METERS)
    if (pathLength < shortAxisMeters / 2) {
        // For short paths, return a point slightly ahead of the start point
        val lookAheadDistance = min(pathLength, shortAxisMeters * 0.3)
        val aheadPoint = TurfMeasurement.along(remainingPath, lookAheadDistance, UNIT_METERS)

        // Calculate a point between start and ahead point to keep both visible
        return Point.fromLngLat(
            (startPoint.longitude() + aheadPoint.longitude()) / 2,
            (startPoint.latitude() + aheadPoint.latitude()) / 2
        )
    }

    // For longer paths, calculate how much we can show while keeping start point visible
    // We'll show enough path to fill about 60% of the viewport width / height, whichever is smaller
    val visiblePathLength = min(pathLength, shortAxisMeters * 0.6)
    val visiblePath = try {
        TurfMisc.lineSliceAlong(remainingPath, 0.0, visiblePathLength, UNIT_METERS)
    } catch (e: Exception) {
        Log.e(TAG, "Error slicing visible path: ${e.message}")
        remainingPath
    }

    // Get the bounds of the visible path
    val visiblePoints = visiblePath.coordinates()
    var minLat = startPoint.latitude()  // Start with start point as initial bounds
    var maxLat = startPoint.latitude()
    var minLng = startPoint.longitude()
    var maxLng = startPoint.longitude()

    for (point in visiblePoints) {
        minLat = min(minLat, point.latitude())
        maxLat = max(maxLat, point.latitude())
        minLng = min(minLng, point.longitude())
        maxLng = max(maxLng, point.longitude())
    }

    // Calculate center of the bounding box, ensuring start point is within view
    val centerLat = (minLat + maxLat) / 2
    val centerLng = (minLng + maxLng) / 2

    // Default case: return center of the visible path bounds
    return Point.fromLngLat(centerLng, centerLat)
}
