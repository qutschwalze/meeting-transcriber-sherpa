package com.sherpa.transcript.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sherpa.transcript.ui.settings.ContactsSection
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
private const val ROUTE_CONTACTS = "contacts"

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
        // Phase 9c (0.9.3): Globales Import-Banner über ALLEN Tabs – hängt an der
        // ImportUiBridge (Prozess-Singleton), damit es den Fortschritt sieht,
        // egal welche ViewModel-Instanz den Import ausführt.
        val importProgress by com.sherpa.transcript.ui.live.ImportUiBridge.progress.collectAsState()
        val importFileName by com.sherpa.transcript.ui.live.ImportUiBridge.fileName.collectAsState()

        Column(modifier = Modifier.padding(innerPadding)) {
            if (importProgress >= 0) {
                val fertig = importProgress >= 100
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
                            progress = { (importProgress.coerceAtMost(99)) / 100f },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (fertig)
                                "Import abgeschlossen – Segmente unten zuweisen oder überspringen"
                            else
                                "Transkribiere '${importFileName ?: "Audio"}' … ${importProgress}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (fertig) {
                            // Phase 9c: „Benennen" springt zum Live-Tab, wo die
                            // Segment-Taps das akustische ENROLL aus dem Puffer machen
                            TextButton(onClick = {
                                com.sherpa.transcript.ui.live.ImportUiBridge.dismiss()
                                navController.navigate(BottomNavItem.Live.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                                    launchSingleTop = true
                                }
                            }) {
                                Text("Benennen")
                            }
                            TextButton(onClick = {
                                com.sherpa.transcript.ui.live.ImportUiBridge.dismiss()
                            }) {
                                Text("Überspringen")
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
                SettingsScreen(
                    onNavigateToContacts = { navController.navigate(ROUTE_CONTACTS) },
                )
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

            // Contacts-Screen (Einstellungen → Kontakte)
            @OptIn(ExperimentalMaterial3Api::class)
            composable(ROUTE_CONTACTS) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Kontakte") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                        ContactsSection()
                    }
                }
            }
        }
        }
    }
}
