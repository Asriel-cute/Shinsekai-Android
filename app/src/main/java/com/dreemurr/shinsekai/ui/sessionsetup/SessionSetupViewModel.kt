package com.dreemurr.shinsekai.ui.sessionsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.voice.VoiceLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 新建与编辑共用；编辑模式在 ready 后填充已有会话参数。
class SessionSetupViewModel(
    private val container: AppContainer,
    private val sessionId: String?,
) : ViewModel() {

    val characters = container.characterRepository.characters
    val backgrounds = container.backgroundRepository.backgrounds
    val ready = container.ready

    private val _existing = MutableStateFlow<ChatSession?>(null)
    val existing: StateFlow<ChatSession?> = _existing.asStateFlow()

    init {
        val id = sessionId
        if (id != null) {
            viewModelScope.launch {
                container.ready.first { it }
                _existing.value = container.sessionRepository.sessions.value.firstOrNull { it.id == id }
            }
        }
    }

    fun save(
        selectedIds: Set<String>,
        scenario: String,
        cloudTtsEnabled: Boolean,
        playerCharacterId: String?,
        backgroundId: String?,
        onSaved: (String) -> Unit,
    ) {
        viewModelScope.launch {
            container.ready.first { it }
            val current = _existing.value
            val byId = characters.value.associateBy { it.id }
            // 保留会话原有角色顺序，新增角色按仓库顺序追加。
            val existingIds = current?.characterIds.orEmpty()
            val existingIdSet = existingIds.toSet()
            val ordered = (
                existingIds.mapNotNull(byId::get) +
                    characters.value.filter { it.id !in existingIdSet }
                ).filter { it.id in selectedIds }
            if (ordered.isEmpty()) return@launch

            // 已删除的背景包按无背景处理，保存后清除绑定。
            val background = backgroundId
                ?.let { id -> backgrounds.value.firstOrNull { it.id == id } }

            val cardsById = container.voiceCardRepository.cards.value.associateBy { it.id }
            val bindings = container.voiceBindings.bindings.value
            // 快照包含自动语言（空串）；未绑定或卡片不可用的角色不进入会话语音表。
            val voiceLanguages = ordered.mapNotNull { character ->
                val card = bindings[character.id]?.let(cardsById::get) ?: return@mapNotNull null
                if (!card.isTtsReady) return@mapNotNull null
                character.id to (VoiceLanguages.normalize(card.lang) ?: VoiceLanguages.AUTO)
            }.toMap()

            val id = if (current == null) {
                container.sessionRepository.create(
                    characters = ordered,
                    scenario = scenario,
                    voiceLanguages = voiceLanguages,
                    cloudTtsEnabled = cloudTtsEnabled,
                    playerCharacterId = playerCharacterId,
                    background = background,
                ).id
            } else {
                container.sessionRepository.updateSetup(
                    id = current.id,
                    characters = ordered,
                    scenario = scenario,
                    voiceLanguages = voiceLanguages,
                    cloudTtsEnabled = cloudTtsEnabled,
                    playerCharacterId = playerCharacterId,
                    background = background,
                )?.id ?: return@launch
            }
            onSaved(id)
        }
    }
}
