package com.kilocoder.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "github_config")
data class GitHubConfigEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String,
    val repo: String,
    val branch: String,
    val workflow: String,
    val tokenCipher: String,
    val tokenLast4: String,
    val login: String,
    val updatedAt: Long,
)
