package com.dreemurr.shinsekai.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.voice.VoiceLanguages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NewSessionViewModel(private val container: AppContainer) : ViewModel() {

    val characters = container.characterRepository.characters

    fun create(
        selectedIds: Set<String>,
        scenario: String,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            container.ready.first { it }
            val selected = characters.value.filter { it.id in selectedIds }
            if (selected.isEmpty()) return@launch
            val cardsById = container.voiceCardRepository.cards.value.associateBy { it.id }
            val bindings = container.voiceBindings.bindings.value
            // 快照包含自动语言（空串）；未绑定或卡片不可用的角色不进入会话语音表。
            val voiceLanguages = selected.mapNotNull { character ->
                val card = bindings[character.id]?.let(cardsById::get) ?: return@mapNotNull null
                if (!card.isTtsReady) return@mapNotNull null
                character.id to (VoiceLanguages.normalize(card.lang) ?: VoiceLanguages.AUTO)
            }.toMap()
            val session = container.sessionRepository.create(
                characters = selected,
                scenario = scenario.trim(),
                voiceLanguages = voiceLanguages,
            )
            onCreated(session.id)
        }
    }
}
