package com.dreemurr.shinsekai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.ChatStreamEvent
import com.dreemurr.shinsekai.chat.Dialog
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.session.SessionEntry
import com.dreemurr.shinsekai.settings.SettingsRepository
import com.dreemurr.shinsekai.voice.SynthResult
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.TtsRepository
import com.dreemurr.shinsekai.voice.VoiceCard
import com.dreemurr.shinsekai.voice.VoiceLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // 云语音合成等待中：期间点击不推进，台词待音频就绪再展示。
        val synthesizing: Boolean = false,
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

    // 一次性提示（语音失败/缺译文汇总等）。
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    // 错误留到队列播完后展示。
    private sealed interface Terminal {
        data object Done : Terminal
        data object Stopped : Terminal
        data class Failed(val message: String) : Terminal
    }

    private val charactersByName = mutableMapOf<String, Character>()

    // 新建会话时的语言快照；缺失条目静默跳过云 TTS，不回退当前卡片。
    private var sessionVoiceLanguages: Map<String, String> = emptyMap()

    // 云语音任务：只有需要合成时才创建，pending 在完成后给出音频文件（null = 失败/无音频）。
    private class CloudJob(
        val card: VoiceCard,
        val provider: TtsProvider,
        val audioFile: File,
        val text: String,
        val language: String?,
        val pending: CompletableDeferred<File?> = CompletableDeferred(),
    )

    // 队列元素：boundCard 非空即由绑定卡片承担语音，合成跳过时也抑制本地立绘语音。
    private class QueuedDialog(
        val dialog: Dialog,
        val boundCard: VoiceCard? = null,
        val cloud: CloudJob? = null,
    )

    private val queue = ArrayDeque<QueuedDialog>()
    private val spriteMemory = mutableMapOf<String, Int>()
    private var terminal: Terminal? = null
    private var parsedAnyDialog = false
    private var streamJob: Job? = null
    private var typewriterJob: Job? = null

    // 云语音：流中全量串行预取，推进时等待就绪（展示前阻塞）。
    private var prefetchChannel = Channel<CloudJob>(Channel.UNLIMITED)
    private var prefetchJob: Job? = null
    private var advanceJob: Job? = null
    private var dialogSeq = 0
    private val ttsFailureMessages = LinkedHashSet<String>()
    private val ttsMissingTranslationNames = LinkedHashSet<String>()
    private val ttsDir = File(container.appContext.cacheDir, "tts")
    private var lastCloudAudio: File? = null

    init {
        // 等启动加载完成，再回放历史重建立绘记忆。
        viewModelScope.launch {
            container.ready.first { it }
            val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId }
            if (session != null) {
                sessionVoiceLanguages = session.voiceLanguages
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
                    sessionVoiceLanguages = session.voiceLanguages
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
        // 语音合成等待期间点击不推进（无跳过）。
        if (_state.value.synthesizing) return
        if (_state.value.typing) {
            completeTypewriter()
            return
        }
        if (queue.isNotEmpty()) {
            advanceInternal()
            return
        }
        when (val result = terminal) {
            Terminal.Done, Terminal.Stopped -> {
                flushTtsNotices()
                _state.update { it.copy(phase = Phase.WaitingInput) }
            }

            is Terminal.Failed -> {
                flushTtsNotices()
                _state.update { it.copy(phase = Phase.Error, error = result.message) }
            }

            null -> Unit // 流未结束，等待新 dialog。
        }
    }

    // 队列播完后一次性提示本轮云语音失败/缺译文；不打断阅读，不进 Error。
    private fun flushTtsNotices() {
        if (ttsFailureMessages.isEmpty() && ttsMissingTranslationNames.isEmpty()) {
            return
        }
        val notices = buildList {
            if (ttsFailureMessages.isNotEmpty()) {
                add(
                    container.appContext.getString(
                        R.string.chat_tts_failed_format,
                        ttsFailureMessages.take(3).joinToString("；"),
                    ),
                )
            }
            if (ttsMissingTranslationNames.isNotEmpty()) {
                add(
                    container.appContext.getString(
                        R.string.chat_tts_missing_translation_format,
                        ttsMissingTranslationNames.take(3).joinToString("、"),
                    ),
                )
            }
        }
        clearTtsNotices()
        _toast.value = notices.joinToString("；")
    }

    private fun clearTtsNotices() {
        ttsFailureMessages.clear()
        ttsMissingTranslationNames.clear()
    }

    override fun onCleared() {
        typewriterJob?.cancel()
        advanceJob?.cancel()
        prefetchChannel.close()
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
        cleanTtsFiles()
    }

    private fun checkConfigured(): Boolean {
        if (SettingsRepository.isConfigured(container.settingsRepository.settings.value)) return true
        _state.update { it.copy(notConfigured = true) }
        return false
    }

    private fun startStream() {
        streamJob?.cancel()
        chatRepo.cancel() // 兜底清理残留连接
        advanceJob?.cancel()
        prefetchJob?.cancel()
        prefetchChannel.close()
        prefetchChannel = Channel(Channel.UNLIMITED)
        dialogSeq = 0
        clearTtsNotices()
        cleanTtsFiles() // 上一轮残留的合成音频不再可用
        queue.clear()
        terminal = null
        parsedAnyDialog = false
        _state.update {
            it.copy(
                phase = Phase.Streaming,
                streaming = true,
                error = null,
                queueSize = 0,
                synthesizing = false,
            )
        }
        // 串行合成消费者：逐条请求云 TTS，避免并发触发限流。
        prefetchJob = viewModelScope.launch { consumePrefetch() }
        streamJob = viewModelScope.launch {
            val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId }
            if (session == null) {
                _state.update { it.copy(streaming = false, phase = Phase.Error, error = "会话不存在") }
                return@launch
            }
            sessionVoiceLanguages = session.voiceLanguages
            val settings = container.settingsRepository.settings.value
            chatRepo.getChatStream(settings, sessionRepo.buildInput(session))
                .collect(::handleEvent)
        }
    }

    private fun handleEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.DialogPart -> {
                parsedAnyDialog = true
                enqueueDialog(event.dialog)
                val snapshot = _state.value
                // 首条立即开播；队列已空、打字结束且不在等音频时自动续播。
                // 合成等待期间绝不重复推进，避免丢掉已在等待的行。
                val idle = !snapshot.typing && !snapshot.synthesizing
                if ((snapshot.phase == Phase.Streaming && !snapshot.synthesizing) ||
                    (snapshot.queueSize == 0 && idle)
                ) {
                    advanceInternal()
                } else {
                    _state.update { it.copy(queueSize = queue.size) }
                }
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

    // 入队并按需发起云语音预取：说话者绑定可用卡片且有台词才合成。
    // 语言目标只认新建会话快照；缺少快照/目标语译文时跳过该条，不回退本地立绘语音。
    private fun enqueueDialog(dialog: Dialog) {
        val name = dialog.name.trim()
        val speaker = charactersByName[name]
        val boundCard = speaker
            ?.let { container.voiceBindings.bindings.value[it.id] }
            ?.let { cardId -> container.voiceCardRepository.cards.value.firstOrNull { it.id == cardId } }
            ?.takeIf { it.isTtsReady }
        if (speaker == null || boundCard == null) {
            queue.addLast(QueuedDialog(dialog))
            return
        }

        val provider = boundCard.providerEnum ?: run {
            queue.addLast(QueuedDialog(dialog, boundCard))
            return
        }

        // 绑定卡片后即使台词为空也抑制本地录音；空台词不发起合成。
        if (dialog.speech.isBlank()) {
            queue.addLast(QueuedDialog(dialog, boundCard))
            return
        }

        val snapshot = sessionVoiceLanguages[speaker.id]
        // 无快照：静默跳过云 TTS，不回退当前卡片语言。
        if (snapshot == null) {
            queue.addLast(QueuedDialog(dialog, boundCard))
            return
        }

        val target = VoiceLanguages.normalize(snapshot)
        val needsTranslation = target == VoiceLanguages.JA || target == VoiceLanguages.EN
        val translated = dialog.translate.trim()
        if (needsTranslation && translated.isBlank()) {
            ttsMissingTranslationNames.add(speaker.name)
            queue.addLast(QueuedDialog(dialog, boundCard))
            return
        }

        val text = if (needsTranslation) translated else dialog.speech.trim()
        val job = CloudJob(
            card = boundCard,
            provider = provider,
            audioFile = File(ttsDir, "$sessionId-${dialogSeq++}.mp3"),
            text = text,
            language = target,
        )
        // 队列在 VM 销毁或重试时关闭；异常关闭时直接完成空结果，避免推进时永久等待。
        if (prefetchChannel.trySend(job).isFailure) {
            job.pending.complete(null)
        }
        queue.addLast(QueuedDialog(dialog, boundCard, job))
    }

    // 串行消费预取队列：合成 → 落盘 → 完成对应 Deferred；失败记录待轮末汇总。
    private suspend fun consumePrefetch() {
        // 同一轮内按密文缓存明文 Key，避免每条台词都走 Keystore 解密。
        val apiKeys = mutableMapOf<String, String>()
        for (job in prefetchChannel) {
            try {
                val result = container.ttsRepository.synth(
                    TtsRepository.SynthParams(
                        provider = job.provider,
                        baseUrl = job.card.baseUrl.trim(),
                        apiKey = apiKeys.getOrPut(job.card.apiKeyCipher) {
                            container.voiceCardRepository.decryptKey(job.card)
                        },
                        model = job.card.synthModel,
                        voiceId = job.card.voiceId,
                        text = job.text,
                        language = job.language,
                    ),
                )
                when (result) {
                    is SynthResult.Ok -> {
                        val saved = withContext(Dispatchers.IO) {
                            writeAudio(job.audioFile, result.audio)
                        }
                        if (saved) {
                            job.pending.complete(job.audioFile)
                        } else {
                            ttsFailureMessages.add(
                                container.appContext.getString(R.string.chat_tts_save_failed),
                            )
                            job.audioFile.delete()
                            job.pending.complete(null)
                        }
                    }

                    is SynthResult.Failure -> {
                        ttsFailureMessages.add(result.message)
                        job.audioFile.delete()
                        job.pending.complete(null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                ttsFailureMessages.add(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: container.appContext.getString(R.string.chat_tts_unknown_error),
                )
                job.audioFile.delete()
                job.pending.complete(null)
            }
        }
    }

    // .part 临时文件原子写；返回是否成功。
    private fun writeAudio(target: File, bytes: ByteArray): Boolean {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tmp.delete()
            target.delete()
            false
        }
    }

    // 清理本会话的全部合成音频（cacheDir 兜底由系统回收）。
    private fun cleanTtsFiles() {
        lastCloudAudio = null
        ttsDir.listFiles()?.forEach { if (it.name.startsWith("$sessionId-")) it.delete() }
    }

    private fun advanceInternal() {
        val item = queue.removeFirstOrNull() ?: return
        val cloud = item.cloud
        val needsWait = cloud != null && !cloud.pending.isCompleted
        _state.update { it.copy(queueSize = queue.size, synthesizing = needsWait) }
        advanceJob = viewModelScope.launch {
            // 展示前阻塞：等待该条云语音就绪（通常预取已完成，等待为零）。
            val audio = cloud?.pending?.await()
            _state.update { it.copy(synthesizing = false) }
            val applied = applyDialog(
                item.dialog,
                _state.value.sprite,
                playVoice = true,
                boundCard = item.boundCard,
            )
            _state.update {
                it.copy(
                    phase = Phase.Playing,
                    line = applied.line,
                    sprite = applied.sprite,
                    queueSize = queue.size,
                )
            }
            startTypewriter(applied.line.speech)
            if (audio != null) {
                // 云音频原样播放（不套角色语速/音量）；先切歌再删上一条文件。
                voicePlayer.play(audio, 1f, 1f)
                lastCloudAudio?.delete()
                lastCloudAudio = audio
            }
        }
    }

    // 行和立绘由调用方一次性提交，避免中间态。
    private data class AppliedDialog(val line: DisplayLine, val sprite: SpriteView?)

    // 绑定声音卡片的角色由云语音承担台词，本地 preset/fallback 录音静音让位。
    private fun applyDialog(
        dialog: Dialog,
        prev: SpriteView?,
        playVoice: Boolean,
        boundCard: VoiceCard? = null,
    ): AppliedDialog {
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
        if (playVoice && boundCard == null && next != null &&
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
