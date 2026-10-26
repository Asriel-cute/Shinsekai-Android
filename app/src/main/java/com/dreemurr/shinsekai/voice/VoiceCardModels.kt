package com.dreemurr.shinsekai.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val voiceCardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

internal val voiceCardListSerializer = ListSerializer(VoiceCard.serializer())

// 云 TTS 服务商预设；defaultBaseUrl 仅作表单初值，允许用户修改。
enum class TtsProvider(val defaultBaseUrl: String) {
    ALIYUN("https://dashscope.aliyuncs.com/api/v1"),
    MINIMAX("https://api.minimax.cn/v1"),
}

fun ttsProviderOf(name: String): TtsProvider? =
    TtsProvider.entries.firstOrNull { it.name == name }

// 声音卡片语言：新建会话时决定翻译目标，复刻与合成也使用。
// 空串表示自动。
object VoiceLanguages {
    const val ZH = "zh"
    const val JA = "ja"
    const val EN = "en"
    const val AUTO = ""

    // 兼容 .voice 包常见的语言别名；未知/自动返回 null。
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (value.isEmpty()) return null
        return when (value.substringBefore('-')) {
            "zh" -> ZH
            "ja" -> JA
            "en" -> EN
            else -> when (value) {
                "cn", "chinese", "cmn", "hans" -> ZH
                "jp", "jpn", "japanese" -> JA
                "eng", "english" -> EN
                else -> null
            }
        }
    }
}

// 阿里复刻的 voice_id 形如 {target_model}-{prefix}-{unique}；解析失败回退存储值。
internal fun aliyunModelFromVoiceId(voiceId: String, fallback: String = ""): String =
    voiceId.trim().split('-').dropLast(2).joinToString("-").ifBlank { fallback }

// 一张声音卡片 = 某服务商下的一套 TTS 配置；API Key 只落密文。
@Serializable
data class VoiceCard(
    val id: String,
    val name: String,
    // 存 TtsProvider.name；未知值读取时跳过。
    val provider: String,
    val baseUrl: String = "",
    val apiKeyCipher: String = "",
    val model: String = "",
    val voiceId: String = "",
    // zh/ja/en；空=自动。
    val lang: String = VoiceLanguages.AUTO,
    val createdAt: Long = 0,
) {
    val providerEnum: TtsProvider? get() = ttsProviderOf(provider)

    // 建会话快照与合成前统一判断可用性，避免各处重复 provider/voiceId 校验。
    val isTtsReady: Boolean get() = providerEnum != null && voiceId.isNotBlank()

    // 阿里以 voice_id 内嵌目标模型为准，避免编辑音色后沿用旧 model。
    val synthModel: String
        get() = when (providerEnum) {
            TtsProvider.ALIYUN -> aliyunModelFromVoiceId(voiceId, model)
            else -> model
        }
}
