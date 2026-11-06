package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MusicViewModel
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 101)
            }
        }
        setContent {
            val viewModel: MusicViewModel = viewModel()
            val accentColorStr by viewModel.accentColor.collectAsStateWithLifecycle()

            MyApplicationTheme(accentColorStr = accentColorStr) {
                val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0, pageCount = { 3 })
                val coroutineScope = rememberCoroutineScope()
                var showFullPlayer by remember { mutableStateOf(false) }

                val currentAccentColor = MaterialTheme.colorScheme.primary

                BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
                    val isTablet = maxWidth >= 600.dp

                    if (isTablet) {
                        // TABLET LAYOUT: NavigationRail on left, main content on right
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(
                                containerColor = Color(0xFF161616),
                                contentColor = Color.White,
                                header = {
                                    Icon(
                                        Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = currentAccentColor,
                                        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp).size(28.dp)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                NavigationRailItem(
                                    selected = pagerState.currentPage == 0,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_library)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_library)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = currentAccentColor,
                                        indicatorColor = currentAccentColor,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    ),
                                    modifier = Modifier.testTag("library_tab")
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                NavigationRailItem(
                                    selected = pagerState.currentPage == 1,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_queue)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_queue)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = currentAccentColor,
                                        indicatorColor = currentAccentColor,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    ),
                                    modifier = Modifier.testTag("queue_tab")
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                NavigationRailItem(
                                    selected = pagerState.currentPage == 2,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_settings)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_settings)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = currentAccentColor,
                                        indicatorColor = currentAccentColor,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray
                                    ),
                                    modifier = Modifier.testTag("settings_tab")
                                )
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                androidx.compose.foundation.pager.HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    when (page) {
                                        0 -> LibraryScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                        1 -> QueueScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                        2 -> SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                    }
                                }

                                // Docked Mini Player for tablet
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp, start = 20.dp, end = 20.dp)
                                ) {
                                    MiniPlayer(
                                        viewModel = viewModel,
                                        onExpand = { showFullPlayer = true }
                                    )
                                }
                            }
                        }
                    } else {
                        // PHONE LAYOUT: Bottom NavigationBar
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            bottomBar = {
                                NavigationBar(
                                    containerColor = Color(0xFF121212),
                                    contentColor = Color.White,
                                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                ) {
                                    NavigationBarItem(
                                        selected = pagerState.currentPage == 0,
                                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_library)) },
                                        label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_library)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color.Black,
                                            selectedTextColor = currentAccentColor,
                                            indicatorColor = currentAccentColor,
                                            unselectedIconColor = Color.Gray,
                                            unselectedTextColor = Color.Gray
                                        ),
                                        modifier = Modifier.testTag("library_tab")
                                    )
                                    NavigationBarItem(
                                        selected = pagerState.currentPage == 1,
                                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_queue)) },
                                        label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_queue)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color.Black,
                                            selectedTextColor = currentAccentColor,
                                            indicatorColor = currentAccentColor,
                                            unselectedIconColor = Color.Gray,
                                            unselectedTextColor = Color.Gray
                                        ),
                                        modifier = Modifier.testTag("queue_tab")
                                    )
                                    NavigationBarItem(
                                        selected = pagerState.currentPage == 2,
                                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_settings)) },
                                        label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_settings)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color.Black,
                                            selectedTextColor = currentAccentColor,
                                            indicatorColor = currentAccentColor,
                                            unselectedIconColor = Color.Gray,
                                            unselectedTextColor = Color.Gray
                                        ),
                                        modifier = Modifier.testTag("settings_tab")
                                    )
                                }
                            },
                            containerColor = Color(0xFF121212)
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = innerPadding.calculateBottomPadding())
                            ) {
                                androidx.compose.foundation.pager.HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    when (page) {
                                        0 -> LibraryScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                        1 -> QueueScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                        2 -> SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                    }
                                }

                                val miniPlayerBottomPadding = if (pagerState.currentPage == 0 || pagerState.currentPage == 1) 80.dp else 8.dp
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(bottom = miniPlayerBottomPadding)
                                ) {
                                    MiniPlayer(
                                        viewModel = viewModel,
                                        onExpand = { showFullPlayer = true }
                                    )
                                }
                            }
                        }
                    }
                }

                // Immersive Full Screen Player Overlay (Dialog style)
                if (showFullPlayer) {
                    FullScreenPlayerDialog(
                        viewModel = viewModel,
                        onClose = { showFullPlayer = false }
                    )
                }
            }
        }
    }
}
