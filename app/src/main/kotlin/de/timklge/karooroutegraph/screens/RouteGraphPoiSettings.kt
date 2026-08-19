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
    val recentlyUsedCategories: List<NearbyPoiCategory> = emptyList(),
    val autoAddToElevationProfileAndMinimap: Boolean = false,
    val enableOfflinePoiStorage: Boolean = false,
    val autoAddPoisToMap: Boolean = false,
    val lastPoiTab: Int = 1,
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphPoiSettings())
    }
}