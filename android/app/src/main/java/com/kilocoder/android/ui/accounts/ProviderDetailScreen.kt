package com.kilocoder.android.ui.accounts

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilocoder.android.R
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.ProviderPolicyEntity
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderSpec
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.domain.account.RotationMode
import com.kilocoder.android.ui.accountCount
import com.kilocoder.android.ui.components.EmptyState
import com.kilocoder.android.ui.components.Pill
import com.kilocoder.android.ui.components.PillTone
import com.kilocoder.android.ui.components.ScreenHeader
import com.kilocoder.android.ui.modeOf
import com.kilocoder.android.ui.rotationHint
import com.kilocoder.android.ui.rotationLabel
import kotlinx.coroutines.delay

@Composable
fun ProviderDetailScreen(
    providerId: String,
    vm: AccountsViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val spec = ProviderCatalog.byId(providerId)
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    if (spec == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = providerId, onBack = onBack)
            EmptyState(
                title = "Provider",
                body = stringResource(R.string.provider_unknown_body),
            )
        }
        return
    }

    val accounts = state.accounts.filter { it.providerId == providerId }.sortedBy { it.position }
    val policy = state.policies[providerId] ?: AccountRepository.defaultPolicy(providerId)

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = spec.name,
            subtitle = spec.summary,
            onBack = onBack,
            action = {
                TextButton(onClick = onAdd) {
                    Text(text = "+", style = MaterialTheme.typography.headlineSmall)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProviderIntro(spec) }
            item { PolicyCard(spec, policy, accounts.size, vm) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.provider_accounts_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.provider_accounts_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = accountCount(accounts.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.provider_empty_title),
                        body = stringResource(R.string.provider_empty_body),
                        actionLabel = stringResource(R.string.add_account),
                        onAction = onAdd,
                    )
                }
            } else {
                itemsIndexed(accounts, key = { _, account -> account.id }) { index, account ->
                    AccountCard(
                        account = account,
                        position = index,
                        total = accounts.size,
                        now = now,
                        state = state,
                        vm = vm,
                        onEdit = onEdit,
                        onDelete = { pendingDelete = it },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = { Text(stringResource(R.string.account_delete_body, target.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderIntro(spec: ProviderSpec) {
    val handler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(ProviderCatalog.protocolName(spec.protocol))
                if (spec.baseUrlRequired) {
                    Pill(stringResource(R.string.provider_custom_endpoint), PillTone.Accent)
                }
            }
            Text(
                text = stringResource(R.string.editor_base_url),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = spec.baseUrl.ifBlank { spec.baseUrlHint },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${stringResource(R.string.provider_key_label)}: ${spec.keyHint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spec.keyUrl.isNotBlank()) {
                    OutlinedButton(onClick = { handler.openUri(spec.keyUrl) }) {
                        Text(stringResource(R.string.provider_api_key))
                    }
                }
                if (spec.docsUrl.isNotBlank()) {
                    TextButton(onClick = { handler.openUri(spec.docsUrl) }) {
                        Text(stringResource(R.string.provider_docs))
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyCard(
    spec: ProviderSpec,
    policy: ProviderPolicyEntity,
    count: Int,
    vm: AccountsViewModel,
) {
    val mode = modeOf(policy.mode)
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.rotation_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                if (count > 1) {
                    TextButton(onClick = { vm.next(spec.id) }) {
                        Text(stringResource(R.string.rotation_next))
                    }
                }
            }
            Box {
                OutlinedButton(
                    onClick = { open = true },
                ) {
                    Text(rotationLabel(mode.name) + "  ▾")
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                ) {
                    RotationMode.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(rotationLabel(entry.name))
                                    Text(
                                        text = rotationHint(entry.name),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                open = false
                                vm.savePolicy(
                                    providerId = spec.id,
                                    mode = entry,
                                    autoFailover = policy.autoFailover,
                                    maxSwitches = policy.maxSwitches,
                                    cooldownSeconds = policy.cooldownSeconds,
                                )
                            },
                        )
                    }
                }
            }
            Text(
                text = rotationHint(mode.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rotation_auto_failover),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = stringResource(R.string.rotation_auto_failover_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = policy.autoFailover,
                    onCheckedChange = { checked ->
                        vm.savePolicy(
                            providerId = spec.id,
                            mode = mode,
                            autoFailover = checked,
                            maxSwitches = policy.maxSwitches,
                            cooldownSeconds = policy.cooldownSeconds,
                        )
                    },
                )
            }
            Stepper(
                label = stringResource(R.string.rotation_max_switches),
                value = policy.maxSwitches,
                range = 0..5,
                step = 1,
            ) { value ->
                vm.savePolicy(
                    providerId = spec.id,
                    mode = mode,
                    autoFailover = policy.autoFailover,
                    maxSwitches = value,
                    cooldownSeconds = policy.cooldownSeconds,
                )
            }
            Stepper(
                label = stringResource(R.string.rotation_cooldown),
                value = policy.cooldownSeconds.coerceAtLeast(10),
                range = 10..600,
                step = 10,
            ) { value ->
                vm.savePolicy(
                    providerId = spec.id,
                    mode = mode,
                    autoFailover = policy.autoFailover,
                    maxSwitches = policy.maxSwitches,
                    cooldownSeconds = value,
                )
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = value > range.first,
        ) {
            Text("−")
        }
        TextButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = value < range.last,
        ) {
            Text("+")
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountEntity,
    position: Int,
    total: Int,
    now: Long,
    state: AccountsUiState,
    vm: AccountsViewModel,
    onEdit: (String) -> Unit,
    onDelete: (AccountEntity) -> Unit,
) {
    val cooling = account.cooldownUntil > now
    val minutes = ((account.cooldownUntil - now + 59_999L) / 60_000L).coerceAtLeast(1L)
    val testing = state.testing && state.testTag == account.id
    val result = state.testResult.takeIf { state.testTag == account.id }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "•••• ${account.keyLast4} · ${account.defaultModel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = { vm.setEnabled(account.id, it) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account.active) Pill(stringResource(R.string.active), PillTone.Success)
                if (!account.enabled) Pill(stringResource(R.string.disabled), PillTone.Warning)
                if (cooling) Pill(stringResource(R.string.cooldown), PillTone.Warning)
            }
            Text(
                text = account.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.account_requests_failures,
                    account.requestCount,
                    account.failureCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cooling) {
                Text(
                    text = stringResource(R.string.cooldown_minutes, minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            account.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (testing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.testing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (result != null) {
                val tone = if (result.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Surface(
                    color = tone.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(
                                if (result.ok) R.string.test_ok else R.string.test_failed,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = tone,
                        )
                        Text(
                            text = result.message +
                                if (result.latencyMs > 0) " · ${result.latencyMs} ms" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!account.active && account.enabled) {
                    TextButton(onClick = { vm.activate(account.id) }) {
                        Text(stringResource(R.string.account_activate))
                    }
                }
                TextButton(
                    onClick = { vm.testAccount(account) },
                    enabled = !state.testing,
                ) {
                    Text(stringResource(R.string.account_test))
                }
                TextButton(onClick = { onEdit(account.id) }) {
                    Text(stringResource(R.string.account_edit))
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { vm.move(account.id, true) },
                    enabled = position > 0,
                ) {
                    Text("↑")
                }
                TextButton(
                    onClick = { vm.move(account.id, false) },
                    enabled = position < total - 1,
                ) {
                    Text("↓")
                }
            }
            TextButton(
                onClick = { onDelete(account) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.account_delete))
            }
        }
    }
}
