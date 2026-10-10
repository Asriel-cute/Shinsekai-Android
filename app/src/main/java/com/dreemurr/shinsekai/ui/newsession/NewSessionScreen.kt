package com.dreemurr.shinsekai.ui.newsession

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.ui.common.ColorDot
import com.dreemurr.shinsekai.ui.common.EmptyState
import com.dreemurr.shinsekai.ui.common.spriteInfoText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onGoImport: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: NewSessionViewModel = viewModel(
        factory = viewModelFactory {
            initializer { NewSessionViewModel(container) }
        },
    )
    val characters by vm.characters.collectAsStateWithLifecycle()

    // 重建后恢复选中 id。
    var selectedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet()) }
    var scenario by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun toggle(id: String, checked: Boolean) {
        selectedIds = if (checked) selectedIds + id else selectedIds - id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_session_title)) },
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
                        creating = true
                        vm.create(
                            selectedIds = selectedIds,
                            scenario = scenario,
                        ) { id ->
                            creating = false
                            onCreated(id)
                        }
                    },
                    enabled = selectedIds.isNotEmpty() && !creating,
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
                        stringResource(R.string.action_start_chat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
    ) { padding ->
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
                Text(
                    stringResource(R.string.new_session_section_characters),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(characters, key = { it.id }) { character ->
                CharacterPickRow(
                    character = character,
                    checked = character.id in selectedIds,
                    onToggle = { toggle(character.id, it) },
                )
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
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun CharacterPickRow(
    character: Character,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
            ColorDot(colorHex = character.color)
            Column(Modifier.weight(1f)) {
                Text(
                    character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    spriteInfoText(character),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
