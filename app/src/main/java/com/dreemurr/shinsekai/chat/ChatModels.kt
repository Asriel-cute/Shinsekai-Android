package com.dreemurr.shinsekai.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningEffort { none, low, high, max }

@Serializable
enum class Role { user, assistant, system }

data class ChatSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val reasoningEffort: String = ReasoningEffort.none.name,
)

// none 也显式发送。
@Serializable
data class Reasoning(
    val effort: ReasoningEffort
)

// system 提示词快照随历史进入 input。
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<InputItem>,
    val stream: Boolean = true,
    val reasoning: Reasoning? = Reasoning(ReasoningEffort.none),
    val text: TextConfig = TextConfig(),
    // 固定 0.7 对齐桌面版默认采样温度，避免服务端缺省 1.0 造成风格漂移；encodeDefaults 保证显式发送。
    val temperature: Double = 0.7,
) {
    @Serializable
    data class TextConfig(
        val format: Format = Format()
    ) {
        @Serializable
        data class Format(
            val type: String = "json_object"
        )
    }
}

@Serializable
data class InputItem(
    val type: String = "message",
    val role: Role,
    val content: String
)

// 未知字段和事件类型忽略。
@Serializable
data class ResponsesStreamEvent(
    val type: String? = null,
    val delta: String? = null,
    val response: Response? = null
) {
    @Serializable
    data class Response(
        val error: Error? = null
    )
    @Serializable
    data class Error(
        val message: String? = null
    )
}

// {dialog:[{name,sprite,speech}]}；sprite 是 1 基编号字符串，"-1" 不切换立绘。
@Serializable
data class Dialog(
    val name: String = "",
    val sprite: String = "-1",
    val speech: String = ""
)
