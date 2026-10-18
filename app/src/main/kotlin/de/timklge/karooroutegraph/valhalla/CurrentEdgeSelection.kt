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
import kotlin.math.min

private const val TRACE_WAY_MISMATCH_PENALTY_DEGREES = 30.0
private const val DISTANCE_PENALTY_DEGREES_PER_METER = 2.0

/**
 * Picks the edge the user is on from the located candidates and returns it oriented in the direction of travel,
 * together with the projection of the user onto it.
 *
 * Each candidate is scored in degrees, lower is better, from three parts:
 * - how far its axis deviates from the user's heading. Orientation is ignored here, so a crossing road is
 *   penalised by up to 90 degrees while the road actually being ridden is not.
 * - [TRACE_WAY_MISMATCH_PENALTY_DEGREES] if the candidate is not the way meili last matched the user on. This is only
 *   a tie-breaker, so a trace that flips to the crossing road at an intersection does not override the heading.
 * - [DISTANCE_PENALTY_DEGREES_PER_METER] per meter between the user and the projection. This favours the road the
 *   user has just moved onto over the one they have just left at a junction.
 */
internal fun chooseCurrentEdge(candidates: List<CurrentEdgeCandidate>, userPosition: Point, userHeading: Double, traceWayId: String): Pair<PredictedEdge, Point>? =
    candidates
        .filter { it.shape.size >= 2 }
        .map { candidate ->
            val edgeHeading = headingNear(candidate.shape, nearestPointIndex(candidate.shape, candidate.projection))
            val headingDifference = angularDifference(userHeading, edgeHeading)
            val axisDifference = min(headingDifference, 180.0 - headingDifference)
            val traceMismatch = if (candidate.wayId == traceWayId) 0.0 else TRACE_WAY_MISMATCH_PENALTY_DEGREES
            val cost = axisDifference + traceMismatch + distance(userPosition, candidate.projection) * DISTANCE_PENALTY_DEGREES_PER_METER

            val oriented = if (headingDifference <= 90.0) candidate.shape else candidate.shape.reversed()

            Triple(cost, PredictedEdge(candidate.wayId, oriented), candidate.projection)
        }
        .minByOrNull { it.first }
        ?.let { it.second to it.third }
