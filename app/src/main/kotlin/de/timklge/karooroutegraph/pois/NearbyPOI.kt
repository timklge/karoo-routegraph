package de.timklge.karooroutegraph.pois

data class NearbyPOI(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>
)