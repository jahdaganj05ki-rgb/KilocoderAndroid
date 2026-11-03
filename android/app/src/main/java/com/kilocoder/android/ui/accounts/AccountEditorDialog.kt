package com.kilocoder.android.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilocoder.android.R
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.provider.AuthStyle
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.repo.AccountDraft
import com.kilocoder.android.ui.authLabel

@Composable
fun AccountEditorDialog(
    providerId: String,
    account: AccountEntity?,
    vm: AccountsViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val spec = ProviderCatalog.byId(providerId) ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val keyRequired = stringResource(R.string.editor_required_key)
    val baseRequired = stringResource(R.string.editor_required_base)
    val modelRequired = stringResource(R.string.editor_required_model)
    val badBase = stringResource(R.string.editor_bad_base)

    var label by remember(account?.id, spec.id) { mutableStateOf(account?.label.orEmpty()) }
    var apiKey by remember(account?.id, spec.id) { mutableStateOf("") }
    var base by remember(account?.id, spec.id) {
        mutableStateOf(account?.baseUrl ?: spec.baseUrl)
    }
    var model by remember(account?.id, spec.id) {
        mutableStateOf(account?.defaultModel ?: spec.defaultModel)
    }
    var auth by remember(account?.id, spec.id) {
        mutableStateOf(
            account?.let { runCatching { AuthStyle.valueOf(it.authStyle) }.getOrNull() }
                ?: spec.authStyle,
        )
    }
    var enabled by remember(account?.id, spec.id) {
        mutableStateOf(account?.enabled ?: true)
    }
    var showKey by remember { mutableStateOf(false) }
    var advanced by remember(account?.id, spec.id) {
        mutableStateOf(spec.editableBaseUrl || spec.baseUrlRequired)
    }
    var modelMenu by remember { mutableStateOf(false) }
    var authMenu by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val tag = account?.id ?: "new:$providerId"
    val testing = state.testing && state.testTag == tag
    val result = state.testResult.takeIf { state.testTag == tag }
    val close = {
        vm.clearTest()
        vm.clearFormError()
        onDismiss()
    }
    val draft = AccountDraft(
        providerId = spec.id,
        label = label,
        apiKey = apiKey,
        baseUrl = base,
        model = model,
        authStyle = auth,
        enabled = enabled,
    )

    fun validate(requireKey: Boolean): String? {
        if (requireKey && apiKey.isBlank()) return keyRequired
        if ((spec.baseUrlRequired || spec.editableBaseUrl) && base.isBlank()) return baseRequired
        if (base.isNotBlank() &&
            !base.startsWith("http://") &&
            !base.startsWith("https://")
        ) {
            return badBase
        }
        if (model.isBlank()) return modelRequired
        return null
    }

    Dialog(
        onDismissRequest = { if (!testing) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 700.dp)
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (account == null) {
                                    R.string.editor_add_title
                                } else {
                                    R.string.editor_edit_title
                                },
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = spec.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = close) {
                        Text("×", style = MaterialTheme.typography.titleLarge)
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_label)) },
                    placeholder = { Text(stringResource(R.string.editor_label_hint)) },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_key)) },
                    placeholder = { Text(stringResource(R.string.editor_key_hint)) },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                stringResource(
                                    if (showKey) R.string.editor_hide else R.string.editor_show,
                                ),
                            )
                        }
                    },
                )
                if (account != null) {
                    Text(
                        text = stringResource(R.string.editor_key_keep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (advanced) {
                    OutlinedTextField(
                        value = base,
                        onValueChange = {
                            base = it
                            localError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.editor_base_url)) },
                        singleLine = true,
                    )
                    if (spec.baseUrlHint.isNotBlank()) {
                        Text(
                            text = spec.baseUrlHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        OutlinedButton(
                            onClick = { authMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(authLabel(auth))
                            Spacer(Modifier.weight(1f))
                            Text("▾")
                        }
                        DropdownMenu(
                            expanded = authMenu,
                            onDismissRequest = { authMenu = false },
                        ) {
                            AuthStyle.entries.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(authLabel(style)) },
                                    onClick = {
                                        authMenu = false
                                        auth = style
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "${stringResource(R.string.editor_base_url)}: ${spec.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = {
                                model = it
                                localError = null
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.editor_model)) },
                            singleLine = true,
                        )
                        if (spec.models.isNotEmpty()) {
                            TextButton(onClick = { modelMenu = true }) {
                                Text("▾")
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                    ) {
                        spec.models.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate) },
                                onClick = {
                                    modelMenu = false
                                    model = candidate
                                },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.editor_advanced),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "−" else "+")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.editor_enabled),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                val formError = localError ?: state.formError
                if (formError != null) {
                    Text(
                        text = formError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (testing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = close) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val error = validate(account == null)
                            if (error == null) {
                                vm.test(draft, account?.id, tag)
                            } else {
                                localError = error
                            }
                        },
                        enabled = !state.testing,
                    ) {
                        Text(stringResource(R.string.editor_test))
                    }
                    Button(
                        onClick = {
                            val error = validate(account == null)
                            if (error == null) {
                                vm.save(draft, account?.id) {
                                    vm.clearTest()
                                    vm.clearFormError()
                                    onSaved()
                                }
                            } else {
                                localError = error
                            }
                        },
                    ) {
                        Text(stringResource(R.string.editor_save))
                    }
                }
            }
        }
    }
}
