package de.timklge.karooroutegraph.pois

import com.mapbox.geojson.Point

interface NearbyPOIProvider {
    suspend fun requestNearbyPOIs(requestedTags: List<Pair<String, String>>, points: List<Point>, radius: Int = 1_000, limit: Int = 20): List<NearbyPOI>
}