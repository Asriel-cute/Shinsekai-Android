package com.dreemurr.shinsekai.background

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import com.dreemurr.shinsekai.character.ImportError
import com.dreemurr.shinsekai.character.packagePathBasename

// 旧包路径不可靠（如 data/backgrounds\prefix\file.png），素材引用只保留 basename。
internal data class RawBackground(
    val name: String,
    val spritePrefix: String,
    val sprites: List<String?>,
    val bgTags: String,
    val bgm: List<String?>,
    val bgmTags: String,
)

// 手动读取 YamlNode，兼容缺字段、null、引号数字和单 map 根；一个包可含多个背景组。
internal object BackgroundYamlParser {

    fun parse(bytes: ByteArray): Pair<List<RawBackground>, List<String>> {
        val warnings = mutableListOf<String>()
        val root = try {
            Yaml.default.parseToYamlNode(stripBom(bytes).toString(Charsets.UTF_8))
        } catch (e: YamlException) {
            throw BackgroundPackageException(ImportError.MalformedYaml, "background.yaml 解析失败", e)
        }

        val items: List<YamlMap> = when (val node = root.unwrap()) {
            is YamlList -> node.items.mapIndexedNotNull { index, item ->
                when (val map = item.unwrap()) {
                    is YamlMap -> map
                    is YamlNull -> null
                    else -> {
                        warnings += "第 ${index + 1} 个背景条目不是键值结构，已跳过"
                        null
                    }
                }
            }
            is YamlMap -> listOf(node)
            is YamlNull -> throw BackgroundPackageException(ImportError.EmptyPackage, "background.yaml 内容为空")
            else -> throw BackgroundPackageException(ImportError.MalformedYaml, "background.yaml 根节点不是列表")
        }

        val backgrounds = mutableListOf<RawBackground>()
        for (item in items) {
            val name = item.str("name")?.trim().orEmpty()
            if (name.isEmpty()) {
                warnings += "跳过一个缺少 name 的背景条目"
                continue
            }
            backgrounds += RawBackground(
                name = name,
                spritePrefix = item.str("sprite_prefix")?.trim().orEmpty(),
                sprites = parsePathList(item, "sprites"),
                bgTags = item.str("bg_tags").orEmpty(),
                bgm = parsePathList(item, "bgm_list"),
                bgmTags = item.str("bgm_tags").orEmpty(),
            )
        }
        if (backgrounds.isEmpty()) {
            throw BackgroundPackageException(ImportError.EmptyPackage, "包内没有可导入的背景")
        }
        return backgrounds to warnings
    }

    // 接受 map（取 path 键）或路径标量，保持条目顺序与数量。
    private fun parsePathList(item: YamlMap, key: String): List<String?> {
        val list = item.node(key) as? YamlList ?: return emptyList()
        return list.items.mapNotNull { entry ->
            when (val node = entry.unwrap()) {
                is YamlMap -> packagePathBasename(node.str("path").orEmpty())
                is YamlScalar -> packagePathBasename(node.content)
                else -> null
            }
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
}
