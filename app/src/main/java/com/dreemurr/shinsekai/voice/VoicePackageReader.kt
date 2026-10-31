package com.dreemurr.shinsekai.voice

import com.dreemurr.shinsekai.core.cleanFileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// .voice 语音包解析结果；字段缺失时对应项为 null，不视为失败。
class VoicePackage(
    val name: String,
    val lang: String?,
    val cloneAudio: File?,
    val promptAudio: File?,
    val promptText: String?,
)

sealed interface VoicePackageResult {
    data class Success(val pkg: VoicePackage) : VoicePackageResult
    data class Failure(val message: String) : VoicePackageResult
}

// .voice = ZIP（manifest.json + 音频）；先 ZipFile 直读，失败回退 ZipInputStream。
object VoicePackageReader {

    // 解析并把引用的音频解包到 outDir，音频仅在克隆流程中临时使用。
    fun read(source: File, outDir: File): VoicePackageResult {
        return try {
            ZipFile(source).use { zip -> readFrom(zip, outDir) }
        } catch (e: IOException) {
            e.printStackTrace()
            // 回退流式读取，兼容非常规 ZIP。
            try {
                readFromStream(source, outDir)
            } catch (e2: Exception) {
                e2.printStackTrace()
                VoicePackageResult.Failure("语音包已损坏或格式不受支持")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            VoicePackageResult.Failure("语音包解析失败")
        }
    }

    private fun readFrom(zip: ZipFile, outDir: File): VoicePackageResult {
        val manifest = zip.getEntry(MANIFEST) ?: return VoicePackageResult.Failure("语音包缺少 manifest.json")
        // manifest 读取的 IO 异常向上抛，由 read() 回退到流式路径。
        val manifestText = zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).readText()
        return assemble(manifestText) { path ->
            extract(zip.getEntry(entryKey(path)), zip, outDir)
        }
    }

    private fun readFromStream(source: File, outDir: File): VoicePackageResult {
        var manifestText: String? = null
        val entries = mutableMapOf<String, ByteArray>()
        source.inputStream().buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = readCapped(zip, MAX_STREAM_ENTRY_BYTES + 1)
                    // 超大条目不缓存直接跳过，避免兜底路径整读进内存。
                    if (bytes.size > MAX_STREAM_ENTRY_BYTES) continue
                    if (entry.name == MANIFEST) manifestText = bytes.toString(Charsets.UTF_8)
                    else entries[entry.name] = bytes
                }
            }
        }
        return assemble(manifestText) { path ->
            if (path == null) {
                null
            } else {
                entries[entryKey(path)]?.let { materialize(outDir, path, it) }
            }
        }
    }

    // 统一组装：校验 manifest 与角色名，音频由 fetch 按包内路径获取。
    private fun assemble(
        manifestText: String?,
        fetch: (String?) -> File?,
    ): VoicePackageResult {
        val text = manifestText ?: return VoicePackageResult.Failure("语音包缺少 manifest.json")
        val obj = runCatching { parseJson(text) }
            .getOrElse { return VoicePackageResult.Failure("manifest.json 解析失败") }
        val name = obj.str("name").orEmpty()
        if (name.isBlank()) return VoicePackageResult.Failure("语音包缺少角色名")
        return VoicePackageResult.Success(
            VoicePackage(
                name = name,
                lang = obj.str("lang"),
                cloneAudio = fetch(obj.str("clone_path")),
                promptAudio = fetch(obj.str("prompt_path")),
                promptText = obj.str("prompt_text"),
            ),
        )
    }

    // 统一以 / 分隔符匹配包内条目。
    private fun entryKey(path: String?): String? = path?.replace('\\', '/')?.trim()?.takeIf { it.isNotBlank() }

    // 至多读取 limit 字节（超出即返回满 limit，由调用方判大小丢弃）。
    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(limit.coerceAtMost(STREAM_CHUNK_BYTES))
        val chunk = ByteArray(STREAM_CHUNK_BYTES)
        var total = 0
        while (total < limit) {
            val read = input.read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            out.write(chunk, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun extract(entry: ZipEntry?, zip: ZipFile, outDir: File): File? {
        if (entry == null) return null
        return runCatching {
            val target = uniqueTarget(outDir, entry.name)
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        }.getOrNull()
    }

    private fun materialize(outDir: File, path: String?, bytes: ByteArray): File? {
        if (path == null) return null
        return runCatching {
            val target = uniqueTarget(outDir, path)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    // 保留原文件名，清理非法字符；冲突时加序号（磁盘判重）。
    private fun uniqueTarget(outDir: File, entryPath: String): File {
        val safe = cleanFileName(entryPath.substringAfterLast('/'), fallback = "audio")
        var candidate = File(outDir, safe)
        var index = 1
        while (candidate.exists()) {
            val dot = safe.lastIndexOf('.')
            val stem = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            candidate = File(outDir, "$stem-${index++}$ext")
        }
        return candidate
    }

    private fun parseJson(text: String) = lenientJson.parseToJsonElement(text).jsonObject

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // 流式回退为兜底路径，单个条目至多缓存 64MB。
    private const val MAX_STREAM_ENTRY_BYTES = 64 * 1024 * 1024
    private const val STREAM_CHUNK_BYTES = 64 * 1024

    private const val MANIFEST = "manifest.json"

    private val lenientJson = Json { ignoreUnknownKeys = true }
}
