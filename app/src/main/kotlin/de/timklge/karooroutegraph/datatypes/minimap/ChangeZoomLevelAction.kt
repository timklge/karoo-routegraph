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

package de.timklge.karooroutegraph.datatypes.minimap

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.ZoomLevel
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ChangeZoomLevelAction : ActionCallback, KoinComponent {
    private val displayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()
    private val karooSystemServiceProvider: KarooSystemServiceProvider by inject()
    private val viewModelProvider: RouteGraphViewModelProvider by inject()

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val viewModel = viewModelProvider.viewModelFlow.first()
        val settings = karooSystemServiceProvider.streamSettings().first()
        val viewIdParameer = parameters[ActionParameters.Key<String>("view_id")]

        displayViewModelProvider.update { displayViewModel ->
            val routeDistance = viewModel.routeDistance

            val newZoomLevel = if(routeDistance != null){
                displayViewModel.zoomLevel.next(viewModel, settings)
            } else {
                ZoomLevel.CompleteRoute
            }

            Log.d(KarooRouteGraphExtension.Companion.TAG, "Updated zoom level: $newZoomLevel for $viewIdParameer")

            displayViewModel.copy(zoomLevel = newZoomLevel)
        }
    }
}

