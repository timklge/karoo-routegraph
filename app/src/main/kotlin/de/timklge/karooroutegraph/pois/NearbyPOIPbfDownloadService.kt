package de.timklge.karooroutegraph.pois

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.streamPbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStoreStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class NearbyPOIPbfDownloadService(
    private val context: Context,
) {
    companion object {
        const val DOWNLOAD_URL = "https://routegraph.timklge.de/pois"
    }

    private val okHttpClient = OkHttpClient()

    fun getDownloadUrl(countryKey: String): String {
        return "$DOWNLOAD_URL/pois.${countryKey.lowercase()}.db"
    }

    private var downloadJob: Job? = null
    lateinit var countriesData: Map<String, CountryData>

    data class CountryData(
        val name: String,
        val bounds: List<Double>, // [minLon, minLat, maxLon, maxLat]
        val continent: String
    )

    init {
        loadCountryData()
        startDownloadJob()
    }

    fun loadCountryData() {
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("countries", "raw", context.packageName)
            )
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = Json.parseToJsonElement(jsonString) as JsonObject

            countriesData = jsonObject.mapValues { (_, value) ->
                val array = value.jsonArray
                val countryName = array[0].jsonPrimitive.content
                val bounds = array[1].jsonArray.map { it.jsonPrimitive.content.toDouble() }
                val continent = if (array.size > 2) array[2].jsonPrimitive.content else "Unknown"
                CountryData(countryName, bounds, continent)
            }
        } catch (t: Throwable) {
            Log.e(KarooRouteGraphExtension.TAG, "Failed to read country bounding boxes", t)
        }
    }


    fun getPoiFile(countryKey: String): File {
        return File(context.filesDir, "pois.${countryKey.lowercase()}.db")
    }

    fun startDownloadJob() {
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val downloadedPbfs = streamPbfDownloadStore(context).filter { list ->
                    list.any { it.downloadState == PbfDownloadStatus.PENDING }
                }.first()

                val nextPbfToDownload = downloadedPbfs.firstOrNull { pbf -> pbf.downloadState == PbfDownloadStatus.PENDING }
                if (nextPbfToDownload == null) {
                    continue
                }

                val downloadUrl = getDownloadUrl(nextPbfToDownload.countryKey)

                Log.i(KarooRouteGraphExtension.TAG, "Starting download of PBF for ${nextPbfToDownload.countryKey} from $downloadUrl")

                try {
                    updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.PENDING)

                    // Build OkHttp request
                    val request = Request.Builder()
                        .url(downloadUrl)
                        .build()

                    // Execute the request
                    okHttpClient.newCall(request).execute().use { response ->
                        val responseBody = response.body

                        if (!response.isSuccessful || responseBody == null) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to download PBF: HTTP ${response.code}")
                            updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.DOWNLOAD_FAILED)
                            return@use
                        }

                        val fileLength = responseBody.contentLength()
                        val inputStream = responseBody.byteStream()

                        // Create output file in app's private storage
                        val outputFile = getPoiFile(nextPbfToDownload.countryKey)
                        val outputStream = FileOutputStream(outputFile)

                        val buffer = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        var lastProgressUpdate = 0.0f

                        while (inputStream.read(buffer).also { count = it } != -1) {
                            total += count
                            outputStream.write(buffer, 0, count)

                            // Update progress in database (only if changed by at least 0.1)
                            if (fileLength > 0) {
                                val progress = (total.toFloat() / fileLength).coerceIn(0.0f, 1.0f)
                                if (progress - lastProgressUpdate >= 0.1f || progress >= 1.0f) {
                                    updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.PENDING, progress)
                                    lastProgressUpdate = progress
                                }
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        // Mark as processing
                        updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.AVAILABLE)

                        Log.i(KarooRouteGraphExtension.TAG, "Successfully downloaded PBF for ${nextPbfToDownload.countryKey}")
                    }
                } catch (e: Exception) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error downloading PBF for ${nextPbfToDownload.countryKey}", e)

                    updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.DOWNLOAD_FAILED)
                }
            }
        }
    }
}