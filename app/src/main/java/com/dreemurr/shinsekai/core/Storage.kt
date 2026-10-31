package com.dreemurr.shinsekai.core

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// 全仓库共享的落盘 JSON 配置：宽容读取、默认值输出、pretty 打印。
internal val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

// 临时文件写完后再原子替换；renameTo 失败时用 Files.move 兜底，失败保留旧文件。
internal fun writeTextAtomic(target: File, text: String) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        target.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        tmp.delete()
        throw e
    }
}

// 文件名清洗规则（正则预编译）。
private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\x00-\\x1f<>:\"/\\\\|?*]")

// 清理非法字符与首尾空白，超长截断保留扩展名；空名回退 fallback。
internal fun cleanFileName(raw: String, fallback: String = "asset"): String {
    val name = ILLEGAL_FILE_NAME_CHARS.replace(raw, "_").trim(' ', '.')
    if (name.isEmpty()) return fallback
    if (name.length <= 120) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot) else ""
    return name.substring(0, 120 - ext.length) + ext
}

// 冲突时在扩展名前加 -n 序号；判重来源由调用方的 used 集合提供。
internal fun uniqueAssetName(name: String, used: MutableSet<String>): String {
    if (name !in used) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var index = 1
    var candidate = "$stem-$index$ext"
    while (candidate in used) {
        index++
        candidate = "$stem-$index$ext"
    }
    return candidate
}
