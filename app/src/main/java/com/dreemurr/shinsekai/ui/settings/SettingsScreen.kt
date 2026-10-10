package com.dreemurr.shinsekai.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.chat.ChatSettings
import com.dreemurr.shinsekai.chat.ReasoningEffort
import com.dreemurr.shinsekai.di.LocalAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        },
    )
    val saved by vm.settings.collectAsStateWithLifecycle()

    // 只初始化一次，持久化回写不覆盖正在编辑的内容。
    var form by remember { mutableStateOf(ChatSettings()) }
    var initialized by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        // 跳过仓库尚未加载时的默认占位值。
        if (!initialized && saved != ChatSettings()) {
            form = saved
            initialized = true
        }
    }

    val savedMsg = stringResource(R.string.settings_saved)
    val failedMsg = stringResource(R.string.settings_save_failed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
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
                // API 服务配置 Panel
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
                            stringResource(R.string.settings_panel_api),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = form.baseUrl,
                            onValueChange = { form = form.copy(baseUrl = it) },
                            label = { Text(stringResource(R.string.settings_base_url_label)) },
                            supportingText = { Text(stringResource(R.string.settings_base_url_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = form.apiKey,
                            onValueChange = { form = form.copy(apiKey = it) },
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
                }

                // 模型与推理配置 Panel
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
                            stringResource(R.string.settings_panel_model),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = form.modelName,
                            onValueChange = { form = form.copy(modelName = it) },
                            label = { Text(stringResource(R.string.settings_model_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Column {
                            Text(
                                stringResource(R.string.settings_reasoning_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                EffortOptions.forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = form.reasoningEffort == value,
                                        onClick = { form = form.copy(reasoningEffort = value) },
                                        shape = SegmentedButtonDefaults.itemShape(index, EffortOptions.size),
                                    ) {
                                        Text(stringResource(label))
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                vm.save(form)
                                snackbarHostState.showSnackbar(savedMsg)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                snackbarHostState.showSnackbar(failedMsg)
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

                Spacer(Modifier.height(24.dp))
            }
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

private val EffortOptions = listOf(
    ReasoningEffort.none.name to R.string.effort_none,
    ReasoningEffort.low.name to R.string.effort_low,
    ReasoningEffort.high.name to R.string.effort_high,
    ReasoningEffort.max.name to R.string.effort_max,
)
