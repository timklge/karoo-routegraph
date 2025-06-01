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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.saveSettings
import de.timklge.karooroutegraph.streamSettings
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

@Serializable
data class RouteGraphSettings(
    val showGradientIndicatorsOnMap: Boolean = false,
    val showPOILabelsOnMinimap: Boolean = true,
    val welcomeDialogAccepted: Boolean = false,
    val hereMapsApiKey: String = "",
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
    val karooSystem by remember { mutableStateOf(KarooSystemService(ctx)) }
    var welcomeDialogVisible by remember { mutableStateOf(false) }
    var showGradientIndicatorsOnMap by remember { mutableStateOf(false) }
    var showPOIsOnMinimap by remember { mutableStateOf(true) }
    var hereMapsApiKey by remember { mutableStateOf("") }
    var apiTestDialogVisible by remember { mutableStateOf(false) }
    var apiTestDialogPending by remember { mutableStateOf(false) }
    var apiTestErrorMessage by remember { mutableStateOf("") }
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val hereMapsIncidentProvider = koinInject<HereMapsIncidentProvider>()

    suspend fun updateSettings(){
        Log.d(KarooRouteGraphExtension.TAG, "Updating settings")

        val newSettings = RouteGraphSettings(
            showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
            welcomeDialogAccepted = !welcomeDialogVisible,
            showPOILabelsOnMinimap = showPOIsOnMinimap,
            hereMapsApiKey = hereMapsApiKey
        )

        saveSettings(ctx, newSettings)
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystem).collect { settings ->
            welcomeDialogVisible = !settings.welcomeDialogAccepted
            showGradientIndicatorsOnMap = settings.showGradientIndicatorsOnMap
            showPOIsOnMinimap = settings.showPOILabelsOnMinimap
            hereMapsApiKey = settings.hereMapsApiKey
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
            runBlocking {
                updateSettings()
            }

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

                        OutlinedTextField(
                            value = hereMapsApiKey,
                            onValueChange = {
                                hereMapsApiKey = it
                            },
                            label = { Text("HERE Maps API Key") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            singleLine = true
                        )

                        Text(
                            text = "If you want to use traffic incident reporting from HERE Maps, you need to provide an API key.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (apiTestDialogVisible) {
                            Dialog(onDismissRequest = { apiTestDialogVisible = false }) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(text = apiTestErrorMessage)
                                        if (apiTestDialogPending) {
                                            LinearProgressIndicator()
                                        }
                                        Button(
                                            onClick = { apiTestDialogVisible = false },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("OK")
                                        }
                                    }
                                }
                            }
                        }

                        FilledTonalButton(modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                            onClick = {
                                apiTestDialogVisible = true
                                apiTestDialogPending = true
                                apiTestErrorMessage = "Testing API key..."

                                coroutineScope.launch {
                                    try {
                                        val response = hereMapsIncidentProvider.requestIncidents(hereMapsApiKey, Point.fromLngLat(13.399, 52.5186), 2000.0)
                                        apiTestDialogPending = false
                                        apiTestErrorMessage = "API key is valid. ${response.results?.size} incidents reported in the center of Berlin (updated at ${response.sourceUpdated})"

                                        Log.d(KarooRouteGraphExtension.TAG, apiTestErrorMessage)
                                    } catch (e: Exception) {
                                        Log.e(KarooRouteGraphExtension.TAG, "Error testing API key: ${e.message}")
                                        apiTestDialogPending = false
                                        apiTestErrorMessage = "Error testing API key. Check if your key is valid."
                                    }
                                }
                            }) {
                            Text("Test API Key")
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