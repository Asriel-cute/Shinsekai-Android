package com.dreemurr.shinsekai.ui.characters

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.common.ColorDot
import com.dreemurr.shinsekai.ui.common.DeleteConfirmationDialog
import com.dreemurr.shinsekai.ui.common.formatImportedAt
import com.dreemurr.shinsekai.ui.common.spriteInfoText
import com.dreemurr.shinsekai.ui.voices.voiceLanguageLabelRes
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.VoiceCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    characterId: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: CharacterDetailViewModel = viewModel(
        key = "character_detail_$characterId",
        factory = viewModelFactory {
            initializer { CharacterDetailViewModel(container, characterId) }
        },
    )
    val character by vm.character.collectAsStateWithLifecycle()
    val boundCard by vm.boundCard.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(colorHex = character?.color)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(character?.name.orEmpty())
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val details = character ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.character_panel_basic_info),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    InfoItem(
                        label = stringResource(R.string.character_source_file),
                        value = details.sourceFileName.ifBlank { stringResource(R.string.character_value_none) },
                    )
                    InfoItem(
                        label = stringResource(R.string.character_imported_at),
                        value = formatImportedAt(details.importedAt),
                    )
                    InfoItem(
                        label = stringResource(R.string.character_stats),
                        value = spriteInfoText(details),
                    )
                }
            }

            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.character_panel_settings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    InfoItem(
                        label = stringResource(R.string.character_setting),
                        value = details.characterSetting.ifBlank { stringResource(R.string.character_value_none) },
                        valueStyle = MaterialTheme.typography.bodyMedium,
                    )
                    InfoItem(
                        label = stringResource(R.string.character_emotion_tags),
                        value = details.emotionTags.ifBlank { stringResource(R.string.character_value_none) },
                        valueStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // 声音卡片：绑定后该角色台词由克隆音合成，本地切图录音让位。
            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.character_panel_voice),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val boundLang = stringResource(voiceLanguageLabelRes(boundCard?.lang))
                    val boundLabel = boundCard?.let { "${it.name} · $boundLang" }
                        ?: stringResource(R.string.character_voice_unbound)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showVoicePicker = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.character_voice_card),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            boundLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        stringResource(R.string.character_voice_language_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.character_sprites),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(details.sprites.size) { index -> SpriteThumbnail(vm.spriteFile(index)) }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { showDelete = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showVoicePicker) {
        VoiceCardPickerDialog(
            cards = cards,
            boundCardId = boundCard?.id,
            onSelect = { cardId ->
                vm.bind(cardId)
                showVoicePicker = false
            },
            onDismiss = { showVoicePicker = false },
        )
    }

    if (showDelete) {
        DeleteConfirmationDialog(
            titleRes = R.string.delete_character_title,
            messageRes = R.string.delete_character_body,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                vm.delete(onBack)
            },
        )
    }
}

@Composable
private fun SpriteThumbnail(file: File?) {
    val modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.character_value_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = valueStyle)
    }
}

// 声音卡片选择弹窗：列表 + 解绑项；空列表给出引导文案。
@Composable
private fun VoiceCardPickerDialog(
    cards: List<VoiceCard>,
    boundCardId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_voice_pick_title)) },
        text = {
            if (cards.isEmpty()) {
                Text(stringResource(R.string.character_voice_no_cards))
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        val providerLabel = stringResource(
                            if (card.providerEnum == TtsProvider.MINIMAX) {
                                R.string.provider_minimax
                            } else {
                                R.string.provider_aliyun
                            },
                        )
                        val langLabel = stringResource(voiceLanguageLabelRes(card.lang))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(card.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(card.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "$providerLabel · $langLabel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (boundCardId != null) {
                        item {
                            Column {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                )
                                Text(
                                    stringResource(R.string.character_voice_pick_none),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelect(null) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
