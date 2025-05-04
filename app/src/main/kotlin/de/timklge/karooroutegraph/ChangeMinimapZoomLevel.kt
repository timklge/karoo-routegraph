package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ChangeMinimapZoomLevel : ActionCallback, KoinComponent {
    private val viewModelProvider: RouteGraphViewModelProvider by inject()
    private val routeGraphDisplayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()

    private fun getRequiredZoomLevel(viewModel: RouteGraphViewModel, width: Int, height: Int) : Float {
        return if (viewModel.rejoin != null){
                viewModel.rejoin.getOSMZoomLevelToFit(width, height)
            } else if (viewModel.routeToDestination != null){
                viewModel.routeToDestination.getOSMZoomLevelToFit(width, height)
            } else if (viewModel.knownRoute != null){
                viewModel.knownRoute.getOSMZoomLevelToFit(width, height)
            } else {
                16.0f
            }
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val viewModel = viewModelProvider.viewModelFlow.first()

        routeGraphDisplayViewModelProvider.update { displayViewModel ->
            val width = displayViewModel.minimapWidth
            val height = displayViewModel.minimapHeight

            if (width != null && height != null) {
                val requiredZoomLevel = getRequiredZoomLevel(viewModel, displayViewModel.minimapWidth, displayViewModel.minimapHeight)
                val newZoomLevel = displayViewModel.minimapZoomLevel.next(requiredZoomLevel)
                Log.d(KarooRouteGraphExtension.TAG, "Updated zoom level: $newZoomLevel")

                displayViewModel.copy(minimapZoomLevel = newZoomLevel)
            } else {
                displayViewModel
            }
        }
    }
}