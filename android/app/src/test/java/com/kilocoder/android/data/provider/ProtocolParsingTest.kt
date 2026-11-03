package com.kilocoder.android.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParsingTest {
    @Test
    fun openAiReadsTextDelta() {
        val events = OpenAiParser.parse("""{"choices":[{"delta":{"content":"Hallo"}}]}""")
        assertEquals(listOf(StreamEvent.Text("Hallo")), events)
    }

    @Test
    fun openAiReadsReasoningDelta() {
        val events = OpenAiParser.parse("""{"choices":[{"delta":{"reasoning_content":"Denke"}}]}""")
        assertEquals(listOf(StreamEvent.Reasoning("Denke")), events)
    }

    @Test
    fun openAiErrorIsNotRetryable() {
        val events = OpenAiParser.parse("""{"error":{"message":"invalid api key"}}""")
        val failure = events.single() as StreamEvent.Failure
        assertEquals("invalid api key", failure.error.message)
        assertFalse(failure.error.retryable)
    }

    @Test
    fun anthropicReadsTextBlockDelta() {
        val events = AnthropicParser.parse(
            "content_block_delta",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hallo"}}""",
        )
        assertEquals(listOf(StreamEvent.Text("Hallo")), events)
    }

    @Test
    fun anthropicReadsMessageStop() {
        val events = AnthropicParser.parse("message_stop", """{"type":"message_stop"}""")
        assertEquals(listOf(StreamEvent.Done), events)
    }

    @Test
    fun anthropicOverloadedIsRetryable() {
        val events = AnthropicParser.parse(
            "error",
            """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""",
        )
        val failure = events.single() as StreamEvent.Failure
        assertTrue(failure.error.retryable)
    }

    @Test
    fun geminiReadsCandidateText() {
        val events = GeminiParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"Hallo"}]},"finishReason":"STOP"}]}""",
        )
        assertEquals(listOf(StreamEvent.Text("Hallo")), events)
    }

    @Test
    fun geminiSafetyBlockIsNotRetryable() {
        val events = GeminiParser.parse(
            """{"candidates":[{"finishReason":"SAFETY"}]}""",
        )
        val failure = events.single() as StreamEvent.Failure
        assertFalse(failure.error.retryable)
    }
}
