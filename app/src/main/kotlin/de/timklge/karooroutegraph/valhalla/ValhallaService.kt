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
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json

@Serializable
private data class TraceAttributesRequest(
    @SerialName("encoded_polyline") val encodedPolyline: String,
    val costing: String,
)

class ValhallaService(private val appContext: Context) {
    val mapDirectoryOnExternalStorage = File(File(Environment.getExternalStorageDirectory(), "offline"), "nav")

    lateinit var config: ValhallaConfig
    private var valhalla: Valhalla? = null

    init {
        try {
            config = ValhallaConfigFactory.usingTilesDir(mapDirectoryOnExternalStorage.absolutePath)
            valhalla = Valhalla(appContext, config)

            //val response = valhalla.routeRaw("""{"locations":[{"lat":51.033376466168725,"lon":13.694934334032988},{"lat":51.06081225661858,"lon":13.776657845042573}],"costing":"auto"}""")

            // val response = valhalla.locateRaw("""{"verbose":true,"locations":[{"lat":51.0346852875883,"lon":3.703615769246603}],"costing":"bicycle","costing_options":{"bicycle":{"bicycle_type":"road"}},"directions_options":{"units":"miles"},"id":"12abc3afe23984fe"}""")

            val response = valhalla?.statusRaw("""{"verbose":true}""")

            // Fetch a route from Valhalla
            // val response = valhalla.route(request)
            println(response)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun mapMatch(polyline: LineString): String? {
        val request = TraceAttributesRequest(
            encodedPolyline = polyline.toPolyline(6),
            costing = "bicycle",
        )
        val tracedAttributes = valhalla?.traceAttributesRaw(json.encodeToString(request))

        Log.i(KarooRouteGraphExtension.TAG, "Traced attributes: $tracedAttributes")

        return tracedAttributes
    }

    /**
     * Uses the Valhalla locate API to get detailed information (shape, attributes, connected nodes)
     * about the edges and nodes near the given point.
     */
    fun locate(point: Point): String? {
        return valhalla?.locateRaw("""{"verbose":true,"locations":[{"lat":${point.latitude()},"lon":${point.longitude()}}],"costing":"bicycle"}""")
    }
}