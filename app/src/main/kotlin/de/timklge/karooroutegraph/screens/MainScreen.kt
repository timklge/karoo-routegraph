package de.timklge.karooroutegraph.screens

import android.content.Intent
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.GradientIndicatorFrequency
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.POIActivity
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.saveSettings
import de.timklge.karooroutegraph.streamSettings
import de.timklge.karooroutegraph.streamUserProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import kotlin.math.roundToInt

enum class ZoomUnit(val stringResource: Int) {
    KILOMETERS(R.string.kilometers),
    MILES(R.string.miles)
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    HorizontalDivider()
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
    var shiftForRadarSwimLane by remember { mutableStateOf(true) }
    var hereMapsApiKey by remember { mutableStateOf("") }
    var enableTrafficIncidentReporting by remember { mutableStateOf(false) }
    var poiDistanceToRouteMaxMeters by remember { mutableDoubleStateOf(1000.0) }
    var poiApproachAlertAtDistance by remember { mutableDoubleStateOf(500.0) }
    var apiTestDialogVisible by remember { mutableStateOf(false) }
    var apiTestDialogPending by remember { mutableStateOf(false) }
    var apiTestErrorMessage by remember { mutableStateOf("") }
    var elevationProfileZoomLevels by remember { mutableStateOf(listOf(2, 20, 50, 100)) }
    var onlyHighlightClimbsAtZoomLevel by remember { mutableStateOf<Int?>(null) }
    var showAddZoomLevelDialog by remember { mutableStateOf(false) }
    var newZoomLevelText by remember { mutableStateOf("") }
    var zoomLevelError by remember { mutableStateOf("") }
    val hereMapsIncidentProvider = koinInject<HereMapsIncidentProvider>()

    val userProfile by karooSystem.streamUserProfile().collectAsStateWithLifecycle(null)

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
            shiftForRadarSwimLane = shiftForRadarSwimLane,
            poiDistanceToRouteMaxMeters = poiDistanceToRouteMaxMeters,
            poiApproachAlertAtDistance = poiApproachAlertAtDistance,
            elevationProfileZoomLevels = elevationProfileZoomLevels,
            onlyHighlightClimbsAtZoomLevel = onlyHighlightClimbsAtZoomLevel
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
            shiftForRadarSwimLane = settings.shiftForRadarSwimLane
            poiDistanceToRouteMaxMeters = settings.poiDistanceToRouteMaxMeters
            poiApproachAlertAtDistance = settings.poiApproachAlertAtDistance ?: 500.0
            elevationProfileZoomLevels = settings.elevationProfileZoomLevels
            onlyHighlightClimbsAtZoomLevel = settings.onlyHighlightClimbsAtZoomLevel
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
                            Text(modifier = Modifier.padding(5.dp), text = stringResource(R.string.device_status_warning))
                        }
                    }

                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)){
                        SectionHeader(stringResource(R.string.elevation_profile))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showNavigateButtonOnGraphs, onCheckedChange = {
                                showNavigateButtonOnGraphs = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.show_navigate_button))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = shiftForRadarSwimLane, onCheckedChange = {
                                shiftForRadarSwimLane = it
                                coroutineScope.launch { updateSettings() }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.shift_for_radar_swim_lane))
                        }

                        // Only Highlight Climbs at Zoom Level Slider
                        val zoomLevelUnit = if (userProfile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) {
                            ZoomUnit.MILES
                        } else {
                            ZoomUnit.KILOMETERS
                        }

                        val sortedZoomLevels = elevationProfileZoomLevels.sorted()

                        // Elevation Profile Zoom Levels
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.elevation_profile_zoom_levels))

                            sortedZoomLevels.forEach { zoomLevel ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$zoomLevel ${stringResource(zoomLevelUnit.stringResource)}",
                                    )

                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.delete_zoom_level),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                elevationProfileZoomLevels = elevationProfileZoomLevels.filter { it != zoomLevel }
                                                coroutineScope.launch {
                                                    updateSettings()
                                                }
                                            },
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        FilledTonalButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            onClick = {
                                showAddZoomLevelDialog = true
                                newZoomLevelText = ""
                                zoomLevelError = ""
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_zoom_level),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_zoom_level))
                        }

                        val climbHighlightOptions = sortedZoomLevels + listOf(null) // null represents "Never"
                        val selectedClimbHighlightIndex = (onlyHighlightClimbsAtZoomLevel?.coerceIn(climbHighlightOptions.indices) ?: climbHighlightOptions.getOrNull(1)) ?: 0

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.only_highlight_climbs_at_zoom_level))
                            Slider(
                                value = selectedClimbHighlightIndex.toFloat(),
                                onValueChange = { idx ->
                                    val newIndex = idx.roundToInt().coerceIn(climbHighlightOptions.indices)
                                    onlyHighlightClimbsAtZoomLevel = newIndex
                                    coroutineScope.launch { updateSettings() }
                                },
                                valueRange = 0f..(climbHighlightOptions.size - 1).toFloat(),
                                steps = climbHighlightOptions.size - 2,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                climbHighlightOptions.forEach { option ->
                                    val label = if (option == null) {
                                        stringResource(R.string.never)
                                    } else {
                                        "$option${stringResource(zoomLevelUnit.stringResource)}"
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        SectionHeader(stringResource(R.string.gradient_chevrons))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showGradientIndicatorsOnMap, onCheckedChange = {
                                showGradientIndicatorsOnMap = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.show_gradient_indicators))
                        }

                        if (showGradientIndicatorsOnMap) {
                            val frequencies = GradientIndicatorFrequency.entries.toTypedArray()
                            val selectedIndex = frequencies.indexOf(gradientIndicatorFrequency)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.amount_of_indicators))
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
                                        Text(stringResource(freq.labelResourceId), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        SectionHeader(stringResource(R.string.points_of_interest_poi))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = showPOIsOnMinimap, onCheckedChange = {
                                showPOIsOnMinimap = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.show_poi_labels))
                        }

                        // POI Management Button
                        FilledTonalButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            onClick = {
                                val intent = Intent(ctx, POIActivity::class.java)
                                ctx.startActivity(intent)
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.bxmap),
                                contentDescription = stringResource(R.string.manage_pois_description),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.manage_pois))
                        }

                        // Max POI Distance from Route Slider
                        val poiDistanceOptions = arrayOf(200.0, 500.0, 1_000.0, 2_000.0, 5_000.0)
                        val selectedPoiDistanceIndex = poiDistanceOptions.indexOf(poiDistanceToRouteMaxMeters)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.max_poi_distance))
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

                        // POI Approach Alert Distance Slider
                        val poiApproachAlertOptions = arrayOf(0.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0)
                        val selectedApproachAlertIndex = poiApproachAlertOptions.indexOf(poiApproachAlertAtDistance)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.poi_approach_alert_distance))
                            Slider(
                                value = selectedApproachAlertIndex.toFloat(),
                                onValueChange = { idx ->
                                    val newIndex = idx.roundToInt().coerceIn(poiApproachAlertOptions.indices)
                                    poiApproachAlertAtDistance = poiApproachAlertOptions[newIndex]
                                    coroutineScope.launch { updateSettings() }
                                },
                                valueRange = 0f..(poiApproachAlertOptions.size - 1).toFloat(),
                                steps = poiApproachAlertOptions.size - 2,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                poiApproachAlertOptions.forEach { distance ->
                                    val label = if (distance == 0.0){
                                        stringResource(R.string.distance_off)
                                    } else if (distance >= 1000.0) {
                                        "${(distance / 1000.0).toInt()}km"
                                    } else {
                                        "${distance.toInt()}m"
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        SectionHeader(stringResource(R.string.traffic_incidents))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = enableTrafficIncidentReporting, onCheckedChange = {
                                enableTrafficIncidentReporting = it
                                coroutineScope.launch {
                                    updateSettings()
                                }
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

                        if (enableTrafficIncidentReporting){
                            OutlinedTextField(
                                value = hereMapsApiKey,
                                onValueChange = {
                                    hereMapsApiKey = it
                                },
                                label = { Text(stringResource(R.string.here_maps_api_key)) },
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
                                                Text(stringResource(R.string.ok))
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

                        // New Zoom Level Dialog
                        if (showAddZoomLevelDialog) {
                            Dialog(onDismissRequest = {
                                showAddZoomLevelDialog = false
                                newZoomLevelText = ""
                                zoomLevelError = ""
                            }) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        val zoomLevelUnit = if (userProfile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) {
                                            ZoomUnit.MILES
                                        } else {
                                            ZoomUnit.KILOMETERS
                                        }

                                        OutlinedTextField(
                                            value = newZoomLevelText,
                                            onValueChange = {
                                                // Filter input to only allow digits
                                                if (it.all { char -> char.isDigit() }) {
                                                    newZoomLevelText = it
                                                    zoomLevelError = ""
                                                }
                                            },
                                            label = { Text(stringResource(R.string.enter_zoom_level)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            isError = zoomLevelError.isNotEmpty(),
                                            suffix = { Text(stringResource(zoomLevelUnit.stringResource)) },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions.Default.copy(
                                                keyboardType = KeyboardType.Number
                                            )
                                        )

                                        if (zoomLevelError.isNotEmpty()) {
                                            Text(
                                                text = zoomLevelError,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilledTonalButton(
                                                onClick = {
                                                    val zoomLevel = newZoomLevelText.toIntOrNull()
                                                    when {
                                                        zoomLevel == null -> {
                                                            zoomLevelError = ctx.getString(R.string.invalid_zoom_level)
                                                        }
                                                        zoomLevel < 1 || zoomLevel > 999 -> {
                                                            zoomLevelError = ctx.getString(R.string.invalid_zoom_level)
                                                        }
                                                        elevationProfileZoomLevels.contains(zoomLevel) -> {
                                                            zoomLevelError = ctx.getString(R.string.zoom_level_exists)
                                                        }
                                                        else -> {
                                                            elevationProfileZoomLevels = (elevationProfileZoomLevels + zoomLevel).sorted()
                                                            newZoomLevelText = ""
                                                            zoomLevelError = ""
                                                            showAddZoomLevelDialog = false
                                                            coroutineScope.launch {
                                                                updateSettings()
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(stringResource(R.string.add))
                                            }

                                            FilledTonalButton(
                                                onClick = {
                                                    showAddZoomLevelDialog = false
                                                    newZoomLevelText = ""
                                                    zoomLevelError = ""
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(stringResource(R.string.cancel))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.padding(30.dp))
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.back),
                    contentDescription = stringResource(R.string.back),
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
                        welcomeDialogAccepted = true
                    ))
                }
            }) { Text(stringResource(R.string.ok)) } },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.welcome_title))

                    Spacer(Modifier.padding(10.dp))

                    Text(stringResource(R.string.welcome_message_1))

                    Spacer(Modifier.padding(10.dp))

                    Text(stringResource(R.string.welcome_message_2))
                }
            }
        )
    }
}