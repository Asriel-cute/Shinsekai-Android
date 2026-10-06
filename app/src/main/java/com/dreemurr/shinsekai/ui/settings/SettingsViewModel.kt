package com.dreemurr.shinsekai.ui.settings

import androidx.lifecycle.ViewModel
import com.dreemurr.shinsekai.chat.ChatSettings
import com.dreemurr.shinsekai.di.AppContainer

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings = container.settingsRepository.settings

    // 写盘完成后，UI 才提示已保存。
    suspend fun save(settings: ChatSettings) {
        container.settingsRepository.save(
            settings.copy(
                baseUrl = settings.baseUrl.trim(),
                apiKey = settings.apiKey.trim(),
                modelName = settings.modelName.trim(),
            ),
        )
    }
}
