package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.core.appJson
import com.dreemurr.shinsekai.core.writeTextAtomic
import java.io.File
import java.io.IOException

// 临时文件写完后原子替换，避免直接写入半截 JSON。
internal class SessionStore(private val rootDir: File) {

    fun ensureLayout() {
        rootDir.mkdirs()
    }

    private fun sessionFile(id: String) = File(rootDir, "$id.json")

    // 损坏文件跳过，最近活跃会话在前。
    fun loadAll(): List<ChatSession> =
        rootDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f ->
                try {
                    appJson.decodeFromString<ChatSession>(f.readText())
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            .sortedByDescending { it.updatedAt }

    fun write(session: ChatSession) {
        try {
            writeTextAtomic(sessionFile(session.id), appJson.encodeToString(session))
        } catch (e: IOException) {
            throw IOException("无法写入会话文件 ${session.id}", e)
        }
    }

    fun delete(id: String): Boolean = sessionFile(id).delete()
}
