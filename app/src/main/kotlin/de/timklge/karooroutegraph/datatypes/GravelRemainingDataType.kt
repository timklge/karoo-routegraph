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

package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.graphics.Color
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceConditionSegment
import de.timklge.karooroutegraph.throttle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Streams the remaining route distance in meters that is covered by offroad
 * surface condition segments (gravel, dirt, loose surfaces).
 */
class GravelRemainingDataType(
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val surfaceConditionRetrievalService: SurfaceConditionRetrievalService
) : DataTypeImpl("karoo-routegraph", "gravelremaining") {

    data class StreamData(
        val distanceAlongRoute: Double?,
        val routeDistance: Double?,
        val surfaceConditions: List<SurfaceConditionSegment>?
    )

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            combine(
                viewModelProvider.viewModelFlow,
                surfaceConditionRetrievalService.flow
            ) { viewModel, surfaceConditions ->
                StreamData(
                    distanceAlongRoute = viewModel.distanceAlongRoute.toDouble(),
                    routeDistance = viewModel.routeDistance?.toDouble(),
                    surfaceConditions = surfaceConditions
                )
            }.throttle(1_000L).collect { streamData ->
                val remainingDistance = calculateRemainingOffroadDistance(streamData)

                if (remainingDistance == null) {
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                Log.d(TAG, "Remaining offroad distance at ${streamData.distanceAlongRoute} m: $remainingDistance m")

                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to remainingDistance))
                    )
                )
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting gravel remaining view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.DISTANCE))
            awaitCancellation()
        }

        val viewJob = CoroutineScope(Dispatchers.Default).launch {
            surfaceConditionRetrievalService.hasPermissionsFlow.collect { hasPermissions ->
                if (hasPermissions) {
                    emitter.onNext(ShowCustomStreamState("", null))
                } else {
                    emitter.onNext(ShowCustomStreamState(context.getString(R.string.no_permissions), Color.RED))
                }
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }

    companion object {
        /**
         * Sums the lengths of all offroad surface condition segments ahead of the
         * current rider position. Segments overlapping the current position are
         * clipped to their remaining part. Returns `null` when no route is loaded
         * or surface conditions have not been calculated (yet), signalling
         * `NotAvailable`.
         *
         * Exposed for unit-testing.
         */
        fun calculateRemainingOffroadDistance(streamData: StreamData): Double? {
            val currentDistance = streamData.distanceAlongRoute ?: return null
            streamData.routeDistance ?: return null
            val surfaceConditions = streamData.surfaceConditions ?: return null

            return surfaceConditions.sumOf { segment ->
                val remainingStart = maxOf(segment.startMeters, currentDistance)
                val remainingEnd = segment.endMeters

                if (remainingEnd > remainingStart) remainingEnd - remainingStart else 0.0
            }
        }
    }
}
