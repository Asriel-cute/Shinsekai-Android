package com.dreemurr.shinsekai.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// 终止事件携带 LLM 原文，供完整或残片落盘。
sealed interface ChatStreamEvent {
    data class DialogPart(val dialog: Dialog) : ChatStreamEvent
    data class Completed(val rawText: String) : ChatStreamEvent
    data class Cancelled(val rawText: String) : ChatStreamEvent
    data class Error(val rawText: String, val message: String) : ChatStreamEvent
}

// 模型列表拉取结果。
sealed interface ModelsResult {
    data class Success(val models: List<String>) : ModelsResult
    data class Failure(val message: String) : ModelsResult
}

// 连通性测试结果。
sealed interface ConnectionTestResult {
    data class Success(val elapsedMs: Long) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

class ChatRepository {
    // 默认字段也写入，保证 stream=true 显式发送。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedApi: ChatApi? = null

    // UI 保证同时只有一个活跃流。
    @Volatile
    private var currentCall: Call<ResponseBody>? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var streamActive = false

    // 原文读写统一在 synchronized(rawBuffer) 内。
    private val rawBuffer = StringBuilder()

    // 供 VM 销毁时补存残片。
    fun rawTextSoFar(): String? =
        if (streamActive) synchronized(rawBuffer) { rawBuffer.toString() } else null

    fun cancel() {
        cancelRequested = true
        currentCall?.cancel()
    }

    fun getChatStream(
        settings: ChatSettings,
        history: List<InputItem>,
    ): Flow<ChatStreamEvent> = flow {

        val spec = specFor(settings.provider, settings.apiType)

        // 非法存储值回退 none。
        val effort = ReasoningEffort.entries.firstOrNull { it.name == settings.reasoningEffort }
            ?: ReasoningEffort.none

        val api = apiFor(settings.baseUrl)

        cancelRequested = false
        synchronized(rawBuffer) { rawBuffer.setLength(0) }
        streamActive = true

        val call = api.postStream(
            spec.streamUrl(settings.modelName),
            spec.headers(settings.apiKey),
            spec.buildStreamBody(json, settings.modelName, history, effort),
        )
        currentCall = call

        // 阻塞读不响应协程取消，需联动底层连接。
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        val parser = DialogJsonParser()

        try {
            val response = try {
                call.execute()
            } catch (_: IOException) {
                emit(
                    if (isUserCancel(call)) ChatStreamEvent.Cancelled(raw())
                    else ChatStreamEvent.Error(raw(), "网络连接失败，请检查网络或 BaseUrl")
                )
                return@flow
            }

            if (!response.isSuccessful) {
                emit(ChatStreamEvent.Error(raw(), responseError(response)))
                return@flow
            }

            val body = response.body()
            if (body == null) {
                emit(ChatStreamEvent.Error(raw(), "服务器返回空响应"))
                return@flow
            }

            emit(readSse(body, spec, parser, call))
        } finally {
            streamActive = false
        }
    }.flowOn(Dispatchers.IO)

    // 各家事件差异由 spec 解析；EOF 按完成；读取异常区分停止与网络中断。
    private suspend fun FlowCollector<ChatStreamEvent>.readSse(
        body: ResponseBody,
        spec: ProviderSpec,
        parser: DialogJsonParser,
        call: Call<ResponseBody>,
    ): ChatStreamEvent {
        try {
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // 忽略 event: 行，类型取 data JSON 的字段。
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue

                    // OpenAI 风格流结束哨兵。
                    if (payload == "[DONE]") return ChatStreamEvent.Completed(raw())

                    // 畸形 data 行记录后跳过。
                    val element = try {
                        json.parseToJsonElement(payload)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    val obj = element as? JsonObject ?: continue

                    when (val result = spec.parseSseData(obj)) {
                        is SseParseResult.Delta -> {
                            synchronized(rawBuffer) { rawBuffer.append(result.text) }
                            for (dialog in parser.append(result.text)) {
                                emit(ChatStreamEvent.DialogPart(dialog))
                            }
                        }

                        SseParseResult.Done -> return ChatStreamEvent.Completed(raw())

                        is SseParseResult.Failed ->
                            return ChatStreamEvent.Error(raw(), result.message)

                        SseParseResult.Ignored -> Unit
                    }
                }
            }
        } catch (_: IOException) {
            return if (isUserCancel(call)) ChatStreamEvent.Cancelled(raw())
            else ChatStreamEvent.Error(raw(), "网络连接中断")
        }
        return ChatStreamEvent.Completed(raw())
    }

    // 拉取模型列表；baseUrl/apiKey 取自当前编辑草稿，可与未保存表单一致。
    suspend fun fetchModels(
        provider: LlmProvider,
        baseUrl: String,
        apiKey: String,
    ): ModelsResult = withContext(Dispatchers.IO) {
        try {
            // OpenAI 两种形态的模型列表端点一致，形态不影响。
            val spec = specFor(provider, OpenAiApiType.RESPONSES)
            val call = apiFor(baseUrl)
                .getModels(spec.modelsUrl(), spec.headers(apiKey))
                .withTestTimeout()
            val response = call.execute()
            if (!response.isSuccessful) {
                ModelsResult.Failure(responseError(response))
            } else {
                val text = runCatching { response.body()?.string() }.getOrNull()
                if (text == null) ModelsResult.Failure("服务器返回空响应")
                else ModelsResult.Success(spec.parseModels(text))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ModelsResult.Failure(friendlyError(e))
        }
    }

    // 连通性测试：向目标模型发一次极小的非流式真实请求。
    suspend fun testConnection(settings: ChatSettings): ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            val spec = specFor(settings.provider, settings.apiType)
            val call = apiFor(settings.baseUrl)
                .post(
                    spec.testUrl(settings.modelName),
                    spec.headers(settings.apiKey),
                    spec.buildTestBody(json, settings.modelName),
                )
                .withTestTimeout()
            // 协程取消时联动断开，避免离开页面后请求悬挂。
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val startAt = System.currentTimeMillis()
            val response = call.execute()
            val elapsed = System.currentTimeMillis() - startAt
            if (!response.isSuccessful) {
                ConnectionTestResult.Failure(responseError(response))
            } else {
                // HTTP 2xx 即视为连通成功，不解析响应体。
                ConnectionTestResult.Success(elapsed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionTestResult.Failure(friendlyError(e))
        }
    }

    // 测试类请求统一 30s 超时，避免长时间悬挂。
    private fun <T> Call<T>.withTestTimeout(): Call<T> = apply {
        timeout().timeout(30, TimeUnit.SECONDS)
    }

    private fun apiFor(url: String): ChatApi {
        val baseUrl = if (url.endsWith("/")) url else "$url/"
        return cachedApi?.takeIf { cachedBaseUrl == baseUrl }
            ?: Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .build()
                .create(ChatApi::class.java)
                .also {
                    cachedApi = it
                    cachedBaseUrl = baseUrl
                }
    }

    private fun raw(): String = synchronized(rawBuffer) { rawBuffer.toString() }

    private fun isUserCancel(call: Call<ResponseBody>): Boolean = cancelRequested || call.isCanceled()

    private fun friendlyError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "无法解析主机，请检查 BaseUrl"
        is IllegalArgumentException -> "BaseUrl 格式不正确"
        is IOException -> "网络连接失败，请检查网络或 BaseUrl"
        else -> e.message ?: "请求失败"
    }

    private fun httpErrorMessage(code: Int, errorBody: String?): String = when (code) {
        401 -> "API Key 无效或未授权"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务器错误"
        else -> extractErrorMessage(errorBody) ?: "请求失败（HTTP $code）"
    }

    // 统一读取错误响应体并转换为友好文案。
    private fun responseError(response: Response<ResponseBody>): String =
        httpErrorMessage(response.code(), runCatching { response.errorBody()?.string() }.getOrNull())

    private fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
}
