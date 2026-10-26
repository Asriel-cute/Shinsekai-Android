package com.dreemurr.shinsekai.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

// 请求体序列化省略 null 与默认值字段。
private val providerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

private val jsonMediaType = "application/json".toMediaType()

// ---- JSON 导航小工具（宽容处理各家形状差异，避免解析异常中断请求） ----

private fun parseObj(body: String?): JsonObject? = body?.let {
    runCatching { providerJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

// ---- 阿里百炼 voice-enrollment ----
// 端点：{base}/services/audio/tts/customization，action 区分增查删。

@Serializable
private data class AliyunEnrollmentRequest(
    val model: String, // 固定 voice-enrollment
    val input: AliyunEnrollmentInput,
)

@Serializable
private data class AliyunEnrollmentInput(
    val action: String,
    @SerialName("target_model") val targetModel: String? = null,
    val prefix: String? = null,
    val audio: String? = null,
    @SerialName("language_hints") val languageHints: List<String>? = null,
    @SerialName("voice_id") val voiceId: String? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("page_index") val pageIndex: Int? = null,
)

private fun aliyunBody(input: AliyunEnrollmentInput): RequestBody = providerJson
    .encodeToString(AliyunEnrollmentRequest(model = "voice-enrollment", input = input))
    .toRequestBody(jsonMediaType)

// 音色名前缀仅允许数字与英文字母、不超过 10 字符，随机生成不暴露表单。
private val prefixSource = ('a'..'z') + ('0'..'9')

internal fun randomVoicePrefix(): String = buildString {
    repeat(8) { append(prefixSource.random()) }
}

// VoiceLanguages 当前只产出 zh/ja/en，CosyVoice 均支持；自动/未知不传，交给服务端检测。
internal fun aliyunLanguageHint(lang: String?): List<String>? =
    VoiceLanguages.normalize(lang)?.let { listOf(it) }

// MiniMax language_boost 枚举；未指定时传 auto，交给服务端自动识别。
internal fun minimaxLanguageBoost(lang: String?): String = when (VoiceLanguages.normalize(lang)) {
    VoiceLanguages.ZH -> "Chinese"
    VoiceLanguages.JA -> "Japanese"
    VoiceLanguages.EN -> "English"
    else -> "auto"
}

// ---- MiniMax ----

@Serializable
private data class MiniMaxVoiceCloneRequest(
    @SerialName("file_id") val fileId: Long,
    @SerialName("voice_id") val voiceId: String,
    @SerialName("clone_prompt") val clonePrompt: MiniMaxClonePrompt? = null,
    @SerialName("language_boost") val languageBoost: String? = null,
    @SerialName("need_noise_reduction") val needNoiseReduction: Boolean? = null,
)

@Serializable
private data class MiniMaxClonePrompt(
    @SerialName("prompt_audio") val promptAudio: Long,
    @SerialName("prompt_text") val promptText: String,
)

@Serializable
private data class MiniMaxVoiceTypeRequest(
    @SerialName("voice_type") val voiceType: String,
    @SerialName("voice_id") val voiceId: String? = null,
)

// ---- 语音合成请求 ----

// 阿里 CosyVoice：voice/format 位于 input 内。
@Serializable
private data class AliyunSynthRequest(
    val model: String,
    val input: AliyunSynthInput,
)

@Serializable
private data class AliyunSynthInput(
    val text: String,
    val voice: String,
    val format: String = "mp3",
    @SerialName("language_hints") val languageHints: List<String>? = null,
)

@Serializable
private data class MiniMaxSynthRequest(
    val model: String,
    val text: String,
    @SerialName("voice_setting") val voiceSetting: MiniMaxVoiceSetting,
    @SerialName("audio_setting") val audioSetting: MiniMaxAudioSetting,
    @SerialName("language_boost") val languageBoost: String? = null,
)

@Serializable
private data class MiniMaxVoiceSetting(
    @SerialName("voice_id") val voiceId: String,
)

@Serializable
private data class MiniMaxAudioSetting(
    val format: String = "mp3",
)

// ---- 请求体构造 ----

internal object TtsRequests {

    fun aliyunListVoice() = aliyunBody(
        AliyunEnrollmentInput(action = "list_voice", pageSize = 100, pageIndex = 0),
    )

    fun aliyunCreateVoice(targetModel: String, audioDataUri: String, language: String?) = aliyunBody(
        AliyunEnrollmentInput(
            action = "create_voice",
            targetModel = targetModel,
            prefix = randomVoicePrefix(),
            audio = audioDataUri,
            languageHints = aliyunLanguageHint(language),
        ),
    )

    fun aliyunQueryVoice(voiceId: String) = aliyunBody(
        AliyunEnrollmentInput(action = "query_voice", voiceId = voiceId),
    )

    fun aliyunDeleteVoice(voiceId: String) = aliyunBody(
        AliyunEnrollmentInput(action = "delete_voice", voiceId = voiceId),
    )

    fun minimaxCloneVoice(
        fileId: Long,
        voiceId: String,
        promptFileId: Long?,
        promptText: String?,
        language: String?,
    ) = providerJson.encodeToString(
        MiniMaxVoiceCloneRequest(
            fileId = fileId,
            voiceId = voiceId,
            // 参考音频与文字成对提供才有意义。
            clonePrompt = if (promptFileId != null && !promptText.isNullOrBlank()) {
                MiniMaxClonePrompt(promptAudio = promptFileId, promptText = promptText.trim())
            } else {
                null
            },
            languageBoost = minimaxLanguageBoost(language),
        ),
    ).toRequestBody(jsonMediaType)

    fun minimaxListVoice() = providerJson
        .encodeToString(MiniMaxVoiceTypeRequest(voiceType = "all"))
        .toRequestBody(jsonMediaType)

    fun minimaxDeleteVoice(voiceId: String) = providerJson
        .encodeToString(MiniMaxVoiceTypeRequest(voiceType = "voice_cloning", voiceId = voiceId))
        .toRequestBody(jsonMediaType)

    fun aliyunSynth(model: String, voice: String, text: String, language: String?) = providerJson
        .encodeToString(
            AliyunSynthRequest(
                model = model,
                input = AliyunSynthInput(
                    text = text,
                    voice = voice,
                    languageHints = aliyunLanguageHint(language),
                ),
            ),
        )
        .toRequestBody(jsonMediaType)

    fun minimaxSynth(model: String, voiceId: String, text: String, language: String?) = providerJson
        .encodeToString(
            MiniMaxSynthRequest(
                model = model,
                text = text,
                voiceSetting = MiniMaxVoiceSetting(voiceId = voiceId),
                audioSetting = MiniMaxAudioSetting(),
                languageBoost = minimaxLanguageBoost(language),
            ),
        )
        .toRequestBody(jsonMediaType)
}

// ---- 响应解析 ----

internal object TtsResponses {

    // 阿里 list_voice：output.voice_list[].voice_id。
    fun aliyunVoiceIds(body: String): List<String> = parseObj(body)
        ?.obj("output")?.arr("voice_list")
        ?.mapNotNull { (it as? JsonObject)?.str("voice_id") }
        ?.sorted()
        ?: emptyList()

    // 阿里 create_voice：output.voice_id。
    fun aliyunVoiceId(body: String): String? = parseObj(body)?.obj("output")?.str("voice_id")

    // 阿里 query_voice：output.status（DEPLOYING/OK/UNDEPLOYED）。
    fun aliyunStatus(body: String): String? = parseObj(body)?.obj("output")?.str("status")

    // MiniMax 文件上传：file.file_id。
    fun minimaxFileId(body: String): Long? = parseObj(body)?.obj("file")?.long("file_id")

    // MiniMax 通用：base_resp.status_code，0 正常。
    fun minimaxStatusCode(body: String): Int? = parseObj(body)?.obj("base_resp")?.int("status_code")

    fun minimaxStatusMessage(body: String): String? = parseObj(body)?.obj("base_resp")?.str("status_msg")

    // MiniMax get_voice：形状随分类变化，递归收集全部 voice_id。
    fun minimaxVoiceIds(body: String): List<String> {
        val found = LinkedHashSet<String>()
        runCatching { collectVoiceIds(providerJson.parseToJsonElement(body), found) }
        return found.sorted()
    }

    private fun collectVoiceIds(element: JsonElement, out: MutableSet<String>) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                val id = (value as? JsonPrimitive)?.contentOrNull
                if (key == "voice_id" && !id.isNullOrBlank()) {
                    out += id
                } else {
                    collectVoiceIds(value, out)
                }
            }

            is JsonArray -> element.forEach { collectVoiceIds(it, out) }

            else -> Unit
        }
    }

    // MiniMax t2a_v2：data.audio 为 hex 编码音频；调用方须先校验 base_resp。
    fun minimaxAudioHex(body: String): String? =
        parseObj(body)?.obj("data")?.str("audio")?.takeIf { it.isNotBlank() }

    // 阿里 SSE 帧：output.audio.data 为 Base64 音频分片。
    fun aliyunSseAudioData(frame: String): ByteArray? = parseObj(frame)
        ?.obj("output")?.obj("audio")?.str("data")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    // 阿里 SSE 帧：finish_reason == stop 表示合成结束。
    fun aliyunSseFinished(frame: String): Boolean =
        parseObj(frame)?.obj("output")?.str("finish_reason") == "stop"

    // 统一错误信息提取：阿里 {message} / {error:{message}}，MiniMax {base_resp:{status_msg}}。
    fun errorMessage(body: String?): String? {
        val obj = parseObj(body) ?: return null
        return obj.str("message")
            ?: obj.obj("error")?.str("message")
            ?: obj.obj("base_resp")?.str("status_msg")
    }
}

// 阿里复刻音频以 Base64 Data URI 提交本地文件。
internal fun audioDataUri(file: File): String {
    val mime = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        else -> "audio/mpeg" // mp3 及其余按 mp3 处理
    }
    return "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
}

// MiniMax t2a_v2 音频为 hex 编码；minSdk 30 无依赖库，手写解码。
internal fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val high = Character.digit(hex[i * 2], 16)
        val low = Character.digit(hex[i * 2 + 1], 16)
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
