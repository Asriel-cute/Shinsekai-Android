package com.dreemurr.shinsekai.chat

// CAMERA 画面请求协议常量：工具名、续跑合成结果与帧消息文案，chat/session/ui 三处共用。
internal const val CAMERA_TOOL_NAME = "request_camera"
internal const val CAMERA_TOOL_RESULT = "已拍摄用户当前画面，画面见下一条图像消息。"
internal const val CAMERA_FRAME_TEXT = "（摄像头画面）"

// 展示条目的保留名与中文别名：匹配、原文预筛与落盘合成共用同一份字面量，不在各调用方另写。
internal const val SCENE_MARKER_NAME = "SCENE"
internal const val SCENE_ALIAS_NAME = "场景"
internal const val BGM_MARKER_NAME = "bgm"
internal const val CAMERA_MARKER_NAME = "CAMERA"
internal const val CAMERA_ALIAS_NAME = "摄像头"
internal const val STAT_MARKER_NAME = "STAT"
internal const val STAT_ALIAS_NAME = "数值"
internal const val CHOICE_MARKER_NAME = "CHOICE"
internal const val CHOICE_ALIAS_NAME = "选项"

// dialog 内保留名对应的展示类型（名称与别名匹配见下）；sprite 为 1 基编号。
enum class PresentationKind { Scene, Bgm, Camera, Stat, Choice }

fun presentationKind(name: String): PresentationKind? {
    val trimmed = name.trim()
    return when {
        trimmed == SCENE_MARKER_NAME || trimmed == SCENE_ALIAS_NAME -> PresentationKind.Scene
        trimmed.lowercase() == BGM_MARKER_NAME -> PresentationKind.Bgm
        trimmed == CAMERA_MARKER_NAME || trimmed == CAMERA_ALIAS_NAME -> PresentationKind.Camera
        trimmed == STAT_MARKER_NAME || trimmed == STAT_ALIAS_NAME -> PresentationKind.Stat
        trimmed == CHOICE_MARKER_NAME || trimmed == CHOICE_ALIAS_NAME -> PresentationKind.Choice
        else -> null
    }
}

// CHOICE 的 speech 拆成选项列表；容忍全角斜杠与换行分隔。
fun choiceOptions(speech: String): List<String> =
    speech.split('/', '／', '\n').map { it.trim() }.filter { it.isNotEmpty() }
