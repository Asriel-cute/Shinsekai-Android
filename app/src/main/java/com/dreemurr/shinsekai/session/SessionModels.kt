package com.dreemurr.shinsekai.session

import com.dreemurr.shinsekai.chat.Role
import kotlinx.serialization.Serializable

// 持久化为 sessions/{id}.json。
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val characterIds: List<String>,
    val scenario: String,
    val history: List<SessionEntry>, // 首条为 system 提示词快照。
    val createdAt: Long,
    val updatedAt: Long,
)

// content 保存原文；complete=false 的残片可查看，但不进入后续请求。
@Serializable
data class SessionEntry(
    val role: Role,
    val content: String,
    val complete: Boolean = true,
)
