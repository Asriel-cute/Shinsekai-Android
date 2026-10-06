package com.dreemurr.shinsekai.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import kotlinx.coroutines.launch

class NewSessionViewModel(private val container: AppContainer) : ViewModel() {

    val characters = container.characterRepository.characters

    fun create(
        selectedIds: Set<String>,
        scenario: String,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val selected = characters.value.filter { it.id in selectedIds }
            if (selected.isEmpty()) return@launch
            val session = container.sessionRepository.create(
                characters = selected,
                scenario = scenario.trim(),
            )
            onCreated(session.id)
        }
    }
}
