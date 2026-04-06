package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import de.timklge.karooroutegraph.screens.RouteGraphTemporaryPOIs
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

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
    val temporaryPOIsKey = stringPreferencesKey("temporaryPOIs")
    val selectedProfileNameKey = stringPreferencesKey("selectedProfileName")

    /**
     * Builds a DataStore key for POI settings scoped to a specific ride profile.
     * Profile name is sanitized to be safe as a preferences key.
     */
    fun profilePoiSettingsKey(profileName: String): String {
        val sanitizedName = profileName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "viewSettings_profile_$sanitizedName"
    }

    private var _currentProfileName: MutableStateFlow<String?> = MutableStateFlow(null)
    val currentProfileName = _currentProfileName.asStateFlow()

    fun setSelectedProfileName(name: String) {
        CoroutineScope(Dispatchers.Default).launch {
            _currentProfileName.emit(name)
            context.dataStore.edit { t ->
                t[selectedProfileNameKey] = name
            }
        }
    }

    fun getSelectedProfileName(): Flow<String?> {
        return context.dataStore.data.map { prefs ->
            prefs[selectedProfileNameKey]
        }.distinctUntilChanged()
    }

    private val profileNamesKey = stringPreferencesKey("profileNames")

    fun getAvailableProfileNames(): Flow<List<String>> {
        return context.dataStore.data.map { prefs ->
            prefs[profileNamesKey]?.let { json ->
                jsonWithUnknownKeys.decodeFromString<List<String>>(json)
            } ?: emptyList()
        }.distinctUntilChanged()
    }

    fun addProfileName(name: String) {
        CoroutineScope(Dispatchers.Default).launch {
            context.dataStore.edit { prefs ->
                val current = prefs[profileNamesKey]?.let { json ->
                    jsonWithUnknownKeys.decodeFromString<List<String>>(json)
                } ?: emptyList()
                if (name !in current) {
                    prefs[profileNamesKey] = jsonWithUnknownKeys.encodeToString(current + name)
                }
            }
        }
    }

    fun clearSelectedProfileName() {
        CoroutineScope(Dispatchers.Default).launch {
            context.dataStore.edit { prefs ->
                prefs.remove(selectedProfileNameKey)
            }
        }
    }

    private fun readSettings(settingsJson: String?): RouteGraphSettings {
        return if (settingsJson != null){
            jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(settingsJson)
        } else {
            val defaultSettings = jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(
                RouteGraphSettings.defaultSettings)

            defaultSettings.copy()
        }
    }

    private fun readViewSettings(settingsJson: String?): RouteGraphPoiSettings {
        return if (settingsJson != null){
            jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>(settingsJson)
        } else {
            val defaultSettings = jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>(
                RouteGraphPoiSettings.defaultSettings)

            defaultSettings.copy()
        }
    }

    private fun readTemporaryPOIs(settingsJson: String?): RouteGraphTemporaryPOIs {
        return if (settingsJson != null){
            jsonWithUnknownKeys.decodeFromString<RouteGraphTemporaryPOIs>(settingsJson)
        } else {
            val defaultSettings = jsonWithUnknownKeys.decodeFromString<RouteGraphTemporaryPOIs>(
                RouteGraphPoiSettings.defaultSettings)

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

    suspend fun saveViewSettings(function: (settings: RouteGraphPoiSettings) -> RouteGraphPoiSettings) {
        context.dataStore.edit { t ->
            val settings = readViewSettings(t[viewSettingsKey])
            val newSettings = function(settings)
            t[viewSettingsKey] = jsonWithUnknownKeys.encodeToString(newSettings)
        }
    }

    suspend fun saveTemporaryPOIs(function: (settings: RouteGraphTemporaryPOIs) -> RouteGraphTemporaryPOIs) {
        context.dataStore.edit { t ->
            val settings = readTemporaryPOIs(t[temporaryPOIsKey])
            val newSettings = function(settings)
            t[temporaryPOIsKey] = jsonWithUnknownKeys.encodeToString(newSettings)
        }
    }

    /**
     * Save POI settings for a specific ride profile.
     */
    suspend fun saveProfileViewSettings(profileName: String, function: (settings: RouteGraphPoiSettings) -> RouteGraphPoiSettings) {
        val key = stringPreferencesKey(profilePoiSettingsKey(profileName))
        context.dataStore.edit { t ->
            val settings = readViewSettings(t[key])
            val newSettings = function(settings)
            t[key] = jsonWithUnknownKeys.encodeToString(newSettings)
        }
    }

    /**
     * Stream POI settings for a specific ride profile.
     */
    fun streamProfileViewSettings(profileName: String): Flow<RouteGraphPoiSettings> {
        val key = stringPreferencesKey(profilePoiSettingsKey(profileName))
        return context.dataStore.data.map { settingsJson ->
            try {
                readViewSettings(settingsJson[key])
            } catch(e: Throwable){
                Log.e(TAG, "Failed to read profile preferences for $profileName", e)
                jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>(RouteGraphPoiSettings.defaultSettings)
            }
        }.distinctUntilChanged()
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

    fun streamViewSettings(): Flow<RouteGraphPoiSettings> {
        return context.dataStore.data.map { settingsJson ->
            try {
                readViewSettings(settingsJson[viewSettingsKey])
            } catch(e: Throwable){
                Log.e(TAG, "Failed to read preferences", e)
                jsonWithUnknownKeys.decodeFromString<RouteGraphPoiSettings>(RouteGraphPoiSettings.defaultSettings)
            }
        }.distinctUntilChanged()
    }

    fun streamTemporaryPOIs(): Flow<RouteGraphTemporaryPOIs> {
        return context.dataStore.data.map { settingsJson ->
            try {
                readTemporaryPOIs(settingsJson[temporaryPOIsKey])
            } catch(e: Throwable){
                Log.e(TAG, "Failed to read preferences", e)
                jsonWithUnknownKeys.decodeFromString<RouteGraphTemporaryPOIs>(RouteGraphTemporaryPOIs.defaultSettings)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamRadarSwimLaneIsVisible(): Flow<Boolean> {
        return streamDataFlow(DataType.Type.RADAR).map { state ->
            (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.RADAR_TARGET_1_RANGE) != null
        }
            .distinctUntilChanged()
            .scan(Pair<Boolean?, Boolean>(null, false)) { acc, current ->
                Pair(acc.second, current)
            }
            .flatMapLatest { (previous, current) ->
                when {
                    // First emission or transition from false to true: emit immediately
                    previous == null || (!previous && current) -> flowOf(current)
                    // Transition from true to false: delay by 3 seconds
                    previous && !current -> callbackFlow {
                        kotlinx.coroutines.delay(3000)
                        trySendBlocking(current)
                        awaitClose { }
                    }
                    // No change: emit immediately
                    else -> flowOf(current)
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


