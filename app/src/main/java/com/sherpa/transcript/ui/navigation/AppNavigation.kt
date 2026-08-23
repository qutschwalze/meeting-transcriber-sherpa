package com.sherpa.transcript.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sherpa.transcript.ui.detail.TranscriptDetailScreen
import com.sherpa.transcript.ui.history.HistoryScreen
import com.sherpa.transcript.ui.live.LiveScreen
import com.sherpa.transcript.ui.settings.SettingsScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

private sealed class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Live : BottomNavItem(
        route = "live",
        label = "Live",
        selectedIcon = Icons.Filled.Mic,
        unselectedIcon = Icons.Outlined.Mic,
    )
    data object History : BottomNavItem(
        route = "history",
        label = "Verlauf",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
    )
    data object Settings : BottomNavItem(
        route = "settings",
        label = "Einstellungen",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )
}

private val bottomNavItems = listOf(
    BottomNavItem.Live,
    BottomNavItem.History,
    BottomNavItem.Settings,
)

/** Routes with arguments */
private const val ROUTE_DETAIL = "detail/{transcriptId}"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Nur Bottom-Navigation zeigen auf Hauptseiten (nicht im Detail)
    val showBottomBar = bottomNavItems.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // Phase 9b (0.9.2): Globales Import-Banner – in ALLEN Tabs sichtbar,
        // damit ein geteiltes Audio nicht lautlos verarbeitet wird.
        val liveVm: com.sherpa.transcript.ui.live.LiveViewModel = viewModel()
        val liveState by liveVm.uiState.collectAsState()

        Column(modifier = Modifier.padding(innerPadding)) {
            if (liveState.importProgress >= 0) {
                val fertig = liveState.importProgress >= 100
                Surface(
                    color = if (fertig) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            progress = { (liveState.importProgress.coerceAtMost(99)) / 100f },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (fertig)
                                "Import abgeschlossen – Transkript liegt im Verlauf"
                            else
                                "Transkribiere '${liveState.importFileName ?: "Audio"}' … ${liveState.importProgress}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (fertig) {
                            TextButton(onClick = { liveVm.dismissImportBanner() }) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = BottomNavItem.Live.route,
            ) {
            composable(BottomNavItem.Live.route) {
                LiveScreen()
            }
            composable(BottomNavItem.History.route) {
                HistoryScreen(
                    onTranscriptClick = { transcriptId ->
                        navController.navigate("detail/$transcriptId")
                    },
                )
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen()
            }

            // Detail-Screen (ohne Bottom-Nav)
            composable(
                route = ROUTE_DETAIL,
                arguments = listOf(
                    navArgument("transcriptId") { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                val transcriptId = backStackEntry.arguments?.getString("transcriptId") ?: ""
                TranscriptDetailScreen(
                    transcriptId = transcriptId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }
    }
}
