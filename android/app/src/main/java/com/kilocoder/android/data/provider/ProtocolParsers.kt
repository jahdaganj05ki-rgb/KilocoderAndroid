package com.kilocoder.android.data.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object OpenAiParser {
    fun parse(data: String): List<StreamEvent> {
        val root = try {
            ProviderHttp.json.parseToJsonElement(data).jsonObjectOrNull() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val error = root["error"] as? JsonObject
        if (error != null) {
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Provider-Fehler"
            return listOf(StreamEvent.Failure(ProviderFailure(message, retryable = false)))
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += StreamEvent.Reasoning(it) }
            (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += StreamEvent.Reasoning(it) }
            (delta["content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += StreamEvent.Text(it) }
            (delta["tool_calls"] as? JsonArray)?.forEach { item ->
                val call = item as? JsonObject ?: return@forEach
                val index = (call["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                val id = (call["id"] as? JsonPrimitive)?.contentOrNull
                val function = call["function"] as? JsonObject
                val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull
                val arguments = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
                out += StreamEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = name,
                    arguments = arguments,
                )
            }
        }
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish != null) out += StreamEvent.Finish(finish)
        return out
    }
}

object AnthropicParser {
    fun parse(type: String?, data: String): List<StreamEvent> {
        val root = try {
            ProviderHttp.json.parseToJsonElement(data).jsonObjectOrNull() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val event = type ?: (root["type"] as? JsonPrimitive)?.contentOrNull
        val index = (root["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return when (event) {
            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject ?: return emptyList()
                if ((block["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") {
                    return emptyList()
                }
                val id = (block["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (block["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                listOf(StreamEvent.ToolCallDelta(index = index, id = id, name = name, arguments = ""))
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val kind = (delta?.get("type") as? JsonPrimitive)?.contentOrNull
                if (kind == "input_json_delta") {
                    val partial = (delta["partial_json"] as? JsonPrimitive)?.contentOrNull
                    if (partial.isNullOrEmpty()) emptyList() else {
                        listOf(StreamEvent.ToolCallDelta(index = index, arguments = partial))
                    }
                } else {
                    val text = (delta?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (text.isNullOrEmpty()) emptyList() else listOf(StreamEvent.Text(text))
                }
            }

            "message_delta" -> {
                val stop = ((root["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)
                    ?.contentOrNull
                if (stop == null) emptyList() else listOf(StreamEvent.Finish(stop))
            }

            "message_stop" -> listOf(StreamEvent.Done)

            "error" -> {
                val error = root["error"] as? JsonObject
                val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Anthropic-Fehler"
                val kind = (error?.get("type") as? JsonPrimitive)?.contentOrNull
                val retry = kind != "invalid_request_error" && kind != "not_found_error"
                listOf(StreamEvent.Failure(ProviderFailure(message, retryable = retry)))
            }

            else -> emptyList()
        }
    }
}

object GeminiParser {
    fun parse(data: String): List<StreamEvent> {
        val root = try {
            ProviderHttp.json.parseToJsonElement(data).jsonObjectOrNull() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val error = root["error"] as? JsonObject
        if (error != null) {
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "Gemini-Fehler"
            return listOf(StreamEvent.Failure(ProviderFailure(message, retryable = true)))
        }
        val block = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
        if (block != null) {
            return listOf(StreamEvent.Failure(ProviderFailure("Anfrage blockiert: $block", retryable = false)))
        }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "SAFETY" || finish == "RECITATION") {
            return listOf(StreamEvent.Failure(ProviderFailure("Antwort blockiert: $finish", retryable = false)))
        }
        val out = mutableListOf<StreamEvent>()
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
        parts?.forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) {
                out += StreamEvent.Text(text)
            }
            val call = obj["functionCall"] as? JsonObject
            if (call != null) {
                val name = (call["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val args = call["args"]
                out += StreamEvent.ToolCallDelta(
                    index = index,
                    id = "gemini-${System.nanoTime()}-$index",
                    name = name,
                    arguments = args?.toString() ?: "{}",
                )
            }
        }
        if (finish != null) out += StreamEvent.Finish(finish)
        return out
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
