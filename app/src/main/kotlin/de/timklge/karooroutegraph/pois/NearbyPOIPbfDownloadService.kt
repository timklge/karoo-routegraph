package de.timklge.karooroutegraph.pois

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.streamPbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStoreStatus
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

class NearbyPOIPbfDownloadService(
    private val context: Context,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
) {
    companion object {
        const val DOWNLOAD_URL = "https://routegraph.timklge.de/pois"
    }

    private val okHttpClient = OkHttpClient()

    fun getDownloadUrl(countryKey: String): String {
        return "$DOWNLOAD_URL/pois.${countryKey.lowercase()}.db.gz"
    }

    private var downloadJob: Job? = null
    private var updateCheckJob: Job? = null
    lateinit var countriesData: Map<String, CountryData>

    data class CountryData(
        val name: String,
        val bounds: List<Double>, // [minLon, minLat, maxLon, maxLat]
        val continent: String
    )

    init {
        loadCountryData()
        startDownloadJob()
        startUpdateCheckJob()
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

    /**
     * Returns the last modified timestamp of the available PBF file on the server for the given country key.
     */
    suspend fun getLastModifiedUrl(countryKey: String): Instant {
        val downloadUrl = getDownloadUrl(countryKey)

        val request = Request.Builder()
            .url(downloadUrl)
            .head()
            .build()

        okHttpClient.newCall(request).executeAsync().use { response ->
            val lastModifiedHeader = response.header("Last-Modified")
                ?: throw Exception("No Last-Modified header in response for $countryKey")

            val formatter = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            return java.time.ZonedDateTime.parse(lastModifiedHeader, formatter).toInstant()
        }
    }

    fun handleUpdateIntent() {
        Log.i(KarooRouteGraphExtension.TAG, "Received update intent")

        CoroutineScope(Dispatchers.IO).launch {
            updatePbfDownloadStore(context) { currentPbfs ->
                currentPbfs.map {
                    if (it.downloadState == PbfDownloadStatus.UPDATE_AVAILABLE) {
                        it.copy(downloadState = PbfDownloadStatus.UPDATING)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun startUpdateCheckJob() {
        var retryCount = 0

        updateCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val downloadedPbfs = streamPbfDownloadStore(context).filter { list ->
                    list.any { it.downloadState == PbfDownloadStatus.AVAILABLE }
                }.first()

                try {
                    for (pbf in downloadedPbfs) {
                        val outputFile = getPoiFile(pbf.countryKey)
                        val outputFileModifiedTimestamp: Instant? = if (outputFile.exists()) {
                            Instant.ofEpochMilli(outputFile.lastModified())
                        } else {
                            null
                        }
                        val onlineFileModifiedTimestamp: Instant = getLastModifiedUrl(pbf.countryKey)

                        if (outputFileModifiedTimestamp == null || onlineFileModifiedTimestamp.isAfter(outputFileModifiedTimestamp)) {
                            Log.i(KarooRouteGraphExtension.TAG, "DB file for ${pbf.countryKey} is outdated or missing. Online last modified: $onlineFileModifiedTimestamp, local last modified: $outputFileModifiedTimestamp. Marking for update.")

                            updatePbfDownloadStoreStatus(
                                context,
                                pbf.countryKey,
                                PbfDownloadStatus.UPDATE_AVAILABLE
                            )
                        } else {
                            Log.i(
                                KarooRouteGraphExtension.TAG,
                                "DB file for ${pbf.countryKey} is up to date. Online last modified: $onlineFileModifiedTimestamp, local last modified: $outputFileModifiedTimestamp. No update needed."
                            )
                        }
                    }

                    karooSystemServiceProvider.karooSystemService.dispatch(
                        SystemNotification(
                            id = "routegraph-poi-update-found",
                            header = "RouteGraph",
                            message = "POI database update available",
                            style = SystemNotification.Style.UPDATE,
                            action = "Download",
                            actionIntent = "de.timklge.karooroutegraph.POI_UPDATE_INTENT"
                        )
                    )

                    break
                } catch (e: Exception) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error checking for PBF updates", e)

                    delay(60 * 1000) // Wait 1 minute before retrying on error
                    retryCount++

                    if (retryCount > 5) {
                        Log.e(KarooRouteGraphExtension.TAG, "Too many errors checking for PBF updates. Stopping update check job.")
                        return@launch
                    }
                }
            }
        }
    }

    fun startDownloadJob() {
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val downloadedPbfs = streamPbfDownloadStore(context).filter { list ->
                    list.any { it.downloadState == PbfDownloadStatus.PENDING || it.downloadState == PbfDownloadStatus.UPDATING }
                }.first()

                val nextPbfToDownload = downloadedPbfs.firstOrNull { pbf -> pbf.downloadState == PbfDownloadStatus.PENDING || pbf.downloadState == PbfDownloadStatus.UPDATING }
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
                    okHttpClient.newCall(request).executeAsync().use { response ->
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