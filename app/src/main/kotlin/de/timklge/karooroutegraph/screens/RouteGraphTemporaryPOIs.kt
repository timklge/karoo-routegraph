package de.timklge.karooroutegraph.screens

import io.hammerhead.karooext.models.Symbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphTemporaryPOIs(
    val poisByOsmId: Map<Long, Symbol.POI> = emptyMap(),
) {
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphTemporaryPOIs())
    }
}