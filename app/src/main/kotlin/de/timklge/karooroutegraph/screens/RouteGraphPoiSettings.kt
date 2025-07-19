package de.timklge.karooroutegraph.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphPoiSettings(
    val poiSortOptionForCustomPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val poiSortOptionForNearbyPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val poiSortOptionForSearchedPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphPoiSettings())
    }
}