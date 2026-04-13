package de.timklge.karooroutegraph.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphPoiSettings(
    val poiSortOptionForCustomPois: PoiSortOption = PoiSortOption.AHEAD_ON_ROUTE,
    val poiSortOptionForNearbyPois: PoiSortOption = PoiSortOption.AHEAD_ON_ROUTE,
    val poiCategoriesForNearbyPois: Set<NearbyPoiCategory> = emptySet(),
    val poiSortOptionForSearchedPois: PoiSortOption = PoiSortOption.AHEAD_ON_ROUTE,
    val autoAddPoiCategories: Set<NearbyPoiCategory> = emptySet(),
    val enableOfflinePoiStorage: Boolean = false,
    val autoAddPoisToMap: Boolean = false,
    val alertPoiCategories: Set<NearbyPoiCategory> = emptySet(),
    val alertDistanceMeters: Double = 500.0,
    val enablePoiAlerts: Boolean = false,
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphPoiSettings())
    }
}