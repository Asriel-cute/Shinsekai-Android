package com.dreemurr.shinsekai.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.voice.VoiceCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class CharacterDetailViewModel(
    private val container: AppContainer,
    private val characterId: String,
) : ViewModel() {

    val character = container.characterRepository.characters
        .map { list -> list.firstOrNull { it.id == characterId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前绑定的声音卡片；未绑定为 null。 */
    val boundCard: StateFlow<VoiceCard?> = combine(
        container.voiceBindings.bindings,
        container.voiceCardRepository.cards,
    ) { bindings, cards ->
        bindings[characterId]?.let { id -> cards.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cards = container.voiceCardRepository.cards

    fun bind(cardId: String?) {
        viewModelScope.launch {
            if (cardId == null) {
                container.voiceBindings.unbind(characterId)
            } else {
                container.voiceBindings.bind(characterId, cardId)
            }
        }
    }

    fun spriteFile(index: Int): File? =
        container.characterRepository.spriteImage(characterId, index)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            container.characterRepository.delete(characterId)
            // 角色已删，绑定关系一并清理。
            container.voiceBindings.unbind(characterId)
            onDone()
        }
    }
}
