package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.chat.Role
import kotlinx.serialization.Serializable

// 持久化为 sessions/{id}.json。
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val characterIds: List<String>,
    // 用户扮演的主控角色；空=未设置。至少 2 名角色时才有效。
    val playerCharacterId: String? = null,
    // 新建会话时的语言快照：characterId → zh/ja/en。
    // 空串=自动；键缺失=无快照，跳过云 TTS。
    val voiceLanguages: Map<String, String> = emptyMap(),
    // 会话级云语音开关；关闭后绑定卡片不再合成，回退本地立绘语音。
    val cloudTtsEnabled: Boolean = true,
    val scenario: String,
    val history: List<SessionEntry>, // 首条为 system 提示词快照，可在会话设置页重生成。
    val createdAt: Long,
    val updatedAt: Long,
)

// content 保存原文；complete=false 的残片可查看，但不进入后续请求。
// summary=true 为手动压缩产生的摘要，按 user 角色发送并在记录面板单独展示。
@Serializable
data class SessionEntry(
    val role: Role,
    val content: String,
    val complete: Boolean = true,
    val summary: Boolean = false,
)
