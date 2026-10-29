package com.dreemurr.shinsekai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.settings.ThemeMode
import com.dreemurr.shinsekai.ui.characters.CharacterDetailScreen
import com.dreemurr.shinsekai.ui.characters.CharactersScreen
import com.dreemurr.shinsekai.ui.chat.ChatScreen
import com.dreemurr.shinsekai.ui.navigation.AppNavigator
import com.dreemurr.shinsekai.ui.navigation.CharacterDetailRoute
import com.dreemurr.shinsekai.ui.navigation.CharactersRoute
import com.dreemurr.shinsekai.ui.navigation.ChatRoute
import com.dreemurr.shinsekai.ui.navigation.SessionsRoute
import com.dreemurr.shinsekai.ui.navigation.SessionSetupRoute
import com.dreemurr.shinsekai.ui.navigation.SettingsRoute
import com.dreemurr.shinsekai.ui.navigation.VoiceCardEditRoute
import com.dreemurr.shinsekai.ui.navigation.VoiceCloneRoute
import com.dreemurr.shinsekai.ui.navigation.VoicesRoute
import com.dreemurr.shinsekai.ui.navigation.rememberNavigationState
import com.dreemurr.shinsekai.ui.onboarding.OnboardingScreen
import com.dreemurr.shinsekai.ui.sessionsetup.SessionSetupScreen
import com.dreemurr.shinsekai.ui.sessions.SessionsScreen
import com.dreemurr.shinsekai.ui.settings.SettingsScreen
import com.dreemurr.shinsekai.ui.theme.ShinsekaiTheme
import com.dreemurr.shinsekai.ui.voices.VoiceCardEditScreen
import com.dreemurr.shinsekai.ui.voices.VoiceCloneScreen
import com.dreemurr.shinsekai.ui.voices.VoicesScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        val container = (application as MainApplication).container
        setContent {
            // 主题模式（跟随系统/浅色/深色）在根部决定明暗。
            val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            ShinsekaiTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val container = LocalAppContainer.current
    val state = rememberNavigationState(
        startRoute = SessionsRoute,
        topLevelRoutes = setOf(SessionsRoute, CharactersRoute, VoicesRoute, SettingsRoute),
    )
    val navigator = remember { AppNavigator(state) }

    // 新手引导状态：null 表示 DataStore 尚未读出；ready 后只读一次仓库现值判断老用户，
    // 不在根节点订阅会话列表，避免流式对话期间整树重组。
    val onboardingCompleted by container.settingsRepository.onboardingCompleted.collectAsStateWithLifecycle()
    val ready by container.ready.collectAsStateWithLifecycle()
    val hasExistingData = remember(ready) {
        ready && (
            container.sessionRepository.sessions.value.isNotEmpty() ||
                container.characterRepository.characters.value.isNotEmpty() ||
                container.voiceCardRepository.cards.value.isNotEmpty()
            )
    }
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
    var onboardingReplay by rememberSaveable { mutableStateOf(false) }

    // 老用户（已有会话/角色/音色卡）升级后不打扰：静默标记引导已完成。
    LaunchedEffect(ready, onboardingCompleted, hasExistingData, onboardingReplay) {
        if (!onboardingReplay && ready && onboardingCompleted == false && hasExistingData) {
            runCatching { container.settingsRepository.completeOnboarding() }
                .onFailure { it.printStackTrace() }
        }
    }

    // 结束/跳过引导：先关界面，完成标记异步落盘；首次启动回会话页，重看留在当前 Tab。
    fun finishOnboarding() {
        val fromReplay = onboardingReplay
        onboardingDismissed = true
        onboardingReplay = false
        container.applicationScope.launch {
            runCatching { container.settingsRepository.completeOnboarding() }
                .onFailure { it.printStackTrace() }
        }
        if (!fromReplay) state.topLevelRoute = SessionsRoute
    }

    // 首启判定统一收敛到一个三态门，条件只写一份。
    val onboardingGate = when {
        onboardingDismissed -> OnboardingGate.Content
        onboardingReplay -> OnboardingGate.Onboarding
        onboardingCompleted == null -> OnboardingGate.Loading
        onboardingCompleted == false && !ready -> OnboardingGate.Loading
        onboardingCompleted == false && !hasExistingData -> OnboardingGate.Onboarding
        else -> OnboardingGate.Content
    }

    when (onboardingGate) {
        // 首启判定未出结果前用纯背景占位，避免先闪一下会话页。
        OnboardingGate.Loading -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {}
            return
        }

        OnboardingGate.Onboarding -> {
            OnboardingScreen(onFinish = { finishOnboarding() })
            return
        }

        OnboardingGate.Content -> Unit
    }

    val provider = entryProvider {
        entry<SessionsRoute> {
            SessionsScreen(
                onNewSession = { navigator.navigate(SessionSetupRoute()) },
                // 历史会话先进设置页，保存后继续对话。
                onOpenSession = { navigator.navigate(SessionSetupRoute(it)) },
            )
        }
        entry<CharactersRoute> {
            CharactersScreen(onOpenDetail = { navigator.navigate(CharacterDetailRoute(it)) })
        }
        entry<VoicesRoute> {
            VoicesScreen(
                onAddCard = { navigator.navigate(VoiceCardEditRoute("")) },
                onOpenEdit = { navigator.navigate(VoiceCardEditRoute(it)) },
                onClone = { navigator.navigate(VoiceCloneRoute) },
            )
        }
        entry<SettingsRoute> {
            SettingsScreen(
                onShowOnboarding = {
                    onboardingDismissed = false
                    onboardingReplay = true
                },
            )
        }
        entry<SessionSetupRoute> { key ->
            SessionSetupScreen(
                sessionId = key.sessionId,
                onBack = { navigator.goBack() },
                onSaved = { id -> navigator.popAndNavigate(ChatRoute(id)) },
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
        entry<VoiceCardEditRoute> { key ->
            VoiceCardEditScreen(
                cardId = key.cardId,
                onBack = { navigator.goBack() },
            )
        }
        entry<VoiceCloneRoute> {
            VoiceCloneScreen(onBack = { navigator.goBack() })
        }
    }

    // 子页面隐藏底部导航栏。
    val showBottomBar = state.backStacks[state.topLevelRoute]?.lastOrNull() == state.topLevelRoute

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
        )
    }
}

private data class TopLevelItem(
    val route: NavKey,
    val labelRes: Int,
    val icon: ImageVector,
)

// 首启引导三态门：Loading=首启判定中，Onboarding=展示引导，Content=主界面。
private enum class OnboardingGate { Loading, Onboarding, Content }

private val TopLevelItems = listOf(
    TopLevelItem(SessionsRoute, R.string.tab_sessions, Icons.Filled.Home),
    TopLevelItem(CharactersRoute, R.string.tab_characters, Icons.Filled.Person),
    TopLevelItem(VoicesRoute, R.string.tab_voices, Icons.Filled.PlayArrow),
    TopLevelItem(SettingsRoute, R.string.tab_settings, Icons.Filled.Settings),
)
