package com.dreemurr.shinsekai.ui.sessionsetup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.background.Background
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.session.ChatType
import com.dreemurr.shinsekai.ui.common.ColorDot
import com.dreemurr.shinsekai.ui.common.EmptyState

// 新建与编辑共用：角色、主控、场景、云语音与数值/选项开关；编辑保存时按当前参数重生成 system 快照。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupScreen(
    sessionId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onGoImport: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: SessionSetupViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SessionSetupViewModel(container, sessionId) }
        },
    )
    val characters by vm.characters.collectAsStateWithLifecycle()
    val backgrounds by vm.backgrounds.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val isEdit = sessionId != null

    // 编辑模式等会话加载完成再进入表单；加载后 key 变化触发一次性回填。
    val sessionKey = existing?.id
    var selectedIds by rememberSaveable(sessionKey, stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() },
        restore = { it.toSet() },
    )) { mutableStateOf(existing?.characterIds?.toSet() ?: emptySet()) }
    var sessionName by rememberSaveable(sessionKey) { mutableStateOf(existing?.title.orEmpty()) }
    var chatType by rememberSaveable(sessionKey) { mutableStateOf(existing?.chatType ?: ChatType.TEXT) }
    var scenario by rememberSaveable(sessionKey) { mutableStateOf(existing?.scenario ?: "") }
    var cloudTts by rememberSaveable(sessionKey) { mutableStateOf(existing?.cloudTtsEnabled ?: true) }
    var stat by rememberSaveable(sessionKey) { mutableStateOf(existing?.statEnabled ?: false) }
    var choice by rememberSaveable(sessionKey) { mutableStateOf(existing?.choiceEnabled ?: false) }
    var playerId by rememberSaveable(sessionKey) { mutableStateOf(existing?.playerCharacterId) }
    var backgroundId by rememberSaveable(sessionKey) { mutableStateOf(existing?.backgroundId) }
    var saving by remember { mutableStateOf(false) }

    val selectedCharacters = characters.filter { it.id in selectedIds }
    val selectedPlayer = selectedCharacters.firstOrNull { it.id == playerId }
    val playerSelectable = selectedIds.size >= 2
    val selectedBackground = backgrounds.firstOrNull { it.id == backgroundId }
    // 编辑时原绑定背景包已不存在（delegated 属性不能 smart cast，用安全调用）。
    val backgroundMissing = existing?.backgroundId != null && selectedBackground == null

    fun toggle(id: String, checked: Boolean) {
        val next = if (checked) selectedIds + id else selectedIds - id
        selectedIds = next
        // 主控必须仍在场且至少还有一位对手戏角色。
        val currentPlayer = playerId
        if (currentPlayer != null && (currentPlayer !in next || next.size < 2)) {
            playerId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEdit) R.string.session_setup_title else R.string.new_session_title,
                        ),
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
            )
        },
        bottomBar = {
            // 底栏随键盘抬升，列表视口同步收缩。
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Button(
                    onClick = {
                        saving = true
                        vm.save(
                            selectedIds = selectedIds,
                            scenario = scenario,
                            cloudTtsEnabled = cloudTts,
                            statEnabled = stat,
                            choiceEnabled = choice,
                            playerCharacterId = selectedPlayer?.id,
                            backgroundId = backgroundId,
                            title = sessionName,
                            chatType = chatType,
                        ) { id ->
                            saving = false
                            onSaved(id)
                        }
                    },
                    enabled = selectedIds.isNotEmpty() && !saving && (!isEdit || existing != null),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(
                        stringResource(
                            if (isEdit) R.string.action_save_continue else R.string.action_start_chat,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
    ) { padding ->
        if (isEdit && existing == null) {
            // 加载中或会话已不存在。
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (!ready) {
                    CircularProgressIndicator()
                } else {
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.session_setup_not_found_title),
                        body = stringResource(R.string.session_setup_not_found_body),
                    )
                }
            }
            return@Scaffold
        }
        if (characters.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.new_session_empty_title),
                    body = stringResource(R.string.new_session_empty_body),
                )
                OutlinedButton(onClick = onGoImport) {
                    Text(stringResource(R.string.action_go_import))
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text(stringResource(R.string.session_setup_name)) },
                    placeholder = { Text(stringResource(R.string.session_setup_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    Text(
                        stringResource(R.string.session_setup_type),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatTypeOptionCard(
                            modifier = Modifier.weight(1f),
                            selected = chatType == ChatType.TEXT,
                            onClick = { chatType = ChatType.TEXT },
                            icon = Icons.AutoMirrored.Filled.Send,
                            title = stringResource(R.string.session_setup_type_text),
                            description = stringResource(R.string.session_setup_type_text_hint),
                        )
                        ChatTypeOptionCard(
                            modifier = Modifier.weight(1f),
                            selected = chatType == ChatType.VIDEO,
                            onClick = { chatType = ChatType.VIDEO },
                            icon = Icons.Filled.Call,
                            title = stringResource(R.string.session_setup_type_video),
                            description = stringResource(R.string.session_setup_type_video_hint),
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.new_session_section_characters),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                CharacterChipPicker(
                    characters = characters,
                    selectedIds = selectedIds,
                    playerCharacterId = selectedPlayer?.id,
                    onToggle = { id, checked -> toggle(id, checked) },
                    onSelectAll = { selectedIds = characters.mapTo(mutableSetOf()) { it.id } },
                    onClear = {
                        selectedIds = emptySet()
                        playerId = null
                    },
                )
            }
            item {
                val missingCount = remember(existing, characters) {
                    val presentIds = characters.mapTo(mutableSetOf()) { it.id }
                    existing?.characterIds.orEmpty().count { it !in presentIds }
                }
                if (missingCount > 0) {
                    Text(
                        stringResource(R.string.session_setup_missing_characters_format, missingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            // 视频会话与主控规则互斥：不展示主控选择，保存时仓库层也会强制清空。
            if (chatType == ChatType.TEXT) {
                item {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.session_setup_player),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.session_setup_player_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        PlayerPicker(
                            selected = selectedPlayer,
                            characters = selectedCharacters,
                            enabled = playerSelectable,
                            onSelect = { playerId = it },
                        )
                        if (!playerSelectable) {
                            Text(
                                stringResource(R.string.session_setup_player_requires_two),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.session_setup_background),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        stringResource(R.string.session_setup_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    BackgroundPicker(
                        selected = selectedBackground,
                        backgrounds = backgrounds,
                        onSelect = { backgroundId = it },
                    )
                    if (backgroundMissing) {
                        Text(
                            stringResource(R.string.session_setup_background_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text(stringResource(R.string.new_session_section_scenario)) },
                    placeholder = { Text(stringResource(R.string.new_session_scenario_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.session_setup_cloud_tts),
                    hint = stringResource(R.string.session_setup_cloud_tts_hint),
                    checked = cloudTts,
                    onCheckedChange = { cloudTts = it },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.session_setup_stat),
                    hint = stringResource(R.string.session_setup_stat_hint),
                    checked = stat,
                    onCheckedChange = { stat = it },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.session_setup_choice),
                    hint = stringResource(R.string.session_setup_choice_hint),
                    checked = choice,
                    onCheckedChange = { choice = it },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// 会话开关行：标题 + 说明 + Switch，云语音/数值状态/对话选项共用同一样式。
@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BackgroundPicker(
    selected: Background?,
    backgrounds: List<Background>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selected?.name ?: stringResource(R.string.session_setup_background_none),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_setup_background_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            backgrounds.forEach { background ->
                DropdownMenuItem(
                    text = {
                        Text(
                            background.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(background.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerPicker(
    selected: Character?,
    characters: List<Character>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selected?.name ?: stringResource(R.string.session_setup_player_none),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_setup_player_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            characters.forEach { character ->
                DropdownMenuItem(
                    text = {
                        Text(
                            character.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(character.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

// 聊天类型单选卡片：图标 + 标题 + 描述 + 单选按钮，整卡可点。
@Composable
private fun ChatTypeOptionCard(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    description: String,
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
        modifier = modifier.clickable { onClick() },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // 点击统一由整卡 clickable 处理，避免按钮与卡片双重涟漪。
                RadioButton(selected = selected, onClick = null)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// 收起态最多显示的行数与出现展开按钮的最低角色数。
private const val CHIP_COLLAPSED_LINES = 3
private const val CHIP_EXPAND_MIN_CHARACTERS = 6

// 角色紧凑选择器：已选置顶；收起态按行数裁剪（避免与 LazyColumn 嵌套滚动崩溃），展开显示全部。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterChipPicker(
    characters: List<Character>,
    selectedIds: Set<String>,
    playerCharacterId: String?,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 稳定排序：已选置顶，组内保持仓库顺序；缓存避免每次重组重排。
    val ordered = remember(characters, selectedIds) {
        characters.sortedByDescending { it.id in selectedIds }
    }

    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = if (expanded) Int.MAX_VALUE else CHIP_COLLAPSED_LINES,
        ) {
            ordered.forEach { character ->
                CharacterChip(
                    character = character,
                    selected = character.id in selectedIds,
                    isPlayer = character.id == playerCharacterId,
                    onToggle = { onToggle(character.id, it) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.session_setup_selected_count_format, selectedIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSelectAll,
                enabled = selectedIds.size < characters.size,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.action_select_all))
            }
            TextButton(
                onClick = onClear,
                enabled = selectedIds.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.action_clear_selection))
            }
            if (characters.size > CHIP_EXPAND_MIN_CHARACTERS) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.action_collapse else R.string.action_expand,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterChip(
    character: Character,
    selected: Boolean,
    isPlayer: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    character.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPlayer) {
                    Text(
                        stringResource(R.string.session_setup_player_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        leadingIcon = { ColorDot(colorHex = character.color, size = 10.dp) },
    )
}
