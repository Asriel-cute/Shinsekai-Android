package com.dreemurr.shinsekai.character

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode

// 旧包路径不可靠，素材引用只保留 basename。
internal data class RawCharacter(
    val name: String,
    val color: String,
    val spritePrefix: String,
    val characterSetting: String,
    val emotionTags: String,
    val spriteScale: Float,
    val speechSpeed: Float,
    val speechVolume: Float,
    val sprites: List<RawSprite>,
)

internal data class RawSprite(
    val imageFileName: String?,
    val voiceFileName: String?,
    val voiceText: String?,
    val voiceType: String?,
)

// 手动读取 YamlNode，兼容缺字段、null、引号数字和单 map 根。
internal object CharacterYamlParser {

    fun parse(bytes: ByteArray): Pair<List<RawCharacter>, List<String>> {
        val warnings = mutableListOf<String>()
        val root = try {
            Yaml.default.parseToYamlNode(stripBom(bytes).toString(Charsets.UTF_8))
        } catch (e: YamlException) {
            throw CharacterPackageException(ImportError.MalformedYaml, "character.yaml 解析失败", e)
        }

        val items: List<YamlMap> = when (val node = root.unwrap()) {
            is YamlList -> node.items.mapIndexedNotNull { index, item ->
                when (val map = item.unwrap()) {
                    is YamlMap -> map
                    is YamlNull -> null
                    else -> {
                        warnings += "第 ${index + 1} 个角色条目不是键值结构，已跳过"
                        null
                    }
                }
            }
            is YamlMap -> listOf(node)
            is YamlNull -> throw CharacterPackageException(ImportError.EmptyPackage, "character.yaml 内容为空")
            else -> throw CharacterPackageException(ImportError.MalformedYaml, "character.yaml 根节点不是列表")
        }

        val characters = mutableListOf<RawCharacter>()
        for (item in items) {
            val name = item.str("name")?.trim().orEmpty()
            if (name.isEmpty()) {
                warnings += "跳过一个缺少 name 的角色条目"
                continue
            }
            characters += RawCharacter(
                name = name,
                color = item.str("color")?.trim().orEmpty(),
                spritePrefix = item.str("sprite_prefix")?.trim().orEmpty(),
                characterSetting = item.str("character_setting").orEmpty(),
                emotionTags = item.str("emotion_tags").orEmpty(),
                spriteScale = item.float("sprite_scale") ?: 1f,
                speechSpeed = item.float("speech_speed") ?: 1f,
                speechVolume = item.float("speech_volume") ?: 1f,
                sprites = parseSprites(item),
            )
        }
        if (characters.isEmpty()) {
            throw CharacterPackageException(ImportError.EmptyPackage, "包内没有可导入的角色")
        }
        return characters to warnings
    }

    // 接受 map 或路径标量，保持条目顺序。
    private fun parseSprites(item: YamlMap): List<RawSprite> {
        val list = item.node("sprites") as? YamlList ?: return emptyList()
        return list.items.mapNotNull { entry ->
            when (val node = entry.unwrap()) {
                is YamlMap -> {
                    val voice = packagePathBasename(node.str("voice_path").orEmpty())
                    val voiceText = node.str("voice_text")?.trim()?.takeIf { it.isNotEmpty() }
                    RawSprite(
                        imageFileName = packagePathBasename(node.str("path").orEmpty()),
                        voiceFileName = voice,
                        voiceText = voiceText,
                        voiceType = voice?.let { normalizeVoiceType(node.str("voice_type"), voiceText != null) },
                    )
                }
                is YamlScalar -> RawSprite(
                    imageFileName = packagePathBasename(node.content),
                    voiceFileName = null,
                    voiceText = null,
                    voiceType = null,
                )
                else -> null
            }
        }
    }

    // 未指定有效类型时，有 voice_text 则为 reference，否则为 fallback。
    private fun normalizeVoiceType(raw: String?, hasVoiceText: Boolean): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "preset", "fallback", "reference" -> value
            else -> if (hasVoiceText) "reference" else "fallback"
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray =
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

    private fun YamlNode.unwrap(): YamlNode =
        if (this is YamlTaggedNode) innerNode.unwrap() else this

    // YamlMap.entries 是 Map，键按标量内容匹配。
    private fun YamlMap.node(key: String): YamlNode? =
        entries.entries.firstOrNull { it.key.content == key }?.value?.unwrap()

    private fun YamlMap.str(key: String): String? = (node(key) as? YamlScalar)?.content

    private fun YamlMap.float(key: String): Float? =
        str(key)?.trim()?.toFloatOrNull()
}

// 统一分隔符后取 basename，排除无效文件名。
internal fun packagePathBasename(raw: String): String? {
    val normalized = raw.replace('\\', '/').trim()
    if (normalized.isEmpty()) return null
    val name = normalized.substringAfterLast('/').trim()
    return name.takeIf { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it }
}
