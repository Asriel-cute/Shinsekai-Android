package com.dreemurr.shinsekai

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.characters.CharacterDetailScreen
import com.dreemurr.shinsekai.ui.characters.CharactersScreen
import com.dreemurr.shinsekai.ui.chat.ChatScreen
import com.dreemurr.shinsekai.ui.navigation.AppNavigator
import com.dreemurr.shinsekai.ui.navigation.CharacterDetailRoute
import com.dreemurr.shinsekai.ui.navigation.CharactersRoute
import com.dreemurr.shinsekai.ui.navigation.ChatRoute
import com.dreemurr.shinsekai.ui.navigation.NewSessionRoute
import com.dreemurr.shinsekai.ui.navigation.SessionsRoute
import com.dreemurr.shinsekai.ui.navigation.SettingsRoute
import com.dreemurr.shinsekai.ui.navigation.navTransitionSpecs
import com.dreemurr.shinsekai.ui.navigation.rememberNavigationState
import com.dreemurr.shinsekai.ui.newsession.NewSessionScreen
import com.dreemurr.shinsekai.ui.sessions.SessionsScreen
import com.dreemurr.shinsekai.ui.settings.SettingsScreen
import com.dreemurr.shinsekai.ui.theme.ShinsekaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val container = (application as MainApplication).container
        setContent {
            ShinsekaiTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val state = rememberNavigationState(
        startRoute = SessionsRoute,
        topLevelRoutes = setOf(SessionsRoute, CharactersRoute, SettingsRoute),
    )
    val navigator = remember { AppNavigator(state) }

    val provider = entryProvider {
        entry<SessionsRoute> {
            SessionsScreen(
                onNewSession = { navigator.navigate(NewSessionRoute) },
                onOpenSession = { navigator.navigate(ChatRoute(it)) },
            )
        }
        entry<CharactersRoute> {
            CharactersScreen(onOpenDetail = { navigator.navigate(CharacterDetailRoute(it)) })
        }
        entry<SettingsRoute> { SettingsScreen() }
        entry<NewSessionRoute> {
            NewSessionScreen(
                onBack = { navigator.goBack() },
                onCreated = { id -> navigator.popAndNavigate(ChatRoute(id)) },
                onGoImport = { navigator.navigate(CharactersRoute) },
            )
        }
        entry<ChatRoute> { key ->
            ChatScreen(
                sessionId = key.sessionId,
                onBack = { navigator.goBack() },
                onOpenSettings = { navigator.navigate(SettingsRoute) },
            )
        }
        entry<CharacterDetailRoute> { key ->
            CharacterDetailScreen(
                characterId = key.characterId,
                onBack = { navigator.goBack() },
            )
        }
    }

    // 子页面隐藏底部导航栏。
    val showBottomBar = state.backStacks[state.topLevelRoute]?.lastOrNull() == state.topLevelRoute

    val tabOrder = remember { TopLevelItems.map { it.route } }
    val transitions = remember(tabOrder, state.backStacks) {
        navTransitionSpecs(tabOrder, state.backStacks)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelItems.forEach { (route, labelRes, icon) ->
                        val label = stringResource(labelRes)
                        NavigationBarItem(
                            selected = state.topLevelRoute == route,
                            onClick = { navigator.navigate(route) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            entries = state.toEntries(provider),
            onBack = { navigator.goBack() },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                // 底栏高度已含导航栏 inset，消费掉避免子页面 Scaffold 重复叠加底部留白。
                .consumeWindowInsets(padding),
            transitionSpec = transitions.forward,
            popTransitionSpec = transitions.pop,
            predictivePopTransitionSpec = transitions.predictivePop,
        )
    }
}

private data class TopLevelItem(
    val route: NavKey,
    val labelRes: Int,
    val icon: ImageVector,
)

private val TopLevelItems = listOf(
    TopLevelItem(SessionsRoute, R.string.tab_sessions, Icons.Filled.Home),
    TopLevelItem(CharactersRoute, R.string.tab_characters, Icons.Filled.Person),
    TopLevelItem(SettingsRoute, R.string.tab_settings, Icons.Filled.Settings),
)
