package com.kilocoder.android.data.repo

import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.provider.ChatRequest
import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderClient
import com.kilocoder.android.data.provider.ProviderFailure
import com.kilocoder.android.data.provider.Protocol
import com.kilocoder.android.data.provider.StreamEvent
import com.kilocoder.android.data.provider.TestResult
import com.kilocoder.android.domain.Clock
import com.kilocoder.android.domain.WallClock
import com.kilocoder.android.domain.account.AccountSelector
import com.kilocoder.android.domain.account.RotationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

sealed interface ChatFailure {
    data object NoAccount : ChatFailure
    data object KeyDecrypt : ChatFailure
    data class Provider(val message: String, val status: Int? = null) : ChatFailure
}

sealed interface ChatEvent {
    data class Account(val id: String, val label: String) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Reasoning(val text: String) : ChatEvent
    data object Completed : ChatEvent
    data class Failed(val error: ChatFailure) : ChatEvent
}

class ChatRepository(
    private val accounts: AccountRepository,
    private val clients: Map<Protocol, ProviderClient>,
    private val selector: AccountSelector = AccountSelector(),
    private val clock: Clock = WallClock,
) {
    fun stream(
        providerId: String,
        model: String,
        system: String,
        turns: List<ChatTurn>,
        pinned: String?,
    ): Flow<ChatEvent> = channelFlow {
        val list = accounts.byProvider(providerId)
        if (list.isEmpty()) {
            send(ChatEvent.Failed(ChatFailure.NoAccount))
            return@channelFlow
        }
        val spec = ProviderCatalog.byId(providerId)
        val client = spec?.let { clients[it.protocol] }
        if (client == null) {
            send(ChatEvent.Failed(ChatFailure.Provider("Protokoll nicht unterstützt.")))
            return@channelFlow
        }
        val policy = accounts.policy(providerId)
        val mode = runCatching { RotationMode.valueOf(policy.mode) }
            .getOrDefault(RotationMode.FAILOVER)
        val request = ChatRequest(model = model, system = system, turns = trim(turns))
        val excluded = mutableSetOf<String>()
        var content = false
        var lastFailure: ProviderFailure? = null
        val max = if (pinned == null && policy.autoFailover) policy.maxSwitches.coerceIn(0, 5) else 0

        for (attempt in 0..max) {
            val account = if (pinned != null) {
                list.firstOrNull { it.id == pinned && it.enabled }
            } else {
                selector.choose(list, policy, excluded, clock.now())
            }
            if (account == null) {
                send(ChatEvent.Failed(ChatFailure.NoAccount))
                return@channelFlow
            }
            val key = accounts.key(account)
            if (key == null) {
                send(ChatEvent.Failed(ChatFailure.KeyDecrypt))
                return@channelFlow
            }
            if (pinned == null && !account.active) {
                accounts.setActive(account.id)
            }
            accounts.markUsed(account.id)
            send(ChatEvent.Account(account.id, account.label))

            var failure: ProviderFailure? = null
            var done = false
            try {
                client.stream(account, key, request).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            content = true
                            send(ChatEvent.Delta(event.text))
                        }

                        is StreamEvent.Reasoning -> {
                            content = true
                            send(ChatEvent.Reasoning(event.text))
                        }

                        StreamEvent.Done -> done = true

                        is StreamEvent.Failure -> failure = event.error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = ProviderFailure(e.message ?: "Netzwerkfehler", retryable = true)
            }

            if (done) {
                accounts.success(account.id)
                if (pinned == null && mode == RotationMode.NEXT) accounts.next(providerId)
                send(ChatEvent.Completed)
                return@channelFlow
            }
            val error = failure
            if (error == null && content) {
                accounts.success(account.id)
                if (pinned == null && mode == RotationMode.NEXT) accounts.next(providerId)
                send(ChatEvent.Completed)
                return@channelFlow
            }
            val reason = error ?: ProviderFailure("Der Stream wurde ohne Antwort beendet.", retryable = !content)
            accounts.failure(account.id, reason, policy)
            lastFailure = reason
            if (pinned != null || !policy.autoFailover || content || !reason.retryable || attempt == max) {
                send(ChatEvent.Failed(ChatFailure.Provider(reason.message, reason.status)))
                return@channelFlow
            }
            excluded += account.id
        }

        send(ChatEvent.Failed(ChatFailure.Provider(lastFailure?.message ?: "Alle Konten sind nicht verfügbar.")))
    }

    suspend fun test(draft: AccountDraft, existingId: String?): TestResult {
        val spec = ProviderCatalog.byId(draft.providerId)
            ?: return TestResult(false, "Unbekannter Provider.", 0L)
        val existing = existingId?.let { accounts.byId(it) }
        val base = draft.baseUrl.trim().ifBlank { existing?.baseUrl ?: spec.baseUrl }
        val model = draft.model.trim().ifBlank { existing?.defaultModel ?: spec.defaultModel }
        val key = draft.apiKey.trim().ifBlank { existing?.let { accounts.key(it) } ?: "" }
        if (base.isBlank()) return TestResult(false, "Base-URL fehlt.", 0L)
        if (model.isBlank()) return TestResult(false, "Modell fehlt.", 0L)
        if (key.isBlank()) return TestResult(false, "API-Key fehlt.", 0L)
        val client = clients[spec.protocol] ?: return TestResult(false, "Protokoll nicht unterstützt.", 0L)
        val account = AccountEntity(
            id = existingId ?: "draft",
            providerId = spec.id,
            label = draft.label,
            keyCipher = "",
            keyLast4 = "",
            baseUrl = base,
            authStyle = draft.authStyle.name,
            defaultModel = model,
            enabled = true,
            active = false,
            position = 0,
            weight = 1,
            requestCount = 0,
            successCount = 0,
            failureCount = 0,
            consecutiveFailures = 0,
            cooldownUntil = 0,
            lastUsedAt = 0,
            lastError = null,
            createdAt = 0,
            updatedAt = 0,
        )
        return client.test(account, key)
    }

    private fun trim(turns: List<ChatTurn>): List<ChatTurn> {
        val recent = turns.takeLast(24)
        var total = recent.sumOf { it.text.length }
        val out = ArrayDeque<ChatTurn>()
        for (turn in recent.asReversed()) {
            if (out.isNotEmpty() && total > 24_000) break
            out.addFirst(turn)
            total -= turn.text.length
        }
        return out.toList()
    }
}
