package com.dreemurr.shinsekai.chat

// dialog 内保留名的展示切换类型：SCENE/场景 切背景图，bgm 切背景音乐（不区分大小写）。
// sprite 字段为 1 基资源编号；其余字段对展示无意义。
enum class PresentationKind { Scene, Bgm }

fun presentationKind(name: String): PresentationKind? {
    val trimmed = name.trim()
    return when {
        trimmed == "SCENE" || trimmed == "场景" -> PresentationKind.Scene
        trimmed.lowercase() == "bgm" -> PresentationKind.Bgm
        else -> null
    }
}
