package com.dreemurr.shinsekai.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 跨分片增量扫描，只解码完整对象；缺字段、null、尾逗号和多余 } 宽容处理。
class DialogJsonParser {
    private val buffer = StringBuilder()
    private var pos = 0 // 不重扫已消费的字符。
    private var inArray = false
    private var objectStart = -1
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun append(delta: String): List<Dialog> {
        buffer.append(delta)
        if (!inArray) {
            val bracketIndex = buffer.indexOf('[', pos)
            if (bracketIndex < 0) {
                pos = buffer.length
                return emptyList()
            }
            pos = bracketIndex + 1
            inArray = true
        }

        val out = mutableListOf<Dialog>()
        while (pos < buffer.length) {
            val char = buffer[pos++]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth <= 0) objectStart = pos - 1
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth > 0) continue
                    // 多余 } 后恢复深度，避免丢弃后续对象。
                    depth = 0
                    if (objectStart < 0) continue
                    decodeDialog(buffer.substring(objectStart, pos))?.let(out::add)
                    objectStart = -1
                }
                ']' -> if (depth == 0) {
                    inArray = false
                    break
                }
            }
        }
        return out
    }

    companion object {
        // 接受模型照抄模板示例中的尾逗号。
        private val json = Json {
            ignoreUnknownKeys = true
            allowTrailingComma = true
        }

        fun parseAll(text: String): List<Dialog> = DialogJsonParser().append(text)

        private fun decodeDialog(text: String): Dialog? {
            val obj = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } ?: return null
            return Dialog(
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                // 保留 "01" 等原始编号，缺失或 null 回退 -1。
                sprite = (obj["sprite"] as? JsonPrimitive)?.contentOrNull ?: "-1",
                speech = (obj["speech"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                translate = (obj["translate"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }
}
