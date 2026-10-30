package com.dreemurr.shinsekai.ui.characters

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.background.BackgroundImportOutcome
import com.dreemurr.shinsekai.character.Conflict
import com.dreemurr.shinsekai.character.ConflictDecision
import com.dreemurr.shinsekai.character.ImportError
import com.dreemurr.shinsekai.character.ImportOutcome
import com.dreemurr.shinsekai.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipInputStream

// 单一「导入」入口同时接收 .char 与 .bg：先按包内容嗅探类型再分发。
class CharactersViewModel(private val container: AppContainer) : ViewModel() {

    val characters = container.characterRepository.characters
    val backgrounds = container.backgroundRepository.backgrounds

    val importing = MutableStateFlow(false)
    private val _importUi = MutableStateFlow<ImportUi?>(null)
    val importUi = _importUi.asStateFlow()

    sealed interface ImportUi {
        data class Success(val messageRes: Int, val count: Int, val warnings: List<String>) : ImportUi
        data class NeedsDecision(
            val token: String,
            val conflicts: List<Conflict>,
            val isBackground: Boolean,
        ) : ImportUi

        data class Failure(val messageRes: Int) : ImportUi
    }

    // 待决议 token -> 是否背景包，resolve 时找回分发目标。
    private val backgroundTokens = mutableSetOf<String>()

    fun import(uri: Uri) {
        val resolver = container.appContext.contentResolver
        launchImport {
            val name = queryDisplayName(resolver, uri)
            val open = {
                resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            }
            val isBackground = withContext(Dispatchers.IO) { sniffBackground(open, name) }
            if (isBackground) {
                container.backgroundRepository.import(open, name)
            } else {
                container.characterRepository.import(open, name)
            }
        }
    }

    fun resolve(token: String, decision: ConflictDecision) =
        launchImport {
            if (token in backgroundTokens) {
                container.backgroundRepository.resolve(token, decision)
            } else {
                container.characterRepository.resolve(token, decision)
            }
        }

    // finally 保证导入进度复位。
    private fun launchImport(block: suspend () -> Any) {
        viewModelScope.launch {
            importing.value = true
            _importUi.value = null
            try {
                _importUi.value = toUi(block())
            } finally {
                importing.value = false
            }
        }
    }

    fun cancelImport(token: String) {
        viewModelScope.launch {
            if (backgroundTokens.remove(token)) {
                container.backgroundRepository.cancel(token)
            } else {
                container.characterRepository.cancel(token)
            }
            _importUi.value = null
        }
    }

    fun consumeImportUi() {
        _importUi.value = null
    }

    fun delete(id: String) {
        viewModelScope.launch { container.characterRepository.delete(id) }
    }

    fun deleteBackground(id: String) {
        viewModelScope.launch { container.backgroundRepository.delete(id) }
    }

    // 背景包首图缩略（无场景时返回 null）。
    fun backgroundCover(id: String): File? =
        container.backgroundRepository.sceneImage(id, 0)

    private fun toUi(outcome: Any): ImportUi = when (outcome) {
        is ImportOutcome.Imported -> ImportUi.Success(R.string.import_success_format, outcome.characters.size, outcome.warnings)
        is ImportOutcome.NeedsDecision -> ImportUi.NeedsDecision(outcome.token, outcome.conflicts, isBackground = false)

        is ImportOutcome.Failed -> ImportUi.Failure(mapError(outcome.error, isBackground = false))

        is BackgroundImportOutcome.Imported ->
            ImportUi.Success(R.string.bg_import_success_format, outcome.backgrounds.size, outcome.warnings)

        is BackgroundImportOutcome.NeedsDecision -> {
            backgroundTokens += outcome.token
            ImportUi.NeedsDecision(outcome.token, outcome.conflicts, isBackground = true)
        }

        is BackgroundImportOutcome.Failed -> ImportUi.Failure(mapError(outcome.error, isBackground = true))

        else -> throw IllegalArgumentException("未知导入结果: $outcome")
    }

    companion object {
        private fun mapError(error: ImportError, isBackground: Boolean): Int = when (error) {
            ImportError.NotAZip ->
                if (isBackground) R.string.err_bg_not_a_zip else R.string.err_not_a_zip
            ImportError.MissingYaml ->
                if (isBackground) R.string.err_bg_missing_yaml else R.string.err_missing_yaml
            ImportError.MalformedYaml ->
                if (isBackground) R.string.err_bg_malformed_yaml else R.string.err_malformed_yaml
            ImportError.EmptyPackage ->
                if (isBackground) R.string.err_bg_empty_package else R.string.err_empty_package
            ImportError.BrokenArchive ->
                if (isBackground) R.string.err_bg_broken_archive else R.string.err_broken_archive
            ImportError.SessionNotFound -> R.string.err_session_not_found
            ImportError.Io -> R.string.err_io
        }

        // 扩展名优先；无法判断时流式扫条目名，根目录出现 background.yaml 即判为背景包。
        // 导出工具均把 yaml 写在首位，通常首个条目即命中。
        private fun sniffBackground(open: () -> InputStream, name: String?): Boolean {
            if (name != null && name.endsWith(".char", ignoreCase = true)) return false
            if (name != null && name.endsWith(".bg", ignoreCase = true)) return true
            return try {
                ZipInputStream(open().buffered(64 * 1024)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val path = entry.name.replace('\\', '/').trim().removePrefix("./")
                        when {
                            path.equals("background.yaml", ignoreCase = true) -> return true
                            path.equals("character.yaml", ignoreCase = true) -> return false
                        }
                        zip.closeEntry()
                    }
                }
                false // 无根 yaml 时走角色导入，由其报出具体错误。
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        private suspend fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
            withContext(Dispatchers.IO) {
                try {
                    resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
    }
}
