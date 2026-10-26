package com.dreemurr.shinsekai.ui.voices

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.di.AppContainer
import com.dreemurr.shinsekai.voice.CloneVoiceResult
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.TtsRepository
import com.dreemurr.shinsekai.voice.VoiceCard
import com.dreemurr.shinsekai.voice.VoiceLanguages
import com.dreemurr.shinsekai.voice.VoicePackageReader
import com.dreemurr.shinsekai.voice.VoicePackageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VoiceCloneViewModel(private val container: AppContainer) : ViewModel() {

    data class Form(
        val name: String = "",
        val provider: TtsProvider = TtsProvider.ALIYUN,
        val baseUrl: String = TtsProvider.ALIYUN.defaultBaseUrl,
        val apiKey: String = "",
        // 阿里为克隆 target_model；MiniMax 为建卡后的合成用模型（如 speech-2.8-hd）。
        val model: String = "",
        // MiniMax 自定义；阿里由克隆结果自动回填。
        val voiceId: String = "",
        // MiniMax 可选参考文字。
        val promptText: String = "",
        // 复刻、合成与新建会话翻译目标共用；空串=自动。
        val lang: String = VoiceLanguages.AUTO,
        val cloneAudio: File? = null,
        val promptAudio: File? = null,
    ) {
        val valid: Boolean
            get() {
                val baseOk = name.isNotBlank() && baseUrl.isNotBlank() &&
                    apiKey.isNotBlank() && cloneAudio != null
                // 两家模型都必填：建卡即完整，克隆音色可直接用于合成。
                return when (provider) {
                    TtsProvider.ALIYUN -> baseOk && model.isNotBlank()
                    TtsProvider.MINIMAX -> baseOk && model.isNotBlank() && minimaxVoiceIdValid(voiceId)
                }
            }
    }

    private val appContext = container.appContext

    // 临时目录：.voice 解包与 SAF 选中的音频；VM 销毁时清理。
    private val workDir = File(appContext.cacheDir, "voice_clone/${UUID.randomUUID()}")

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _cloning = MutableStateFlow(false)
    val cloning: StateFlow<Boolean> = _cloning.asStateFlow()

    // 克隆并建卡成功后置 true，UI 据此返回列表。
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    // 一次性提示（导入结果、克隆失败原因等）。
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    fun update(transform: (Form) -> Form) {
        _form.value = transform(_form.value)
    }

    // 切换服务商时 baseUrl 回填预设、清空服务商专属字段（model/voiceId 语义不同），仍可继续手改。
    fun switchProvider(provider: TtsProvider) {
        _form.value = _form.value.copy(
            provider = provider,
            baseUrl = provider.defaultBaseUrl,
            model = "",
            voiceId = "",
        )
    }

    // 一键导入 .voice：解析 manifest 并把音频解包到临时目录，仅填写非空字段。
    fun importPackage(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val pkgFile = copyToWork(uri, "package-${UUID.randomUUID()}.voice")
                pkgFile?.let { VoicePackageReader.read(it, File(workDir, "pkg")) }
            }
            when (result) {
                null -> _toast.value = "无法读取所选文件"
                is VoicePackageResult.Failure -> _toast.value = "导入失败：${result.message}"

                is VoicePackageResult.Success -> {
                    val pkg = result.pkg
                    val current = _form.value
                    val importedLang = VoiceLanguages.normalize(pkg.lang)
                    // 包内没有 lang 时保留用户已选语言；不支持的语言回退自动。
                    val nextLang = when {
                        pkg.lang.isNullOrBlank() -> current.lang
                        importedLang != null -> importedLang
                        else -> VoiceLanguages.AUTO
                    }
                    _form.value = current.copy(
                        name = pkg.name,
                        promptText = pkg.promptText ?: current.promptText,
                        lang = nextLang,
                        cloneAudio = pkg.cloneAudio ?: current.cloneAudio,
                        promptAudio = pkg.promptAudio ?: current.promptAudio,
                    )
                    _toast.value = when {
                        pkg.cloneAudio == null -> "导入完成，但包内未找到克隆音频"
                        !pkg.lang.isNullOrBlank() && importedLang == null -> appContext.getString(
                            R.string.voice_lang_unsupported_format,
                            pkg.lang,
                        )
                        else -> "导入完成，已填写相关字段"
                    }
                }
            }
        }
    }

    fun pickCloneAudio(uri: Uri) = pickAudio(uri) { file ->
        _form.value = _form.value.copy(cloneAudio = file)
    }

    fun pickPromptAudio(uri: Uri) = pickAudio(uri) { file ->
        _form.value = _form.value.copy(promptAudio = file)
    }

    private fun pickAudio(uri: Uri, setter: (File) -> Unit) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                copyToWork(uri, "pick-${UUID.randomUUID()}-${displayName(uri) ?: "audio"}")
            }
            if (file == null) {
                _toast.value = "无法读取所选音频"
            } else {
                setter(file)
            }
        }
    }

    // SAF 权限是临时的，先复制进私有目录再使用。
    private fun copyToWork(uri: Uri, name: String): File? {
        return try {
            workDir.mkdirs()
            val target = File(workDir, name.takeLast(120))
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    fun submit() {
        val current = _form.value
        if (!current.valid || _cloning.value) return
        _cloning.value = true
        viewModelScope.launch {
            try {
                doSubmit(current)
            } finally {
                _cloning.value = false
            }
        }
    }

    // 克隆并建卡；cloneAudio 判空仅作防御（valid 已校验）。
    private suspend fun doSubmit(current: Form) {
        val cloneAudio = current.cloneAudio ?: return
        val result = container.ttsRepository.cloneVoice(
            TtsRepository.CloneParams(
                provider = current.provider,
                baseUrl = current.baseUrl.trim(),
                apiKey = current.apiKey.trim(),
                model = current.model,
                voiceId = current.voiceId,
                cloneAudio = cloneAudio,
                promptAudio = current.promptAudio,
                promptText = current.promptText.takeIf { it.isNotBlank() },
                language = current.lang,
            ),
        )
        when (result) {
            is CloneVoiceResult.Success -> {
                // 克隆成功直接建卡并返回列表。
                val card = VoiceCard(
                    id = container.voiceCardRepository.newCardId(),
                    name = current.name.trim(),
                    provider = current.provider.name,
                    baseUrl = current.baseUrl.trim(),
                    apiKeyCipher = container.voiceCardRepository.encryptKey(current.apiKey.trim()),
                    // 两家都存模型：阿里为克隆 target_model，MiniMax 为合成用模型。
                    model = current.model.trim(),
                    voiceId = result.voiceId,
                    lang = current.lang,
                    createdAt = System.currentTimeMillis(),
                )
                try {
                    container.voiceCardRepository.upsert(card)
                    _toast.value = "克隆成功"
                    _finished.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    _toast.value = "克隆成功，但卡片保存失败：${e.message ?: "未知错误"}"
                }
            }

            is CloneVoiceResult.Failure -> {
                _toast.value = "克隆失败：${result.message}"
            }
        }
    }

    override fun onCleared() {
        workDir.deleteRecursively()
    }

    companion object {
        // 8~256 位、首字符英文字母、仅字母数字与 -_、末位不可为 -_。
        private fun minimaxVoiceIdValid(raw: String): Boolean {
            val id = raw.trim()
            return id.length in 8..256 &&
                id.first().isLetter() &&
                id.last() != '-' && id.last() != '_' &&
                id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
