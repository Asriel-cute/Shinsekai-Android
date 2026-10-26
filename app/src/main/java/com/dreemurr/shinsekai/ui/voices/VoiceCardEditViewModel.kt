package com.dreemurr.shinsekai.ui.voices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.VoiceCard
import com.dreemurr.shinsekai.voice.VoiceIdsResult
import com.dreemurr.shinsekai.voice.VoiceLanguages
import com.dreemurr.shinsekai.voice.aliyunModelFromVoiceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoicePickerState {
    data object Idle : VoicePickerState
    data object Loading : VoicePickerState
    data class Loaded(val ids: List<String>) : VoicePickerState
    data class Error(val message: String) : VoicePickerState
}

class VoiceCardEditViewModel(
    cardId: String,
    private val container: AppContainer,
) : ViewModel() {

    data class Form(
        val name: String = "",
        val provider: TtsProvider = TtsProvider.ALIYUN,
        val baseUrl: String = TtsProvider.ALIYUN.defaultBaseUrl,
        val apiKey: String = "",
        // 表单仅 MiniMax 输入；阿里模型名嵌在 voice_id 前缀中，无需填写。
        val model: String = "",
        val voiceId: String = "",
        // 复刻、合成与新建会话翻译目标共用；空串=自动。
        val lang: String = VoiceLanguages.AUTO,
    ) {
        val valid: Boolean
            get() = when (provider) {
                TtsProvider.ALIYUN -> name.isNotBlank() && baseUrl.isNotBlank() && voiceId.isNotBlank()
                TtsProvider.MINIMAX -> name.isNotBlank() && baseUrl.isNotBlank() &&
                    model.isNotBlank() && voiceId.isNotBlank()
            }
    }

    private val repo = container.voiceCardRepository

    // 编辑对象；卡片仓库随启动加载，这里同步取值即可。
    private val existing: VoiceCard? =
        if (cardId.isBlank()) null else repo.cards.value.firstOrNull { it.id == cardId }

    private val _form = MutableStateFlow(
        existing?.let {
            Form(
                name = it.name,
                provider = it.providerEnum ?: TtsProvider.ALIYUN,
                baseUrl = it.baseUrl,
                apiKey = repo.decryptKey(it),
                model = it.model,
                voiceId = it.voiceId,
                lang = VoiceLanguages.normalize(it.lang) ?: VoiceLanguages.AUTO,
            )
        } ?: Form(),
    )
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _voicesState = MutableStateFlow<VoicePickerState>(VoicePickerState.Idle)
    val voicesState: StateFlow<VoicePickerState> = _voicesState.asStateFlow()

    fun update(transform: (Form) -> Form) {
        _form.value = transform(_form.value)
    }

    // 切换服务商时 baseUrl 回填预设；model/voiceId 是服务商专属字段，一并清空。
    fun switchProvider(provider: TtsProvider) {
        _form.value = _form.value.copy(
            provider = provider,
            baseUrl = provider.defaultBaseUrl,
            model = "",
            voiceId = "",
        )
    }

    // 用当前编辑中的草稿拉取音色列表（无需先保存）。
    fun fetchVoices() {
        val current = _form.value
        _voicesState.value = VoicePickerState.Loading
        viewModelScope.launch {
            _voicesState.value = when (
                val result = container.ttsRepository.fetchVoiceIds(
                    provider = current.provider,
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                )
            ) {
                is VoiceIdsResult.Success -> VoicePickerState.Loaded(result.ids)
                is VoiceIdsResult.Failure -> VoicePickerState.Error(result.message)
            }
        }
    }

    fun dismissVoices() {
        _voicesState.value = VoicePickerState.Idle
    }

    /** 保存；返回 null 成功，否则为错误信息。编辑保留原创建时间。 */
    suspend fun save(): String? {
        val current = _form.value
        val card = VoiceCard(
            id = existing?.id ?: repo.newCardId(),
            name = current.name.trim(),
            provider = current.provider.name,
            baseUrl = current.baseUrl.trim(),
            apiKeyCipher = repo.encryptKey(current.apiKey.trim()),
            // 阿里模型由 voice_id 前缀决定，编辑音色后同步重算；MiniMax 仍用手填模型。
            model = when (current.provider) {
                TtsProvider.ALIYUN -> aliyunModelFromVoiceId(current.voiceId)
                TtsProvider.MINIMAX -> current.model.trim()
            },
            voiceId = current.voiceId.trim(),
            lang = current.lang,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        return try {
            repo.upsert(card)
            null
        } catch (e: Exception) {
            e.printStackTrace()
            "保存失败，请重试"
        }
    }
}
