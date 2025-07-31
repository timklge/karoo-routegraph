package de.timklge.karooroutegraph

import android.content.Context
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.screens.PoiSortOption
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import io.hammerhead.karooext.models.Symbol
import kotlin.math.absoluteValue

data class NearestPoint(val pointOnRoute: Point?, val distanceFromPointOnRoute: Float, val distanceFromRouteStart: Float, val target: Point?)

enum class PoiType {
    POI, INCIDENT
}

data class POI(val symbol: Symbol.POI, val type: PoiType = PoiType.POI)

sealed class DistanceToPoiResult : Comparable<DistanceToPoiResult> {
    data class LinearDistance(val distance: Double) : DistanceToPoiResult()
    data class AheadOnRouteDistance(val distanceOnRoute: Double, val distanceFromPointOnRoute: Double, val elevationMetersRemaining: Double?) : DistanceToPoiResult()

    override fun compareTo(other: DistanceToPoiResult): Int {
        return when {
            this is LinearDistance && other is LinearDistance -> this.distance.compareTo(other.distance)
            this is AheadOnRouteDistance && other is AheadOnRouteDistance -> {
                (this.distanceOnRoute + this.distanceFromPointOnRoute).compareTo(other.distanceOnRoute + other.distanceFromPointOnRoute)
            }
            this is LinearDistance && other is AheadOnRouteDistance -> 1 // Linear distance is always greater than ahead on route distance
            this is AheadOnRouteDistance && other is LinearDistance -> -1 // Ahead on route distance is always less than linear distance
            else -> 0 // Should not happen, but just in case
        }
    }

    fun formatDistance(context: Context, isImperial: Boolean, flat: Boolean = false): String {
        return when (this){
            is LinearDistance -> de.timklge.karooroutegraph.screens.formatDistance(
                distance,
                isImperial
            )
            is AheadOnRouteDistance -> {
                if (flat){
                    de.timklge.karooroutegraph.screens.formatDistance (distanceOnRoute + distanceFromPointOnRoute, isImperial)
                } else {
                    buildString {
                        append(de.timklge.karooroutegraph.screens.formatDistance(distanceOnRoute, isImperial))
                        append(" ${context.getString(R.string.distance_ahead)}, ")
                        append(de.timklge.karooroutegraph.screens.formatDistance(distanceFromPointOnRoute, isImperial))
                        append(" ${context.getString(R.string.distance_from_route)}")
                        if (elevationMetersRemaining != null) {
                            append(" ↗ ${distanceToString(elevationMetersRemaining.toFloat(), isImperial, true)}")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Calculate the distances of all POIs to the given polyline asynchronously.
 *
 * @param polyline The polyline to calculate distances against.
 * @param pois The list of POIs to calculate distances for.
 * @param maxDistanceToRoute The maximum distance from the route to consider a POI as relevant.
 * @return A map of POIs to their nearest points on the route and distances.
 */
suspend fun calculatePoiDistancesAsync(polyline: LineString, pois: List<POI>, maxDistanceToRoute: Double): Map<POI, List<NearestPoint>> {
    /* return coroutineScope {
        val deferredResults = pois.map { poi ->
            async {
                poi to calculatePoiDistance(polyline, poi, maxDistanceToRoute)
            }
        }

        deferredResults.awaitAll().toMap()
    } */

    return pois.associateWith { poi ->
        val nearestPoints = calculatePoiDistance(polyline, poi, maxDistanceToRoute)

        nearestPoints
    }
}

/**
 * Get the nearest point on a line segment defined by two points (startPoint and endPoint) to a given point (poiPoint).
 *
 * @param poiPoint The point for which we want to find the nearest point on the line segment.
 * @param startPoint The start point of the line segment.
 * @param endPoint The end point of the line segment.
 * @return The nearest point on the line segment to the given point.
 */
private fun getNearestPointOnLine(poiPoint: Point, startPoint: Point, endPoint: Point): Point {
    val x1 = startPoint.longitude()
    val y1 = startPoint.latitude()
    val x2 = endPoint.longitude()
    val y2 = endPoint.latitude()
    val px = poiPoint.longitude()
    val py = poiPoint.latitude()

    // Vector from start to end point
    val dx = x2 - x1
    val dy = y2 - y1

    // If start and end points are the same, return start point
    if (dx == 0.0 && dy == 0.0) {
        return startPoint
    }

    // Calculate the parameter t for the projection
    // t represents the position along the line segment (0 = start, 1 = end)
    val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)

    // Clamp t to [0, 1] to ensure the point lies on the line segment
    val clampedT = t.coerceIn(0.0, 1.0)

    // Calculate the nearest point coordinates
    val nearestX = x1 + clampedT * dx
    val nearestY = y1 + clampedT * dy

    return Point.fromLngLat(nearestX, nearestY)
}

/**
 * Calculate the distance from a POI to the nearest point on a polyline.
 *
 * @param polyline The polyline to calculate distances against.
 * @param poi The POI to calculate distances for.
 * @param maxDistanceToRoute The maximum distance from the route to consider a POI as relevant.
 * @return A list of nearest points on the route with their distances.
 */
private fun calculatePoiDistance(polyline: LineString, poi: POI, maxDistanceToRoute: Double): List<NearestPoint> {
    val nearestPointCandidates = mutableListOf<NearestPoint>()
    var currentRouteDistance = 0.0f
    val poiPoint = Point.fromLngLat(poi.symbol.lng, poi.symbol.lat)

    val coordinates = polyline.coordinates()
    for (i in 1 until coordinates.size){
        val startPoint = coordinates[i - 1]
        val endPoint = coordinates[i]

        val nearestPointOnSegment = getNearestPointOnLine(poiPoint, startPoint, endPoint)
        val nearestPointDist = TurfMeasurement.distance(poiPoint, nearestPointOnSegment, TurfConstants.UNIT_METERS).toFloat()

        if (nearestPointDist < maxDistanceToRoute){
            val nearestPointRouteDistance = currentRouteDistance + TurfMeasurement.distance(startPoint, nearestPointOnSegment, TurfConstants.UNIT_METERS).toFloat()
            nearestPointCandidates.add(NearestPoint(nearestPointOnSegment, nearestPointDist, nearestPointRouteDistance, poiPoint))
        }

        currentRouteDistance += TurfMeasurement.distance(startPoint, endPoint, TurfConstants.UNIT_METERS).toFloat()
    }

    // Cluster nearest point candidates together
    return buildList {
        nearestPointCandidates.forEach { candidate ->
            val existingCandidate = this@buildList.find { existingPoint ->
                (existingPoint.distanceFromRouteStart - candidate.distanceFromRouteStart).absoluteValue < maxDistanceToRoute * 2
            }

            if (existingCandidate != null){
                if (candidate.distanceFromPointOnRoute < existingCandidate.distanceFromPointOnRoute){
                    this@buildList.remove(existingCandidate)
                    this@buildList.add(candidate)
                }
            } else {
                this@buildList.add(candidate)
            }
        }
    }
}

fun distanceToPoi(poi: Symbol.POI, sampledElevationData: SampledElevationData?, nearestPointsOnRouteToFoundPois: Map<POI, List<NearestPoint>>?, currentPosition: Point?, selectedSort: PoiSortOption, distanceAlongRoute: Float?): DistanceToPoiResult? {
    val linearDistance = currentPosition?.let {
        TurfMeasurement.distance(
            Point.fromLngLat(poi.lng, poi.lat),
            it,
            TurfConstants.UNIT_METERS
        )
    }

    when (selectedSort) {
        PoiSortOption.LINEAR_DISTANCE -> {
            return linearDistance?.let { DistanceToPoiResult.LinearDistance(it) }
        }
        PoiSortOption.AHEAD_ON_ROUTE -> {
            val nearestPoints = nearestPointsOnRouteToFoundPois?.entries?.find { it.key.symbol == poi }?.value
            val nearestPointsAheadOnRoute = nearestPoints?.filter { it.distanceFromRouteStart >= (distanceAlongRoute ?: 0f) }
            val nearestPointAheadOnRoute = nearestPointsAheadOnRoute?.minByOrNull { it.distanceFromPointOnRoute + it.distanceFromRouteStart }

            val distanceAheadOnRoute = nearestPointAheadOnRoute?.let {
                val elevationMetersRemaining = if (distanceAlongRoute != null) {
                    sampledElevationData?.getTotalClimb(distanceAlongRoute, nearestPointAheadOnRoute.distanceFromRouteStart)
                } else null

                DistanceToPoiResult.AheadOnRouteDistance(
                    it.distanceFromRouteStart.toDouble() - (distanceAlongRoute ?: 0.0f),
                    it.distanceFromPointOnRoute.toDouble(),
                    elevationMetersRemaining
                )
            }

            val linearDistanceResult = linearDistance?.let { DistanceToPoiResult.LinearDistance(it) }

            return distanceAheadOnRoute ?: linearDistanceResult
        }
    }
}

fun getStartAndEndPoiIfNone(routeLineString: LineString?, pois: List<Symbol.POI>, settings: RouteGraphSettings?, applicationContext: Context): List<POI> {
    return buildList {
        val startPoint = routeLineString?.coordinates()?.firstOrNull()
        val endPoint = routeLineString?.coordinates()?.lastOrNull()

        // Add start and end of route POIs if no POIs are present there yet
        if (startPoint != null) {
            val hasPoiAtStartPoint = pois.any { poi ->
                val poiPoint = Point.fromLngLat(poi.lng, poi.lat)

                TurfMeasurement.distance(poiPoint, startPoint, TurfConstants.UNIT_METERS) < (settings?.poiDistanceToRouteMaxMeters ?: 500.0)
            }
            if (!hasPoiAtStartPoint) {
                add(POI(
                    Symbol.POI(
                        "start-of-route",
                        startPoint.latitude(),
                        startPoint.longitude(),
                        type = Symbol.POI.Types.GENERIC,
                        name = applicationContext.getString(R.string.start_of_route)
                    ),
                    PoiType.POI
                ))
            }
        }

        if (endPoint != null) {
            val hasPoiAtEndPoint = pois.any { poi ->
                val poiPoint = Point.fromLngLat(poi.lng, poi.lat)

                TurfMeasurement.distance(poiPoint, endPoint, TurfConstants.UNIT_METERS) < (settings?.poiDistanceToRouteMaxMeters ?: 500.0)
            }
            if (!hasPoiAtEndPoint) {
                add(POI(
                    Symbol.POI(
                        "end-of-route",
                        endPoint.latitude(),
                        endPoint.longitude(),
                        type = Symbol.POI.Types.GENERIC,
                        name = applicationContext.getString(R.string.end_of_route)
                    ),
                    PoiType.POI
                ))
            }
        }
    }
}

fun processPoiName(name: String?): String? {
    return when (name) {
        "Startseite" -> "Zu Hause"
        else -> name
    }
}