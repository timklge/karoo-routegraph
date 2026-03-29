package de.timklge.karooroutegraph.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import org.koin.compose.koinInject

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
                onBack = { currentScreen = SettingsScreen.MENU }
            )
        }
        SettingsScreen.GRADIENT_CHEVRONS -> {
            GradientChevronsScreen(
                onBack = { currentScreen = SettingsScreen.MENU }
            )
        }
        SettingsScreen.MINIMAP -> {
            MinimapScreen(
                karooSystemServiceProvider = koinInject(),
                onBack = { currentScreen = SettingsScreen.MENU }
            )
        }
        SettingsScreen.POINTS_OF_INTEREST -> {
            PointsOfInterestScreen(
                onBack = { currentScreen = SettingsScreen.MENU }
            )
        }
        SettingsScreen.TRAFFIC_INCIDENTS -> {
            TrafficIncidentsScreen(
                onBack = { currentScreen = SettingsScreen.MENU }
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
    val karooSystem = KarooSystemServiceProvider(ctx).karooSystemService

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
                        Text(modifier = Modifier.padding(10.dp), text = stringResource(R.string.device_status_warning))
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp, top = 0.dp, end = 10.dp, 10.dp),
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
