package com.dreemurr.shinsekai.ui.voices

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.voice.TtsProvider

// 克隆角色声音：字段按服务商显隐；成功后直接建卡返回列表。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCloneScreen(
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: VoiceCloneViewModel = viewModel(
        factory = viewModelFactory {
            initializer { VoiceCloneViewModel(container) }
        },
    )
    val form by vm.form.collectAsStateWithLifecycle()
    val cloning by vm.cloning.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 一次性提示与完成返回。
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeToast()
        }
    }
    LaunchedEffect(finished) {
        if (finished) onBack()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importPackage) }

    val cloneAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::pickCloneAudio) }

    val promptAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::pickPromptAudio) }

    fun launchAudio(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(arrayOf("audio/*", "application/octet-stream", "*/*"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_clone_title)) },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormPanel(stringResource(R.string.voice_panel_import)) {
                OutlinedButton(
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
                    enabled = !cloning,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(stringResource(R.string.action_import_voice))
                }
            }

            FormPanel(stringResource(R.string.voice_panel_connect)) {
                ConnectPanelFields(
                    name = form.name,
                    onNameChange = { value -> vm.update { it.copy(name = value) } },
                    provider = form.provider,
                    onProviderChange = vm::switchProvider,
                    baseUrl = form.baseUrl,
                    onBaseUrlChange = { value -> vm.update { it.copy(baseUrl = value) } },
                    apiKey = form.apiKey,
                    onApiKeyChange = { value -> vm.update { it.copy(apiKey = value) } },
                    enabled = !cloning,
                )

                // 两家模型都必填：阿里为克隆 target_model，MiniMax 为建卡后的合成用模型。
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { value -> vm.update { it.copy(model = value) } },
                    label = { Text(stringResource(R.string.voice_field_model)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (form.provider == TtsProvider.ALIYUN) {
                                    R.string.voice_model_hint_aliyun
                                } else {
                                    R.string.voice_model_hint_minimax
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    enabled = !cloning,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Voice ID 仅 MiniMax 自定义必填；阿里由克隆结果回填建卡，无需展示输入框。
                if (form.provider == TtsProvider.MINIMAX) {
                    OutlinedTextField(
                        value = form.voiceId,
                        onValueChange = { value -> vm.update { it.copy(voiceId = value) } },
                        label = { Text(stringResource(R.string.voice_field_voice_id)) },
                        supportingText = {
                            Text(stringResource(R.string.voice_voice_id_minimax_hint))
                        },
                        singleLine = true,
                        enabled = !cloning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                VoiceLanguageRow(
                    selected = form.lang,
                    onSelect = { value -> vm.update { it.copy(lang = value) } },
                    supportingText = stringResource(R.string.voice_lang_clone_hint),
                    enabled = !cloning,
                )
            }

            FormPanel(stringResource(R.string.voice_panel_source)) {
                AudioFileField(
                    label = stringResource(R.string.voice_field_clone_audio),
                    fileName = form.cloneAudio?.name,
                    onSelect = { launchAudio(cloneAudioLauncher) },
                )

                // 参考音频与参考文字成对提供，可提升相似度；阿里不支持（隐藏）。
                if (form.provider == TtsProvider.MINIMAX) {
                    AudioFileField(
                        label = stringResource(R.string.voice_field_prompt_audio),
                        fileName = form.promptAudio?.name,
                        onSelect = { launchAudio(promptAudioLauncher) },
                    )
                    OutlinedTextField(
                        value = form.promptText,
                        onValueChange = { value -> vm.update { it.copy(promptText = value) } },
                        label = { Text(stringResource(R.string.voice_field_prompt_text)) },
                        supportingText = {
                            Text(stringResource(R.string.voice_field_prompt_text_hint))
                        },
                        enabled = !cloning,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = vm::submit,
                enabled = form.valid && !cloning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (cloning) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (cloning) R.string.voice_cloning else R.string.action_start_clone,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
