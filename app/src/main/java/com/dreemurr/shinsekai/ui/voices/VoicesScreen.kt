package com.dreemurr.shinsekai.ui.voices

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.common.EmptyState
import com.dreemurr.shinsekai.ui.common.formatImportedAt
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.VoiceCard
import kotlinx.coroutines.launch

// 语音 Tab 根页：角色声音卡片列表，右上角「克隆」「添加」。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(
    onAddCard: () -> Unit,
    onOpenEdit: (String) -> Unit,
    onClone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: VoicesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { VoicesViewModel(container) }
        },
    )
    val cards by vm.cards.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<VoiceCard?>(null) }

    val cloudFailedFormat = stringResource(R.string.voice_delete_cloud_failed_format)
    val deletedMsg = stringResource(R.string.voice_deleted)

    fun confirmDelete(card: VoiceCard, purge: Boolean) {
        deleteTarget = null
        scope.launch {
            val error = vm.delete(card, purge)
            // 云端删除失败不动本地，留待重试；结果经系统 Toast 提示（失败含原因用长时）。
            val message = error?.let { cloudFailedFormat.format(it) } ?: deletedMsg
            val duration = if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            Toast.makeText(context, message, duration).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voices_title)) },
                actions = {
                    TextButton(onClick = onClone, enabled = !deleting) {
                        Text(stringResource(R.string.action_clone))
                    }
                    TextButton(onClick = onAddCard, enabled = !deleting) {
                        Text(stringResource(R.string.action_add))
                    }
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
            if (deleting) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            if (cards.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        VoiceCardItem(
                            card = card,
                            onClick = { onOpenEdit(card.id) },
                            onLongClick = { deleteTarget = card },
                        )
                    }
                }
            } else {
                EmptyState(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.voices_empty_title),
                    body = stringResource(R.string.voices_empty_body),
                )
            }
        }
    }

    // 长按弹出删除方式选择：普通删除仅本地，彻底删除含云端音色。
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text(stringResource(R.string.delete_voice_title)) },
            text = { Text(stringResource(R.string.delete_voice_body_format, target.name)) },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { confirmDelete(target, purge = false) },
                        enabled = !deleting,
                    ) { Text(stringResource(R.string.action_delete_local)) }
                    TextButton(
                        onClick = { confirmDelete(target, purge = true) },
                        enabled = !deleting,
                    ) { Text(stringResource(R.string.action_delete_purge)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    enabled = !deleting,
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceCardItem(
    card: VoiceCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val providerLabel = stringResource(
        if (card.providerEnum == TtsProvider.MINIMAX) {
            R.string.provider_minimax
        } else {
            R.string.provider_aliyun
        },
    )
    val noModel = stringResource(R.string.voice_card_no_model)
    val langLabel = stringResource(voiceLanguageLabelRes(card.lang))

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
            Text(
                card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // 阿里模型嵌在 voice_id 前缀中，不展示模型；仅 MiniMax 展示模型或未设置提示。
            Text(
                if (card.providerEnum == TtsProvider.MINIMAX) {
                    "$providerLabel · ${card.model.ifBlank { noModel }} · $langLabel"
                } else {
                    "$providerLabel · $langLabel"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                card.voiceId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatImportedAt(card.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
