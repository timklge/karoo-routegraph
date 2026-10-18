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

import com.mapbox.geojson.Point
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// trace_attributes response (only the fields needed to identify the edge the user is currently on)

@Serializable
internal data class TraceAttributesResponse(
    val edges: List<TracedEdge> = emptyList(),
)

@Serializable
internal data class TracedEdge(
    // way ids are unsigned 64 bit values that can exceed Long.MAX_VALUE, so they are parsed as strings
    @SerialName("way_id") val wayId: String? = null,
)

// locate response (verbose, only the fields needed for path prediction)

@Serializable
internal data class LocateLocation(
    val edges: List<LocatedEdge>? = null,
)

@Serializable
internal data class LocatedEdge(
    @SerialName("edge_info") val edgeInfo: LocatedEdgeInfo? = null,
    @SerialName("correlated_lat") val correlatedLat: Double? = null,
    @SerialName("correlated_lon") val correlatedLon: Double? = null,
)

@Serializable
internal data class LocatedEdgeInfo(
    val shape: String? = null,
    @SerialName("way_id") val wayId: String = "",
)

/**
 * An edge of the valhalla graph oriented in the direction the user is travelling.
 */
internal class PredictedEdge(
    val wayId: String,
    val shape: List<Point>,
) {
    /** Direction-independent identity of the edge, so that traversing it backwards counts as a revisit. */
    fun identity(): Set<Point> = setOf(shape.first(), shape.last())
}

/** An edge located near the user, not yet oriented. [projection] is the point on the edge closest to the user. */
internal class CurrentEdgeCandidate(
    val wayId: String,
    val shape: List<Point>,
    val projection: Point,
)
