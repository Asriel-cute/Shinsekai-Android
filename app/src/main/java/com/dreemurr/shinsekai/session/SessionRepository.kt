package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.chat.InputItem
import com.dreemurr.shinsekai.chat.Role
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
    rootDir: File,
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

    // 现场生成提示词快照，作为首条 system 消息落盘。
    suspend fun create(
        characters: List<Character>,
        scenario: String,
    ): ChatSession = mutex.withLock {
        withContext(io) {
            val now = System.currentTimeMillis()
            val names = characters.map { it.name }
            val system = TemplateGenerator.generate(characters)
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = if (names.size <= 2) {
                    names.joinToString("、")
                } else {
                    names.take(2).joinToString("、") + " 等 ${names.size} 人"
                },
                characterIds = characters.map { it.id },
                scenario = scenario,
                history = listOf(
                    SessionEntry(
                        role = Role.system,
                        content = TemplateGenerator.composeForLlm(scenario, system),
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

    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(io) {
            val deleted = store.delete(id)
            if (deleted) _sessions.value = _sessions.value.filterNot { it.id == id }
            deleted
        }
    }

    suspend fun rename(id: String, title: String): ChatSession? = update(id) { it.copy(title = title) }

    suspend fun appendEntry(id: String, entry: SessionEntry): ChatSession? =
        update(id) { it.copy(history = it.history + entry) }

    // 结尾已有未完成回复时，新的残片替换它，避免同轮残片堆积。
    suspend fun appendAssistantResult(id: String, rawText: String, complete: Boolean) {
        if (rawText.isBlank()) return
        update(id) { session ->
            val last = session.history.lastOrNull()
            val replaceTail = !complete && last != null && last.role == Role.assistant && !last.complete
            val history = (if (replaceTail) session.history.dropLast(1) else session.history) +
                SessionEntry(role = Role.assistant, content = rawText, complete = complete)
            session.copy(history = history)
        }
    }

    // 保留 system 顺序，排除未完成条目。
    fun buildInput(session: ChatSession): List<InputItem> =
        session.history.mapNotNull { entry ->
            if (entry.complete) InputItem(role = entry.role, content = entry.content) else null
        }

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
