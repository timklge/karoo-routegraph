package de.timklge.karooroutegraph

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::KarooSystemServiceProvider)
    singleOf(::RouteGraphViewModelProvider)
    singleOf(::RouteGraphDisplayViewModelProvider)
    singleOf(::ValhallaAPIElevationProvider)
    singleOf(::MinimapViewModelProvider)
    singleOf(::TileDownloadService)
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