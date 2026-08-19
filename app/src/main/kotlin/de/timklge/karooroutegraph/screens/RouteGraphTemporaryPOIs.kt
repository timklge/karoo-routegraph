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

import io.hammerhead.karooext.models.Symbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphTemporaryPOIs(
    val poisByOsmId: Map<Long, Symbol.POI> = emptyMap(),
    val poiIdOpeningHours: Map<String, String> = emptyMap()
) {
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphTemporaryPOIs())
    }
}