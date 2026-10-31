package com.dreemurr.shinsekai.ui.chat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.chat.ContextCompressor
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.chat.presentationKind
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.session.SessionEntry
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.LineKind
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.Phase
import com.dreemurr.shinsekai.ui.common.parseHexColor
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ChatViewModel = viewModel(
        key = "chat_$sessionId",
        factory = viewModelFactory {
            initializer { ChatViewModel(sessionId, container) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showRecord by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var rewindTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val inputHint = state.playerName?.let {
        stringResource(R.string.chat_input_hint_as_format, it)
    } ?: stringResource(R.string.chat_input_hint)
    val userLabel = state.playerName?.let {
        stringResource(R.string.record_user_label_as_format, it)
    } ?: stringResource(R.string.record_user_label)

    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        vm.consumeToast()
    }

    // 回溯后把被回溯的用户消息回填输入框。
    LaunchedEffect(state.pendingDraft) {
        state.pendingDraft?.let {
            input = it
            vm.consumeDraft()
        }
    }

    // 背景图全屏铺底，顶栏/立绘/对话框/输入栏叠在其上；无背景时保持纯色。
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        state.backgroundImage?.let { BackgroundImage(it) }

        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        state.title.ifBlank { stringResource(R.string.chat_title_fallback) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showChatSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.chat_settings),
                        )
                    }
                    IconButton(onClick = { showRecord = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.chat_record),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                state.sprite?.let { SpriteImage(it) }
            }

            DialogueBox(
                state = state,
                visibleSpeech = vm.visibleSpeech,
                onRetry = vm::retry,
                onBackToInput = vm::dismissError,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.onBoxTap() },
            )

            ChatBottomBar(
                phase = state.phase,
                loading = state.loading,
                streaming = state.streaming,
                input = input,
                inputHint = inputHint,
                onInputChange = { input = it },
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = vm::stop,
            )
        }
    }

    if (showChatSettings) {
        ModalBottomSheet(onDismissRequest = { showChatSettings = false }) {
            ChatSettingsSheetContent(
                onPreviewVolume = vm::previewBgmVolume,
                onVolumeSettled = vm::setBgmVolume,
            )
        }
    }

    if (showRecord) {
        ModalBottomSheet(onDismissRequest = { showRecord = false }) {
            RecordSheetContent(
                history = state.history,
                tokenEstimate = state.tokenEstimate,
                userLabel = userLabel,
                compressEnabled = remember(state.streaming, state.compressing, state.history) {
                    !state.streaming && !state.compressing &&
                        state.history.count { it.role != Role.system } > ContextCompressor.RECENT_ENTRIES
                },
                onCompress = {
                    showRecord = false
                    vm.requestCompress()
                },
                onRewind = { index, content -> rewindTarget = index to content },
            )
        }
    }

    // 回溯确认：截断到该条之前并把原文回填输入框。
    rewindTarget?.let { (index, _) ->
        AlertDialog(
            onDismissRequest = { rewindTarget = null },
            title = { Text(stringResource(R.string.rewind_confirm_title)) },
            text = { Text(stringResource(R.string.rewind_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rewindTo(index)
                        rewindTarget = null
                        showRecord = false
                    },
                ) { Text(stringResource(R.string.record_rewind)) }
            },
            dismissButton = {
                TextButton(onClick = { rewindTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 手动压缩确认：明确说明摘要点之前的记录会被删除。
    if (state.showCompressConfirm) {
        val foldableCount = remember(state.history) {
            (state.history.count { it.role != Role.system } - ContextCompressor.RECENT_ENTRIES)
                .coerceAtLeast(0)
        }
        AlertDialog(
            onDismissRequest = vm::dismissCompressConfirm,
            title = { Text(stringResource(R.string.compress_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.compress_confirm_body_format,
                        foldableCount,
                        ContextCompressor.RECENT_ENTRIES,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::compress) {
                    Text(stringResource(R.string.action_compress))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissCompressConfirm) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.compressing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.compress_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.compress_running_body))
                }
            },
            confirmButton = {
                TextButton(onClick = vm::cancelCompress) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.notConfigured) {
        AlertDialog(
            onDismissRequest = vm::consumeNotConfigured,
            title = { Text(stringResource(R.string.chat_not_configured_title)) },
            text = { Text(stringResource(R.string.chat_not_configured_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.consumeNotConfigured()
                        onOpenSettings()
                    },
                ) { Text(stringResource(R.string.action_go_settings)) }
            },
            dismissButton = {
                TextButton(onClick = vm::consumeNotConfigured) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// 文件图片请求统一构建：背景与立绘共用，crossfade 平滑切换。
@Composable
private fun rememberImageRequest(file: File?): ImageRequest {
    val context = LocalContext.current
    return remember(context, file) {
        ImageRequest.Builder(context)
            .data(file)
            .crossfade(true)
            .build()
    }
}

// 场景背景图全屏铺底，裁切填充。
@Composable
private fun BackgroundImage(file: File) {
    AsyncImage(
        model = rememberImageRequest(file),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SpriteImage(sprite: ChatViewModel.SpriteView) {
    AsyncImage(
        model = rememberImageRequest(sprite.imageFile),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxHeight(sprite.spriteScale.coerceIn(0.1f, 1f))
            .alpha(if (sprite.dimmed) 0.4f else 1f),
    )
}

// 聊天页设置：暂时只有全局 BGM 音量；拖动实时试听，松手才落盘。
@Composable
private fun ChatSettingsSheetContent(
    onPreviewVolume: (Float) -> Unit,
    onVolumeSettled: (Float) -> Unit,
) {
    // 音量流只在弹层内收集，落盘变化不触发整屏重组。
    val container = LocalAppContainer.current
    val bgmVolume by container.settingsRepository.bgmVolume.collectAsStateWithLifecycle()
    // 拖动期间的本地值；松手后回到 DataStore 值。
    var draggingVolume by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Text(
            stringResource(R.string.chat_settings),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.chat_settings_bgm_volume),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    R.string.chat_settings_volume_percent_format,
                    ((draggingVolume ?: bgmVolume) * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draggingVolume ?: bgmVolume,
            onValueChange = {
                draggingVolume = it
                onPreviewVolume(it)
            },
            onValueChangeFinished = {
                draggingVolume?.let(onVolumeSettled)
                draggingVolume = null
            },
        )
    }
}

// 生成中显示停止条；停止后输入栏常驻，发送即丢弃未播放队列。
@Composable
private fun ChatBottomBar(
    phase: Phase,
    loading: Boolean,
    streaming: Boolean,
    input: String,
    inputHint: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    when {
        streaming -> StopBar(onStop = onStop)
        phase == Phase.Error -> Spacer(Modifier.navigationBarsPadding())
        else -> InputBar(
            loading = loading,
            input = input,
            hint = inputHint,
            onInputChange = onInputChange,
            onSend = onSend,
        )
    }
}

// 悬浮式胶囊输入栏，贴合桌面端 2.3.1 pill layout 风格。
@Composable
private fun InputBar(
    loading: Boolean,
    input: String,
    hint: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    if (loading) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = input.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StopBar(onStop: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    stringResource(R.string.action_stop),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// Galgame 风格独立名字铭牌，还原 2.3.1 chat-name 浮层规范。
@Composable
private fun NameBadge(line: ChatViewModel.DisplayLine) {
    val speakerColor = remember(line.speakerColorHex) {
        parseHexColor(line.speakerColorHex)
    }
    val badgeBg = when (line.kind) {
        LineKind.Character -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        LineKind.Narration, LineKind.Stranger -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    }
    val badgeBorder = when (line.kind) {
        LineKind.Character -> speakerColor?.copy(alpha = 0.65f) ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        LineKind.Narration, LineKind.Stranger -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val textColor = when (line.kind) {
        LineKind.Character -> speakerColor ?: MaterialTheme.colorScheme.onPrimaryContainer
        LineKind.Narration, LineKind.Stranger -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = badgeBg,
        border = BorderStroke(1.dp, badgeBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line.name,
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DialogueBox(
    state: ChatViewModel.UiState,
    visibleSpeech: StateFlow<String>,
    onRetry: () -> Unit,
    onBackToInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = state.line
    val showNameBadge = state.phase != Phase.Error && state.phase != Phase.Streaming && line != null

    // 非 weight 子项用 heightIn，避免挤掉立绘和输入栏；状态行贴底。
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (line != null && showNameBadge) {
            NameBadge(line)
        }

        Surface(
            shape = if (showNameBadge) {
                RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            } else {
                RoundedCornerShape(16.dp)
            },
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    // 内容区高度不含内边距。
                    .heightIn(min = 130.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                when (state.phase) {
                    Phase.Error -> ErrorContent(
                        error = state.error.orEmpty(),
                        onRetry = onRetry,
                        onBackToInput = onBackToInput,
                    )

                    Phase.Streaming -> CenteredHint(
                        text = stringResource(R.string.chat_generating),
                        showProgress = true,
                    )

                    else -> LineContent(state, visibleSpeech)
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(error: String, onRetry: () -> Unit, onBackToInput: () -> Unit) {
    Text(
        error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
        OutlinedButton(onClick = onBackToInput) {
            Text(stringResource(R.string.action_back_to_input))
        }
    }
}

@Composable
private fun LineContent(state: ChatViewModel.UiState, visibleSpeech: StateFlow<String>) {
    val line = state.line
    if (line == null) {
        CenteredHint(text = stringResource(R.string.chat_first_input_hint))
        return
    }

    Column {
        SpeechText(full = line.speech, visibleSpeech = visibleSpeech)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        when {
            state.synthesizing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.chat_tts_synthesizing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            !state.typing && state.queueSize == 0 && state.streaming ->
                Text(
                    stringResource(R.string.chat_generating),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )

            !state.typing ->
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun CenteredHint(text: String, showProgress: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// 完整台词隐形占位防抖，超长内容内部滚动；高频进度只在此收集。
@Composable
private fun SpeechText(full: String, visibleSpeech: StateFlow<String>) {
    val visible by visibleSpeech.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    LaunchedEffect(visible) {
        if (scrollState.value < scrollState.maxValue) scrollState.scrollTo(scrollState.maxValue)
    }
    val style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
    Box {
        Text(
            full,
            color = Color.Transparent,
            style = style,
            modifier = Modifier
                .heightIn(max = 132.dp)
                .clearAndSetSemantics {},
        )
        Text(
            visible.ifBlank { " " },
            color = MaterialTheme.colorScheme.onSurface,
            style = style,
            modifier = Modifier
                .heightIn(max = 132.dp)
                .verticalScroll(scrollState),
        )
    }
}

@Composable
private fun RecordSheetContent(
    history: List<SessionEntry>,
    tokenEstimate: Int,
    userLabel: String,
    compressEnabled: Boolean,
    onCompress: () -> Unit,
    onRewind: (Int, String) -> Unit,
) {
    // 保留原始下标，回溯按 history 下标截断。
    val entries = remember(history) {
        history.withIndex().filter { it.value.role != Role.system }
    }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .padding(horizontal = 20.dp),
    ) {
        item {
            RecordHeader(
                tokenEstimate = tokenEstimate,
                compressEnabled = compressEnabled,
                onCompress = onCompress,
            )
        }
        if (entries.isEmpty()) {
            item { RecordEmptyHint() }
        }
        items(entries, key = { it.index }) { (index, entry) ->
            RecordEntryItem(
                entry = entry,
                userLabel = userLabel,
                onRewind = { onRewind(index, entry.content) },
            )
        }
        item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
    }
}

// 上下文用量与手动压缩入口；估算值仅供参考。
@Composable
private fun RecordHeader(
    tokenEstimate: Int,
    compressEnabled: Boolean,
    onCompress: () -> Unit,
) {
    val overLimit = tokenEstimate > ContextCompressor.MAX_CONTEXT_TOKENS
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                R.string.chat_context_tokens_format,
                formatTokens(tokenEstimate),
                formatTokens(ContextCompressor.MAX_CONTEXT_TOKENS),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (overLimit) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onCompress,
            enabled = compressEnabled,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                stringResource(R.string.action_compress),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// 82.4k / 128k 形式的 token 展示。
private fun formatTokens(tokens: Int): String =
    if (tokens >= 1000) String.format(Locale.US, "%.1fk", tokens / 1000.0) else tokens.toString()

@Composable
private fun RecordEmptyHint() {
    Text(
        stringResource(R.string.record_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun RecordEntryItem(entry: SessionEntry, userLabel: String, onRewind: () -> Unit) {
    when {
        // 压缩产生的摘要：单独展示，不给回溯。
        entry.summary -> Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.chat_summary_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                entry.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        entry.role == Role.user -> Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "$userLabel：${entry.content}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRewind,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(
                    stringResource(R.string.record_rewind),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        entry.role == Role.assistant -> {
            // SCENE/bgm 展示条目不属于台词，记录面板不展示。
            val dialogs = remember(entry) {
                DialogJsonParser.parseAll(entry.content).filterNot { presentationKind(it.name) != null }
            }
            Column(Modifier.padding(vertical = 8.dp)) {
                if (!entry.complete) {
                    Text(
                        stringResource(R.string.record_incomplete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                dialogs.forEach { dialog ->
                    Text(
                        "${dialog.name}：${dialog.speech}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        else -> Unit
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}
