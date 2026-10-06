package com.dreemurr.shinsekai.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SessionsRoute : NavKey

@Serializable
data object CharactersRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object NewSessionRoute : NavKey

@Serializable
data class ChatRoute(val sessionId: String) : NavKey

@Serializable
data class CharacterDetailRoute(val characterId: String) : NavKey
