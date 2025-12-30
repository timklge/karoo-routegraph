package de.timklge.karooroutegraph

import android.app.Application
import de.timklge.karooroutegraph.datatypes.minimap.MinimapViewModelProvider
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.NominatimProvider
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.OverpassPOIProvider
import de.timklge.karooroutegraph.pois.PoiApproachAlertService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::KarooSystemServiceProvider) { createdAtStart() }
    singleOf(::RouteGraphViewModelProvider) { createdAtStart() }
    singleOf(::RouteGraphDisplayViewModelProvider) { createdAtStart() }
    singleOf(::OverpassPOIProvider) { createdAtStart() }
    singleOf(::NominatimProvider) { createdAtStart() }
    singleOf(::MinimapViewModelProvider) { createdAtStart() }
    singleOf(::TileDownloadService) { createdAtStart() }
    singleOf(::HereMapsIncidentProvider) { createdAtStart() }
    singleOf(::LocationViewModelProvider) { createdAtStart() }
    singleOf(::PoiApproachAlertService) { createdAtStart() }
    singleOf(::NearbyPOIPbfDownloadService) { createdAtStart() }
    singleOf(::SurfaceConditionRetrievalService) { createdAtStart() }
    singleOf(::RouteGraphUpdateManager) { createdAtStart() }
    singleOf(::OfflineNearbyPOIProvider) { createdAtStart() }
    singleOf(::AutoAddedPOIsViewModelProvider) { createdAtStart() }
    singleOf(::SurfaceConditionViewModelProvider) { createdAtStart() }
}

class KarooRouteGraphApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@KarooRouteGraphApplication)
            modules(appModule)
        }
    }
}