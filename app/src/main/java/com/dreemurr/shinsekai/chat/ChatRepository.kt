package com.dreemurr.shinsekai.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

// 终止事件携带 LLM 原文，供完整或残片落盘。
sealed interface ChatStreamEvent {
    data class DialogPart(val dialog: Dialog) : ChatStreamEvent
    data class Completed(val rawText: String) : ChatStreamEvent
    data class Cancelled(val rawText: String) : ChatStreamEvent
    data class Error(val rawText: String, val message: String) : ChatStreamEvent
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

        val api = apiFor(settings.baseUrl)

        // 非法存储值回退 none。
        val effort = ReasoningEffort.entries.firstOrNull { it.name == settings.reasoningEffort }
            ?: ReasoningEffort.none

        val request = ResponsesRequest(
            model = settings.modelName,
            input = history,
            reasoning = Reasoning(effort)
        )

        cancelRequested = false
        synchronized(rawBuffer) { rawBuffer.setLength(0) }
        streamActive = true

        val call = api.streamChat("responses", "Bearer ${settings.apiKey}", request)
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
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                emit(ChatStreamEvent.Error(raw(), httpErrorMessage(response.code(), errorBody)))
                return@flow
            }

            val body = response.body()
            if (body == null) {
                emit(ChatStreamEvent.Error(raw(), "服务器返回空响应"))
                return@flow
            }

            emit(readSse(body, parser, call))
        } finally {
            streamActive = false
        }
    }.flowOn(Dispatchers.IO)

    // completed、incomplete 和 EOF 按完成；读取异常区分停止与网络中断。
    private suspend fun FlowCollector<ChatStreamEvent>.readSse(
        body: ResponseBody,
        parser: DialogJsonParser,
        call: Call<ResponseBody>,
    ): ChatStreamEvent {
        try {
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // 类型取 data JSON 的 type，忽略 event: 行。
                    if (!line.startsWith("data:")) continue

                    val event = decodeEvent(line) ?: continue

                    when (event.type) {
                        "response.output_text.delta" -> {
                            val delta = event.delta ?: continue
                            synchronized(rawBuffer) { rawBuffer.append(delta) }
                            for (dialog in parser.append(delta)) {
                                emit(ChatStreamEvent.DialogPart(dialog))
                            }
                        }

                        "response.completed", "response.incomplete" ->
                            return ChatStreamEvent.Completed(raw())

                        "response.failed" ->
                            return ChatStreamEvent.Error(
                                raw(),
                                event.response?.error?.message ?: "上游返回错误",
                            )
                    }
                }
            }
        } catch (_: IOException) {
            return if (isUserCancel(call)) ChatStreamEvent.Cancelled(raw())
            else ChatStreamEvent.Error(raw(), "网络连接中断")
        }
        return ChatStreamEvent.Completed(raw())
    }

    private fun apiFor(url: String): ChatApi {
        val baseUrl = if (url.endsWith("/")) url else "$url/"
        return cachedApi?.takeIf { cachedBaseUrl == baseUrl }
            ?: Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ChatApi::class.java)
                .also {
                    cachedApi = it
                    cachedBaseUrl = baseUrl
                }
    }

    // 畸形 data 行记录后跳过。
    private fun decodeEvent(line: String): ResponsesStreamEvent? = try {
        json.decodeFromString<ResponsesStreamEvent>(line.substring(5).trim())
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun raw(): String = synchronized(rawBuffer) { rawBuffer.toString() }

    private fun isUserCancel(call: Call<ResponseBody>): Boolean = cancelRequested || call.isCanceled()

    private fun httpErrorMessage(code: Int, errorBody: String?): String = when (code) {
        401 -> "API Key 无效或未授权"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务器错误"
        else -> extractErrorMessage(errorBody) ?: "请求失败（HTTP $code）"
    }

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
