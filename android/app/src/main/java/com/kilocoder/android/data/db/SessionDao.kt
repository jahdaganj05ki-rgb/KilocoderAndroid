package com.kilocoder.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM session_messages WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun messages(sessionId: String): List<SessionMessageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM session_messages WHERE sessionId = :sessionId")
    suspend fun maxPosition(sessionId: String): Int

    @Insert
    suspend fun insertMessage(message: SessionMessageEntity)

    @Update
    suspend fun updateMessage(message: SessionMessageEntity)

    @Query("UPDATE session_messages SET content = :content, reasoning = :reasoning, toolCallsJson = :toolCalls WHERE id = :id")
    suspend fun updateContent(id: String, content: String, reasoning: String, toolCalls: String?)
}
