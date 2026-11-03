package com.kilocoder.android.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.ProviderPolicyEntity
import com.kilocoder.android.data.provider.AuthStyle
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.TestResult
import com.kilocoder.android.data.repo.AccountDraft
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.data.repo.ChatRepository
import com.kilocoder.android.domain.account.RotationMode
import com.kilocoder.android.ui.UiMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsUiState(
    val loading: Boolean = true,
    val accounts: List<AccountEntity> = emptyList(),
    val policies: Map<String, ProviderPolicyEntity> = emptyMap(),
    val testTag: String? = null,
    val testing: Boolean = false,
    val testResult: TestResult? = null,
    val message: UiMessage? = null,
    val formError: String? = null,
)

private data class TestState(
    val tag: String,
    val running: Boolean,
    val result: TestResult? = null,
)

class AccountsViewModel(
    private val repo: AccountRepository,
    private val chat: ChatRepository,
) : ViewModel() {
    private val ids = AtomicLong(0L)
    private val loading = MutableStateFlow(true)
    private val accounts = MutableStateFlow<List<AccountEntity>>(emptyList())
    private val policies = MutableStateFlow<Map<String, ProviderPolicyEntity>>(emptyMap())
    private val message = MutableStateFlow<UiMessage?>(null)
    private val formError = MutableStateFlow<String?>(null)
    private val test = MutableStateFlow<TestState?>(null)

    val state: StateFlow<AccountsUiState> = combine(
        combine(loading, accounts, policies) { busy, list, policyMap ->
            Triple(busy, list, policyMap)
        },
        message,
        formError,
        test,
    ) { base, note, error, probe ->
        AccountsUiState(
            loading = base.first,
            accounts = base.second,
            policies = base.third,
            testTag = probe?.tag,
            testing = probe?.running == true,
            testResult = probe?.result,
            message = note,
            formError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AccountsUiState(),
    )

    init {
        viewModelScope.launch {
            repo.observeAccounts()
                .catch { error ->
                    loading.value = false
                    message.value = note(error.message ?: "Konten konnten nicht geladen werden.")
                }
                .collect { list ->
                    accounts.value = list
                    loading.value = false
                }
        }
        viewModelScope.launch {
            repo.observePolicies()
                .catch { error ->
                    message.value = note(error.message ?: "Rotation konnte nicht geladen werden.")
                }
                .collect { list ->
                    policies.value = list.associateBy { it.providerId }
                }
        }
    }

    fun accounts(providerId: String): List<AccountEntity> =
        accounts.value.filter { it.providerId == providerId }.sortedBy { it.position }

    fun policy(providerId: String): ProviderPolicyEntity =
        policies.value[providerId] ?: AccountRepository.defaultPolicy(providerId)

    fun account(id: String): AccountEntity? = accounts.value.firstOrNull { it.id == id }

    fun save(draft: AccountDraft, id: String?, onSaved: () -> Unit) {
        formError.value = null
        viewModelScope.launch {
            try {
                if (id == null) repo.add(draft) else repo.update(id, draft)
                message.value = note(if (id == null) "Konto hinzugefügt." else "Konto gespeichert.")
                onSaved()
            } catch (error: Exception) {
                formError.value = error.message ?: "Speichern fehlgeschlagen."
            }
        }
    }

    fun delete(id: String) = act("Konto gelöscht.") { repo.delete(id) }

    fun setEnabled(id: String, enabled: Boolean) =
        act(if (enabled) "Konto aktiviert." else "Konto deaktiviert.") {
            repo.setEnabled(id, enabled)
        }

    fun activate(id: String) = act("Aktives Konto gewechselt.") { repo.setActive(id) }

    fun next(providerId: String) = act("Nächstes Konto aktiviert.") { repo.next(providerId) }

    fun move(id: String, up: Boolean) = act(null) { repo.move(id, up) }

    fun savePolicy(
        providerId: String,
        mode: RotationMode,
        autoFailover: Boolean,
        maxSwitches: Int,
        cooldownSeconds: Int,
    ) {
        viewModelScope.launch {
            try {
                repo.setPolicy(
                    ProviderPolicyEntity(
                        providerId = providerId,
                        mode = mode.name,
                        autoFailover = autoFailover,
                        maxSwitches = maxSwitches.coerceIn(0, 5),
                        cooldownSeconds = cooldownSeconds.coerceIn(0, 3600),
                    ),
                )
            } catch (error: Exception) {
                message.value = note(error.message ?: "Rotation konnte nicht gespeichert werden.")
            }
        }
    }

    fun test(draft: AccountDraft, existingId: String?, tag: String) {
        if (test.value?.running == true) return
        test.value = TestState(tag = tag, running = true)
        viewModelScope.launch {
            val result = try {
                chat.test(draft, existingId)
            } catch (error: Exception) {
                TestResult(false, error.message ?: "Verbindungstest fehlgeschlagen.", 0L)
            }
            test.value = TestState(tag = tag, running = false, result = result)
        }
    }

    fun testAccount(account: AccountEntity) {
        val style = runCatching { AuthStyle.valueOf(account.authStyle) }
            .getOrDefault(ProviderCatalog.byId(account.providerId)?.authStyle ?: AuthStyle.BEARER)
        test(
            draft = AccountDraft(
                providerId = account.providerId,
                label = account.label,
                apiKey = "",
                baseUrl = account.baseUrl,
                model = account.defaultModel,
                authStyle = style,
                enabled = account.enabled,
            ),
            existingId = account.id,
            tag = account.id,
        )
    }

    fun clearAll() = act("Alle Konten gelöscht.") { repo.clearAll() }

    fun consumeMessage() {
        message.value = null
    }

    fun clearFormError() {
        formError.value = null
    }

    fun clearTest() {
        test.value = null
    }

    private fun act(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (success != null) message.value = note(success)
            } catch (error: Exception) {
                message.value = note(error.message ?: "Aktion fehlgeschlagen.")
            }
        }
    }

    private fun note(text: String) = UiMessage(ids.incrementAndGet(), text)
}
