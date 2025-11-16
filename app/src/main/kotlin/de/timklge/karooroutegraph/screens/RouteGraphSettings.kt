package de.timklge.karooroutegraph.screens

import de.timklge.karooroutegraph.GradientIndicatorFrequency
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphSettings(
    val showGradientIndicatorsOnMap: Boolean = false,
    val showPOILabelsOnMinimap: Boolean = true,
    val welcomeDialogAccepted: Boolean = false,
    val enableTrafficIncidentReporting: Boolean = false,
    val showNavigateButtonOnGraphs: Boolean = true,
    val hereMapsApiKey: String = "",
    val gradientIndicatorFrequency: GradientIndicatorFrequency = GradientIndicatorFrequency.HIGH,
    val poiDistanceToRouteMaxMeters: Double = 1000.0,
    val poiApproachAlertAtDistance: Double? = 500.0,
    val poiApproachAlertReminderIntervalSeconds: Int = 300,
    val elevationProfileZoomLevels: List<Int> = listOf(2, 10, 25, 50),
    val onlyHighlightClimbsAtZoomLevel: Int? = 1, // null means "Never"
    val shiftForRadarSwimLane: Boolean = true,
    val indicateSurfaceConditionsOnGraph: Boolean = true
){

    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}