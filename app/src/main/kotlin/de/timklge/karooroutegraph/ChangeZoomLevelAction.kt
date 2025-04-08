package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Suppress("unused")
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
        val displayViewModel = displayViewModelProvider.viewModelFlow.first()
        val routeDistance = viewModel.routeDistance

        val newZoomLevel = if(routeDistance != null){
            displayViewModel.zoomLevel.next(routeDistance.toDouble(), viewModel.isImperial)
        } else {
            ZoomLevel.COMPLETE_ROUTE
        }

        displayViewModelProvider.update {
            Log.d(KarooRouteGraphExtension.TAG, "Updated zoom level: $newZoomLevel")

            displayViewModel.copy(zoomLevel = newZoomLevel)
        }
    }
}

