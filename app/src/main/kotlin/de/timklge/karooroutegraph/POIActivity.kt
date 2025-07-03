package de.timklge.karooroutegraph

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooroutegraph.screens.PoiScreen
import de.timklge.karooroutegraph.theme.AppTheme
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

enum class PoiSortOption(val displayName: String) {
    LINEAR_DISTANCE("Linear distance"),
    AHEAD_ON_ROUTE("Ahead on route");
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPoiListScreen() {
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val viewModelProvider = koinInject<RouteGraphViewModelProvider>()

    val localPois by karooSystemServiceProvider.stream<OnNavigationState>()
        .mapNotNull { onNavigationState ->
            val newLocalPois = (onNavigationState.state as? OnNavigationState.NavigationState.NavigatingRoute)?.pois
            newLocalPois
        }
        .collectAsStateWithLifecycle(listOf())

    val routeGraphViewModel by viewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)

    val globalPois by karooSystemServiceProvider.stream<OnGlobalPOIs>()
        .map { onGlobalPois ->
            onGlobalPois.pois
        }
        .collectAsStateWithLifecycle(listOf())

    val currentPosition by karooSystemServiceProvider.streamDataFlow(DataType.Type.LOCATION)
        .map { newLocation ->
            if (newLocation is StreamState.Streaming) {
                val lat = newLocation.dataPoint.values[DataType.Field.LOC_LATITUDE]
                val lon = newLocation.dataPoint.values[DataType.Field.LOC_LONGITUDE]
                if (lat != null && lon != null) {
                    Point.fromLngLat(lon, lat)
                } else {
                    null
                }
            } else {
                null
            }
        }
        .collectAsStateWithLifecycle(null)

    val isImperial by karooSystemServiceProvider.stream<UserProfile>()
        .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
        .collectAsStateWithLifecycle(false)

    // State for dropdown
    var expanded by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(PoiSortOption.LINEAR_DISTANCE) }

    fun distanceToPoi(poi: Symbol.POI): Double? {
        when (selectedSort) {
            PoiSortOption.LINEAR_DISTANCE -> {
                return currentPosition?.let { currentPosition ->
                    TurfMeasurement.distance(
                        Point.fromLngLat(poi.lng, poi.lat),
                        currentPosition,
                        TurfConstants.UNIT_METERS
                    )
                }
            }
            PoiSortOption.AHEAD_ON_ROUTE -> {
                val nearestPoints = routeGraphViewModel?.poiDistances?.entries?.find { it.key.symbol == poi }?.value
                val nearestPointsAheadOnRoute = nearestPoints?.filter { it.distanceFromRouteStart >= (routeGraphViewModel?.distanceAlongRoute ?: 0f) }
                val nearestPointAheadOnRoute = nearestPointsAheadOnRoute?.minByOrNull { it.distanceFromPointOnRoute + it.distanceFromRouteStart }

                return nearestPointAheadOnRoute?.let {
                    it.distanceFromPointOnRoute.toDouble() + it.distanceFromRouteStart.toDouble()
                }
            }
        }
    }

    val pois by remember(localPois, globalPois, currentPosition, routeGraphViewModel) {
        val poiList = currentPosition?.let { _ ->
            (localPois + globalPois).filter { poi -> distanceToPoi(poi) != null }.sortedBy { poi ->
                distanceToPoi(poi)
            }
        } ?: run {
            localPois + globalPois
        }

        mutableStateOf(poiList)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedSort.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sort by") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        PoiSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName, style = MaterialTheme.typography.bodyLarge) },
                                onClick = {
                                    selectedSort = option
                                    expanded = false
                                }
                            )
                        }
                    }
                }
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
                        text = poi.name ?: "Unnamed POI",
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
                        karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(poi))
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

class POIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KoinContext {
                AppTheme {
                    PoiScreen() {
                        finish()
                    }
                }
            }
        }
    }
}