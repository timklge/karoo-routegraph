package de.timklge.karooroutegraph.pois

import android.content.Context
import android.content.Intent
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.screens.PoiSortOption
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.throttle
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.collections.filter
import kotlin.collections.forEach

class PoiApproachAlertService(
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val locationViewModelProvider: LocationViewModelProvider,
    private val applicationContext: Context
) {
    private var alertScheduleJob: Job? = null
    private var alertShowJob: Job? = null
    private var alertChannel: Channel<PoiAlert> = Channel(10)

    init {
        startAlertJob()
    }

    data class PoiAlert(val message: String)

    fun startAlertJob() {
        val lastAlertTriggeredAt: MutableMap<Symbol.POI, Instant> = mutableMapOf()

        alertShowJob = CoroutineScope(Dispatchers.IO).launch {
            for (alert in alertChannel) {
                val intent = Intent("de.timklge.HIDE_POWERBAR").apply {
                    putExtra("duration", 11_000L)
                    putExtra("location", "top")
                }

                applicationContext.sendBroadcast(intent)

                karooSystemServiceProvider.karooSystemService.dispatch(
                    InRideAlert(
                        id = "poi_alert_${System.currentTimeMillis()}",
                        icon = R.drawable.bxmap,
                        title = applicationContext.getString(R.string.poi_approach),
                        detail = alert.message,
                        autoDismissMs = 10_000L,
                        backgroundColor = R.color.eleLightGreen,
                        textColor = R.color.black
                    )
                )

                if (karooSystemServiceProvider.karooSystemService.hardwareType == HardwareType.K2) {
                    karooSystemServiceProvider.karooSystemService.dispatch(
                        PlayBeepPattern(
                            tones = listOf(
                                PlayBeepPattern.Tone(3500, 400),
                                PlayBeepPattern.Tone(3000, 400),
                                PlayBeepPattern.Tone(3000, 400)
                            )
                        )
                    )
                } else {
                    karooSystemServiceProvider.karooSystemService.dispatch(
                        PlayBeepPattern(
                            tones = listOf(
                                PlayBeepPattern.Tone(2500, 250),
                                PlayBeepPattern.Tone(2800, 250),
                                PlayBeepPattern.Tone(2500, 250)
                            )
                        )
                    )
                }

                delay(30_000L)
            }
        }

        alertScheduleJob = CoroutineScope(Dispatchers.IO).launch {
            val lastAlertTriggeredAt: MutableMap<Symbol.POI, Instant> = mutableMapOf()

            // Combine base flows
            val baseFlow = combine(
                karooSystemServiceProvider.streamSettings(),
                viewModelProvider.viewModelFlow,
                locationViewModelProvider.viewModelFlow,
                karooSystemServiceProvider.stream<UserProfile>()
            ) { settings, viewModel, currentPosition, profile ->
                Quad(settings, viewModel, currentPosition, profile)
            }

            // Combine with profile name and settings
            val fullFlow = combine(
                baseFlow,
                karooSystemServiceProvider.streamActiveKarooProfileName(),
                karooSystemServiceProvider.streamViewSettings()
            ) { base, profileName, globalPoiSettings ->
                Triple(base, profileName, globalPoiSettings)
            }

            fullFlow
            .throttle(5_000L)
            .collect { triple ->
                val (base, profileName, globalPoiSettings) = triple
                val poiSettings = if (profileName != null) {
                    try {
                        karooSystemServiceProvider.streamProfileViewSettings(profileName).first()
                    } catch (e: Exception) {
                        globalPoiSettings
                    }
                } else {
                    globalPoiSettings
                }

                if (!poiSettings.enablePoiAlerts || poiSettings.alertPoiCategories.isEmpty() || poiSettings.alertDistanceMeters <= 0.0) {
                    return@collect
                }

                val settings = base.first
                val viewModel = base.second
                val currentPosition = base.third
                val isImperial = base.fourth.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                val currentTime = Instant.now()
                val checkForPoiApproachAlertsAfter = currentTime.minus(settings.poiApproachAlertReminderIntervalSeconds.toLong(), ChronoUnit.SECONDS)

                val distanceAlongRoute = viewModel.distanceAlongRoute

                if (distanceAlongRoute != null && viewModel.isOnRoute == true) {
                    viewModel.poiDistances?.forEach { (poi, points) ->
                        val lastAlertShownForPoi = lastAlertTriggeredAt[poi.symbol]

                        // Check if this POI's category is in the alert categories
                        val poiCategory = NearbyPoiCategory.entries.find { cat ->
                            cat.hhType.equals(poi.symbol.type, ignoreCase = true)
                        }

                        val matchesAlertCategory = poiCategory in poiSettings.alertPoiCategories

                        if (!matchesAlertCategory) {
                            return@forEach
                        }

                        val pointsAhead = points.filter { it.distanceFromRouteStart >= distanceAlongRoute }
                        val nearestPointInRange = pointsAhead.find {
                            val alongRoute = it.distanceFromRouteStart - distanceAlongRoute

                            alongRoute <= poiSettings.alertDistanceMeters && alongRoute >= 20.0 && it.distanceFromRouteStart > poiSettings.alertDistanceMeters
                        }

                        if (nearestPointInRange == null && lastAlertShownForPoi != null) {
                            // Reset alert if no point is in range
                            lastAlertTriggeredAt.remove(poi.symbol)
                            return@forEach
                        }

                        if (nearestPointInRange != null && (lastAlertShownForPoi == null || lastAlertShownForPoi.isBefore(checkForPoiApproachAlertsAfter))) {
                            val distance = distanceToPoi(poi.symbol,
                                viewModel.sampledElevationData,
                                viewModel.poiDistances, currentPosition, PoiSortOption.AHEAD_ON_ROUTE,
                                distanceAlongRoute,
                                forNearestPoint = nearestPointInRange
                            )?.formatDistance(applicationContext, isImperial, flat = true)

                            val text = applicationContext.getString(R.string.poi_in_distance, poi.symbol.name, distance ?: "")

                            alertChannel.send(PoiAlert(text))

                            lastAlertTriggeredAt[poi.symbol] = currentTime
                        }
                    }
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}