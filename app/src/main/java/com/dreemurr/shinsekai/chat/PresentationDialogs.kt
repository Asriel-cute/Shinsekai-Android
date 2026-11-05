package com.dreemurr.shinsekai.chat

// dialog 内保留名的展示切换类型：SCENE/场景 切背景图、bgm 切音乐、CAMERA/摄像头 为画面请求工具条目；sprite 为 1 基编号。
enum class PresentationKind { Scene, Bgm, Camera }

fun presentationKind(name: String): PresentationKind? {
    val trimmed = name.trim()
    return when {
        trimmed == "SCENE" || trimmed == "场景" -> PresentationKind.Scene
        trimmed.lowercase() == "bgm" -> PresentationKind.Bgm
        trimmed == "CAMERA" || trimmed == "摄像头" -> PresentationKind.Camera
        else -> null
    }
}
