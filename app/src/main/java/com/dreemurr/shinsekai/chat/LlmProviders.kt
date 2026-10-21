package com.dreemurr.shinsekai.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** SSE 单个 data 负载的解析结果。 */
internal sealed interface SseParseResult {
    data object Ignored : SseParseResult
    data class Delta(val text: String) : SseParseResult
    data object Done : SseParseResult
    data class Failed(val message: String) : SseParseResult
}

private val jsonMediaType = "application/json".toMediaType()

// 连通性测试的极小提示词。
private const val TEST_PROMPT = "只回复两个字符:ok"

/** 提供商差异的完整封装：端点、鉴权、请求体与各类响应解析。 */
internal interface ProviderSpec {
    /** 流式对话端点（相对 baseUrl）。 */
    fun streamUrl(model: String): String

    /** 非流式连通性测试端点（相对 baseUrl）；与流式端点一致时无需覆写。 */
    fun testUrl(model: String): String = streamUrl(model)

    /** 模型列表端点（相对 baseUrl）。 */
    fun modelsUrl(): String

    /** 鉴权等请求头。 */
    fun headers(apiKey: String): Map<String, String>

    fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
    ): RequestBody

    fun buildTestBody(json: Json, model: String): RequestBody

    /** 解析 SSE data 负载（OpenAI 的 [DONE] 哨兵由调用方处理）。 */
    fun parseSseData(payload: JsonObject): SseParseResult

    fun parseModels(body: String): List<String>
}

internal fun specFor(provider: LlmProvider, apiType: OpenAiApiType): ProviderSpec = when (provider) {
    LlmProvider.OPENAI ->
        if (apiType == OpenAiApiType.CHAT_COMPLETIONS) ChatCompletionsSpec else ResponsesSpec
    LlmProvider.ANTHROPIC -> AnthropicSpec
    LlmProvider.GOOGLE -> GoogleSpec
}

// ---- JSON 导航小工具：宽容处理各家形状差异，避免解析异常中断流 ----

private fun parseObj(body: String?): JsonObject? = body?.let {
    runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonArray.objs(): List<JsonObject> = mapNotNull { it as? JsonObject }

// 连续同角色合并（Anthropic 硬性要求交替，Google 防御性处理）。
private fun mergeConsecutiveRoles(items: List<InputItem>): List<InputItem> = buildList {
    for (item in items) {
        val last = lastOrNull()
        if (last != null && last.role == item.role) {
            this[size - 1] = last.copy(content = last.content + "\n\n" + item.content)
        } else {
            add(item)
        }
    }
}

// 提取 history 中的 system 快照文本（多条合并）。
private fun systemText(history: List<InputItem>): String? = history
    .filter { it.role == Role.system }
    .joinToString("\n\n") { it.content }
    .ifEmpty { null }

// OpenAI / Anthropic 的模型列表同为 {data:[{id}]} 形状。
private fun parseModelIds(body: String): List<String> =
    parseObj(body)?.arr("data")?.objs()?.mapNotNull { it.str("id") }?.sorted() ?: emptyList()

// ---- OpenAI Responses（原实现平移）----

// none 也显式发送。
@Serializable
private data class Reasoning(val effort: ReasoningEffort)

// system 提示词快照随历史进入 input。
@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<InputItem>,
    val stream: Boolean = true,
    val reasoning: Reasoning? = Reasoning(ReasoningEffort.none),
    val text: TextConfig = TextConfig(),
    // 固定 0.7 对齐桌面版默认采样温度，避免服务端缺省 1.0 造成风格漂移；encodeDefaults 保证显式发送。
    val temperature: Double = 0.7,
) {
    @Serializable
    data class TextConfig(val format: Format = Format()) {
        @Serializable
        data class Format(val type: String = "json_object")
    }
}

@Serializable
private data class ResponsesTestRequest(
    val model: String,
    val input: List<InputItem>,
    val stream: Boolean = false,
)

internal object ResponsesSpec : ProviderSpec {
    override fun streamUrl(model: String) = "responses"
    override fun modelsUrl() = "models"
    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
    ): RequestBody = json.encodeToString(
        ResponsesRequest(model = model, input = history, reasoning = Reasoning(effort)),
    ).toRequestBody(jsonMediaType)

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        ResponsesTestRequest(
            model = model,
            input = listOf(InputItem(role = Role.user, content = TEST_PROMPT)),
        ),
    ).toRequestBody(jsonMediaType)

    override fun parseSseData(payload: JsonObject): SseParseResult = when (payload.str("type")) {
        "response.output_text.delta" ->
            payload.str("delta")?.let { SseParseResult.Delta(it) } ?: SseParseResult.Ignored

        "response.completed", "response.incomplete" -> SseParseResult.Done

        "response.failed" -> SseParseResult.Failed(
            payload.obj("response")?.obj("error")?.str("message") ?: "上游返回错误",
        )

        else -> SseParseResult.Ignored
    }

    override fun parseModels(body: String): List<String> = parseModelIds(body)
}

// ---- OpenAI Chat Completions ----

@Serializable
private data class ChatCompletionsRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = true,
    // 推理档位仅在非 none 时发送，避免不支持的模型报错。
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
    val temperature: Double = 0.7,
) {
    @Serializable
    data class Message(val role: Role, val content: String)

    @Serializable
    data class ResponseFormat(val type: String = "json_object")
}

@Serializable
private data class ChatCompletionsTestRequest(
    val model: String,
    val messages: List<ChatCompletionsRequest.Message>,
    val stream: Boolean = false,
)

internal object ChatCompletionsSpec : ProviderSpec {
    override fun streamUrl(model: String) = "chat/completions"
    override fun modelsUrl() = "models"
    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
    ): RequestBody {
        val request = ChatCompletionsRequest(
            model = model,
            messages = history.map { ChatCompletionsRequest.Message(role = it.role, content = it.content) },
            reasoningEffort = if (effort == ReasoningEffort.none) null else effort.name,
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        ChatCompletionsTestRequest(
            model = model,
            messages = listOf(ChatCompletionsRequest.Message(role = Role.user, content = TEST_PROMPT)),
        ),
    ).toRequestBody(jsonMediaType)

    override fun parseSseData(payload: JsonObject): SseParseResult {
        // 部分网关在流中以 error 对象报错。
        payload.obj("error")?.let { return SseParseResult.Failed(it.str("message") ?: "上游返回错误") }
        // 限流等信息块 choices 可能为空。
        val choice = payload.arr("choices")?.objs()?.firstOrNull() ?: return SseParseResult.Ignored
        choice.obj("delta")?.str("content")?.let { return SseParseResult.Delta(it) }
        return if (choice.str("finish_reason") != null) SseParseResult.Done else SseParseResult.Ignored
    }

    override fun parseModels(body: String): List<String> = parseModelIds(body)
}

// ---- Anthropic ----

@Serializable
private data class AnthropicRequest(
    val model: String,
    // system 快照提取为顶层字段，Anthropic 不接受 messages 内的 system。
    val system: String? = null,
    val messages: List<Message>,
    val stream: Boolean = true,
    // API 必填；需大于 thinking 预算。
    @SerialName("max_tokens") val maxTokens: Long = 16384,
    // thinking 开启时不允许携带自定义 temperature。
    val temperature: Double? = 0.7,
    val thinking: Thinking? = null,
) {
    @Serializable
    data class Message(val role: Role, val content: String)

    @Serializable
    data class Thinking(
        val type: String = "enabled",
        @SerialName("budget_tokens") val budgetTokens: Long,
    )
}

@Serializable
private data class AnthropicTestRequest(
    val model: String,
    val messages: List<AnthropicRequest.Message>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Long = 16,
)

internal object AnthropicSpec : ProviderSpec {
    override fun streamUrl(model: String) = "v1/messages"
    // 默认 limit=20，拉满到 1000。
    override fun modelsUrl() = "v1/models?limit=1000"
    override fun headers(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
    )

    override fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
    ): RequestBody {
        val thinking = thinkingBudget(effort)?.let { AnthropicRequest.Thinking(budgetTokens = it) }
        val request = AnthropicRequest(
            model = model,
            system = systemText(history),
            messages = mergeConsecutiveRoles(history.filter { it.role != Role.system })
                .map { AnthropicRequest.Message(role = it.role, content = it.content) },
            temperature = if (thinking == null) 0.7 else null,
            thinking = thinking,
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        AnthropicTestRequest(
            model = model,
            messages = listOf(AnthropicRequest.Message(role = Role.user, content = TEST_PROMPT)),
        ),
    ).toRequestBody(jsonMediaType)

    override fun parseSseData(payload: JsonObject): SseParseResult = when (payload.str("type")) {
        "content_block_delta" -> {
            val delta = payload.obj("delta")
            if (delta?.str("type") == "text_delta") {
                delta.str("text")?.let { SseParseResult.Delta(it) } ?: SseParseResult.Ignored
            } else {
                // thinking_delta 等非文本块不进入原文缓冲。
                SseParseResult.Ignored
            }
        }

        "message_stop" -> SseParseResult.Done

        "error" -> SseParseResult.Failed(payload.obj("error")?.str("message") ?: "上游返回错误")

        else -> SseParseResult.Ignored
    }

    override fun parseModels(body: String): List<String> = parseModelIds(body)

    // 固定预算映射，必须小于 max_tokens=16384。
    private fun thinkingBudget(effort: ReasoningEffort): Long? = when (effort) {
        ReasoningEffort.none -> null
        ReasoningEffort.low -> 2048L
        ReasoningEffort.high -> 8192L
        ReasoningEffort.max -> 12288L
    }
}

// ---- Google Gemini ----

@Serializable
private data class GoogleRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig = GenerationConfig(),
) {
    // role 仅 user/model；system 走 systemInstruction。
    @Serializable
    data class Content(val role: String? = null, val parts: List<Part>) {
        @Serializable
        data class Part(val text: String)
    }

    @Serializable
    data class GenerationConfig(
        val temperature: Double = 0.7,
        val responseMimeType: String = "application/json",
        val thinkingConfig: ThinkingConfig? = null,
    ) {
        @Serializable
        data class ThinkingConfig(val thinkingBudget: Int)
    }
}

@Serializable
private data class GoogleTestRequest(
    val contents: List<GoogleRequest.Content>,
)

internal object GoogleSpec : ProviderSpec {
    override fun streamUrl(model: String) =
        "v1beta/models/${model.removePrefix("models/")}:streamGenerateContent?alt=sse"

    override fun testUrl(model: String) =
        "v1beta/models/${model.removePrefix("models/")}:generateContent"

    override fun modelsUrl() = "v1beta/models?pageSize=1000"
    override fun headers(apiKey: String) = mapOf("x-goog-api-key" to apiKey)

    override fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
    ): RequestBody {
        val contents = mergeConsecutiveRoles(history.filter { it.role != Role.system }).map {
            GoogleRequest.Content(
                role = if (it.role == Role.assistant) "model" else "user",
                parts = listOf(GoogleRequest.Content.Part(it.content)),
            )
        }
        // -1 表示动态思考（模型自定预算）。
        val budget = when (effort) {
            ReasoningEffort.none -> null
            ReasoningEffort.low -> 1024
            ReasoningEffort.high -> 8192
            ReasoningEffort.max -> -1
        }
        val request = GoogleRequest(
            contents = contents,
            systemInstruction = systemText(history)?.let {
                GoogleRequest.Content(parts = listOf(GoogleRequest.Content.Part(it)))
            },
            generationConfig = GoogleRequest.GenerationConfig(
                thinkingConfig = budget?.let { GoogleRequest.GenerationConfig.ThinkingConfig(it) },
            ),
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        GoogleTestRequest(
            listOf(
                GoogleRequest.Content(
                    role = "user",
                    parts = listOf(GoogleRequest.Content.Part(TEST_PROMPT)),
                ),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    override fun parseSseData(payload: JsonObject): SseParseResult {
        payload.obj("error")?.let { return SseParseResult.Failed(it.str("message") ?: "上游返回错误") }
        val text = candidateText(payload) ?: return SseParseResult.Ignored
        return if (text.isEmpty()) SseParseResult.Ignored else SseParseResult.Delta(text)
    }

    override fun parseModels(body: String): List<String> =
        parseObj(body)?.arr("models")?.objs()
            ?.filter { m ->
                // 无该字段时不过滤；有则仅保留支持 generateContent（含流式）的模型。
                m.arr("supportedGenerationMethods")?.any {
                    (it as? JsonPrimitive)?.contentOrNull?.contains("generateContent") == true
                } ?: true
            }
            ?.mapNotNull { it.str("name")?.removePrefix("models/") }
            ?.sorted()
            ?: emptyList()

    // candidates[0].content.parts[].text 拼接；流式响应中每个 data 块即一段增量。
    private fun candidateText(root: JsonObject?): String? {
        val parts = root?.arr("candidates")?.objs()?.firstOrNull()?.obj("content")?.arr("parts")
            ?: return null
        return parts.objs().mapNotNull { it.str("text") }.joinToString("")
    }
}
