package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.NearestPoint
import de.timklge.karooroutegraph.NominatimProvider
import de.timklge.karooroutegraph.OsmPlace
import de.timklge.karooroutegraph.POI
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.calculatePoiDistancesAsync
import de.timklge.karooroutegraph.distanceToPoi
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiSearchScreen() {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(PoiSortOption.LINEAR_DISTANCE) }
    val focusManager = LocalFocusManager.current

    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val nominatimProvider = koinInject<NominatimProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()
    val routeGraphViewModelProvider = koinInject<RouteGraphViewModelProvider>()

    var maxDistanceFromRoute by remember { mutableStateOf(1_000.0) }

    LaunchedEffect(Unit) {
        val viewSettings = karooSystemServiceProvider.streamViewSettings().first()
        selectedSort = viewSettings.poiSortOptionForSearchedPois
    }

    LaunchedEffect(Unit) {
        val settings = karooSystemServiceProvider.streamSettings().first()
        maxDistanceFromRoute = settings.poiDistanceToRouteMaxMeters
    }

    var pois by remember { mutableStateOf(emptyList<OsmPlace>()) }

    var isImperial by remember { mutableStateOf(false)}
    val temporaryPois by karooSystemServiceProvider.streamTemporaryPOIs().collectAsStateWithLifecycle(RouteGraphTemporaryPOIs())

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<UserProfile>()
            .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
            .collect { isImperial = it }
    }

    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val viewModel by routeGraphViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    var nearestPointsOnRouteToFoundPois by remember { mutableStateOf<Map<POI, List<NearestPoint>>>(mapOf()) }

    LaunchedEffect(viewModel?.knownRoute, pois) {
        val route = viewModel?.knownRoute

        if (route != null) {
            val poisForCalculation = pois.map { poi ->
                POI(Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI"))
            }
            val distances = calculatePoiDistancesAsync(route, poisForCalculation, maxDistanceFromRoute)
            nearestPointsOnRouteToFoundPois = distances
        }
    }

    fun linearDistanceToPoi(poi: OsmPlace): Double? {
        return currentPosition?.let { currentPosition ->
            TurfMeasurement.distance(
                Point.fromLngLat(poi.lon.toDouble(), poi.lat.toDouble()),
                currentPosition,
                TurfConstants.UNIT_METERS
            )
        }
    }

    fun onRefresh() {
        if (isRefreshing) return // Prevent multiple refreshes
        if (searchQuery.isEmpty()) {
            return
        }

        focusManager.clearFocus()
        isRefreshing = true
        lastErrorMessage = null // Reset error message

        coroutineScope.launch {
            try {
                val currentPos = currentPosition
                if (currentPos == null) {
                    lastErrorMessage = "Failed to fetch POIs: Current position is not available. Check GPS signal."
                    delay(1_000)
                    isRefreshing = false
                    return@launch
                }

                if (searchQuery.isEmpty()) {
                    delay(1_000)
                    pois = emptyList() // Clear POIs if search query is empty
                    isRefreshing = false
                    return@launch
                }

                val foundPlaces = nominatimProvider.requestNominatim(searchQuery,
                    lat = currentPos.latitude(),
                    lng = currentPos.longitude(),
                )

                when (selectedSort) {
                    PoiSortOption.LINEAR_DISTANCE -> {
                        pois = foundPlaces.sortedWith(
                            compareBy<OsmPlace> { it.placeRank }.thenBy { poi ->
                                linearDistanceToPoi(poi) ?: Double.MAX_VALUE
                            }
                        )
                    }
                    PoiSortOption.AHEAD_ON_ROUTE -> {
                        if (viewModel?.knownRoute == null) {
                            lastErrorMessage = "Failed to fetch POIs: No route available. Please start a route first."
                            delay(1_000)
                            isRefreshing = false
                        } else {
                            val poisForCalculation = foundPlaces.map { poi ->
                                POI(Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI"))
                            }

                            val newNearestPointsOnRouteToFoundPois = viewModel?.knownRoute?.let { route ->
                                calculatePoiDistancesAsync(route, poisForCalculation, maxDistanceFromRoute)
                            } ?: emptyMap()

                            /* pois = foundPlaces.sortedWith(
                                compareBy<OsmPlace> { it.placeRank }.thenBy { poi ->
                                    val symbol = Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI")
                                    distanceToPoi(symbol, newNearestPointsOnRouteToFoundPois, currentPos, selectedSort, viewModel?.distanceAlongRoute)
                                }
                            ) */

                            pois = foundPlaces.sortedBy { poi ->
                                val symbol = Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI")
                                distanceToPoi(symbol, viewModel?.sampledElevationData,
                                    newNearestPointsOnRouteToFoundPois, currentPos, selectedSort, viewModel?.distanceAlongRoute)
                            }
                        }
                    }
                }
            } catch(e: Exception){
                lastErrorMessage = "Failed to fetch POIs: ${e.message}"
                delay(1_000)
                isRefreshing = false
                return@launch
            }

            isRefreshing = false
        }
    }

    if (showSortDialog) {
        fun selectSortOption(option: PoiSortOption) {
            val optionChanged = selectedSort != option
            selectedSort = option
            showSortDialog = false

            coroutineScope.launch {
                karooSystemServiceProvider.saveViewSettings { settings ->
                    settings.copy(poiSortOptionForSearchedPois = option)
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
                            Text(option.displayName)
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search query") },
                        maxLines = 1,
                        singleLine = true,
                        modifier = Modifier.weight(1.0f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onRefresh() })
                    )

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
            }

            items(pois) { poi ->
                var showContextMenu by remember { mutableStateOf(false) }
                val symbol = Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = poi.displayName ?: poi.name ?: "Unnamed POI",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val distanceLabel = currentPosition?.let {
                            if (selectedSort == PoiSortOption.LINEAR_DISTANCE) {
                                val distanceMeters = linearDistanceToPoi(poi)
                                distanceMeters?.let { distance ->
                                    formatDistance(distance, isImperial)
                                }
                            } else {
                                val result = distanceToPoi(symbol, viewModel?.sampledElevationData, nearestPointsOnRouteToFoundPois, currentPosition, selectedSort, viewModel?.distanceAlongRoute)
                                result?.formatDistance(isImperial)
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
                                text = { Text("Navigate") },
                                onClick = {
                                    showContextMenu = false
                                    karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(symbol))
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.bxmap),
                                        contentDescription = "Navigate"
                                    )
                                }
                            )

                            if (poi.osmId != null || poi.placeId != null){
                                val id = (poi.osmId ?: poi.placeId) ?: error("Missing id")

                                if (!temporaryPois.poisByOsmId.contains(id)) {
                                    DropdownMenuItem(
                                        text = { Text("Add to map") },
                                        onClick = {
                                            showContextMenu = false
                                            coroutineScope.launch {
                                                karooSystemServiceProvider.saveTemporaryPOIs {
                                                    it.copy(poisByOsmId = it.poisByOsmId + (id to symbol))
                                                }
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.bx_info_circle),
                                                contentDescription = "Add to map"
                                            )
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Remove from map") },
                                        onClick = {
                                            showContextMenu = false
                                            coroutineScope.launch {
                                                karooSystemServiceProvider.saveTemporaryPOIs {
                                                    it.copy(poisByOsmId = it.poisByOsmId - id)
                                                }
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.bx_info_circle),
                                                contentDescription = "Remove from map"
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
}