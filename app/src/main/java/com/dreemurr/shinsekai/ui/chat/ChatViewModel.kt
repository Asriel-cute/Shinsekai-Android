package com.dreemurr.shinsekai.ui.chat

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.camera.view.PreviewView
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.background.Background
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.CAMERA_ALIAS_NAME
import com.dreemurr.shinsekai.chat.CAMERA_FRAME_TEXT
import com.dreemurr.shinsekai.chat.CAMERA_MARKER_NAME
import com.dreemurr.shinsekai.chat.ChatStreamEvent
import com.dreemurr.shinsekai.chat.ContextCompressor
import com.dreemurr.shinsekai.chat.Dialog
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.PresentationKind
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.chat.StatItem
import com.dreemurr.shinsekai.chat.StatPayload
import com.dreemurr.shinsekai.chat.ThinkingPart
import com.dreemurr.shinsekai.chat.TokenEstimator
import com.dreemurr.shinsekai.chat.choiceOptions
import com.dreemurr.shinsekai.chat.presentationKind
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.session.ChatType
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val backgroundRepo = container.backgroundRepository
    private val chatRepo = container.chatRepository
    private val voicePlayer = container.voicePlayer
    private val bgmPlayer = container.bgmPlayer

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
        // 当前场景背景图；null=无背景包或尚无场景。
        val backgroundImage: File? = null,
        // 数值面板：只来自模型 STAT 条目，整表替换；空=不显示、不注入。
        val stats: List<StatItem> = emptyList(),
        // 待选选项：最近一条 CHOICE 的拆分结果；点击回填输入框，发送或新一轮开始时清空。
        val choices: List<String> = emptyList(),
        val error: String? = null,
        val notConfigured: Boolean = false,
        // 主控角色名；仅用于输入提示与记录面板标签。
        val playerName: String? = null,
        val history: List<SessionEntry> = emptyList(),
        // 上下文估算与手动压缩状态；进入聊天页超限弹确认。
        val tokenEstimate: Int = 0,
        val showCompressConfirm: Boolean = false,
        val compressing: Boolean = false,
        // 回溯或点击选项后回填输入框的一次性草稿。
        val pendingDraft: String? = null,
        // 视频会话：悬浮摄像头窗、画面变化/CAMERA 请求发帧。
        val videoChat: Boolean = false,
        // 摄像头已启动（权限通过且控制器创建）。
        val cameraActive: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    // 高频打字更新仅重组台词组件。
    private val _visibleSpeech = MutableStateFlow("")
    val visibleSpeech = _visibleSpeech.asStateFlow()

    // 思考内容流式展示：同样独立 StateFlow，只留尾部，避免长文本反复重排。
    private val _thinking = MutableStateFlow("")
    val thinking = _thinking.asStateFlow()

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

    // 会话绑定的背景包；null=无背景，SCENE/bgm 条目按编号切换场景与音乐。
    private var sessionBackground: Background? = null

    // 模型确立且素材存在的场景/BGM 编号（1 基）；null=无记录不写状态块，回落默认开场不算记录，发送时不回扫历史。
    private var sceneNumber: Int? = null
    private var bgmNumber: Int? = null

    // 数值状态/对话选项开关；关闭时对应展示条目静默忽略，不注入、不弹出。
    private var statEnabled = false
    private var choiceEnabled = false

    // 视频会话状态：悬浮窗与 CAMERA 请求仅在 VIDEO 会话生效。
    private var videoChat = false
    private var cameraController: CameraController? = null
    private var cameraCooldownSeconds = 30
    private var lastFrameSentAt = 0L

    // 拍帧发送进行中：captureJpeg 需数百毫秒，期间状态守卫仍是旧值，防止 trigger 重入双发。
    private var cameraSending = false

    // 忙碌期间 trigger 到达的时刻：忙碌时间不计入冷却，基准取 max(上次发帧, 该时刻)防连环发帧。
    private var lastBusyTriggerAt = 0L

    // 本轮出现 CAMERA 画面请求：待可见台词播空后发帧；cameraCut 后到达的台词按打断语义丢弃。
    private var cameraRequested = false
    private var cameraCut = false

    // CAMERA 经工具调用到达（区别于旧文本保留名）：续跑请求据此重构工具配对；thoughtSignature 仅 Gemini 需要。
    private var cameraViaTool = false
    private var cameraThoughtSignature: String? = null

    // 本段收到的可回放思考块（Anthropic thinking / Responses reasoning），续跑请求原样带回。
    private var cameraThinking: List<ThinkingPart> = emptyList()

    // 本逻辑轮（含续跑）累计画面请求次数，封顶防连环发帧；用户新发送时清零。
    private var cameraCountThisTurn = 0

    // 会话期内是否已播放过 BGM，用于重进/回溯后判断是否回落到第 1 首。
    private var bgmStarted = false

    // 云语音任务：只有需要合成时才创建，pending 在完成后给出音频文件（null = 失败/无音频）。
    private class CloudJob(
        val card: VoiceCard,
        val provider: TtsProvider,
        val audioFile: File,
        val text: String,
        val language: String?,
        val pending: CompletableDeferred<File?> = CompletableDeferred(),
    )

    // boundCard 非空即由绑定卡片承担语音；suppressLocalVoice 为主控（固定录音不当朗读）；presentation 为展示条目。
    private class QueuedDialog(
        val dialog: Dialog,
        val boundCard: VoiceCard? = null,
        val cloud: CloudJob? = null,
        val suppressLocalVoice: Boolean = false,
        val presentation: Boolean = false,
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
    // 视频会话自动推进调度：语音播完或动态停留后进入下一条。
    private var autoAdvanceJob: Job? = null
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
                applySession(session)
                replayHistory(session)
                // 历史中没有 SCENE/bgm 时回落到场景 1 + BGM 1。
                initPresentationDefaults()
                refreshTokenEstimate()
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
        // 全局 BGM 音量实时生效。
        viewModelScope.launch {
            container.settingsRepository.bgmVolume.collect { bgmPlayer.setVolume(it) }
        }
        // 视频聊天：冷却与变化阈值实时生效。
        viewModelScope.launch {
            combine(
                container.settingsRepository.cameraCooldownSeconds,
                container.settingsRepository.cameraChangeThreshold,
            ) { cooldown, threshold -> cooldown to threshold }
                .collect { (cooldown, threshold) ->
                    cameraCooldownSeconds = cooldown
                    cameraController?.changeThreshold = threshold
                }
        }
    }

    // 同步会话级字段与派生状态；token 估算较贵，只在回合结束/回溯/压缩节点重算。
    private fun applySession(session: ChatSession) {
        sessionVoiceLanguages = session.voiceLanguages
        cloudTtsEnabled = session.cloudTtsEnabled
        playerCharacterId = session.playerCharacterId
        videoChat = session.chatType == ChatType.VIDEO
        sessionBackground = session.backgroundId
            ?.let { id -> backgroundRepo.backgrounds.value.firstOrNull { it.id == id } }
        statEnabled = session.statEnabled
        choiceEnabled = session.choiceEnabled
        val byId = characterRepo.characters.value.associateBy { it.id }
        val playerName = session.playerCharacterId?.let(byId::get)?.name
        _state.update {
            it.copy(
                title = session.title,
                playerName = playerName,
                history = session.history,
                videoChat = videoChat,
                // 开关关掉即下线对应展示，历史里的旧条目不再恢复。
                stats = if (statEnabled) it.stats else emptyList(),
                choices = if (choiceEnabled) it.choices else emptyList(),
            )
        }
    }

    // 当前会话快照；各流程共用。
    private fun currentSession(): ChatSession? =
        sessionRepo.sessions.value.firstOrNull { it.id == sessionId }

    // 仅在进入页面/回溯/压缩/回合结束时调用；全历史解析与逐字符估算较贵，放 Default 避免阻塞主线程。
    private suspend fun refreshTokenEstimate() {
        val session = currentSession() ?: return
        val estimate = withContext(Dispatchers.Default) {
            TokenEstimator.estimate(sessionRepo.buildInput(session))
        }
        _state.update { it.copy(tokenEstimate = estimate) }
    }

    // 无任何 SCENE/bgm 记录时按默认开场（场景 1 + BGM 1）；回落不算模型确立过的状态，不记编号。
    private fun initPresentationDefaults() {
        if (_state.value.backgroundImage == null) applyScene(0)
        if (!bgmStarted) applyBgm(0)
    }

    // 发送前渲染当前状态块（随 entry 落盘、仅 buildInput 前置）；只报告模型确立过的状态，任一类无记录就不写该行以免误导开场已设置，全空不注入。
    private fun statusContextBlock(): String? {
        val scene = sceneNumber
        val bgm = bgmNumber
        // 开关关闭时 applySession 已清空面板，这里直接用运行时状态。
        val stats = _state.value.stats
        if (scene == null && bgm == null && stats.isEmpty()) return null
        val background = sessionBackground
        return buildString {
            append("[当前状态]\n")
            scene?.let { appendIndexedLine("场景", it, background?.bgTags) }
            bgm?.let { appendIndexedLine("BGM", it, background?.bgmTags) }
            // 数值行与 STAT 输出同格式，模型可直接在旧列表上改。
            if (stats.isNotEmpty()) {
                append("数值（图标|名称|值|上限）：\n")
                for (stat in stats) {
                    append(stat.icon).append('|').append(stat.label).append('|').append(stat.valueText)
                    stat.maxText?.let { append('|').append(it) }
                    append('\n')
                }
            }
            append("[/当前状态]")
        }
    }

    // 「标签：编号（说明）」一行；标签行号与提示词同口径：整段 trim 后再按行取，前导空行不参与编号。
    private fun StringBuilder.appendIndexedLine(label: String, number: Int, tags: String?) {
        append(label).append('：').append(number)
        tags?.trim()?.lines()?.getOrNull(number - 1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { append('（').append(it).append('）') }
        append('\n')
    }

    // 越界、缺素材、解析不出内容或开关关闭时一律静默忽略。
    private fun applyPresentation(dialog: Dialog) {
        when (val kind = presentationKind(dialog.name)) {
            PresentationKind.Scene, PresentationKind.Bgm -> {
                val number = dialog.sprite.trim().toIntOrNull() ?: return
                if (number < 1) return
                // 切换成功才记编号：状态块只报告实际生效过的场景/BGM。
                if (kind == PresentationKind.Scene) {
                    if (applyScene(number - 1)) sceneNumber = number
                } else {
                    if (applyBgm(number - 1)) bgmNumber = number
                }
            }

            PresentationKind.Stat -> if (statEnabled) applyStat(dialog)
            PresentationKind.Choice -> if (choiceEnabled) applyChoice(dialog)
            // CAMERA 请求在 enqueueDialog 单独处理，回放时无副作用。
            PresentationKind.Camera, null -> Unit
        }
    }

    // STAT 整表替换：解析出条目才替换，speech 为空视为清空，解析不出则保持原样。
    private fun applyStat(dialog: Dialog) {
        val stats = StatPayload.parse(dialog.speech)
        when {
            stats.isNotEmpty() -> _state.update { it.copy(stats = stats) }
            dialog.speech.isBlank() -> _state.update { it.copy(stats = emptyList()) }
        }
    }

    // CHOICE 拆出选项即弹出；无有效选项时收起。
    private fun applyChoice(dialog: Dialog) {
        _state.update { it.copy(choices = choiceOptions(dialog.speech)) }
    }

    // 切换场景图；无背景包或素材缺失时保持原画面。返回是否实际切换。
    private fun applyScene(index: Int): Boolean {
        val background = sessionBackground ?: return false
        val file = backgroundRepo.sceneImage(background.id, index) ?: return false
        _state.update { it.copy(backgroundImage = file) }
        return true
    }

    // 切换 BGM；无背景包或素材缺失时保持原曲目。返回是否实际切换。
    private fun applyBgm(index: Int): Boolean {
        val background = sessionBackground ?: return false
        val file = backgroundRepo.bgmFile(background.id, index) ?: return false
        bgmStarted = true
        bgmPlayer.play(file)
        return true
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !checkConfigured()) return
        viewModelScope.launch {
            // 播放中发送即打断：历史只保留已播出的 dialog，未播放部分一并丢弃。
            dropPlayback()
            trimUnplayedReply()
            sessionRepo.appendEntry(
                sessionId,
                SessionEntry(role = Role.user, content = trimmed, statusContext = statusContextBlock()),
            )
            startStream()
        }
    }

    // 未播内容截断为已播出的 dialog（全未播则整条删）；失败轮一律整条删除。
    private suspend fun trimUnplayedReply() {
        if (terminal is Terminal.Failed) {
            dropFailedFragment()
            return
        }
        if (enqueuedDialogs <= playedDialogs.size) return
        val last = currentSession()?.history?.lastOrNull()
        if (last?.role != Role.assistant) return
        val content = if (playedDialogs.isEmpty()) "" else DialogJsonParser.encode(playedDialogs)
        sessionRepo.truncateLastAssistant(sessionId, content)
        enqueuedDialogs = 0
        playedDialogs.clear()
    }

    // 失败残片不留上下文：末条为未完成 assistant 时整条删除，复用回溯的截断入口。
    private suspend fun dropFailedFragment() {
        val last = currentSession()?.history?.lastOrNull()
        if (last?.role != Role.assistant || last.complete) return
        sessionRepo.truncateLastAssistant(sessionId, "")
    }

    // 失败轮整轮回溯：一次快照判定末尾形态，单次 truncate 到该轮用户消息之前；返回原文供回填。
    private suspend fun rewindFailedTurn(): String? {
        val history = currentSession()?.history.orEmpty()
        val last = history.lastOrNull() ?: return null
        return when {
            // [.., user, 残片]：一次截掉两者。
            last.role == Role.assistant && !last.complete -> {
                val user = history.getOrNull(history.size - 2)
                if (user != null && user.role == Role.user && !user.summary) {
                    sessionRepo.truncate(sessionId, keep = history.size - 2)
                    user.content
                } else {
                    dropFailedFragment() // 防御：末尾只有残片
                    null
                }
            }

            // [.., user]：截掉该轮输入。
            last.role == Role.user && !last.summary -> {
                sessionRepo.truncate(sessionId, keep = history.size - 1)
                last.content
            }

            else -> null
        }
    }

    // 重试复用 history 中的用户消息；先整条删除失败残片，整轮重新生成。
    fun retry() {
        if (!checkConfigured()) return
        viewModelScope.launch {
            dropPlayback()
            dropFailedFragment()
            startStream()
        }
    }

    fun stop() {
        // 用户主动停止：未完成的 CAMERA 请求一并作废，避免停止后自动发帧。
        cameraRequested = false
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
            val draft = currentSession()?.history?.getOrNull(historyIndex)?.content
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

    // 点击选项：收起按钮并把选项文本回填输入框，改不改、发不发由用户决定。
    fun pickChoice(text: String) {
        _state.update { it.copy(choices = emptyList(), pendingDraft = text) }
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
        val session = currentSession() ?: return
        compressJob = viewModelScope.launch {
            _state.update { it.copy(compressing = true, showCompressConfirm = false) }
            try {
                val settings = container.settingsRepository.settings.value
                val result = container.contextCompressor.compact(session, settings)
                if (result == null) {
                    _toast.value = container.appContext.getString(R.string.compress_too_few)
                } else {
                    sessionRepo.applyCompaction(sessionId, result.summaryEntry, result.foldedCount)
                    refreshTokenEstimate()
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
        cancelAutoAdvance()
        _state.value.line?.let { _visibleSpeech.value = it.speech }
        _state.update { it.copy(typing = false, synthesizing = false, queueSize = 0) }
    }

    // 截断/压缩后重放剩余历史，恢复最后一句、立绘记忆与场景/BGM 状态。
    private suspend fun restoreFromHistory() {
        // 历史被重写，旧自动推进调度一并作废。
        cancelAutoAdvance()
        spriteMemory.clear()
        terminal = null
        parsedAnyDialog = false
        enqueuedDialogs = 0
        playedDialogs.clear()
        bgmStarted = false
        sceneNumber = null
        bgmNumber = null
        _state.update {
            it.copy(
                line = null,
                sprite = null,
                typing = false,
                queueSize = 0,
                error = null,
                backgroundImage = null,
                stats = emptyList(),
                choices = emptyList(),
            )
        }
        _visibleSpeech.value = ""
        val session = currentSession() ?: return
        replayHistory(session)
        initPresentationDefaults()
        refreshTokenEstimate()
    }

    fun consumeNotConfigured() {
        _state.update { it.copy(notConfigured = false) }
    }

    // 返回输入：失败轮按回溯处理——残片与该轮输入一并截出历史，原文回填输入框待改重发。
    fun dismissError() {
        viewModelScope.launch {
            val draft = rewindFailedTurn()
            restoreFromHistory()
            _state.update { it.copy(phase = Phase.WaitingInput, error = null, pendingDraft = draft) }
        }
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
                // 点击结算同样作废延迟中的自动推进，避免到点重复结算。
                cancelAutoAdvance()
                flushTtsNotices()
                _state.update { it.copy(phase = Phase.WaitingInput) }
                // 本轮含 CAMERA 请求：台词播空即等工具结果，直接发帧。
                if (cameraRequested) {
                    cameraRequested = false
                    sendCameraFrame(respectCooldown = false)
                }
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
        when (snapshot.phase) {
            // 报错界面直接退出：失败轮（残片 + 该轮输入）同样整轮回溯，不留给重进后的上下文。
            Phase.Error -> container.applicationScope.launch { rewindFailedTurn() }

            Phase.Streaming, Phase.Playing ->
                chatRepo.rawTextSoFar()?.takeIf { it.isNotBlank() }?.let { raw ->
                    container.applicationScope.launch {
                        sessionRepo.appendAssistantResult(sessionId, raw, complete = false)
                    }
                }

            else -> Unit
        }
        voicePlayer.release()
        // 离开聊天页即停 BGM；重进按历史记录恢复。
        bgmPlayer.stop()
        // 离开聊天页即停摄像头采集。
        cameraController?.stop()
        cameraController = null
        cleanTtsFiles()
    }

    // 音量滑条拖动中实时试听，不落盘。
    fun previewBgmVolume(volume: Float) {
        bgmPlayer.setVolume(volume)
    }

    // 滑条松手时持久化；收集器会把同一音量再同步一次给播放器。
    fun setBgmVolume(volume: Float) {
        viewModelScope.launch { container.settingsRepository.setBgmVolume(volume) }
    }

    // 视频聊天：预览视图供悬浮窗/全屏 AndroidView 复用。
    val cameraPreviewView: PreviewView?
        get() = cameraController?.previewView

    // 权限通过后由 UI 启动；旋转重建后以新 owner 重绑（PreviewView 实例不变，factory 只执行一次）。
    fun startCamera(owner: LifecycleOwner) {
        if (!videoChat) return
        val existing = cameraController
        if (existing != null) {
            existing.start(owner)
            return
        }
        val controller = CameraController(container.appContext)
        controller.changeThreshold = container.settingsRepository.cameraChangeThreshold.value
        cameraController = controller
        controller.start(owner)
        _state.update { it.copy(cameraActive = true) }
        viewModelScope.launch {
            // 画面大幅变化自动触发：受冷却与生成/播放状态约束（控制器只在视频会话创建）。
            controller.triggers.collect { sendCameraFrame(respectCooldown = true) }
        }
    }

    // 发帧：拍帧→落盘→user 消息（带图）→常规流式；respectCooldown=true 走空闲冷却（0=关），false 为 CAMERA 请求立即执行。
    private fun sendCameraFrame(respectCooldown: Boolean) {
        val controller = cameraController ?: return
        // 拍帧协程完成前状态守卫未更新，靠该标志防重入双发。
        if (cameraSending) return
        val snapshot = _state.value
        if (snapshot.phase == Phase.Error) return
        val busy = snapshot.streaming || snapshot.typing || snapshot.synthesizing ||
            queue.isNotEmpty()
        if (busy) {
            // 忙碌期间不判定冷却也不消耗触发，仅刷新忙碌尾点供冷却基准取较晚者。
            if (respectCooldown) lastBusyTriggerAt = System.currentTimeMillis()
            return
        }
        if (respectCooldown) {
            if (cameraCooldownSeconds == 0) return
            val now = System.currentTimeMillis()
            if (now - maxOf(lastFrameSentAt, lastBusyTriggerAt) < cameraCooldownSeconds * 1000L) return
        }
        cameraSending = true
        viewModelScope.launch {
            try {
                val bytes = controller.captureJpeg()
                if (bytes == null || bytes.isEmpty()) return@launch
                val filename = sessionRepo.saveCameraImage(sessionId, bytes)
                sessionRepo.appendEntry(
                    sessionId,
                    SessionEntry(
                        role = Role.user,
                        content = CAMERA_FRAME_TEXT,
                        image = filename,
                        statusContext = statusContextBlock(),
                    ),
                )
                lastFrameSentAt = System.currentTimeMillis()
                controller.resetBaseline()
                // 同轮闭环：续跑请求携带工具配对，台词队列与播放状态随 startStream 常规重置。
                startStream(cameraContinuation = true)
            } finally {
                // startStream 已同步置 streaming，守卫随之接管。
                cameraSending = false
            }
        }
    }

    // 冷却/阈值滑条松手落盘。
    fun setCameraCooldown(seconds: Int) {
        viewModelScope.launch { container.settingsRepository.setCameraCooldownSeconds(seconds) }
    }

    fun setCameraThreshold(threshold: Float) {
        viewModelScope.launch { container.settingsRepository.setCameraChangeThreshold(threshold) }
    }

    private fun checkConfigured(): Boolean {
        if (SettingsRepository.isConfigured(container.settingsRepository.settings.value)) return true
        _state.update { it.copy(notConfigured = true) }
        return false
    }

    private fun startStream(cameraContinuation: Boolean = false) {
        // 续跑请求需要上一段的工具元数据与思考块，先于重置取出；文本保留名路径不重构工具配对。
        val continuation = cameraContinuation && cameraViaTool
        val continuationSignature = if (continuation) cameraThoughtSignature else null
        val continuationThinking = if (continuation) cameraThinking else emptyList()
        if (!cameraContinuation) cameraCountThisTurn = 0
        val cameraBudget = (MAX_CAMERA_PER_TURN - cameraCountThisTurn).coerceAtLeast(0)
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
        cameraRequested = false
        cameraCut = false
        cameraViaTool = false
        cameraThoughtSignature = null
        cameraThinking = emptyList()
        _thinking.value = ""
        cancelAutoAdvance()
        _state.update {
            it.copy(
                phase = Phase.Streaming,
                streaming = true,
                error = null,
                queueSize = 0,
                synthesizing = false,
                // 新一轮开始即收起上一轮选项，数值面板跨轮保留。
                choices = emptyList(),
            )
        }
        // 串行合成消费者：逐条请求云 TTS，避免并发触发限流。
        prefetchJob = viewModelScope.launch { consumePrefetch() }
        streamJob = viewModelScope.launch {
            val session = currentSession()
            if (session == null) {
                _state.update { it.copy(streaming = false, phase = Phase.Error, error = "会话不存在") }
                return@launch
            }
            sessionVoiceLanguages = session.voiceLanguages
            val settings = container.settingsRepository.settings.value
            // buildInput 内部已切 Default；SSE 读取本身已在 IO。
            val input = sessionRepo.buildInput(session, continuation, continuationSignature, continuationThinking)
            chatRepo.getChatStream(settings, input, cameraTool = videoChat, cameraBudget = cameraBudget)
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
                // 首条立即开播；队列空且打字/合成空闲时自动续播，合成等待期间绝不重复推进。
                val idle = !snapshot.typing && !snapshot.synthesizing
                if ((snapshot.phase == Phase.Streaming && !snapshot.synthesizing) ||
                    (snapshot.queueSize == 0 && idle)
                ) {
                    advanceInternal()
                } else {
                    _state.update { it.copy(queueSize = queue.size) }
                }
            }

            is ChatStreamEvent.Thinking -> {
                // 只留尾部：长思维链不整段重排，展示随流滚动。
                _thinking.value = (_thinking.value + event.text).takeLast(THINKING_DISPLAY_CHARS)
            }

            is ChatStreamEvent.CameraRequested -> {
                // 工具命中即本段终点：计入有效内容（纯 CAMERA 段不判格式异常），其后文本已在仓库层丢弃。
                parsedAnyDialog = true
                cameraViaTool = true
                cameraThoughtSignature = event.thoughtSignature
                cameraCountThisTurn++
                cameraRequested = true
                cameraCut = true
            }

            is ChatStreamEvent.Completed -> {
                terminal = Terminal.Done
                // 本段思考块留给续跑请求原样带回（Anthropic 开思考时必需）。
                cameraThinking = event.thinking
                // 未解析出 dialog 时按格式错误保存残片。
                persistAssistant(event.rawText, complete = parsedAnyDialog)
                finishStream(if (parsedAnyDialog) null else "回复格式异常，未解析出对话内容")
                // 仅播放真正空闲（CAMERA-only 轮）才立即发帧；末条仍在打字/合成/待上屏时保留请求交正常结算，防吞请求与跳台词。
                if (cameraRequested && queue.isEmpty() && playbackIdle()) {
                    cameraRequested = false
                    _state.update { it.copy(phase = Phase.WaitingInput) }
                    sendCameraFrame(respectCooldown = false)
                }
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
                refreshTokenEstimate()
            }
        }
    }

    // 未播放任何 dialog 时立即报错，否则留待队列播完；回合结束统一刷新 token 估算。
    private suspend fun finishStream(error: String?) {
        _state.update {
            if (error == null) it.copy(streaming = false)
            else it.copy(streaming = false, phase = Phase.Error, error = error)
        }
        refreshTokenEstimate()
    }

    // 入队并按需发起云语音预取；语言目标只认会话快照，缺快照/translate 时跳过不回退本地语音。
    private fun enqueueDialog(dialog: Dialog) {
        // CAMERA 即本轮输出终点：记录请求即可，其后到达的台词按打断语义直接丢弃。
        if (cameraCut) return
        val kind = presentationKind(dialog.name)
        if (kind == PresentationKind.Camera) {
            // 旧文本保留名兼容路径；与工具路径共用次数上限。
            if (cameraCountThisTurn < MAX_CAMERA_PER_TURN) {
                cameraCountThisTurn++
                cameraRequested = true
                cameraCut = true
            }
            return
        }
        enqueuedDialogs++
        // SCENE/bgm/STAT/CHOICE 展示条目只改状态或弹选项，不参与台词展示与语音。
        if (kind != null) {
            queue.addLast(QueuedDialog(dialog, presentation = true))
            return
        }
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

    // 作废自动推进调度：任何推进、打断与历史重写路径统一调用。
    private fun cancelAutoAdvance() {
        autoAdvanceJob?.cancel()
        autoAdvanceJob = null
    }

    // 播放是否真正空闲：无打字/合成，且无待执行的推进与自动推进调度。
    private fun playbackIdle(): Boolean {
        val snapshot = _state.value
        return !snapshot.typing && !snapshot.synthesizing &&
            advanceJob?.isActive != true && autoAdvanceJob?.isActive != true
    }

    private fun advanceInternal() {
        // 任何推进（点击/自动到点）都先作废旧自动调度，避免双重推进。
        cancelAutoAdvance()
        // 展示条目立即生效并自动续播：不占点击、不上屏、不播语音。
        var drainedOnly = true
        while (true) {
            val item = queue.removeFirstOrNull() ?: break
            if (!item.presentation) {
                drainedOnly = false
                launchAdvance(item)
                return
            }
            applyPresentation(item.dialog)
            // 已生效即计入已播，打断截断时保留在记录里供恢复。
            playedDialogs.add(item.dialog)
            _state.update { it.copy(queueSize = queue.size) }
        }
        // 队列被展示条目排空且本轮没有台词上屏时直接结算终止状态，避免白费一次点击。
        if (drainedOnly && terminal != null) onBoxTap()
    }

    private fun launchAdvance(item: QueuedDialog) {
        val cloud = item.cloud
        val needsWait = cloud != null && !cloud.pending.isCompleted
        _state.update { it.copy(queueSize = queue.size, synthesizing = needsWait) }
        advanceJob = viewModelScope.launch {
            // 展示前阻塞：等待该条云语音就绪（通常预取已完成，等待为零）。
            val audio = cloud?.pending?.await()
            _state.update { it.copy(synthesizing = false) }
            // 语音完成信号须预创建（本地录音与云音频两处回调只负责 complete），无语音时由调度方丢弃。
            val voiceDone = if (videoChat) CompletableDeferred<Unit>() else null
            fun voiceCompleted(): (() -> Unit)? = voiceDone?.let { d -> { d.complete(Unit) } }
            val applied = applyDialog(
                item.dialog,
                _state.value.sprite,
                playVoice = true,
                boundCard = item.boundCard,
                suppressLocalVoice = item.suppressLocalVoice,
                onVoiceCompletion = voiceCompleted(),
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
                voicePlayer.play(audio, 1f, 1f, voiceCompleted())
                lastCloudAudio?.delete()
                lastCloudAudio = audio
            }
            if (videoChat) {
                // 确有语音（云音频或本地录音）才等待播完，否则信号无持有者会被直接丢弃。
                val waitVoice = voiceDone?.takeIf { audio != null || applied.voiceStarted }
                // 云语音念的就是台词，播完加尾停即推进；本地录音与纯文字仍按字数停留。
                scheduleAutoAdvance(applied.line.speech, waitVoice, followVoice = audio != null)
            }
        }
    }

    // 跟随云语音时等播完加尾停（且打字已结束）；本地录音/纯文字等播完与字数停留的较晚者；点击/打断统一取消。
    private fun scheduleAutoAdvance(
        speech: String,
        voiceDone: CompletableDeferred<Unit>?,
        followVoice: Boolean,
    ) {
        autoAdvanceJob?.cancel()
        autoAdvanceJob = viewModelScope.launch {
            coroutineScope {
                launch {
                    voiceDone?.let { it.await() }
                    delay(if (followVoice) VOICE_TAIL_MILLIS else autoHoldMillis(speech))
                }
                // 合成文本短于屏幕台词时语音可能先于打字结束，兜底等打字完再切。
                if (followVoice) launch { _state.first { !it.typing } }
            }
            // 先摘除自身再推进，避免 advanceInternal 内的统一取消作用到自己。
            autoAdvanceJob = null
            advanceInternal()
        }
    }

    // 无云语音台词的动态停留：按字数估算（打字 40ms/字 + 阅读约 400ms/字）加缓冲，钳制区间防呆滞。
    private fun autoHoldMillis(speech: String): Long =
        (speech.length * 400L + 800L).coerceIn(2_500L, 15_000L)

    // 行和立绘由调用方一次性提交，避免中间态。
    private data class AppliedDialog(val line: DisplayLine, val sprite: SpriteView?, val voiceStarted: Boolean = false)

    // 云语音承担绑定卡片角色的台词（本地录音让位）；主控不播本地录音；voiceStarted 标记是否发起播放。
    private fun applyDialog(
        dialog: Dialog,
        prev: SpriteView?,
        playVoice: Boolean,
        boundCard: VoiceCard? = null,
        suppressLocalVoice: Boolean = false,
        onVoiceCompletion: (() -> Unit)? = null,
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
        var voiceStarted = false
        if (playVoice && !suppressLocalVoice && boundCard == null && next != null &&
            (prev?.characterId != speaker.id || prev.spriteIndex != next.spriteIndex)
        ) {
            voiceStarted = playSpriteVoice(speaker, next.spriteIndex, onVoiceCompletion)
        }

        return AppliedDialog(
            line = DisplayLine(
                name = speaker.name,
                speech = dialog.speech,
                kind = LineKind.Character,
                speakerColorHex = speaker.color,
            ),
            sprite = (next ?: prev)?.let { it.copy(dimmed = it.characterId != speaker.id) },
            voiceStarted = voiceStarted,
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

    // 返回是否实际发起了本地录音播放；完成回调透传给 VoicePlayer。
    private fun playSpriteVoice(
        speaker: Character,
        index: Int,
        onCompletion: (() -> Unit)? = null,
    ): Boolean {
        val type = speaker.sprites.getOrNull(index)?.voiceType
        if (type != "preset" && type != "fallback") return false
        val voice = characterRepo.spriteVoice(speaker.id, index) ?: return false
        voicePlayer.play(voice, speaker.speechSpeed, speaker.speechVolume, onCompletion)
        return true
    }

    // 历史回放不播语音、不打字，仅重建行与各项状态；长历史解析较贵放 Default，状态切换等副作用留在主线程。
    private suspend fun replayHistory(session: ChatSession) {
        val parsed = withContext(Dispatchers.Default) {
            session.history
                .filter { it.complete }
                .map { entry ->
                    entry.role to if (entry.role == Role.assistant) {
                        DialogJsonParser.parseAll(entry.content)
                    } else {
                        emptyList()
                    }
                }
        }
        var lastApplied: AppliedDialog? = null
        var sprite = _state.value.sprite
        for ((role, dialogs) in parsed) {
            // 用户已回应过的选项不再弹出；数值状态不受后续用户消息影响。
            if (role == Role.user) {
                _state.update { it.copy(choices = emptyList()) }
                continue
            }
            for (dialog in dialogs) {
                if (presentationKind(dialog.name) != null) {
                    applyPresentation(dialog)
                    continue
                }
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
        // 工具路径原文不含 CAMERA 条目：落盘时合成标记条目，保持记录/回放形状与文本路径一致。
        val content = if (cameraViaTool) {
            DialogJsonParser.encode(
                DialogJsonParser.parseAll(rawText) + Dialog(name = CAMERA_MARKER_NAME, sprite = "-1"),
            )
        } else {
            truncateAfterCamera(rawText)
        }
        if (content.isBlank()) return
        sessionRepo.appendAssistantResult(sessionId, content, complete)
    }

    // CAMERA 即本轮输出终点：保留 CAMERA 及其之前的条目（标记随记录持久化），之后的台词丢弃。
    private fun truncateAfterCamera(rawText: String): String {
        if (!rawText.contains(CAMERA_MARKER_NAME) && !rawText.contains(CAMERA_ALIAS_NAME)) return rawText
        val dialogs = DialogJsonParser.parseAll(rawText)
        val cameraIndex = dialogs.indexOfFirst { presentationKind(it.name) == PresentationKind.Camera }
        if (cameraIndex < 0) return rawText
        return DialogJsonParser.encode(dialogs.subList(0, cameraIndex + 1))
    }

    companion object {
        // 单逻辑轮（含 CAMERA 续跑）画面请求上限；超限的调用静默忽略。
        private const val MAX_CAMERA_PER_TURN = 3

        // 视频会话云语音播完后的尾停：台词跟随语音推进，不再叠加字数停留。
        private const val VOICE_TAIL_MILLIS = 500L

        // 思维链展示只保留最近这些字符，限制单次文本重排规模。
        private const val THINKING_DISPLAY_CHARS = 1000
    }
}
