package com.kilocoder.android.data.agent

import com.kilocoder.android.data.provider.ChatRequest
import com.kilocoder.android.data.provider.ChatToolCall
import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderClient
import com.kilocoder.android.data.provider.ProviderFailure
import com.kilocoder.android.data.provider.Protocol
import com.kilocoder.android.data.provider.Role
import com.kilocoder.android.data.provider.StreamEvent
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.data.repo.ChatEvent
import com.kilocoder.android.data.repo.ChatFailure
import com.kilocoder.android.domain.Clock
import com.kilocoder.android.domain.WallClock
import com.kilocoder.android.domain.account.AccountSelector
import com.kilocoder.android.domain.account.RotationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

class AgentRepository(
    private val accounts: AccountRepository,
    private val clients: Map<Protocol, ProviderClient>,
    private val tools: WorkspaceTools,
    private val selector: AccountSelector = AccountSelector(),
    private val clock: Clock = WallClock,
    private val compactor: ContextCompactor = ContextCompactor(),
) {
    fun stream(
        providerId: String,
        model: String,
        system: String,
        history: List<ChatTurn>,
        pinned: String?,
        maxSteps: Int = 6,
    ): Flow<ChatEvent> = channelFlow {
        val compacted = compactor.compact(history)
        if (compacted.summary != null) send(ChatEvent.Compacted(compacted.summary))
        var turns = compacted.turns

        for (step in 0 until maxSteps) {
            val result = collectTurn(
                providerId = providerId,
                model = model,
                system = system,
                turns = turns,
                pinned = pinned,
                emit = { send(it) },
            )
            when (result) {
                is TurnResult.Error -> {
                    send(ChatEvent.Failed(result.failure))
                    return@channelFlow
                }

                is TurnResult.Ok -> {
                    if (result.calls.isEmpty()) {
                        send(ChatEvent.Completed)
                        return@channelFlow
                    }
                    turns = turns + ChatTurn(
                        role = Role.ASSISTANT,
                        text = result.text,
                        toolCalls = result.calls,
                    )
                    for (call in result.calls) {
                        send(ChatEvent.ToolStart(call.id, call.name, call.arguments))
                        val tool = tools.execute(call.name, call.arguments)
                        val truncated = if (tool.output.length > MAX_TOOL_OUTPUT) {
                            tool.output.take(MAX_TOOL_OUTPUT) + "\n… output truncated"
                        } else {
                            tool.output
                        }
                        send(
                            ChatEvent.ToolResult(
                                id = call.id,
                                name = call.name,
                                output = truncated,
                                isError = tool.isError,
                            ),
                        )
                        turns = turns + ChatTurn(
                            role = Role.TOOL,
                            text = truncated,
                            toolCallId = call.id,
                            name = call.name,
                            isError = tool.isError,
                        )
                    }
                }
            }
        }
        send(ChatEvent.Failed(ChatFailure.Provider("Tool step limit reached.")))
    }

    private suspend fun collectTurn(
        providerId: String,
        model: String,
        system: String,
        turns: List<ChatTurn>,
        pinned: String?,
        emit: suspend (ChatEvent) -> Unit,
    ): TurnResult {
        val list = accounts.byProvider(providerId)
        if (list.isEmpty()) return TurnResult.Error(ChatFailure.NoAccount)
        val spec = ProviderCatalog.byId(providerId)
            ?: return TurnResult.Error(ChatFailure.Provider("Unknown provider."))
        val client = clients[spec.protocol]
            ?: return TurnResult.Error(ChatFailure.Provider("Protocol not supported."))
        val policy = accounts.policy(providerId)
        val mode = runCatching { RotationMode.valueOf(policy.mode) }
            .getOrDefault(RotationMode.FAILOVER)
        val max = if (pinned == null && policy.autoFailover) policy.maxSwitches.coerceIn(0, 5) else 0
        val excluded = mutableSetOf<String>()
        var last: ProviderFailure? = null

        for (attempt in 0..max) {
            val account = if (pinned != null) {
                list.firstOrNull { it.id == pinned && it.enabled }
            } else {
                selector.choose(list, policy, excluded, clock.now())
            }
            if (account == null) return TurnResult.Error(ChatFailure.NoAccount)
            val key = accounts.key(account) ?: return TurnResult.Error(ChatFailure.KeyDecrypt)
            if (pinned == null && !account.active) accounts.setActive(account.id)
            accounts.markUsed(account.id)
            emit(ChatEvent.Account(account.id, account.label))

            val request = ChatRequest(
                model = model,
                system = system,
                turns = turns,
                tools = tools.specs(),
            )
            val calls = mutableMapOf<Int, PartialCall>()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var done = false
            var failure: ProviderFailure? = null

            try {
                client.stream(account, key, request).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            text.append(event.text)
                            emit(ChatEvent.Delta(event.text))
                        }

                        is StreamEvent.Reasoning -> {
                            reasoning.append(event.text)
                            emit(ChatEvent.Reasoning(event.text))
                        }

                        is StreamEvent.ToolCallDelta -> {
                            val call = calls.getOrPut(event.index) { PartialCall() }
                            if (!event.id.isNullOrBlank()) call.id = event.id
                            if (!event.name.isNullOrBlank()) call.name = event.name
                            if (event.arguments != null) call.arguments.append(event.arguments)
                        }

                        is StreamEvent.Finish -> Unit
                        StreamEvent.Done -> done = true
                        is StreamEvent.Failure -> failure = event.error
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = ProviderFailure(error.message ?: "Network error", retryable = true)
            }

            val output = calls.toSortedMap().mapNotNull { (index, call) ->
                if (call.name.isBlank()) {
                    null
                } else {
                    ChatToolCall(
                        id = call.id.ifBlank { "call_${clock.now()}_$index" },
                        name = call.name,
                        arguments = call.arguments.toString().ifBlank { "{}" },
                    )
                }
            }
            val hasText = text.isNotBlank() || reasoning.isNotBlank()
            if (failure == null && (done || hasText || output.isNotEmpty())) {
                accounts.success(account.id)
                if (pinned == null && mode == RotationMode.NEXT) accounts.next(providerId)
                return TurnResult.Ok(text.toString(), reasoning.toString(), output)
            }

            val reason = failure ?: ProviderFailure(
                "The stream ended without a response.",
                retryable = !hasText,
            )
            accounts.failure(account.id, reason, policy)
            last = reason
            if (pinned != null || !policy.autoFailover || hasText || !reason.retryable || attempt == max) {
                return TurnResult.Error(ChatFailure.Provider(reason.message, reason.status))
            }
            excluded += account.id
        }

        return TurnResult.Error(
            ChatFailure.Provider(last?.message ?: "No account is available."),
        )
    }

    private data class PartialCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private sealed interface TurnResult {
        data class Ok(
            val text: String,
            val reasoning: String,
            val calls: List<ChatToolCall>,
        ) : TurnResult

        data class Error(val failure: ChatFailure) : TurnResult
    }

    private companion object {
        const val MAX_TOOL_OUTPUT = 12_000
    }
}
