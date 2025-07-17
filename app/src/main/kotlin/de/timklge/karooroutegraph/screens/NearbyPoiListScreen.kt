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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import de.timklge.karooroutegraph.Element
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.NearestPoint
import de.timklge.karooroutegraph.OverpassPOIProvider
import de.timklge.karooroutegraph.OverpassResponse
import de.timklge.karooroutegraph.POI
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.calculatePoiDistances
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

enum class NearbyPoiCategory(val label: String, val osmTag: String) {
    DRINKING_WATER("Water", "amenity=drinking_water"),
    GAS_STATIONS("Gas Station", "amenity=fuel"),
    SUPERMARKETS("Supermarket", "shop=supermarket"),
    RESTAURANTS("Restaurant", "amenity=restaurant"),
    TOILETS("Toilets", "amenity=toilets"),
    SHOWERS("Showers", "amenity=shower"),
    ATMS("ATMs", "amenity=atm"),
    SHELTER("Shelter", "amenity=shelter"),
    CAMPING_SITE("Camping Site", "tourism=camp_site"),
    HOTEL("Hotel", "tourism=hotel"),
    TRAIN_STATION("Train Station", "railway=station"),
    WASTE_BASKET("Waste Basket", "amenity=waste_basket"),
    BIKE_SHOP("Bike Shop", "shop=bicycle"),
}

data class NearbyPoi(val element: Element, val poi: Symbol.POI)

fun formatDistance(distanceMeters: Double, isImperial: Boolean): String {
    return distanceMeters.let {
        if (isImperial){
            String.format(java.util.Locale.getDefault(), "%.1f mi", distanceMeters * 0.000621371)
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000)
        }
    }
}

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
    val overpassPOIProvider = koinInject<OverpassPOIProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()
    val routeGraphViewModelProvider = koinInject<RouteGraphViewModelProvider>()

    val maxDistanceFromRoute = 1_000.0

    LaunchedEffect(Unit) {
        val settings = karooSystemServiceProvider.streamViewSettings().first()
        selectedSort = settings.poiSortOptionForNearbyPois
    }

    var pois by remember { mutableStateOf(emptyList<NearbyPoi>()) }

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

    val nearestPointsOnRouteToFoundPois by remember { derivedStateOf { viewModel?.knownRoute?.let { route ->
        calculatePoiDistances(route, pois.map { POI(it.poi) }, maxDistanceFromRoute)
    } ?: emptyMap() } }

    fun linearDistanceToPoi(poi: NearbyPoi): Double? {
        return currentPosition?.let { currentPosition ->
            TurfMeasurement.distance(
                Point.fromLngLat(poi.element.lon, poi.element.lat),
                currentPosition,
                TurfConstants.UNIT_METERS
            )
        }
    }

    data class DistanceOnRouteToPoiResult(val distanceOnRoute: Double, val distanceFromPointOnRoute: Double)

    fun distanceOnRouteToPoi(poi: NearbyPoi, nearestPointsOnRouteToFoundPois: Map<POI, List<NearestPoint>>, distanceAlongRoute: Float?): DistanceOnRouteToPoiResult? {
        val nearestPoints = nearestPointsOnRouteToFoundPois.entries.find { it.key.symbol == poi.poi }?.value
        val nearestPointsAheadOnRoute = nearestPoints?.filter { it.distanceFromRouteStart >= (distanceAlongRoute ?: 0f) }
        val nearestPointAheadOnRoute = nearestPointsAheadOnRoute?.minByOrNull { it.distanceFromPointOnRoute + it.distanceFromRouteStart }

        return nearestPointAheadOnRoute?.let {
            DistanceOnRouteToPoiResult(
                distanceOnRoute = it.distanceFromRouteStart.toDouble() - (distanceAlongRoute ?: 0.0f),
                distanceFromPointOnRoute = it.distanceFromPointOnRoute.toDouble()
            )
        }
    }

    fun onRefresh() {
        if (isRefreshing) return // Prevent multiple refreshes

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

                val radiusSteps = listOf(2_000, 5_000, 10_000)
                val desiredCount = 10 // Desired number of POIs to fetch
                val limit = 30

                var overpassResponse: OverpassResponse? = null

                if (selectedSort == PoiSortOption.LINEAR_DISTANCE) {
                    for (step in radiusSteps) {
                        overpassResponse = overpassPOIProvider.requestOverpassPOIs(
                            selectedCategories.map { it.osmTag },
                            points = listOf(currentPos),
                            radius = step,
                            limit = limit
                        )

                        if (overpassResponse.elements.size >= desiredCount) {
                            break // Enough POIs found, exit loop
                        }
                    }
                } else {
                    val route = viewModel?.knownRoute

                    if (route == null) {
                        lastErrorMessage = "Failed to fetch POIs ahead on route: No route loaded."
                        delay(1_000)
                        isRefreshing = false
                        return@launch
                    }

                    val routeAhead = try {
                        val routeLength = viewModel?.routeDistance?.toDouble() ?: TurfMeasurement.length(route, TurfConstants.UNIT_METERS)
                        val startDistance = viewModel?.distanceAlongRoute?.toDouble() ?: 0.0
                        val endDistance = (startDistance + 50_000).coerceAtMost(routeLength) // 50 km ahead
                        TurfMisc.lineSliceAlong(route, startDistance, endDistance, TurfConstants.UNIT_METERS)
                    } catch(e: Exception) {
                        Log.e(KarooRouteGraphExtension.TAG, "Failed to slice route ahead", e)
                        route
                    }

                    overpassResponse = overpassPOIProvider.requestOverpassPOIs(
                        requestedTags = selectedCategories.map { it.osmTag },
                        points = routeAhead.coordinates(),
                        radius = 1_000,
                        limit = 100
                    )
                }

                val mappedPois = overpassResponse?.elements?.map { element ->
                    NearbyPoi(
                        element = element,
                        poi = Symbol.POI(
                            id = "nearby-${element.id}",
                            lat = element.lat,
                            lng = element.lon,
                            name = element.tags?.get("name") ?: "Unnamed POI"
                        )
                    )
                } ?: emptyList()

                when (selectedSort) {
                    PoiSortOption.LINEAR_DISTANCE -> {
                        pois = mappedPois.sortedBy { linearDistanceToPoi(it) ?: Double.MAX_VALUE }
                    }
                    PoiSortOption.AHEAD_ON_ROUTE -> {
                        if (viewModel?.knownRoute == null) {
                            lastErrorMessage = "Failed to fetch POIs: No route available. Please start a route first."
                            delay(1_000)
                            isRefreshing = false
                        } else {
                            val newNearestPointsOnRouteToFoundPois = viewModel?.knownRoute?.let { route ->
                                calculatePoiDistances(route, mappedPois.map { POI(it.poi) }, maxDistanceFromRoute)
                            } ?: emptyMap()

                            pois = mappedPois.sortedBy { poi ->
                                val result = distanceOnRouteToPoi(poi, newNearestPointsOnRouteToFoundPois, viewModel?.distanceAlongRoute)

                                result?.distanceOnRoute?.plus(result.distanceFromPointOnRoute) ?: Double.MAX_VALUE
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
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier
                        .weight(1f)
                        .clickable { showDialog = true }) {
                        OutlinedTextField(
                            value = if (selectedCategories.isEmpty()) "Select" else selectedCategories.joinToString { it.label },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Categories") },
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

                    IconButton(onClick = { showSortDialog = true }) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = poi.element.tags?.get("name") ?: "Unnamed POI",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        val distanceLabel = currentPosition?.let {
                            if (selectedSort == PoiSortOption.LINEAR_DISTANCE) {
                                val distanceMeters = linearDistanceToPoi(poi)

                                distanceMeters?.let { distance -> formatDistance(distance, isImperial) }
                            } else {
                                val result = distanceOnRouteToPoi(poi, nearestPointsOnRouteToFoundPois, viewModel?.distanceAlongRoute)

                                result?.let {
                                    "In ${formatDistance(it.distanceOnRoute, isImperial)}, ${formatDistance(it.distanceFromPointOnRoute, isImperial)} from route"
                                }
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

                    if (poi.element.hasAdditionalInfo()) {
                        IconButton(
                            onClick = {
                                openingHoursText = poi.element.tags?.map { (k, v) -> "$k=$v" }?.joinToString("\r\n") ?: "No info available" // poi.tags?.get("opening_hours") ?: "No opening hours info"
                                showOpeningHoursDialog = true
                            },
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.bx_info_circle),
                                contentDescription = "Info"
                            )
                        }
                    }


                    IconButton(
                        onClick = {
                            karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(poi.poi))
                        },
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.bxmap),
                            contentDescription = "POI"
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var tempSelectedCategories by remember { mutableStateOf(selectedCategories) }

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
                                Text(category.label)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            selectedCategories = tempSelectedCategories
                            showDialog = false
                            onRefresh()
                        }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
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
                        Text("OK")
                    }
                }
            }
        }
    }
}
