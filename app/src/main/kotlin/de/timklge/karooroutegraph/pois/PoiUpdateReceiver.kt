package de.timklge.karooroutegraph.pois

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.timklge.karooroutegraph.MainActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PoiUpdateReceiver : BroadcastReceiver(), KoinComponent {
    private val nearbyPOIPbfDownloadService: NearbyPOIPbfDownloadService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "de.timklge.karooroutegraph.POI_UPDATE_INTENT") {
            nearbyPOIPbfDownloadService.handleUpdateIntent()
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mainIntent)
        }
    }
}
