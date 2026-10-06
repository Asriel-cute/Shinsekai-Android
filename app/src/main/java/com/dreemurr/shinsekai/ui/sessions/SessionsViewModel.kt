package com.dreemurr.shinsekai.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.session.ChatSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    val sessions = container.sessionRepository.sessions

    // ready 区分启动加载与空列表。
    val loading = container.ready
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun delete(id: String) {
        viewModelScope.launch { container.sessionRepository.delete(id) }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { container.sessionRepository.rename(id, title.trim()) }
    }

    companion object {
        // 预览最后一条完整消息；assistant 取最后一句台词。
        fun previewOf(session: ChatSession): String {
            val last = session.history.lastOrNull { it.complete && it.role != Role.system }
                ?: return ""
            return when (last.role) {
                Role.user -> last.content
                Role.assistant ->
                    DialogJsonParser.parseAll(last.content).lastOrNull()?.speech.orEmpty()
                Role.system -> ""
            }
        }
    }
}
