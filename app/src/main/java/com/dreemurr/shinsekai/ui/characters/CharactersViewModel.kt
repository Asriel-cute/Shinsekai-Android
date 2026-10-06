package com.dreemurr.shinsekai.ui.characters

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreemurr.shinsekai.R
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
import java.io.FileNotFoundException

class CharactersViewModel(private val container: AppContainer) : ViewModel() {

    val characters = container.characterRepository.characters

    val importing = MutableStateFlow(false)
    private val _importUi = MutableStateFlow<ImportUi?>(null)
    val importUi = _importUi.asStateFlow()

    sealed interface ImportUi {
        data class Success(val count: Int, val warnings: List<String>) : ImportUi
        data class NeedsDecision(val token: String, val conflicts: List<Conflict>) : ImportUi
        data class Failure(val messageRes: Int) : ImportUi
    }

    fun import(uri: Uri) {
        val resolver = container.appContext.contentResolver
        launchImport {
            container.characterRepository.import(
                openStream = {
                    resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                },
                sourceFileName = queryDisplayName(resolver, uri),
            )
        }
    }

    fun resolve(token: String, decision: ConflictDecision) =
        launchImport { container.characterRepository.resolve(token, decision) }

    // finally 保证导入进度复位。
    private fun launchImport(block: suspend () -> ImportOutcome) {
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
            container.characterRepository.cancel(token)
            _importUi.value = null
        }
    }

    fun consumeImportUi() {
        _importUi.value = null
    }

    fun delete(id: String) {
        viewModelScope.launch { container.characterRepository.delete(id) }
    }

    private fun toUi(outcome: ImportOutcome): ImportUi = when (outcome) {
        is ImportOutcome.Imported -> ImportUi.Success(outcome.characters.size, outcome.warnings)
        is ImportOutcome.NeedsDecision -> ImportUi.NeedsDecision(outcome.token, outcome.conflicts)
        is ImportOutcome.Failed -> ImportUi.Failure(mapError(outcome.error))
    }

    companion object {
        private fun mapError(error: ImportError): Int = when (error) {
            ImportError.NotAZip -> R.string.err_not_a_zip
            ImportError.MissingYaml -> R.string.err_missing_yaml
            ImportError.MalformedYaml -> R.string.err_malformed_yaml
            ImportError.EmptyPackage -> R.string.err_empty_package
            ImportError.BrokenArchive -> R.string.err_broken_archive
            ImportError.SessionNotFound -> R.string.err_session_not_found
            ImportError.Io -> R.string.err_io
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
