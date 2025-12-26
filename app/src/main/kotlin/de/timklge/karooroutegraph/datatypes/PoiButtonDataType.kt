package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.pois.POIActivity
import de.timklge.karooroutegraph.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PoiButtonDataType(
    private val karooSystem: KarooSystemService,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "poiButton") {
    private val glance = GlanceRemoteViews()

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(KarooRouteGraphExtension.TAG, "Starting poi button view with $emitter")

        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            val result = glance.compose(context, DpSize.Unspecified) {
                val modifier = GlanceModifier.fillMaxSize()

                Box(
                    modifier = if (config.preview) modifier else modifier.clickable(
                        actionStartActivity<POIActivity>()
                    )
                ) {
                    Image(
                        modifier = GlanceModifier.fillMaxSize().padding(10.dp),
                        provider = if (isNightMode()) ImageProvider(R.drawable.bxs_map_pin_night) else ImageProvider(R.drawable.bxs_map_pin),
                        contentDescription = "POIs",
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            emitter.updateView(result.remoteViews)
        }

        emitter.setCancellable {
            Log.d(KarooRouteGraphExtension.TAG, "Stopping button view with $emitter")
            configJob.cancel()
            viewJob.cancel()
        }
    }
}