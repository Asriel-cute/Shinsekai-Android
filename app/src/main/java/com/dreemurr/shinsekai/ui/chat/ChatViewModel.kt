package com.dreemurr.shinsekai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.ChatStreamEvent
import com.dreemurr.shinsekai.chat.ContextCompressor
import com.dreemurr.shinsekai.chat.Dialog
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.chat.TokenEstimator
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
        // 主控角色名；仅用于输入提示与记录面板标签。
        val playerName: String? = null,
        val history: List<SessionEntry> = emptyList(),
        // 上下文估算与手动压缩状态；进入聊天页超限弹确认。
        val tokenEstimate: Int = 0,
        val showCompressConfirm: Boolean = false,
        val compressing: Boolean = false,
        // 回溯后回填输入框的一次性草稿。
        val pendingDraft: String? = null,
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

    // 会话级云语音开关；关闭后绑定卡片让位本地立绘语音。
    private var cloudTtsEnabled = true

    // 主控角色；主控台词不播本地立绘语音，只走云语音或静默展示。
    private var playerCharacterId: String? = null

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
    // suppressLocalVoice 用于主控：固定立绘录音不能被当成用户台词的朗读。
    private class QueuedDialog(
        val dialog: Dialog,
        val boundCard: VoiceCard? = null,
        val cloud: CloudJob? = null,
        val suppressLocalVoice: Boolean = false,
    )

    private val queue = ArrayDeque<QueuedDialog>()
    // 打断播放判定：本轮已入队与已真正上屏的 dialog 数，两者差值即未播内容。
    private var enqueuedDialogs = 0
    private val playedDialogs = mutableListOf<Dialog>()
    private val spriteMemory = mutableMapOf<String, Int>()
    private var terminal: Terminal? = null
    private var parsedAnyDialog = false
    private var streamJob: Job? = null
    private var typewriterJob: Job? = null
    private var compressJob: Job? = null

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
                val byId = characterRepo.characters.value.associateBy { it.id }
                for (id in session.characterIds) {
                    val character = byId[id] ?: continue
                    charactersByName.putIfAbsent(character.name, character)
                }
                replayHistory(session)
                applySession(session)
                // 每次进入聊天页检测一次；超限弹窗由用户决定是否压缩。
                _state.update {
                    it.copy(showCompressConfirm = it.tokenEstimate > ContextCompressor.MAX_CONTEXT_TOKENS)
                }
            }
            _state.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            sessionRepo.sessions
                .mapNotNull { list -> list.firstOrNull { it.id == sessionId } }
                .distinctUntilChanged()
                .collect { applySession(it) }
        }
    }

    // 同步会话级字段与派生 UI 状态；快照/开关语义见各字段注释。
    private fun applySession(session: ChatSession) {
        sessionVoiceLanguages = session.voiceLanguages
        cloudTtsEnabled = session.cloudTtsEnabled
        playerCharacterId = session.playerCharacterId
        val byId = characterRepo.characters.value.associateBy { it.id }
        val playerName = session.playerCharacterId?.let(byId::get)?.name
        _state.update {
            it.copy(
                title = session.title,
                playerName = playerName,
                history = session.history,
                tokenEstimate = TokenEstimator.estimate(sessionRepo.buildInput(session)),
            )
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !checkConfigured()) return
        viewModelScope.launch {
            // 播放中发送即打断：历史只保留已播出的 dialog，未播放部分一并丢弃。
            dropPlayback()
            trimUnplayedReply()
            sessionRepo.appendEntry(sessionId, SessionEntry(role = Role.user, content = trimmed))
            startStream()
        }
    }

    // 有未播内容时把最后一条 assistant 截断为已播出的 dialog；一条都没播则整条删除。
    private suspend fun trimUnplayedReply() {
        if (enqueuedDialogs <= playedDialogs.size) return
        val last = sessionRepo.sessions.value
            .firstOrNull { it.id == sessionId }?.history?.lastOrNull()
        if (last?.role != Role.assistant) return
        val content = if (playedDialogs.isEmpty()) "" else DialogJsonParser.encode(playedDialogs)
        sessionRepo.truncateLastAssistant(sessionId, content)
        enqueuedDialogs = 0
        playedDialogs.clear()
    }

    // 重试复用 history 中的用户消息。
    fun retry() {
        if (checkConfigured()) startStream()
    }

    fun stop() {
        chatRepo.cancel()
    }

    // 回溯：截断到目标用户消息之前，并把原文回填输入框等改写。
    fun rewindTo(historyIndex: Int) {
        viewModelScope.launch {
            // 流式生成中先停止并等残片落盘，避免截断后残片再追加到结尾。
            if (_state.value.streaming) {
                chatRepo.cancel()
                streamJob?.join()
            }
            dropPlayback()
            val draft = sessionRepo.sessions.value
                .firstOrNull { it.id == sessionId }
                ?.history?.getOrNull(historyIndex)?.content
            sessionRepo.truncate(sessionId, keep = historyIndex)
            restoreFromHistory()
            if (draft != null) {
                _state.update {
                    it.copy(
                        pendingDraft = draft,
                        phase = Phase.WaitingInput,
                        streaming = false,
                        error = null,
                    )
                }
            }
        }
    }

    fun consumeDraft() {
        _state.update { it.copy(pendingDraft = null) }
    }

    fun requestCompress() {
        _state.update { it.copy(showCompressConfirm = true) }
    }

    fun dismissCompressConfirm() {
        _state.update { it.copy(showCompressConfirm = false) }
    }

    // 手动压缩：失败保持原样，仅 Toast 提示。
    fun compress() {
        if (_state.value.compressing) return
        if (!checkConfigured()) {
            _state.update { it.copy(showCompressConfirm = false) }
            return
        }
        val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId } ?: return
        compressJob = viewModelScope.launch {
            _state.update { it.copy(compressing = true, showCompressConfirm = false) }
            try {
                val settings = container.settingsRepository.settings.value
                val result = container.contextCompressor.compact(session, settings)
                if (result == null) {
                    _toast.value = container.appContext.getString(R.string.compress_too_few)
                } else {
                    sessionRepo.applyCompaction(sessionId, result.summaryEntry, result.foldedCount)
                    _toast.value = container.appContext.getString(
                        R.string.compress_success_format,
                        result.foldedCount,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _toast.value = container.appContext.getString(R.string.compress_failed)
            } finally {
                _state.update { it.copy(compressing = false) }
            }
        }
    }

    fun cancelCompress() {
        compressJob?.cancel()
    }

    // 丢弃未播放的展示队列：清队列、停推进与打字机，当前台词补全显示。
    private fun dropPlayback() {
        queue.clear()
        advanceJob?.cancel()
        typewriterJob?.cancel()
        _state.value.line?.let { _visibleSpeech.value = it.speech }
        _state.update { it.copy(typing = false, synthesizing = false, queueSize = 0) }
    }

    // 截断/压缩后重放剩余历史，恢复最后一句与立绘记忆。
    private fun restoreFromHistory() {
        spriteMemory.clear()
        terminal = null
        parsedAnyDialog = false
        enqueuedDialogs = 0
        playedDialogs.clear()
        _state.update {
            it.copy(line = null, sprite = null, typing = false, queueSize = 0, error = null)
        }
        _visibleSpeech.value = ""
        val session = sessionRepo.sessions.value.firstOrNull { it.id == sessionId } ?: return
        replayHistory(session)
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
        enqueuedDialogs = 0
        playedDialogs.clear()
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

    // 落盘完成后才切换 UI 状态，保证停止后立即发送不会与残片写入错序。
    private suspend fun handleEvent(event: ChatStreamEvent) {
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
    // 语言目标只认新建会话快照；缺少快照/translate 时跳过该条，不回退本地立绘语音。
    private fun enqueueDialog(dialog: Dialog) {
        enqueuedDialogs++
        val name = dialog.name.trim()
        val speaker = charactersByName[name]
        // 主控台词一律不播本地立绘语音：固定录音不是用户台词的朗读。
        val suppressLocalVoice = speaker != null && speaker.id == playerCharacterId
        // 会话级开关关闭时不走云语音，boundCard 传 null 回退本地立绘语音。
        val boundCard = if (!cloudTtsEnabled) null else speaker
            ?.let { container.voiceBindings.bindings.value[it.id] }
            ?.let { cardId -> container.voiceCardRepository.cards.value.firstOrNull { it.id == cardId } }
            ?.takeIf { it.isTtsReady }
        val cloud = createCloudJob(dialog, speaker, boundCard)
        queue.addLast(QueuedDialog(dialog, boundCard, cloud, suppressLocalVoice))
    }

    // 合成条件齐备才创建预取任务；条件不满足时返回 null，仍保留绑定卡片以抑制本地录音。
    private fun createCloudJob(dialog: Dialog, speaker: Character?, boundCard: VoiceCard?): CloudJob? {
        if (speaker == null || boundCard == null) return null
        val provider = boundCard.providerEnum ?: return null
        // 空台词不发起合成。
        if (dialog.speech.isBlank()) return null
        // 无快照：静默跳过云 TTS，不回退当前卡片语言。
        val snapshot = sessionVoiceLanguages[speaker.id] ?: return null
        val target = VoiceLanguages.normalize(snapshot)
        // 朗读文本一律取 translate：ja/en 为译文、zh 为纯台词、Auto 为模型自判语言。
        val text = dialog.translate.trim()
        if (text.isEmpty()) {
            ttsMissingTranslationNames.add(speaker.name)
            return null
        }
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
        return job
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
                suppressLocalVoice = item.suppressLocalVoice,
            )
            _state.update {
                it.copy(
                    phase = Phase.Playing,
                    line = applied.line,
                    sprite = applied.sprite,
                    queueSize = queue.size,
                )
            }
            // 上屏后才计入已播放，打断时未播部分会被截断。
            playedDialogs.add(item.dialog)
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
    // 主控台词即使没有云语音也不播本地录音，避免固定台词被误认成朗读。
    private fun applyDialog(
        dialog: Dialog,
        prev: SpriteView?,
        playVoice: Boolean,
        boundCard: VoiceCard? = null,
        suppressLocalVoice: Boolean = false,
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
        if (playVoice && !suppressLocalVoice && boundCard == null && next != null &&
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

    private suspend fun persistAssistant(rawText: String, complete: Boolean) {
        if (rawText.isBlank()) return
        sessionRepo.appendAssistantResult(sessionId, rawText, complete)
    }
}
