// android/app/src/main/java/com/rezvani/mesh/ui/navigation/MainScreenWithBottomNav.kt

package com.rezvani.mesh.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rezvani.mesh.MeshServiceConnection

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

// Network sits in the center (spec section 5). Startup destination is "network".
private val tabs = listOf(
    TabItem("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    TabItem("channels", "Channels", Icons.Filled.Forum),
    TabItem("network", "Network", Icons.Filled.Hub),
    TabItem("emergency", "Emergency", Icons.Filled.Warning),
    TabItem("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun MainScreenWithBottomNav() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val meshConnection = remember { MeshServiceConnection(context.applicationContext) }
    DisposableEffect(meshConnection) {
        MeshServiceConnection.registerConnection(meshConnection)
        onDispose { }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val tabRoutes = remember { tabs.map { it.route } }
    val showBottomBar = currentDestination?.route in tabRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavGraph(
            navController = navController,
            meshConnection = meshConnection,
            modifier = Modifier.padding(innerPadding)
        )
    }
}