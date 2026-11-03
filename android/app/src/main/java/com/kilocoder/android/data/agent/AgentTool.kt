package com.kilocoder.android.data.agent

import com.kilocoder.android.data.provider.ChatToolSpec
import kotlinx.serialization.json.JsonObject

data class AgentToolResult(
    val output: String,
    val isError: Boolean = false,
)

interface AgentTool {
    val spec: ChatToolSpec
    suspend fun execute(args: JsonObject): AgentToolResult
}
