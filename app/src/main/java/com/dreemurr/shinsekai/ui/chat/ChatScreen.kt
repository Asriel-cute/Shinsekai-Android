package com.dreemurr.shinsekai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.VerticalAlignBottom
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
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.dreemurr.shinsekai.chat.PresentationKind
import com.dreemurr.shinsekai.chat.Role
import com.dreemurr.shinsekai.chat.StatItem
import com.dreemurr.shinsekai.chat.choiceOptions
import com.dreemurr.shinsekai.chat.presentationKind
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.session.SessionEntry
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.LineKind
import com.dreemurr.shinsekai.ui.chat.ChatViewModel.Phase
import com.dreemurr.shinsekai.ui.common.parseCssColor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var showRecord by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var rewindTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // 摄像头/立绘大小互换；离开页面重置为摄像头小窗默认态。
    var cameraSwapped by rememberSaveable { mutableStateOf(false) }

    val cameraDeniedText = stringResource(R.string.chat_camera_permission_denied)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startCamera(lifecycleOwner)
        } else {
            Toast.makeText(context, cameraDeniedText, Toast.LENGTH_LONG).show()
            onBack()
        }
    }
    // 视频会话进入即请求摄像头权限，拒绝直接退出；key 含 lifecycleOwner 供旋转后重绑。
    LaunchedEffect(state.videoChat, state.loading, lifecycleOwner) {
        if (!state.videoChat || state.loading) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.startCamera(lifecycleOwner)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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

    // 背景图全屏铺底；摄像头全屏互换时隐藏场景背景，底色沿用主题背景色。
    val cameraFullscreen = cameraSwapped && state.cameraActive
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!cameraFullscreen) state.backgroundImage?.let { BackgroundImage(it) }

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
                // 互换时摄像头占满舞台、立绘退居小窗；用 cameraFullscreen 判定，摄像头未接管时立绘保持显示。
                if (!cameraFullscreen) state.sprite?.let { SpriteImage(it) }
                if (state.cameraActive) {
                    vm.cameraPreviewView?.let { preview ->
                        AndroidView(
                            factory = { preview },
                            modifier = (
                                if (cameraSwapped) {
                                    // 全屏铺满舞台区，无需圆角。
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                        .size(width = 96.dp, height = 128.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                }
                                ).clickable { cameraSwapped = !cameraSwapped },
                        )
                    }
                    if (cameraSwapped) {
                        SceneMiniWindow(
                            background = state.backgroundImage,
                            sprite = state.sprite,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .clickable { cameraSwapped = false },
                        )
                    }
                }
                // 数值面板贴左上、选项浮层贴底（对话框正上方）：都在舞台内叠加，不挤压立绘。
                if (state.stats.isNotEmpty()) {
                    StatsPanel(
                        stats = state.stats,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    )
                }
                if (state.choices.isNotEmpty() && !state.loading && !state.streaming &&
                    state.phase != Phase.Error
                ) {
                    ChoiceOverlay(
                        options = state.choices,
                        onPick = vm::pickChoice,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp),
                    )
                }
            }

            DialogueBox(
                state = state,
                visibleSpeech = vm.visibleSpeech,
                thinking = vm.thinking,
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
                videoChat = state.videoChat,
                onPreviewVolume = vm::previewBgmVolume,
                onVolumeSettled = vm::setBgmVolume,
                onCooldownSettled = vm::setCameraCooldown,
                onThresholdSettled = vm::setCameraThreshold,
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

// 摄像头全屏时的 VN 场景缩略小窗：背景 Crop 铺底 + 立绘 Fit 叠加，与主舞台同构、共享 Coil 缓存。
@Composable
private fun SceneMiniWindow(
    background: File?,
    sprite: ChatViewModel.SpriteView?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = 96.dp, height = 128.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
    ) {
        background?.let { file ->
            AsyncImage(
                model = rememberImageRequest(file),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        sprite?.imageFile?.let { file ->
            AsyncImage(
                model = rememberImageRequest(file),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (sprite.dimmed) 0.4f else 1f),
            )
        }
    }
}

// 数值面板：舞台左上角竖排，宽度随内容自适应（上限 180dp）；点击整块收起成小胶囊，再点展开。
@Composable
private fun StatsPanel(stats: List<StatItem>, modifier: Modifier = Modifier) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val panelLabel = stringResource(
        if (collapsed) R.string.chat_stats_expand_hint else R.string.chat_stats_collapse_hint,
    )
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = modifier
            .widthIn(max = 180.dp)
            .heightIn(max = 220.dp)
            // 裁切须在 clickable 之前，水波纹才跟着圆角走；Surface 自带的裁切在其外层，裁不到水波纹。
            .clip(shape)
            .semantics { contentDescription = panelLabel }
            .clickable { collapsed = !collapsed },
    ) {
        // 动画挂在内容层：Surface 自身尺寸随之缩放，背景/边框两个方向都有动画，超出部分由 Surface 裁切。
        Box(Modifier.animateContentSize()) {
            if (collapsed) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.chat_stats_badge_format, stats.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                // 宽度取最宽一行的内容宽度：行内不再用 weight 撑满，进度条随之等宽对齐。
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    stats.forEach { stat -> StatRow(stat) }
                }
            }
        }
    }
}

// 不用 LinearProgressIndicator：内部固定 240dp 宽，固有宽度会把面板的 IntrinsicSize.Max 顶到上限。
@Composable
private fun StatRow(stat: StatItem) {
    // max 由 StatPayload 保证为正数；无上限不画条，但留等高占位。
    val ratio = stat.max?.let { (stat.value / it).toFloat().coerceIn(0f, 1f) }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = barColor.copy(alpha = 0.16f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (ratio == null) return@drawBehind
                val barHeight = 4.dp.toPx()
                val top = size.height - barHeight
                val corner = CornerRadius(2.dp.toPx())
                drawRoundRect(trackColor, Offset(0f, top), Size(size.width, barHeight), corner)
                if (ratio > 0f) {
                    drawRoundRect(barColor, Offset(0f, top), Size(size.width * ratio, barHeight), corner)
                }
            }
            .padding(bottom = 7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                statIcon(stat.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stat.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            Text(
                buildString {
                    append(stat.valueText)
                    stat.maxText?.let { append('/').append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// 保留图标名 → Material 图标；未知名回落 gauge。
private fun statIcon(name: String): ImageVector = when (name) {
    "heart" -> Icons.Filled.Favorite
    "shield" -> Icons.Filled.Shield
    "zap" -> Icons.Filled.Bolt
    "star" -> Icons.Filled.Star
    "sparkles" -> Icons.Filled.AutoAwesome
    "target" -> Icons.Filled.TrackChanges
    "coins" -> Icons.Filled.MonetizationOn
    "clock" -> Icons.Filled.Schedule
    else -> Icons.Filled.Speed
}

// 待选选项：对话框正上方的半透明按钮列，点击回填输入框由用户手动发送；超高内部滚动。
@Composable
private fun ChoiceOverlay(
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val optionShape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            Surface(
                shape = optionShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    // 同数值面板：裁切在 clickable 之前，水波纹才跟着圆角走。
                    .clip(optionShape)
                    .clickable { onPick(option) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// 聊天页设置：全局 BGM 音量拖动实时试听；视频会话追加阈值与冷却滑条，均松手落盘。
@Composable
private fun ChatSettingsSheetContent(
    videoChat: Boolean,
    onPreviewVolume: (Float) -> Unit,
    onVolumeSettled: (Float) -> Unit,
    onCooldownSettled: (Int) -> Unit,
    onThresholdSettled: (Float) -> Unit,
) {
    // 音量流只在弹层内收集，落盘变化不触发整屏重组。
    val container = LocalAppContainer.current
    val bgmVolume by container.settingsRepository.bgmVolume.collectAsStateWithLifecycle()
    // 拖动值由 SliderState 持有；DataStore 回写后同步回滑条，等待落盘期间滑块停在所选位置。
    val volumeState = rememberSliderState(bgmVolume)
    LaunchedEffect(bgmVolume) { volumeState.value = bgmVolume }
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
                    (volumeState.value * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            state = volumeState,
            onValueChange = {
                volumeState.value = it
                onPreviewVolume(it)
            },
            onValueChangeFinished = { onVolumeSettled(volumeState.value) },
        )
        if (videoChat) {
            Spacer(Modifier.height(8.dp))
            CameraThresholdRow(onThresholdSettled)
            CameraCooldownRow(onCooldownSettled)
        }
    }
}

// 画面变化阈值：10%–70% 步进 5%，越高越不灵敏。
@Composable
private fun CameraThresholdRow(onSettled: (Float) -> Unit) {
    val container = LocalAppContainer.current
    val threshold by container.settingsRepository.cameraChangeThreshold.collectAsStateWithLifecycle()
    val thresholdState = rememberSliderState(threshold, steps = 11, trackRange = 0.10f..0.70f)
    LaunchedEffect(threshold) { thresholdState.value = threshold }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_settings_camera_threshold),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(
                R.string.chat_settings_camera_threshold_percent_format,
                (thresholdState.value * 100).roundToInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        state = thresholdState,
        onValueChange = { thresholdState.value = it },
        onValueChangeFinished = { onSettled(thresholdState.value) },
    )
}

// 画面变化冷却：10–120s 步进 5s（下标 0..22 对应 10+5*i），最右档 23 为关闭（存储值 0）。
@Composable
private fun CameraCooldownRow(onSettled: (Int) -> Unit) {
    val container = LocalAppContainer.current
    val cooldownSeconds by container.settingsRepository.cameraCooldownSeconds.collectAsStateWithLifecycle()
    val settledIndex = secondsToCooldownIndex(cooldownSeconds)
    val cooldownState = rememberSliderState(
        settledIndex.toFloat(),
        steps = COOLDOWN_OFF_INDEX - 1,
        trackRange = 0f..COOLDOWN_OFF_INDEX.toFloat(),
    )
    LaunchedEffect(settledIndex) { cooldownState.value = settledIndex.toFloat() }
    val currentIndex = cooldownState.value.roundToInt()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_settings_camera_cooldown),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (currentIndex == COOLDOWN_OFF_INDEX) {
                stringResource(R.string.chat_settings_camera_cooldown_off)
            } else {
                stringResource(
                    R.string.chat_settings_camera_cooldown_seconds_format,
                    cooldownIndexToSeconds(currentIndex),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        state = cooldownState,
        onValueChange = { cooldownState.value = it },
        onValueChangeFinished = { onSettled(cooldownIndexToSeconds(cooldownState.value.roundToInt())) },
    )
}

// 冷却档位换算：23=关闭（0 秒），其余 10+5*i。
private const val COOLDOWN_OFF_INDEX = 23

private fun cooldownIndexToSeconds(index: Int): Int =
    if (index >= COOLDOWN_OFF_INDEX) 0 else 10 + index * 5

private fun secondsToCooldownIndex(seconds: Int): Int = when {
    seconds == 0 -> COOLDOWN_OFF_INDEX
    seconds <= 10 -> 0
    seconds >= 120 -> COOLDOWN_OFF_INDEX - 1
    else -> (seconds - 10) / 5
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
        parseCssColor(line.speakerColorHex)
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
    thinking: StateFlow<String>,
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

                    Phase.Streaming -> StreamingContent(thinking)

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

// 生成中的思维链：有思考文本时流式展示并自动滚到最新，没有时保持原转圈提示；高频进度只在此收集。
@Composable
private fun StreamingContent(thinking: StateFlow<String>) {
    val text by thinking.collectAsStateWithLifecycle()
    if (text.isEmpty()) {
        CenteredHint(text = stringResource(R.string.chat_generating), showProgress = true)
        return
    }
    val scrollState = rememberScrollState()
    // 键取 maxValue：它在布局后才更新，按文本键会滚到上一轮的旧底部、末尾差一截。
    LaunchedEffect(scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.chat_thinking),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .heightIn(max = 132.dp)
                .verticalScroll(scrollState),
        )
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
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 20.dp),
            state = listState,
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
        ScrollToBottomButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
        )
    }
}

// 一键滚到最新记录；到底即隐藏，不遮挡末尾条目的回溯按钮。
@Composable
private fun ScrollToBottomButton(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // canScrollForward 由布局结果驱动，仅在跨越边界时重组本组件。
    if (!listState.canScrollForward) return
    FilledIconButton(
        onClick = {
            // 末项是底部留白，滚到它即被钳制在最大偏移处。
            scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
        },
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.VerticalAlignBottom,
            contentDescription = stringResource(R.string.record_scroll_to_bottom),
        )
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

// 记录面板的一行；choice 为选项行，展示时加「选项」标签并用次要色。
private data class RecordRow(val text: String, val choice: Boolean)

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
            // 摄像头帧消息无文字可回填，不给回溯。
            if (entry.image == null) {
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
        }

        entry.role == Role.assistant -> {
            // 展示条目里只有 CHOICE 属于用户可见内容；解析、拆选项与拼接一次做完，重组不重算。
            val rows = remember(entry) {
                DialogJsonParser.parseAll(entry.content).mapNotNull { dialog ->
                    when (presentationKind(dialog.name)) {
                        PresentationKind.Choice -> choiceOptions(dialog.speech)
                            .takeIf { it.isNotEmpty() }
                            ?.let { RecordRow(it.joinToString(" / "), choice = true) }

                        null -> RecordRow("${dialog.name}：${dialog.speech}", choice = false)
                        else -> null
                    }
                }
            }
            val choiceLabel = stringResource(R.string.record_choice_label)
            Column(Modifier.padding(vertical = 8.dp)) {
                if (!entry.complete) {
                    Text(
                        stringResource(R.string.record_incomplete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                rows.forEach { row ->
                    Text(
                        if (row.choice) "$choiceLabel：${row.text}" else row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.choice) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        else -> Unit
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}
