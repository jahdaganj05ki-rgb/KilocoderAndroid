package com.kilocoder.android.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {
    @Query("SELECT * FROM provider_policies")
    fun observeAll(): Flow<List<ProviderPolicyEntity>>

    @Query("SELECT * FROM provider_policies WHERE providerId = :providerId")
    suspend fun byId(providerId: String): ProviderPolicyEntity?

    @Upsert
    suspend fun upsert(policy: ProviderPolicyEntity)

    @Query("DELETE FROM provider_policies")
    suspend fun clear()
}
