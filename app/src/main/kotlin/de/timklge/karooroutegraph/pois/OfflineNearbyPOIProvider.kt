package de.timklge.karooroutegraph.pois

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.jsonWithUnknownKeys
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.streamPbfDownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos

/**
 * DB Schema is 	CREATE TABLE nodes (
 * 		id INTEGER PRIMARY KEY,
 * 		lat REAL,
 * 		lon REAL,
 * 		tags TEXT
 * 	);
 */

class OfflineNearbyPOIProvider(val context: Context, val downloadService: NearbyPOIPbfDownloadService) : NearbyPOIProvider {
    suspend fun getAvailableCountries(): List<String> {
        val countries = downloadService.countriesData
        val pbfStorage = streamPbfDownloadStore(context).first().associateBy { it.countryKey.lowercase() }
        val availableCountries = countries.keys.filter { pbfStorage.containsKey(it.lowercase()) }

        return availableCountries
    }

    suspend fun getAvailableCountriesInBounds(
        points: List<Point>,
        radius: Int
    ): List<String> {
        if (points.isEmpty()) return emptyList()

        val pointsMinLon = points.minOf { it.longitude() }
        val pointsMaxLon = points.maxOf { it.longitude() }
        val pointsMinLat = points.minOf { it.latitude() }
        val pointsMaxLat = points.maxOf { it.latitude() }

        val latRadius = radius / 111000.0
        val avgLat = (pointsMinLat + pointsMaxLat) / 2.0
        val lonRadius = radius / (111000.0 * cos(Math.toRadians(avgLat)))

        val searchMinLat = pointsMinLat - latRadius
        val searchMaxLat = pointsMaxLat + latRadius
        val searchMinLon = pointsMinLon - lonRadius
        val searchMaxLon = pointsMaxLon + lonRadius

        val countries = downloadService.countriesData

        val availableCountriesInBounds = getAvailableCountries().filter { countryKey ->
            val bounds = countries[countryKey]?.bounds ?: return@filter false

            val (minLon, minLat, maxLon, maxLat) = bounds

            searchMinLon <= maxLon && searchMaxLon >= minLon &&
                    searchMinLat <= maxLat && searchMaxLat >= minLat
        }

        return availableCountriesInBounds
    }

    override suspend fun requestNearbyPOIs(
        requestedTags: List<Pair<String, String>>,
        points: List<Point>,
        radius: Int,
        limit: Int
    ): List<NearbyPOI> {
        val lineLength = TurfMeasurement.length(points, TurfConstants.UNIT_METERS)
        val sampleInterval = radius.toDouble() / 2
        val samples = if (points.size < 2) points else buildList {
            var distance = 0.0
            while (distance < lineLength) {
                add(TurfMeasurement.along(points, distance, TurfConstants.UNIT_METERS))
                distance += sampleInterval
            }
            add(TurfMeasurement.along(points, lineLength, TurfConstants.UNIT_METERS))
        }

        if (samples.isEmpty()) return emptyList()
        val requestedTagsOrEverything = requestedTags.ifEmpty {
            NearbyPoiCategory.entries.map { it.osmTag }.flatten().distinct()
        }
        Log.i(KarooRouteGraphExtension.TAG, "Offline POI query: requestedTags=$requestedTags, requestedTagsOrEverything=$requestedTagsOrEverything")

        val pointsMinLon = samples.minOf { it.longitude() }
        val pointsMaxLon = samples.maxOf { it.longitude() }
        val pointsMinLat = samples.minOf { it.latitude() }
        val pointsMaxLat = samples.maxOf { it.latitude() }

        val latRadius = radius / 111000.0
        val avgLat = (pointsMinLat + pointsMaxLat) / 2.0
        val lonRadius = radius / (111000.0 * cos(Math.toRadians(avgLat)))

        val searchMinLat = pointsMinLat - latRadius
        val searchMaxLat = pointsMaxLat + latRadius
        val searchMinLon = pointsMinLon - lonRadius
        val searchMaxLon = pointsMaxLon + lonRadius

        val availableCountriesInBounds = getAvailableCountriesInBounds(samples, radius)

        Log.i(KarooRouteGraphExtension.TAG, "Searching offline POIs in countries: $availableCountriesInBounds")

        // Pre-compile tag search patterns for SQL LIKE clause
        // This moves tag filtering from application code to database level
        val tagPatterns = requestedTagsOrEverything.map { (key, value) ->
            "\"$key\":\"$value\""
        }
        Log.i(KarooRouteGraphExtension.TAG, "Offline POI tagPatterns: $tagPatterns")

        val jobs = availableCountriesInBounds.map { countryKey ->
            CoroutineScope(Dispatchers.IO).async {
                buildList {
                    val dbFile = downloadService.getPoiFile(countryKey)
                    if (!dbFile.exists()) return@buildList

                    try {
                        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                            // Use SQL LIKE to filter tags at database level
                            // This significantly reduces the number of rows processed
                            // Note: tags is stored as BLOB so we need CAST to TEXT for LIKE comparison
                            val whereClause = buildString {
                                append("lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND (")
                                tagPatterns.forEachIndexed { index, pattern ->
                                    if (index > 0) append(" OR ")
                                    append("CAST(tags AS TEXT) LIKE ?")
                                }
                                append(")")
                            }

                            val whereArgs = arrayOf(
                                searchMinLat.toString(),
                                searchMaxLat.toString(),
                                searchMinLon.toString(),
                                searchMaxLon.toString(),
                                *tagPatterns.map { "%$it%" }.toTypedArray()
                            )

                            val cursor = db.query(
                                "nodes",
                                arrayOf("id", "lat", "lon", "tags"),
                                whereClause,
                                whereArgs,
                                null, null, null
                            )

                            cursor.use {
                                val idIndex = it.getColumnIndex("id")
                                val latIndex = it.getColumnIndex("lat")
                                val lonIndex = it.getColumnIndex("lon")
                                val tagsIndex = it.getColumnIndex("tags")

                                while (it.moveToNext()) {
                                    val lat = it.getDouble(latIndex)
                                    val lon = it.getDouble(lonIndex)
                                    val tagsBlob = it.getBlob(tagsIndex)

                                    // Parse JSON only for rows that passed the LIKE filter
                                    val jsonObject = jsonWithUnknownKeys.parseToJsonElement(String(tagsBlob))
                                    val tags = jsonObject.jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content }

                                    // Verify the match (LIKE is approximate)
                                    val matchesTags = requestedTagsOrEverything.any { (key, value) ->
                                        tags[key] == value
                                    }

                                    if (matchesTags) {
                                        val poiPoint = Point.fromLngLat(lon, lat)
                                        val minDistance = if (samples.size > 1) {
                                            getNearestPointOnLineDistance(poiPoint, samples)
                                        } else {
                                            TurfMeasurement.distance(poiPoint, samples[0], TurfConstants.UNIT_METERS)
                                        }

                                        if (minDistance != null && minDistance <= radius) {
                                            add(NearbyPOI(it.getLong(idIndex), lat, lon, tags))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(KarooRouteGraphExtension.TAG, "Error reading POI DB for $countryKey", e)
                    }
                }
            }
        }

        val result = jobs.awaitAll().flatten().toSet()
        Log.i(KarooRouteGraphExtension.TAG, "Offline POI query found ${result.size} POIs, returning ${result.take(limit).size}")

        return result.take(limit)
    }
}