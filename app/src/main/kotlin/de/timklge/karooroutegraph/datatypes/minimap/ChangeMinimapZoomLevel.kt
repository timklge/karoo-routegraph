package de.timklge.karooroutegraph.datatypes.minimap

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.RouteGraphDisplayViewModelProvider
import de.timklge.karooroutegraph.RouteGraphViewModel
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ChangeMinimapZoomLevel : ActionCallback, KoinComponent {
    private val viewModelProvider: RouteGraphViewModelProvider by inject()
    private val routeGraphDisplayViewModelProvider: RouteGraphDisplayViewModelProvider by inject()

    private fun getRequiredZoomLevel(viewModel: RouteGraphViewModel, width: Int, height: Int): Float? {
        return if (viewModel.rejoin != null){
                viewModel.rejoin.getOSMZoomLevelToFit(width, height)
            } else viewModel.knownRoute?.getOSMZoomLevelToFit(width, height)
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
            val viewId = parameters[viewIdParameter]

            Log.d(KarooRouteGraphExtension.TAG, "ChangeMinimapZoomLevel called for viewId: $viewId, width: $width, height: $height")

            if (width != null && height != null && viewId != null) {
                val requiredZoomLevel = getRequiredZoomLevel(viewModel, displayViewModel.minimapWidth, displayViewModel.minimapHeight)
                val zoomLevels = displayViewModel.minimapZoomLevel.toMutableMap()
                val defaultZoomLevel = if (viewModel.knownRoute != null) MinimapZoomLevel.COMPLETE_ROUTE else MinimapZoomLevel.FAR
                val newZoomLevel = zoomLevels.getOrDefault(viewId, defaultZoomLevel).next(requiredZoomLevel)
                zoomLevels[viewId] = newZoomLevel
                Log.d(KarooRouteGraphExtension.TAG, "Updated zoom level: $newZoomLevel")

                displayViewModel.copy(minimapZoomLevel = zoomLevels)
            } else {
                displayViewModel
            }
        }
    }
}