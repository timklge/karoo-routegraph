package de.timklge.karooroutegraph.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphPoiSettings(
    val poiSortOptionForCustomPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val poiSortOptionForNearbyPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val poiCategoriesForNearbyPois: Set<NearbyPoiCategory> = emptySet(),
    val poiSortOptionForSearchedPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val autoAddPoiCategories: Set<NearbyPoiCategory> = emptySet(),
    val autoAddToElevationProfileAndMinimap: Boolean = false,
    val enableOfflinePoiStorage: Boolean = false,
    val autoAddPoisToMap: Boolean = false,
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphPoiSettings())
    }
}