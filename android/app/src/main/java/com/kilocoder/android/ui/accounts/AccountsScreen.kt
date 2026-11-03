package com.kilocoder.android.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilocoder.android.R
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.ProviderPolicyEntity
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderSpec
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.ui.accountCount
import com.kilocoder.android.ui.components.Pill
import com.kilocoder.android.ui.components.PillTone
import com.kilocoder.android.ui.components.ScreenHeader
import com.kilocoder.android.ui.rotationLabel
import androidx.compose.ui.res.stringResource

private data class ProviderGroup(
    val spec: ProviderSpec,
    val accounts: List<AccountEntity>,
    val policy: ProviderPolicyEntity,
)

@Composable
fun AccountsScreen(
    vm: AccountsViewModel,
    onOpenProvider: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(state.accounts, state.policies, query) {
        ProviderCatalog.all.map { spec ->
            ProviderGroup(
                spec = spec,
                accounts = state.accounts.filter { it.providerId == spec.id }.sortedBy { it.position },
                policy = state.policies[spec.id] ?: AccountRepository.defaultPolicy(spec.id),
            )
        }.filter { group ->
            query.isBlank() ||
                group.spec.name.contains(query, ignoreCase = true) ||
                group.spec.id.contains(query, ignoreCase = true) ||
                group.spec.summary.contains(query, ignoreCase = true)
        }
    }
    val totalAccounts = groups.sumOf { it.accounts.size }
    val activeAccounts = groups.sumOf { group -> group.accounts.count { it.active } }
    val configured = groups.count { it.accounts.isNotEmpty() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.accounts_title),
            subtitle = stringResource(R.string.accounts_subtitle),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard(
                    accounts = totalAccounts,
                    active = activeAccounts,
                    providers = configured,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.accounts_search)) },
                    leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleMedium) },
                )
            }
            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (groups.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.accounts_empty_search),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(groups, key = { it.spec.id }) { group ->
                    ProviderCard(
                        group = group,
                        onClick = { onOpenProvider(group.spec.id) },
                        onAdd = { onAdd(group.spec.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(accounts: Int, active: Int, providers: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(accounts.toString(), stringResource(R.string.accounts_summary_accounts))
            Metric(active.toString(), stringResource(R.string.accounts_summary_active))
            Metric(providers.toString(), stringResource(R.string.accounts_summary_providers))
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun ProviderCard(
    group: ProviderGroup,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    val active = group.accounts.firstOrNull { it.active }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProviderAvatar(group.spec.name)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.spec.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = group.spec.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Pill(accountCount(group.accounts.size))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(ProviderCatalog.protocolName(group.spec.protocol))
                Pill(rotationLabel(group.policy.mode), PillTone.Accent)
                if (active != null) {
                    Pill(stringResource(R.string.active), PillTone.Success)
                } else if (group.accounts.isEmpty()) {
                    Pill(stringResource(R.string.no_accounts), PillTone.Warning)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = active?.let { stringResource(R.string.accounts_active_label, it.label) }
                        ?: stringResource(R.string.no_active_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onAdd) {
                    Text(stringResource(R.string.add_account))
                }
            }
        }
    }
}

@Composable
private fun ProviderAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(2).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
