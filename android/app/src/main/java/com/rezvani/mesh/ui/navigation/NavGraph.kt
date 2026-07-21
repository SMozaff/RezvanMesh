// android/app/src/main/java/com/rezvani/mesh/ui/navigation/NavGraph.kt

package com.rezvani.mesh.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.screens.*

@Composable
fun NavGraph(
    navController: NavHostController,
    meshConnection: MeshServiceConnection,
    modifier: Modifier = Modifier
) {
    fun NavHostController.switchTab(route: String) = navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    NavHost(
        navController = navController,
        startDestination = "network",   // simplified Network page is the home/startup
        modifier = modifier
    ) {
        // ---------- Primary tabs ----------
        composable("network") {
            NetworkScreen(
                onOpenAdvanced = { navController.navigate("advanced_monitoring?from=network") }
            )
        }
        composable("chats") {
            ChatsScreen(
                onConversationClick = { id, name -> navController.navigate("chat_detail/$id/${Uri.encode(name)}") },
                onNewMessageClick = { navController.navigate("contacts") },
                onNewChannelClick = { navController.switchTab("channels") },
                onEmergencyClick = { navController.switchTab("emergency") },
                onVoiceClick = { navController.navigate("voice") }
            )
        }
        composable("channels") {
            ChannelsScreen(
                onChannelClick = { id, name -> navController.navigate("channel_detail/$id/${Uri.encode(name)}") },
                onCreateChannel = { navController.navigate("create_channel") }
            )
        }
        composable("emergency") { EmergencyScreen() }
        composable("settings") {
            SettingsScreen(
                onNavigateToAdvanced = { navController.navigate("advanced_monitoring?from=settings") },
                onNavigateToDiagnostics = { navController.navigate("diagnostics") }
            )
        }

        // ---------- Secondary routes ----------
        composable(
            route = "advanced_monitoring?from={from}",
            arguments = listOf(navArgument("from") {
                type = NavType.StringType; defaultValue = "network"
            })
        ) { entry ->
            val from = entry.arguments?.getString("from") ?: "network"
            AdvancedNetworkScreen(
                onNavigateBack = {
                    // Return to correct origin: network tab or settings
                    when (from) {
                        "settings" -> navController.switchTab("settings")
                        else       -> navController.switchTab("network")
                    }
                },
                // Plain push (not switchTab): diagnostics isn't a bottom-nav
                // tab, it's a secondary screen, so a normal popBackStack()
                // from it correctly lands back on THIS Advanced Monitoring
                // screen -- not on whatever tab Advanced Monitoring itself
                // was opened from. Settings already reaches diagnostics the
                // same way (plain navigate("diagnostics")), so both entry
                // points share one correctly-behaving back button.
                onNavigateToDiagnostics = { navController.navigate("diagnostics") }
            )
        }
        composable(
            route = "chat_detail/{conversationId}/{contactName}",
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("contactName") { type = NavType.StringType }
            )
        ) { entry ->
            ChatDetailScreen(
                conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                contactName = entry.arguments?.getString("contactName").orEmpty(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "channel_detail/{channelId}/{channelName}",
            arguments = listOf(
                navArgument("channelId") { type = NavType.IntType },
                navArgument("channelName") { type = NavType.StringType }
            )
        ) { entry ->
            ChannelDetailScreen(
                channelId = entry.arguments?.getInt("channelId") ?: 0,
                channelName = entry.arguments?.getString("channelName").orEmpty(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("create_channel") {
            CreateChannelScreen(
                onNavigateBack = { navController.popBackStack() },
                onChannelCreated = { navController.popBackStack() },
                onScanChannelQr = { navController.navigate("qr_scanner/channel") },
                channelQrScanResult = navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.getStateFlow<String?>("qr_scan_result_channel", null)
            )
        }
        // NOTE: "messages" route removed -- nothing anywhere in the app
        // navigated to it (confirmed via full-repo search), and it appears
        // to be an earlier prototype superseded by the real ChatsScreen /
        // ChatDetailScreen pipeline (chats tab + chat_detail/{id}/{name}).
        // MessagesScreen.kt itself was left untouched in case it's still
        // wanted for something -- only the unreachable route was removed.
        composable("contacts") {
            ContactsScreen(
                meshConnection = meshConnection,
                onOpenChat = { convId, name ->
                    navController.navigate("chat_detail/$convId/${android.net.Uri.encode(name)}")
                },
                onScanQr = { navController.navigate("qr_scanner/contact") },
                contactQrScanResult = navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.getStateFlow<String?>("qr_scan_result_contact", null),
                onNavigateBackToChats = { navController.popBackStack() }
            )
        }
        composable(
            route = "qr_scanner/{purpose}",
            arguments = listOf(navArgument("purpose") { type = NavType.StringType })
        ) { entry ->
            val purpose = entry.arguments?.getString("purpose") ?: "contact"
            QrScannerScreen(
                title = if (purpose == "channel") stringResource(com.rezvani.mesh.R.string.scan_channel_qr)
                        else stringResource(com.rezvani.mesh.R.string.scan_contact_qr),
                prompt = if (purpose == "channel") stringResource(com.rezvani.mesh.R.string.scan_channel_qr_prompt)
                         else stringResource(com.rezvani.mesh.R.string.scan_contact_qr_prompt),
                onResult = { decoded ->
                    // Standard Compose Navigation "return a result" pattern:
                    // the previous back-stack entry's SavedStateHandle. The
                    // destination screen (Contacts or CreateChannel) reads
                    // this on resume -- see their LaunchedEffect blocks below.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("qr_scan_result_$purpose", decoded)
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable("diagnostics") { DiagnosticsScreen(onNavigateBack = { navController.popBackStack() }) }
        composable("voice") { VoiceScreen(onNavigateBack = { navController.popBackStack() }) }
    }
}