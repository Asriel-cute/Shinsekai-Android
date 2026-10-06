package com.dreemurr.shinsekai.ui.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.character.ConflictDecision
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.common.ColorDot
import com.dreemurr.shinsekai.ui.common.DeleteConfirmationDialog
import com.dreemurr.shinsekai.ui.common.EmptyState
import com.dreemurr.shinsekai.ui.common.formatImportedAt
import com.dreemurr.shinsekai.ui.common.spriteInfoText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    onOpenDetail: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: CharactersViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CharactersViewModel(container) }
        },
    )
    val resources = LocalResources.current
    val characters by vm.characters.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val importUi by vm.importUi.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::import) }

    LaunchedEffect(importUi) {
        // NeedsDecision 留给冲突弹窗，不在这里消费。
        val text = when (val ui = importUi) {
            is CharactersViewModel.ImportUi.Success ->
                resources.getString(R.string.import_success_format, ui.count) +
                    if (ui.warnings.isEmpty()) "" else "：" + ui.warnings.joinToString("；")

            is CharactersViewModel.ImportUi.Failure -> resources.getString(ui.messageRes)
            else -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(text)
        vm.consumeImportUi()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.characters_title)) },
                actions = {
                    TextButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        enabled = !importing,
                    ) { Text(stringResource(R.string.action_import)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (importing) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            when {
                characters.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(characters, key = { it.id }) { character ->
                        CharacterCard(
                            character = character,
                            onClick = { onOpenDetail(character.id) },
                            onLongClick = { deleteTarget = character },
                        )
                    }
                }

                importing -> CircularProgressIndicator()

                else -> EmptyState(
                    icon = Icons.Filled.Face,
                    title = stringResource(R.string.characters_empty_title),
                    body = stringResource(R.string.characters_empty_body),
                )
            }
        }
    }

    (importUi as? CharactersViewModel.ImportUi.NeedsDecision)?.let { ui ->
        AlertDialog(
            onDismissRequest = { vm.cancelImport(ui.token) },
            title = { Text(stringResource(R.string.import_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.import_conflict_body))
                    Spacer(Modifier.height(8.dp))
                    ui.conflicts.forEach { conflict ->
                        Text(
                            stringResource(
                                R.string.import_conflict_line_format,
                                conflict.incomingName,
                                conflict.existingName,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.resolve(ui.token, ConflictDecision.Overwrite) }) {
                    Text(stringResource(R.string.action_overwrite))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.resolve(ui.token, ConflictDecision.KeepBoth) }) {
                        Text(stringResource(R.string.action_keep_both))
                    }
                    TextButton(onClick = { vm.cancelImport(ui.token) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteConfirmationDialog(
            titleRes = R.string.delete_character_title,
            messageRes = R.string.delete_character_body,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                vm.delete(target.id)
                deleteTarget = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(
    character: Character,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(colorHex = character.color)
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                spriteInfoText(character),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatImportedAt(character.importedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
