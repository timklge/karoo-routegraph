package de.timklge.karooroutegraph.pois

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.topobyte.osm4j.core.model.iface.EntityContainer
import de.topobyte.osm4j.core.model.iface.EntityType
import de.topobyte.osm4j.core.model.iface.OsmNode
import de.topobyte.osm4j.core.model.util.OsmModelUtil
import de.topobyte.osm4j.pbf.seq.PbfIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val downloadedPbfDao: DownloadedPbfDao,
    private val nodeDao: NodeDao,
) {
    companion object {
        const val DOWNLOAD_URL = "https://routegraph.timklge.de/pois"
        const val CHUNK_SIZE = 20_000
    }

    private val okHttpClient = OkHttpClient()

    fun getDownloadUrl(countryKey: String): String {
        return "$DOWNLOAD_URL/pois.${countryKey.lowercase()}.pbf"
    }

    private var downloadJob: Job? = null
    private var processingJob: Job? = null
    var countriesData: Map<String, CountryData>? = null

    data class CountryData(
        val name: String,
        val bounds: List<Double> // [minLon, minLat, maxLon, maxLat]
    )

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
                CountryData(countryName, bounds)
            }
        } catch (t: Throwable) {
            Log.e(KarooRouteGraphExtension.TAG, "Failed to read country bounding boxes", t)
        }
    }

    fun startDownloadJob() {
        loadCountryData()

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            downloadedPbfDao.getPendingDownloads().distinctUntilChanged().collect { downloadedPbfs ->
                val nextPbfToDownload = downloadedPbfs.firstOrNull()
                if (nextPbfToDownload == null) {
                    return@collect
                }

                val downloadUrl = getDownloadUrl(nextPbfToDownload.countryKey)

                try {
                    // Update status to PROCESSING
                    downloadedPbfDao.updateDownloadStatus(
                        nextPbfToDownload.countryKey,
                        PbfDownloadStatus.PROCESSING,
                        0.0f
                    )

                    // Build OkHttp request
                    val request = Request.Builder()
                        .url(downloadUrl)
                        .build()

                    // Execute the request
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to download PBF: HTTP ${response.code}")
                            downloadedPbfDao.updateDownloadStatus(
                                nextPbfToDownload.countryKey,
                                PbfDownloadStatus.DOWNLOAD_FAILED,
                                0.0f
                            )
                            return@collect
                        }

                        val responseBody = response.body
                        val fileLength = responseBody.contentLength()
                        val inputStream = responseBody.byteStream()

                        // Create output file in app's private storage
                        val outputFile = File(context.filesDir, "pois.${nextPbfToDownload.countryKey.lowercase()}.pbf")
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
                                    downloadedPbfDao.updateDownloadStatus(
                                        nextPbfToDownload.countryKey,
                                        PbfDownloadStatus.PROCESSING,
                                        progress
                                    )
                                    lastProgressUpdate = progress
                                }
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        // Mark as processing
                        downloadedPbfDao.updateDownloadStatus(
                            nextPbfToDownload.countryKey,
                            PbfDownloadStatus.PROCESSING,
                            1.0f
                        )

                        Log.i(KarooRouteGraphExtension.TAG, "Successfully downloaded PBF for ${nextPbfToDownload.countryKey}")
                    }
                } catch (e: Exception) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error downloading PBF for ${nextPbfToDownload.countryKey}", e)

                    downloadedPbfDao.updateDownloadStatus(
                        nextPbfToDownload.countryKey,
                        PbfDownloadStatus.PENDING,
                        0.0f
                    )
                }
            }
        }

        processingJob = CoroutineScope(Dispatchers.IO).launch {
            downloadedPbfDao.getPendingDownloads().distinctUntilChanged()
                .collect { processingPbfs ->
                    val nextPbfToProcess = processingPbfs.firstOrNull()
                    if (nextPbfToProcess == null) {
                        return@collect
                    }

                    val outputFile = File(context.filesDir, "pois.${nextPbfToProcess.countryKey.lowercase()}.pbf")

                    try {
                        outputFile.inputStream().use { inputStream ->
                            val iterator = PbfIterator(inputStream, false)

                            (iterator as Iterator<EntityContainer>).asSequence()
                                .filter { it.type == EntityType.Node }
                                .map { entity ->
                                    val osmEntity = entity.entity
                                    val tags = OsmModelUtil.getTagsAsMap(osmEntity)

                                    osmEntity to tags
                                }
                                .chunked(CHUNK_SIZE)
                                .forEach { chunk ->
                                    val nodes = chunk.map { (entity, _) ->
                                        val node = entity as OsmNode
                                        Node(
                                            id = entity.id,
                                            lat = node.latitude,
                                            lon = node.longitude,
                                        )
                                    }
                                    val tagsList = chunk.map { (entity, tags) ->
                                        tags.map { (k, v) ->
                                            Tag(
                                                nodeId = entity.id,
                                                key = k,
                                                value = v
                                            )
                                        }
                                    }.flatten()

                                    // Insert into database
                                    nodeDao.insertNodes(nodes)
                                    nodeDao.insertTags(tagsList)
                                }
                        }

                        Log.i(KarooRouteGraphExtension.TAG, "Successfully processed PBF for ${nextPbfToProcess.countryKey}")

                        downloadedPbfDao.updateDownloadStatus(
                            nextPbfToProcess.countryKey,
                            PbfDownloadStatus.AVAILABLE,
                            0.0f
                        )
                    } catch (t: Throwable) {
                        Log.e(KarooRouteGraphExtension.TAG, "Error processing downloaded PBF for ${nextPbfToProcess.countryKey}", t)
                        downloadedPbfDao.updateDownloadStatus(
                            nextPbfToProcess.countryKey,
                            PbfDownloadStatus.PROCESSING_FAILED,
                            0.0f
                        )
                        return@collect
                    } finally {
                        outputFile.delete()
                    }
                }
        }
    }
}