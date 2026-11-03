package com.kilocoder.android.data.agent

import com.kilocoder.android.data.provider.ChatToolCall
import com.kilocoder.android.data.provider.ChatTurn
import com.kilocoder.android.data.provider.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {
    @Test
    fun compactsLongHistoryAndKeepsRecentTurns() {
        val history = (0 until 30).map { index ->
            ChatTurn(
                role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
                text = "message $index " + "x".repeat(200),
            )
        }
        val result = ContextCompactor(maxChars = 1_000, keepTurns = 6).compact(history)

        assertNotNull(result.summary)
        assertTrue(result.summary!!.contains("message 0"))
        assertEquals(24, result.removed)
        assertEquals(7, result.turns.size)
        assertEquals("[Earlier context]", result.turns.first().text.lineSequence().first())
    }

    @Test
    fun keepsToolCallAttachedToItsResult() {
        val history = buildList {
            add(ChatTurn(Role.USER, "start"))
            repeat(4) { index ->
                add(
                    ChatTurn(
                        role = Role.ASSISTANT,
                        text = "assistant $index",
                        toolCalls = listOf(
                            ChatToolCall("call_$index", "read_file", "{}"),
                        ),
                    ),
                )
                add(
                    ChatTurn(
                        role = Role.TOOL,
                        text = "result $index",
                        toolCallId = "call_$index",
                        name = "read_file",
                    ),
                )
            }
            add(ChatTurn(Role.USER, "after"))
        }
        val result = ContextCompactor(maxChars = 10, keepTurns = 4).compact(history)

        assertNotNull(result.summary)
        assertEquals(Role.ASSISTANT, result.turns[1].role)
        assertTrue(result.turns[1].toolCalls.isNotEmpty())
        assertEquals(Role.TOOL, result.turns[2].role)
    }

    @Test
    fun doesNotCompactShortHistory() {
        val history = listOf(ChatTurn(Role.USER, "hello"))
        val result = ContextCompactor().compact(history)
        assertEquals(0, result.removed)
        assertEquals(history, result.turns)
    }
}
