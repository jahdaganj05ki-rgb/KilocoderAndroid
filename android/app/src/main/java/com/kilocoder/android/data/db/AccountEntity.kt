package com.kilocoder.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index(value = ["providerId"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val label: String,
    val keyCipher: String,
    val keyLast4: String,
    val baseUrl: String,
    val authStyle: String,
    val defaultModel: String,
    val enabled: Boolean,
    val active: Boolean,
    val position: Int,
    val weight: Int,
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val consecutiveFailures: Int,
    val cooldownUntil: Long,
    val lastUsedAt: Long,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "provider_policies")
data class ProviderPolicyEntity(
    @PrimaryKey val providerId: String,
    val mode: String,
    val autoFailover: Boolean,
    val maxSwitches: Int,
    val cooldownSeconds: Int,
)
