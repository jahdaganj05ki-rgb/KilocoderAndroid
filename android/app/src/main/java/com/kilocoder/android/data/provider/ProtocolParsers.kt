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
        val delta = choice["delta"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += StreamEvent.Reasoning(it) }
        (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += StreamEvent.Reasoning(it) }
        (delta["content"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += StreamEvent.Text(it) }
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
        return when (event) {
            "content_block_delta" -> {
                val text = ((root["delta"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                if (text.isNullOrEmpty()) emptyList() else listOf(StreamEvent.Text(text))
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
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return emptyList()
        return parts.mapNotNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { StreamEvent.Text(it) }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
