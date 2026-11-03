package com.kilocoder.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kilocoder.android.AppContainer
import com.kilocoder.android.R
import com.kilocoder.android.ui.accounts.AccountEditorDialog
import com.kilocoder.android.ui.accounts.AccountsScreen
import com.kilocoder.android.ui.accounts.AccountsViewModel
import com.kilocoder.android.ui.accounts.ProviderDetailScreen
import com.kilocoder.android.ui.chat.ChatScreen
import com.kilocoder.android.ui.chat.ChatViewModel
import com.kilocoder.android.ui.github.GitHubScreen
import com.kilocoder.android.ui.github.GitHubViewModel
import com.kilocoder.android.ui.settings.SettingsScreen

private enum class MainTab(@StringRes val label: Int, val glyph: String) {
    CHAT(R.string.nav_chat, "◆"),
    ACCOUNTS(R.string.nav_accounts, "≡"),
    GITHUB(R.string.nav_github, "⌁"),
    SETTINGS(R.string.nav_settings, "⚙"),
}

private data class EditorTarget(
    val providerId: String,
    val accountId: String?,
)

@Composable
fun AppRoot(container: AppContainer) {
    val accountsVm: AccountsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AccountsViewModel(container.accounts, container.chat)
            }
        },
    )
    val chatVm: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(container.agent, container.sessions, container.workspace)
            }
        },
    )
    val githubVm: GitHubViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GitHubViewModel(container.github, container.installer)
            }
        },
    )
    val accountsState by accountsVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.CHAT.name) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val note = accountsState.message

    LaunchedEffect(note?.id) {
        if (note != null) {
            snackbar.showSnackbar(note.text)
            accountsVm.consumeMessage()
        }
    }

    val current = MainTab.entries.firstOrNull { it.name == tab } ?: MainTab.CHAT

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = current == item,
                        onClick = {
                            tab = item.name
                            provider = null
                        },
                        icon = { Text(item.glyph) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val selected = provider
            if (selected != null) {
                ProviderDetailScreen(
                    providerId = selected,
                    vm = accountsVm,
                    onBack = { provider = null },
                    onAdd = { editor = EditorTarget(selected, null) },
                    onEdit = { accountId -> editor = EditorTarget(selected, accountId) },
                )
            } else {
                when (current) {
                    MainTab.CHAT -> ChatScreen(chatVm, accountsVm)
                    MainTab.ACCOUNTS -> AccountsScreen(
                        vm = accountsVm,
                        onOpenProvider = { provider = it },
                        onAdd = { providerId ->
                            provider = providerId
                            editor = EditorTarget(providerId, null)
                        },
                    )

                    MainTab.GITHUB -> GitHubScreen(githubVm)

                    MainTab.SETTINGS -> SettingsScreen(accountsVm, container.workspace.rootPath)
                }
            }
        }
    }

    val target = editor
    if (target != null) {
        AccountEditorDialog(
            providerId = target.providerId,
            account = target.accountId?.let { accountsVm.account(it) },
            vm = accountsVm,
            onDismiss = { editor = null },
            onSaved = { editor = null },
        )
    }
}
