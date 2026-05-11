package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceConditionSegment
import de.timklge.karooroutegraph.TravelTimeEstimationService
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.throttle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit

class ETAAtNextPOIDataType(
    private val karooSystemProvider: KarooSystemServiceProvider,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val travelTimeEstimationService: TravelTimeEstimationService,
    private val surfaceConditionRetrievalService: SurfaceConditionRetrievalService
) : DataTypeImpl("karoo-routegraph", "etapoi") {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        data class StreamState(val state: RouteGraphViewModel, val userProfile: UserProfile, val powerPerHour: Double?, val surfaceConditions: List<SurfaceConditionSegment>?)

        val job = CoroutineScope(Dispatchers.Default).launch {
            val averagePowerFlow = streamPowerPerHour( karooSystemProvider)
            val surfaceConditionFlow = surfaceConditionRetrievalService.flow

            combine(viewModelProvider.viewModelFlow, karooSystemProvider.stream<UserProfile>(), averagePowerFlow, surfaceConditionFlow) { viewModel, userProfile, averagePower, surfaceConditions ->
                StreamState(viewModel, userProfile, averagePower, surfaceConditions)
            }.throttle(20_000L).collect { (state, userProfile, averagePower, surfaceConditions) ->
                val currentDistanceAlongRoute = state.distanceAlongRoute.toDouble()
                val totalWeight = userProfile.weight + 10.0f
                val routeDistance = state.routeDistance?.toDouble()

                if (routeDistance == null){
                    emitter.onNext(io.hammerhead.karooext.models.StreamState.NotAvailable)
                    return@collect
                }

                val poiDistances = state.poiDistances?.entries?.flatMap { (poi, list) ->
                    list.map { distance ->
                        poi to distance
                    }
                }

                val poisAhead = poiDistances?.filter { (poi, distance) ->
                    poi.type != PoiType.INCIDENT && distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                }

                val poisAheadSorted = poisAhead?.sortedBy { (_, distance) ->
                    distance.distanceFromRouteStart
                }

                val nextPoi = poisAheadSorted?.firstOrNull()
                val targetDistanceFromRouteStart = nextPoi?.second?.distanceFromRouteStart?.toDouble() ?: routeDistance
                val targetFinalSegmentLength = nextPoi?.second?.distanceFromPointOnRoute?.toDouble()

                val estimatedTravelTime = travelTimeEstimationService.estimateTravelTime(
                    routeElevationData = state.sampledElevationData,
                    startDistance = currentDistanceAlongRoute,
                    endDistance = targetDistanceFromRouteStart,
                    totalWeight = totalWeight.toDouble(),
                    profileFtp = userProfile.ftp.toDouble(),
                    lastHourAvgPower = averagePower,
                    surfaceConditions = surfaceConditions ?: emptyList(),
                    finalSegmentLength = targetFinalSegmentLength
                )
                val estimatedArrivalTimeInUnixMs = System.currentTimeMillis() + estimatedTravelTime.toLong(DurationUnit.MILLISECONDS)

                Log.i(TAG, "Estimated arrival time at next POI (${nextPoi?.first} in ${nextPoi?.second?.distanceFromRouteStart?.minus(
                    currentDistanceAlongRoute
                )} m): $estimatedArrivalTimeInUnixMs (in ${estimatedTravelTime.toLong(DurationUnit.SECONDS)} seconds)")

                emitter.onNext(io.hammerhead.karooext.models.StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to estimatedArrivalTimeInUnixMs.toDouble()))))
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting ETA at next poi view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.TIME_OF_ARRIVAL))
            awaitCancellation()
        }

        emitter.setCancellable {
            configJob.cancel()
        }
    }
}