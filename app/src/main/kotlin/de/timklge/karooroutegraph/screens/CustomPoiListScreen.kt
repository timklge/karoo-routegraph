package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.LocationViewModelProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService
import de.timklge.karooroutegraph.TravelTimeEstimationService
import de.timklge.karooroutegraph.datatypes.streamPowerPerHour
import de.timklge.karooroutegraph.getOpeningHoursStatusLabel
import de.timklge.karooroutegraph.pois.DistanceToPoiResult
import de.timklge.karooroutegraph.pois.distanceToPoi
import de.timklge.karooroutegraph.pois.getStartAndEndPoiIfNone
import de.timklge.karooroutegraph.pois.processPoiName
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
import java.util.Date
import kotlin.time.DurationUnit

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
    val temporaryPois by karooSystemServiceProvider.streamTemporaryPOIs().collectAsStateWithLifecycle(null)

    val travelTimeEstimationService = koinInject<TravelTimeEstimationService>()
    val surfaceConditionRetrievalService = koinInject<SurfaceConditionRetrievalService>()

    val averagePowerFlow by streamPowerPerHour(karooSystemServiceProvider).collectAsStateWithLifecycle(null)
    val surfaceConditions by surfaceConditionRetrievalService.flow.collectAsStateWithLifecycle(null)
    val userProfile by karooSystemServiceProvider.stream<UserProfile>().collectAsStateWithLifecycle(null)

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

    val viewModel by viewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val currentPosition by locationViewModelProvider.viewModelFlow.collectAsStateWithLifecycle(null)
    val settings by karooSystemServiceProvider.streamSettings().collectAsStateWithLifecycle(null)

    val context = LocalContext.current

    val additionalPois by remember {
        derivedStateOf {
            val route = viewModel?.knownRoute
            val allPois = (localPois + globalPois + tempPois).map { it.poi }

            getStartAndEndPoiIfNone(route, allPois, settings, context, viewModel?.navigatingToDestination == true)
        }
    }

    // State for dropdown
    var expanded by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(PoiSortOption.LINEAR_DISTANCE) }

    LaunchedEffect(Unit) {
        val settings = karooSystemServiceProvider.streamViewSettings().first()
        val savedSort = settings.poiSortOptionForCustomPois

        if (viewModel?.knownRoute == null && savedSort == PoiSortOption.AHEAD_ON_ROUTE) {
            selectedSort = PoiSortOption.LINEAR_DISTANCE
        } else {
            selectedSort = savedSort
        }
    }

    val pois by remember {
        derivedStateOf {
            currentPosition?.let { _ ->
                val poiList = buildList {
                    addAll(localPois)
                    addAll(globalPois)
                    addAll(tempPois)
                    addAll(additionalPois.mapIndexed { index, poi -> DisplayedCustomPoi.Additional(index.toLong(), poi.symbol) })
                }

                val poisSortedByDistance = poiList.filter { displayedCustomPoi -> distanceToPoi(displayedCustomPoi.poi, viewModel?.sampledElevationData, viewModel?.poiDistances, currentPosition, selectedSort, viewModel?.distanceAlongRoute) != null }.sortedBy { displayedCustomPoi ->
                    distanceToPoi(displayedCustomPoi.poi, viewModel?.sampledElevationData, viewModel?.poiDistances, currentPosition, selectedSort, viewModel?.distanceAlongRoute)
                }

                buildList {
                    // If not on route but have a last known position along the route, add it as POI to navigate to
                    if (viewModel?.lastKnownPositionOnMainRoute != null && viewModel?.routeDistance != null && viewModel?.isOnRoute == false && viewModel?.distanceAlongRoute != 0.0f) {
                        val lastKnownPositionAlongRoute = Symbol.POI(
                            lat = viewModel!!.lastKnownPositionOnMainRoute!!.latitude(),
                            lng = viewModel!!.lastKnownPositionOnMainRoute!!.longitude(),
                            name = "Last known position along route", // Will be translated in UI
                            id = "last_known_position",
                        )
                        add(DisplayedCustomPoi.Local(lastKnownPositionAlongRoute))
                    }

                    addAll(poisSortedByDistance)
                }

                poisSortedByDistance
            } ?: run {
                // Include additionalPois even when currentPosition is null
                localPois + globalPois + additionalPois.mapIndexed { index, poi -> DisplayedCustomPoi.Additional(index.toLong(), poi.symbol) }
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            val routeLoaded = viewModel?.knownRoute != null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (routeLoaded) expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = stringResource(selectedSort.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        enabled = routeLoaded,
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

                    val distanceLabel = buildString {
                        currentPosition?.let {
                            val distanceResult = distanceToPoi(displayedCustomPoi.poi, viewModel?.sampledElevationData,
                                viewModel?.poiDistances, it, selectedSort, viewModel?.distanceAlongRoute)

                            append(distanceResult?.formatDistance(LocalContext.current, isImperial))

                            viewModel?.let { viewModel ->
                                val estimatedTravelTime = if (selectedSort == PoiSortOption.AHEAD_ON_ROUTE && viewModel.distanceAlongRoute != null) travelTimeEstimationService.estimateTravelTime(
                                    routeElevationData = viewModel.sampledElevationData,
                                    startDistance = viewModel.distanceAlongRoute.toDouble(),
                                    endDistance = viewModel.distanceAlongRoute.toDouble() + ((distanceResult as? DistanceToPoiResult.AheadOnRouteDistance)?.distanceOnRoute ?: 0.0),
                                    totalWeight = (userProfile?.weight?.toDouble() ?: 70.0) + 10.0,
                                    lastHourAvgPower = averagePowerFlow,
                                    surfaceConditions = surfaceConditions ?: emptyList(),
                                    finalSegmentLength = (distanceResult as? DistanceToPoiResult.AheadOnRouteDistance)?.distanceFromPointOnRoute
                                ) else null
                                val eta = estimatedTravelTime?.let { System.currentTimeMillis() + estimatedTravelTime.toLong(DurationUnit.MILLISECONDS) } ?: System.currentTimeMillis()
                                if (estimatedTravelTime != null) {
                                    append(" ⏲\u00A0${android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(eta))}")
                                }

                                val poiId = displayedCustomPoi.poi.id
                                val openingHours = temporaryPois?.poiIdOpeningHours?.get(poiId)
                                getOpeningHoursStatusLabel(eta, openingHours, context)?.let { statusText ->
                                    append(" ")
                                    append(statusText.uppercase())
                                }
                            }
                        }
                    }

                    if (distanceLabel.isNotEmpty()){
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

        item {
            Spacer(modifier = Modifier.padding(30.dp))
        }
    }
}