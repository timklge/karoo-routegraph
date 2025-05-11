package de.timklge.karooroutegraph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import de.timklge.karooroutegraph.datatypes.minimap.MinimapViewModelProvider
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant

class TileDownloadService(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val minimapViewModelProvider: MinimapViewModelProvider,
    private val context: Context,
) {
    data class CachedTile(val lastAccessed: Instant, val bitmap: Bitmap)

    private val cacheDir = File(context.cacheDir, "tiles")
    private val inMemoryCache = mutableMapOf<Tile, CachedTile>()
    private val mutex = Mutex()
    private val maxCacheSize = 50
    private val maxCacheAge = Duration.ofMinutes(30)

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    private var downloadJob: Job? = null
    private var downloadQueue: Channel<Tile> = Channel(Channel.UNLIMITED)

    fun startDownloadJob() {
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            for (tile in downloadQueue) {
                try {
                    val downloadedTile = getTileIfAvailableInstantly(tile)
                    if (downloadedTile != null) {
                        Log.d(KarooRouteGraphExtension.TAG, "Tile $tile already in cache")
                        continue
                    }
                    val fetchedBitmap = fetchTileFromNetwork(tile)

                    // Save to caches (file and memory)
                    mutex.withLock {
                        val tileFile = getTileFile(tile)
                        saveTileToFile(fetchedBitmap, tileFile)
                        gcCache() // Clean up memory cache before adding new item
                        inMemoryCache[tile] = CachedTile(Instant.now(), fetchedBitmap)
                        Log.d(KarooRouteGraphExtension.TAG, "Fetched tile ${tile}, saved to file and added to memory cache")
                    }

                    minimapViewModelProvider.update {
                        it.copy(lastTileDownloadedAt = Instant.now())
                    }
                } catch (e: Exception) {
                    Log.e(KarooRouteGraphExtension.TAG, "Error downloading tile $tile", e)
                }
            }
        }
    }

    private fun getTileFile(tile: Tile): File {
        return File(cacheDir, "${tile.style}-${tile.z}-${tile.x}-${tile.y}.png")
    }

    suspend fun getTileIfAvailableInstantly(tile: Tile): Bitmap? {
        mutex.withLock {
            val cachedTile = inMemoryCache[tile]
            if (cachedTile != null) {
                // Update last accessed time in memory cache
                inMemoryCache[tile] = cachedTile.copy(lastAccessed = Instant.now())
                return cachedTile.bitmap
            }
        }

        // Check file system cache (outside memory cache lock initially for read)
        val tileFile = getTileFile(tile)
        if (tileFile.exists()) {
            try {
                val bitmap = loadTileFromFile(tileFile)
                if (bitmap != null) {
                    // Add to memory cache (needs lock)
                    mutex.withLock {
                        // Double check if another thread added it while we were reading the file
                        if (!inMemoryCache.containsKey(tile)) {
                            gcCache() // Make space if needed before adding
                            inMemoryCache[tile] = CachedTile(Instant.now(), bitmap)
                            Log.d(KarooRouteGraphExtension.TAG, "Loaded tile ${tile} from file cache into memory")
                        } else {
                            // Already in memory, update access time
                            inMemoryCache[tile] = inMemoryCache[tile]!!.copy(lastAccessed = Instant.now())
                        }
                        // Return the bitmap from the (potentially updated) memory cache entry
                        return inMemoryCache[tile]?.bitmap
                    }
                } else {
                    // File exists but couldn't be decoded, delete it
                    Log.w(KarooRouteGraphExtension.TAG, "Could not decode tile file ${tileFile.path}, deleting.")
                    tileFile.delete()
                }
            } catch (e: IOException) {
                Log.e(KarooRouteGraphExtension.TAG, "Error reading tile file ${tileFile.path}", e)
                // Optionally delete corrupted file: tileFile.delete()
            }
        }

        return null // Not found in memory or file cache
    }

    fun queueTileDownload(tile: Tile) {
        // If not available instantly, add to download queue
        downloadQueue.trySendBlocking(tile)
    }

    private suspend fun fetchTileFromNetwork(tile: Tile): Bitmap {
        // This logic remains largely the same, just extracted
        return callbackFlow {
            val url = "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"

            Log.d(KarooRouteGraphExtension.TAG, "Http tile request to ${url}...")

            val listenerId = karooSystemServiceProvider.karooSystemService.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    "GET",
                    url,
                    waitForConnection = false,
                    headers = mapOf("User-Agent" to KarooRouteGraphExtension.TAG),
                ),
            ) { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete) {
                    val completeEvent = (event.state as HttpResponseState.Complete)

                    try {
                        val inputStream =
                            java.io.ByteArrayInputStream(completeEvent.body ?: ByteArray(0))

                        if (completeEvent.error != null) {
                            Log.e(
                                KarooRouteGraphExtension.TAG,
                                "Http response event; error ${completeEvent.error}"
                            )
                            error(completeEvent.error ?: "Unknown error")
                        }

                        val result = BitmapFactory.decodeStream(inputStream)

                        trySendBlocking(result)
                    } catch (e: Throwable) {
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

    private fun saveTileToFile(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.v(KarooRouteGraphExtension.TAG, "Saved tile to ${file.path}")
        } catch (e: IOException) {
            Log.e(KarooRouteGraphExtension.TAG, "Error saving tile to ${file.path}", e)
            // Attempt to delete partially written file
            file.delete()
        }
    }

    private fun loadTileFromFile(file: File): Bitmap? {
        return try {
            FileInputStream(file).use { fis ->
                BitmapFactory.decodeStream(fis)
            }.also {
                if (it != null) Log.v(KarooRouteGraphExtension.TAG, "Loaded tile from ${file.path}")
                else Log.w(KarooRouteGraphExtension.TAG, "Failed to decode bitmap from ${file.path}")
            }
        } catch (e: IOException) {
            Log.e(KarooRouteGraphExtension.TAG, "Error loading tile from ${file.path}", e)
            null
        }
    }


    private fun gcCache() {
        // Assumes this function is called within a mutex lock
        val now = Instant.now()
        val expiryTime = now.minus(maxCacheAge)

        // Remove entries older than maxCacheAge
        val iterator = inMemoryCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastAccessed.isBefore(expiryTime)) {
                iterator.remove()
                Log.d(KarooRouteGraphExtension.TAG, "GC Cache: Removed expired tile ${entry.key}")
            }
        }

        // If cache still exceeds max size, remove the oldest entries
        if (inMemoryCache.size > maxCacheSize) {
            // Sort entries by lastAccessed time (oldest first)
            val sortedEntries = inMemoryCache.entries.sortedBy { it.value.lastAccessed }
            val entriesToRemove = inMemoryCache.size - maxCacheSize

            // Remove the oldest entries
            for (i in 0 until entriesToRemove) {
                val entryToRemove = sortedEntries[i]
                inMemoryCache.remove(entryToRemove.key)
                Log.d(KarooRouteGraphExtension.TAG, "GC Cache: Removed oldest tile ${entryToRemove.key} due to size limit")
            }
        }
    }

}
