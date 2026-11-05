package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.chat.Role
import kotlinx.serialization.Serializable

// 会话类型；VIDEO 启用视频聊天：悬浮摄像头窗、画面变化/CAMERA 请求发帧，主控规则不生效。
@Serializable
enum class ChatType { TEXT, VIDEO }

// 持久化为 sessions/{id}.json。
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val characterIds: List<String>,
    // 用户扮演的主控角色；空=未设置。至少 2 名角色时才有效；VIDEO 会话固定为空（主控规则与视频聊天互斥）。
    val playerCharacterId: String? = null,
    // 新建会话时的语言快照 characterId → zh/ja/en；空串=自动，键缺失=无快照跳过云 TTS。
    val voiceLanguages: Map<String, String> = emptyMap(),
    // 会话级云语音开关；关闭后绑定卡片不再合成，回退本地立绘语音。
    val cloudTtsEnabled: Boolean = true,
    // 绑定的背景包 id；空=无背景。指向已删除的包时按无背景处理。
    val backgroundId: String? = null,
    // 聊天类型；旧存档缺字段回落普通聊天。
    val chatType: ChatType = ChatType.TEXT,
    val scenario: String,
    val history: List<SessionEntry>, // 首条为 system 提示词快照，可在会话设置页重生成。
    val createdAt: Long,
    val updatedAt: Long,
)

// content 保存原文；complete=false 残片可查看但不进请求；summary=true 为压缩摘要；image 非空为摄像头帧（仅最近一帧参与请求）。
@Serializable
data class SessionEntry(
    val role: Role,
    val content: String,
    val complete: Boolean = true,
    val summary: Boolean = false,
    val image: String? = null,
)
