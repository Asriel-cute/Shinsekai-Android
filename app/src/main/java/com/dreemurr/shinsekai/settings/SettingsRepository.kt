package com.dreemurr.shinsekai.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreemurr.shinsekai.chat.ChatSettings
import com.dreemurr.shinsekai.chat.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// 顶层委托保证 DataStore 实例唯一。
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_settings")

// API Key 只保存 Keystore 加密后的密文。
class SettingsRepository(
    context: Context,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val cipher = ApiKeyCipher()

    val settings: StateFlow<ChatSettings> = appContext.settingsDataStore.data
        .catch { e ->
            e.printStackTrace()
            emit(emptyPreferences())
        }
        .map { p ->
            ChatSettings(
                baseUrl = p[BASE_URL].orEmpty(),
                apiKey = cipher.decrypt(p[API_KEY_CIPHER].orEmpty()),
                modelName = p[MODEL_NAME].orEmpty(),
                reasoningEffort = p[REASONING_EFFORT] ?: ReasoningEffort.none.name,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ChatSettings())

    suspend fun save(settings: ChatSettings) {
        appContext.settingsDataStore.edit { p ->
            p[BASE_URL] = settings.baseUrl
            p[API_KEY_CIPHER] = cipher.encrypt(settings.apiKey)
            p[MODEL_NAME] = settings.modelName
            p[REASONING_EFFORT] = settings.reasoningEffort
        }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("base_url")
        private val API_KEY_CIPHER = stringPreferencesKey("api_key_cipher")
        private val MODEL_NAME = stringPreferencesKey("model_name")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")

        // 允许无鉴权的本地网关，API Key 可为空。
        fun isConfigured(settings: ChatSettings): Boolean =
            settings.baseUrl.isNotBlank() && settings.modelName.isNotBlank()
    }
}
