package de.timklge.karooroutegraph

import android.util.Log
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.single
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

@Serializable
data class OsmPlace(
    @SerialName("place_id")
    val placeId: Long? = null,
    val licence: String? = null,
    @SerialName("osm_type")
    val osmType: String? = null,
    @SerialName("osm_id")
    val osmId: Long? = null,
    val lat: String,
    val lon: String,
    @SerialName("class")
    val clazz: String? = null, // 'class' is a reserved word, so use 'clazz'
    val type: String? = null,
    @SerialName("place_rank")
    val placeRank: Int? = null,
    val importance: Double? = null,
    @SerialName("addresstype")
    val addressType: String? = null,
    val name: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    val boundingbox: List<String>? = null
)

class NominatimProvider(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
) {
    suspend fun requestNominatim(request: String, lat: Double? = null, lng: Double? = null, limit: Int = 10): List<OsmPlace> {
        return callbackFlow {
            @Suppress("BlockingMethodInNonBlockingContext")
            val query = URLEncoder.encode(request, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$query&limit=$limit&format=json"


            Log.d(KarooRouteGraphExtension.TAG, "Http request to ${url}...")

            val listenerId = karooSystemServiceProvider.karooSystemService.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    "GET",
                    url,
                    waitForConnection = false,
                    headers = mapOf("User-Agent" to KarooRouteGraphExtension.TAG, "Accept-Encoding" to "gzip"),
                ),
                onError = { err ->
                    Log.e(KarooRouteGraphExtension.TAG, "Failed to send request: $err")
                    close(Exception(err))
                }) { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete){
                    val completeEvent = (event.state as HttpResponseState.Complete)

                    try {
                        val inputStream = java.io.ByteArrayInputStream(completeEvent.body ?: ByteArray(0))
                        val lowercaseHeaders = completeEvent.headers.map { (k: String, v: String) -> k.lowercase() to v.lowercase() }.toMap()
                        val isGzippedResponse = lowercaseHeaders["content-encoding"]?.contains("gzip") == true
                        val responseString = if(isGzippedResponse){
                            try {
                                val gzipStream = GZIPInputStream(inputStream)
                                val response = gzipStream.use { stream -> String(stream.readBytes()) }
                                Log.d(KarooRouteGraphExtension.TAG, "Http response event; size ${completeEvent.body?.size} gzip decompressed to ${response.length} bytes")
                                response
                            } catch(e: Exception) {
                                Log.e(KarooRouteGraphExtension.TAG, "Failed to decompress gzip response", e)
                                String(completeEvent.body ?: ByteArray(0))
                            }
                        } else {
                            val response = inputStream.use { stream -> String(stream.readBytes()) }
                            Log.d(KarooRouteGraphExtension.TAG, "Http response event; size ${completeEvent.body?.size} bytes")
                            response
                        }

                        if (completeEvent.error != null) {
                            Log.e(KarooRouteGraphExtension.TAG, "Http response event; error ${completeEvent.error}")
                            error(completeEvent.error ?: "Unknown error")
                        }

                        val response = try {
                            jsonWithUnknownKeys.decodeFromString(ListSerializer(OsmPlace.serializer()), responseString)
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to parse response: ${completeEvent.body}", e)
                            throw e
                        }

                        Log.d(KarooRouteGraphExtension.TAG, "Parsed nominatim response with ${response.size} elements")

                        trySendBlocking(response)
                    } catch(e: Throwable){
                        Log.e(KarooRouteGraphExtension.TAG, "Failed to process response", e)
                        close(e)
                    }

                    close()
                }
            }
            awaitClose {
                karooSystemServiceProvider.karooSystemService.removeConsumer(listenerId)
            }
        }.single()
    }
}