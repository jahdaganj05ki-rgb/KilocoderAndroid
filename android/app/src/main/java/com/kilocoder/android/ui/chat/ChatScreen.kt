package com.kilocoder.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilocoder.android.R
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderSpec
import com.kilocoder.android.ui.accounts.AccountsViewModel
import com.kilocoder.android.ui.components.EmptyState
import com.kilocoder.android.ui.components.Pill
import com.kilocoder.android.ui.components.PillTone
import com.kilocoder.android.ui.components.ScreenHeader

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    accountsVm: AccountsViewModel,
) {
    val state by vm.stream.collectAsStateWithLifecycle()
    val accountState by accountsVm.state.collectAsStateWithLifecycle()
    val spec = ProviderCatalog.byId(state.providerId) ?: ProviderCatalog.all.first()
    val accounts = remember(accountState.accounts, state.providerId) {
        accountState.accounts
            .filter { it.providerId == state.providerId }
            .sortedBy { it.position }
    }
    var sessionsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(accounts.map { it.id }, state.pinned) {
        if (state.pinned != null && accounts.none { it.id == state.pinned }) {
            vm.pin(null)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val sessionTitle = state.sessions
        .firstOrNull { it.id == state.sessionId }
        ?.title
        ?.takeIf { it.isNotBlank() && it != "New chat" }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.chat_title),
            subtitle = sessionTitle ?: stringResource(R.string.chat_subtitle),
            action = {
                Row {
                    TextButton(onClick = { sessionsOpen = true }) {
                        Text(stringResource(R.string.chat_sessions))
                    }
                    TextButton(onClick = vm::newChat) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            },
        )
        ControlCard(
            spec = spec,
            model = state.model,
            accounts = accounts,
            pinned = state.pinned,
            onProvider = vm::selectProvider,
            onModel = vm::setModel,
            onPin = vm::pin,
        )
        state.accountLabel?.let { label ->
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(stringResource(R.string.chat_current_account, label))
            }
        }
        if (state.compacted) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(stringResource(R.string.chat_compacted), PillTone.Warning)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.chat_empty_title),
                        body = stringResource(R.string.chat_empty_body),
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
        state.error?.let { error ->
            ErrorCard(error = error, onDismiss = vm::dismissError)
        }
        Composer(
            state = state,
            onInput = vm::setInput,
            onSend = vm::send,
            onStop = vm::stop,
        )
    }

    if (sessionsOpen) {
        SessionsDialog(
            sessions = state.sessions,
            currentId = state.sessionId,
            onSelect = {
                vm.loadSession(it)
                sessionsOpen = false
            },
            onDelete = vm::deleteSession,
            onNew = {
                vm.newChat()
                sessionsOpen = false
            },
            onDismiss = { sessionsOpen = false },
        )
    }
}

@Composable
private fun ControlCard(
    spec: ProviderSpec,
    model: String,
    accounts: List<AccountEntity>,
    pinned: String?,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onPin: (String?) -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == pinned }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { providerMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = spec.name + "  ▾",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false },
                    ) {
                        ProviderCatalog.all.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    providerMenu = false
                                    onProvider(provider.id)
                                },
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { accountMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = account?.label
                                ?: stringResource(R.string.chat_account_auto),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = accountMenu,
                        onDismissRequest = { accountMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_account_auto)) },
                            onClick = {
                                accountMenu = false
                                onPin(null)
                            },
                        )
                        accounts.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.label)
                                        Text(
                                            text = "•••• ${item.keyLast4}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    accountMenu = false
                                    onPin(item.id)
                                },
                            )
                        }
                    }
                }
            }
            Box {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_model)) },
                    singleLine = true,
                    trailingIcon = {
                        if (spec.models.isNotEmpty()) {
                            TextButton(onClick = { modelMenu = true }) {
                                Text("▾")
                            }
                        }
                    },
                )
                DropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                ) {
                    spec.models.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate) },
                            onClick = {
                                modelMenu = false
                                onModel(candidate)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantBubble(message)
        ChatRole.TOOL -> ToolBubble(message)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SelectionContainer {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.reasoning.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.chat_reasoning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = message.reasoning,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (message.toolCalls.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.toolCalls.forEach { call ->
                            Pill(call.name, PillTone.Accent)
                        }
                    }
                }
                if (message.streaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(
                        text = message.toolName ?: stringResource(R.string.chat_tool),
                        tone = PillTone.Accent,
                    )
                    if (message.isError) {
                        Pill(stringResource(R.string.chat_tool_error), PillTone.Error)
                    }
                    if (message.streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text.take(4_000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: ChatUiError, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (error) {
                    ChatUiError.NoAccount -> stringResource(R.string.chat_error_no_account)
                    ChatUiError.Key -> stringResource(R.string.chat_error_key)
                    is ChatUiError.Provider -> error.message
                },
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
private fun Composer(
    state: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 4,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        if (state.streaming) {
            OutlinedButton(onClick = onStop) {
                Text(stringResource(R.string.chat_stop))
            }
        } else {
            Button(
                onClick = onSend,
                enabled = state.input.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        }
    }
}
