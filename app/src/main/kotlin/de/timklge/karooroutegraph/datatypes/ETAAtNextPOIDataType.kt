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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
        data class StreamState(val state: RouteGraphViewModel, val riderWeight: Float, val averageHourPower: Double?, val averageSpeedPerHour: Double?, val surfaceConditions: List<SurfaceConditionSegment>?)

        val job = CoroutineScope(Dispatchers.Default).launch {
            val averagePowerFlow = karooSystemProvider.streamDataFlow(DataType.Type.SMOOTHED_1HR_AVERAGE_POWER).map { (it as? io.hammerhead.karooext.models.StreamState.Streaming)?.dataPoint?.singleValue }
            val surfaceConditionFlow = surfaceConditionRetrievalService.flow
            val averageEstimatedPowerFlow = karooSystemProvider.stream<UserProfile>().flatMapLatest { profile ->
                val totalWeight = profile.weight + 10.0
                streamEstimatedPowerPerHour(totalWeight, karooSystemProvider).map { it as Double? }.onStart { emit(null) }
            }

            combine(viewModelProvider.viewModelFlow, karooSystemProvider.stream<UserProfile>(), averagePowerFlow, averageEstimatedPowerFlow, surfaceConditionFlow) { viewModel, userProfile, averagePower, averageEstimatedPower, surfaceConditions ->
                StreamState(viewModel, userProfile.weight, averagePower, averageEstimatedPower, surfaceConditions)
            }.throttle(20_000L).collect { (state, riderWeight, averagePower, averageEstimatedPower, surfaceConditions) ->
                val currentDistanceAlongRoute = state.distanceAlongRoute?.toDouble()
                val totalWeight = riderWeight + 10.0f
                val routeDistance = state.routeDistance?.toDouble()

                if (currentDistanceAlongRoute == null || routeDistance == null){
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

                val estimatedTravelTime = travelTimeEstimationService.estimateTravelTime(
                    routeElevationData = state.sampledElevationData,
                    startDistance = currentDistanceAlongRoute,
                    endDistance = targetDistanceFromRouteStart,
                    totalWeight = totalWeight.toDouble(),
                    lastHourAvgPower = averageEstimatedPower ?: averagePower,
                    surfaceConditions = surfaceConditions ?: emptyList()
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