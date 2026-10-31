package com.dreemurr.shinsekai.chat

import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.session.SessionEntry
import java.io.IOException

// 手动上下文压缩：把较早记录折叠为一条摘要，system 与最近条目保留。
// 摘要作为 user 条目落盘，磁盘、记录面板与请求上下文保持一致。
class ContextCompressor(private val chatRepository: ChatRepository) {

    data class Compaction(val summaryEntry: SessionEntry, val foldedCount: Int)

    // 记录太少时返回 null；摘要请求失败抛 IOException 由调用方提示。
    suspend fun compact(session: ChatSession, settings: ChatSettings): Compaction? {
        val rest = session.history.drop(1)
        if (rest.size <= RECENT_ENTRIES) return null
        val folded = rest.dropLast(RECENT_ENTRIES)

        val user = buildString {
            append(SUMMARIZER_INSTRUCTION)
            for (line in capRecent(buildTranscript(folded))) {
                append("\n").append(line)
            }
        }
        val summaryText = chatRepository.summarize(settings, SUMMARIZER_SYSTEM, user)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("摘要响应为空")
        return Compaction(
            summaryEntry = SessionEntry(
                role = Role.user,
                content = SUMMARY_HEADER + summaryText + SUMMARY_FOOTER,
                summary = true,
            ),
            foldedCount = folded.size,
        )
    }

    // assistant 只喂 name: speech，不喂原始 JSON；摘要条目原样并入做滚动总结。
    private fun buildTranscript(entries: List<SessionEntry>): List<String> {
        val lines = ArrayList<String>(entries.size)
        for (entry in entries) {
            // 未完成残片不进摘要，与 buildInput 的过滤口径一致。
            if (!entry.complete) continue
            when {
                entry.summary -> lines.add(entry.content)
                entry.role == Role.user -> lines.add("用户：" + entry.content)
                entry.role == Role.assistant -> {
                    val dialogs = DialogJsonParser.parseAll(entry.content)
                    if (dialogs.isEmpty()) {
                        lines.add("助手：" + entry.content)
                    } else {
                        for (dialog in dialogs) lines.add("${dialog.name}：${dialog.speech}")
                    }
                }

                else -> Unit
            }
        }
        return lines
    }

    // 折叠内容过多时只取最近部分，避免摘要请求自身超出模型窗口；更早内容随压缩删除。
    private fun capRecent(lines: List<String>): List<String> {
        var total = 0
        val kept = ArrayList<String>()
        for (line in lines.asReversed()) {
            val cost = TokenEstimator.estimate(line)
            if (total + cost > TRANSCRIPT_MAX_TOKENS && kept.isNotEmpty()) break
            total += cost
            kept.add(line)
        }
        return kept.asReversed()
    }

    companion object {
        const val MAX_CONTEXT_TOKENS = 128_000
        const val RECENT_ENTRIES = 20

        // 摘要请求输入上限（约数），仅防御极端长历史。
        private const val TRANSCRIPT_MAX_TOKENS = 60_000

        private const val SUMMARIZER_SYSTEM =
            "你是一个专业的对话总结助手，擅长将长对话压缩成简洁准确的总结。请保留用户的重要信息和目前的对话话题。"

        private const val SUMMARIZER_INSTRUCTION = """
请将以下对话历史压缩成一个简洁的总结。总结应该：
1. 保留人物、人物关系、当前场景、关键事件与做出的承诺
2. 保留人物情绪与态度的变化、尚未解决的线索
3. 忽略重复内容、寒暄和无关细节
4. 按时间顺序，用第三人称中文陈述，保持客观准确
5. 总结不超过 500 字
6. 只输出总结正文，不要 JSON，不要任何前缀或解释

对话历史：
"""

        // 摘要与原项目一致：作为 user 消息插在 system 之后。
        private const val SUMMARY_HEADER = "【历史对话总结】以下是之前对话的压缩总结：\n"
        private const val SUMMARY_FOOTER = "\n\n请基于这个上下文继续对话。"
    }
}
