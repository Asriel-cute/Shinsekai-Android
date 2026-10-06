package com.dreemurr.shinsekai.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreemurr.shinsekai.R
import com.dreemurr.shinsekai.di.LocalAppContainer
import com.dreemurr.shinsekai.session.ChatSession
import com.dreemurr.shinsekai.ui.common.DeleteConfirmationDialog
import com.dreemurr.shinsekai.ui.common.EmptyState

private enum class SessionAction { Menu, Rename, Delete }

private data class SessionDialog(val action: SessionAction, val target: ChatSession)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: SessionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SessionsViewModel(container) }
        },
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<SessionDialog?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sessions_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSession,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_new_session)) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator()

                sessions.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.sessions_empty_title),
                    body = stringResource(R.string.sessions_empty_body),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onOpenSession(session.id) },
                            onLongClick = { dialog = SessionDialog(SessionAction.Menu, session) },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { (action, target) ->
        when (action) {
            SessionAction.Menu -> SessionMenuDialog(
                target = target,
                onDismiss = { dialog = null },
                onRename = { dialog = SessionDialog(SessionAction.Rename, target) },
                onDelete = { dialog = SessionDialog(SessionAction.Delete, target) },
            )

            SessionAction.Rename -> RenameSessionDialog(
                target = target,
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    if (name.isNotBlank()) vm.rename(target.id, name)
                    dialog = null
                },
            )

            SessionAction.Delete -> DeleteConfirmationDialog(
                titleRes = R.string.delete_session_title,
                messageRes = R.string.delete_session_body,
                onDismiss = { dialog = null },
                onConfirm = {
                    vm.delete(target.id)
                    dialog = null
                },
            )
        }
    }
}

@Composable
private fun SessionMenuDialog(
    target: ChatSession,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_menu_title)) },
        text = { Text(target.title) },
        confirmButton = {
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete))
            }
        },
    )
}

@Composable
private fun RenameSessionDialog(
    target: ChatSession,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(target.id) { mutableStateOf(target.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_session_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.rename_session_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ChatSession,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val preview = remember(session) { SessionsViewModel.previewOf(session) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                preview
                    .ifBlank { session.scenario }
                    .ifBlank { stringResource(R.string.session_preview_fallback) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                DateUtils.getRelativeTimeSpanString(session.updatedAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
