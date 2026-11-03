package com.kilocoder.android.data.agent

import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.Role

data class CompactedContext(
    val turns: List<ChatTurn>,
    val summary: String?,
    val removed: Int,
)

class ContextCompactor(
    private val maxChars: Int = 28_000,
    private val keepTurns: Int = 10,
    private val summaryChars: Int = 8_000,
) {
    fun compact(history: List<ChatTurn>): CompactedContext {
        val size = history.sumOf { turn ->
            turn.text.length + turn.toolCalls.sumOf { it.arguments.length }
        }
        if (size <= maxChars || history.size <= keepTurns) {
            return CompactedContext(history, null, 0)
        }
        var cut = history.size - keepTurns
        while (cut > 0 && history[cut].role == Role.TOOL) cut--
        if (cut <= 0) return CompactedContext(history, null, 0)
        val older = history.take(cut)
        val recent = history.drop(cut)
        val summary = buildString {
            appendLine("Earlier conversation summary:")
            for (turn in older) {
                val role = when (turn.role) {
                    Role.USER -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.TOOL -> "tool"
                }
                appendLine("$role: ${turn.text.replace('\n', ' ').take(600)}")
                if (turn.toolCalls.isNotEmpty()) {
                    appendLine("  tools: ${turn.toolCalls.joinToString { it.name }}")
                }
                if (length >= summaryChars) break
            }
        }.take(summaryChars)
        val head = ChatTurn(Role.USER, "[Earlier context]\n$summary")
        return CompactedContext(listOf(head) + recent, summary, older.size)
    }
}
