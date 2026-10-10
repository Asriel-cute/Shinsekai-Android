package com.dreemurr.shinsekai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.ChatStreamEvent
import com.dreemurr.shinsekai.chat.Dialog
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.session.SessionEntry
import com.dreemurr.shinsekai.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

// 流终止即落盘，不等待播放；销毁时用 applicationScope 补存残片。
class ChatViewModel(
    private val sessionId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val sessionRepo = container.sessionRepository
    private val characterRepo = container.characterRepository
    private val chatRepo = container.chatRepository
    private val voicePlayer = container.voicePlayer

    enum class Phase { WaitingInput, Streaming, Playing, Error }

    enum class LineKind { Character, Narration, Stranger }

    data class DisplayLine(
        val name: String,
        val speech: String,
        val kind: LineKind,
        val speakerColorHex: String?,
    )

    data class SpriteView(
        val characterId: String,
        val spriteIndex: Int, // 0 基
        val imageFile: File?,
        val spriteScale: Float,
        val dimmed: Boolean,
    )

    data class UiState(
        val loading: Boolean = true,
        val title: String = "",
        val phase: Phase = Phase.WaitingInput,
        val line: DisplayLine? = null,
        val typing: Boolean = false,
        val queueSize: Int = 0,
        val streaming: Boolean = false,
        val sprite: SpriteView? = null,
        val error: String? = null,
        val notConfigured: Boolean = false,
        val history: List<SessionEntry> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    // 高频打字更新仅重组台词组件。
    private val _visibleSpeech = MutableStateFlow("")
    val visibleSpeech = _visibleSpeech.asStateFlow()

    // 错误留到队列播完后展示。
    private sealed interface Terminal {
        data object Done : Terminal
        data object Stopped : Terminal
        data class Failed(val message: String) : Terminal
    }

    private val charactersByName = mutableMapOf<String, Character>()
    private val queue = ArrayDeque<Dialog>()
    private val spriteMemory = mutableMapOf<String, Int>()
    private var terminal: Terminal? = null
    private var parsedAnyDialog = false
    private var streamJob: Job? = null
    private var typewriterJob: Job? = null

    init {
        // 等启动加载完成，再回放历史重建立绘记忆。
        viewModelScope.launch {
            container.ready.first { it }
            val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId }
            if (session != null) {
                val byId = characterRepo.characters.value.associateBy { it.id }
                for (id in session.characterIds) {
                    val character = byId[id] ?: continue
                    charactersByName.putIfAbsent(character.name, character)
                }
                replayHistory(session)
                _state.update { it.copy(title = session.title, history = session.history) }
            }
            _state.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            sessionRepo.sessions
                .mapNotNull { list -> list.firstOrNull { it.id == sessionId } }
                .distinctUntilChanged()
                .collect { session ->
                    _state.update { it.copy(title = session.title, history = session.history) }
                }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !checkConfigured()) return
        viewModelScope.launch {
            sessionRepo.appendEntry(sessionId, SessionEntry(role = Role.user, content = trimmed))
            startStream()
        }
    }

    // 重试复用 history 中的用户消息。
    fun retry() {
        if (checkConfigured()) startStream()
    }

    fun stop() {
        chatRepo.cancel()
    }

    fun consumeNotConfigured() {
        _state.update { it.copy(notConfigured = false) }
    }

    fun dismissError() {
        _state.update { it.copy(phase = Phase.WaitingInput, error = null) }
    }

    fun onBoxTap() {
        if (_state.value.typing) {
            completeTypewriter()
            return
        }
        if (queue.isNotEmpty()) {
            advanceInternal()
            return
        }
        when (val result = terminal) {
            Terminal.Done, Terminal.Stopped ->
                _state.update { it.copy(phase = Phase.WaitingInput) }

            is Terminal.Failed ->
                _state.update { it.copy(phase = Phase.Error, error = result.message) }

            null -> Unit // 流未结束，等待新 dialog。
        }
    }

    override fun onCleared() {
        typewriterJob?.cancel()
        val snapshot = _state.value
        chatRepo.cancel()
        if (snapshot.phase == Phase.Streaming || snapshot.phase == Phase.Playing) {
            chatRepo.rawTextSoFar()?.takeIf { it.isNotBlank() }?.let { raw ->
                container.applicationScope.launch {
                    sessionRepo.appendAssistantResult(sessionId, raw, complete = false)
                }
            }
        }
        voicePlayer.release()
    }

    private fun checkConfigured(): Boolean {
        if (SettingsRepository.isConfigured(container.settingsRepository.settings.value)) return true
        _state.update { it.copy(notConfigured = true) }
        return false
    }

    private fun startStream() {
        streamJob?.cancel()
        chatRepo.cancel() // 兜底清理残留连接
        queue.clear()
        terminal = null
        parsedAnyDialog = false
        _state.update {
            it.copy(
                phase = Phase.Streaming,
                streaming = true,
                error = null,
                queueSize = 0,
            )
        }
        streamJob = viewModelScope.launch {
            val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId }
            if (session == null) {
                _state.update { it.copy(streaming = false, phase = Phase.Error, error = "会话不存在") }
                return@launch
            }
            val settings = container.settingsRepository.settings.value
            chatRepo.getChatStream(settings, sessionRepo.buildInput(session))
                .collect(::handleEvent)
        }
    }

    private fun handleEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.DialogPart -> {
                parsedAnyDialog = true
                val snapshot = _state.value
                // 首条立即开播；队列已空且打字结束时自动续播。
                queue.addLast(event.dialog)
                if (snapshot.phase == Phase.Streaming || (snapshot.queueSize == 0 && !snapshot.typing)) advanceInternal()
                else _state.update { it.copy(queueSize = queue.size) }
            }

            is ChatStreamEvent.Completed -> {
                terminal = Terminal.Done
                // 未解析出 dialog 时按格式错误保存残片。
                persistAssistant(event.rawText, complete = parsedAnyDialog)
                finishStream(if (parsedAnyDialog) null else "回复格式异常，未解析出对话内容")
            }

            is ChatStreamEvent.Error -> {
                terminal = Terminal.Failed(event.message)
                persistAssistant(event.rawText, complete = false)
                finishStream(if (parsedAnyDialog) null else event.message)
            }

            is ChatStreamEvent.Cancelled -> {
                terminal = Terminal.Stopped
                persistAssistant(event.rawText, complete = false)
                _state.update { it.copy(streaming = false, phase = Phase.WaitingInput, queueSize = queue.size) }
            }
        }
    }

    // 未播放任何 dialog 时立即报错，否则留待队列播完。
    private fun finishStream(error: String?) {
        _state.update {
            if (error == null) it.copy(streaming = false)
            else it.copy(streaming = false, phase = Phase.Error, error = error)
        }
    }

    private fun advanceInternal() {
        val dialog = queue.removeFirstOrNull() ?: return
        val applied = applyDialog(dialog, _state.value.sprite, playVoice = true)
        _state.update {
            it.copy(phase = Phase.Playing, line = applied.line, sprite = applied.sprite, queueSize = queue.size)
        }
        startTypewriter(applied.line.speech)
    }

    // 行和立绘由调用方一次性提交，避免中间态。
    private data class AppliedDialog(val line: DisplayLine, val sprite: SpriteView?)

    private fun applyDialog(dialog: Dialog, prev: SpriteView?, playVoice: Boolean): AppliedDialog {
        val name = dialog.name.trim()
        val speaker = charactersByName[name]

        if (speaker == null) {
            val narration = name.isEmpty() || name == "旁白"
            return AppliedDialog(
                line = DisplayLine(
                    name = if (narration) "旁白" else name,
                    speech = dialog.speech,
                    kind = if (narration) LineKind.Narration else LineKind.Stranger,
                    speakerColorHex = null,
                ),
                sprite = prev?.copy(dimmed = true),
            )
        }

        val next = resolveSprite(speaker, dialog.sprite)
        if (playVoice && next != null &&
            (prev?.characterId != speaker.id || prev.spriteIndex != next.spriteIndex)
        ) {
            playSpriteVoice(speaker, next.spriteIndex)
        }

        return AppliedDialog(
            line = DisplayLine(
                name = speaker.name,
                speech = dialog.speech,
                kind = LineKind.Character,
                speakerColorHex = speaker.color,
            ),
            sprite = (next ?: prev)?.let { it.copy(dimmed = it.characterId != speaker.id) },
        )
    }

    private fun resolveSprite(speaker: Character, sprite: String): SpriteView? {
        val number = sprite.trim().toIntOrNull() ?: return null
        val index = when {
            number >= 1 -> number - 1
            number == -1 && speaker.sprites.isNotEmpty() -> spriteMemory[speaker.id] ?: 0
            else -> return null
        }
        val image = characterRepo.spriteImage(speaker.id, index)
        // -1 恢复记忆；显式编号缺图时才维持原画面。
        if (number >= 1 && image == null) return null
        spriteMemory[speaker.id] = index
        return SpriteView(
            characterId = speaker.id,
            spriteIndex = index,
            imageFile = image,
            spriteScale = speaker.spriteScale,
            dimmed = false,
        )
    }

    private fun playSpriteVoice(speaker: Character, index: Int) {
        val type = speaker.sprites.getOrNull(index)?.voiceType
        if (type != "preset" && type != "fallback") return
        val voice = characterRepo.spriteVoice(speaker.id, index) ?: return
        voicePlayer.play(voice, speaker.speechSpeed, speaker.speechVolume)
    }

    // 历史回放不播语音、不打字，仅重建行和立绘记忆。
    private fun replayHistory(session: ChatSession) {
        var lastApplied: AppliedDialog? = null
        var sprite = _state.value.sprite
        for (entry in session.history) {
            if (!entry.complete || entry.role != Role.assistant) continue
            for (dialog in DialogJsonParser.parseAll(entry.content)) {
                val applied = applyDialog(dialog, sprite, playVoice = false)
                sprite = applied.sprite
                lastApplied = applied
            }
        }
        if (lastApplied != null) {
            _visibleSpeech.value = lastApplied.line.speech
            _state.update { it.copy(line = lastApplied.line, sprite = lastApplied.sprite, typing = false) }
        }
    }

    // 增量推进码点下标，避免从头计数，也不拆开代理对。
    private fun startTypewriter(full: String) {
        typewriterJob?.cancel()
        _visibleSpeech.value = ""
        if (full.isEmpty()) {
            _state.update { it.copy(typing = false) }
            return
        }
        _state.update { it.copy(typing = true) }
        typewriterJob = viewModelScope.launch {
            var index = 0
            while (index < full.length) {
                delay(40L)
                index = full.offsetByCodePoints(index, 1)
                _visibleSpeech.value = full.substring(0, index)
            }
            _state.update { it.copy(typing = false) }
        }
    }

    private fun completeTypewriter() {
        typewriterJob?.cancel()
        _state.value.line?.let { line ->
            _visibleSpeech.value = line.speech
            _state.update { it.copy(typing = false) }
        }
    }

    private fun persistAssistant(rawText: String, complete: Boolean) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            sessionRepo.appendAssistantResult(sessionId, rawText, complete)
        }
    }
}
