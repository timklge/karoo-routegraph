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

import android.content.Context
import android.os.Environment
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.floor

class ValhallaTileChecker(private val context: Context) {
    val mapDirectoryOnExternalStorage = File(File(Environment.getExternalStorageDirectory(), "offline"), "nav")

    data class TileInfo(val country: String, val tileCountZoomLevel2: Int)

    private data class CountryBounds(
        val name: String,
        val minLongitude: Double,
        val minLatitude: Double,
        val maxLongitude: Double,
        val maxLatitude: Double,
    )

    private val countryBounds: List<CountryBounds> by lazy { loadCountryBounds() }

    /**
     * Lists the available Valhalla tiles in the given root navigation directory.
     *
     * @return Country names with the number of available level-2 tiles intersecting each country's bounds.
     * @throws IllegalArgumentException if the provided directory is invalid.
     */
    fun listTiles(): List<TileInfo> {
        if (!mapDirectoryOnExternalStorage.exists() || !mapDirectoryOnExternalStorage.isDirectory) {
            throw IllegalArgumentException("Invalid nav directory: $mapDirectoryOnExternalStorage")
        }

        val availableTileIds = findLevel2TileIds(mapDirectoryOnExternalStorage)
        return countryBounds.mapNotNull { country ->
            val tileCount = countIntersectingTiles(country, availableTileIds)
            if (tileCount == 0) null else TileInfo(country.name, tileCount)
        }.sortedBy { it.country }
    }

    private fun findLevel2TileIds(navDir: File): Set<Int> {
        val tileIds = mutableSetOf<Int>()
        val rootPath = navDir.toPath()

        Files.walk(rootPath).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val fileName = path.fileName.toString()
                val tileFileName = when {
                    fileName.endsWith(".gph.gz") -> fileName.removeSuffix(".gph.gz")
                    fileName.endsWith(".gph") -> fileName.removeSuffix(".gph")
                    else -> return@forEach
                }

                val relativePath = rootPath.relativize(path)
                if (relativePath.nameCount < 2 || relativePath.getName(0).toString() != LEVEL_2.toString()) {
                    return@forEach
                }

                val tileParts = (1 until relativePath.nameCount - 1)
                    .map { relativePath.getName(it).toString() } + tileFileName
                require(tileParts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                    "Invalid Valhalla tile path: $path"
                }

                val tileId = tileParts.joinToString("").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid Valhalla tile path: $path")
                require(tileId in 0 until LEVEL_2_TILE_COUNT) {
                    "Level-2 tile ID out of range in path: $path"
                }
                tileIds += tileId
            }
        }

        return tileIds
    }

    private fun countIntersectingTiles(country: CountryBounds, availableTileIds: Set<Int>): Int {
        val firstColumn = floor((country.minLongitude + HALF_WORLD_LONGITUDE) / TILE_SIZE_DEGREES)
            .toInt().coerceIn(0, LEVEL_2_COLUMN_COUNT - 1)
        val lastColumn = (ceil((country.maxLongitude + HALF_WORLD_LONGITUDE) / TILE_SIZE_DEGREES).toInt() - 1)
            .coerceIn(0, LEVEL_2_COLUMN_COUNT - 1)
        val firstRow = floor((country.minLatitude + HALF_WORLD_LATITUDE) / TILE_SIZE_DEGREES)
            .toInt().coerceIn(0, LEVEL_2_ROW_COUNT - 1)
        val lastRow = (ceil((country.maxLatitude + HALF_WORLD_LATITUDE) / TILE_SIZE_DEGREES).toInt() - 1)
            .coerceIn(0, LEVEL_2_ROW_COUNT - 1)

        if (country.minLongitude >= country.maxLongitude || country.minLatitude >= country.maxLatitude) {
            return 0
        }

        var count = 0
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val tileId = row * LEVEL_2_COLUMN_COUNT + column
                if (tileId in availableTileIds) {
                    count++
                }
            }
        }
        return count
    }

    private fun loadCountryBounds(): List<CountryBounds> {
        val countryJson = context.resources.openRawResource(R.raw.countries)
            .bufferedReader()
            .use { it.readText() }
        val countries = Json.parseToJsonElement(countryJson) as JsonObject

        return countries.map { (code, value) ->
            val fields = value.jsonArray
            val bounds = fields[1].jsonArray
            require(bounds.size == 4) { "Expected four bounds for country $code" }
            CountryBounds(
                name = fields[0].jsonPrimitive.content,
                minLongitude = bounds[0].jsonPrimitive.content.toDouble(),
                minLatitude = bounds[1].jsonPrimitive.content.toDouble(),
                maxLongitude = bounds[2].jsonPrimitive.content.toDouble(),
                maxLatitude = bounds[3].jsonPrimitive.content.toDouble(),
            )
        }
    }

    init {
        try {
            val tiles = listTiles()
            val tilesString = tiles.joinToString(separator = "\n") { "- ${it.country}: ${it.tileCountZoomLevel2} tiles" }
            Log.i(KarooRouteGraphExtension.TAG, "Available Valhalla tiles: \n$tilesString")
        } catch (e: Exception) {
            Log.e(KarooRouteGraphExtension.TAG, "Error listing Valhalla tiles", e)
        }
    }

    private companion object {
        const val LEVEL_2 = 2
        const val TILE_SIZE_DEGREES = 0.25
        const val HALF_WORLD_LONGITUDE = 180.0
        const val HALF_WORLD_LATITUDE = 90.0
        const val LEVEL_2_COLUMN_COUNT = 1440
        const val LEVEL_2_ROW_COUNT = 720
        const val LEVEL_2_TILE_COUNT = LEVEL_2_COLUMN_COUNT * LEVEL_2_ROW_COUNT
    }
}