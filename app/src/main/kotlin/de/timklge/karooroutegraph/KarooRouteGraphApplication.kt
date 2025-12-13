package de.timklge.karooroutegraph

import android.app.Application
import de.timklge.karooroutegraph.datatypes.minimap.MinimapViewModelProvider
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::KarooSystemServiceProvider)
    singleOf(::RouteGraphViewModelProvider)
    singleOf(::RouteGraphDisplayViewModelProvider)
    singleOf(::OverpassPOIProvider)
    singleOf(::NominatimProvider)
    singleOf(::MinimapViewModelProvider)
    singleOf(::TileDownloadService)
    singleOf(::HereMapsIncidentProvider)
    singleOf(::LocationViewModelProvider)
    singleOf(::PoiApproachAlertService)
    singleOf(::SurfaceConditionRetrievalService)
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