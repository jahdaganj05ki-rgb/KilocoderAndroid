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
        assertTrue(events.contains(StreamEvent.Text("Hallo")))
        assertTrue(events.any { it is StreamEvent.Finish && it.reason == "STOP" })
    }

    @Test
    fun openAiReadsToolCall() {
        val events = OpenAiParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}]},"finish_reason":"tool_calls"}]}""",
        )
        val call = events.filterIsInstance<StreamEvent.ToolCallDelta>().single()
        assertEquals("call_1", call.id)
        assertEquals("read_file", call.name)
        assertTrue(events.any { it is StreamEvent.Finish && it.reason == "tool_calls" })
    }

    @Test
    fun anthropicReadsToolStartAndJsonDelta() {
        val start = AnthropicParser.parse(
            "content_block_start",
            """{"index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file"}}""",
        ).single() as StreamEvent.ToolCallDelta
        assertEquals("toolu_1", start.id)
        assertEquals("read_file", start.name)

        val delta = AnthropicParser.parse(
            "content_block_delta",
            """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
        ).single() as StreamEvent.ToolCallDelta
        assertEquals("{\"path\":", delta.arguments)
    }

    @Test
    fun geminiReadsFunctionCall() {
        val events = GeminiParser.parse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"list_files","args":{"path":"."}}}]},"finishReason":"STOP"}]}""",
        )
        val call = events.filterIsInstance<StreamEvent.ToolCallDelta>().single()
        assertEquals("list_files", call.name)
        assertTrue(call.arguments!!.contains("path"))
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
