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

@Serializable
data class InputItem(
    val type: String = "message",
    val role: Role,
    val content: String,
)

// {dialog:[{name,sprite,speech,translate?}]}；sprite 是 1 基编号字符串，"-1" 不切换立绘。
// translate 仅供云语音合成，不参与前端展示。
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
