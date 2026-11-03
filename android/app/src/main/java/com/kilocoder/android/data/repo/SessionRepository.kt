package com.kilocoder.android.data.repo

import androidx.room.withTransaction
import com.kilocoder.android.data.db.AppDatabase
import com.kilocoder.android.data.db.SessionEntity
import com.kilocoder.android.data.db.SessionMessageEntity
import com.kilocoder.android.data.provider.ChatToolCall
import com.kilocoder.android.data.provider.ProviderHttp
import com.kilocoder.android.domain.Clock
import com.kilocoder.android.domain.WallClock
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class SessionSnapshot(
    val session: SessionEntity,
    val messages: List<SessionMessageEntity>,
)

class SessionRepository(
    private val db: AppDatabase,
    private val clock: Clock = WallClock,
) {
    fun observeSessions() = db.sessions().observeAll()

    suspend fun create(providerId: String, model: String, pinned: String?): SessionEntity {
        val now = clock.now()
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            providerId = providerId,
            model = model,
            pinnedAccountId = pinned,
            summary = null,
            createdAt = now,
            updatedAt = now,
        )
        db.sessions().insert(session)
        return session
    }

    suspend fun load(id: String): SessionSnapshot? {
        val session = db.sessions().byId(id) ?: return null
        return SessionSnapshot(session, db.sessions().messages(id))
    }

    suspend fun append(
        sessionId: String,
        role: String,
        content: String,
        reasoning: String = "",
        toolCalls: List<ChatToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        isError: Boolean = false,
        id: String = UUID.randomUUID().toString(),
    ): SessionMessageEntity = db.withTransaction {
        val session = db.sessions().byId(sessionId)
            ?: throw IllegalStateException("Session not found.")
        val message = SessionMessageEntity(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            reasoning = reasoning,
            toolCallsJson = encodeCalls(toolCalls),
            toolCallId = toolCallId,
            toolName = toolName,
            isError = isError,
            createdAt = clock.now(),
            position = db.sessions().maxPosition(sessionId) + 1,
        )
        db.sessions().insertMessage(message)
        db.sessions().update(session.copy(updatedAt = clock.now()))
        message
    }

    suspend fun updateMessage(
        id: String,
        content: String,
        reasoning: String,
        toolCalls: List<ChatToolCall>,
    ) {
        db.sessions().updateContent(id, content, reasoning, encodeCalls(toolCalls))
    }

    suspend fun updateSession(
        id: String,
        providerId: String,
        model: String,
        pinned: String?,
        title: String? = null,
        summary: String? = null,
    ) {
        val session = db.sessions().byId(id) ?: return
        db.sessions().update(
            session.copy(
                providerId = providerId,
                model = model,
                pinnedAccountId = pinned,
                title = title ?: session.title,
                summary = summary ?: session.summary,
                updatedAt = clock.now(),
            ),
        )
    }

    suspend fun delete(id: String) {
        db.sessions().delete(id)
    }

    companion object {
        fun encodeCalls(calls: List<ChatToolCall>): String? =
            if (calls.isEmpty()) null else ProviderHttp.json.encodeToString(calls)

        fun decodeCalls(raw: String?): List<ChatToolCall> =
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching { ProviderHttp.json.decodeFromString<List<ChatToolCall>>(raw) }
                    .getOrDefault(emptyList())
            }
    }
}
