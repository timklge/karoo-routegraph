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

        displayViewModelProvider.update { displayViewModel ->
            val viewId = parameters[viewIdParameter]
            val routeDistance = viewModel.routeDistance

            Log.d(KarooRouteGraphExtension.TAG, "ChangeZoomLevelAction called for viewId: $viewId, routeDistance: $routeDistance")

            if (viewId != null && routeDistance != null){
                val zoomLevels = displayViewModel.zoomLevel.toMutableMap()
                val newZoomLevel = displayViewModel.zoomLevel.getOrDefault(viewId, ZoomLevel.COMPLETE_ROUTE).next(routeDistance.toDouble(), viewModel.isImperial)
                zoomLevels[viewId] = newZoomLevel

                displayViewModel.copy(zoomLevel = zoomLevels)
            } else {
                displayViewModel
            }
        }
    }
}