package com.dreemurr.shinsekai.ui.voices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.voice.TtsProvider
import com.dreemurr.shinsekai.voice.VoiceLanguages

// 语音相关表单的通用小组件，样式对齐设置页。

internal val ProviderOptions = listOf(
    TtsProvider.ALIYUN to R.string.provider_aliyun,
    TtsProvider.MINIMAX to R.string.provider_minimax,
)

// 语音语言选项：自动（空串）在前，其余为规范化代码。
internal val VoiceLanguageOptions: List<Pair<String, Int>> = listOf(
    VoiceLanguages.AUTO to R.string.voice_lang_auto,
    VoiceLanguages.ZH to R.string.voice_lang_zh,
    VoiceLanguages.JA to R.string.voice_lang_ja,
    VoiceLanguages.EN to R.string.voice_lang_en,
)

internal fun voiceLanguageLabelRes(lang: String?): Int = when (VoiceLanguages.normalize(lang)) {
    VoiceLanguages.ZH -> R.string.voice_lang_zh
    VoiceLanguages.JA -> R.string.voice_lang_ja
    VoiceLanguages.EN -> R.string.voice_lang_en
    else -> R.string.voice_lang_auto
}

// 语言选择 + 可选说明；克隆与编辑卡片的语言字段共用。
@Composable
internal fun VoiceLanguageRow(
    selected: String,
    onSelect: (String) -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Column {
        LabeledSegmentedRow(
            label = stringResource(R.string.voice_field_language),
            options = VoiceLanguageOptions,
            selected = selected,
            onSelect = onSelect,
            enabled = enabled,
        )
        if (supportingText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FormPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
internal fun <T> LabeledSegmentedRow(
    label: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(stringResource(text))
                }
            }
        }
    }
}

// 连接信息四项：角色名/服务商/BaseUrl/API Key，添加与克隆表单共用；
// enabled=false 时锁定输入（克隆进行中），服务商切换不在此组件内做字段清理。
@Composable
internal fun ConnectPanelFields(
    name: String,
    onNameChange: (String) -> Unit,
    provider: TtsProvider,
    onProviderChange: (TtsProvider) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    var showKey by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.voice_field_name)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )

    LabeledSegmentedRow(
        label = stringResource(R.string.voice_field_provider),
        options = ProviderOptions,
        selected = provider,
        onSelect = onProviderChange,
        enabled = enabled,
    )

    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrlChange,
        label = { Text(stringResource(R.string.voice_field_base_url)) },
        supportingText = { Text(provider.defaultBaseUrl) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.voice_field_api_key)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation =
            if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showKey = !showKey }) {
                Text(stringResource(if (showKey) R.string.action_hide else R.string.action_show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// 音色选择弹窗：加载中转圈，失败提示原因，成功给出可过滤列表。
@Composable
internal fun VoicePickerDialog(
    state: VoicePickerState,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    VoicePickerState.Idle, VoicePickerState.Loading -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    is VoicePickerState.Error -> Text(
                        stringResource(R.string.voice_picker_error_format, state.message),
                        color = MaterialTheme.colorScheme.error,
                    )

                    is VoicePickerState.Loaded -> {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.voice_picker_filter_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val filtered = state.ids.filter { it.contains(filter, ignoreCase = true) }
                        if (filtered.isEmpty()) {
                            Text(
                                stringResource(R.string.voice_picker_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(filtered) { id ->
                                    TextButton(
                                        onClick = { onPick(id) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            id,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Start,
                                        )
                                    }
                                }
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

// 只读文件框 + 「选择」按钮，展示已选音频文件名。
@Composable
internal fun AudioFileField(
    label: String,
    fileName: String?,
    onSelect: () -> Unit,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = fileName.orEmpty(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        trailingIcon = {
            TextButton(onClick = onSelect) { Text(stringResource(R.string.action_pick_audio)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
