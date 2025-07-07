package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import de.timklge.karooroutegraph.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PoiScreen(finish: () -> Unit){
    val coroutineScope = rememberCoroutineScope()
    var showWarnings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(3000L)
        showWarnings = true
    }

    val selectedTabIndex by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) {
                when (it) {
                    0 -> {
                        CustomPoiListScreen()
                    }
                    1 -> {
                        NearbyPoiListScreen()
                    }
                    2 -> {
                        PoiSearchScreen()
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

                Tab(selected = selectedTabIndex == 1, text = { Text("Nearby") }, icon = { Icon(
                    painterResource(R.drawable.bx_info_circle), contentDescription = "Nearby", modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } })

                Tab(selected = selectedTabIndex == 2, text = { Text("Search") }, icon = { Icon(
                    painterResource(R.drawable.bx_search_alt), contentDescription = "Search", modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } })
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
