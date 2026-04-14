package de.timklge.karooroutegraph

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Environment
import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.pois.getNearestPointOnLineDistance
import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.time.Instant

fun isNightMode(applicationContext: Context): Boolean {
    val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
}

// Surface condition paints with hatched patterns
fun getSurfaceConditionPaints(applicationContext: Context, isNightMode: Boolean) = mapOf(
    SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL to Paint().apply {
        style = Paint.Style.FILL
        alpha = 255 / 3
        val patternBitmap = BitmapFactory.decodeResource(applicationContext.resources, if (isNightMode) R.drawable.cross_pattern_white else R.drawable.cross_pattern)
        shader = android.graphics.BitmapShader(patternBitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    },
    SurfaceConditionRetrievalService.SurfaceCondition.LOOSE to Paint().apply {
        style = Paint.Style.FILL
        alpha = 255 / 3
        val patternBitmap = BitmapFactory.decodeResource(applicationContext.resources, if (isNightMode) R.drawable.cross_pattern_white else R.drawable.cross_pattern)
        shader = android.graphics.BitmapShader(patternBitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }
)

fun getSurfaceConditionStrokePaints(applicationContext: Context) = mapOf(
    SurfaceConditionRetrievalService.SurfaceCondition.GRAVEL to Paint().apply {
        style = Paint.Style.STROKE
        alpha = 255
        val patternBitmap = BitmapFactory.decodeResource(applicationContext.resources, R.drawable.cross_pattern_small)
        shader = android.graphics.BitmapShader(patternBitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    },
    SurfaceConditionRetrievalService.SurfaceCondition.LOOSE to Paint().apply {
        style = Paint.Style.STROKE
        alpha = 255
        val patternBitmap = BitmapFactory.decodeResource(applicationContext.resources, R.drawable.cross_pattern_small)
        shader = android.graphics.BitmapShader(patternBitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }
)

class SurfaceConditionRetrievalService(
    private val context: Context,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val surfaceConditionViewModelProvider: SurfaceConditionViewModelProvider
) {
    companion object {
        const val MAPFILE_SCAN_INTERVAL_MS = 60_000L * 5 // 5 minutes
    }

    data class TileInMapfiles(
        val tile: MapfileTile,
        val mapfiles: List<File>
    )

    data class MapFileInfo(
        val file: File,
        val boundingBox: BoundingBox,
    )

    enum class SurfaceCondition(val redColorFactor: Float, val strokeThickness: Float) {
        GRAVEL(redColorFactor = 0f, strokeThickness = 8f),
        LOOSE(redColorFactor = 1f, strokeThickness = 10f),
    }

    data class SurfaceConditionSegment(
        val startMeters: Double,
        val endMeters: Double,
        val condition: SurfaceCondition,
        val samples: Int
    )

    private var knownMapfiles = setOf<MapFileInfo>()

    // Cache surface condition results to avoid reprocessing identical route segments
    // Key: polyline hash, Value: surface condition segments
    private val surfaceConditionCache = mutableMapOf<String, List<SurfaceConditionSegment>>()
    private val maxCacheSize = 20 // Keep last 20 routes

    private fun hasExternalStoragePermission(): Boolean {
        val readGranted = context.checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val writeGranted = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

        return readGranted == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               writeGranted == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private var mapScanJob: Job? = null
    private var surfaceConditionUpdateJob: Job? = null

    init {
        startMapScanJob()
        startSurfaceConditionUpdateJob()
    }

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

                    val mapDirectoryOnExternalStorage = File(File(Environment.getExternalStorageDirectory(), "offline"), "maps")
                    if (!mapDirectoryOnExternalStorage.exists() || !mapDirectoryOnExternalStorage.isDirectory) {
                        Log.w(KarooRouteGraphExtension.TAG, "Map directory does not exist: ${mapDirectoryOnExternalStorage.absolutePath}")
                        delay(MAPFILE_SCAN_INTERVAL_MS)
                        continue
                    }

                    Log.d(KarooRouteGraphExtension.TAG, "Scanning for mapfiles in ${mapDirectoryOnExternalStorage.absolutePath}")

                    val mapFiles = mapDirectoryOnExternalStorage.listFiles { file ->
                        file.isFile && file.extension.equals("map", ignoreCase = true)
                    } ?: arrayOf()

                    val startTime = Instant.now()

                    knownMapfiles = mapFiles.mapNotNull{ file ->
                        try {
                            val mapfile = MapFile(file)
                            try {
                                val boundingBox = mapfile.mapFileInfo.boundingBox

                                MapFileInfo(
                                    file = file,
                                    boundingBox = boundingBox,
                                )
                            } finally {
                                mapfile.close()
                            }
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.TAG, "Error reading mapfile ${file.name}: ${e.message}", e)
                            null
                        }
                    }.toSet()

                    Log.d(KarooRouteGraphExtension.TAG, "Found ${knownMapfiles.size} mapfiles in ${(Instant.now().toEpochMilli() - startTime.toEpochMilli())} ms")

                    surfaceConditionViewModelProvider.update { state ->
                        state.copy(knownFiles = knownMapfiles.size)
                    }

                    delay(MAPFILE_SCAN_INTERVAL_MS)
                } while(true)
            }
        }
    }
    val gravelSurfaces = setOf(
        "unpaved", "dirt", "ground", "gravel", "fine_gravel", "compacted",
        "pebblestone", "cobblestone", "sett", "unhewn_cobblestone",
        "rock", "rocks", "stone", "grass_paver", "clay", "woodchips", "salt", "wood"
    )
    val looseSurfaces = setOf("grass", "sand", "mud", "snow", "ice")

    private fun getSurfaceConditionFromTags(tags: List<org.mapsforge.core.model.Tag>): SurfaceCondition? {
        val surfaceTag = tags.find { it.key.equals("surface", ignoreCase = true) }?.value?.lowercase()
        val trackTypeTag = tags.find { it.key.equals("tracktype", ignoreCase = true) }?.value?.lowercase()

        if (trackTypeTag in setOf("grade2", "grade3") || surfaceTag in gravelSurfaces) {
            return SurfaceCondition.GRAVEL
        }

        if (trackTypeTag in setOf("grade4", "grade5") || surfaceTag in looseSurfaces) {
            return SurfaceCondition.LOOSE
        }

        return null
    }

    data class RouteSamplePoint(
        val latLong: LatLong,
        val distanceMeters: Double,
    )

    data class MapfileTile(
        val tile: Tile,
        val samples: List<RouteSamplePoint>,
    )

    private data class WayWithGeometry(
        val tags: List<org.mapsforge.core.model.Tag>,
        val segments: List<List<Point>>,
        val bounds: BBox?
    )

    private data class BBox(
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double
    ) {
        fun contains(point: Point): Boolean {
            return point.longitude() >= minLon && point.longitude() <= maxLon &&
                    point.latitude() >= minLat && point.latitude() <= maxLat
        }

        fun intersects(otherMinLon: Double, otherMinLat: Double, otherMaxLon: Double, otherMaxLat: Double): Boolean {
            return !(otherMinLon > maxLon || otherMaxLon < minLon ||
                    otherMinLat > maxLat || otherMaxLat < minLat)
        }
    }

    private fun buildSurfaceConditionSegments(
        routeLength: Double,
        coords: List<RouteSamplePoint>,
        tilesInMapfiles: List<TileInMapfiles>,
    ): List<SurfaceConditionSegment> {
        val sampledSurfaceConditions = mutableMapOf<RouteSamplePoint, SurfaceCondition>()

        val tilesByMapfile = tilesInMapfiles.flatMap { tileInMapfiles ->
            tileInMapfiles.mapfiles.map { mapfile ->
                Pair(mapfile, tileInMapfiles.tile)
            }
        }.groupBy({ it.first }) { it.second }

        for ((mapFile, tiles) in tilesByMapfile) {
            val mapFileReader = MapFile(mapFile)
            try {
                // Process each tile covered by this mapfile
                for (mapfileTile in tiles) {
                    val samples = mapfileTile.samples
                    val tile = mapfileTile.tile

                    // Read map data from the tile
                    val mapReadResult = mapFileReader.readMapData(org.mapsforge.core.model.Tile(
                        tile.x,
                        tile.y,
                        tile.z.toByte(),
                        256
                    ))

                    // Pre-compute way geometry and bounding boxes once per tile (was being recomputed per sample)
                    val waysWithGeometry = mapReadResult.ways.map { way ->
                        val segments = way.latLongs.map { seg ->
                            seg.map { latLong -> Point.fromLngLat(latLong.longitude, latLong.latitude) }
                        }
                        val bounds = segments.flatMap { it }.let { points ->
                            if (points.isEmpty()) null else BBox(
                                points.minOf { it.longitude() },
                                points.minOf { it.latitude() },
                                points.maxOf { it.longitude() },
                                points.maxOf { it.latitude() }
                            )
                        }
                        WayWithGeometry(way.tags, segments, bounds)
                    }

                    // Process each sample point in this tile
                    for (sample in samples) {
                        val point = Point.fromLngLat(sample.latLong.longitude, sample.latLong.latitude)

                        // Filter ways by bounding box first, then find closest (was checking ALL ways for each sample)
                        val candidateWays = waysWithGeometry.filter { way ->
                            way.bounds?.contains(point) != false
                        }

                        val closestWayTags = if (candidateWays.size < mapReadResult.ways.size) {
                            // Only compute distances for candidate ways that pass bounding box check
                            val closestWay = candidateWays.minByOrNull { way ->
                                val minDistanceToSegments = way.segments.mapNotNull { segment ->
                                    getNearestPointOnLineDistance(point, segment)
                                }
                                minDistanceToSegments.minOrNull() ?: Double.MAX_VALUE
                            }
                            closestWay?.tags
                        } else {
                            // Few enough ways that bounding box filter doesn't help, compute directly
                            val closestWay = mapReadResult.ways.minByOrNull { way ->
                                val segments = way.latLongs.map { seg ->
                                    seg.map { latLong -> Point.fromLngLat(latLong.longitude, latLong.latitude) }
                                }
                                val minDistanceToSegments = segments.mapNotNull { segment ->
                                    getNearestPointOnLineDistance(point, segment)
                                }
                                minDistanceToSegments.minOrNull() ?: Double.MAX_VALUE
                            }
                            closestWay?.tags
                        }

                        val surfaceCondition = closestWayTags?.let { tags -> getSurfaceConditionFromTags(tags) }

                        if (surfaceCondition != null) {
                            // Log.d(KarooRouteGraphExtension.TAG, "Found surface condition $surfaceCondition for sample at ${sample.latLong} from way with tags: ${closestWay.tags}")
                            sampledSurfaceConditions[sample] = surfaceCondition
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(KarooRouteGraphExtension.TAG, "Error reading map data from ${mapFile.name}: ${e.message}", e)
            } finally {
                mapFileReader.close()
            }
        }

        // Map each sample point to its surface condition
        var currentSurfaceCondition: SurfaceCondition? = null
        var currentSurfaceConditionStartedAt: Double? = null
        var currentSurfaceConditionSampleCount = 0
        val surfaceConditions = mutableListOf<SurfaceConditionSegment>()

        fun finishCurrentSurfaceConditionSegment(currentDistance: Double) {
            val condition = currentSurfaceCondition
            val startedAt = currentSurfaceConditionStartedAt

            if (condition != null && startedAt != null) {
                surfaceConditions.add(SurfaceConditionSegment(
                    startMeters = startedAt,
                    endMeters = currentDistance,
                    condition = condition,
                    samples = currentSurfaceConditionSampleCount
                ))

                currentSurfaceCondition = null
                currentSurfaceConditionStartedAt = null
                currentSurfaceConditionSampleCount = 0
            }
        }

        for (sample in coords) {
            val matchingCondition = sampledSurfaceConditions[sample]

            if (matchingCondition != null) {
                if (matchingCondition != currentSurfaceCondition) {
                    // Finish previous segment
                    finishCurrentSurfaceConditionSegment(sample.distanceMeters)

                    // Start new segment
                    currentSurfaceCondition = matchingCondition
                    currentSurfaceConditionStartedAt = sample.distanceMeters
                }

                currentSurfaceConditionSampleCount++
            } else {
                // No matching condition, finish any ongoing segment
                finishCurrentSurfaceConditionSegment(sample.distanceMeters)
            }
        }

        finishCurrentSurfaceConditionSegment(routeLength)

        return surfaceConditions
    }

    private val stateFlow: MutableStateFlow<List<SurfaceConditionSegment>?> = MutableStateFlow(null)
    val flow: Flow<List<SurfaceConditionSegment>?> = stateFlow

    fun startSurfaceConditionUpdateJob() {
        var lastKnownPolyline: String? = null

        surfaceConditionUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            combine(
                context.streamSettings(karooSystemServiceProvider.karooSystemService).map { it.indicateSurfaceConditionsOnGraph },
                karooSystemServiceProvider.stream<OnNavigationState>()
            ) { isEnabled, state ->
                Pair(isEnabled, state)
            }.filter { (isEnabled, _) ->
                isEnabled
            }.map { (_, state) ->
                state
            }.catch { e ->
                Log.e(KarooRouteGraphExtension.TAG, "Surface condition processing error: ${e.message}", e)
            }.collect { navigationState ->
                val polyline = when (navigationState.state) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        (navigationState.state as OnNavigationState.NavigationState.NavigatingRoute).routePolyline
                    }

                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        (navigationState.state as OnNavigationState.NavigationState.NavigatingToDestination).polyline
                    }

                    else -> {
                        null
                    }
                }

                if (polyline == null) return@collect
                if (polyline == lastKnownPolyline) return@collect

                // Check cache for this polyline
                val polylineHash = polyline.hashCode().toString()
                if (surfaceConditionCache.containsKey(polylineHash)) {
                    Log.d(KarooRouteGraphExtension.TAG, "Using cached surface conditions for route")
                    val cachedSegments = surfaceConditionCache[polylineHash]!!
                    stateFlow.update { cachedSegments }
                    lastKnownPolyline = polyline
                    return@collect
                }

                stateFlow.update { null }

                lastKnownPolyline = polyline

                val startTime: Instant = Instant.now()

                val lineString = LineString.fromPolyline(polyline, 5)

                val routeDistance = try {
                    TurfMeasurement.length(lineString, TurfConstants.UNIT_METERS)
                } catch (t: Throwable) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error calculating route distance: ${t.message}", t)
                    return@collect
                }
                val samplingIntervalMeters = when (routeDistance) {
                    in 0.0..100_000.0 -> 80.0
                    in 100_000.0..200_000.0 -> 100.0
                    in 200_000.0..500_000.0 -> 150.0
                    else -> 250.0
                }

                val routeSampled = buildList {
                    var distanceMeters = 0.0
                    while (distanceMeters <= routeDistance) {
                        val point = TurfMeasurement.along(lineString, distanceMeters, TurfConstants.UNIT_METERS)
                        add(RouteSamplePoint(
                            latLong = LatLong(point.latitude(), point.longitude()),
                            distanceMeters = distanceMeters
                        ))
                        distanceMeters += samplingIntervalMeters
                    }
                }

                Log.d(KarooRouteGraphExtension.TAG, "Sampled route with ${routeSampled.size} points over $routeDistance meters")

                val zoomLevel = 16
                val samplesByTile = routeSampled.groupBy { sample ->
                    val (x, y) = TileUtils.locationToTileXY(sample.latLong.latitude, sample.latLong.longitude, z = zoomLevel)
                    Tile(x, y, zoomLevel)
                }
                val tiles = samplesByTile.keys

                val tilesWithMapfiles = tiles.associateWith { tile ->
                    knownMapfiles.filter { mapfileInfo ->
                        val bbox = mapfileInfo.boundingBox
                        val mapfileBbox = BBox(bbox.minLatitude, bbox.minLongitude, bbox.maxLatitude, bbox.maxLongitude)

                        // Get the bounding box of the tile by converting its corners to lat/lon
                        val (topLeftLat, topLeftLon) = TileUtils.tileXYToLatLon(
                            tile.x,
                            tile.y,
                            tile.z
                        )
                        val (bottomRightLat, bottomRightLon) = TileUtils.tileXYToLatLon(
                            tile.x + 1,
                            tile.y + 1,
                            tile.z
                        )

                        val tileBbox = BBox(
                            bottomRightLat,
                            topLeftLon,
                            topLeftLat,
                            bottomRightLon
                        )

                        // Check if tile bounding box intersects with mapfile bounding box
                        mapfileBbox.intersects(tileBbox.minLon, tileBbox.minLat, tileBbox.maxLon, tileBbox.maxLat)
                    }.map { it.file }
                }
                val neededMapfiles = tilesWithMapfiles.values.toSet()

                Log.d(KarooRouteGraphExtension.TAG, "Route intersects ${tiles.size} tiles in ${neededMapfiles.size} mapfiles")

                val tilesWithoutMapfile = tilesWithMapfiles.filter { (_, value) -> value.isEmpty() }.keys
                if (tilesWithoutMapfile.isNotEmpty()) {
                    Log.w(KarooRouteGraphExtension.TAG, "No mapfile found for ${tilesWithoutMapfile.size} tiles: $tilesWithoutMapfile")
                }

                val mapfilesToTiles = tilesWithMapfiles.entries
                    .map { (tile, mapfile) ->
                        TileInMapfiles(
                            tile = MapfileTile(
                                tile = tile,
                                samples = samplesByTile[tile] ?: emptyList()
                            ),
                            mapfiles = mapfile
                        )
                    }

                val surfaceConditionSegments = buildSurfaceConditionSegments(routeDistance,
                    routeSampled,
                    mapfilesToTiles)

                // Cache the results
                surfaceConditionCache[polylineHash] = surfaceConditionSegments
                
                // Evict oldest entries if cache exceeds max size
                if (surfaceConditionCache.size > maxCacheSize) {
                    val oldestKey = surfaceConditionCache.keys.first()
                    surfaceConditionCache.remove(oldestKey)
                    Log.d(KarooRouteGraphExtension.TAG, "Evicted oldest surface condition cache entry")
                }

                val totalSegmentLengthByType = surfaceConditionSegments.groupBy { it.condition }.mapValues { entry ->
                    entry.value.sumOf { it.endMeters - it.startMeters }
                }
                Log.d(KarooRouteGraphExtension.TAG, "Found ${surfaceConditionSegments.size} surface condition segments in ${(Instant.now().toEpochMilli() - startTime.toEpochMilli())} ms")
                totalSegmentLengthByType.forEach { condition, length ->
                    Log.d(KarooRouteGraphExtension.TAG, " - $condition: $length meters")
                }

                stateFlow.update {
                    surfaceConditionSegments
                }

                surfaceConditionViewModelProvider.update {
                    SurfaceConditionViewModel(
                        knownFiles = knownMapfiles.size,
                        osmTiles = tilesWithMapfiles.size,
                        tilesWithoutMapfile = tilesWithoutMapfile.size,
                        samples = routeSampled.size,
                        gravelSamples = surfaceConditionSegments.sumOf { it.samples }
                    )
                }
            }
        }
    }
}