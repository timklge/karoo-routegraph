/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.timklge.karooroutegraph

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import fi.nikosavola.karooext.testing.robolectric.FakeKarooBinding
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Stands in for [KarooRouteGraphApplication] in binder tests. Koin's singletons connect to the
 * Karoo during `Application.onCreate`, before any JUnit rule runs, so the fake has to be installed
 * first.
 */
class RouteGraphTestApplication : Application() {
    override fun onCreate() {
        FakeKarooBinding.installEarly(this)
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@RouteGraphTestApplication)
            modules(appModule)
        }
    }
}

private val testSettingsKey = stringPreferencesKey("settings")

/**
 * Rewrites the persisted settings before the extension service starts, so it sees them on its first
 * read. Call it from a test body, not from `@Before` of a class that already started a host.
 */
fun Application.updateStoredSettings(transform: (RouteGraphSettings) -> RouteGraphSettings) {
    runBlocking {
        dataStore.edit { preferences ->
            // From the defaults, not the stored value: the DataStore file survives between tests
            // of a class, and one test's settings must not leak into the next.
            val current = jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(RouteGraphSettings.defaultSettings)

            preferences[testSettingsKey] = jsonWithUnknownKeys.encodeToString(transform(current))
        }
    }
}
