package com.dreemurr.shinsekai.core

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode

// 手写 YamlNode 读取的公共 helper：兼容缺字段、null、引号数字和单 map 根。

internal fun stripBom(bytes: ByteArray): ByteArray =
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }

internal fun YamlNode.unwrap(): YamlNode =
    if (this is YamlTaggedNode) innerNode.unwrap() else this

// YamlMap.entries 是 Map，键按标量内容匹配。
internal fun YamlMap.node(key: String): YamlNode? =
    entries.entries.firstOrNull { it.key.content == key }?.value?.unwrap()

internal fun YamlMap.str(key: String): String? = (node(key) as? YamlScalar)?.content

internal fun YamlMap.float(key: String): Float? =
    str(key)?.trim()?.toFloatOrNull()

// 根节点解析结果：正常条目 / 空 yaml / 根不是列表或 map。
internal sealed interface YamlRoot {
    data class Items(val items: List<YamlMap>) : YamlRoot
    data object Empty : YamlRoot
    data object NotACollection : YamlRoot
}

// 列表根逐项校验（非键值条目告警跳过），单 map 根视为单条目；itemLabel 用于告警文案。
internal fun yamlRootItems(root: YamlNode, itemLabel: String, warnings: MutableList<String>): YamlRoot =
    when (val node = root.unwrap()) {
        is YamlList -> YamlRoot.Items(
            node.items.mapIndexedNotNull { index, item ->
                when (val map = item.unwrap()) {
                    is YamlMap -> map
                    is YamlNull -> null
                    else -> {
                        warnings += "第 ${index + 1} 个${itemLabel}条目不是键值结构，已跳过"
                        null
                    }
                }
            }
        )

        is YamlMap -> YamlRoot.Items(listOf(node))
        is YamlNull -> YamlRoot.Empty
        else -> YamlRoot.NotACollection
    }
