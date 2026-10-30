package com.dreemurr.shinsekai.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.BuildConfig
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.chat.AllProviderSettings
import com.dreemurr.shinsekai.chat.ConnectionTestResult
import com.dreemurr.shinsekai.chat.LlmProvider
import com.dreemurr.shinsekai.chat.OpenAiApiType
import com.dreemurr.shinsekai.chat.ProviderConfig
import com.dreemurr.shinsekai.chat.ReasoningEffort
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.settings.ThemeMode
import kotlinx.coroutines.launch
import java.util.Locale

// Releases 页面；「检查更新」不请求网络，直接跳转由浏览器展示最新版本。
private const val RELEASES_URL = "https://github.com/Asriel-cute/Shinsekai-Android/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onShowOnboarding: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        },
    )
    val saved by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()

    // 只初始化一次；已就绪时首帧直接使用仓库数据，避免空表单闪动与浮动标签动画掉帧。
    var form by remember { mutableStateOf(saved) }
    var initialized by remember { mutableStateOf(saved != AllProviderSettings()) }
    var showKey by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        // 跳过仓库尚未加载时的默认占位值。
        if (!initialized && saved != AllProviderSettings()) {
            form = saved
            initialized = true
        }
    }

    val activeProvider = form.activeProvider
    val config = form.configs.getValue(activeProvider)

    fun updateConfig(provider: LlmProvider, transform: (ProviderConfig) -> ProviderConfig) {
        form = form.withConfig(provider, transform)
    }

    // Toast 只用 context 本体；文案仍按约定经 stringResource 获取。
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val savedMsg = stringResource(R.string.settings_saved)
    val failedMsg = stringResource(R.string.settings_save_failed)
    val testSuccessFormat = stringResource(R.string.settings_test_success_format)
    val openLinkFailedMsg = stringResource(R.string.err_open_link)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // API 服务、模型与推理和测试/保存按钮同框展示，与外观、关于两张卡片保持一致。
            SettingsCard {
                SettingsSection(stringResource(R.string.settings_panel_api))
                // 提供商选择：切换表单内容与激活项，各提供商草稿独立。
                LabeledSegmentedRow(
                    label = stringResource(R.string.settings_provider_label),
                    options = ProviderOptions,
                    selected = activeProvider,
                    onSelect = { form = form.copy(activeProvider = it) },
                )

                // OpenAI 两种接口形态，共用同一套连接配置。
                if (activeProvider == LlmProvider.OPENAI) {
                    LabeledSegmentedRow(
                        label = stringResource(R.string.settings_api_type_label),
                        options = ApiTypeOptions,
                        selected = config.apiType,
                        onSelect = { updateConfig(LlmProvider.OPENAI) { c -> c.copy(apiType = it) } },
                    )
                }

                // 提供商切换时输入框成组换值，key 重建让 label 首帧到位，避免浮动标签动画。
                key(activeProvider) {
                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { value ->
                            updateConfig(activeProvider) { it.copy(baseUrl = value) }
                        },
                        label = { Text(stringResource(R.string.settings_base_url_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    when (activeProvider) {
                                        LlmProvider.OPENAI -> R.string.settings_base_url_hint_openai
                                        LlmProvider.ANTHROPIC -> R.string.settings_base_url_hint_anthropic
                                        LlmProvider.GOOGLE -> R.string.settings_base_url_hint_google
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = config.apiKey,
                        onValueChange = { value ->
                            updateConfig(activeProvider) { it.copy(apiKey = value) }
                        },
                        label = { Text(stringResource(R.string.settings_api_key_label)) },
                        supportingText = { Text(stringResource(R.string.settings_api_key_hint)) },
                        singleLine = true,
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

                SettingsDivider()

                SettingsSection(stringResource(R.string.settings_panel_model))
                // 同上，模型名随提供商切换整体换值，key 重建避免 label 浮动动画。
                key(activeProvider) {
                    OutlinedTextField(
                        value = config.modelName,
                        onValueChange = { value ->
                            updateConfig(activeProvider) { it.copy(modelName = value) }
                        },
                        label = { Text(stringResource(R.string.settings_model_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedButton(
                    onClick = {
                        showModelPicker = true
                        vm.fetchModels(activeProvider, config)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(stringResource(R.string.action_fetch_models))
                }

                LabeledSegmentedRow(
                    label = stringResource(R.string.settings_reasoning_label),
                    options = EffortOptions,
                    selected = form.reasoningEffort,
                    onSelect = { form = form.copy(reasoningEffort = it) },
                )

                SettingsDivider()

                // 连通性测试：对当前编辑草稿发起一次真实小请求，结果经系统 Toast 提示。
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = vm.testConnection(activeProvider, config)
                            when (result) {
                                is ConnectionTestResult.Success -> {
                                    val elapsed = String.format(Locale.ROOT, "%.1f", result.elapsedMs / 1000.0)
                                    Toast.makeText(
                                        context,
                                        String.format(Locale.ROOT, testSuccessFormat, elapsed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }

                                is ConnectionTestResult.Failure ->
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !testState.running &&
                        config.baseUrl.isNotBlank() && config.modelName.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    if (testState.running) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (testState.running) R.string.settings_test_running
                            else R.string.action_test_connection,
                        ),
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                vm.save(form)
                                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
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
                        stringResource(R.string.action_save),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 外观：主题下拉选择，即时生效，不走「保存」按钮。
            SettingsPanel(stringResource(R.string.settings_panel_appearance)) {
                LabeledDropdown(
                    label = stringResource(R.string.settings_theme_label),
                    options = ThemeModeOptions,
                    selected = themeMode,
                    onSelect = vm::setThemeMode,
                )
            }

            // 关于：当前版本、重看新手引导与检查更新入口。
            SettingsPanel(stringResource(R.string.settings_panel_about)) {
                Text(
                    stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = onShowOnboarding,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.settings_show_onboarding))
                }

                OutlinedButton(
                    onClick = {
                        runCatching { uriHandler.openUri(RELEASES_URL) }
                            .onFailure {
                                Toast.makeText(context, openLinkFailedMsg, Toast.LENGTH_SHORT).show()
                            }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.action_check_update))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            state = modelsState,
            onDismiss = {
                showModelPicker = false
                vm.dismissModels()
            },
            onPick = { name ->
                updateConfig(activeProvider) { it.copy(modelName = name) }
                showModelPicker = false
                vm.dismissModels()
            },
        )
    }
}

// 模型选择弹窗：加载中转圈，失败提示原因，成功给出可过滤列表。
@Composable
private fun ModelPickerDialog(
    state: ModelsUiState,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_model_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    ModelsUiState.Idle, ModelsUiState.Loading -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    is ModelsUiState.Error -> Text(
                        stringResource(R.string.settings_model_picker_error_format, state.message),
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ModelsUiState.Loaded -> {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.settings_model_picker_filter_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val filtered = state.models.filter { it.contains(filter, ignoreCase = true) }
                        if (filtered.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_model_picker_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(filtered) { name ->
                                    TextButton(
                                        onClick = { onPick(name) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            name,
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

// 单节面板：标题 + 内容；外观、关于等独立设置项继续使用。
@Composable
private fun SettingsPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard {
        SettingsSection(title)
        content()
    }
}

// 卡片外壳，允许多个分节合并在同一框内。
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
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
            content()
        }
    }
}

// 框内分节标题，样式与单节面板标题一致。
@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

// 框内分节分隔线。
@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}

@Composable
private fun <T> LabeledSegmentedRow(
    label: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
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
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(stringResource(text))
                }
            }
        }
    }
}

// 标签 + 下拉选择，与 LabeledSegmentedRow 同款布局；选项为「值 → 文案资源」。
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 值缺失时回退首项，避免找不到选项直接崩溃。
                val selectedRes = options.firstOrNull { it.first == selected }?.second
                    ?: options.first().second
                Text(
                    stringResource(selectedRes),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (value, textRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(textRes)) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// 更新单个提供商的草稿配置，其余提供商不受影响。
private fun AllProviderSettings.withConfig(
    provider: LlmProvider,
    transform: (ProviderConfig) -> ProviderConfig,
): AllProviderSettings = copy(
    configs = configs + (provider to transform(configs.getValue(provider))),
)

private val ProviderOptions = listOf(
    LlmProvider.OPENAI to R.string.provider_openai,
    LlmProvider.ANTHROPIC to R.string.provider_anthropic,
    LlmProvider.GOOGLE to R.string.provider_google,
)

private val ApiTypeOptions = listOf(
    OpenAiApiType.RESPONSES to R.string.api_type_responses,
    OpenAiApiType.CHAT_COMPLETIONS to R.string.api_type_chat_completions,
)

private val EffortOptions = listOf(
    ReasoningEffort.none.name to R.string.effort_none,
    ReasoningEffort.low.name to R.string.effort_low,
    ReasoningEffort.high.name to R.string.effort_high,
    ReasoningEffort.max.name to R.string.effort_max,
)

private val ThemeModeOptions = listOf(
    ThemeMode.SYSTEM to R.string.theme_system,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.DARK to R.string.theme_dark,
)
