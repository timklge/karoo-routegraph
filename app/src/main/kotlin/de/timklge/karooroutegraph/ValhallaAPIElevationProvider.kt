package de.timklge.karooroutegraph

import android.util.Log
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.mapbox.geojson.LineString
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.single
import kotlinx.serialization.json.Json
import java.util.zip.GZIPInputStream

@DrawableRes
fun getInclineIndicator(percent: Float): Int? {
    return when (percent) {
        in 2.5f..3f -> R.drawable.elevate1
        in 3f..3.5f -> R.drawable.elevate2
        in 3.5f..5.0f -> R.drawable.elevate3
        in 5.0f..7.0f -> R.drawable.elevate4
        in 7.0f..10.0f -> R.drawable.elevate5
        in 10.0f..13.0f -> R.drawable.elevate6
        in 13.0f..16.0f -> R.drawable.elevate7
        in 16.0f..Float.MAX_VALUE -> R.drawable.elevate8
        else -> null
    }
}

@ColorRes
fun getInclineIndicatorColor(percent: Float): Int? {
    return when(percent) {
        in 2.5f..3f -> R.color.elevate0
        in 3f..3.5f -> R.color.elevate1
        in 3.5f..5.0f -> R.color.elevate2
        in 5.0f..7.0f -> R.color.elevate2
        in 7.0f..10.0f -> R.color.elevate3
        in 10.0f..13.0f -> R.color.elevate2
        in 13.0f..16.0f -> R.color.elevate4
        in 16.0f..Float.MAX_VALUE -> R.color.elevate4
        else -> null
    }
}

class ValhallaAPIElevationProvider(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
) {
    suspend fun requestValhallaElevations(polyline: LineString, interval: Float = 60.0f): SampledElevationData {
        return callbackFlow {
            val url = "https://valhalla1.openstreetmap.de/height"
            val request = HeightRequest(range = true, shapeFormat = "polyline5", encodedPolyline = polyline.toPolyline(5), heightPrecision = 2, resampleDistance = interval.toDouble())
            val requestBody = Json.encodeToString(HeightRequest.serializer(), request).encodeToByteArray()

            Log.d(KarooRouteGraphExtension.TAG, "Http request to ${url}...")

            val listenerId = karooSystemServiceProvider.karooSystemService.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    "POST",
                    url,
                    waitForConnection = false,
                    headers = mapOf("User-Agent" to KarooRouteGraphExtension.TAG, "Accept-Encoding" to "gzip"),
                    body = requestBody,
                ),
            ) { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete){
                    val completeEvent = (event.state as HttpResponseState.Complete)

                    try {
                        val inputStream = java.io.ByteArrayInputStream(completeEvent.body ?: ByteArray(0))
                        val lowercaseHeaders = completeEvent.headers.map { (k: String, v: String) -> k.lowercase() to v.lowercase() }.toMap()
                        val isGzippedResponse = lowercaseHeaders["content-encoding"]?.contains("gzip") == true
                        val responseString = if(isGzippedResponse){
                            val gzipStream = GZIPInputStream(inputStream)
                            val response = gzipStream.use { stream -> String(stream.readBytes()) }
                            Log.d(KarooRouteGraphExtension.TAG, "Http response event; size ${completeEvent.body?.size} gzip decompressed to ${response.length} bytes")
                            response
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
                            Json.decodeFromString(HeightResponse.serializer(), responseString)
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to parse response: ${completeEvent.body}", e)
                            throw e
                        }

                        Log.d(KarooRouteGraphExtension.TAG, "Parsed elevation data response with ${response.rangeHeight.size} points")

                        val resultElevations = FloatArray(response.rangeHeight.size) { index -> response.rangeHeight[index][1].toFloat() }

                        // Smooth the elevation data using a 3-value moving average
                        val smoothedElevations = FloatArray(resultElevations.size)
                        for (i in resultElevations.indices) {
                            val windowValues = mutableListOf<Float>()
                            if (i > 0) windowValues.add(resultElevations[i-1])
                            windowValues.add(resultElevations[i])
                            if (i < resultElevations.size - 1) windowValues.add(resultElevations[i+1])

                            smoothedElevations[i] = windowValues.average().toFloat()
                        }
                        val result = SampledElevationData(interval, smoothedElevations)

                        trySendBlocking(result)
                    } catch(e: Throwable){
                        Log.e(KarooRouteGraphExtension.TAG, "Failed to process response", e)
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