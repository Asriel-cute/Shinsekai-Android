package com.dreemurr.shinsekai.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// 角色与声音卡片的绑定关系：filesDir/voice_bindings.json 单文件 Map，临时文件原子替换。
// 角色管道只管导入，绑定关系由本仓库独立维护；同名覆盖重导会保留角色 id，绑定不丢。
class VoiceBindings(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private val _bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    val bindings: StateFlow<Map<String, String>> = _bindings.asStateFlow()

    // 仅启动时调用；损坏文件回退为空 Map。
    suspend fun load() = mutex.withLock {
        withContext(io) {
            _bindings.value = runCatching {
                voiceCardJson.decodeFromString(serializer, file.readText())
            }.getOrDefault(emptyMap())
        }
    }

    suspend fun bind(characterId: String, cardId: String) = mutate { it + (characterId to cardId) }

    suspend fun unbind(characterId: String) = mutate { it - characterId }

    // 删除卡片时清理指向它的全部绑定。
    suspend fun clearCard(cardId: String) = mutate { map -> map.filterValues { it != cardId } }

    private suspend fun mutate(transform: (Map<String, String>) -> Map<String, String>) = mutex.withLock {
        withContext(io) {
            val next = transform(_bindings.value)
            persist(next)
            _bindings.value = next
        }
    }

    // 先写临时文件再原子替换。
    private fun persist(map: Map<String, String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(voiceCardJson.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tmp.delete()
            throw e
        }
    }
}
