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
    val poiApproachAlertAtDistance: Double = 500.0
){

    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}