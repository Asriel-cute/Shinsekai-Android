package com.dreemurr.shinsekai.chat

import kotlin.math.roundToInt

// 无字典的轻量 token 估算：CJK/假名/谚文按一字一代，ASCII 约 3.3 字符一代。
// 仅用于触发提示与展示，不追求精确。
object TokenEstimator {
    private const val ENTRY_OVERHEAD = 4

    fun estimate(text: String): Int {
        var units = 0.0
        for (char in text) units += weight(char.code)
        return units.roundToInt()
    }

    fun estimate(items: List<InputItem>): Int =
        items.sumOf { ENTRY_OVERHEAD + estimate(it.content) }

    private fun weight(code: Int): Double = when (code) {
        in 0x3040..0x30FF, in 0x31F0..0x31FF, // 假名
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, // CJK
        in 0xAC00..0xD7AF, in 0xF900..0xFAFF, // 谚文 / CJK 兼容
        -> 1.0

        in 0x00..0x7F -> 0.3
        else -> 0.6
    }
}
