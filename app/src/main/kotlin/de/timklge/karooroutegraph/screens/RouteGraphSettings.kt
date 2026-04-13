package de.timklge.karooroutegraph.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphSettings(
    val welcomeDialogAccepted: Boolean = false,
    val showNavigateButtonOnGraphs: Boolean = true,
    val poiDistanceToRouteMaxMeters: Double = 500.0,
    val poiApproachAlertAtDistance: Double? = 500.0,
    val poiApproachAlertReminderIntervalSeconds: Int = 300,
    val elevationProfileZoomLevels: List<Int> = listOf(2, 10, 25, 50),
    val onlyHighlightClimbsAtZoomLevel: Int? = 1, // null means "Never"
    val shiftForRadarSwimLane: Boolean = true,
    val indicateSurfaceConditionsOnGraph: Boolean = false,
    val showEtaOnVerticalRouteGraph: Boolean = true,
    val showRemainingElevationOnVerticalRouteGraph: Boolean = true,
    val showRemainingDistanceOnVerticalRouteGraph: Boolean = true,
){

    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}