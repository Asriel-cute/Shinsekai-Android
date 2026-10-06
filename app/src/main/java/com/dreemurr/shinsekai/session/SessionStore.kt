package com.dreemurr.shinsekai.session

import kotlinx.serialization.json.Json
import java.io.File

// 临时文件写完后改名，避免直接写入半截 JSON。
internal class SessionStore(private val rootDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

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
                    json.decodeFromString<ChatSession>(f.readText())
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            .sortedByDescending { it.updatedAt }

    fun write(session: ChatSession) {
        val file = sessionFile(session.id)
        val tmp = File(rootDir, "${session.id}.json.tmp")
        tmp.writeText(json.encodeToString(session))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("无法写入会话文件 ${session.id}")
            }
        }
    }

    fun delete(id: String): Boolean = sessionFile(id).delete()
}
