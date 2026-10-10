package com.dreemurr.shinsekai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.dreemurr.shinsekai.chat.DialogJsonParser
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.session.SessionEntry
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.LineKind
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.Phase
import com.dreemurr.shinsekai.ui.common.parseHexColor
import kotlinx.coroutines.flow.StateFlow

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
    var showRecord by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
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
            onInputChange = { input = it },
            onSend = {
                vm.send(input)
                input = ""
            },
            onStop = vm::stop,
        )
    }

    if (showRecord) {
        ModalBottomSheet(onDismissRequest = { showRecord = false }) {
            RecordSheetContent(state.history)
        }
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

@Composable
private fun SpriteImage(sprite: ChatViewModel.SpriteView) {
    val context = LocalContext.current
    val request = remember(context, sprite.imageFile) {
        ImageRequest.Builder(context)
            .data(sprite.imageFile)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxHeight(sprite.spriteScale.coerceIn(0.1f, 1f))
            .alpha(if (sprite.dimmed) 0.4f else 1f),
    )
}

// 流结束后隐藏底栏，只通过对话框推进。
@Composable
private fun ChatBottomBar(
    phase: Phase,
    loading: Boolean,
    streaming: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    when (phase) {
        Phase.WaitingInput -> InputBar(
            loading = loading,
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
        )

        // 无底栏可见时补齐导航栏内边距，避免对话框被系统导航栏遮挡。
        Phase.Streaming, Phase.Playing ->
            if (streaming) StopBar(onStop = onStop) else Spacer(Modifier.navigationBarsPadding())

        Phase.Error -> Spacer(Modifier.navigationBarsPadding())
    }
}

// 悬浮式胶囊输入栏，贴合桌面端 2.3.1 pill layout 风格。
@Composable
private fun InputBar(
    loading: Boolean,
    input: String,
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
                        stringResource(R.string.chat_input_hint),
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
private fun RecordSheetContent(history: List<SessionEntry>) {
    val entries = remember(history) { history.filter { it.role != Role.system } }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .padding(horizontal = 20.dp),
    ) {
        if (entries.isEmpty()) {
            item { RecordEmptyHint() }
        }
        items(entries) { entry -> RecordEntryItem(entry) }
        item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
    }
}

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
private fun RecordEntryItem(entry: SessionEntry) {
    when (entry.role) {
        Role.user ->
            Text(
                "${stringResource(R.string.record_user_label)}：${entry.content}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )

        Role.assistant -> {
            val dialogs = remember(entry) { DialogJsonParser.parseAll(entry.content) }
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

        Role.system -> Unit
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}
