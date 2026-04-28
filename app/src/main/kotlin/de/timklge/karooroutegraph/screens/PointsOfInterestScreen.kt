package de.timklge.karooroutegraph.screens

import android.content.Intent
import android.util.Log
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.pois.DownloadedPbf
import de.timklge.karooroutegraph.pois.NearbyPOIPbfDownloadService
import de.timklge.karooroutegraph.pois.POIActivity
import de.timklge.karooroutegraph.pois.PbfDownloadStatus
import de.timklge.karooroutegraph.pois.PbfType
import de.timklge.karooroutegraph.streamPbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStore
import de.timklge.karooroutegraph.updatePbfDownloadStoreStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.Collator
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration

@Composable
private fun getContinentString(continent: String): String {
    val context = LocalContext.current
    val resourceName = "continent_" + continent.lowercase().replace(" ", "_")
    val resId = remember(continent) { context.resources.getIdentifier(resourceName, "string", context.packageName) }
    return if (resId != 0) stringResource(resId) else continent
}

@Composable
private fun getCountryString(countryCode: String, defaultName: String): String {
    val context = LocalContext.current
    val resourceName = "country_" + countryCode.lowercase()
    val resId = remember(countryCode) { context.resources.getIdentifier(resourceName, "string", context.packageName) }
    return if (resId != 0) stringResource(resId) else defaultName
}

private fun getCountryStringSync(context: android.content.Context, countryCode: String, defaultName: String): String {
    val resourceName = "country_" + countryCode.lowercase()
    val resId = context.resources.getIdentifier(resourceName, "string", context.packageName)
    return if (resId != 0) context.getString(resId) else defaultName
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsOfInterestScreen(
    onBack: () -> Unit
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
                        val locale = LocalConfiguration.current.locales[0]
                        val collator = remember(locale) { Collator.getInstance(locale) }

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
                                                items(countries.sortedWith(compareBy(collator) { entry -> getCountryStringSync(ctx, entry.key, entry.value.name) })) { entry ->
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
                                                                PbfDownloadStatus.PENDING, PbfDownloadStatus.UPDATING -> if (downloadedPbf.progress in 0.01f..0.99f) {
                                                                    CircularProgressIndicator(modifier = Modifier.size(48.dp), progress = { downloadedPbf.progress })
                                                                } else {
                                                                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                                                }
                                                                PbfDownloadStatus.DOWNLOAD_FAILED -> Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.failed), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                                                PbfDownloadStatus.UPDATE_AVAILABLE -> {
                                                                    IconButton(onClick = {
                                                                        coroutineScope.launch { updatePbfDownloadStoreStatus(ctx, key, PbfDownloadStatus.UPDATING) }
                                                                    }) {
                                                                        Icon(Icons.Default.Update, contentDescription = stringResource(R.string.download), tint = MaterialTheme.colorScheme.primary)
                                                                    }
                                                                }
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