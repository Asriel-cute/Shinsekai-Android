package com.dreemurr.shinsekai.ui.voices

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

// 添加/编辑角色声音卡片；模型名仅 MiniMax 手填，Voice ID 支持从服务商拉取。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCardEditScreen(
    cardId: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: VoiceCardEditViewModel = viewModel(
        key = "voice_card_edit_$cardId",
        factory = viewModelFactory {
            initializer { VoiceCardEditViewModel(cardId, container) }
        },
    )
    val form by vm.form.collectAsStateWithLifecycle()
    val voicesState by vm.voicesState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val savedMsg = stringResource(R.string.settings_saved)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (cardId.isBlank()) R.string.voice_add_title else R.string.voice_edit_title,
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
                )
            }

            FormPanel(stringResource(R.string.voice_panel_voice)) {
                // 模型名称手填仅 MiniMax；阿里模型与 voice_id 绑定（嵌在前缀中），无需填写。
                if (form.provider == TtsProvider.MINIMAX) {
                    OutlinedTextField(
                        value = form.model,
                        onValueChange = { value -> vm.update { it.copy(model = value) } },
                        label = { Text(stringResource(R.string.voice_field_model)) },
                        supportingText = {
                            Text(stringResource(R.string.voice_model_hint_minimax))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = form.voiceId,
                    onValueChange = { value -> vm.update { it.copy(voiceId = value) } },
                    label = { Text(stringResource(R.string.voice_field_voice_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                VoiceLanguageRow(
                    selected = form.lang,
                    onSelect = { value -> vm.update { it.copy(lang = value) } },
                    supportingText = stringResource(R.string.voice_lang_edit_hint),
                )

                OutlinedButton(
                    onClick = {
                        showPicker = true
                        vm.fetchVoices()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(stringResource(R.string.action_fetch_voices))
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val error = vm.save()
                        if (error == null) {
                            Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = form.valid,
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

    if (showPicker) {
        VoicePickerDialog(
            state = voicesState,
            onDismiss = {
                showPicker = false
                vm.dismissVoices()
            },
            onPick = { id ->
                vm.update { it.copy(voiceId = id) }
                showPicker = false
                vm.dismissVoices()
            },
        )
    }
}
