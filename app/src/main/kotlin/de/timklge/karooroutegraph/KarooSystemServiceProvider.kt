package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphViewSettings
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class KarooSystemServiceProvider(private val context: Context) {
    val karooSystemService: KarooSystemService = KarooSystemService(context)

    private val _connectionState = MutableStateFlow(false)
    val connectionState = _connectionState.asStateFlow()

    init {
        karooSystemService.connect { connected ->
            if (connected) {
                Log.d(TAG, "Connected to Karoo system")
            }

            CoroutineScope(Dispatchers.Default).launch {
                _connectionState.emit(connected)
            }
        }
    }

    val settingsKey = stringPreferencesKey("settings")
    val viewSettingsKey = stringPreferencesKey("viewSettings")

    fun readSettings(settingsJson: String?): RouteGraphSettings {
        return if (settingsJson != null){
            jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(settingsJson)
        } else {
            val defaultSettings = jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(
                RouteGraphSettings.defaultSettings)

            defaultSettings.copy()
        }
    }

    fun readViewSettings(settingsJson: String?): RouteGraphViewSettings {
        return if (settingsJson != null){
            jsonWithUnknownKeys.decodeFromString<RouteGraphViewSettings>(settingsJson)
        } else {
            val defaultSettings = jsonWithUnknownKeys.decodeFromString<RouteGraphViewSettings>(
                RouteGraphViewSettings.defaultSettings)

            defaultSettings.copy()
        }
    }

    suspend fun saveSettings(function: (settings: RouteGraphSettings) -> RouteGraphSettings) {
        context.dataStore.edit { t ->
            val settings = readSettings(t[settingsKey])
            val newSettings = function(settings)
            t[settingsKey] = jsonWithUnknownKeys.encodeToString(newSettings)
        }
    }

    suspend fun saveViewSettings(function: (settings: RouteGraphViewSettings) -> RouteGraphViewSettings) {
        context.dataStore.edit { t ->
            val settings = readViewSettings(t[viewSettingsKey])
            val newSettings = function(settings)
            t[viewSettingsKey] = jsonWithUnknownKeys.encodeToString(newSettings)
        }
    }

    fun streamSettings(): Flow<RouteGraphSettings> {
        return context.dataStore.data.map { settingsJson ->
            try {
                readSettings(settingsJson[settingsKey])
            } catch(e: Throwable){
                Log.e(TAG, "Failed to read preferences", e)
                jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(RouteGraphSettings.defaultSettings)
            }
        }.distinctUntilChanged()
    }

    fun streamViewSettings(): Flow<RouteGraphViewSettings> {
        return context.dataStore.data.map { settingsJson ->
            try {
                readViewSettings(settingsJson[viewSettingsKey])
            } catch(e: Throwable){
                Log.e(TAG, "Failed to read preferences", e)
                jsonWithUnknownKeys.decodeFromString<RouteGraphViewSettings>(RouteGraphViewSettings.defaultSettings)
            }
        }.distinctUntilChanged()
    }

    /* fun showError(header: String, message: String, e: Throwable? = null) {
        karooSystemService.dispatch(InRideAlert(id = "error-${System.currentTimeMillis()}", icon = R.drawable.spotify, title = header, detail = errorMessageString, autoDismissMs = 10_000L, backgroundColor = R.color.hRed, textColor = R.color.black))
    } */


    fun streamDataFlow(dataTypeId: String): Flow<StreamState> {
        return callbackFlow {
            val listenerId = karooSystemService.addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
                trySendBlocking(event.state)
            }
            awaitClose {
                karooSystemService.removeConsumer(listenerId)
            }
        }
    }

    inline fun<reified T : KarooEvent> stream(): Flow<T> {
        return callbackFlow {
            val listenerId = karooSystemService.addConsumer { event: T ->
                trySendBlocking(event)
            }
            awaitClose {
                karooSystemService.removeConsumer(listenerId)
            }
        }
    }
}

