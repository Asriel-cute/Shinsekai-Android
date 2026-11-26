package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.background.Background
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.CAMERA_ALIAS_NAME
import com.dreemurr.shinsekai.chat.CAMERA_MARKER_NAME
import com.dreemurr.shinsekai.chat.CAMERA_TOOL_NAME
import com.dreemurr.shinsekai.chat.CAMERA_TOOL_RESULT
import com.dreemurr.shinsekai.chat.CHOICE_ALIAS_NAME
import com.dreemurr.shinsekai.chat.CHOICE_MARKER_NAME
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.InputItem
import com.dreemurr.shinsekai.chat.InputType
import com.dreemurr.shinsekai.chat.PresentationKind
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.chat.STAT_ALIAS_NAME
import com.dreemurr.shinsekai.chat.STAT_MARKER_NAME
import com.dreemurr.shinsekai.chat.ThinkingPart
import com.dreemurr.shinsekai.chat.presentationKind
import com.dreemurr.shinsekai.template.TemplateGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// 单写 Mutex 保护；写盘成功后才更新内存状态。
class SessionRepository(
    private val rootDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = SessionStore(rootDir)
    private val mutex = Mutex()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    // 仅启动时调用。
    suspend fun load() = mutex.withLock {
        withContext(io) {
            store.ensureLayout()
            _sessions.value = store.loadAll()
        }
    }

    // 现场生成提示词快照，作为首条 system 消息落盘；名称留空按角色自动命名。
    suspend fun create(
        characters: List<Character>,
        scenario: String,
        voiceLanguages: Map<String, String> = emptyMap(),
        cloudTtsEnabled: Boolean = true,
        playerCharacterId: String? = null,
        background: Background? = null,
        title: String? = null,
        chatType: ChatType = ChatType.TEXT,
        statEnabled: Boolean = false,
        choiceEnabled: Boolean = false,
    ): ChatSession = mutex.withLock {
        withContext(io) {
            val now = System.currentTimeMillis()
            val scenarioText = scenario.trim()
            val names = characters.map { it.name }
            val ids = characters.map { it.id }
            val idSet = ids.toSet()
            val voiceSnapshot = voiceLanguages.filterKeys { it in idSet }
            val playerId = normalizePlayerId(playerCharacterId, characters, chatType)
            val system = TemplateGenerator.generate(
                characters,
                voiceSnapshot,
                playerId,
                background,
                videoChat = chatType == ChatType.VIDEO,
                statEnabled = statEnabled,
                choiceEnabled = choiceEnabled,
            )
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: autoTitle(names),
                characterIds = ids,
                playerCharacterId = playerId,
                voiceLanguages = voiceSnapshot,
                cloudTtsEnabled = cloudTtsEnabled,
                statEnabled = statEnabled,
                choiceEnabled = choiceEnabled,
                backgroundId = background?.id,
                chatType = chatType,
                scenario = scenarioText,
                history = listOf(
                    SessionEntry(
                        role = Role.system,
                        content = TemplateGenerator.composeForLlm(scenarioText, system),
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            )
            store.write(session)
            _sessions.value = listOf(session) + _sessions.value
            session
        }
    }

    // 会话设置页保存：按当前角色/场景/背景重生成 system 快照，其余记录原样保留；名称留空保持原标题。
    suspend fun updateSetup(
        id: String,
        characters: List<Character>,
        scenario: String,
        voiceLanguages: Map<String, String> = emptyMap(),
        cloudTtsEnabled: Boolean = true,
        playerCharacterId: String? = null,
        background: Background? = null,
        title: String? = null,
        chatType: ChatType = ChatType.TEXT,
        statEnabled: Boolean = false,
        choiceEnabled: Boolean = false,
    ): ChatSession? = update(id) { session ->
        val ids = characters.map { it.id }
        val idSet = ids.toSet()
        val voiceSnapshot = voiceLanguages.filterKeys { it in idSet }
        val playerId = normalizePlayerId(playerCharacterId, characters, chatType)
        val system = TemplateGenerator.composeForLlm(
            scenario.trim(),
            TemplateGenerator.generate(
                characters,
                voiceSnapshot,
                playerId,
                background,
                videoChat = chatType == ChatType.VIDEO,
                statEnabled = statEnabled,
                choiceEnabled = choiceEnabled,
            ),
        )
        val history = if (session.history.firstOrNull()?.role == Role.system) {
            session.history.mapIndexed { index, entry ->
                if (index == 0) entry.copy(content = system) else entry
            }
        } else {
            listOf(SessionEntry(role = Role.system, content = system)) + session.history
        }
        session.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: session.title,
            characterIds = ids,
            playerCharacterId = playerId,
            voiceLanguages = voiceSnapshot,
            cloudTtsEnabled = cloudTtsEnabled,
            statEnabled = statEnabled,
            choiceEnabled = choiceEnabled,
            backgroundId = background?.id,
            chatType = chatType,
            scenario = scenario.trim(),
            history = history,
        )
    }

    // 回溯：截断到 keep（不含）；keep 至少保留 system 快照。
    suspend fun truncate(id: String, keep: Int): ChatSession? = update(id) { session ->
        session.copy(history = session.history.take(keep.coerceIn(1, session.history.size)))
    }

    // 打断播放：最后一条 assistant 只保留已播出的 dialog；content 为空则整条丢弃。
    suspend fun truncateLastAssistant(id: String, content: String): ChatSession? = update(id) { session ->
        val last = session.history.lastOrNull()
        if (last?.role != Role.assistant) return@update session
        val history = if (content.isBlank()) {
            session.history.dropLast(1)
        } else {
            session.history.dropLast(1) + last.copy(content = content)
        }
        session.copy(history = history)
    }

    // 手动压缩：把前 foldedCount 条非 system 记录替换为一条摘要。
    suspend fun applyCompaction(
        id: String,
        summary: SessionEntry,
        foldedCount: Int,
    ): ChatSession? = update(id) { session ->
        val rest = session.history.drop(1)
        val kept = rest.drop(foldedCount.coerceIn(0, rest.size))
        session.copy(history = listOf(session.history.first(), summary) + kept)
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(io) {
            val deleted = store.delete(id)
            if (deleted) {
                // 连带清理摄像头帧目录；失败仅记录，不影响会话删除。
                camDir(id).deleteRecursively()
                _sessions.value = _sessions.value.filterNot { it.id == id }
            }
            deleted
        }
    }

    suspend fun rename(id: String, title: String): ChatSession? = update(id) { it.copy(title = title) }

    suspend fun appendEntry(id: String, entry: SessionEntry): ChatSession? =
        update(id) { it.copy(history = it.history + entry) }

    // 结尾已有未完成回复时一律替换：重试成功的新回复也顶掉旧残片，避免残片悬挂在完整回复前。
    suspend fun appendAssistantResult(id: String, rawText: String, complete: Boolean) {
        if (rawText.isBlank()) return
        update(id) { session ->
            val last = session.history.lastOrNull()
            val replaceTail = last != null && last.role == Role.assistant && !last.complete
            val history = (if (replaceTail) session.history.dropLast(1) else session.history) +
                SessionEntry(role = Role.assistant, content = rawText, complete = complete)
            session.copy(history = history)
        }
    }

    // 摄像头帧落盘 sessions/cam/{sessionId}/{时间戳}.jpg，只保留最近一张并返回文件名；更早 image 条目构建请求时降级纯文字。
    suspend fun saveCameraImage(sessionId: String, bytes: ByteArray): String = mutex.withLock {
        withContext(io) {
            val dir = camDir(sessionId)
            dir.mkdirs()
            val name = "${System.currentTimeMillis()}.jpg"
            val tmp = File(dir, "$name.part")
            val target = File(dir, name)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                dir.listFiles()?.forEach { if (it != target) it.delete() }
            } catch (e: Exception) {
                e.printStackTrace()
                tmp.delete()
                target.delete()
            }
            name
        }
    }

    // 续跑展开的工具配对与思考块仅本次请求生效、不落盘，后续回合按普通消息序列化；全历史解析较贵，统一切 Default。
    suspend fun buildInput(
        session: ChatSession,
        cameraContinuation: Boolean = false,
        cameraThoughtSignature: String? = null,
        cameraThinking: List<ThinkingPart> = emptyList(),
    ): List<InputItem> = withContext(Dispatchers.Default) {
        val lastImageIndex = session.history.indexOfLast { it.complete && it.image != null }
        val items = session.history.mapIndexedNotNull { index, entry ->
            if (!entry.complete) return@mapIndexedNotNull null
            val content = if (entry.role == Role.assistant) {
                stripPresentationDialogs(entry.content, session.statEnabled, session.choiceEnabled)
            } else {
                entry.content
            }
            if (content.isEmpty()) return@mapIndexedNotNull null
            val imagePath = entry.image?.takeIf { index == lastImageIndex }
                ?.let { name -> File(camDir(session.id), name).absolutePath }
            val text = if (entry.role == Role.user && entry.statusContext != null) {
                entry.statusContext + "\n\n" + content
            } else {
                content
            }
            InputItem(role = entry.role, content = text, imagePath = imagePath)
        }.toMutableList()
        if (cameraContinuation) {
            val last = items.lastOrNull()
            if (last != null && last.role == Role.user && last.imagePath != null) {
                val callId = "call_camera_${session.history.size}"
                items.add(
                    items.size - 1,
                    InputItem(
                        type = InputType.function_call,
                        role = Role.assistant,
                        content = "{}",
                        callId = callId,
                        toolName = CAMERA_TOOL_NAME,
                        thoughtSignature = cameraThoughtSignature,
                        thinking = cameraThinking.takeIf { it.isNotEmpty() },
                    ),
                )
                items.add(
                    items.size - 1,
                    InputItem(
                        type = InputType.function_call_output,
                        role = Role.user,
                        content = CAMERA_TOOL_RESULT,
                        callId = callId,
                        toolName = CAMERA_TOOL_NAME,
                    ),
                )
            }
        }
        items
    }

    // 与上游一致保留模型写的展示条目做格式强化（剥掉模型会逐渐不再输出）；只剥 CAMERA 合成标记与已关闭的 STAT/CHOICE，全剥返回空串、无可剥保持原文。
    private fun stripPresentationDialogs(
        content: String,
        statEnabled: Boolean,
        choiceEnabled: Boolean,
    ): String {
        // 原文预筛（剥离规则的超集）：带引号匹配排除台词正文里的同名词，避免每轮白解析；误命中只是多解析一条。
        val maybeCamera = content.containsQuoted(CAMERA_MARKER_NAME) || content.containsQuoted(CAMERA_ALIAS_NAME)
        val maybeStat = !statEnabled &&
            (content.containsQuoted(STAT_MARKER_NAME) || content.containsQuoted(STAT_ALIAS_NAME))
        val maybeChoice = !choiceEnabled &&
            (content.containsQuoted(CHOICE_MARKER_NAME) || content.containsQuoted(CHOICE_ALIAS_NAME))
        if (!maybeCamera && !maybeStat && !maybeChoice) return content
        val dialogs = DialogJsonParser.parseAll(content)
        val kept = dialogs.filterNot { dialog ->
            when (presentationKind(dialog.name)) {
                PresentationKind.Camera -> true
                PresentationKind.Stat -> !statEnabled
                PresentationKind.Choice -> !choiceEnabled
                else -> false
            }
        }
        if (kept.size == dialogs.size) return content
        if (kept.isEmpty()) return ""
        return DialogJsonParser.encode(kept)
    }

    // 解析器只认双引号字段，保留名命中时原文里必有 "名字" 这一段。
    private fun String.containsQuoted(name: String): Boolean = contains("\"$name\"")

    // 主控至少要有对手戏角色且在选角内；失效按未设置处理。视频会话与主控规则互斥，固定为空。
    private fun normalizePlayerId(
        playerCharacterId: String?,
        characters: List<Character>,
        chatType: ChatType,
    ): String? = playerCharacterId
        ?.takeIf { chatType == ChatType.TEXT }
        ?.takeIf { id -> characters.size >= 2 && characters.any { it.id == id } }

    // 未命名会话按出场角色自动命名。
    private fun autoTitle(names: List<String>): String =
        if (names.size <= 2) {
            names.joinToString("、")
        } else {
            names.take(2).joinToString("、") + " 等 ${names.size} 人"
        }

    // 摄像头帧目录与 sessions/{id}.json 同级，不干扰 *.json 扫描。
    private fun camDir(sessionId: String) = File(rootDir, "cam/$sessionId")

    private suspend fun update(
        id: String,
        transform: (ChatSession) -> ChatSession,
    ): ChatSession? = mutex.withLock {
        withContext(io) {
            val current = _sessions.value.firstOrNull { it.id == id } ?: return@withContext null
            val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
            store.write(updated)
            _sessions.value = _sessions.value
                .map { if (it.id == id) updated else it }
                .sortedByDescending { it.updatedAt }
            updated
        }
    }
}
