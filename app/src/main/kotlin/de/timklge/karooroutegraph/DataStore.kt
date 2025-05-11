package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

fun distanceToString(distanceMeters: Float, userProfile: UserProfile, onlyMinorUnit: Boolean): String {
    return if (userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL){
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