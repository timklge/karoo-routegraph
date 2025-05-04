package de.timklge.karooroutegraph.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.saveSettings
import de.timklge.karooroutegraph.streamSettings
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RouteGraphSettings(
    val showGradientIndicatorsOnMap: Boolean = false,
    val showPOILabelsOnMinimap: Boolean = true,
    val welcomeDialogAccepted: Boolean = false,
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onFinish: () -> Unit) {
    var karooConnected by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val karooSystem = remember { KarooSystemService(ctx) }
    var welcomeDialogVisible by remember { mutableStateOf(false) }
    var showGradientIndicatorsOnMap by remember { mutableStateOf(false) }
    var showPOIsOnMinimap by remember { mutableStateOf(true) }

    suspend fun updateSettings(){
        Log.d(KarooRouteGraphExtension.TAG, "Updating settings")

        val newSettings = RouteGraphSettings(
            showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
            welcomeDialogAccepted = !welcomeDialogVisible,
            showPOILabelsOnMinimap = showPOIsOnMinimap
        )

        saveSettings(ctx, newSettings)
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystem).collect { settings ->
            welcomeDialogVisible = !settings.welcomeDialogAccepted
            showGradientIndicatorsOnMap = settings.showGradientIndicatorsOnMap
            showPOIsOnMinimap = settings.showPOILabelsOnMinimap
        }
    }

    LaunchedEffect(Unit) {
        karooSystem.connect { connected ->
            karooConnected = connected
        }
    }

    var showWarnings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1000L)
        showWarnings = true
    }

    DisposableEffect(Unit) {
        onDispose {
            karooSystem.disconnect()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text("RouteGraph")}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (showWarnings){
                        if (!karooConnected){
                            Text(modifier = Modifier.padding(5.dp), text = "Could not read device status. Is your Karoo updated?")
                        }
                    }

                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)){
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showGradientIndicatorsOnMap, onCheckedChange = {
                                showGradientIndicatorsOnMap = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Show gradient indicators on map")
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showPOIsOnMinimap, onCheckedChange = {
                                showPOIsOnMinimap = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Show POI labels on minimap")
                        }

                        Spacer(modifier = Modifier.padding(30.dp))
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.back),
                    contentDescription = "Back",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 10.dp)
                        .size(54.dp)
                        .clickable {
                            onFinish()
                        }
                )
            }
        }
    )

    if (welcomeDialogVisible){
        AlertDialog(onDismissRequest = { },
            confirmButton = { Button(onClick = {
                coroutineScope.launch {
                    saveSettings(ctx, RouteGraphSettings(
                        showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
                        welcomeDialogAccepted = true
                    ))
                }
            }) { Text("OK") } },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Welcome to karoo-routegraph!")

                    Spacer(Modifier.padding(10.dp))

                    Text("You can add a vertical or horizontal route elevation profile and other fields to your data pages.")

                    Spacer(Modifier.padding(10.dp))

                    Text("Please note that currently, this app will download elevation profiles from a public API hosted by german FOSSGIS e. V.")
                }
            }
        )
    }
}