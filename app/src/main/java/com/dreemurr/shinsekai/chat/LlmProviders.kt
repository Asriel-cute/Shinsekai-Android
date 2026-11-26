package com.dreemurr.shinsekai.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

// SSE 单个 data 负载的解析结果；一个负载可能含多个结果（如 Gemini 同块文本+工具调用），按序处理。
internal sealed interface SseParseResult {
    data class Delta(val text: String) : SseParseResult
    // 思考文本增量：只供前端流式展示，不进原文缓冲、不落盘。
    data class Thinking(val text: String) : SseParseResult
    // thinking 块边界：带签名表示此前累积文本与该签名合成一个可回放块，不带表示丢弃未封口残留。
    data class ThinkingBoundary(val signature: String? = null) : SseParseResult
    // 整块到达、需原样回放的思考片段（redacted_thinking 块、Responses reasoning 条目）。
    data class ThinkingItem(val json: JsonElement) : SseParseResult
    data class CameraTool(val thoughtSignature: String? = null) : SseParseResult
    data object Done : SseParseResult
    data class Failed(val message: String) : SseParseResult
}

private val jsonMediaType = "application/json".toMediaType()

// 上游错误未带 message 时的兜底文案。
private const val UPSTREAM_ERROR = "上游返回错误"

// CAMERA 画面请求工具：仅 VIDEO 会话声明；终止型调用，命中即结束本段输出。协议常量见 PresentationDialogs。
private const val CAMERA_TOOL_DESCRIPTION =
    "请求查看用户当前的摄像头画面。调用后立即结束本段输出；画面会作为工具结果与下一条图像消息送达，随后在同一轮内继续回复并对画面作出反应。"

private val cameraToolParameters = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {})
}

// 连通性测试的极小提示词。
private const val TEST_PROMPT = "只回复两个字符:ok"

// 连通性测试的输出硬上限：16 是 Responses API 的最小允许值；只判定 2xx，截断无影响。
private const val TEST_MAX_OUTPUT_TOKENS = 16

// 摘要输出上限只经提示词与请求参数约束，前端不做硬截断。
private const val SUMMARY_MAX_OUTPUT_TOKENS = 2048

// 提供商差异的完整封装：端点（均相对 baseUrl）、鉴权、请求体与各类响应解析。
internal interface ProviderSpec {
    fun streamUrl(model: String): String

    // 与流式端点一致时无需覆写。
    fun testUrl(model: String): String = streamUrl(model)

    fun modelsUrl(): String

    fun headers(apiKey: String): Map<String, String>

    fun buildStreamBody(
        json: Json,
        model: String,
        history: List<InputItem>,
        effort: ReasoningEffort,
        cameraTool: Boolean,
    ): RequestBody

    fun buildTestBody(json: Json, model: String): RequestBody

    // 与连通性测试端点一致（Google 需要 generateContent 形态）。
    fun summaryUrl(model: String): String = testUrl(model)

    // 手动压缩用的非流式纯文本请求。
    fun buildSummaryBody(json: Json, model: String, system: String, user: String): RequestBody

    // 无文本返回 null。
    fun parseSummary(body: String): String?

    // OpenAI 的 [DONE] 哨兵由调用方处理；空列表表示忽略。
    fun parseSseData(payload: JsonObject): List<SseParseResult>

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

// 提取 history 中的 system 快照文本（多条合并）。
private fun systemText(history: List<InputItem>): String? = history
    .filter { it.role == Role.system }
    .joinToString("\n\n") { it.content }
    .ifEmpty { null }

// OpenAI / Anthropic 的模型列表同为 {data:[{id}]} 形状。
private fun parseModelIds(body: String): List<String> =
    parseObj(body)?.arr("data")?.objs()?.mapNotNull { it.str("id") }?.sorted() ?: emptyList()

// ---- 跨提供商的图像消息序列化：纯文本保持字符串 content（与旧格式一致），带图用各家 content 数组；帧固定 JPEG ----
private const val IMAGE_MIME = "image/jpeg"

private fun imageDataUrl(base64: String) = "data:$IMAGE_MIME;base64,$base64"

// OpenAI Responses：input_item.content 数组，input_text + input_image。
private fun responsesContent(item: InputItem): JsonElement = if (item.imageBase64 == null) {
    JsonPrimitive(item.content)
} else {
    buildJsonArray {
        add(
            buildJsonObject {
                put("type", "input_text")
                put("text", item.content)
            },
        )
        add(
            buildJsonObject {
                put("type", "input_image")
                put("image_url", imageDataUrl(item.imageBase64))
            },
        )
    }
}

// OpenAI Chat Completions：content 数组，text + image_url。
private fun chatCompletionsContent(item: InputItem): JsonElement = if (item.imageBase64 == null) {
    JsonPrimitive(item.content)
} else {
    buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", item.content)
            },
        )
        add(
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", imageDataUrl(item.imageBase64)) })
            },
        )
    }
}

// ---- OpenAI Responses ----

// none 也显式发送，关闭服务端默认思考；summary 仅在开启思考时请求，否则服务端不返回思考摘要。
@Serializable
private data class Reasoning(val effort: ReasoningEffort, val summary: String? = null)

// input 用 JsonElement 统一承载 system/消息/工具条目；不带强制 JSON 格式（避免与 tools 互相抑制），形状靠提示词与宽容解析。
@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<JsonElement>,
    val stream: Boolean = true,
    val reasoning: Reasoning? = Reasoning(ReasoningEffort.none),
    // 固定 0.7 对齐桌面版默认采样温度，避免服务端缺省 1.0 造成风格漂移；encodeDefaults 保证显式发送。
    val temperature: Double = 0.7,
    val tools: List<JsonElement>? = null,
)

// CAMERA 工具的 Responses 形态声明。
private val responsesCameraTool: JsonElement = buildJsonObject {
    put("type", "function")
    put("name", CAMERA_TOOL_NAME)
    put("description", CAMERA_TOOL_DESCRIPTION)
    put("parameters", cameraToolParameters)
}

// 单条 input 序列化；function_call 前原样放回本段 reasoning 条目（Responses 要求思考紧邻它产生的工具调用）。
private fun responsesInput(item: InputItem): List<JsonElement> = when (item.type) {
    InputType.function_call -> buildList<JsonElement> {
        item.thinking.orEmpty().forEach { part ->
            part.opaque?.let { raw -> runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let(::add) }
        }
        add(
            buildJsonObject {
                put("type", "function_call")
                put("call_id", item.callId.orEmpty())
                put("name", item.toolName.orEmpty())
                put("arguments", item.content)
            },
        )
    }

    InputType.function_call_output -> listOf(
        buildJsonObject {
            put("type", "function_call_output")
            put("call_id", item.callId.orEmpty())
            put("output", item.content)
        },
    )

    InputType.message -> listOf(
        buildJsonObject {
            put("type", "message")
            put("role", item.role.name)
            put("content", responsesContent(item))
        },
    )
}

@Serializable
private data class ResponsesTestRequest(
    val model: String,
    val input: List<InputItem>,
    val stream: Boolean = false,
    // 与聊天默认档位一致；显式关闭思考，避免测试被推理 token 放大。
    val reasoning: Reasoning = Reasoning(ReasoningEffort.none),
    @SerialName("max_output_tokens") val maxOutputTokens: Int = TEST_MAX_OUTPUT_TOKENS,
)

// system 摘要指令随 input 进入；不带 json 格式约束。
@Serializable
private data class ResponsesSummaryRequest(
    val model: String,
    val input: List<InputItem>,
    val stream: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = SUMMARY_MAX_OUTPUT_TOKENS,
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
        cameraTool: Boolean,
    ): RequestBody = json.encodeToString(
        ResponsesRequest(
            model = model,
            input = history.flatMap(::responsesInput),
            // 开启思考才要摘要；none 时不发送 summary，避免不支持的网关报错。
            reasoning = Reasoning(effort, summary = if (effort == ReasoningEffort.none) null else "auto"),
            tools = if (cameraTool) listOf(responsesCameraTool) else null,
        ),
    ).toRequestBody(jsonMediaType)

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        ResponsesTestRequest(
            model = model,
            input = listOf(InputItem(role = Role.user, content = TEST_PROMPT)),
        ),
    ).toRequestBody(jsonMediaType)

    override fun buildSummaryBody(
        json: Json,
        model: String,
        system: String,
        user: String,
    ): RequestBody = json.encodeToString(
        ResponsesSummaryRequest(
            model = model,
            input = listOf(
                InputItem(role = Role.system, content = system),
                InputItem(role = Role.user, content = user),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    // output[].content[] 里 type=output_text 的部分拼接。
    override fun parseSummary(body: String): String? {
        val output = parseObj(body)?.arr("output") ?: return null
        val text = buildString {
            for (item in output.objs()) {
                for (part in item.arr("content")?.objs().orEmpty()) {
                    if (part.str("type") == "output_text") append(part.str("text").orEmpty())
                }
            }
        }
        return text.ifEmpty { null }
    }

    override fun parseSseData(payload: JsonObject): List<SseParseResult> = when (payload.str("type")) {
        "response.output_text.delta" ->
            payload.str("delta")?.let { listOf(SseParseResult.Delta(it)) } ?: emptyList()

        // 思考摘要增量；raw 形态（部分网关直接给原文）同样接收。
        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
            payload.str("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(SseParseResult.Thinking(it)) }
                ?: emptyList()

        "response.output_item.added", "response.output_item.done" -> {
            val item = payload.obj("item") ?: return emptyList()
            when (item.str("type")) {
                // 工具调用条目出现即命中（added/done 均可能先到，调用方去重）。
                "function_call" ->
                    if (item.str("name") == CAMERA_TOOL_NAME) listOf(SseParseResult.CameraTool()) else emptyList()

                // reasoning 条目整条完成后收下，续跑时原样放回 function_call 之前。
                "reasoning" ->
                    if (payload.str("type") == "response.output_item.done") {
                        listOf(SseParseResult.ThinkingItem(item))
                    } else {
                        emptyList()
                    }

                else -> emptyList()
            }
        }

        "response.completed", "response.incomplete" -> listOf(SseParseResult.Done)

        "response.failed" -> listOf(
            SseParseResult.Failed(payload.obj("response")?.obj("error")?.str("message") ?: UPSTREAM_ERROR),
        )

        else -> emptyList()
    }

    override fun parseModels(body: String): List<String> = parseModelIds(body)
}

// ---- OpenAI Chat Completions ----

// 不带强制 JSON 格式（避免与 tools 互相抑制），输出形状靠提示词与宽容解析。
@Serializable
private data class ChatCompletionsRequest(
    val model: String,
    val messages: List<JsonElement>,
    val stream: Boolean = true,
    // 推理档位仅在非 none 时发送，避免不支持的模型报错。
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val temperature: Double = 0.7,
    val tools: List<JsonElement>? = null,
) {
    // content 兼容纯文本（JsonPrimitive）与图文数组两种形态；测试/摘要请求仍用。
    @Serializable
    data class Message(val role: Role, val content: JsonElement)
}

// CAMERA 工具的 Chat Completions 形态声明。
private val chatCompletionsCameraTool: JsonElement = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", CAMERA_TOOL_NAME)
        put("description", CAMERA_TOOL_DESCRIPTION)
        put("parameters", cameraToolParameters)
    })
}

// 消息组装：function_call 并入前一条 assistant 的 tool_calls，function_call_output 序列化为 role:tool 消息。
private fun chatCompletionsMessages(history: List<InputItem>): List<JsonElement> {
    val messages = mutableListOf<MutableMap<String, JsonElement>>()
    for (item in history) {
        when (item.type) {
            InputType.function_call -> {
                val last = messages.lastOrNull()
                val target = if (last != null && last["role"] == JsonPrimitive(Role.assistant.name)) {
                    last
                } else {
                    mutableMapOf<String, JsonElement>(
                        "role" to JsonPrimitive(Role.assistant.name),
                        "content" to JsonNull,
                    ).also { messages.add(it) }
                }
                val calls = (target["tool_calls"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                calls.add(
                    buildJsonObject {
                        put("id", item.callId.orEmpty())
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", item.toolName.orEmpty())
                            put("arguments", item.content)
                        })
                    },
                )
                target["tool_calls"] = JsonArray(calls)
            }

            InputType.function_call_output -> messages.add(
                mutableMapOf(
                    "role" to JsonPrimitive("tool"),
                    "tool_call_id" to JsonPrimitive(item.callId.orEmpty()),
                    "content" to JsonPrimitive(item.content),
                ),
            )

            InputType.message -> messages.add(
                mutableMapOf(
                    "role" to JsonPrimitive(item.role.name),
                    "content" to chatCompletionsContent(item),
                ),
            )
        }
    }
    return messages.map { JsonObject(it) }
}

@Serializable
private data class ChatCompletionsTestRequest(
    val model: String,
    val messages: List<ChatCompletionsRequest.Message>,
    val stream: Boolean = false,
    // 含推理 token 的总上限；不传 reasoning_effort，避免非推理模型/兼容网关返回 400。
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int = TEST_MAX_OUTPUT_TOKENS,
)

// 不带 response_format，输出为纯文本总结。
@Serializable
private data class ChatCompletionsSummaryRequest(
    val model: String,
    val messages: List<ChatCompletionsRequest.Message>,
    val stream: Boolean = false,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int = SUMMARY_MAX_OUTPUT_TOKENS,
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
        cameraTool: Boolean,
    ): RequestBody {
        val request = ChatCompletionsRequest(
            model = model,
            messages = chatCompletionsMessages(history),
            reasoningEffort = if (effort == ReasoningEffort.none) null else effort.name,
            tools = if (cameraTool) listOf(chatCompletionsCameraTool) else null,
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        ChatCompletionsTestRequest(
            model = model,
            messages = listOf(
                ChatCompletionsRequest.Message(role = Role.user, content = JsonPrimitive(TEST_PROMPT)),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    override fun buildSummaryBody(
        json: Json,
        model: String,
        system: String,
        user: String,
    ): RequestBody = json.encodeToString(
        ChatCompletionsSummaryRequest(
            model = model,
            messages = listOf(
                ChatCompletionsRequest.Message(role = Role.system, content = JsonPrimitive(system)),
                ChatCompletionsRequest.Message(role = Role.user, content = JsonPrimitive(user)),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    // choices[0].message.content。
    override fun parseSummary(body: String): String? =
        parseObj(body)?.arr("choices")?.objs()?.firstOrNull()
            ?.obj("message")?.str("content")?.ifEmpty { null }

    override fun parseSseData(payload: JsonObject): List<SseParseResult> {
        // 部分网关在流中以 error 对象报错。
        payload.obj("error")?.let {
            return listOf(SseParseResult.Failed(it.str("message") ?: UPSTREAM_ERROR))
        }
        // 限流等信息块 choices 可能为空。
        val choice = payload.arr("choices")?.objs()?.firstOrNull() ?: return emptyList()
        val out = mutableListOf<SseParseResult>()
        val delta = choice.obj("delta")
        delta?.str("content")?.let { out.add(SseParseResult.Delta(it)) }
        // 思考字段各家不一：DeepSeek 等用 reasoning_content，OpenRouter 用 reasoning。
        (delta?.str("reasoning_content") ?: delta?.str("reasoning"))
            ?.takeIf { it.isNotEmpty() }?.let { out.add(SseParseResult.Thinking(it)) }
        // 工具名通常在首个 tool_calls 分片完整给出。
        delta?.arr("tool_calls")?.objs()?.forEach { call ->
            if (call.obj("function")?.str("name") == CAMERA_TOOL_NAME) {
                out.add(SseParseResult.CameraTool())
            }
        }
        if (choice.str("finish_reason") != null) out.add(SseParseResult.Done)
        return out
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
    val tools: List<JsonElement>? = null,
) {
    // content 兼容纯文本（JsonPrimitive）与内容块数组两种形态。
    @Serializable
    data class Message(val role: Role, val content: JsonElement)

    @Serializable
    data class Thinking(
        val type: String = "enabled",
        @SerialName("budget_tokens") val budgetTokens: Long,
    )
}

// CAMERA 工具的 Anthropic 形态声明。
private val anthropicCameraTool: JsonElement = buildJsonObject {
    put("name", CAMERA_TOOL_NAME)
    put("description", CAMERA_TOOL_DESCRIPTION)
    put("input_schema", cameraToolParameters)
}

private fun anthropicImageBlock(base64: String): JsonElement = buildJsonObject {
    put("type", "image")
    put(
        "source",
        buildJsonObject {
            put("type", "base64")
            put("media_type", IMAGE_MIME)
            put("data", base64)
        },
    )
}

// 连续同角色消息合并为一条（Anthropic 硬性要求交替）；工具条目并入相邻消息的 tool_use / tool_result 块，单文本块保持纯字符串 content。
private fun anthropicMessages(history: List<InputItem>): List<AnthropicRequest.Message> {
    val messages = mutableListOf<Pair<Role, MutableList<JsonElement>>>()
    for (item in history) {
        if (item.role == Role.system) continue
        when (item.type) {
            InputType.function_call -> {
                val last = messages.lastOrNull()
                val blocks = if (last != null && last.first == Role.assistant) {
                    last.second
                } else {
                    mutableListOf<JsonElement>().also { messages.add(Role.assistant to it) }
                }
                // 开启思考时 thinking 块必须排在 assistant 消息最前（先于文本与 tool_use），否则请求被拒。
                val thinkingBlocks = item.thinking.orEmpty().map { part ->
                    part.opaque?.let { raw -> runCatching { Json.parseToJsonElement(raw) }.getOrNull() }
                        ?: buildJsonObject {
                            put("type", "thinking")
                            put("thinking", part.text)
                            put("signature", part.signature)
                        }
                }
                blocks.addAll(0, thinkingBlocks)
                blocks.add(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", item.callId.orEmpty())
                        put("name", item.toolName.orEmpty())
                        put(
                            "input",
                            runCatching { Json.parseToJsonElement(item.content) }
                                .getOrDefault(buildJsonObject {}),
                        )
                    },
                )
            }

            InputType.function_call_output -> messages.add(
                Role.user to mutableListOf<JsonElement>(
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", item.callId.orEmpty())
                        put("content", item.content)
                    },
                ),
            )

            InputType.message -> {
                val role = item.role
                val last = messages.lastOrNull()
                val blocks = if (last != null && last.first == role) {
                    last.second
                } else {
                    mutableListOf<JsonElement>().also { messages.add(role to it) }
                }
                blocks.add(buildJsonObject {
                    put("type", "text")
                    put("text", item.content)
                })
                item.imageBase64?.let { blocks.add(anthropicImageBlock(it)) }
            }
        }
    }
    return messages.map { (role, blocks) ->
        val single = blocks.singleOrNull() as? JsonObject
        val content: JsonElement = if (single != null && single.str("type") == "text") {
            JsonPrimitive(single.str("text").orEmpty())
        } else {
            JsonArray(blocks)
        }
        AnthropicRequest.Message(role = role, content = content)
    }
}

@Serializable
private data class AnthropicTestRequest(
    val model: String,
    val messages: List<AnthropicRequest.Message>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Long = TEST_MAX_OUTPUT_TOKENS.toLong(),
)

// system 走顶层字段；不开 thinking，输出为纯文本总结。
@Serializable
private data class AnthropicSummaryRequest(
    val model: String,
    val system: String,
    val messages: List<AnthropicRequest.Message>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Long = SUMMARY_MAX_OUTPUT_TOKENS.toLong(),
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
        cameraTool: Boolean,
    ): RequestBody {
        val thinking = thinkingBudget(effort)?.let { AnthropicRequest.Thinking(budgetTokens = it) }
        val request = AnthropicRequest(
            model = model,
            system = systemText(history),
            messages = anthropicMessages(history),
            temperature = if (thinking == null) 0.7 else null,
            thinking = thinking,
            tools = if (cameraTool) listOf(anthropicCameraTool) else null,
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        AnthropicTestRequest(
            model = model,
            messages = listOf(AnthropicRequest.Message(role = Role.user, content = JsonPrimitive(TEST_PROMPT))),
        ),
    ).toRequestBody(jsonMediaType)

    override fun buildSummaryBody(
        json: Json,
        model: String,
        system: String,
        user: String,
    ): RequestBody = json.encodeToString(
        AnthropicSummaryRequest(
            model = model,
            system = system,
            messages = listOf(AnthropicRequest.Message(role = Role.user, content = JsonPrimitive(user))),
        ),
    ).toRequestBody(jsonMediaType)

    // content[] 里 text 块拼接。
    override fun parseSummary(body: String): String? {
        val text = parseObj(body)?.arr("content")?.objs()
            ?.mapNotNull { it.str("text") }
            ?.joinToString("")
            .orEmpty()
        return text.ifEmpty { null }
    }

    override fun parseSseData(payload: JsonObject): List<SseParseResult> = when (payload.str("type")) {
        "content_block_start" -> {
            val block = payload.obj("content_block") ?: return emptyList()
            when (block.str("type")) {
                "tool_use" ->
                    if (block.str("name") == CAMERA_TOOL_NAME) listOf(SseParseResult.CameraTool()) else emptyList()

                // thinking 块开始：丢弃上一条未封口残留，文本与签名随后分片到达。
                "thinking" -> listOf(SseParseResult.ThinkingBoundary())

                // redacted_thinking 整块到达，原样回放。
                "redacted_thinking" -> listOf(SseParseResult.ThinkingItem(block))

                else -> emptyList()
            }
        }

        "content_block_delta" -> {
            val delta = payload.obj("delta") ?: return emptyList()
            when (delta.str("type")) {
                "text_delta" ->
                    delta.str("text")?.takeIf { it.isNotEmpty() }?.let { listOf(SseParseResult.Delta(it)) }
                        ?: emptyList()

                "thinking_delta" ->
                    delta.str("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(SseParseResult.Thinking(it)) } ?: emptyList()

                // 签名在块结束前单独送达，与此前累积文本合成一个可回放块。
                "signature_delta" -> listOf(SseParseResult.ThinkingBoundary(delta.str("signature")))

                // input_json_delta 等其余增量不进入原文缓冲。
                else -> emptyList()
            }
        }

        "message_stop" -> listOf(SseParseResult.Done)

        "error" -> listOf(
            SseParseResult.Failed(payload.obj("error")?.str("message") ?: UPSTREAM_ERROR),
        )

        else -> emptyList()
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
    val tools: List<JsonElement>? = null,
) {
    // role 仅 user/model（system 走 systemInstruction）；parts 用 JsonElement 统一承载文本、图像与工具块。
    @Serializable
    data class Content(val role: String? = null, val parts: List<JsonElement>)

    // 不带强制 JSON 格式（避免与 tools 互相抑制），输出形状靠提示词与宽容解析。
    @Serializable
    data class GenerationConfig(
        val temperature: Double = 0.7,
        val thinkingConfig: ThinkingConfig? = null,
    ) {
        @Serializable
        data class ThinkingConfig(val thinkingBudget: Int, val includeThoughts: Boolean? = null)
    }
}

private fun googleTextPart(text: String): JsonElement = buildJsonObject { put("text", text) }

private fun googleImagePart(base64: String): JsonElement = buildJsonObject {
    put("inline_data", buildJsonObject {
        put("mime_type", IMAGE_MIME)
        put("data", base64)
    })
}

// CAMERA 工具的 Gemini 形态声明。
private val googleCameraTool: JsonElement = buildJsonObject {
    put("functionDeclarations", buildJsonArray {
        add(
            buildJsonObject {
                put("name", CAMERA_TOOL_NAME)
                put("description", CAMERA_TOOL_DESCRIPTION)
                put("parameters", cameraToolParameters)
            },
        )
    })
}

// 连续同角色内容合并为一条，工具条目并入相邻内容；thoughtSignature 为 part 级字段（Gemini 3 回放必需）。
private fun googleContents(history: List<InputItem>): List<GoogleRequest.Content> {
    val contents = mutableListOf<Pair<String, MutableList<JsonElement>>>()
    for (item in history) {
        if (item.role == Role.system) continue
        when (item.type) {
            InputType.function_call -> {
                val last = contents.lastOrNull()
                val parts = if (last != null && last.first == "model") {
                    last.second
                } else {
                    mutableListOf<JsonElement>().also { contents.add("model" to it) }
                }
                parts.add(
                    buildJsonObject {
                        put("functionCall", buildJsonObject {
                            put("name", item.toolName.orEmpty())
                            put(
                                "args",
                                runCatching { Json.parseToJsonElement(item.content) }
                                    .getOrDefault(buildJsonObject {}),
                            )
                        })
                        item.thoughtSignature?.let { put("thoughtSignature", it) }
                    },
                )
            }

            InputType.function_call_output -> contents.add(
                "user" to mutableListOf<JsonElement>(
                    buildJsonObject {
                        put("functionResponse", buildJsonObject {
                            put("name", item.toolName.orEmpty())
                            put("response", buildJsonObject { put("result", item.content) })
                        })
                    },
                ),
            )

            InputType.message -> {
                val role = if (item.role == Role.assistant) "model" else "user"
                val last = contents.lastOrNull()
                val parts = if (last != null && last.first == role) {
                    last.second
                } else {
                    mutableListOf<JsonElement>().also { contents.add(role to it) }
                }
                parts.add(googleTextPart(item.content))
                item.imageBase64?.let { parts.add(googleImagePart(it)) }
            }
        }
    }
    return contents.map { (role, parts) -> GoogleRequest.Content(role = role, parts = parts) }
}

@Serializable
private data class GoogleTestRequest(
    val contents: List<GoogleRequest.Content>,
    // 只限制输出（含 thought token）；不传 thinkingConfig，兼容不能关闭思考的型号。
    val generationConfig: GenerationConfig = GenerationConfig(),
) {
    @Serializable
    data class GenerationConfig(val maxOutputTokens: Int = TEST_MAX_OUTPUT_TOKENS)
}

// 摘要输出纯文本，不能带 responseMimeType=application/json。
@Serializable
private data class GoogleSummaryRequest(
    val contents: List<GoogleRequest.Content>,
    val systemInstruction: GoogleRequest.Content? = null,
    val generationConfig: GenerationConfig = GenerationConfig(),
) {
    @Serializable
    data class GenerationConfig(val maxOutputTokens: Int = SUMMARY_MAX_OUTPUT_TOKENS)
}

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
        cameraTool: Boolean,
    ): RequestBody {
        // -1 表示动态思考（模型自定预算）。
        val budget = when (effort) {
            ReasoningEffort.none -> null
            ReasoningEffort.low -> 1024
            ReasoningEffort.high -> 8192
            ReasoningEffort.max -> -1
        }
        val request = GoogleRequest(
            contents = googleContents(history),
            systemInstruction = systemText(history)?.let {
                GoogleRequest.Content(parts = listOf(googleTextPart(it)))
            },
            generationConfig = GoogleRequest.GenerationConfig(
                // 开启思考才要摘要；摘要 part 带 thought 标记，解析时不进台词原文。
                thinkingConfig = budget?.let {
                    GoogleRequest.GenerationConfig.ThinkingConfig(it, includeThoughts = true)
                },
            ),
            tools = if (cameraTool) listOf(googleCameraTool) else null,
        )
        return json.encodeToString(request).toRequestBody(jsonMediaType)
    }

    override fun buildTestBody(json: Json, model: String): RequestBody = json.encodeToString(
        GoogleTestRequest(
            listOf(
                GoogleRequest.Content(
                    role = "user",
                    parts = listOf(googleTextPart(TEST_PROMPT)),
                ),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    override fun buildSummaryBody(
        json: Json,
        model: String,
        system: String,
        user: String,
    ): RequestBody = json.encodeToString(
        GoogleSummaryRequest(
            contents = listOf(
                GoogleRequest.Content(
                    role = "user",
                    parts = listOf(googleTextPart(user)),
                ),
            ),
            systemInstruction = GoogleRequest.Content(
                parts = listOf(googleTextPart(system)),
            ),
        ),
    ).toRequestBody(jsonMediaType)

    // 复用流式的 candidates 文本提取。
    override fun parseSummary(body: String): String? =
        candidateText(parseObj(body))?.ifEmpty { null }

    override fun parseSseData(payload: JsonObject): List<SseParseResult> {
        payload.obj("error")?.let {
            return listOf(SseParseResult.Failed(it.str("message") ?: UPSTREAM_ERROR))
        }
        val parts = payload.arr("candidates")?.objs()?.firstOrNull()
            ?.obj("content")?.arr("parts") ?: return emptyList()
        // 按 part 顺序产出：同块可能文本与工具调用并存。
        val out = mutableListOf<SseParseResult>()
        for (part in parts.objs()) {
            // 思考摘要只供展示，不进台词原文。
            if (part["thought"] == JsonPrimitive(true)) {
                part.str("text")?.takeIf { it.isNotEmpty() }?.let { out.add(SseParseResult.Thinking(it)) }
                continue
            }
            part.str("text")?.takeIf { it.isNotEmpty() }?.let { out.add(SseParseResult.Delta(it)) }
            val call = part.obj("functionCall")
            if (call != null && call.str("name") == CAMERA_TOOL_NAME) {
                out.add(SseParseResult.CameraTool(part.str("thoughtSignature")))
            }
        }
        return out
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
