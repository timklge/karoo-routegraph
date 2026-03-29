package de.timklge.karooroutegraph.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.SurfaceConditionViewModel
import de.timklge.karooroutegraph.SurfaceConditionViewModelProvider
import de.timklge.karooroutegraph.streamSettings
import de.timklge.karooroutegraph.streamUserProfile
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import kotlin.collections.forEach
import kotlin.collections.getOrNull
import kotlin.collections.indices
import kotlin.collections.plus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElevationProfileScreen(
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showNavigateButtonOnGraphs by remember { mutableStateOf(true) }
    var shiftForRadarSwimLane by remember { mutableStateOf(true) }
    var indicateSurfaceConditionsOnGraph by remember { mutableStateOf(true) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var elevationProfileZoomLevels by remember { mutableStateOf(listOf(2, 20, 50, 100)) }
    var onlyHighlightClimbsAtZoomLevel by remember { mutableStateOf<Int?>(null) }
    var showAddZoomLevelDialog by remember { mutableStateOf(false) }
    var newZoomLevelText by remember { mutableStateOf("") }
    var zoomLevelError by remember { mutableStateOf("") }
    var showEtaOnVerticalRouteGraph by remember { mutableStateOf(true) }
    var showRemainingElevationOnVerticalRouteGraph by remember { mutableStateOf(true) }
    var showRemainingDistanceOnVerticalRouteGraph by remember { mutableStateOf(true) }
    val surfaceConditionViewModelProvider = koinInject<SurfaceConditionViewModelProvider>()
    val surfaceConditionViewModel by surfaceConditionViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(SurfaceConditionViewModel())
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()

    fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    val userProfile by karooSystemServiceProvider.karooSystemService.streamUserProfile().collectAsStateWithLifecycle(null)

    suspend fun updateSettings(){
        karooSystemServiceProvider.saveSettings {
            it.copy(
                showNavigateButtonOnGraphs = showNavigateButtonOnGraphs,
                shiftForRadarSwimLane = shiftForRadarSwimLane,
                elevationProfileZoomLevels = elevationProfileZoomLevels,
                onlyHighlightClimbsAtZoomLevel = onlyHighlightClimbsAtZoomLevel,
                indicateSurfaceConditionsOnGraph = indicateSurfaceConditionsOnGraph,
                showEtaOnVerticalRouteGraph = showEtaOnVerticalRouteGraph,
                showRemainingElevationOnVerticalRouteGraph = showRemainingElevationOnVerticalRouteGraph,
                showRemainingDistanceOnVerticalRouteGraph = showRemainingDistanceOnVerticalRouteGraph
            )
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasStoragePermission = permissions.values.all { it }
        if (!hasStoragePermission) {
            indicateSurfaceConditionsOnGraph = false
            coroutineScope.launch {
                updateSettings()
            }
        }
    }

    LaunchedEffect(Unit) {
        hasStoragePermission = checkStoragePermission()
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystemServiceProvider.karooSystemService).collect { settings ->
            showNavigateButtonOnGraphs = settings.showNavigateButtonOnGraphs
            shiftForRadarSwimLane = settings.shiftForRadarSwimLane
            indicateSurfaceConditionsOnGraph = settings.indicateSurfaceConditionsOnGraph
            elevationProfileZoomLevels = settings.elevationProfileZoomLevels
            onlyHighlightClimbsAtZoomLevel = settings.onlyHighlightClimbsAtZoomLevel
            indicateSurfaceConditionsOnGraph = settings.indicateSurfaceConditionsOnGraph
            showEtaOnVerticalRouteGraph = settings.showEtaOnVerticalRouteGraph
            showRemainingElevationOnVerticalRouteGraph = settings.showRemainingElevationOnVerticalRouteGraph
            showRemainingDistanceOnVerticalRouteGraph = settings.showRemainingDistanceOnVerticalRouteGraph
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runBlocking {
                updateSettings()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.elevation_profile))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 10.dp, top = 0.dp, bottom = 10.dp, end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showNavigateButtonOnGraphs, onCheckedChange = {
                            showNavigateButtonOnGraphs = it
                            coroutineScope.launch { updateSettings() }
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = indicateSurfaceConditionsOnGraph, onCheckedChange = { checked ->
                            indicateSurfaceConditionsOnGraph = checked
                            if (checked && !hasStoragePermission) {
                                val permissions = arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                storagePermissionLauncher.launch(permissions)
                            }
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.indicate_surface_conditions))
                    }

                    if (indicateSurfaceConditionsOnGraph && !hasStoragePermission) {
                        Text(
                            text = stringResource(R.string.storage_permission_required),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilledTonalButton(
                            onClick = {
                                val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                storagePermissionLauncher.launch(permissions)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }

                    if (indicateSurfaceConditionsOnGraph && hasStoragePermission) {
                        val s = buildString {
                            append(stringResource(R.string.map_files_found, surfaceConditionViewModel.knownFiles))
                            if (surfaceConditionViewModel.osmTiles > 0) {
                                append(" ")
                                append(stringResource(R.string.tiles_on_route,
                                    surfaceConditionViewModel.osmTiles,
                                    surfaceConditionViewModel.tilesWithoutMapfile,
                                    surfaceConditionViewModel.samples,
                                    surfaceConditionViewModel.gravelSamples))
                            }
                        }
                        Text(s)
                    }

                    val zoomLevelUnit = if (userProfile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) {
                        ZoomUnit.MILES
                    } else {
                        ZoomUnit.KILOMETERS
                    }

                    val sortedZoomLevels = elevationProfileZoomLevels.sorted()

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
                                Text(text = "$zoomLevel ${stringResource(zoomLevelUnit.stringResource)}")
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete_zoom_level),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            elevationProfileZoomLevels = elevationProfileZoomLevels.filter { it != zoomLevel }
                                            coroutineScope.launch { updateSettings() }
                                        },
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = {
                            showAddZoomLevelDialog = true
                            newZoomLevelText = ""
                            zoomLevelError = ""
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_zoom_level), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_zoom_level))
                    }

                    val climbHighlightOptions = sortedZoomLevels + listOf(null)
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
                                val label = if (option == null) stringResource(R.string.never) else "$option${stringResource(zoomLevelUnit.stringResource)}"
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showRemainingDistanceOnVerticalRouteGraph, onCheckedChange = {
                            showRemainingDistanceOnVerticalRouteGraph = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_remaining_distance_on_vertical_route_graph))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showEtaOnVerticalRouteGraph, onCheckedChange = {
                            showEtaOnVerticalRouteGraph = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_eta_on_vertical_route_graph))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showRemainingElevationOnVerticalRouteGraph, onCheckedChange = {
                            showRemainingElevationOnVerticalRouteGraph = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_remaining_elevation_on_vertical_route_graph))
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

    if (showAddZoomLevelDialog) {
        Dialog(onDismissRequest = {
            showAddZoomLevelDialog = false
            newZoomLevelText = ""
            zoomLevelError = ""
        }) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val zoomLevelUnit = if (userProfile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) ZoomUnit.MILES else ZoomUnit.KILOMETERS
                    val invalidZoomLevelString = stringResource(R.string.invalid_zoom_level)
                    val zoomLevelExistsString = stringResource(R.string.zoom_level_exists)

                    OutlinedTextField(
                        value = newZoomLevelText,
                        onValueChange = {
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
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                    )

                    if (zoomLevelError.isNotEmpty()) {
                        Text(text = zoomLevelError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val zoomLevel = newZoomLevelText.toIntOrNull()
                                when {
                                    zoomLevel == null -> zoomLevelError = invalidZoomLevelString
                                    zoomLevel < 1 || zoomLevel > 999 -> zoomLevelError = invalidZoomLevelString
                                    elevationProfileZoomLevels.contains(zoomLevel) -> zoomLevelError = zoomLevelExistsString
                                    else -> {
                                        elevationProfileZoomLevels = (elevationProfileZoomLevels + zoomLevel).sorted()
                                        newZoomLevelText = ""
                                        zoomLevelError = ""
                                        showAddZoomLevelDialog = false
                                        coroutineScope.launch { updateSettings() }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.add)) }

                        FilledTonalButton(
                            onClick = {
                                showAddZoomLevelDialog = false
                                newZoomLevelText = ""
                                zoomLevelError = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}