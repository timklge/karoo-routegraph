package de.timklge.karooroutegraph.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.distanceToPoi
import de.timklge.karooroutegraph.getStartAndEndPoiIfNone
import de.timklge.karooroutegraph.processPoiName
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

enum class PoiSortOption(val displayNameRes: Int) {
    LINEAR_DISTANCE(R.string.sort_linear_distance),
    AHEAD_ON_ROUTE(R.string.sort_ahead_on_route);
}

sealed class DisplayedCustomPoi {
    abstract val poi: Symbol.POI

    data class Local(override val poi: Symbol.POI) : DisplayedCustomPoi()
    data class Global(override val poi: Symbol.POI) : DisplayedCustomPoi()
    data class Temporary(val id: Long, override val poi: Symbol.POI) : DisplayedCustomPoi()
    data class Additional(val id: Long, override val poi: Symbol.POI) : DisplayedCustomPoi()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPoiListScreen() {
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    val viewModelProvider = koinInject<RouteGraphViewModelProvider>()
    val locationViewModelProvider = koinInject<LocationViewModelProvider>()

    var localPois by remember { mutableStateOf<List<DisplayedCustomPoi.Local>>(listOf()) }
    var globalPois by remember { mutableStateOf<List<DisplayedCustomPoi.Global>>(listOf()) }
    var tempPois by remember { mutableStateOf<List<DisplayedCustomPoi.Temporary>>(listOf()) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<OnNavigationState>()
            .mapNotNull { onNavigationState ->
                val newLocalPois = (onNavigationState.state as? OnNavigationState.NavigationState.NavigatingRoute)?.pois
                newLocalPois
            }
            .collect {
                localPois = it.map { poi -> DisplayedCustomPoi.Local(poi) }
            }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<OnGlobalPOIs>()
            .map { onGlobalPois ->
                onGlobalPois.pois.map { poi ->
                    poi.copy(name = processPoiName(poi.name))
                }
            }
            .collect {
                globalPois = it.map { poi -> DisplayedCustomPoi.Global(poi) }
            }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.streamTemporaryPOIs()
            .collect { temporaryPOIs ->
                tempPois = temporaryPOIs.poisByOsmId.map { (id, poi) -> DisplayedCustomPoi.Temporary(id, poi) }
            }
    }

    var isImperial by remember { mutableStateOf(false)}

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.stream<UserProfile>()
            .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
            .collect { isImperial = it }
    }

    val routeGraphViewModel by viewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val settings by karooSystemServiceProvider.streamSettings().collectAsStateWithLifecycle(null)

    val context = LocalContext.current

    val additionalPois by remember {
        derivedStateOf {
            val route = routeGraphViewModel?.knownRoute
            val allPois = (localPois + globalPois + tempPois).map { it.poi }

            // Debug logging
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: additionalPois calculation")
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: route is null: ${route == null}")
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: route coordinates count: ${route?.coordinates()?.size ?: 0}")
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: existing POIs count: ${allPois.size}")
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: settings is null: ${settings == null}")

            val result = getStartAndEndPoiIfNone(route, allPois, settings, context)
            Log.d(KarooRouteGraphExtension.TAG, "DEBUG: additionalPois result count: ${result.size}")

            result
        }
    }

    // State for dropdown
    var expanded by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(PoiSortOption.LINEAR_DISTANCE) }

    LaunchedEffect(Unit) {
        val settings = karooSystemServiceProvider.streamViewSettings().first()
        selectedSort = settings.poiSortOptionForCustomPois
    }

    val pois by remember {
        derivedStateOf {
            currentPosition?.let { _ ->
                val poiList = buildList {
                    addAll(localPois)
                    addAll(globalPois)
                    addAll(tempPois)
                    addAll(additionalPois.mapIndexed { index, poi -> DisplayedCustomPoi.Additional(index.toLong(), poi.symbol) })

                    // If not on route but have a last known position along the route, add it as POI to navigate to
                    if (routeGraphViewModel?.lastKnownPositionOnMainRoute != null && routeGraphViewModel?.routeDistance == null) {
                        val lastKnownPositionAlongRoute = Symbol.POI(
                            lat = routeGraphViewModel!!.lastKnownPositionOnMainRoute!!.latitude(),
                            lng = routeGraphViewModel!!.lastKnownPositionOnMainRoute!!.longitude(),
                            name = "Last known position along route", // Will be translated in UI
                            id = "last_known_position",
                        )
                        add(DisplayedCustomPoi.Local(lastKnownPositionAlongRoute))
                    }
                }

                poiList.filter { displayedCustomPoi -> distanceToPoi(displayedCustomPoi.poi, routeGraphViewModel?.sampledElevationData, routeGraphViewModel?.poiDistances, currentPosition, selectedSort, routeGraphViewModel?.distanceAlongRoute) != null }.sortedBy { displayedCustomPoi ->
                    distanceToPoi(displayedCustomPoi.poi, routeGraphViewModel?.sampledElevationData, routeGraphViewModel?.poiDistances, currentPosition, selectedSort, routeGraphViewModel?.distanceAlongRoute)
                }
            } ?: run {
                // Include additionalPois even when currentPosition is null
                localPois + globalPois + additionalPois.mapIndexed { index, poi -> DisplayedCustomPoi.Additional(index.toLong(), poi.symbol) }
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = stringResource(selectedSort.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.sort_by)) },
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
                                text = { Text(stringResource(option.displayNameRes), style = MaterialTheme.typography.bodyLarge) },
                                onClick = {
                                    selectedSort = option
                                    expanded = false

                                    coroutineScope.launch {
                                        karooSystemServiceProvider.saveViewSettings { settings ->
                                            settings.copy(poiSortOptionForCustomPois = option)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        items(pois) { displayedCustomPoi ->
            var showContextMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val displayName = if (displayedCustomPoi.poi.id == "last_known_position") {
                        stringResource(R.string.last_known_position)
                    } else {
                        displayedCustomPoi.poi.name ?: stringResource(R.string.unnamed_poi)
                    }

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val distanceLabel = currentPosition?.let {
                        val distanceResult = distanceToPoi(displayedCustomPoi.poi, routeGraphViewModel?.sampledElevationData,
                            routeGraphViewModel?.poiDistances, it, selectedSort, routeGraphViewModel?.distanceAlongRoute)

                        distanceResult?.formatDistance(LocalContext.current, isImperial)
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
                                karooSystemServiceProvider.karooSystemService.dispatch(LaunchPinDrop(displayedCustomPoi.poi))
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.bxmap),
                                    contentDescription = stringResource(R.string.navigate)
                                )
                            }
                        )

                        if (displayedCustomPoi is DisplayedCustomPoi.Temporary) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_from_map)) },
                                onClick = {
                                    showContextMenu = false
                                    coroutineScope.launch {
                                        karooSystemServiceProvider.saveTemporaryPOIs {
                                            it.copy(poisByOsmId = it.poisByOsmId - displayedCustomPoi.id)
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
                    }
                }
            }
        }
    }
}