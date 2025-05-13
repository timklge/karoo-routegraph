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

            if (width != null && height != null) {
                val requiredZoomLevel = getRequiredZoomLevel(viewModel, displayViewModel.minimapWidth, displayViewModel.minimapHeight)
                val newZoomLevel = displayViewModel.minimapZoomLevel.next(requiredZoomLevel)
                Log.d(KarooRouteGraphExtension.Companion.TAG, "Updated zoom level: $newZoomLevel")

                displayViewModel.copy(minimapZoomLevel = newZoomLevel)
            } else {
                displayViewModel
            }
        }
    }
}