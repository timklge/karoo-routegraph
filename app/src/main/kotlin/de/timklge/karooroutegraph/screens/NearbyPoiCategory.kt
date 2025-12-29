package de.timklge.karooroutegraph.screens

import de.timklge.karooroutegraph.R
import io.hammerhead.karooext.models.Symbol

enum class NearbyPoiCategory(val labelRes: Int, val osmTag: List<Pair<String, String>>, val hhType: String) {
    DRINKING_WATER(R.string.category_water, listOf("amenity" to "drinking_water"), Symbol.POI.Types.WATER),
    GAS_STATIONS(R.string.category_gas_station, listOf("amenity" to "fuel"), Symbol.POI.Types.GAS_STATION),
    SUPERMARKETS(R.string.category_supermarket, listOf("shop" to "supermarket"), Symbol.POI.Types.CONVENIENCE_STORE),
    RESTAURANTS(R.string.category_restaurant, listOf("amenity" to "restaurant"), Symbol.POI.Types.FOOD),
    CAFES(R.string.category_cafe, listOf("amenity" to "cafe"), Symbol.POI.Types.COFFEE),
    ICE_CREAM(R.string.category_ice_cream, listOf("amenity" to "ice_cream"), Symbol.POI.Types.FOOD),
    BAKERY(R.string.category_bakery, listOf("shop" to "bakery"), Symbol.POI.Types.FOOD),
    TOILETS(R.string.category_toilets, listOf("amenity" to "toilets"), Symbol.POI.Types.RESTROOM),
    SHOWERS(R.string.category_showers, listOf("amenity" to "shower"), Symbol.POI.Types.RESTROOM),
    ATMS(R.string.category_atms, listOf("amenity" to "atm"), Symbol.POI.Types.ATM),
    SHELTER(R.string.category_shelter, listOf("amenity" to "shelter"), Symbol.POI.Types.REST_STOP),
    CAMPING_SITE(R.string.category_camping_site, listOf("tourism" to "camp_site"), Symbol.POI.Types.CAMPING),
    HOTEL(R.string.category_hotel, listOf("tourism" to "hotel"), Symbol.POI.Types.LODGING),
    TRAIN_STATION(R.string.category_train_station, listOf("railway" to "station"), Symbol.POI.Types.TRANSIT_CENTER),
    WASTE_BASKET(R.string.category_waste_basket, listOf("amenity" to "waste_basket"), Symbol.POI.Types.GENERIC),
    BENCH(R.string.category_bench, listOf("amenity" to "bench"), Symbol.POI.Types.GENERIC),
    BIKE_SHOP(R.string.category_bike_shop, listOf("shop" to "bicycle"), Symbol.POI.Types.BIKE_SHOP),
    TOURISM_ATTRACTION(R.string.category_attraction, listOf("tourism" to "attraction"), Symbol.POI.Types.VIEWPOINT),
    VIEWPOINT(R.string.category_viewpoint, listOf("tourism" to "viewpoint"), Symbol.POI.Types.VIEWPOINT),
    PHARMACY(R.string.category_pharmacy, listOf("amenity" to "pharmacy"), Symbol.POI.Types.FIRST_AID),
    HOSPITAL(R.string.category_hospital, listOf("amenity" to "hospital"), Symbol.POI.Types.HOSPITAL),
    VILLAGE(R.string.category_village, listOf("place" to "village"), Symbol.POI.Types.GENERIC),
    TOWN(R.string.category_town, listOf("place" to "town", "place" to "city"), Symbol.POI.Types.GENERIC);

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