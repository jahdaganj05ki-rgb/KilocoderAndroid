package com.kilocoder.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GitHubDao {
    @Query("SELECT * FROM github_config WHERE id = 1")
    fun observe(): Flow<GitHubConfigEntity?>

    @Query("SELECT * FROM github_config WHERE id = 1")
    suspend fun get(): GitHubConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(config: GitHubConfigEntity)

    @Query("DELETE FROM github_config")
    suspend fun clear()
}
