package com.dreemurr.shinsekai.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningEffort { none, low, high, max }

@Serializable
enum class Role { user, assistant, system }

enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
}

// OpenAI 两种接口形态，共用同一套连接配置。
enum class OpenAiApiType { RESPONSES, CHAT_COMPLETIONS }

// 单个提供商的连接配置；apiType 仅 OPENAI 使用。
data class ProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val apiType: OpenAiApiType = OpenAiApiType.RESPONSES,
)

// 设置页编辑的全量配置：激活提供商 + 各提供商独立配置 + 全局推理档位。
data class AllProviderSettings(
    val activeProvider: LlmProvider = LlmProvider.OPENAI,
    val configs: Map<LlmProvider, ProviderConfig> = LlmProvider.entries.associateWith { ProviderConfig() },
    val reasoningEffort: String = ReasoningEffort.none.name,
)

// 聊天链路使用的有效配置（激活提供商的配置展开）。
data class ChatSettings(
    val provider: LlmProvider = LlmProvider.OPENAI,
    val apiType: OpenAiApiType = OpenAiApiType.RESPONSES,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val reasoningEffort: String = ReasoningEffort.none.name,
)

// input 条目类型；后两者仅用于 CAMERA 工具续跑请求的尾部配对，不落盘。
@Serializable
enum class InputType { message, function_call, function_call_output }

// 续跑原样带回：Anthropic thinking 用 text+signature，redacted_thinking 与 Responses reasoning 用 opaque 原文。
@Serializable
data class ThinkingPart(
    val text: String = "",
    val signature: String = "",
    val opaque: String? = null,
)

// imagePath 为帧文件路径，发送前 IO 读出 imageBase64，两者均 null 时按旧纯文本格式序列化；工具条目字段仅续跑请求内生效、不落盘。
@Serializable
data class InputItem(
    val type: InputType = InputType.message,
    val role: Role,
    val content: String,
    val imagePath: String? = null,
    val imageBase64: String? = null,
    val callId: String? = null,
    val toolName: String? = null,
    val thoughtSignature: String? = null,
    val thinking: List<ThinkingPart>? = null,
)

// {dialog:[{name,sprite,speech,translate?}]}；sprite 为 1 基编号字符串、"-1" 不切换；translate 仅供云语音合成。
@Serializable
data class Dialog(
    val name: String = "",
    val sprite: String = "-1",
    val speech: String = "",
    val translate: String = "",
)

// 打断播放时重建 assistant 原文，保持与 LLM 契约一致。
@Serializable
data class DialogPayload(val dialog: List<Dialog>)
