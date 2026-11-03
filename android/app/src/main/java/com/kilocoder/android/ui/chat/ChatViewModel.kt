package com.kilocoder.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kilocoder.android.data.agent.AgentRepository
import com.kilocoder.android.data.db.SessionEntity
import com.kilocoder.android.data.db.SessionMessageEntity
import com.kilocoder.android.data.provider.ChatToolCall
import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.Role
import com.kilocoder.android.data.repo.ChatEvent
import com.kilocoder.android.data.repo.ChatFailure
import com.kilocoder.android.data.repo.SessionRepository
import com.kilocoder.android.data.workspace.WorkspaceRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val reasoning: String = "",
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
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
    val sessionId: String? = null,
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val streaming: Boolean = false,
    val accountLabel: String? = null,
    val error: ChatUiError? = null,
    val compacted: Boolean = false,
)

class ChatViewModel(
    private val agent: AgentRepository,
    private val sessions: SessionRepository,
    private val workspace: WorkspaceRepository,
) : ViewModel() {
    private val state = MutableStateFlow(ChatUiState())
    val stream: StateFlow<ChatUiState> = state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            sessions.observeSessions()
                .catch { error ->
                    state.update {
                        it.copy(error = ChatUiError.Provider(error.message ?: "Could not load sessions."))
                    }
                }
                .collect { list ->
                    state.update { it.copy(sessions = list) }
                }
        }
    }

    fun selectProvider(providerId: String) {
        val spec = ProviderCatalog.byId(providerId) ?: return
        stop()
        state.update {
            it.copy(
                providerId = spec.id,
                model = spec.defaultModel,
                pinned = null,
                sessionId = null,
                messages = emptyList(),
                input = "",
                accountLabel = null,
                error = null,
                compacted = false,
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
        val user = ChatMessage(UUID.randomUUID().toString(), ChatRole.USER, text)
        val assistant = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            text = "",
            streaming = true,
        )
        val history = (snapshot.messages + user).toTurns()
        val first = snapshot.messages.none { it.role == ChatRole.USER }

        state.update {
            it.copy(
                messages = it.messages + user + assistant,
                input = "",
                streaming = true,
                accountLabel = null,
                error = null,
                compacted = false,
            )
        }

        job = viewModelScope.launch {
            var sessionId = snapshot.sessionId
            if (sessionId == null) {
                sessionId = sessions.create(
                    providerId = snapshot.providerId,
                    model = model,
                    pinned = snapshot.pinned,
                ).id
                state.update { it.copy(sessionId = sessionId) }
            }
            sessions.append(sessionId, "USER", text)
            sessions.updateSession(
                id = sessionId,
                providerId = snapshot.providerId,
                model = model,
                pinned = snapshot.pinned,
                title = if (first) title(text) else null,
            )

            try {
                agent.stream(
                    providerId = snapshot.providerId,
                    model = model,
                    system = systemPrompt(),
                    history = history,
                    pinned = snapshot.pinned,
                ).collect { event ->
                    handle(event, sessionId, assistant.id)
                }
                if (state.value.streaming) complete(sessionId, assistant.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail(
                    sessionId = sessionId,
                    assistantId = assistant.id,
                    failure = ChatFailure.Provider(error.message ?: "Network error"),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        val current = state.value
        val sessionId = current.sessionId
        val assistant = current.messages.lastOrNull {
            it.role == ChatRole.ASSISTANT && it.streaming
        }
        val messages = if (assistant != null) {
            splitAssistant(current.messages, assistant.id)
        } else {
            current.messages
        }
        state.update { snapshot ->
            snapshot.copy(
                streaming = false,
                messages = messages.map { message ->
                    if (message.streaming) message.copy(streaming = false) else message
                },
            )
        }
        if (sessionId != null && assistant != null) {
            viewModelScope.launch { persistTurn(sessionId, assistant.id, messages) }
        }
    }

    fun newChat() {
        stop()
        state.update {
            it.copy(
                sessionId = null,
                messages = emptyList(),
                input = "",
                accountLabel = null,
                error = null,
                compacted = false,
            )
        }
    }

    fun loadSession(id: String) {
        job?.cancel()
        job = null
        viewModelScope.launch {
            val snapshot = sessions.load(id) ?: return@launch
            state.update {
                it.copy(
                    providerId = snapshot.session.providerId,
                    model = snapshot.session.model,
                    pinned = snapshot.session.pinnedAccountId,
                    sessionId = snapshot.session.id,
                    messages = snapshot.messages.map(::toMessage),
                    input = "",
                    streaming = false,
                    accountLabel = null,
                    error = null,
                    compacted = snapshot.session.summary != null,
                )
            }
        }
    }

    fun deleteSession(id: String) {
        if (state.value.sessionId == id) {
            job?.cancel()
            job = null
        }
        viewModelScope.launch {
            sessions.delete(id)
            if (state.value.sessionId == id) {
                state.update {
                    it.copy(
                        sessionId = null,
                        messages = emptyList(),
                        input = "",
                        streaming = false,
                        accountLabel = null,
                        error = null,
                        compacted = false,
                    )
                }
            }
        }
    }

    private suspend fun handle(event: ChatEvent, sessionId: String, assistantId: String) {
        when (event) {
            is ChatEvent.Account -> {
                state.update { it.copy(accountLabel = event.label) }
            }

            is ChatEvent.Delta -> append(assistantId, event.text, reasoning = false)
            is ChatEvent.Reasoning -> append(assistantId, event.text, reasoning = true)
            is ChatEvent.ToolStart -> {
                val current = state.value.messages.firstOrNull { it.id == assistantId } ?: return
                if (current.toolCalls.any { it.id == event.id }) return
                val call = ChatToolCall(event.id, event.name, event.arguments)
                val updated = current.copy(toolCalls = current.toolCalls + call)
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.TOOL,
                    text = "",
                    toolCallId = event.id,
                    toolName = event.name,
                    streaming = true,
                )
                state.update { snapshot ->
                    snapshot.copy(
                        messages = snapshot.messages.map {
                            if (it.id == assistantId) updated else it
                        } + message,
                    )
                }
            }

            is ChatEvent.ToolResult -> {
                state.update { snapshot ->
                    snapshot.copy(
                        messages = snapshot.messages.map { message ->
                            if (message.role == ChatRole.TOOL &&
                                message.toolCallId == event.id &&
                                message.streaming
                            ) {
                                message.copy(
                                    text = event.output,
                                    isError = event.isError,
                                    streaming = false,
                                )
                            } else {
                                message
                            }
                        },
                    )
                }
            }

            is ChatEvent.Compacted -> {
                state.update { it.copy(compacted = true) }
                sessions.updateSession(
                    id = sessionId,
                    providerId = state.value.providerId,
                    model = state.value.model,
                    pinned = state.value.pinned,
                    summary = event.summary,
                )
            }

            ChatEvent.Completed -> complete(sessionId, assistantId)
            is ChatEvent.Failed -> fail(sessionId, assistantId, event.error)
        }
    }

    private suspend fun complete(sessionId: String, assistantId: String) {
        state.update { snapshot ->
            snapshot.copy(
                streaming = false,
                messages = splitAssistant(snapshot.messages, assistantId).map { message ->
                    if (message.streaming) message.copy(streaming = false) else message
                },
            )
        }
        persistTurn(sessionId, assistantId)
    }

    private suspend fun fail(sessionId: String, assistantId: String, failure: ChatFailure) {
        val error = when (failure) {
            ChatFailure.NoAccount -> ChatUiError.NoAccount
            ChatFailure.KeyDecrypt -> ChatUiError.Key
            is ChatFailure.Provider -> ChatUiError.Provider(failure.message)
        }
        state.update { snapshot ->
            snapshot.copy(
                streaming = false,
                error = error,
                messages = splitAssistant(snapshot.messages, assistantId).map { message ->
                    if (message.streaming) message.copy(streaming = false) else message
                },
            )
        }
        persistTurn(sessionId, assistantId)
    }

    private suspend fun persistTurn(
        sessionId: String,
        assistantId: String,
        messages: List<ChatMessage> = state.value.messages,
    ) {
        val start = messages.indexOfFirst { it.id == assistantId }
        if (start < 0) return
        try {
            messages.drop(start).forEach { message ->
                when (message.role) {
                    ChatRole.ASSISTANT -> {
                        if (message.text.isNotBlank() ||
                            message.reasoning.isNotBlank() ||
                            message.toolCalls.isNotEmpty()
                        ) {
                            sessions.append(
                                sessionId = sessionId,
                                role = "ASSISTANT",
                                content = message.text,
                                reasoning = message.reasoning,
                                toolCalls = message.toolCalls,
                                id = message.id,
                            )
                        }
                    }

                    ChatRole.TOOL -> {
                        if (message.toolCallId != null) {
                            sessions.append(
                                sessionId = sessionId,
                                role = "TOOL",
                                content = message.text,
                                toolCallId = message.toolCallId,
                                toolName = message.toolName,
                                isError = message.isError,
                                id = message.id,
                            )
                        }
                    }

                    ChatRole.USER -> Unit
                }
            }
        } catch (error: Exception) {
            state.update {
                it.copy(
                    error = ChatUiError.Provider(
                        error.message ?: "Could not save the session.",
                    ),
                )
            }
        }
    }

    private fun splitAssistant(
        messages: List<ChatMessage>,
        assistantId: String,
    ): List<ChatMessage> {
        val source = messages.firstOrNull { it.id == assistantId } ?: return messages
        if (source.toolCalls.isEmpty() ||
            (source.text.isBlank() && source.reasoning.isBlank())
        ) {
            return messages
        }
        val answer = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            text = source.text,
            reasoning = source.reasoning,
        )
        return messages.map { message ->
            if (message.id == assistantId) {
                message.copy(text = "", reasoning = "")
            } else {
                message
            }
        } + answer
    }

    private fun append(id: String, text: String, reasoning: Boolean) {
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

    private fun toMessage(entity: SessionMessageEntity): ChatMessage = ChatMessage(
        id = entity.id,
        role = when (entity.role) {
            "USER" -> ChatRole.USER
            "ASSISTANT" -> ChatRole.ASSISTANT
            else -> ChatRole.TOOL
        },
        text = entity.content,
        reasoning = entity.reasoning,
        toolCalls = SessionRepository.decodeCalls(entity.toolCallsJson),
        toolCallId = entity.toolCallId,
        toolName = entity.toolName,
        isError = entity.isError,
    )

    private fun List<ChatMessage>.toTurns(): List<ChatTurn> =
        filter { it.text.isNotBlank() || it.toolCalls.isNotEmpty() }
            .map { message ->
                when (message.role) {
                    ChatRole.USER -> ChatTurn(Role.USER, message.text)
                    ChatRole.ASSISTANT -> ChatTurn(
                        role = Role.ASSISTANT,
                        text = message.text,
                        toolCalls = message.toolCalls,
                    )

                    ChatRole.TOOL -> ChatTurn(
                        role = Role.TOOL,
                        text = message.text,
                        toolCallId = message.toolCallId,
                        name = message.toolName,
                        isError = message.isError,
                    )
                }
            }

    private fun title(text: String): String =
        text.lineSequence().firstOrNull()?.trim()?.take(60)?.ifBlank { "New chat" } ?: "New chat"

    private fun systemPrompt(): String = """
        You are KiloCoder, a coding agent running on Android.
        Workspace root: ${workspace.rootPath}
        Use the provided tools to inspect and edit files. Paths are relative to the workspace.
        Read files before changing them and keep changes focused.
        There is no shell or build tool on the device; builds run on GitHub Actions later.
        Reply in the user's language. Use Markdown code fences for code.
    """.trimIndent()
}
