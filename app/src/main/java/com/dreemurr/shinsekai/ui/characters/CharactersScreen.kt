package com.dreemurr.shinsekai.ui.characters

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.background.Background
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.character.ConflictDecision
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.common.ColorDot
import com.dreemurr.shinsekai.ui.common.DeleteConfirmationDialog
import com.dreemurr.shinsekai.ui.common.EmptyState
import com.dreemurr.shinsekai.ui.common.formatImportedAt
import com.dreemurr.shinsekai.ui.common.spriteInfoText
import java.io.File

private const val RESOURCES_URL = "https://shinsekai.end0rph1n.icu/resources"

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
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val characters by vm.characters.collectAsStateWithLifecycle()
    val backgrounds by vm.backgrounds.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val importUi by vm.importUi.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<Character?>(null) }
    var deleteBackgroundTarget by remember { mutableStateOf<Background?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::import) }

    LaunchedEffect(importUi) {
        // NeedsDecision 留给冲突弹窗，不在这里消费。
        // 导入结果经系统 Toast 提示：带警告或失败用长时，纯成功用短时。
        when (val ui = importUi) {
            is CharactersViewModel.ImportUi.Success -> {
                val text = resources.getString(ui.messageRes, ui.count) +
                    if (ui.warnings.isEmpty()) "" else "：" + ui.warnings.joinToString("；")
                val duration =
                    if (ui.warnings.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, text, duration).show()
            }

            is CharactersViewModel.ImportUi.Failure ->
                Toast.makeText(context, resources.getString(ui.messageRes), Toast.LENGTH_LONG).show()

            else -> return@LaunchedEffect
        }
        vm.consumeImportUi()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.characters_title)) },
                actions = {
                    TextButton(
                        onClick = {
                            runCatching { uriHandler.openUri(RESOURCES_URL) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        R.string.err_open_link,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        },
                    ) { Text(stringResource(R.string.action_download)) }
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

            val hasContent = characters.isNotEmpty() || backgrounds.isNotEmpty()
            when {
                hasContent -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (characters.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.characters_section_characters)) }
                        items(characters, key = { it.id }) { character ->
                            CharacterCard(
                                character = character,
                                onClick = { onOpenDetail(character.id) },
                                onLongClick = { deleteTarget = character },
                            )
                        }
                    }
                    if (backgrounds.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.characters_section_backgrounds)) }
                        items(backgrounds, key = { "bg_${it.id}" }) { background ->
                            BackgroundCard(
                                background = background,
                                coverFile = vm.backgroundCover(background.id),
                                onLongClick = { deleteBackgroundTarget = background },
                            )
                        }
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
            title = {
                Text(
                    stringResource(
                        if (ui.isBackground) R.string.bg_import_conflict_title
                        else R.string.import_conflict_title,
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (ui.isBackground) R.string.bg_import_conflict_body
                            else R.string.import_conflict_body,
                        ),
                    )
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

    deleteBackgroundTarget?.let { target ->
        DeleteConfirmationDialog(
            titleRes = R.string.delete_background_title,
            messageRes = R.string.delete_background_body,
            onDismiss = { deleteBackgroundTarget = null },
            onConfirm = {
                vm.deleteBackground(target.id)
                deleteBackgroundTarget = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(
    character: Character,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
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
            Spacer(Modifier.height(6.dp))
            Text(
                spriteInfoText(character),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatImportedAt(character.importedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

// 背景包卡片：首场景缩略 + 场景/BGM 数量；长按删除。
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackgroundCard(
    background: Background,
    coverFile: File?,
    onLongClick: () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .padding(1.dp),
                )
            } else {
                Box(
                    Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    background.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.bg_info_format,
                        background.sprites.size,
                        background.bgm.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatImportedAt(background.importedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}
