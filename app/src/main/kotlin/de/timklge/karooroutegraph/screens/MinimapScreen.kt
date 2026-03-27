package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimapScreen(
    karooSystemServiceProvider: KarooSystemServiceProvider,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var minimapNightMode by remember { mutableStateOf(true) }
    var showPOIsOnMinimap by remember { mutableStateOf(true) }

    suspend fun updateSettings(){
        karooSystemServiceProvider.saveSettings { settings ->
            settings.copy(
                minimapNightMode = minimapNightMode,
                showPOILabelsOnMinimap = showPOIsOnMinimap
            )
        }
    }

    LaunchedEffect(Unit) {
        karooSystemServiceProvider.streamSettings().collect { settings ->
            minimapNightMode = settings.minimapNightMode
            showPOIsOnMinimap = settings.showPOILabelsOnMinimap
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.minimap))}) },
        content = {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(it)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = minimapNightMode, onCheckedChange = {
                            minimapNightMode = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.minimap_night_mode))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showPOIsOnMinimap, onCheckedChange = {
                            showPOIsOnMinimap = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_poi_labels))
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