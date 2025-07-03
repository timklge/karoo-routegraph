package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.CustomPoiListScreen
import de.timklge.karooroutegraph.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PoiScreen(finish: () -> Unit){
    val coroutineScope = rememberCoroutineScope()
    var showWarnings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1000L)
        showWarnings = true
    }

    val selectedTabIndex by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // TopAppBar(title = { Text("Play") })
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) {
                when (it) {
                    0 -> {
                        CustomPoiListScreen()
                    }
                    1 -> {
                        NearbyPoiListScreen()
                    }
                }
            }

            TabRow(selectedTabIndex = pagerState.currentPage, modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
                divider = { }, // Remove default divider
                indicator = { tabPositions ->
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                                .align(androidx.compose.ui.Alignment.TopStart)
                                .height(2.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            ) {

                Tab(selected = selectedTabIndex == 0, text = { Text("Custom") }, icon = { Icon(
                    painterResource(R.drawable.bx_home), contentDescription = "Custom", modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } })

                /* Tab(selected = selectedTabIndex == 1, text = { Text("Browse") }, icon = { Icon(
                    painterResource(R.drawable.spotify), contentDescription = "Browse", modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }) */

                Tab(selected = selectedTabIndex == 1, text = { Text("Nearby") }, icon = { Icon(
                    painterResource(R.drawable.bx_info_circle), contentDescription = "Nearby", modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } })
            }
        }

        if (showWarnings){
            Image(
                painter = painterResource(id = R.drawable.back),
                contentDescription = "Back",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .clickable {
                        finish()
                    }
            )
        }
    }
}

enum class NearbyPoiCategory(val label: String) {
    RESTAURANTS("Restaurants"),
    GAS_STATIONS("Gas Stations"),
    SUPERMARKETS("Supermarkets"),
    TOILETS("Toilets"),
    SHOWERS("Showers"),
    ATMS("ATMs")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPoiListScreen() {
    var selectedCategories by remember { mutableStateOf(emptySet<NearbyPoiCategory>()) }
    var expanded by remember { mutableStateOf(false) }

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
                        value = if (selectedCategories.isEmpty()) "Select categories" else selectedCategories.joinToString { it.label },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categories") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        NearbyPoiCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedCategories.contains(category),
                                            onCheckedChange = {
                                                selectedCategories = if (selectedCategories.contains(category)) {
                                                    selectedCategories - category
                                                } else {
                                                    selectedCategories + category
                                                }
                                            }
                                        )
                                        Text(category.label)
                                    }
                                },
                                onClick = {
                                    selectedCategories = if (selectedCategories.contains(category)) {
                                        selectedCategories - category
                                    } else {
                                        selectedCategories + category
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
