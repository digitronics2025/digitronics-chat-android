package com.digitronics.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.digitronics.chat.AppContainer
import com.digitronics.chat.MainActivity
import com.digitronics.chat.auth.SessionState
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.ThemeMode
import com.digitronics.chat.ui.chats.ChatListScreen
import com.digitronics.chat.ui.chats.ChatListViewModel
import com.digitronics.chat.ui.settings.AboutScreen
import com.digitronics.chat.ui.settings.DiagnosticsScreen
import com.digitronics.chat.ui.settings.LicensesScreen
import com.digitronics.chat.ui.settings.SettingsScreen
import com.digitronics.chat.ui.settings.UpdatesScreen
import com.digitronics.chat.ui.signin.SignInScreen
import com.digitronics.chat.ui.theme.DigitronicsTheme
import com.digitronics.chat.ui.thread.ThreadScreen
import com.digitronics.chat.ui.thread.ThreadViewModel

private object Routes {
    const val CHATS = "chats"
    const val THREAD = "thread/{conversationId}"
    const val SETTINGS = "settings"
    const val UPDATES = "updates"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val DIAGNOSTICS = "diagnostics"

    fun thread(id: String) = "thread/" + android.net.Uri.encode(id)
}

@Composable
fun AppRoot(container: AppContainer) {
    val theme by container.prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val session by container.session.state.collectAsState()
    DigitronicsTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val state = session) {
                SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is SessionState.SignedOut -> SignInScreen(container, state.notice)
                // Keyed by account: a different person never inherits screens or view models.
                is SessionState.SignedIn -> key(state.account.key) { SignedInNav(container, state.account) }
            }
        }
    }
}

@Composable
private fun SignedInNav(container: AppContainer, account: Account) {
    val nav = rememberNavController()
    val pendingConversation by container.pendingConversation.collectAsState()
    val pendingDestination by container.pendingDestination.collectAsState()

    // A notification tap opens its thread only now, with a live session (plan §5.4).
    LaunchedEffect(pendingConversation) {
        val id = pendingConversation ?: return@LaunchedEffect
        container.pendingConversation.value = null
        nav.navigate(Routes.thread(id)) { launchSingleTop = true }
    }
    LaunchedEffect(pendingDestination) {
        val destination = pendingDestination ?: return@LaunchedEffect
        container.pendingDestination.value = null
        if (destination == MainActivity.DESTINATION_UPDATES) nav.navigate(Routes.UPDATES) { launchSingleTop = true }
    }

    NavHost(navController = nav, startDestination = Routes.CHATS) {
        composable(Routes.CHATS) {
            val vm: ChatListViewModel = viewModel(factory = viewModelFactory { initializer { ChatListViewModel(container, account) } })
            ChatListScreen(container, vm, onOpen = { nav.navigate(Routes.thread(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.THREAD, arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("conversationId").orEmpty()
            val vm: ThreadViewModel = viewModel(factory = viewModelFactory { initializer { ThreadViewModel(container, account, id) } })
            ThreadScreen(container, vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                account = account,
                onBack = { nav.popBackStack() },
                onUpdates = { nav.navigate(Routes.UPDATES) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.UPDATES) { UpdatesScreen(container, onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() }, onUpdates = { nav.navigate(Routes.UPDATES) }, onLicenses = { nav.navigate(Routes.LICENSES) })
        }
        composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
