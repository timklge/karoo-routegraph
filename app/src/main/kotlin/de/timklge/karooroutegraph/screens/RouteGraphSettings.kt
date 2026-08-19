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

import de.timklge.karooroutegraph.GradientIndicatorFrequency
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphSettings(
    val showGradientIndicatorsOnMap: Boolean = false,
    val showPOILabelsOnMinimap: Boolean = false,
    val welcomeDialogAccepted: Boolean = false,
    val showNavigateButtonOnGraphs: Boolean = true,
    val gradientIndicatorFrequency: GradientIndicatorFrequency = GradientIndicatorFrequency.HIGH,
    val poiDistanceToRouteMaxMeters: Double = 500.0,
    val poiApproachAlertAtDistance: Double? = 500.0,
    val poiApproachAlertReminderIntervalSeconds: Int = 300,
    val elevationProfileZoomLevels: List<Int> = listOf(2, 10, 25, 50),
    val onlyHighlightClimbsAtZoomLevel: Int? = 1, // null means "Never"
    val shiftForRadarSwimLane: Boolean = true,
    val indicateSurfaceConditionsOnGraph: Boolean = false,
    val minimapNightMode: Boolean = true,
    val showEtaOnVerticalRouteGraph: Boolean = true,
    val showRemainingElevationOnVerticalRouteGraph: Boolean = true,
    val showRemainingDistanceOnVerticalRouteGraph: Boolean = true,
){

    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}