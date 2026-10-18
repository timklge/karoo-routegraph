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

package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val VALUES_PER_POINT = 4

class RiddenRouteProvider(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    context: Context,
) {
    companion object {
        private const val TAG = "RiddenRouteProvider"
        private const val MIN_POINT_DISTANCE_METERS = 10.0
        private val SAVE_INTERVAL = 60.seconds
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val riddenTrackFile = File(context.filesDir, "ridden_track")
    private val riddenRoutePoints = DoubleArrayBuffer()
    private val persistenceMutex = Mutex()

    fun getRiddenPoints(numberOfLastPoints: Int? = null): List<Pair<Double, Point>> {
        val points = synchronized(riddenRoutePoints) {
            riddenRoutePoints.snapshot(numberOfLastPoints, VALUES_PER_POINT)
        }

        val result = mutableListOf<Pair<Double, Point>>()

        for (i in points.indices step VALUES_PER_POINT) {
            val distance = points[i]
            val latitude = points[i + 1]
            val longitude = points[i + 2]
            val altitude = points[i + 3]

            val point = Point.fromLngLat(longitude, latitude, altitude)

            result.add(distance to point)
        }

        return result
    }

    fun getRiddenRoute(numberOfLastPoints: Int? = null): LineString {
        val points = getRiddenPoints(numberOfLastPoints)
        val coordinates = points.map { it.second }

        return LineString.fromLngLats(coordinates)
    }

    fun start() {
        scope.launch {
            loadRiddenRoute()

            launch {
                combine(
                    karooSystemServiceProvider.streamDataFlow(DataType.Type.DISTANCE)
                        .mapNotNull { it as? StreamState.Streaming },
                    karooSystemServiceProvider.streamDataFlow(DataType.Type.LOCATION)
                        .mapNotNull { it as? StreamState.Streaming },
                ) { distanceDataPoint, locationDataPoint ->
                    val distance = distanceDataPoint.dataPoint.singleValue
                    val latitude = locationDataPoint.dataPoint.values[DataType.Field.LOC_LATITUDE]
                    val longitude = locationDataPoint.dataPoint.values[DataType.Field.LOC_LONGITUDE]
                    val altitude = locationDataPoint.dataPoint.values["FIELD_LOC_ALTITUDE_ID"]

                    if (distance != null && latitude != null && longitude != null && altitude != null) {
                        doubleArrayOf(distance, latitude, longitude, altitude)
                    } else {
                        null
                    }
                }.collect { point ->
                    if (point != null) {
                        synchronized(riddenRoutePoints) {
                            val lastPointPosition = if (riddenRoutePoints.size >= VALUES_PER_POINT) Point.fromLngLat(
                                riddenRoutePoints.getOrNull(riddenRoutePoints.size - 2) ?: error("Latitude value missing"),
                                riddenRoutePoints.getOrNull(riddenRoutePoints.size - 3) ?: error("Longitude value missing"),
                            ) else null

                            val distanceToLastPoint = if (lastPointPosition != null) {
                                TurfMeasurement.distance(
                                    lastPointPosition,
                                    Point.fromLngLat(point[2], point[1]),
                                    TurfConstants.UNIT_METERS,
                                )
                            } else {
                                null
                            }

                            if (distanceToLastPoint == null ||
                                distanceToLastPoint >= MIN_POINT_DISTANCE_METERS
                            ) {
                                riddenRoutePoints.addPoint(
                                    point[0],
                                    point[1],
                                    point[2],
                                    point[3],
                                )
                            }
                        }
                    }
                }
            }

            launch {
                var rideWasActive = false
                karooSystemServiceProvider.streamRideState().collect { rideState ->
                    if (rideState !is RideState.Idle) {
                        rideWasActive = true
                    } else if (rideWasActive) {
                        persistenceMutex.withLock {
                            synchronized(riddenRoutePoints) {
                                riddenRoutePoints.clear()
                            }
                            if (riddenTrackFile.exists() && !riddenTrackFile.delete()) {
                                Log.e(TAG, "Failed to delete ${riddenTrackFile.name} after the ride ended")
                            }
                        }
                        rideWasActive = false
                    }
                }
            }

            launch {
                while (true) {
                    delay(SAVE_INTERVAL)
                    persistRiddenRoute()
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun loadRiddenRoute() {
        if (!riddenTrackFile.exists()) return

        persistenceMutex.withLock {
            try {
                DataInputStream(BufferedInputStream(riddenTrackFile.inputStream())).use { input ->
                    val fileLength = riddenTrackFile.length()
                    val valuesToRead = fileLength / java.lang.Double.BYTES
                    if (fileLength % (java.lang.Double.BYTES * VALUES_PER_POINT) != 0L) {
                        Log.w(TAG, "Ignoring incomplete trailing values in ${riddenTrackFile.name}")
                    }
                    val pointValues = valuesToRead - valuesToRead % VALUES_PER_POINT
                    val loadedPoints = DoubleArray(pointValues.toInt())
                    repeat(pointValues.toInt()) {
                        loadedPoints[it] = input.readDouble()
                    }
                    synchronized(riddenRoutePoints) {
                        riddenRoutePoints.replaceWith(loadedPoints)
                    }
                }
                val restoredPointCount = synchronized(riddenRoutePoints) {
                    riddenRoutePoints.size / VALUES_PER_POINT
                }
                Log.i(TAG, "Restored $restoredPointCount ridden route points")
            } catch (exception: IOException) {
                Log.e(TAG, "Failed to read ${riddenTrackFile.name}", exception)
            }
        }
    }

    private suspend fun persistRiddenRoute() {
        persistenceMutex.withLock {
            val points = synchronized(riddenRoutePoints) { riddenRoutePoints.toDoubleArray() }
            val temporaryFile = File(riddenTrackFile.parentFile, "${riddenTrackFile.name}.tmp")
            try {
                FileOutputStream(temporaryFile).use { fileOutput ->
                    val output = DataOutputStream(BufferedOutputStream(fileOutput))
                    points.forEach(output::writeDouble)
                    output.flush()
                    fileOutput.fd.sync()
                }
                Files.move(
                    temporaryFile.toPath(),
                    riddenTrackFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (exception: IOException) {
                Log.e(TAG, "Failed to write ${riddenTrackFile.name}", exception)
            } finally {
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    Log.w(TAG, "Failed to remove temporary ridden track file")
                }
            }
        }
    }

    private class DoubleArrayBuffer {
        private var values = DoubleArray(0)
        var size: Int = 0
            private set

        fun addPoint(distance: Double, latitude: Double, longitude: Double, altitude: Double) {
            ensureCapacity(size + VALUES_PER_POINT)
            values[size++] = distance
            values[size++] = latitude
            values[size++] = longitude
            values[size++] = altitude
        }

        fun getOrNull(index: Int): Double? =
            if (index in 0 until size) values[index] else null

        fun snapshot(numberOfLastPoints: Int?, valuesPerPoint: Int): DoubleArray {
            require(numberOfLastPoints == null || numberOfLastPoints >= 0)
            val valuesToCopy = if (numberOfLastPoints == null) {
                size
            } else {
                minOf(size, numberOfLastPoints.coerceAtMost(size / valuesPerPoint) * valuesPerPoint)
            }
            return values.copyOfRange(size - valuesToCopy, size)
        }

        fun toDoubleArray(): DoubleArray = values.copyOf(size)

        fun replaceWith(newValues: DoubleArray) {
            values = newValues
            size = newValues.size
        }

        fun clear() {
            size = 0
        }

        private fun ensureCapacity(minimumCapacity: Int) {
            if (minimumCapacity <= values.size) return
            val expandedCapacity = if (values.isEmpty()) {
                minimumCapacity.coerceAtLeast(16)
            } else {
                maxOf(minimumCapacity, values.size * 2)
            }
            values = values.copyOf(expandedCapacity)
        }
    }
}
