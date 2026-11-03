package com.kilocoder.android.ui.github

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilocoder.android.R
import com.kilocoder.android.data.github.GitHubArtifact
import com.kilocoder.android.data.github.GitHubRun
import com.kilocoder.android.ui.components.Pill
import com.kilocoder.android.ui.components.PillTone
import com.kilocoder.android.ui.components.ScreenHeader
import java.util.Locale

@Composable
fun GitHubScreen(vm: GitHubViewModel) {
    val state by vm.stream.collectAsStateWithLifecycle()
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var install by remember { mutableStateOf<GitHubArtifact?>(null) }
    val selected = state.runs.firstOrNull { it.id == state.selectedId }
    val connected = state.config != null

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.github_title),
            subtitle = stringResource(R.string.github_subtitle),
        )
        if (state.loading || state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.note != null) {
                item {
                    NoteCard(
                        text = state.note.orEmpty(),
                        onDismiss = vm::dismissNote,
                    )
                }
            }
            if (state.error != null) {
                item {
                    ErrorCard(
                        text = state.error.orEmpty(),
                        onDismiss = vm::dismissError,
                    )
                }
            }
            item {
                ConnectionCard(
                    state = state,
                    tokenVisible = tokenVisible,
                    onToggleToken = { tokenVisible = !tokenVisible },
                    vm = vm,
                    onDisconnect = { disconnect = true },
                )
            }
            item {
                BuildCard(
                    state = state,
                    connected = connected,
                    vm = vm,
                )
            }
            item {
                RunsCard(
                    state = state,
                    connected = connected,
                    vm = vm,
                )
            }
            if (selected != null) {
                item {
                    RunActionsCard(
                        run = selected,
                        busy = state.busy,
                        onLogs = vm::loadLogs,
                        onArtifacts = vm::loadArtifacts,
                    )
                }
            }
            if (state.logs.isNotBlank()) {
                item { LogsCard(logs = state.logs) }
            }
            if (state.artifactsLoaded) {
                item {
                    ArtifactsCard(
                        artifacts = state.artifacts,
                        busy = state.busy,
                        onInstall = { install = it },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (disconnect) {
        AlertDialog(
            onDismissRequest = { disconnect = false },
            title = { Text(stringResource(R.string.github_disconnect_title)) },
            text = { Text(stringResource(R.string.github_disconnect_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        disconnect = false
                        vm.disconnect()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.github_disconnect),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnect = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val target = install
    if (target != null) {
        AlertDialog(
            onDismissRequest = { install = null },
            title = {
                Text(stringResource(R.string.github_install_confirm, target.name))
            },
            text = { Text(stringResource(R.string.github_install_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        install = null
                        vm.install(target)
                    },
                ) {
                    Text(stringResource(R.string.github_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { install = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    state: GitHubUiState,
    tokenVisible: Boolean,
    onToggleToken: () -> Unit,
    vm: GitHubViewModel,
    onDisconnect: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.github_connection_title),
    ) {
        val config = state.config
        if (config != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = stringResource(R.string.github_connected, config.login),
                    tone = PillTone.Success,
                )
                Text(
                    text = "${config.owner}/${config.repo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${config.branch} · ${config.workflow} · token ••••${config.tokenLast4}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Pill(
                text = stringResource(R.string.github_not_connected),
                tone = PillTone.Warning,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.owner,
                onValueChange = vm::setOwner,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.github_owner)) },
                placeholder = { Text(stringResource(R.string.github_owner_hint)) },
                singleLine = true,
                enabled = !state.busy,
            )
            OutlinedTextField(
                value = state.repo,
                onValueChange = vm::setRepo,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.github_repo)) },
                placeholder = { Text(stringResource(R.string.github_repo_hint)) },
                singleLine = true,
                enabled = !state.busy,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.branch,
                onValueChange = vm::setBranch,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.github_branch)) },
                placeholder = { Text(stringResource(R.string.github_branch_hint)) },
                singleLine = true,
                enabled = !state.busy,
            )
            OutlinedTextField(
                value = state.workflow,
                onValueChange = vm::setWorkflow,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.github_workflow)) },
                placeholder = { Text(stringResource(R.string.github_workflow_hint)) },
                singleLine = true,
                enabled = !state.busy,
            )
        }
        OutlinedTextField(
            value = state.token,
            onValueChange = vm::setToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.github_token)) },
            placeholder = {
                Text(
                    stringResource(
                        if (config == null) R.string.github_token_hint
                        else R.string.github_token_keep,
                    ),
                )
            },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = if (tokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = onToggleToken) {
                    Text(
                        stringResource(
                            if (tokenVisible) R.string.github_token_hide
                            else R.string.github_token_show,
                        ),
                    )
                }
            },
        )
        Text(
            text = stringResource(R.string.github_pat_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = vm::connect,
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.github_connect))
            }
            if (config != null) {
                TextButton(
                    onClick = onDisconnect,
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.github_disconnect))
                }
            }
        }
    }
}

@Composable
private fun BuildCard(
    state: GitHubUiState,
    connected: Boolean,
    vm: GitHubViewModel,
) {
    SectionCard(
        title = stringResource(R.string.github_push_title),
        subtitle = stringResource(R.string.github_push_body),
    ) {
        OutlinedTextField(
            value = state.commit,
            onValueChange = vm::setCommit,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.github_commit)) },
            singleLine = true,
            enabled = connected && !state.busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::push,
                modifier = Modifier.weight(1f),
                enabled = connected && !state.busy,
            ) {
                Text(stringResource(R.string.github_push))
            }
            OutlinedButton(
                onClick = vm::dispatch,
                modifier = Modifier.weight(1f),
                enabled = connected && !state.busy,
            ) {
                Text(stringResource(R.string.github_dispatch))
            }
        }
        if (!connected) {
            Text(
                text = stringResource(R.string.github_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.push?.let { push ->
            Text(
                text = stringResource(
                    R.string.github_push_ok,
                    push.files,
                    push.branch,
                    push.commitSha.take(7),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (push.skipped > 0) {
                Text(
                    text = stringResource(R.string.github_push_skipped, push.skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunsCard(
    state: GitHubUiState,
    connected: Boolean,
    vm: GitHubViewModel,
) {
    SectionCard(
        title = stringResource(R.string.github_runs_title),
        subtitle = stringResource(R.string.github_runs_hint),
        action = {
            TextButton(
                onClick = vm::refreshRuns,
                enabled = connected && !state.busy,
            ) {
                Text(stringResource(R.string.github_refresh))
            }
        },
    ) {
        if (!connected) {
            Text(
                text = stringResource(R.string.github_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.runs.isEmpty()) {
            Text(
                text = stringResource(R.string.github_no_runs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.runs.forEach { run ->
                    RunRow(
                        run = run,
                        selected = run.id == state.selectedId,
                        onClick = { vm.selectRun(run.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunRow(
    run: GitHubRun,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cardColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.background
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = run.title.ifBlank {
                        run.workflow ?: stringResource(R.string.github_runs_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Pill(
                    text = run.conclusion ?: run.status ?: "unknown",
                    tone = runTone(run),
                )
            }
            Text(
                text = stringResource(
                    R.string.github_run_meta,
                    run.number,
                    run.status ?: "unknown",
                    timestamp(run.createdAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.sha?.take(7)?.let { sha ->
                Text(
                    text = stringResource(R.string.github_run_sha, sha),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunActionsCard(
    run: GitHubRun,
    busy: Boolean,
    onLogs: () -> Unit,
    onArtifacts: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.github_selected_run, run.number),
        subtitle = run.title.ifBlank { run.workflow.orEmpty() },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onLogs,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.github_load_logs))
            }
            OutlinedButton(
                onClick = onArtifacts,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.github_load_artifacts))
            }
        }
    }
}

@Composable
private fun LogsCard(logs: String) {
    SectionCard(
        title = stringResource(R.string.github_logs_title),
        subtitle = stringResource(R.string.github_logs_limit),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    text = logs,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ArtifactsCard(
    artifacts: List<GitHubArtifact>,
    busy: Boolean,
    onInstall: (GitHubArtifact) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.github_artifacts_title),
        subtitle = stringResource(R.string.github_install_hint),
    ) {
        if (artifacts.isEmpty()) {
            Text(
                text = stringResource(R.string.github_no_artifacts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            artifacts.forEach { artifact ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artifact.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = size(artifact.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (artifact.expired) {
                        Pill(
                            text = stringResource(R.string.github_artifact_expired),
                            tone = PillTone.Error,
                        )
                    } else {
                        Button(
                            onClick = { onInstall(artifact) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.github_install))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun NoteCard(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("×")
            }
        }
    }
}

@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("×")
            }
        }
    }
}

private fun runTone(run: GitHubRun): PillTone = when (run.conclusion) {
    "success" -> PillTone.Success
    "failure", "cancelled", "timed_out", "action_required" -> PillTone.Error
    "skipped", "neutral" -> PillTone.Neutral
    else -> if (run.status == "in_progress" || run.status == "queued") {
        PillTone.Warning
    } else {
        PillTone.Accent
    }
}

private fun timestamp(value: String?): String =
    value?.replace('T', ' ')?.removeSuffix("Z")?.take(16).orEmpty()

private fun size(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
