package com.dreemurr.shinsekai.chat

// 数值面板条目；icon 为保留图标名，max 仅在解析出正数时保留。
data class StatItem(
    val icon: String,
    val label: String,
    val value: Double,
    val max: Double? = null,
) {
    // 整数值不带小数点，面板展示与注入块共用同一口径。
    val valueText: String = formatNumber(value)
    val maxText: String? = max?.let(::formatNumber)
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

// STAT 的 speech 解析：每行 icon|label|value|max（max 可省），兼容旧式「名称: 值/上限」；无 Android 依赖，整表替换由调用方决定。
object StatPayload {
    const val MAX_ITEMS = 8

    private val ICONS = setOf(
        "clock", "coins", "gauge", "heart", "shield", "sparkles", "star", "target", "zap",
    )

    private val ICON_ALIASES = mapOf(
        "affinity" to "sparkles",
        "coin" to "coins",
        "defense" to "shield",
        "energy" to "zap",
        "favour" to "sparkles",
        "favor" to "sparkles",
        "gold" to "coins",
        "health" to "heart",
        "hp" to "heart",
        "level" to "star",
        "life" to "heart",
        "love" to "sparkles",
        "money" to "coins",
        "progress" to "target",
        "stamina" to "zap",
        "task" to "target",
        "time" to "clock",
    )

    // 图标写错时按名称关键词回落，全不匹配用 gauge。
    private val LABEL_HINTS = listOf(
        listOf("hp", "health", "life", "生命", "体力", "血量") to "heart",
        listOf("shield", "defense", "护盾", "防御") to "shield",
        listOf("energy", "stamina", "精力", "能量", "体力值") to "zap",
        listOf("affinity", "favor", "favour", "love", "好感", "亲密") to "sparkles",
        listOf("gold", "coin", "money", "金币", "金钱") to "coins",
        listOf("level", "rank", "等级", "级别") to "star",
        listOf("task", "quest", "progress", "任务", "进度") to "target",
        listOf("time", "day", "hour", "时间", "天数") to "clock",
    )

    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(?:div|li|p)\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val LEGACY = Regex(
        "^\\s*([^:：|]{1,48})\\s*[:：]\\s*([-+]?\\d[\\d,]*(?:\\.\\d+)?)" +
            "(?:\\s*/\\s*([-+]?\\d[\\d,]*(?:\\.\\d+)?))?\\s*$",
    )

    fun parse(speech: String): List<StatItem> {
        val stats = mutableListOf<StatItem>()
        for (line in statLines(speech)) {
            val fields = preferredFields(line) ?: legacyFields(line) ?: continue
            val label = fields[1].trim().take(48)
            if (label.isEmpty()) continue
            val value = number(fields[2]) ?: continue
            val max = number(fields[3])?.takeIf { it > 0 }
            stats += StatItem(icon = icon(fields[0], label), label = label, value = value, max = max)
            if (stats.size >= MAX_ITEMS) break
        }
        return stats
    }

    // 容忍 <br>、块级结束标签与其余 HTML；模型偶尔把换行双重转义成字面 \n。
    private fun statLines(speech: String): List<String> =
        speech.replace(BR, "\n")
            .replace(BLOCK_END, "\n")
            .replace(TAG, "")
            .replace("\\n", "\n")
            .let(::unescapeEntities)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private fun unescapeEntities(text: String): String =
        if (!text.contains('&')) {
            text
        } else {
            text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }

    // icon|label|value[|max]；未给 max 时允许 value 写成 值/上限。
    private fun preferredFields(line: String): Array<String>? {
        val parts = line.split("|").map { it.trim() }
        if (parts.size !in 3..4) return null
        var value = parts[2]
        var maximum = if (parts.size == 4) parts[3] else ""
        if (maximum.isEmpty() && "/" in value) {
            val halves = value.split("/", limit = 2).map { it.trim() }
            value = halves[0]
            maximum = halves[1]
        }
        return arrayOf(parts[0], parts[1], value, maximum)
    }

    private fun legacyFields(line: String): Array<String>? {
        val match = LEGACY.matchEntire(line) ?: return null
        return arrayOf("", match.groupValues[1].trim(), match.groupValues[2], match.groupValues[3])
    }

    private fun number(raw: String): Double? {
        val normalized = raw.trim().replace(",", "")
        if (normalized.isEmpty()) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun icon(raw: String, label: String): String {
        val normalized = raw.trim().lowercase()
        ICON_ALIASES[normalized]?.let { return it }
        if (normalized in ICONS) return normalized
        val folded = label.lowercase()
        for ((hints, fallback) in LABEL_HINTS) {
            if (hints.any { folded.contains(it) }) return fallback
        }
        return "gauge"
    }
}
