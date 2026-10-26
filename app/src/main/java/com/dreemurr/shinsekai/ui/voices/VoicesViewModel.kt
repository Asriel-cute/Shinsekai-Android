package com.dreemurr.shinsekai.ui.voices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.voice.DeleteVoiceResult
import com.dreemurr.shinsekai.voice.VoiceCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoicesViewModel(private val container: AppContainer) : ViewModel() {

    val cards = container.voiceCardRepository.cards

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    /**
     * 返回 null 表示删除完成；彻底删除云端失败时返回错误信息并保留本地卡片。
     */
    suspend fun delete(card: VoiceCard, purge: Boolean): String? {
        if (_deleting.value) return null
        _deleting.value = true
        try {
            if (purge) {
                val provider = card.providerEnum
                if (provider != null) {
                    when (
                        val result = container.ttsRepository.deleteVoice(
                            provider = provider,
                            baseUrl = card.baseUrl.trim(),
                            apiKey = container.voiceCardRepository.decryptKey(card),
                            voiceId = card.voiceId,
                        )
                    ) {
                        is DeleteVoiceResult.Failure -> return result.message
                        DeleteVoiceResult.Success -> Unit
                    }
                }
            }
            container.voiceCardRepository.delete(card.id)
            // 卡片已删，清理指向它的绑定关系。
            container.voiceBindings.clearCard(card.id)
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return e.message ?: "删除失败"
        } finally {
            _deleting.value = false
        }
    }
}
