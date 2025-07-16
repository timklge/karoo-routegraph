package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.NominatimProvider
import de.timklge.karooroutegraph.OsmPlace
import de.timklge.karooroutegraph.R
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiSearchScreen() {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastErrorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val nominatimProvider = koinInject<NominatimProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()

    var pois by remember { mutableStateOf(emptyList<OsmPlace>()) }

    var isImperial by remember { mutableStateOf(false)}

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<UserProfile>()
            .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
            .collect { isImperial = it }
    }

    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)

    fun distanceToPoi(poi: OsmPlace): Double? {
        return currentPosition?.let { currentPosition ->
            val poiPoint = Point.fromLngLat(poi.lon.toDouble(), poi.lat.toDouble())
            TurfMeasurement.distance(
                poiPoint,
                currentPosition,
                TurfConstants.UNIT_METERS
            )
        }
    }

    fun onRefresh() {
        if (isRefreshing) return // Prevent multiple refreshes

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

                pois = foundPlaces.sortedWith(
                    compareBy<OsmPlace> { it.placeRank }.thenBy { distanceToPoi(it) }
                )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { showDialog = true },
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

                    IconButton(
                        onClick = {
                            karooSystemServiceProvider.karooSystemService.dispatch(
                                LaunchPinDrop(Symbol.POI("poi-${poi.osmId ?: poi.placeId}", poi.lat.toDouble(), poi.lon.toDouble(), name = poi.displayName ?: poi.name ?: "Unnamed POI"))
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
}