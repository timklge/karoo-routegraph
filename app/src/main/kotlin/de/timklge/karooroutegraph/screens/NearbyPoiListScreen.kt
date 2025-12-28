package de.timklge.karooroutegraph.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.pois.NearbyPOI
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.OverpassPOIProvider
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.pois.distanceToPoi
import de.timklge.karooroutegraph.pois.formatDistance
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPoiListScreen() {
    val coroutineScope = rememberCoroutineScope()
    var selectedCategories by remember { mutableStateOf(emptySet<NearbyPoiCategory>()) }
    var showDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(PoiSortOption.LINEAR_DISTANCE) }

    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val offlineNearbyPOIProvider = koinInject<OfflineNearbyPOIProvider>()
    val overpassNearbyPOIProvider = koinInject<OverpassPOIProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()
    val routeGraphViewModelProvider = koinInject<RouteGraphViewModelProvider>()

    var maxDistanceFromRoute by remember { mutableDoubleStateOf(1_000.0) }

    // Add these for accessing string resources
    val errorNoRoute = stringResource(R.string.error_no_route)
    val errorNoPosition = stringResource(R.string.error_no_position)
    val errorFetchPois = stringResource(R.string.error_fetch_pois)
    val unnamedPoi = stringResource(R.string.unnamed_poi)

    LaunchedEffect(Unit) {
        val settings = karooSystemServiceProvider.streamSettings().first()
        maxDistanceFromRoute = settings.poiDistanceToRouteMaxMeters
    }

    var pois by remember { mutableStateOf(emptyList<NearbyPOISymbol>()) }
    var mappedPois by remember { mutableStateOf(emptyList<NearbyPOISymbol>()) }

    var showOpeningHoursDialog by remember { mutableStateOf(false) }
    var openingHoursText by remember { mutableStateOf("") }

    var isImperial by remember { mutableStateOf(false)}

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<UserProfile>()
                .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
                .collect { isImperial = it }
    }

    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val viewModel by routeGraphViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val temporaryPois by karooSystemServiceProvider.streamTemporaryPOIs().collectAsStateWithLifecycle(RouteGraphTemporaryPOIs())
    var nearestPointsOnRouteToFoundPois by remember { mutableStateOf<Map<POI, List<NearestPoint>>>(mapOf()) }

    LaunchedEffect(viewModel?.knownRoute, pois) {
        val route = viewModel?.knownRoute

        if (route != null) {
            val distances = calculatePoiDistances(route, pois.map { POI(it.poi) }, maxDistanceFromRoute)
            nearestPointsOnRouteToFoundPois = distances
        }
    }

    fun linearDistanceToPoi(poi: NearbyPOISymbol): Double? {
        return currentPosition?.let { currentPosition ->
            TurfMeasurement.distance(
                Point.fromLngLat(poi.element.lon, poi.element.lat),
                currentPosition,
                TurfConstants.UNIT_METERS
            )
        }
    }

    suspend fun sortPois() {
        when (selectedSort) {
            PoiSortOption.LINEAR_DISTANCE -> {
                pois = mappedPois.sortedBy { linearDistanceToPoi(it) ?: Double.MAX_VALUE }
            }
            PoiSortOption.AHEAD_ON_ROUTE -> {
                if (viewModel?.knownRoute == null) {
                    lastErrorMessage = errorNoRoute
                    delay(1_000)
                    isRefreshing = false
                } else {
                    val newNearestPointsOnRouteToFoundPois = viewModel?.knownRoute?.let { route ->
                        calculatePoiDistances(route, mappedPois.map { POI(it.poi) }, maxDistanceFromRoute)
                    } ?: emptyMap()

                    pois = mappedPois.sortedBy { poi ->
                        distanceToPoi(poi.poi, viewModel?.sampledElevationData, newNearestPointsOnRouteToFoundPois, currentPosition, selectedSort, viewModel?.distanceAlongRoute)
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedSort, mappedPois) {
        sortPois()
    }

    fun onRefresh() {
        if (isRefreshing) return // Prevent multiple refreshes

        isRefreshing = true
        lastErrorMessage = null // Reset error message

        coroutineScope.launch {
            try {
                val currentPos = currentPosition
                if (currentPos == null) {
                    lastErrorMessage = errorNoPosition
                    delay(1_000)
                    isRefreshing = false
                    return@launch
                }

                val desiredCount = 20 // Desired number of POIs to fetch
                val limit = 30

                val onlyTownOrVillagesSelected =
                    selectedCategories.all { it == NearbyPoiCategory.TOWN || it == NearbyPoiCategory.VILLAGE }

                val route = viewModel?.knownRoute
                if (selectedSort != PoiSortOption.LINEAR_DISTANCE && route == null) {
                    lastErrorMessage = errorNoRoute
                    delay(1_000)
                    isRefreshing = false
                    return@launch
                }

                var overpassResponse: Set<NearbyPOI>? = null

                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val radius = if (onlyTownOrVillagesSelected) 50_000 else 10_000
                    val hasOfflineFiles = offlineNearbyPOIProvider.getAvailableCountriesInBounds(
                        listOf(currentPos),
                        radius
                    ).isNotEmpty()

                    if (selectedSort == PoiSortOption.LINEAR_DISTANCE) {
                        if (hasOfflineFiles) {
                            val offlineResponse = offlineNearbyPOIProvider.requestNearbyPOIs(
                                selectedCategories.flatMap { it.osmTag }.distinct(),
                                points = listOf(currentPos),
                                radius = if (onlyTownOrVillagesSelected) 50_000 else 10_000,
                                limit = limit
                            )

                            overpassResponse = offlineResponse.toSet()
                        } else {
                            val radiusSteps = if (onlyTownOrVillagesSelected) {
                                listOf(5_000, 10_000, 20_000, 50_000)
                            } else {
                                listOf(2_000, 5_000, 10_000)
                            }

                            for (step in radiusSteps) {
                                val newResponse = overpassNearbyPOIProvider.requestNearbyPOIs(
                                    selectedCategories.flatMap { it.osmTag }.distinct(),
                                    points = listOf(currentPos),
                                    radius = step,
                                    limit = limit
                                )
                                overpassResponse = newResponse.toSet()

                                if (newResponse.size >= desiredCount) {
                                    break // Enough POIs found, exit loop
                                }
                            }
                        }
                    } else {
                        val route = route ?: return@withContext

                        val routeLength = viewModel?.routeDistance?.toDouble() ?: TurfMeasurement.length(route, TurfConstants.UNIT_METERS)
                        val radius = if (onlyTownOrVillagesSelected) 5_000 else maxDistanceFromRoute.roundToInt()
                        val nearbyPoiProvider = if (hasOfflineFiles) offlineNearbyPOIProvider else overpassNearbyPOIProvider
                        val lookaheadDistance = if (hasOfflineFiles) routeLength else 50_000.0

                        val routeAhead = try {
                            val startDistance = ((viewModel?.distanceAlongRoute?.toDouble()
                                ?: 0.0) - 2_000).coerceAtLeast(0.0) // 2 km behind
                            val endDistance = (startDistance + lookaheadDistance).coerceAtMost(routeLength) // 50 km ahead
                            TurfMisc.lineSliceAlong(
                                route,
                                startDistance,
                                endDistance,
                                TurfConstants.UNIT_METERS
                            )
                        } catch (e: Exception) {
                            Log.e(KarooRouteGraphExtension.TAG, "Failed to slice route ahead", e)
                            route
                        }

                        overpassResponse = nearbyPoiProvider.requestNearbyPOIs(
                            requestedTags = selectedCategories.flatMap { it.osmTag }.distinct(),
                            points = routeAhead.coordinates(),
                            radius = radius,
                            limit = if (hasOfflineFiles) 200 else 100
                        ).toSet()
                    }

                    mappedPois = overpassResponse?.map { element ->
                        NearbyPOISymbol(
                            element = element,
                            poi = Symbol.POI(
                                id = "nearby-${element.id}",
                                lat = element.lat,
                                lng = element.lon,
                                name = element.tags["name"] ?: unnamedPoi,
                                type = NearbyPoiCategory.fromTag(element.tags)?.hhType ?: Symbol.POI.Types.GENERIC,
                            )
                        )
                    } ?: emptyList()
                }
            } catch (e: CancellationException) {
                throw e // Rethrow cancellation exceptions
            } catch(e: Throwable){
                lastErrorMessage = String.format(errorFetchPois, e.message ?: "")
                delay(1_000)
                isRefreshing = false
                return@launch
            }

            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        val viewSettings = karooSystemServiceProvider.streamViewSettings().first()
        selectedSort = viewSettings.poiSortOptionForNearbyPois
        selectedCategories = viewSettings.poiCategoriesForNearbyPois

        if (selectedCategories.isNotEmpty()) {
            onRefresh()
        }
    }

    if (showSortDialog) {
        fun selectSortOption(option: PoiSortOption) {
            val optionChanged = selectedSort != option
            selectedSort = option
            showSortDialog = false

            coroutineScope.launch {
                karooSystemServiceProvider.saveViewSettings { settings ->
                    settings.copy(poiSortOptionForNearbyPois = option)
                }
            }

            if (optionChanged) {
                onRefresh() // Refresh POIs if sort option changed
            }
        }

        Dialog(
            onDismissRequest = { showSortDialog = false },
        ) {
            Card(modifier = Modifier.padding(16.dp)) {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)) {
                    PoiSortOption.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectSortOption(option)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedSort == option,
                                onClick = {
                                    selectSortOption(option)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(option.displayNameRes))
                        }
                    }
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = ::onRefresh,
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val selectText = stringResource(R.string.select)
                    val categoriesText = if (selectedCategories.isEmpty()) {
                        selectText
                    } else {
                        @Suppress("SimplifiableCallChain")
                        selectedCategories.map { stringResource(it.labelRes) }.joinToString()
                    }

                    Box(modifier = Modifier
                        .weight(1f)
                        .clickable { showDialog = true }) {
                        OutlinedTextField(
                            value = categoriesText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.categories)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { showSortDialog = true },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Sort Options"
                        )
                    }
                }
            }

            if (lastErrorMessage != null) {
                item {
                    Text(
                        text = lastErrorMessage ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                items(pois) { poi ->
                    var showContextMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = poi.element.tags["name"] ?: stringResource(R.string.unnamed_poi),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            val distanceLabel = currentPosition?.let {
                                if (selectedSort == PoiSortOption.LINEAR_DISTANCE) {
                                    val distanceMeters = linearDistanceToPoi(poi)

                                    distanceMeters?.let { distance ->
                                        formatDistance(
                                            distance,
                                            isImperial
                                        )
                                    }
                                } else {
                                    val result = distanceToPoi(poi.poi, viewModel?.sampledElevationData,
                                        nearestPointsOnRouteToFoundPois, currentPosition, selectedSort, viewModel?.distanceAlongRoute)

                                    result?.formatDistance(LocalContext.current, isImperial)
                                }
                            }

                            if (distanceLabel != null){
                                Text(
                                    text = distanceLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showContextMenu = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }

                            DropdownMenu(
                                expanded = showContextMenu,
                                onDismissRequest = { showContextMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.navigate)) },
                                    onClick = {
                                        showContextMenu = false
                                        karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(poi.poi))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.bxmap),
                                            contentDescription = stringResource(R.string.navigate)
                                        )
                                    }
                                )

                                if (!temporaryPois.poisByOsmId.contains(poi.element.id)) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.add_to_map)) },
                                        onClick = {
                                            showContextMenu = false

                                            coroutineScope.launch {
                                                delay(100) // fixme delay to ensure the menu closes before saving

                                                karooSystemServiceProvider.saveTemporaryPOIs {
                                                    it.copy(poisByOsmId = it.poisByOsmId + (poi.element.id to poi.poi))
                                                }
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.bx_info_circle),
                                                contentDescription = stringResource(R.string.add_to_map)
                                            )
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.remove_from_map)) },
                                        onClick = {
                                            showContextMenu = false
                                            coroutineScope.launch {
                                                karooSystemServiceProvider.saveTemporaryPOIs {
                                                    it.copy(poisByOsmId = it.poisByOsmId - poi.element.id)
                                                }
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.bx_info_circle),
                                                contentDescription = stringResource(R.string.remove_from_map)
                                            )
                                        }
                                    )
                                }

                                if (poi.element.tags.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.show_info)) },
                                        onClick = {
                                            showContextMenu = false
                                            openingHoursText = poi.element.tags.map { (k, v) -> "$k=$v" }.joinToString("\r\n")
                                            showOpeningHoursDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.bx_info_circle),
                                                contentDescription = stringResource(R.string.show_info)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        CategorySelectionDialog(
            initialCategories = selectedCategories,
            onDismiss = { showDialog = false },
            onConfirm = { newCategories ->
                selectedCategories = newCategories
                showDialog = false

                coroutineScope.launch {
                    karooSystemServiceProvider.saveViewSettings { settings ->
                        settings.copy(poiCategoriesForNearbyPois = selectedCategories)
                    }
                    onRefresh()
                }
            }
        )
    }

    if (showOpeningHoursDialog) {
        Dialog(onDismissRequest = { showOpeningHoursDialog = false }) {
            Card(modifier = Modifier.padding(16.dp)) {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)) {
                    Text(text = openingHoursText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showOpeningHoursDialog = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
fun CategorySelectionDialog(
    initialCategories: Set<NearbyPoiCategory>,
    onDismiss: () -> Unit,
    onConfirm: (Set<NearbyPoiCategory>) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var tempSelectedCategories by remember { mutableStateOf(initialCategories) }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(NearbyPoiCategory.entries) { category ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    tempSelectedCategories =
                                        if (tempSelectedCategories.contains(category)) {
                                            tempSelectedCategories - category
                                        } else {
                                            tempSelectedCategories + category
                                        }
                                }
                        ) {
                            Checkbox(
                                checked = tempSelectedCategories.contains(category),
                                onCheckedChange = {
                                    tempSelectedCategories = if (tempSelectedCategories.contains(category)) {
                                        tempSelectedCategories - category
                                    } else {
                                        tempSelectedCategories + category
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(category.labelRes))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onConfirm(tempSelectedCategories)
                    }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}
