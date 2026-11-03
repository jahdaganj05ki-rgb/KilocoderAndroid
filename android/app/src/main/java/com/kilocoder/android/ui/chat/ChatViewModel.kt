package com.kilocoder.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.Role
import com.kilocoder.android.data.repo.ChatEvent
import com.kilocoder.android.data.repo.ChatFailure
import com.kilocoder.android.data.repo.ChatRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val user: Boolean,
    val text: String,
    val reasoning: String = "",
    val streaming: Boolean = false,
)

sealed interface ChatUiError {
    data object NoAccount : ChatUiError
    data object Key : ChatUiError
    data class Provider(val message: String) : ChatUiError
}

data class ChatUiState(
    val providerId: String = ProviderCatalog.all.first().id,
    val model: String = ProviderCatalog.all.first().defaultModel,
    val pinned: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val streaming: Boolean = false,
    val accountLabel: String? = null,
    val error: ChatUiError? = null,
)

class ChatViewModel(
    private val repo: ChatRepository,
) : ViewModel() {
    private val ids = AtomicLong(0L)
    private val state = MutableStateFlow(ChatUiState())
    val stream: StateFlow<ChatUiState> = state.asStateFlow()
    private var job: Job? = null

    fun selectProvider(providerId: String) {
        val spec = ProviderCatalog.byId(providerId) ?: return
        stop()
        state.update {
            it.copy(
                providerId = spec.id,
                model = spec.defaultModel,
                pinned = null,
                messages = emptyList(),
                input = "",
                accountLabel = null,
                error = null,
            )
        }
    }

    fun setModel(model: String) {
        state.update { it.copy(model = model) }
    }

    fun pin(accountId: String?) {
        state.update { it.copy(pinned = accountId) }
    }

    fun setInput(input: String) {
        state.update { it.copy(input = input) }
    }

    fun dismissError() {
        state.update { it.copy(error = null) }
    }

    fun send() {
        val snapshot = state.value
        val text = snapshot.input.trim()
        if (text.isEmpty() || snapshot.streaming) return
        val spec = ProviderCatalog.byId(snapshot.providerId) ?: return
        val model = snapshot.model.trim().ifBlank { spec.defaultModel }
        val user = ChatMessage(id = ids.incrementAndGet(), user = true, text = text)
        val assistant = ChatMessage(id = ids.incrementAndGet(), user = false, text = "")
        val turns = (snapshot.messages + user)
            .filter { it.text.isNotBlank() }
            .map { ChatTurn(if (it.user) Role.USER else Role.ASSISTANT, it.text) }
        state.update {
            it.copy(
                messages = it.messages + user + assistant,
                input = "",
                streaming = true,
                accountLabel = null,
                error = null,
            )
        }
        job = viewModelScope.launch {
            try {
                repo.stream(
                    providerId = snapshot.providerId,
                    model = model,
                    system = SYSTEM,
                    turns = turns,
                    pinned = snapshot.pinned,
                ).collect { event ->
                    when (event) {
                        is ChatEvent.Account -> {
                            state.update { it.copy(accountLabel = event.label) }
                        }

                        is ChatEvent.Delta -> append(assistant.id, event.text, reasoning = false)
                        is ChatEvent.Reasoning -> append(assistant.id, event.text, reasoning = true)
                        ChatEvent.Completed -> finish(assistant.id)
                        is ChatEvent.Failed -> fail(assistant.id, event.error)
                    }
                }
                if (state.value.streaming) finish(assistant.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail(
                    assistant.id,
                    ChatFailure.Provider(error.message ?: "Network error"),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        state.update { current ->
            current.copy(
                streaming = false,
                messages = current.messages.map { message ->
                    if (message.streaming) message.copy(streaming = false) else message
                },
            )
        }
    }

    fun clear() {
        stop()
        state.update {
            it.copy(
                messages = emptyList(),
                input = "",
                accountLabel = null,
                error = null,
            )
        }
    }

    private fun append(id: Long, text: String, reasoning: Boolean) {
        state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id != id) {
                        message
                    } else if (reasoning) {
                        message.copy(reasoning = message.reasoning + text)
                    } else {
                        message.copy(text = message.text + text)
                    }
                },
            )
        }
    }

    private fun finish(id: Long) {
        state.update { current ->
            current.copy(
                streaming = false,
                messages = current.messages.map { message ->
                    if (message.id == id) message.copy(streaming = false) else message
                },
            )
        }
    }

    private fun fail(id: Long, failure: ChatFailure) {
        val error = when (failure) {
            ChatFailure.NoAccount -> ChatUiError.NoAccount
            ChatFailure.KeyDecrypt -> ChatUiError.Key
            is ChatFailure.Provider -> ChatUiError.Provider(failure.message)
        }
        state.update { current ->
            current.copy(
                streaming = false,
                error = error,
                messages = current.messages.map { message ->
                    if (message.id == id) message.copy(streaming = false) else message
                },
            )
        }
    }

    private companion object {
        const val SYSTEM =
            "You are KiloCoder, a concise coding assistant on Android. " +
                "Reply in the user's language. Use Markdown code fences for code."
    }
}
