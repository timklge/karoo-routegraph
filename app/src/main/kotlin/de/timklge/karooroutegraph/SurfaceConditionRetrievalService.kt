package de.timklge.karooroutegraph

import android.content.Context
import android.os.Environment
import android.util.Log
import com.mapbox.geojson.LineString
import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.lang.Math.toRadians
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

class SurfaceConditionRetrievalService(
    private val context: Context,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
) {
    companion object {
        const val MAPFILE_SCAN_INTERVAL_MS = 60_000L * 5 // 5 minutes
    }

    data class MapFileInfo(
        val file: File,
        val boundingBox: BoundingBox,
    )

    enum class SurfaceCondition {
        GRAVEL,
        UNPAVED
    }

    data class SurfaceConditionSegment(
        val startMeters: Double,
        val endMeters: Double,
        val condition: SurfaceCondition,
    )

    private var knownMapfiles = setOf<MapFileInfo>()

    private fun hasExternalStoragePermission(): Boolean {
        val permission = android.Manifest.permission.READ_EXTERNAL_STORAGE
        val res = context.checkCallingOrSelfPermission(permission)

        return res == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private var mapScanJob: Job? = null

    private var surfaceConditionUpdateJob: Job? = null

    fun startMapScanJob() {
        mapScanJob = CoroutineScope(Dispatchers.IO).launch {
            context.streamSettings(karooSystemServiceProvider.karooSystemService).collectLatest { settings ->
                val isEnabled = settings.indicateSurfaceConditionsOnGraph
                if (!isEnabled) return@collectLatest

                do {
                    if (!hasExternalStoragePermission()) {
                        Log.w(KarooRouteGraphExtension.TAG, "Skip map scanning, no external storage permission")
                        delay(MAPFILE_SCAN_INTERVAL_MS)
                        continue
                    }

                    val mapDirectoryOnExternalStorage = File(Environment.getExternalStorageDirectory(), "offline")
                    if (!mapDirectoryOnExternalStorage.exists() || !mapDirectoryOnExternalStorage.isDirectory) {
                        Log.w(KarooRouteGraphExtension.TAG, "Map directory does not exist: ${mapDirectoryOnExternalStorage.absolutePath}")
                        delay(MAPFILE_SCAN_INTERVAL_MS)
                        continue
                    }

                    val mapFiles = mapDirectoryOnExternalStorage.listFiles { file ->
                        file.isFile && file.extension.equals("map", ignoreCase = true)
                    } ?: arrayOf()

                    knownMapfiles = mapFiles.map { file ->
                        val mapfile = MapFile(file)
                        val boundingBox = mapfile.mapFileInfo.boundingBox

                        MapFileInfo(
                            file = file,
                            boundingBox = boundingBox,
                        )
                    }.toSet()

                    delay(MAPFILE_SCAN_INTERVAL_MS)
                } while(true)
            }
        }
    }

    private fun getTilesIntersectedByPolyline(polyline: LineString, zoomLevel: Int = 18): Set<Tile> {
        val coords = polyline.coordinates()
        if (coords.isEmpty()) return emptySet()

        fun latLonToTile(latRaw: Double, lon: Double, z: Int): Pair<Int, Int> {
            val lat = latRaw.coerceIn(-85.05112878, 85.05112878)
            val n = 1 shl z
            val xTile = ((lon + 180.0) / 360.0 * n).toInt()
            val latRad = toRadians(lat)
            val yTile = ((1.0 - (ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI)) / 2.0 * n).toInt()
            return Pair(xTile, yTile)
        }

        val tiles = mutableSetOf<Tile>()
        var prev: Pair<Int, Int>? = null

        for (pt in coords) {
            val (tx, ty) = latLonToTile(pt.latitude(), pt.longitude(), zoomLevel)
            tiles.add(Tile(tx, ty, zoomLevel))

            prev?.let { (x0, y0) ->
                var x = x0
                var y = y0
                val x1 = tx
                val y1 = ty
                val dx = kotlin.math.abs(x1 - x)
                val dy = kotlin.math.abs(y1 - y)
                val sx = if (x < x1) 1 else -1
                val sy = if (y < y1) 1 else -1
                var err = if (dx > dy) dx else -dy

                while (true) {
                    tiles.add(Tile(x, y, zoomLevel))
                    if (x == x1 && y == y1) break
                    val e2 = 2 * err
                    if (e2 > -dy) {
                        err -= dy
                        x += sx
                    }
                    if (e2 < dx) {
                        err += dx
                        y += sy
                    }
                }
            }

            prev = Pair(tx, ty)
        }

        return tiles
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).pow(2) +
                cos(toRadians(lat1)) * cos(toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun calculateRouteDistances(lineString: LineString): List<Double> {
        val coords = lineString.coordinates()
        val distances = mutableListOf(0.0)

        for (i in 1 until coords.size) {
            val prev = coords[i - 1]
            val curr = coords[i]
            val segmentDist = haversineDistance(
                prev.latitude(), prev.longitude(),
                curr.latitude(), curr.longitude()
            )
            distances.add(distances.last() + segmentDist)
        }

        return distances
    }

    private fun getSurfaceConditionFromTags(tags: List<org.mapsforge.core.model.Tag>): SurfaceCondition? {
        val surfaceTag = tags.find { it.key.equals("surface", ignoreCase = true) }?.value?.lowercase()
        val trackTypeTag = tags.find { it.key.equals("tracktype", ignoreCase = true) }?.value?.lowercase()

        // Check for gravel surfaces
        val gravelSurfaces = setOf("gravel", "fine_gravel", "compacted", "pebblestone")
        if (surfaceTag in gravelSurfaces) {
            return SurfaceCondition.GRAVEL
        }

        // Check for unpaved surfaces
        val unpavedSurfaces = setOf("unpaved", "dirt", "ground", "earth", "grass", "sand", "mud")
        if (surfaceTag in unpavedSurfaces) {
            return SurfaceCondition.UNPAVED
        }

        // Check tracktype
        if (trackTypeTag in setOf("grade3", "grade4", "grade5")) {
            return SurfaceCondition.UNPAVED
        }

        return null
    }

    private fun pointToSegmentDistance(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double
    ): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) {
            return haversineDistance(px, py, x1, y1)
        }

        val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        val tClamped = t.coerceIn(0.0, 1.0)

        val closestX = x1 + tClamped * dx
        val closestY = y1 + tClamped * dy

        return haversineDistance(px, py, closestX, closestY)
    }

    private fun findClosestRouteSegment(
        lat: Double, lon: Double,
        lineString: LineString
    ): Pair<Int, Double>? {
        val coords = lineString.coordinates()
        var minDist = Double.MAX_VALUE
        var closestSegmentIdx = -1

        for (i in 0 until coords.size - 1) {
            val p1 = coords[i]
            val p2 = coords[i + 1]
            val dist = pointToSegmentDistance(
                lat, lon,
                p1.latitude(), p1.longitude(),
                p2.latitude(), p2.longitude()
            )

            if (dist < minDist) {
                minDist = dist
                closestSegmentIdx = i
            }
        }

        return if (closestSegmentIdx >= 0) Pair(closestSegmentIdx, minDist) else null
    }

    private fun buildSurfaceConditionSegments(
        lineString: LineString,
        mapfilesToTiles: Map<File, List<Tile>>
    ): List<SurfaceConditionSegment> {
        val routeDistances = calculateRouteDistances(lineString)
        val coords = lineString.coordinates()

        // Track surface conditions for each route segment
        val segmentConditions = mutableMapOf<Int, SurfaceCondition>()

        mapfilesToTiles.forEach { (file, tilesForMapfile) ->
            val mapfile = MapFile(file)

            tilesForMapfile.forEach { tile ->
                val mapdata = mapfile.readMapData(
                    org.mapsforge.core.model.Tile(tile.x, tile.y, tile.z.toByte(), 0)
                )

                mapdata.ways.forEach { way ->
                    val condition = getSurfaceConditionFromTags(way.tags) ?: return@forEach

                    // Check each way coordinate against route segments
                    // latLongs is a 2D array where each sub-array is a line string
                    for (wayLineString in way.latLongs) {
                        for (latLong in wayLineString) {
                            val result = findClosestRouteSegment(
                                latLong.latitude,
                                latLong.longitude,
                                lineString
                            )

                            if (result != null) {
                                val (segmentIdx, distance) = result
                                // Only consider if within 10 meters of route
                                if (distance < 10.0) {
                                    segmentConditions[segmentIdx] = condition
                                }
                            }
                        }
                    }
                }
            }
        }

        // Build continuous segments
        val segments = mutableListOf<SurfaceConditionSegment>()
        var currentCondition: SurfaceCondition? = null
        var segmentStart: Double? = null

        for (i in 0 until coords.size - 1) {
            val condition = segmentConditions[i]

            if (condition != null) {
                if (condition != currentCondition) {
                    // End previous segment if exists
                    if (currentCondition != null && segmentStart != null) {
                        segments.add(
                            SurfaceConditionSegment(
                                startMeters = segmentStart,
                                endMeters = routeDistances[i],
                                condition = currentCondition
                            )
                        )
                    }
                    // Start new segment
                    currentCondition = condition
                    segmentStart = routeDistances[i]
                }
            } else if (currentCondition != null && segmentStart != null) {
                // End current segment
                segments.add(
                    SurfaceConditionSegment(
                        startMeters = segmentStart,
                        endMeters = routeDistances[i],
                        condition = currentCondition
                    )
                )
                currentCondition = null
                segmentStart = null
            }
        }

        // Close final segment if exists
        if (currentCondition != null && segmentStart != null) {
            segments.add(
                SurfaceConditionSegment(
                    startMeters = segmentStart,
                    endMeters = routeDistances.last(),
                    condition = currentCondition
                )
            )
        }

        return segments
    }

    fun startSurfaceConditionUpdateJob() {
        surfaceConditionUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystemServiceProvider.stream<OnNavigationState>().collect { navigationState ->
                val polyline = if (navigationState.state is OnNavigationState.NavigationState.NavigatingRoute) {
                    (navigationState.state as OnNavigationState.NavigationState.NavigatingRoute).routePolyline
                } else if (navigationState.state is OnNavigationState.NavigationState.NavigatingToDestination) {
                    (navigationState.state as OnNavigationState.NavigationState.NavigatingToDestination).polyline
                } else {
                    null
                }

                if (polyline == null) return@collect

                val lineString = LineString.fromPolyline(polyline, 5)
                val tiles = getTilesIntersectedByPolyline(lineString, zoomLevel = 18)

                val tilesWithMapfiles = tiles.associateWith { tile ->
                    knownMapfiles.filter { mapfileInfo ->
                        val bbox = mapfileInfo.boundingBox
                        val (tileMinLat, tileMinLon) = TileUtils.tileXYToLatLon(tile.x, tile.y + 1, tile.z)
                        val (tileMaxLat, tileMaxLon) = TileUtils.tileXYToLatLon(tile.x + 1, tile.y, tile.z)

                        !(tileMaxLat <= bbox.minLatitude ||
                          tileMinLat >= bbox.maxLatitude ||
                          tileMaxLon <= bbox.minLongitude ||
                          tileMinLon >= bbox.maxLongitude)
                    }.map { it.file }
                }

                val mapfilesToTiles = tilesWithMapfiles.entries
                    .flatMap { (tile, mapfiles) ->
                        mapfiles.map { mapfile -> Pair(mapfile, tile) }
                    }
                    .groupBy({ it.first }, { it.second })

                val surfaceConditionSegments = buildSurfaceConditionSegments(lineString, mapfilesToTiles)

                Log.d(KarooRouteGraphExtension.TAG, "Found ${surfaceConditionSegments.size} surface condition segments")
                surfaceConditionSegments.forEach { segment ->
                    Log.d(KarooRouteGraphExtension.TAG,
                        "Segment: ${segment.startMeters}m - ${segment.endMeters}m: ${segment.condition}")
                }
            }
        }
    }
}