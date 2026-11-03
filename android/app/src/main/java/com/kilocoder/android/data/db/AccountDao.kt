package com.kilocoder.android.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY providerId ASC, position ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE providerId = :providerId ORDER BY position ASC")
    suspend fun byProvider(providerId: String): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM accounts WHERE providerId = :providerId")
    suspend fun maxPosition(providerId: String): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE providerId = :providerId")
    suspend fun count(providerId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE accounts SET active = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("UPDATE accounts SET active = 0 WHERE providerId = :providerId")
    suspend fun clearActive(providerId: String)

    @Query("UPDATE accounts SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    @Query("UPDATE accounts SET requestCount = requestCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun markUsed(id: String, now: Long)

    @Query("UPDATE accounts SET successCount = successCount + 1, consecutiveFailures = 0, cooldownUntil = 0, lastError = NULL WHERE id = :id")
    suspend fun markSuccess(id: String)

    @Query("UPDATE accounts SET failureCount = failureCount + 1, consecutiveFailures = consecutiveFailures + 1, cooldownUntil = :cooldownUntil, lastError = :error WHERE id = :id")
    suspend fun markFailure(id: String, cooldownUntil: Long, error: String)

    @Query("UPDATE accounts SET cooldownUntil = 0, consecutiveFailures = 0, lastError = NULL WHERE id = :id")
    suspend fun clearCooldown(id: String)

    @Query("DELETE FROM accounts")
    suspend fun clear()
}
