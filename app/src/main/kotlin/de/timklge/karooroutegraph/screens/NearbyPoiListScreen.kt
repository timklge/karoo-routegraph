package de.timklge.karooroutegraph.screens

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.Element
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.OverpassPOIProvider
import de.timklge.karooroutegraph.OverpassResponse
import de.timklge.karooroutegraph.R
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPoiListScreen() {
    val coroutineScope = rememberCoroutineScope()
    var selectedCategories by remember { mutableStateOf(emptySet<NearbyPoiCategory>()) }
    var showDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }

    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val overpassPOIProvider = koinInject<OverpassPOIProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()

    var pois by remember { mutableStateOf(emptyList<Element>()) }

    var showOpeningHoursDialog by remember { mutableStateOf(false) }
    var openingHoursText by remember { mutableStateOf("") }

    var isImperial by remember { mutableStateOf(false)}

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<UserProfile>()
                .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
                .collect { isImperial = it }
    }

    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)

    fun distanceToPoi(poi: Element): Double? {
        return currentPosition?.let { currentPosition ->
            val poiPoint = Point.fromLngLat(poi.lon, poi.lat)
            TurfMeasurement.distance(
                poiPoint,
                currentPosition,
                TurfConstants.UNIT_METERS
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

                val radiusSteps = listOf(2_000, 5_000, 20_000)
                val desiredCount = 20 // Desired number of POIs to fetch

                var overpassResponse: OverpassResponse? = null

                for (step in radiusSteps) {
                    overpassResponse = overpassPOIProvider.requestOverpassPOIs(
                        selectedCategories.map { it.osmTag },
                        lat = currentPos.latitude(),
                        lng = currentPos.longitude(),
                        radius = step,
                        limit = desiredCount
                    )

                    if (overpassResponse.elements.size >= desiredCount) {
                        break // Enough POIs found, exit loop
                    }
                }

                pois = overpassResponse?.elements?.sortedBy { poi ->
                    distanceToPoi(poi)
                } ?: emptyList()
            } catch(e: Exception){
                lastErrorMessage = "Failed to fetch POIs: ${e.message}"
                delay(1_000)
                isRefreshing = false
                return@launch
            }

            isRefreshing = false
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = ::onRefresh,
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { showDialog = true },
                ) {
                    OutlinedTextField(
                        value = if (selectedCategories.isEmpty()) "Select categories" else selectedCategories.joinToString { it.label },
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
                            text = poi.tags?.get("name") ?: "Unnamed POI",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val distanceLabel = currentPosition?.let {
                            val distanceMeters = distanceToPoi(poi)

                            distanceMeters?.let {
                                if (isImperial){
                                    String.format(java.util.Locale.getDefault(), "%.1f mi", distanceMeters * 0.000621371)
                                } else {
                                    String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000)
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

                    if (poi.hasAdditionalInfo()) {
                        IconButton(
                            onClick = {
                                openingHoursText = poi.tags?.map { (k, v) -> "$k=$v" }?.joinToString("\r\n") ?: "No info available" // poi.tags?.get("opening_hours") ?: "No opening hours info"
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
                            karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(
                                Symbol.POI("poi-${poi.id}", poi.lat, poi.lon, name = poi.tags?.get("name") ?: "Unnamed POI"))
                            )
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
                                        tempSelectedCategories = if (tempSelectedCategories.contains(category)) {
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
                Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
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