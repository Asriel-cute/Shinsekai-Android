package com.dreemurr.shinsekai.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreemurr.shinsekai.chat.AllProviderSettings
import com.dreemurr.shinsekai.chat.ChatSettings
import com.dreemurr.shinsekai.chat.LlmProvider
import com.dreemurr.shinsekai.chat.OpenAiApiType
import com.dreemurr.shinsekai.chat.ProviderConfig
import com.dreemurr.shinsekai.chat.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// 顶层委托保证 DataStore 实例唯一。
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_settings")

// 各提供商配置独立保存；API Key 只保存 Keystore 加密后的密文。
class SettingsRepository(
    context: Context,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val cipher = ApiKeyCipher()

    // 统一读取流；读取失败回退空配置，避免首启崩溃。
    private val preferences: Flow<Preferences> = appContext.settingsDataStore.data
        .catch { e ->
            e.printStackTrace()
            emit(emptyPreferences())
        }

    /** 全量配置（含全部提供商），供设置页编辑；仅配置相关 key 变化时才重算（含 Keystore 解密）。 */
    val allSettings: StateFlow<AllProviderSettings> = preferences
        .map(::rawConfig)
        .distinctUntilChanged()
        .map { raw -> raw.toSettings(cipher) }
        .stateIn(scope, SharingStarted.Eagerly, AllProviderSettings())

    /** 新手引导是否已完成；null 表示尚未读取，缺失 key 或读取失败均视为未完成。 */
    val onboardingCompleted: StateFlow<Boolean?> = preferences
        .map { p -> p[ONBOARDING_COMPLETED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 主题模式；未设置或读取失败按跟随系统。 */
    val themeMode: StateFlow<ThemeMode> = preferences
        .map { p -> p[THEME_MODE].toEnum(ThemeMode.SYSTEM) }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** BGM 音量（0..1），全局设置；未设置按默认值 0.3。 */
    val bgmVolume: StateFlow<Float> = preferences
        .map { p -> p[BGM_VOLUME] ?: DEFAULT_BGM_VOLUME }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_BGM_VOLUME)

    /** 激活提供商展开后的有效配置，供聊天链路。 */
    val settings: StateFlow<ChatSettings> = allSettings
        .map { s ->
            val config = s.configs.getValue(s.activeProvider)
            ChatSettings(
                provider = s.activeProvider,
                apiType = config.apiType,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                modelName = config.modelName,
                reasoningEffort = s.reasoningEffort,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ChatSettings())

    suspend fun save(settings: AllProviderSettings) {
        appContext.settingsDataStore.edit { p ->
            p[ACTIVE_PROVIDER] = settings.activeProvider.name
            settings.configs.forEach { (provider, config) ->
                p[providerKey(provider, "base_url")] = config.baseUrl
                p[providerKey(provider, "api_key_cipher")] = cipher.encrypt(config.apiKey)
                p[providerKey(provider, "model_name")] = config.modelName
            }
            p[OPENAI_API_TYPE] = settings.configs.getValue(LlmProvider.OPENAI).apiType.name
            p[REASONING_EFFORT] = settings.reasoningEffort
            // 新结构已全量落盘，清理旧版单配置 key。
            p.remove(LEGACY_BASE_URL)
            p.remove(LEGACY_API_KEY_CIPHER)
            p.remove(LEGACY_MODEL_NAME)
        }
    }

    /** 标记新手引导已完成；结束/跳过/重看离开时写入。 */
    suspend fun completeOnboarding() {
        appContext.settingsDataStore.edit { p ->
            p[ONBOARDING_COMPLETED] = true
        }
    }

    /** 主题选择即时生效并落盘。 */
    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.settingsDataStore.edit { p ->
            p[THEME_MODE] = mode.name
        }
    }

    /** BGM 音量即时生效并落盘。 */
    suspend fun setBgmVolume(volume: Float) {
        appContext.settingsDataStore.edit { p ->
            p[BGM_VOLUME] = volume.coerceIn(0f, 1f)
        }
    }

    // 配置相关 key 的原始快照；主题/音量/引导等无关写入不触发解密重算。
    // 旧版单配置 key 首次读取时回退到 OpenAI（Responses）配置，保存后清理。
    private data class RawConfig(
        val active: String?,
        val apiType: String?,
        val effort: String?,
        val openaiBaseUrl: String?,
        val openaiKeyCipher: String?,
        val openaiModelName: String?,
        val anthropicBaseUrl: String?,
        val anthropicKeyCipher: String?,
        val anthropicModelName: String?,
        val googleBaseUrl: String?,
        val googleKeyCipher: String?,
        val googleModelName: String?,
    )

    private fun rawConfig(p: Preferences) = RawConfig(
        active = p[ACTIVE_PROVIDER],
        apiType = p[OPENAI_API_TYPE],
        effort = p[REASONING_EFFORT],
        openaiBaseUrl = p[KEY_OPENAI_BASE_URL] ?: p[LEGACY_BASE_URL],
        openaiKeyCipher = p[KEY_OPENAI_API_KEY_CIPHER] ?: p[LEGACY_API_KEY_CIPHER],
        openaiModelName = p[KEY_OPENAI_MODEL_NAME] ?: p[LEGACY_MODEL_NAME],
        anthropicBaseUrl = p[KEY_ANTHROPIC_BASE_URL],
        anthropicKeyCipher = p[KEY_ANTHROPIC_API_KEY_CIPHER],
        anthropicModelName = p[KEY_ANTHROPIC_MODEL_NAME],
        googleBaseUrl = p[KEY_GOOGLE_BASE_URL],
        googleKeyCipher = p[KEY_GOOGLE_API_KEY_CIPHER],
        googleModelName = p[KEY_GOOGLE_MODEL_NAME],
    )

    private fun RawConfig.toSettings(cipher: ApiKeyCipher) = AllProviderSettings(
        activeProvider = active.toEnum(LlmProvider.OPENAI),
        configs = mapOf(
            LlmProvider.OPENAI to ProviderConfig(
                baseUrl = openaiBaseUrl ?: "",
                apiKey = cipher.decrypt(openaiKeyCipher ?: ""),
                modelName = openaiModelName ?: "",
                apiType = apiType.toEnum(OpenAiApiType.RESPONSES),
            ),
            LlmProvider.ANTHROPIC to ProviderConfig(
                baseUrl = anthropicBaseUrl ?: "",
                apiKey = cipher.decrypt(anthropicKeyCipher ?: ""),
                modelName = anthropicModelName ?: "",
            ),
            LlmProvider.GOOGLE to ProviderConfig(
                baseUrl = googleBaseUrl ?: "",
                apiKey = cipher.decrypt(googleKeyCipher ?: ""),
                modelName = googleModelName ?: "",
            ),
        ),
        reasoningEffort = effort ?: ReasoningEffort.none.name,
    )

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    companion object {
        // 各提供商 key 统一经此派生，读（KEY_* 常量）写（save）共用一份拼接规则。
        private fun providerKey(provider: LlmProvider, suffix: String) =
            stringPreferencesKey("${provider.name.lowercase()}_$suffix")

        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val BGM_VOLUME = floatPreferencesKey("bgm_volume")
        private const val DEFAULT_BGM_VOLUME = 0.3f
        private val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        private val OPENAI_API_TYPE = stringPreferencesKey("openai_api_type")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")

        // 旧版单配置 key，仅作读取回退。
        private val LEGACY_BASE_URL = stringPreferencesKey("base_url")
        private val LEGACY_API_KEY_CIPHER = stringPreferencesKey("api_key_cipher")
        private val LEGACY_MODEL_NAME = stringPreferencesKey("model_name")

        private val KEY_OPENAI_BASE_URL = providerKey(LlmProvider.OPENAI, "base_url")
        private val KEY_OPENAI_API_KEY_CIPHER = providerKey(LlmProvider.OPENAI, "api_key_cipher")
        private val KEY_OPENAI_MODEL_NAME = providerKey(LlmProvider.OPENAI, "model_name")
        private val KEY_ANTHROPIC_BASE_URL = providerKey(LlmProvider.ANTHROPIC, "base_url")
        private val KEY_ANTHROPIC_API_KEY_CIPHER = providerKey(LlmProvider.ANTHROPIC, "api_key_cipher")
        private val KEY_ANTHROPIC_MODEL_NAME = providerKey(LlmProvider.ANTHROPIC, "model_name")
        private val KEY_GOOGLE_BASE_URL = providerKey(LlmProvider.GOOGLE, "base_url")
        private val KEY_GOOGLE_API_KEY_CIPHER = providerKey(LlmProvider.GOOGLE, "api_key_cipher")
        private val KEY_GOOGLE_MODEL_NAME = providerKey(LlmProvider.GOOGLE, "model_name")

        // 允许无鉴权的本地网关，API Key 可为空。
        fun isConfigured(settings: ChatSettings): Boolean =
            settings.baseUrl.isNotBlank() && settings.modelName.isNotBlank()
    }
}
