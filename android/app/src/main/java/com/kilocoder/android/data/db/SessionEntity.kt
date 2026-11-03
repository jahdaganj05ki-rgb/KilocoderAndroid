package com.kilocoder.android.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String,
    val model: String,
    val pinnedAccountId: String?,
    val summary: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "session_messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val reasoning: String,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val isError: Boolean,
    val createdAt: Long,
    val position: Int,
)
