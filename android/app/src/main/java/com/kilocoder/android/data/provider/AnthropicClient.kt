package com.kilocoder.android.data.provider

import com.kilocoder.android.data.db.AccountEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class AnthropicClient(http: OkHttpClient) : BaseSseClient(http) {
    override fun buildRequest(
        account: AccountEntity,
        key: String,
        request: ChatRequest,
        stream: Boolean,
    ): Request {
        val base = ProviderHttp.baseUrl(account)
        require(base.isNotBlank()) { "Base-URL fehlt." }
        val url = ProviderHttp.endpoint(base, "/messages")
        val messages = messages(request.turns)
        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens.coerceAtLeast(1))
            put("stream", stream)
            if (request.system.isNotBlank()) put("system", request.system)
            putJsonArray("messages") {
                messages.forEach { add(it) }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { spec ->
                        add(
                            buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("input_schema", spec.parameters)
                            },
                        )
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url(url)
            .post(ProviderHttp.body(body))
            .header("anthropic-version", "2023-06-01")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        return ProviderHttp.auth(builder, account, key).build()
    }

    override fun parse(type: String?, data: String): List<StreamEvent> = AnthropicParser.parse(type, data)

    private fun messages(turns: List<ChatTurn>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val results = mutableListOf<JsonObject>()

        fun flushResults() {
            if (results.isEmpty()) return
            out += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    results.forEach { add(it) }
                }
            }
            results.clear()
        }

        turns.forEach { turn ->
            when (turn.role) {
                Role.TOOL -> {
                    results += buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", turn.toolCallId.orEmpty())
                        put("content", turn.text)
                        if (turn.isError) put("is_error", true)
                    }
                }

                Role.USER -> {
                    flushResults()
                    out += buildJsonObject {
                        put("role", "user")
                        put("content", turn.text)
                    }
                }

                Role.ASSISTANT -> {
                    flushResults()
                    out += buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            if (turn.text.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", turn.text)
                                    },
                                )
                            }
                            turn.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", args(call.arguments))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        flushResults()
        return out
    }

    private fun args(raw: String): JsonObject =
        runCatching { ProviderHttp.json.parseToJsonElement(raw).jsonObject }
            .getOrElse { buildJsonObject { } }
}
