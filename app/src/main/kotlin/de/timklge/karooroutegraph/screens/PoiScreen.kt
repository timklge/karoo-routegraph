package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun PoiScreen(finish: () -> Unit){
    val coroutineScope = rememberCoroutineScope()
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    var initialPage by remember { mutableIntStateOf(1) }
    var initialPageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        initialPage = karooSystemServiceProvider.streamViewSettings().first().lastPoiTab
        initialPageLoaded = true
    }

    if (!initialPageLoaded) return

    val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = initialPage)

    LaunchedEffect(pagerState.currentPage) {
        karooSystemServiceProvider.saveViewSettings { it.copy(lastPoiTab = pagerState.currentPage) }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
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
                                .align(Alignment.BottomStart)
                                .height(2.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            ) {

                Tab(selected = pagerState.currentPage == 0, text = { Text(stringResource(R.string.poi_tab_custom)) }, icon = { Icon(
                    painterResource(R.drawable.bx_home), contentDescription = stringResource(R.string.poi_tab_custom), modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } })

                Tab(selected = pagerState.currentPage == 1, text = { Text(stringResource(R.string.poi_tab_nearby)) }, icon = { Icon(
                    painterResource(R.drawable.bx_info_circle), contentDescription = stringResource(R.string.poi_tab_nearby), modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } })

                Tab(selected = pagerState.currentPage == 2, text = { Text(stringResource(R.string.poi_tab_search)) }, icon = { Icon(
                    painterResource(R.drawable.bx_search_alt), contentDescription = stringResource(R.string.poi_tab_search), modifier = Modifier
                        .size(30.dp)
                        .padding(2.dp)
                ) }, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } })
            }

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
        }

        FixedBackButton(
            modifier = Modifier.align(Alignment.BottomStart),
            onBack = { finish() }
        )
    }
}
