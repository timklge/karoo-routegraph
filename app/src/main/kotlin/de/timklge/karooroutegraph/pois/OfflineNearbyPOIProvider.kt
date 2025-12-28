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
        if (points.isEmpty()) return emptyList()
        val requestedTagsOrEverything = requestedTags.ifEmpty {
            NearbyPoiCategory.entries.map { it.osmTag }.flatten().distinct()
        }

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

        val availableCountriesInBounds = getAvailableCountriesInBounds(points, radius)

        Log.i(KarooRouteGraphExtension.TAG, "Searching offline POIs in countries: $availableCountriesInBounds")

        val jobs = availableCountriesInBounds.map { countryKey ->
            CoroutineScope(Dispatchers.IO).async {
                buildList {
                    val dbFile = downloadService.getPoiFile(countryKey)
                    if (!dbFile.exists()) return@buildList

                    try {
                        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                            val cursor = db.query(
                                "nodes",
                                arrayOf("id", "lat", "lon", "tags"),
                                "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                                arrayOf(searchMinLat.toString(), searchMaxLat.toString(), searchMinLon.toString(), searchMaxLon.toString()),
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

                                    val jsonObject = jsonWithUnknownKeys.parseToJsonElement(String(tagsBlob))
                                    val tags = jsonObject.jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content }

                                    val matchesTags = requestedTagsOrEverything.any { (key, value) ->
                                        tags[key] == value
                                    }

                                    if (matchesTags) {
                                        val poiPoint = Point.fromLngLat(lon, lat)
                                        val minDistance = if (points.size > 1) {
                                            getNearestPointOnLineDistance(poiPoint, points)
                                        } else {
                                            TurfMeasurement.distance(poiPoint, points[0], TurfConstants.UNIT_METERS)
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

        return result.take(limit)
    }
}