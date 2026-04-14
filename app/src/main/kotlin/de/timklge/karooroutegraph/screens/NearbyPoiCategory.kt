package de.timklge.karooroutegraph.screens

import android.util.Log
import de.timklge.karooroutegraph.R
import io.hammerhead.karooext.models.Symbol

enum class NearbyPoiCategory(val labelRes: Int, val osmTag: List<Pair<String, String>>, val hhType: String) {
    DRINKING_WATER(R.string.category_water, listOf("amenity" to "drinking_water"), Symbol.POI.Types.WATER),
    RESTAURANTS(R.string.category_restaurant, listOf("amenity" to "restaurant"), Symbol.POI.Types.FOOD),
    CAFES(R.string.category_cafe, listOf("amenity" to "cafe"), Symbol.POI.Types.COFFEE),
    ICE_CREAM(R.string.category_ice_cream, listOf("amenity" to "ice_cream"), Symbol.POI.Types.FOOD),
    FAST_FOOD(R.string.category_fast_food, listOf("amenity" to "fast_food"), Symbol.POI.Types.FOOD),
    SUPERMARKETS(R.string.category_supermarket, listOf("shop" to "supermarket"), Symbol.POI.Types.CONVENIENCE_STORE),
    CONVENIENCE(R.string.category_convenience, listOf("shop" to "convenience"), Symbol.POI.Types.CONVENIENCE_STORE),
    BAKERY(R.string.category_bakery, listOf("shop" to "bakery"), Symbol.POI.Types.FOOD),
    CAMPING_SITE(R.string.category_camping_site, listOf("tourism" to "camp_site"), Symbol.POI.Types.CAMPING),
    TOURISM_ATTRACTION(R.string.category_attraction, listOf("tourism" to "attraction"), Symbol.POI.Types.VIEWPOINT),
    VIEWPOINT(R.string.category_viewpoint, listOf("tourism" to "viewpoint"), Symbol.POI.Types.VIEWPOINT),
    BEACH(R.string.category_beach, listOf("natural" to "beach"), Symbol.POI.Types.SWIMMING),
    SWIMMING_AREA(R.string.category_swimming_area, listOf("leisure" to "swimming_area"), Symbol.POI.Types.SWIMMING);

    companion object {
        fun fromTag(tags: Map<String, String>): NearbyPoiCategory? {
            return NearbyPoiCategory.entries.find { category ->
                category.osmTag.any { (key, value) ->
                    tags[key] == value
                }
            }
        }
    }
}