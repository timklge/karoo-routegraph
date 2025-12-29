package de.timklge.karooroutegraph.pois

import android.util.Log
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfConversion
import com.mapbox.turf.TurfTransformation
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.jsonWithUnknownKeys
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.single
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

@Serializable
data class OverpassResponse(
    val version: Double? = null,
    val generator: String? = null,
    val osm3s: Osm3s? = null,
    val elements: List<Element>
)

@Serializable
data class Osm3s(
    @SerialName("timestamp_osm_base")
    val timestampOsmBase: String? = null,
    val copyright: String? = null
)

@Serializable
data class Element(
    val type: String? = null,
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>? = null
) {
    fun hasAdditionalInfo(): Boolean {
        return tags != null && (tags.contains("opening_hours"))
    }
}

class OverpassPOIProvider(
    private val karooSystemServiceProvider: KarooSystemServiceProvider
) : NearbyPOIProvider {
    override suspend fun requestNearbyPOIs(
        requestedTags: List<Pair<String, String>>,
        points: List<Point>,
        radius: Int,
        limit: Int
    ): List<NearbyPOI> {
        val response = callbackFlow {
            val simplifiedPolyline = TurfTransformation.simplify(points, TurfConversion.convertLength(radius.toDouble() / 2, TurfConstants.UNIT_METERS, TurfConstants.UNIT_DEGREES), true)
            val polylineString = simplifiedPolyline.joinToString(separator = ",") { point ->
                String.format(Locale.US, "%.4f,%.4f", point.latitude(), point.longitude())
            }

            val url = "https://overpass-api.de/api/interpreter"
            val requestBodyDataPart = "[out:json];(" +
                    requestedTags.joinToString("") { (key, value) -> "node[$key=$value](around:$radius,$polylineString);" } +
                    ");out center $limit;"

            @Suppress("BlockingMethodInNonBlockingContext")
            val requestBody = "data=${URLEncoder.encode(requestBodyDataPart, "UTF-8")}".encodeToByteArray()

            Log.d(KarooRouteGraphExtension.Companion.TAG, "Http request to ${url}... with body: ${requestBodyDataPart}")

            val listenerId = karooSystemServiceProvider.karooSystemService.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    "POST",
                    url,
                    waitForConnection = false,
                    headers = mapOf("User-Agent" to KarooRouteGraphExtension.Companion.TAG, "Accept-Encoding" to "gzip"),
                    body = requestBody,
                ),
                onError = { err ->
                    Log.e(KarooRouteGraphExtension.Companion.TAG, "Failed to send request: $err")
                    close(Exception(err))
                }) { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete){
                    val completeEvent = (event.state as HttpResponseState.Complete)

                    try {
                        val inputStream = ByteArrayInputStream(completeEvent.body ?: ByteArray(0))
                        val lowercaseHeaders = completeEvent.headers.map { (k: String, v: String) -> k.lowercase() to v.lowercase() }.toMap()
                        val isGzippedResponse = lowercaseHeaders["content-encoding"]?.contains("gzip") == true
                        val responseString = if(isGzippedResponse){
                            try {
                                val gzipStream = GZIPInputStream(inputStream)
                                val response = gzipStream.use { stream -> String(stream.readBytes()) }
                                Log.d(KarooRouteGraphExtension.Companion.TAG, "Http response event; size ${completeEvent.body?.size} gzip decompressed to ${response.length} bytes")
                                response
                            } catch(e: Exception) {
                                Log.e(KarooRouteGraphExtension.Companion.TAG, "Failed to decompress gzip response", e)
                                String(completeEvent.body ?: ByteArray(0))
                            }
                        } else {
                            val response = inputStream.use { stream -> String(stream.readBytes()) }
                            Log.d(KarooRouteGraphExtension.Companion.TAG, "Http response event; size ${completeEvent.body?.size} bytes")
                            response
                        }

                        if (completeEvent.error != null) {
                            Log.e(KarooRouteGraphExtension.Companion.TAG, "Http response event; error ${completeEvent.error}")
                            error(completeEvent.error ?: "Unknown error")
                        }

                        val response = try {
                            jsonWithUnknownKeys.decodeFromString(OverpassResponse.serializer(), responseString)
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.Companion.TAG, "Failed to parse response: ${completeEvent.body}", e)
                            throw e
                        }

                        Log.d(KarooRouteGraphExtension.Companion.TAG, "Parsed overpass response with ${response.elements.size} elements")

                        trySendBlocking(response)
                    } catch(e: Throwable){
                        Log.e(KarooRouteGraphExtension.Companion.TAG, "Failed to process response", e)
                        close(e)
                    }

                    close()
                }
            }
            awaitClose {
                karooSystemServiceProvider.karooSystemService.removeConsumer(listenerId)
            }
        }.single()

        return response.elements.map { element ->
            NearbyPOI(
                id = element.id,
                lat = element.lat,
                lon = element.lon,
                tags = element.tags ?: emptyMap(),
            )
        }
    }
}