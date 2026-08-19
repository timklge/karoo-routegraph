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
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class ElevationToNextPOIDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "elevationtopoi") {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute

                if (currentDistanceAlongRoute == null){
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val poiDistances = state.poiDistances?.entries?.flatMap { (poi, list) ->
                    list.map { distance ->
                        poi to distance
                    }
                }

                val poisAhead = poiDistances?.filter { (_, distance) ->
                    distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                }

                val poisAheadSorted = poisAhead?.sortedBy { (_, distance) ->
                    distance.distanceFromRouteStart
                }

                val nextPoi = poisAheadSorted?.firstOrNull()

                if (nextPoi != null && state.sampledElevationData != null){
                    val nextPoiAtDistance = nextPoi.second.distanceFromRouteStart
                    val elevationToNextPoi = state.sampledElevationData.getTotalClimb(currentDistanceAlongRoute.toFloat(), nextPoiAtDistance.toFloat())

                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to elevationToNextPoi))))
                } else {
                    emitter.onNext(StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting distance to next poi view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.ELEVATION_REMAINING))
            awaitCancellation()
        }

        emitter.setCancellable {
            configJob.cancel()
        }
    }
}