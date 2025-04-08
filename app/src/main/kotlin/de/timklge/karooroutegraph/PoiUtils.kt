package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import io.hammerhead.karooext.models.Symbol
import kotlin.math.absoluteValue

data class NearestPoint(val pointOnRoute: Point?, val distanceFromPointOnRoute: Float, val distanceFromRouteStart: Float, val target: Point?)

/**
 * Calculate the distances of the given POIs to the given polyline.
 */
fun calculatePoiDistances(polyline: LineString, pois: List<Symbol.POI>): Map<Symbol.POI, List<NearestPoint>> {
    val pointList: MutableList<Point> = mutableListOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 0.0))

    return buildMap {
        pois.forEach { poi ->
            val nearestPointCandidates = mutableListOf<NearestPoint>()
            var currentRouteDistance = 0.0f

            val coordinates = polyline.coordinates()
            for (i in 1 until coordinates.size){
                val startPoint = coordinates[i - 1]
                val endPoint = coordinates[i]
                pointList[0] = startPoint
                pointList[1] = endPoint

                val poiPoint = Point.fromLngLat(poi.lng, poi.lat)
                val nearestPoint = TurfMisc.nearestPointOnLine(poiPoint, pointList, TurfConstants.UNIT_METERS)
                val nearestPointDist = nearestPoint.getNumberProperty("dist")?.toFloat()
                val nearestPointPoint = nearestPoint.geometry() as? Point

                if (nearestPointDist != null && nearestPointPoint != null && nearestPointDist < 500){
                    val nearestPointRouteDistance = currentRouteDistance + TurfMeasurement.distance(startPoint, nearestPointPoint, TurfConstants.UNIT_METERS).toFloat()
                    nearestPointCandidates.add(NearestPoint(nearestPointPoint, nearestPointDist, nearestPointRouteDistance, poiPoint))
                }

                currentRouteDistance += TurfMeasurement.distance(startPoint, endPoint, TurfConstants.UNIT_METERS).toFloat()
            }

            // Cluster nearest point candidates together
            val nearestPoints = buildList<NearestPoint> {
                nearestPointCandidates.forEach { candidate ->
                    val existingCandidate = this@buildList.find { existingPoint ->
                        (existingPoint.distanceFromRouteStart - candidate.distanceFromRouteStart).absoluteValue < 2_000
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

            put(poi, nearestPoints)
        }
    }
}