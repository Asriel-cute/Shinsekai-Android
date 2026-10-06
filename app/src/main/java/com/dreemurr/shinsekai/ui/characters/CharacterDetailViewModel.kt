package com.dreemurr.shinsekai.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
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

    fun spriteFile(index: Int): File? =
        container.characterRepository.spriteImage(characterId, index)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            container.characterRepository.delete(characterId)
            onDone()
        }
    }
}
