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
import androidx.compose.material3.Slider
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
import de.timklge.karooroutegraph.GradientIndicatorFrequency
import de.timklge.karooroutegraph.KarooRouteGraphExtension
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
import kotlin.math.roundToInt

@Serializable
data class RouteGraphSettings(
    val showGradientIndicatorsOnMap: Boolean = false,
    val showPOILabelsOnMinimap: Boolean = true,
    val welcomeDialogAccepted: Boolean = false,
    val enableTrafficIncidentReporting: Boolean = false,
    val showNavigateButtonOnGraphs: Boolean = true,
    val hereMapsApiKey: String = "",
    val gradientIndicatorFrequency: GradientIndicatorFrequency = GradientIndicatorFrequency.HIGH,
    val poiDistanceToRouteMaxMeters: Double = 1000.0
){

    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphSettings())
    }
}

@Serializable
data class RouteGraphViewSettings(
    val poiSortOptionForCustomPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE,
    val poiSortOptionForNearbyPois: PoiSortOption = PoiSortOption.LINEAR_DISTANCE
){
    companion object {
        val defaultSettings = Json.encodeToString(RouteGraphViewSettings())
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
    var gradientIndicatorFrequency by remember { mutableStateOf(GradientIndicatorFrequency.HIGH) }
    var showPOIsOnMinimap by remember { mutableStateOf(true) }
    var showNavigateButtonOnGraphs by remember { mutableStateOf(true) }
    var hereMapsApiKey by remember { mutableStateOf("") }
    var enableTrafficIncidentReporting by remember { mutableStateOf(false) }
    var poiDistanceToRouteMaxMeters by remember { mutableStateOf(1000.0) }
    var apiTestDialogVisible by remember { mutableStateOf(false) }
    var apiTestDialogPending by remember { mutableStateOf(false) }
    var apiTestErrorMessage by remember { mutableStateOf("") }
    val hereMapsIncidentProvider = koinInject<HereMapsIncidentProvider>()

    suspend fun updateSettings(){
        Log.d(KarooRouteGraphExtension.TAG, "Updating settings")

        val newSettings = RouteGraphSettings(
            showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
            welcomeDialogAccepted = !welcomeDialogVisible,
            showPOILabelsOnMinimap = showPOIsOnMinimap,
            hereMapsApiKey = hereMapsApiKey,
            gradientIndicatorFrequency = gradientIndicatorFrequency,
            enableTrafficIncidentReporting = enableTrafficIncidentReporting,
            showNavigateButtonOnGraphs = showNavigateButtonOnGraphs,
            poiDistanceToRouteMaxMeters = poiDistanceToRouteMaxMeters
        )

        saveSettings(ctx, newSettings)
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystem).collect { settings ->
            welcomeDialogVisible = !settings.welcomeDialogAccepted
            showGradientIndicatorsOnMap = settings.showGradientIndicatorsOnMap
            gradientIndicatorFrequency = settings.gradientIndicatorFrequency
            showPOIsOnMinimap = settings.showPOILabelsOnMinimap
            hereMapsApiKey = settings.hereMapsApiKey
            enableTrafficIncidentReporting = settings.enableTrafficIncidentReporting
            showNavigateButtonOnGraphs = settings.showNavigateButtonOnGraphs
            poiDistanceToRouteMaxMeters = settings.poiDistanceToRouteMaxMeters
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

                        if (showGradientIndicatorsOnMap) {
                            val frequencies = GradientIndicatorFrequency.entries.toTypedArray()
                            val selectedIndex = frequencies.indexOf(gradientIndicatorFrequency)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Amount of indicators:")
                                Slider(
                                    value = selectedIndex.toFloat(),
                                    onValueChange = { idx ->
                                        val newIndex = idx.roundToInt().coerceIn(frequencies.indices)
                                        gradientIndicatorFrequency = frequencies[newIndex]
                                        coroutineScope.launch { updateSettings() }
                                    },
                                    valueRange = 0f..(frequencies.size - 1).toFloat(),
                                    steps = frequencies.size - 2,
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                                )
                                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    frequencies.forEach { freq ->
                                        Text(freq.name, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
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

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showNavigateButtonOnGraphs, onCheckedChange = {
                                showNavigateButtonOnGraphs = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Show navigate button on graphs")
                        }

                        // POI Distance Slider
                        val poiDistanceOptions = arrayOf(200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)
                        val selectedPoiDistanceIndex = poiDistanceOptions.indexOf(poiDistanceToRouteMaxMeters)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("POI search distance from route:")
                            Slider(
                                value = selectedPoiDistanceIndex.toFloat(),
                                onValueChange = { idx ->
                                    val newIndex = idx.roundToInt().coerceIn(poiDistanceOptions.indices)
                                    poiDistanceToRouteMaxMeters = poiDistanceOptions[newIndex]
                                    coroutineScope.launch { updateSettings() }
                                },
                                valueRange = 0f..(poiDistanceOptions.size - 1).toFloat(),
                                steps = poiDistanceOptions.size - 2,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                poiDistanceOptions.forEach { distance ->
                                    val label = if (distance >= 1000.0) {
                                        "${(distance / 1000.0).toInt()}km"
                                    } else {
                                        "${distance.toInt()}m"
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = enableTrafficIncidentReporting, onCheckedChange = {
                                enableTrafficIncidentReporting = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Enable traffic incident reporting")
                                Text(
                                    text = "Requires HERE Maps API Key",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }

                        if (enableTrafficIncidentReporting){
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