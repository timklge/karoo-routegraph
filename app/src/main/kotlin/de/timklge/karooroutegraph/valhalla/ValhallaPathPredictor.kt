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

package de.timklge.karooroutegraph.valhalla

import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.RiddenRouteProvider
import de.timklge.karooroutegraph.throttle
import de.timklge.karooroutegraph.valhalla.ValhallaPathPredictor.Companion.MAX_PREDICTION_ITERATIONS
import de.timklge.karooroutegraph.valhalla.ValhallaPathPredictor.Companion.PREDICTION_DISTANCE_METERS
import de.timklge.karooroutegraph.valhalla.ValhallaPathPredictor.Companion.SAME_ROAD_MIN_EDGE_METERS
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * Path predictor algorithm: Try to stay on the current road whenever possible, otherwise pick the
 * "straightest" continuations at intersections. Edges shorter than [SAME_ROAD_MIN_EDGE_METERS] do not count as
 * the current road. "Straightest" means the continuation with the smallest change in direction compared to the
 * heading [GENERAL_HEADING_LOOKBACK_METERS] before the intersection.
 * Never visit the same edge twice, and stop when the predicted path reaches
 * [PREDICTION_DISTANCE_METERS] or when [MAX_PREDICTION_ITERATIONS] is reached.
 */
class ValhallaPathPredictor(private val valhallaService: ValhallaService, private val riddenRouteProvider: RiddenRouteProvider, private val karooSystemServiceProvider: KarooSystemServiceProvider) {
    companion object {
        private const val PREDICTION_DISTANCE_METERS = 5_000.0
        private const val MAX_PREDICTION_ITERATIONS = 100
        private const val INTERSECTION_TOLERANCE_METERS = 20.0
        private const val SAME_ROAD_MIN_EDGE_METERS = 40.0
        internal const val GENERAL_HEADING_LOOKBACK_METERS = 100.0
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _predictedPath = MutableStateFlow<LineString?>(null)

    /** The path the rider is most likely to take next, or null if it could not be determined. */
    val predictedPath: StateFlow<LineString?> = _predictedPath.asStateFlow()

    data class StreamData(val lng: Double, val lat: Double, val altitude: Double?, val bearing: Double?)

    fun start() {
        scope.launch {
            karooSystemServiceProvider.streamRideState().distinctUntilChanged().collectLatest { rideState ->
                val isRunning = rideState is RideState.Recording

                if (!isRunning) {
                    _predictedPath.value = null
                }

                if (isRunning) {
                    val locationFlow = karooSystemServiceProvider.streamDataFlow(DataType.Type.LOCATION).mapNotNull { (it as? StreamState.Streaming) }.throttle(10_000L).mapNotNull { currentLocationDataPoint ->
                        val lng = currentLocationDataPoint.dataPoint.values[DataType.Field.LOC_LONGITUDE]
                        val lat = currentLocationDataPoint.dataPoint.values[DataType.Field.LOC_LATITUDE]
                        val altitude = currentLocationDataPoint.dataPoint.values["FIELD_LOC_ALTITUDE_ID"]
                        val bearing = currentLocationDataPoint.dataPoint.values[DataType.Field.LOC_BEARING]

                        if (lng != null && lat != null) {
                            StreamData(lng, lat, altitude, bearing)
                        } else {
                            null
                        }
                    }

                    locationFlow.collect { location ->
                        Log.i(KarooRouteGraphExtension.TAG, "Current location: $location")

                        val lastPoints = riddenRouteProvider.getRiddenPoints(5).map { it.second }
                        // Valhalla's native trace_attributes crashes on traces with repeated points (e.g. a stationary rider).
                        val points = deduplicate(lastPoints + Point.fromLngLat(location.lng, location.lat))

                        if (points.size >= 2) {
                            val lineString = LineString.fromLngLats(points)
                            val currentPath = try {
                                valhallaService.mapMatch(lineString)
                            } catch (e: Exception) {
                                Log.e(KarooRouteGraphExtension.TAG, "Unable to map match the ridden points", e)
                                null
                            }

                            val predictedPath = if (currentPath != null) predictPath(currentPath, points, location.bearing) else null

                            if (predictedPath != null) {
                                Log.i(KarooRouteGraphExtension.TAG, "Predicted path: ${predictedPath.coordinates().size} points")
                            } else {
                                Log.d(KarooRouteGraphExtension.TAG, "Unable to predict the path the user is likely to take")
                            }

                            _predictedPath.value = predictedPath
                        }
                    }
                }
            }
        }
    }

    /**
     * Predicts the next [PREDICTION_DISTANCE_METERS] meters of the path the user is likely to take.
     *
     * The edge the user is currently on is chosen among the edges located at the user's position, favouring
     * the last matched way of the given trace_attributes response but following the heading and position
     * when they disagree. Its shape and attributes are retrieved via the Valhalla locate API. At each intersection,
     * the prediction assumes that the user stays on the current road if possible. Otherwise, the
     * straightest continuation is chosen. No edge is visited twice.
     */
    private fun predictPath(traceAttributes: String, riddenPoints: List<Point>, currentHeading: Double?): LineString? {
        if (riddenPoints.size < 2) return null

        val currentWayId = try {
            json.decodeFromString(TraceAttributesResponse.serializer(), traceAttributes).edges.lastOrNull()?.wayId
        } catch (e: Exception) {
            Log.e(KarooRouteGraphExtension.TAG, "Unable to parse trace attributes response", e)
            null
        } ?: return null

        // The trace response is produced on meili's own map matching graph, so its edge ids do not
        // match the routing graph ids returned by locate; the current edge is identified by its way id instead.
        val locatedEdges = locate(riddenPoints.last()) ?: return null
        val (currentEdge, startPoint) = selectCurrentEdge(locatedEdges, riddenPoints, currentWayId, currentHeading) ?: return null

        var lastEdge = currentEdge
        // The road the rider is on. Edges shorter than SAME_ROAD_MIN_EDGE_METERS do not replace it, so the
        // road before a tiny segment remains the preferred continuation after it.
        var currentRoadWayId = currentEdge.wayId
        val visitedEdges = mutableSetOf(lastEdge.identity())
        val predictedPoints = remainderFrom(currentEdge.shape, startPoint).toMutableList()
        var predictedDistance = pathLength(predictedPoints)

        // The path travelled so far: the rider's trail on the current edge followed by the predicted path.
        // Its heading over the last GENERAL_HEADING_LOOKBACK_METERS before each intersection is the reference
        // for choosing the straightest continuation there.
        val pathBeforeIntersection = (shapeBehind(currentEdge.shape, startPoint) + predictedPoints.drop(1)).toMutableList()

        var iterations = 0
        while (predictedDistance < PREDICTION_DISTANCE_METERS && iterations++ < MAX_PREDICTION_ITERATIONS) {
            val intersection = lastEdge.shape.last()

            val candidates = (locate(intersection) ?: break)
                .mapNotNull { orientEdgeAtIntersection(it, intersection) }
                .filter { it.identity() !in visitedEdges }

            // If the rider has not travelled any distance yet, fall back to the direction of the current edge.
            val generalHeading = if (pathLength(pathBeforeIntersection) > 0.0) {
                trailingHeading(pathBeforeIntersection, GENERAL_HEADING_LOOKBACK_METERS)
            } else {
                headingNear(currentEdge.shape, currentEdge.shape.lastIndex)
            }

            lastEdge = selectNextEdge(currentRoadWayId, generalHeading, candidates) ?: break
            visitedEdges += lastEdge.identity()
            if (pathLength(lastEdge.shape) >= SAME_ROAD_MIN_EDGE_METERS) currentRoadWayId = lastEdge.wayId

            predictedPoints += lastEdge.shape
            pathBeforeIntersection += lastEdge.shape.drop(1)
            predictedDistance += pathLength(lastEdge.shape)
        }

        val trimmed = trimToDistance(predictedPoints, PREDICTION_DISTANCE_METERS)
        if (trimmed.size < 2) return null

        return LineString.fromLngLats(trimmed)
    }

    private fun locate(point: Point): List<LocatedEdge>? {
        val response = valhallaService.locate(point) ?: return null

        return try {
            json.decodeFromString(ListSerializer(LocateLocation.serializer()), response).firstOrNull()?.edges
        } catch (e: Exception) {
            Log.e(KarooRouteGraphExtension.TAG, "Unable to parse locate response", e)
            null
        }
    }

    /**
     * Finds the edge the user is currently on among the edges located at the user's position, oriented
     * in the direction the user is travelling, along with the point where the user is located on it.
     */
    private fun selectCurrentEdge(locatedEdges: List<LocatedEdge>, riddenPoints: List<Point>, currentWayId: String, currentHeading: Double?): Pair<PredictedEdge, Point>? {
        val userPosition = riddenPoints.last()
        val userHeading = currentHeading ?: bearing(riddenPoints[riddenPoints.size - 2], userPosition)

        val candidates = locatedEdges.mapNotNull { locatedEdge ->
            val shape = locatedEdge.edgeInfo?.shape?.let { decodePolyline(it) } ?: return@mapNotNull null
            val projection = locatedEdge.correlatedLat?.let { lat ->
                locatedEdge.correlatedLon?.let { lon -> Point.fromLngLat(lon, lat) }
            } ?: userPosition

            CurrentEdgeCandidate(locatedEdge.edgeInfo?.wayId ?: "", shape, projection)
        }

        return chooseCurrentEdge(candidates, userPosition, userHeading, currentWayId)
    }

    /**
     * Orients an edge found near an intersection so that it starts at the intersection,
     * discarding edges that do not touch it or that lead into it instead of away from it.
     */
    private fun orientEdgeAtIntersection(locatedEdge: LocatedEdge, intersection: Point): PredictedEdge? {
        val shape = locatedEdge.edgeInfo?.shape?.let { decodePolyline(it) } ?: return null
        if (shape.size < 2) return null

        // Locate returns every edge near the intersection; keep only the ones that actually touch it
        // and orient them so that they lead away from it. Comparing both ends also handles edges
        // that are shorter than the tolerance.
        val distanceToStart = distance(shape.first(), intersection)
        val distanceToEnd = distance(shape.last(), intersection)

        if (min(distanceToStart, distanceToEnd) > INTERSECTION_TOLERANCE_METERS) return null

        return PredictedEdge(locatedEdge.edgeInfo?.wayId ?: "", if (distanceToStart <= distanceToEnd) shape else shape.reversed())
    }

    /**
     * Stays on the current road (same way) if possible, otherwise picks the straightest continuation.
     * Candidates shorter than [SAME_ROAD_MIN_EDGE_METERS] do not count as staying on the current road, since
     * tiny segments carrying the road's name at an intersection are not a real continuation. The road is
     * therefore still [currentRoadWayId] after such a segment has been skipped.
     * Straightness is the difference between [generalHeading], the average heading over the last
     * [GENERAL_HEADING_LOOKBACK_METERS] before the intersection, and the average heading of each candidate over its first
     * [GENERAL_HEADING_LOOKBACK_METERS], so short segments right before or after a sharp turn do not dominate the choice.
     */
    private fun selectNextEdge(currentRoadWayId: String, generalHeading: Double, candidates: List<PredictedEdge>): PredictedEdge? =
        candidates.minWithOrNull(
            compareBy(
                { if (it.wayId == currentRoadWayId && pathLength(it.shape) >= SAME_ROAD_MIN_EDGE_METERS) 0 else 1 },
                { angularDifference(leadingHeading(it.shape, GENERAL_HEADING_LOOKBACK_METERS), generalHeading) },
            )
        )

    fun stop() {
        scope.cancel()
        _predictedPath.value = null
    }
}
