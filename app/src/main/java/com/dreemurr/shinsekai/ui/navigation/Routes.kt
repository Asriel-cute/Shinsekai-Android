package com.dreemurr.shinsekai.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SessionsRoute : NavKey

@Serializable
data object CharactersRoute : NavKey

@Serializable
data object VoicesRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

// 新建与编辑共用；sessionId 为空表示新建。
@Serializable
data class SessionSetupRoute(val sessionId: String? = null) : NavKey

@Serializable
data class ChatRoute(val sessionId: String) : NavKey

@Serializable
data class CharacterDetailRoute(val characterId: String) : NavKey

@Serializable
data class VoiceCardEditRoute(val cardId: String) : NavKey

@Serializable
data object VoiceCloneRoute : NavKey
