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
            val routeDistance = viewModel.routeDistance

            val newZoomLevel = if(routeDistance != null){
                displayViewModel.zoomLevel.next(routeDistance.toDouble(), viewModel.isImperial)
            } else {
                ZoomLevel.COMPLETE_ROUTE
            }

            Log.d(KarooRouteGraphExtension.Companion.TAG, "Updated zoom level: $newZoomLevel")

            displayViewModel.copy(zoomLevel = newZoomLevel)
        }
    }
}