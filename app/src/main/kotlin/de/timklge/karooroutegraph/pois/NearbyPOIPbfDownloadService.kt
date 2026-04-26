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
        return "$DOWNLOAD_URL/pois.${countryKey.lowercase()}.db.gz"
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

                        if (!response.isSuccessful) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to download PBF: HTTP ${response.code}")
                            updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.DOWNLOAD_FAILED)
                            return@use
                        }

                        val fileLength = responseBody.contentLength()
                        Log.i(KarooRouteGraphExtension.TAG, "Content-Length for ${nextPbfToDownload.countryKey}: $fileLength bytes")

                        var compressedBytesRead = 0L
                        val rawStream = responseBody.byteStream()
                        val countingStream = object : java.io.InputStream() {
                            override fun read(): Int {
                                val b = rawStream.read()
                                if (b != -1) compressedBytesRead++
                                return b
                            }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val n = rawStream.read(b, off, len)
                                if (n != -1) compressedBytesRead += n
                                return n
                            }
                            override fun close() = rawStream.close()
                        }
                        val inputStream = java.util.zip.GZIPInputStream(countingStream)

                        // Create output file in app's private storage
                        val tempFile = File.createTempFile("pois_${nextPbfToDownload.countryKey.lowercase()}", ".db.gz", context.cacheDir)
                        val outputFile = getPoiFile(nextPbfToDownload.countryKey)

                        try {
                            val outputStream = FileOutputStream(tempFile)

                            val buffer = ByteArray(4096)
                            var count: Int
                            var lastProgressUpdate = 0.0f

                            while (inputStream.read(buffer).also { count = it } != -1) {
                                outputStream.write(buffer, 0, count)

                                // Update progress in database (only if changed by at least 0.1)
                                if (fileLength > 0) {
                                    val progress = (compressedBytesRead.toFloat() / fileLength).coerceIn(0.0f, 1.0f)
                                    if (progress - lastProgressUpdate >= 0.1f || progress >= 1.0f) {
                                        updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.PENDING, progress)
                                        lastProgressUpdate = progress
                                    }
                                } else {
                                    updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.PENDING, 0.0f)
                                }
                            }

                            Log.i(KarooRouteGraphExtension.TAG, "Finished downloading PBF for ${nextPbfToDownload.countryKey}, renaming file...")

                            outputStream.flush()
                            outputStream.close()
                            inputStream.close()
                            if (!tempFile.renameTo(outputFile)) {
                                Log.e(KarooRouteGraphExtension.TAG, "Failed to move downloaded file to final location for ${nextPbfToDownload.countryKey}")
                                updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.DOWNLOAD_FAILED)
                                return@use
                            }

                            // Mark as available
                            updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.AVAILABLE)

                            Log.i(KarooRouteGraphExtension.TAG, "Successfully downloaded PBF for ${nextPbfToDownload.countryKey}")
                        } finally {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error downloading PBF for ${nextPbfToDownload.countryKey}", e)

                    updatePbfDownloadStoreStatus(context, nextPbfToDownload.countryKey, PbfDownloadStatus.DOWNLOAD_FAILED)
                }
            }
        }
    }
}