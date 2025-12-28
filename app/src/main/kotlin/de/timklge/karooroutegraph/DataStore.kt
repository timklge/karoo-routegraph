package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.timklge.karooroutegraph.pois.DownloadedPbf
import de.timklge.karooroutegraph.pois.PbfDownloadStatus
import de.timklge.karooroutegraph.screens.RouteGraphSettings
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }

val settingsKey = stringPreferencesKey("settings")

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings", corruptionHandler = ReplaceFileCorruptionHandler {
    Log.w(KarooRouteGraphExtension.TAG, "Error reading settings, using default values")
    emptyPreferences()
})

val Context.pbfDownloadStore: DataStore<Preferences> by preferencesDataStore(name = "pbf_downloads", corruptionHandler = ReplaceFileCorruptionHandler {
    Log.w(KarooRouteGraphExtension.TAG, "Error reading PBF download settings, using default values")
    emptyPreferences()
})

val pbfDownloadStoreKey = stringPreferencesKey("pbfDownloads")

fun streamPbfDownloadStore(context: Context): Flow<List<DownloadedPbf>> {
    return context.pbfDownloadStore.data
        .map { preferences ->
            preferences[pbfDownloadStoreKey]?.let { value ->
                jsonWithUnknownKeys.decodeFromString<List<DownloadedPbf>>(value)
            } ?: emptyList()
        }
}

suspend fun updatePbfDownloadStore(context: Context, f: (List<DownloadedPbf>) -> List<DownloadedPbf>) {
    context.pbfDownloadStore.edit { t ->
        val currentPbfs = t[pbfDownloadStoreKey]?.let { value ->
            jsonWithUnknownKeys.decodeFromString<List<DownloadedPbf>>(value)
        } ?: emptyList()
        val downloadedPbfs = f(currentPbfs)
        t[pbfDownloadStoreKey] = jsonWithUnknownKeys.encodeToString(downloadedPbfs)
    }
}

suspend fun updatePbfDownloadStoreStatus(context: Context, countryKey: String, status: PbfDownloadStatus, progress: Float = 0f) {
    updatePbfDownloadStore(context) { currentPbfs ->
        currentPbfs.map {
            if (it.countryKey == countryKey){
                it.copy(downloadState = status, progress = progress)
            } else {
                it
            }
        }
    }
}

suspend fun saveSettings(context: Context, settings: RouteGraphSettings) {
    context.dataStore.edit { t ->
        t[settingsKey] = Json.encodeToString(settings)
    }
}

fun Context.streamSettings(karooSystemService: KarooSystemService): Flow<RouteGraphSettings> {
    return dataStore.data.map { settingsJson ->
        try {
            if (settingsJson.contains(settingsKey)){
                jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(settingsJson[settingsKey]!!)
            } else {
                jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(
                    RouteGraphSettings.defaultSettings)
            }
        } catch(e: Throwable){
            Log.e(KarooRouteGraphExtension.TAG, "Failed to read preferences", e)
            jsonWithUnknownKeys.decodeFromString<RouteGraphSettings>(RouteGraphSettings.defaultSettings)
        }
    }.distinctUntilChanged()
}

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> {
    return callbackFlow {
        val listenerId = addConsumer { userProfile: UserProfile ->
            trySendBlocking(userProfile)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

fun KarooSystemService.streamActiveRidePage(): Flow<ActiveRidePage> {
    return callbackFlow {
        val listenerId = addConsumer { activeRidePage: ActiveRidePage ->
            trySendBlocking(activeRidePage)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

fun distanceToString(distanceMeters: Float, isImperial: Boolean, onlyMinorUnit: Boolean): String {
    return if (isImperial){
        val distanceMiles = distanceMeters / 1609.344f
        if (distanceMiles > 1 && !onlyMinorUnit){
            "${distanceMiles.toInt()} mi"
        } else {
            "${(distanceMiles * 5280).toInt()} ft"
        }
    } else {
        val distanceKm = distanceMeters / 1000f
        if (distanceKm > 1 && !onlyMinorUnit){
            "${distanceKm.toInt()} km"
        } else {
            "${(distanceKm * 1000).toInt()} m"
        }
    }
}

fun distanceIsZero(distanceMeters: Float, userProfile: UserProfile): Boolean {
    return if (userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL){
        val distanceMiles = distanceMeters / 1609.344f
        (distanceMiles * 5280).toInt() == 0
    } else {
        val distanceKm = distanceMeters / 1000f
        (distanceKm * 1000).toInt() == 0
    }
}