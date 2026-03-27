package de.timklge.karooroutegraph.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.GradientIndicatorFrequency
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.SurfaceConditionViewModel
import de.timklge.karooroutegraph.SurfaceConditionViewModelProvider
import de.timklge.karooroutegraph.incidents.HereMapsIncidentProvider
import de.timklge.karooroutegraph.pois.DownloadedPbf
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.PbfDownloadStatus
import de.timklge.karooroutegraph.pois.PbfType
import de.timklge.karooroutegraph.pois.POIActivity
import de.timklge.karooroutegraph.saveSettings
import de.timklge.karooroutegraph.streamPbfDownloadStore
import de.timklge.karooroutegraph.streamSettings
import de.timklge.karooroutegraph.streamUserProfile
import de.timklge.karooroutegraph.updatePbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStoreStatus
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

enum class SettingsScreen {
    MENU,
    ELEVATION_PROFILE,
    GRADIENT_CHEVRONS,
    MINIMAP,
    POINTS_OF_INTEREST,
    TRAFFIC_INCIDENTS
}

data class MenuItem(
    val screen: SettingsScreen,
    val iconResId: Int,
    val labelResId: Int
)

val menuItems = listOf(
    MenuItem(SettingsScreen.ELEVATION_PROFILE, R.drawable.menu_spline, R.string.elevation_profile),
    MenuItem(SettingsScreen.GRADIENT_CHEVRONS, R.drawable.menu_chevron, R.string.gradient_chevrons),
    MenuItem(SettingsScreen.MINIMAP, R.drawable.menu_map, R.string.minimap),
    MenuItem(SettingsScreen.POINTS_OF_INTEREST, R.drawable.menu_poi, R.string.points_of_interest_poi),
    MenuItem(SettingsScreen.TRAFFIC_INCIDENTS, R.drawable.menu_barrier, R.string.traffic_incidents)
)

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

@Composable
fun FixedBackButton(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Image(
        painter = painterResource(id = R.drawable.back),
        contentDescription = stringResource(R.string.back),
        modifier = modifier
            .padding(bottom = 10.dp)
            .size(54.dp)
            .clickable { onBack() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onFinish: () -> Unit) {
    var currentScreen by remember { mutableStateOf(SettingsScreen.MENU) }
    val ctx = LocalContext.current

    BackHandler {
        if (currentScreen != SettingsScreen.MENU) {
            currentScreen = SettingsScreen.MENU
        } else {
            onFinish()
        }
    }

    when (currentScreen) {
        SettingsScreen.MENU -> {
            MainMenuScreen(
                onMenuItemClick = { screen -> currentScreen = screen },
                onFinish = onFinish
            )
        }
        SettingsScreen.ELEVATION_PROFILE -> {
            ElevationProfileScreen(
                onBack = { currentScreen = SettingsScreen.MENU },
                onFinish = onFinish
            )
        }
        SettingsScreen.GRADIENT_CHEVRONS -> {
            GradientChevronsScreen(
                onBack = { currentScreen = SettingsScreen.MENU },
                onFinish = onFinish
            )
        }
        SettingsScreen.MINIMAP -> {
            MinimapScreen(
                karooSystemServiceProvider = koinInject(),
                onBack = { currentScreen = SettingsScreen.MENU },
                onFinish = onFinish
            )
        }
        SettingsScreen.POINTS_OF_INTEREST -> {
            PointsOfInterestScreen(
                onBack = { currentScreen = SettingsScreen.MENU },
                onFinish = onFinish
            )
        }
        SettingsScreen.TRAFFIC_INCIDENTS -> {
            TrafficIncidentsScreen(
                onBack = { currentScreen = SettingsScreen.MENU },
                onFinish = onFinish
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    onMenuItemClick: (SettingsScreen) -> Unit,
    onFinish: () -> Unit
) {
    var karooConnected by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val karooSystem = koinInject<KarooSystemServiceProvider>().karooSystemService

    DisposableEffect(Unit) {
        karooSystem.connect { connected ->
            karooConnected = connected
        }

        onDispose {
            karooSystem.disconnect()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.app_name))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (!karooConnected){
                        Text(modifier = Modifier.padding(5.dp), text = stringResource(R.string.device_status_warning))
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)
                    ) {
                        items(menuItems) { item ->
                            MenuItemRow(
                                item = item,
                                onClick = { onMenuItemClick(item.screen) }
                            )
                            if (item != menuItems.last()) HorizontalDivider()
                        }
                    }
                }

                FixedBackButton(
                    modifier = Modifier.align(Alignment.BottomStart),
                    onBack = onFinish
                )
            }
        }
    )
}

@Composable
fun MenuItemRow(
    item: MenuItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = item.iconResId),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElevationProfileScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit
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
    val surfaceConditionViewModelProvider = koinInject<SurfaceConditionViewModelProvider>()
    val surfaceConditionViewModel by surfaceConditionViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(SurfaceConditionViewModel())
    var welcomeDialogVisible by remember { mutableStateOf(false) }
    val karooSystem = koinInject<KarooSystemServiceProvider>().karooSystemService

    fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    val userProfile by karooSystem.streamUserProfile().collectAsStateWithLifecycle(null)

    suspend fun updateSettings(){
        val newSettings = RouteGraphSettings(
            showGradientIndicatorsOnMap = true,
            welcomeDialogAccepted = !welcomeDialogVisible,
            showPOILabelsOnMinimap = true,
            hereMapsApiKey = "",
            gradientIndicatorFrequency = GradientIndicatorFrequency.HIGH,
            enableTrafficIncidentReporting = false,
            showNavigateButtonOnGraphs = showNavigateButtonOnGraphs,
            shiftForRadarSwimLane = shiftForRadarSwimLane,
            poiDistanceToRouteMaxMeters = 1000.0,
            poiApproachAlertAtDistance = 500.0,
            elevationProfileZoomLevels = elevationProfileZoomLevels,
            onlyHighlightClimbsAtZoomLevel = onlyHighlightClimbsAtZoomLevel,
            indicateSurfaceConditionsOnGraph = indicateSurfaceConditionsOnGraph,
            minimapNightMode = true
        )
        saveSettings(ctx, newSettings)
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
        ctx.streamSettings(karooSystem).collect { settings ->
            welcomeDialogVisible = !settings.welcomeDialogAccepted
            showNavigateButtonOnGraphs = settings.showNavigateButtonOnGraphs
            shiftForRadarSwimLane = settings.shiftForRadarSwimLane
            indicateSurfaceConditionsOnGraph = settings.indicateSurfaceConditionsOnGraph
            elevationProfileZoomLevels = settings.elevationProfileZoomLevels
            onlyHighlightClimbsAtZoomLevel = settings.onlyHighlightClimbsAtZoomLevel
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
                                    zoomLevel == null -> zoomLevelError = ctx.getString(R.string.invalid_zoom_level)
                                    zoomLevel < 1 || zoomLevel > 999 -> zoomLevelError = ctx.getString(R.string.invalid_zoom_level)
                                    elevationProfileZoomLevels.contains(zoomLevel) -> zoomLevelError = ctx.getString(R.string.zoom_level_exists)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradientChevronsScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showGradientIndicatorsOnMap by remember { mutableStateOf(false) }
    var gradientIndicatorFrequency by remember { mutableStateOf(GradientIndicatorFrequency.HIGH) }
    val karooSystem = koinInject<KarooSystemServiceProvider>().karooSystemService

    suspend fun updateSettings(){
        val currentSettings = ctx.streamSettings(karooSystem)
        saveSettings(ctx, RouteGraphSettings(
            showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
            gradientIndicatorFrequency = gradientIndicatorFrequency
        ))
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystem).collect { settings ->
            showGradientIndicatorsOnMap = settings.showGradientIndicatorsOnMap
            gradientIndicatorFrequency = settings.gradientIndicatorFrequency
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.gradient_chevrons))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showGradientIndicatorsOnMap, onCheckedChange = {
                            showGradientIndicatorsOnMap = it
                            coroutineScope.launch { updateSettings() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimapScreen(
    karooSystemServiceProvider: KarooSystemServiceProvider,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var minimapNightMode by remember { mutableStateOf(true) }
    var showPOIsOnMinimap by remember { mutableStateOf(true) }

    suspend fun updateSettings(){
        karooSystemServiceProvider.saveSettings { settings ->
            settings.copy(
                minimapNightMode = minimapNightMode,
                showPOILabelsOnMinimap = showPOIsOnMinimap
            )
        }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.streamSettings().collect { settings ->
            minimapNightMode = settings.minimapNightMode
            showPOIsOnMinimap = settings.showPOILabelsOnMinimap
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.minimap))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = minimapNightMode, onCheckedChange = {
                            minimapNightMode = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.minimap_night_mode))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showPOIsOnMinimap, onCheckedChange = {
                            showPOIsOnMinimap = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_poi_labels))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsOfInterestScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val nearbyPOIPbfDownloadService = koinInject<NearbyPOIPbfDownloadService>()
    var enableOfflinePoiStorage by remember { mutableStateOf(false) }
    var autoAddPoisToMap by remember { mutableStateOf(false) }
    var autoAddToElevationProfileAndMinimap by remember { mutableStateOf(false) }
    var autoAddPoiCategories by remember { mutableStateOf(emptySet<NearbyPoiCategory>()) }
    var showAutoAddPoiCategoriesDialog by remember { mutableStateOf(false) }
    var showDownloadPoisDialog by remember { mutableStateOf(false) }
    var poiDistanceToRouteMaxMeters by remember { mutableDoubleStateOf(1000.0) }
    var poiApproachAlertAtDistance by remember { mutableDoubleStateOf(500.0) }

    suspend fun updateSettings(){
        karooSystemServiceProvider.saveSettings { settings ->
            settings.copy(
                poiDistanceToRouteMaxMeters = poiDistanceToRouteMaxMeters,
                poiApproachAlertAtDistance = poiApproachAlertAtDistance
            )
        }
    }

    suspend fun updatePoiSettings() {
        karooSystemServiceProvider.saveViewSettings { settings ->
            settings.copy(
                enableOfflinePoiStorage = enableOfflinePoiStorage,
                autoAddPoisToMap = autoAddPoisToMap,
                autoAddPoiCategories = autoAddPoiCategories,
                autoAddToElevationProfileAndMinimap = autoAddToElevationProfileAndMinimap
            )
        }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.streamSettings().collect { settings ->
            poiDistanceToRouteMaxMeters = settings.poiDistanceToRouteMaxMeters
            poiApproachAlertAtDistance = settings.poiApproachAlertAtDistance ?: 500.0
        }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.streamViewSettings().collect { settings ->
            enableOfflinePoiStorage = settings.enableOfflinePoiStorage
            autoAddPoisToMap = settings.autoAddPoisToMap
            autoAddPoiCategories = settings.autoAddPoiCategories
            autoAddToElevationProfileAndMinimap = settings.autoAddToElevationProfileAndMinimap
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.points_of_interest_poi))}) },
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
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { ctx.startActivity(Intent(ctx, POIActivity::class.java)) }
                    ) {
                        Icon(painter = painterResource(id = R.drawable.bx_info_circle), contentDescription = stringResource(R.string.open_poi_menu), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.open_poi_menu))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enableOfflinePoiStorage, onCheckedChange = {
                            enableOfflinePoiStorage = it
                            coroutineScope.launch { updatePoiSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.enable_offline_poi_storage))
                    }

                    if (enableOfflinePoiStorage) {
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            onClick = { showDownloadPoisDialog = true }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.offline_pois), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.offline_pois))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = autoAddPoisToMap, onCheckedChange = {
                                autoAddPoisToMap = it
                                coroutineScope.launch { updatePoiSettings() }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.auto_add_pois_to_map))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = autoAddToElevationProfileAndMinimap, onCheckedChange = {
                                autoAddToElevationProfileAndMinimap = it
                                coroutineScope.launch { updatePoiSettings() }
                            })
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.auto_add_to_elevation_profile_and_minimap))
                        }

                        if (autoAddPoisToMap) {
                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                onClick = { showAutoAddPoiCategoriesDialog = true }
                            ) {
                                Icon(painter = painterResource(id = R.drawable.bx_info_circle), contentDescription = stringResource(R.string.select_categories), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.select_categories))
                            }
                        }
                    }

                    if (showAutoAddPoiCategoriesDialog) {
                        CategorySelectionDialog(
                            initialCategories = autoAddPoiCategories,
                            onDismiss = { showAutoAddPoiCategoriesDialog = false },
                            onConfirm = { newCategories ->
                                autoAddPoiCategories = newCategories
                                showAutoAddPoiCategoriesDialog = false
                                coroutineScope.launch { updatePoiSettings() }
                            }
                        )
                    }

                    if (showDownloadPoisDialog) {
                        val downloadedPbfs by streamPbfDownloadStore(ctx).collectAsStateWithLifecycle(listOf())
                        val countriesByContinent = remember {
                            nearbyPOIPbfDownloadService.countriesData.entries.groupBy { it.value.continent }.toSortedMap()
                        }
                        var expandedContinents by remember { mutableStateOf(setOf<String>()) }

                        Dialog(onDismissRequest = { showDownloadPoisDialog = false }) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp).fillMaxSize()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = stringResource(R.string.download_pois), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))

                                    LazyColumn(modifier = Modifier.weight(1f)) {
                                        countriesByContinent.forEach { (continent, countries) ->
                                            item {
                                                val isExpanded = expandedContinents.contains(continent)
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().clickable {
                                                        expandedContinents = if (isExpanded) expandedContinents - continent else expandedContinents + continent
                                                    }.padding(vertical = 12.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(text = getContinentString(continent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    Icon(
                                                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                                        contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand)
                                                    )
                                                }
                                                HorizontalDivider()
                                            }

                                            if (expandedContinents.contains(continent)) {
                                                items(countries.sortedBy { country -> country.value.name }) { entry ->
                                                    val key = entry.key
                                                    val data = entry.value
                                                    val downloadedPbf = downloadedPbfs.find { it.countryKey == key }
                                                    val status = downloadedPbf?.downloadState

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(text = getCountryString(key, data.name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

                                                        if (downloadedPbf == null) {
                                                            IconButton(onClick = {
                                                                coroutineScope.launch {
                                                                    updatePbfDownloadStore(ctx) { currentPbfs ->
                                                                        val pbfs = currentPbfs.filterNot { it.countryKey == key } + listOf(
                                                                            DownloadedPbf(countryKey = key, countryName = data.name, pbfType = PbfType.POI, downloadState = PbfDownloadStatus.PENDING, progress = 0f)
                                                                        )
                                                                        pbfs
                                                                    }
                                                                }
                                                            }) {
                                                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.download))
                                                            }
                                                        } else if (status == PbfDownloadStatus.AVAILABLE) {
                                                            IconButton(onClick = {
                                                                coroutineScope.launch {
                                                                    try {
                                                                        nearbyPOIPbfDownloadService.getPoiFile(key).delete()
                                                                    } catch(t: Throwable) {
                                                                        Log.e(KarooRouteGraphExtension.TAG, "Failed to delete PBF file for $key", t)
                                                                    }
                                                                    updatePbfDownloadStore(ctx) { currentPbfs -> currentPbfs.filterNot { pbf -> pbf.countryKey == key } }
                                                                }
                                                            }) {
                                                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
                                                            }
                                                        } else {
                                                            when (status) {
                                                                PbfDownloadStatus.PENDING -> if (downloadedPbf.progress in 0.01f..0.99f) {
                                                                    CircularProgressIndicator(modifier = Modifier.size(48.dp), progress = { downloadedPbf.progress })
                                                                } else {
                                                                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                                                }
                                                                PbfDownloadStatus.DOWNLOAD_FAILED -> Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.failed), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                                                else -> {}
                                                            }
                                                            if (status == PbfDownloadStatus.DOWNLOAD_FAILED) {
                                                                IconButton(onClick = {
                                                                    coroutineScope.launch { updatePbfDownloadStoreStatus(ctx, key, PbfDownloadStatus.PENDING) }
                                                                }) {
                                                                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.retry), tint = MaterialTheme.colorScheme.primary)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                                                }
                                            }
                                        }
                                    }

                                    Button(onClick = { showDownloadPoisDialog = false }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                        Text(stringResource(R.string.close))
                                    }
                                }
                            }
                        }
                    }

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
                                val label = if (distance >= 1000.0) "${(distance / 1000.0).toInt()}km" else "${distance.toInt()}m"
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

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
                                val label = when {
                                    distance == 0.0 -> stringResource(R.string.distance_off)
                                    distance >= 1000.0 -> "${(distance / 1000.0).toInt()}km"
                                    else -> "${distance.toInt()}m"
                                }
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficIncidentsScreen(
    onBack: () -> Unit,
    onFinish: () -> Unit
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
        saveSettings(ctx, RouteGraphSettings(
            enableTrafficIncidentReporting = enableTrafficIncidentReporting,
            hereMapsApiKey = hereMapsApiKey
        ))
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

@Composable
fun getContinentString(continent: String): String {
    val context = LocalContext.current
    val resourceName = "continent_" + continent.lowercase().replace(" ", "_")
    val resId = remember(continent) { context.resources.getIdentifier(resourceName, "string", context.packageName) }
    return if (resId != 0) stringResource(resId) else continent
}

@Composable
fun getCountryString(countryCode: String, defaultName: String): String {
    val context = LocalContext.current
    val resourceName = "country_" + countryCode.lowercase()
    val resId = remember(countryCode) { context.resources.getIdentifier(resourceName, "string", context.packageName) }
    return if (resId != 0) stringResource(resId) else defaultName
}
