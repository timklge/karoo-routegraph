package de.timklge.karooroutegraph

import de.timklge.karooroutegraph.screens.RouteGraphSettings
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class PoiApproachAlertService(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val viewModelProvider: RouteGraphViewModelProvider
) {
    private var alertJob: Job? = null

    fun startAlertJob() {
        val lastAlertTriggeredAt: MutableMap<Symbol.POI, Instant> = mutableMapOf()

        alertJob = CoroutineScope(Dispatchers.IO).launch {
            data class StreamData(
                val settings: RouteGraphSettings,
                val viewModel: RouteGraphViewModel
            )

            combine(karooSystemServiceProvider.streamSettings(), viewModelProvider.viewModelFlow) { settings, viewModel ->
                StreamData(settings, viewModel)
            }.collect { streamData ->
                val settings = streamData.settings
                val viewModel = streamData.viewModel

                val currentTime = Instant.now()
                val checkForPoiApproachAlertsAfter = currentTime.minus(5, ChronoUnit.MINUTES)

                val distanceAlongRoute = viewModel.distanceAlongRoute ?: 0.0f

                viewModel.poiDistances?.forEach { (poi, points) ->
                    val lastAlertShownForPoi = lastAlertTriggeredAt[poi.symbol]
                    val pointsAhead = points.filter { it.distanceFromRouteStart >= distanceAlongRoute }
                    val nearestPointInRange = pointsAhead.find {
                        val alongRoute = it.distanceFromRouteStart - distanceAlongRoute

                        alongRoute <= settings.poiApproachAlertAtDistance
                    }

                    if (nearestPointInRange == null && lastAlertShownForPoi != null) {
                        // Reset alert if no point is in range
                        lastAlertTriggeredAt.remove(poi.symbol)
                        return@forEach
                    }

                    if (nearestPointInRange != null && (lastAlertShownForPoi == null || lastAlertShownForPoi.isBefore(checkForPoiApproachAlertsAfter))) {
                        lastAlertTriggeredAt[poi.symbol] = currentTime
                    }
                }
            }
        }
    }
}