package de.timklge.karooroutegraph.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.streamSettings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficIncidentsScreen(
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hereMapsIncidentProvider = koinInject<HereMapsIncidentProvider>()
    var hereMapsApiKey by remember { mutableStateOf("") }
    var enableTrafficIncidentReporting by remember { mutableStateOf(false) }
    var apiTestDialogVisible by remember { mutableStateOf(false) }
    var apiTestDialogPending by remember { mutableStateOf(false) }
    var apiTestErrorMessage by remember { mutableStateOf("") }
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()

    suspend fun updateSettings(){
        karooSystemServiceProvider.saveSettings { settings ->
            settings.copy(
                enableTrafficIncidentReporting = enableTrafficIncidentReporting,
                hereMapsApiKey = hereMapsApiKey
            )
        }
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystemServiceProvider.karooSystemService).collect { settings ->
            hereMapsApiKey = settings.hereMapsApiKey
            enableTrafficIncidentReporting = settings.enableTrafficIncidentReporting
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.traffic_incidents))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enableTrafficIncidentReporting, onCheckedChange = {
                            enableTrafficIncidentReporting = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(stringResource(R.string.enable_traffic_incident_reporting))
                            Text(
                                text = stringResource(R.string.requires_here_maps_api),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }

                    if (enableTrafficIncidentReporting) {
                        OutlinedTextField(
                            value = hereMapsApiKey,
                            onValueChange = { hereMapsApiKey = it },
                            label = { Text(stringResource(R.string.here_maps_api_key)) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true
                        )

                        if (apiTestDialogVisible) {
                            Dialog(onDismissRequest = { apiTestDialogVisible = false }) {
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(10.dp)) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(text = apiTestErrorMessage)
                                        if (apiTestDialogPending) {
                                            LinearProgressIndicator()
                                        }
                                        Button(onClick = { apiTestDialogVisible = false }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.ok))
                                        }
                                    }
                                }
                            }
                        }

                        FilledTonalButton(modifier = Modifier.fillMaxWidth().height(40.dp), onClick = {
                            apiTestDialogVisible = true
                            apiTestDialogPending = true
                            apiTestErrorMessage = ctx.getString(R.string.testing_api_key)

                            coroutineScope.launch {
                                try {
                                    val response = hereMapsIncidentProvider.requestIncidents(hereMapsApiKey, Point.fromLngLat(13.399, 52.5186), 2000.0)
                                    apiTestDialogPending = false
                                    apiTestErrorMessage = ctx.getString(R.string.api_key_valid, response.results?.size ?: 0, response.sourceUpdated ?: "")
                                    Log.d(KarooRouteGraphExtension.TAG, apiTestErrorMessage)
                                } catch (e: Exception) {
                                    Log.e(KarooRouteGraphExtension.TAG, "Error testing API key: ${e.message}")
                                    apiTestDialogPending = false
                                    apiTestErrorMessage = ctx.getString(R.string.api_key_error)
                                }
                            }
                        }) {
                            Text(stringResource(R.string.test_api_key))
                        }
                    }

                    Spacer(modifier = Modifier.padding(30.dp))
                }

                FixedBackButton(
                    modifier = Modifier.align(Alignment.BottomStart),
                    onBack = onBack
                )
            }
        }
    )
}