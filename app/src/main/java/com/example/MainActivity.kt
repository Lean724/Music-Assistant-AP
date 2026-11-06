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
                var currentTab by remember { mutableStateOf("library") }
                var showFullPlayer by remember { mutableStateOf(false) }

                val currentAccentColor = MaterialTheme.colorScheme.primary

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF121212),
                            contentColor = Color.White,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            NavigationBarItem(
                                selected = currentTab == "library",
                                onClick = { currentTab = "library" },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Biblioteca") },
                                label = { Text("Biblioteca") },
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
                                selected = currentTab == "queue",
                                onClick = { currentTab = "queue" },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Cola") },
                                label = { Text("Cola") },
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
                                selected = currentTab == "settings",
                                onClick = { currentTab = "settings" },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                                label = { Text("Ajustes") },
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
                        // Render Active Screen based on Tab State
                        when (currentTab) {
                            "library" -> LibraryScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                            "queue" -> QueueScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                            "settings" -> SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        }

                        // Floating Mini Player docked above bottom bar
                        val miniPlayerBottomPadding = if (currentTab == "library" || currentTab == "queue") 80.dp else 8.dp
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
