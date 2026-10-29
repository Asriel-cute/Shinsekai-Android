package com.dreemurr.shinsekai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.chat.AllProviderSettings
import com.dreemurr.shinsekai.chat.ChatSettings
import com.dreemurr.shinsekai.chat.ConnectionTestResult
import com.dreemurr.shinsekai.chat.LlmProvider
import com.dreemurr.shinsekai.chat.ModelsResult
import com.dreemurr.shinsekai.chat.ProviderConfig
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ModelsUiState {
    data object Idle : ModelsUiState
    data object Loading : ModelsUiState
    data class Loaded(val models: List<String>) : ModelsUiState
    data class Error(val message: String) : ModelsUiState
}

// 连通性测试运行状态（结果经返回值交给 UI 提示）。
data class TestUiState(
    val running: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings = container.settingsRepository.allSettings

    val themeMode = container.settingsRepository.themeMode

    private val _modelsState = MutableStateFlow<ModelsUiState>(ModelsUiState.Idle)
    val modelsState: StateFlow<ModelsUiState> = _modelsState.asStateFlow()

    private val _testState = MutableStateFlow(TestUiState())
    val testState: StateFlow<TestUiState> = _testState.asStateFlow()

    // 写盘完成后，UI 才提示已保存。
    suspend fun save(settings: AllProviderSettings) {
        container.settingsRepository.save(
            settings.copy(
                configs = settings.configs.mapValues { (_, config) ->
                    config.copy(
                        baseUrl = config.baseUrl.trim(),
                        apiKey = config.apiKey.trim(),
                        modelName = config.modelName.trim(),
                    )
                },
            ),
        )
    }

    // 主题选择立即生效；重复选当前主题直接忽略，失败仅记录，不打断界面。
    fun setThemeMode(mode: ThemeMode) {
        if (themeMode.value == mode) return
        viewModelScope.launch {
            runCatching { container.settingsRepository.setThemeMode(mode) }
                .onFailure { it.printStackTrace() }
        }
    }

    // 用当前编辑中的草稿拉取模型列表（无需先保存）。
    fun fetchModels(provider: LlmProvider, config: ProviderConfig) {
        _modelsState.value = ModelsUiState.Loading
        viewModelScope.launch {
            val result = container.chatRepository.fetchModels(
                provider = provider,
                baseUrl = config.baseUrl.trim(),
                apiKey = config.apiKey.trim(),
            )
            _modelsState.value = when (result) {
                is ModelsResult.Success -> ModelsUiState.Loaded(result.models)
                is ModelsResult.Failure -> ModelsUiState.Error(result.message)
            }
        }
    }

    fun dismissModels() {
        _modelsState.value = ModelsUiState.Idle
    }

    // 用当前编辑中的草稿发起一次真实小请求；结果以返回值交给 UI 弹窗提示。
    suspend fun testConnection(provider: LlmProvider, config: ProviderConfig): ConnectionTestResult {
        _testState.value = TestUiState(running = true)
        try {
            val settings = ChatSettings(
                provider = provider,
                apiType = config.apiType,
                baseUrl = config.baseUrl.trim(),
                apiKey = config.apiKey.trim(),
                modelName = config.modelName.trim(),
            )
            return container.chatRepository.testConnection(settings)
        } finally {
            _testState.value = TestUiState()
        }
    }
}
