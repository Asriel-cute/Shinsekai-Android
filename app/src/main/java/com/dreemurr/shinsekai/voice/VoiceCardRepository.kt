package com.dreemurr.shinsekai.voice

import com.dreemurr.shinsekai.settings.ApiKeyCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

// 声音卡片存 filesDir/voice_cards.json 单文件列表，临时文件原子替换；按创建时间倒序。
class VoiceCardRepository(
    private val file: File,
    private val cipher: ApiKeyCipher = ApiKeyCipher(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    private val _cards = MutableStateFlow<List<VoiceCard>>(emptyList())
    val cards: StateFlow<List<VoiceCard>> = _cards.asStateFlow()

    fun newCardId(): String = UUID.randomUUID().toString()

    fun encryptKey(plain: String): String = cipher.encrypt(plain)

    fun decryptKey(card: VoiceCard): String = cipher.decrypt(card.apiKeyCipher)

    // 仅启动时调用；损坏文件回退为空列表，未知服务商条目跳过。
    suspend fun load() = mutex.withLock {
        withContext(io) {
            _cards.value = runCatching {
                voiceCardJson.decodeFromString(voiceCardListSerializer, file.readText())
            }.getOrDefault(emptyList())
                .filter { it.providerEnum != null }
                .sortedByDescending { it.createdAt }
        }
    }

    // 按 id 替换或追加。
    suspend fun upsert(card: VoiceCard) = mutate { list ->
        list.filterNot { it.id == card.id } + card
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    // 计算 next → 持久化 → 发布，统一按创建时间倒序。
    private suspend fun mutate(transform: (List<VoiceCard>) -> List<VoiceCard>) = mutex.withLock {
        withContext(io) {
            val next = transform(_cards.value).sortedByDescending { it.createdAt }
            persist(next)
            _cards.value = next
        }
    }

    // 先写临时文件再原子替换。
    private fun persist(cards: List<VoiceCard>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(voiceCardJson.encodeToString(voiceCardListSerializer, cards))
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
