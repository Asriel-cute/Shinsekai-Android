package com.dreemurr.shinsekai.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface VoiceIdsResult {
    data class Success(val ids: List<String>) : VoiceIdsResult
    data class Failure(val message: String) : VoiceIdsResult
}

// 克隆结果；成功携带可用 voice_id（阿里为服务端生成，MiniMax 为自定义值）。
sealed interface CloneVoiceResult {
    data class Success(val voiceId: String) : CloneVoiceResult
    data class Failure(val message: String) : CloneVoiceResult
}

sealed interface DeleteVoiceResult {
    data object Success : DeleteVoiceResult
    data class Failure(val message: String) : DeleteVoiceResult
}

// 合成结果；Ok 携带音频字节（mp3）。
sealed interface SynthResult {
    data class Ok(val audio: ByteArray) : SynthResult
    data class Failure(val message: String) : SynthResult
}

// 阿里百炼 voice-enrollment 端点（增查删共用）；MiniMax 各操作走独立 REST 子路径。
private const val ALIYUN_CUSTOMIZATION = "services/audio/tts/customization"

// 单次请求结果：Ok 携带响应文本，Err 携带用户可读错误。
private sealed interface HttpText {
    data class Ok(val text: String) : HttpText
    data class Err(val message: String) : HttpText
}

private sealed interface UploadResult {
    data class Ok(val fileId: Long) : UploadResult
    data class Err(val message: String) : UploadResult
}

// 阿里克隆部署状态的单次查询结果。
private sealed interface AliyunPoll {
    data object Deployed : AliyunPoll
    data object Undeployed : AliyunPoll
    data object Pending : AliyunPoll
    data class Failed(val message: String) : AliyunPoll
}

// 云 TTS 请求执行：服务商差异由 TtsRequests/TtsResponses 封装，这里只管连接与错误。
class TtsRepository {
    // 上传与克隆音频可能较大，统一放宽超时；callTimeout 覆盖单次调用全程。
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedApi: TtsApi? = null

    // 克隆入参；voiceId/promptAudio/promptText 仅 MiniMax 使用，阿里忽略。
    data class CloneParams(
        val provider: TtsProvider,
        val baseUrl: String,
        val apiKey: String,
        val model: String, // 阿里 target_model，必与后续合成模型一致
        val voiceId: String, // MiniMax 自定义；阿里忽略（服务端生成）
        val cloneAudio: File,
        val promptAudio: File? = null, // MiniMax 可选参考音频
        val promptText: String? = null, // MiniMax 可选参考文字
        val language: String? = null, // 卡片语言（导入或手选）→ 两家复刻语言增强
    )

    // 获取账号下音色列表（阿里 list_voice / MiniMax get_voice）。
    suspend fun fetchVoiceIds(
        provider: TtsProvider,
        baseUrl: String,
        apiKey: String,
    ): VoiceIdsResult = withContext(Dispatchers.IO) {
        try {
            val (path, body) = when (provider) {
                TtsProvider.ALIYUN -> ALIYUN_CUSTOMIZATION to TtsRequests.aliyunListVoice()
                TtsProvider.MINIMAX -> "get_voice" to TtsRequests.minimaxListVoice()
            }
            when (val http = executePost(baseUrl, path, apiKey, body)) {
                is HttpText.Err -> VoiceIdsResult.Failure(http.message)
                is HttpText.Ok -> VoiceIdsResult.Success(
                    when (provider) {
                        TtsProvider.ALIYUN -> TtsResponses.aliyunVoiceIds(http.text)
                        TtsProvider.MINIMAX -> TtsResponses.minimaxVoiceIds(http.text)
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            VoiceIdsResult.Failure(friendlyError(e))
        }
    }

    // 克隆音色；阿里创建后轮询至部署完成。
    suspend fun cloneVoice(params: CloneParams): CloneVoiceResult = withContext(Dispatchers.IO) {
        try {
            when (params.provider) {
                TtsProvider.ALIYUN -> cloneAliyun(params)
                TtsProvider.MINIMAX -> cloneMinimax(params)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            CloneVoiceResult.Failure(friendlyError(e))
        }
    }

    // 云端删除克隆音色；失败时调用方保留本地卡片。
    suspend fun deleteVoice(
        provider: TtsProvider,
        baseUrl: String,
        apiKey: String,
        voiceId: String,
    ): DeleteVoiceResult = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                TtsProvider.ALIYUN -> deleteAliyun(baseUrl, apiKey, voiceId)
                TtsProvider.MINIMAX -> deleteMinimax(baseUrl, apiKey, voiceId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            DeleteVoiceResult.Failure(friendlyError(e))
        }
    }

    // 阿里 HTTP 200 即成功。
    private fun deleteAliyun(baseUrl: String, apiKey: String, voiceId: String): DeleteVoiceResult {
        val http = executePost(
            baseUrl,
            ALIYUN_CUSTOMIZATION,
            apiKey,
            TtsRequests.aliyunDeleteVoice(voiceId),
        )
        return when (http) {
            is HttpText.Err -> DeleteVoiceResult.Failure(http.message)
            is HttpText.Ok -> DeleteVoiceResult.Success
        }
    }

    // MiniMax 以 base_resp 判定。
    private fun deleteMinimax(baseUrl: String, apiKey: String, voiceId: String): DeleteVoiceResult {
        val http = executePost(baseUrl, "delete_voice", apiKey, TtsRequests.minimaxDeleteVoice(voiceId))
        val text = when (http) {
            is HttpText.Err -> return DeleteVoiceResult.Failure(http.message)
            is HttpText.Ok -> http.text
        }
        val code = TtsResponses.minimaxStatusCode(text)
        return if (code != null && code != 0) {
            DeleteVoiceResult.Failure(
                TtsResponses.minimaxStatusMessage(text) ?: "删除失败（错误码 $code）",
            )
        } else {
            DeleteVoiceResult.Success
        }
    }

    // 合成入参；model 语义随服务商（阿里 target_model / MiniMax 合成模型名，如 speech-2.8-hd）。
    data class SynthParams(
        val provider: TtsProvider,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val voiceId: String,
        val text: String,
        val language: String? = null, // 合成目标语言 → 阿里 language_hints / MiniMax language_boost
    )

    // 合成台词语音（mp3 字节）；阿里走 SSE 流式，MiniMax 走同步 JSON。
    suspend fun synth(params: SynthParams): SynthResult = withContext(Dispatchers.IO) {
        try {
            when (params.provider) {
                TtsProvider.ALIYUN -> synthAliyun(params)
                TtsProvider.MINIMAX -> synthMinimax(params)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            SynthResult.Failure(friendlyError(e))
        }
    }

    private fun synthMinimax(params: SynthParams): SynthResult {
        val text = when (
            val http = executePost(
                baseUrl = params.baseUrl,
                path = "t2a_v2",
                apiKey = params.apiKey,
                body = TtsRequests.minimaxSynth(
                    model = params.model.trim(),
                    voiceId = params.voiceId.trim(),
                    text = params.text,
                    language = params.language,
                ),
            )
        ) {
            is HttpText.Err -> return SynthResult.Failure(http.message)
            is HttpText.Ok -> http.text
        }
        val code = TtsResponses.minimaxStatusCode(text)
        if (code == null || code != 0) {
            return SynthResult.Failure(
                TtsResponses.minimaxStatusMessage(text)
                    ?: TtsResponses.errorMessage(text)
                    ?: "合成失败（错误码 ${code ?: "未知"}）",
            )
        }
        val hex = TtsResponses.minimaxAudioHex(text)
            ?: return SynthResult.Failure("合成响应缺少音频数据")
        return SynthResult.Ok(hexToBytes(hex))
    }

    // SSE 帧逐段返回 Base64 音频，拼接至 finish_reason == stop；
    // 非流式响应只给 24h 时效的 http 下载链接（受明文流量限制），故不走非流式。
    private suspend fun synthAliyun(params: SynthParams): SynthResult {
        if (params.model.isBlank()) return SynthResult.Failure("无法从 Voice ID 解析合成模型")
        val call = apiFor(params.baseUrl).postStream(
            url = "services/audio/tts/SpeechSynthesizer",
            headers = bearer(params.apiKey) + mapOf("X-DashScope-SSE" to "enable"),
            body = TtsRequests.aliyunSynth(
                model = params.model.trim(),
                voice = params.voiceId.trim(),
                text = params.text,
                language = params.language,
            ),
        )
        currentCoroutineContext().job.invokeOnCompletion { runCatching { call.cancel() } }
        val response = call.execute()
        if (!response.isSuccessful) return SynthResult.Failure(responseError(response))
        val audio = ByteArrayOutputStream()
        response.body()?.charStream()?.let { stream ->
            BufferedReader(stream).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    TtsResponses.aliyunSseAudioData(payload)?.let { audio.write(it) }
                    if (TtsResponses.aliyunSseFinished(payload)) break
                }
            }
        }
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) return SynthResult.Failure("合成响应中没有音频数据")
        return SynthResult.Ok(bytes)
    }

    private suspend fun cloneAliyun(params: CloneParams): CloneVoiceResult {
        val createdText = when (
            val http = executePost(
                baseUrl = params.baseUrl,
                path = ALIYUN_CUSTOMIZATION,
                apiKey = params.apiKey,
                body = TtsRequests.aliyunCreateVoice(
                    targetModel = params.model.trim(),
                    audioDataUri = audioDataUri(params.cloneAudio),
                    language = params.language,
                ),
            )
        ) {
            is HttpText.Err -> return CloneVoiceResult.Failure(http.message)
            is HttpText.Ok -> http.text
        }
        val voiceId = TtsResponses.aliyunVoiceId(createdText)
            ?: return CloneVoiceResult.Failure(
                TtsResponses.errorMessage(createdText) ?: "创建音色失败，未返回 Voice ID",
            )

        // 创建后需审核部署，轮询至出结果；单次查询失败（网络抖动/限流）连续 3 次才判克隆失败。
        var consecutiveFailures = 0
        repeat(ALIYUN_POLL_TIMES) {
            delay(ALIYUN_POLL_INTERVAL)
            when (val poll = pollAliyunOnce(params, voiceId)) {
                AliyunPoll.Deployed -> return CloneVoiceResult.Success(voiceId)
                AliyunPoll.Undeployed -> return CloneVoiceResult.Failure("音色审核未通过")
                AliyunPoll.Pending -> consecutiveFailures = 0
                is AliyunPoll.Failed -> if (++consecutiveFailures >= ALIYUN_POLL_FAILURES) {
                    return CloneVoiceResult.Failure(poll.message)
                }
            }
        }
        return CloneVoiceResult.Failure("音色部署超时，请稍后通过「获取音色」确认状态")
    }

    // 单次部署状态查询：HTTP 成功但缺 status 视为部署中，不计入失败。
    private suspend fun pollAliyunOnce(params: CloneParams, voiceId: String): AliyunPoll =
        try {
            when (
                val http = executePost(
                    baseUrl = params.baseUrl,
                    path = ALIYUN_CUSTOMIZATION,
                    apiKey = params.apiKey,
                    body = TtsRequests.aliyunQueryVoice(voiceId),
                )
            ) {
                is HttpText.Err -> AliyunPoll.Failed(http.message)
                is HttpText.Ok -> when (TtsResponses.aliyunStatus(http.text)) {
                    "OK" -> AliyunPoll.Deployed
                    "UNDEPLOYED" -> AliyunPoll.Undeployed
                    else -> AliyunPoll.Pending
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            AliyunPoll.Failed(friendlyError(e))
        }

    private fun cloneMinimax(params: CloneParams): CloneVoiceResult {
        // 第一步：上传克隆音频；参考音频（可选）走 prompt_audio 用途。
        val fileId = when (val up = uploadMinimax(params, params.cloneAudio, "voice_clone")) {
            is UploadResult.Ok -> up.fileId
            is UploadResult.Err -> return CloneVoiceResult.Failure("克隆音频上传失败：${up.message}")
        }
        val promptFileId = params.promptAudio?.let { audio ->
            when (val up = uploadMinimax(params, audio, "prompt_audio")) {
                is UploadResult.Ok -> up.fileId
                is UploadResult.Err -> return CloneVoiceResult.Failure("参考音频上传失败：${up.message}")
            }
        }

        // 第二步：提交复刻；不带试听参数（text/model），避免额外计费。
        val text = when (
            val http = executePost(
                baseUrl = params.baseUrl,
                path = "voice_clone",
                apiKey = params.apiKey,
                body = TtsRequests.minimaxCloneVoice(
                    fileId = fileId,
                    voiceId = params.voiceId.trim(),
                    promptFileId = promptFileId,
                    promptText = params.promptText,
                    language = params.language,
                ),
            )
        ) {
            is HttpText.Err -> return CloneVoiceResult.Failure(http.message)
            is HttpText.Ok -> http.text
        }
        val code = TtsResponses.minimaxStatusCode(text)
        if (code == null || code != 0) {
            return CloneVoiceResult.Failure(
                TtsResponses.minimaxStatusMessage(text)
                    ?: TtsResponses.errorMessage(text)
                    ?: "克隆失败（错误码 ${code ?: "未知"}）",
            )
        }
        return CloneVoiceResult.Success(params.voiceId.trim())
    }

    // multipart 上传；失败返回用户可读原因，不吞真实错误。
    private fun uploadMinimax(
        params: CloneParams,
        audio: File,
        purpose: String,
    ): UploadResult {
        val filePart = MultipartBody.Part.createFormData(
            name = "file",
            filename = audio.name,
            body = audio.asRequestBody(audioMime(audio).toMediaType()),
        )
        val text = when (val http = executeUpload(params.baseUrl, params.apiKey, purpose, filePart)) {
            is HttpText.Err -> return UploadResult.Err(http.message)
            is HttpText.Ok -> http.text
        }
        val code = TtsResponses.minimaxStatusCode(text)
        if (code == null || code != 0) {
            return UploadResult.Err(
                TtsResponses.minimaxStatusMessage(text)
                    ?: TtsResponses.errorMessage(text)
                    ?: "错误码 ${code ?: "未知"}",
            )
        }
        return TtsResponses.minimaxFileId(text)?.let { UploadResult.Ok(it) }
            ?: UploadResult.Err("响应缺少 file_id")
    }

    // ---- 连接与错误 ----

    private fun bearer(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    private fun audioMime(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        else -> "audio/mpeg"
    }

    private fun apiFor(url: String): TtsApi {
        val baseUrl = if (url.endsWith("/")) url else "$url/"
        return cachedApi?.takeIf { cachedBaseUrl == baseUrl }
            ?: Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .build()
                .create(TtsApi::class.java)
                .also {
                    cachedApi = it
                    cachedBaseUrl = baseUrl
                }
    }

    // 执行 JSON POST；网络异常向上抛，由各公共入口统一转换。
    private fun executePost(
        baseUrl: String,
        path: String,
        apiKey: String,
        body: RequestBody,
    ): HttpText {
        val response = apiFor(baseUrl).post(path, bearer(apiKey), body).execute()
        return readResponse(response)
    }

    // 执行 MiniMax multipart 上传（files/upload）。
    private fun executeUpload(
        baseUrl: String,
        apiKey: String,
        purpose: String,
        file: MultipartBody.Part,
    ): HttpText {
        val response = apiFor(baseUrl)
            .upload(
                "files/upload",
                bearer(apiKey),
                purpose.toRequestBody("text/plain".toMediaType()),
                file,
            )
            .execute()
        return readResponse(response)
    }

    // 成功响应读出文本；非 2xx 或读取失败转为用户可读错误。
    private fun readResponse(response: Response<ResponseBody>): HttpText {
        val text = if (response.isSuccessful) {
            runCatching { response.body()?.string() }.getOrNull()
        } else {
            null
        }
        return if (text != null) HttpText.Ok(text) else HttpText.Err(responseError(response))
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "无法解析主机，请检查 BaseUrl"
        is IllegalArgumentException -> "BaseUrl 格式不正确"
        is IOException -> "网络连接失败，请检查网络或 BaseUrl"
        else -> e.message ?: "请求失败"
    }

    private fun httpErrorMessage(code: Int, errorBody: String?): String = when (code) {
        401 -> "API Key 无效或未授权"
        403 -> "无访问权限，请检查账号认证状态"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务器错误"
        else -> TtsResponses.errorMessage(errorBody) ?: "请求失败（HTTP $code）"
    }

    private fun responseError(response: Response<ResponseBody>): String =
        httpErrorMessage(response.code(), runCatching { response.errorBody()?.string() }.getOrNull())

    private companion object {
        const val ALIYUN_POLL_TIMES = 30
        const val ALIYUN_POLL_INTERVAL = 2000L

        // 查询部署状态连续失败上限，达到才判克隆失败。
        const val ALIYUN_POLL_FAILURES = 3
    }
}
