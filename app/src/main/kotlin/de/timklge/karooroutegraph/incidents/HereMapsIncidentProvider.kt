package de.timklge.karooroutegraph.incidents

import android.util.Log
import com.here.flexpolyline.PolylineEncoderDecoder
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfConstants.UNIT_KILOMETERS
import com.mapbox.turf.TurfConversion
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
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
import kotlinx.serialization.json.Json
import java.util.zip.GZIPInputStream

class HereMapsIncidentProvider(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
) {
    suspend fun requestIncidents(apiKey: String, polyline: LineString): IncidentsResponse {
        if (TurfMeasurement.length(polyline, UNIT_KILOMETERS) >= 500){
            Log.w(KarooRouteGraphExtension.TAG, "Polyline exceeds 500km, slicing polyline")

            val polylineSegments = buildList {
                var start = 0.0
                while (start < TurfMeasurement.length(polyline, UNIT_KILOMETERS)) {
                    val end = start + 500.0
                    val segment = TurfMisc.lineSliceAlong(polyline, start, end, UNIT_KILOMETERS)
                    if (segment.coordinates().isNotEmpty()) {
                        add(segment)
                    }
                    start = end
                }
            }

            polylineSegments.mapIndexed { index, segment ->
                Log.d(KarooRouteGraphExtension.TAG, "HERE Maps requesting polyline segment $index of length ${TurfMeasurement.length(segment, UNIT_KILOMETERS)}km with ${segment.coordinates().size} points")
                requestIncidents(apiKey, segment)
            }.let { responses ->
                return IncidentsResponse(responses.firstNotNullOfOrNull { it.sourceUpdated }, responses.flatMap { it.results ?: emptyList() })
            }
        }

        val toleranceInDegrees = TurfConversion.convertLength(70.0, TurfConstants.UNIT_METERS, TurfConstants.UNIT_DEGREES)
        val initialCoords = TurfTransformation.simplify(polyline.coordinates(), toleranceInDegrees, true)

        val polylineCoords = buildList {
            addAll(initialCoords.map { PolylineEncoderDecoder.LatLngZ(it.latitude(), it.longitude()) })

            if (count() < 3) {
                Log.w(KarooRouteGraphExtension.TAG, "Polyline has less than 3 points, returning empty response")
                return IncidentsResponse("", emptyList())
            }
            if (count() > 300) {
                Log.w(KarooRouteGraphExtension.TAG, "Polyline exceeds 300 point limit, deleting random points")
                while (count() > 300) {
                    val index = (1 until count()-1).random()
                    removeAt(index)
                }
            }
        }

        val flexiblePolyline = PolylineEncoderDecoder.encode(polylineCoords, 5, PolylineEncoderDecoder.ThirdDimension.ABSENT, 0)
        val request = IncidentRequestParameters(
            `in` = IncidentGeospatialParameters(
                type = "corridor",
                corridor = flexiblePolyline,
                radius = 100,
            ),
            locationReferencing = listOf("shape"),
        )

        Log.d(KarooRouteGraphExtension.TAG, "HERE Maps requesting polyline of length ${TurfMeasurement.length(polyline, UNIT_KILOMETERS)}km with ${polylineCoords.size} points")

        return requestIncidents(apiKey, request)
    }

    suspend fun requestIncidents(apiKey: String, center: Point, radius: Double): IncidentsResponse {
        val request = IncidentRequestParameters(
            `in` = IncidentGeospatialParameters(
                type = "circle",
                center = IncidentPoint(center.latitude(), center.longitude()),
                radius = radius.toInt(),
            ),
            locationReferencing = listOf("shape"),
        )

        Log.d(KarooRouteGraphExtension.TAG, "HERE Maps requesting circle with center $center and radius $radius meters")

        return requestIncidents(apiKey, request)
    }

    private suspend fun requestIncidents(apiKey: String, parameters: IncidentRequestParameters): IncidentsResponse {
        return callbackFlow {
            @Suppress("BlockingMethodInNonBlockingContext")
            val url = "https://data.traffic.hereapi.com/v7/incidents?apiKey=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"

            val requestBody = Json.encodeToString(IncidentRequestParameters.serializer(), parameters).encodeToByteArray()

            Log.d(KarooRouteGraphExtension.TAG, "Requesting incidents with body: ${String(requestBody)}")

            val listenerId = karooSystemServiceProvider.karooSystemService.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    "POST",
                    url,
                    waitForConnection = false,
                    headers = mapOf("User-Agent" to KarooRouteGraphExtension.TAG, "Accept-Encoding" to "gzip", "Content-type" to "application/json"),
                    body = requestBody,
                ),
            ) { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete){
                    val completeEvent = (event.state as HttpResponseState.Complete)

                    try {
                        if (completeEvent.statusCode !in 200..299 || completeEvent.error != null) {
                            Log.e(KarooRouteGraphExtension.TAG, "Http response event; error ${completeEvent.statusCode} ${completeEvent.error}")
                            error(completeEvent.error ?: "HTTP ${completeEvent.statusCode}")
                        }

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

                        Log.d(KarooRouteGraphExtension.TAG, "Http response event; data $responseString")

                        val response = try {
                            jsonWithUnknownKeys.decodeFromString(IncidentsResponse.serializer(), responseString)
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to parse incident response: ${completeEvent.body}", e)
                            throw e
                        }

                        Log.d(KarooRouteGraphExtension.TAG, "Parsed incident data response with ${response.results} incidents")

                        trySendBlocking(response)
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