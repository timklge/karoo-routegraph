package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.GradientIndicatorFrequency
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.streamSettings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradientChevronsScreen(
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showGradientIndicatorsOnMap by remember { mutableStateOf(false) }
    var gradientIndicatorFrequency by remember { mutableStateOf(GradientIndicatorFrequency.HIGH) }
    val karooSystem = koinInject<KarooSystemServiceProvider>()

    suspend fun updateSettings() {
        karooSystem.saveSettings { settings ->
            settings.copy(
                showGradientIndicatorsOnMap = showGradientIndicatorsOnMap,
                gradientIndicatorFrequency = gradientIndicatorFrequency
            )
        }
    }

    LaunchedEffect(Unit) {
        ctx.streamSettings(karooSystem.karooSystemService).collect { settings ->
            showGradientIndicatorsOnMap = settings.showGradientIndicatorsOnMap
            gradientIndicatorFrequency = settings.gradientIndicatorFrequency
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = {Text(stringResource(R.string.gradient_chevrons))}) },
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
                        Switch(checked = showGradientIndicatorsOnMap, onCheckedChange = {
                            showGradientIndicatorsOnMap = it
                            coroutineScope.launch { updateSettings() }
                        })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.show_gradient_indicators))
                    }

                    if (showGradientIndicatorsOnMap) {
                        val frequencies = GradientIndicatorFrequency.entries.toTypedArray()
                        val selectedIndex = frequencies.indexOf(gradientIndicatorFrequency)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.amount_of_indicators))
                            Slider(
                                value = selectedIndex.toFloat(),
                                onValueChange = { idx ->
                                    val newIndex = idx.roundToInt().coerceIn(frequencies.indices)
                                    gradientIndicatorFrequency = frequencies[newIndex]
                                    coroutineScope.launch { updateSettings() }
                                },
                                valueRange = 0f..(frequencies.size - 1).toFloat(),
                                steps = frequencies.size - 2,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                frequencies.forEach { freq ->
                                    Text(stringResource(freq.labelResourceId), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
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