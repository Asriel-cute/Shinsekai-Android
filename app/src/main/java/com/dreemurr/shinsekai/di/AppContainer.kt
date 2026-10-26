package com.dreemurr.shinsekai.di

import android.content.Context
import com.dreemurr.shinsekai.character.CharacterRepository
import com.dreemurr.shinsekai.chat.ChatRepository
import com.dreemurr.shinsekai.session.SessionRepository
import com.dreemurr.shinsekai.settings.SettingsRepository
import com.dreemurr.shinsekai.voice.TtsRepository
import com.dreemurr.shinsekai.voice.VoiceBindings
import com.dreemurr.shinsekai.voice.VoiceCardRepository
import com.dreemurr.shinsekai.voice.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

// applicationScope 承接 ViewModel 销毁后的落盘；角色/会话/声音卡片/绑定仓库加载后置 ready。
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val settingsRepository = SettingsRepository(appContext, applicationScope)
    val characterRepository = CharacterRepository(File(appContext.filesDir, "characters"))
    val sessionRepository = SessionRepository(File(appContext.filesDir, "sessions"))
    val chatRepository = ChatRepository()
    val voicePlayer = VoicePlayer()
    val voiceCardRepository = VoiceCardRepository(File(appContext.filesDir, "voice_cards.json"))
    val voiceBindings = VoiceBindings(File(appContext.filesDir, "voice_bindings.json"))
    val ttsRepository = TtsRepository()

    /** 仅由 MainApplication 启动流程调用 */
    fun markReady() {
        _ready.value = true
    }
}
