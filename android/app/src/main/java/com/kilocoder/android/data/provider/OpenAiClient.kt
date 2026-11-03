package com.kilocoder.android.data.provider

import com.kilocoder.android.data.db.AccountEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class OpenAiClient(http: OkHttpClient) : BaseSseClient(http) {
    override fun buildRequest(
        account: AccountEntity,
        key: String,
        request: ChatRequest,
        stream: Boolean,
    ): Request {
        val base = ProviderHttp.baseUrl(account)
        require(base.isNotBlank()) { "Base-URL fehlt." }
        val url = ProviderHttp.endpoint(base, "/chat/completions")
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", stream)
            putJsonArray("messages") {
                if (request.system.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", request.system)
                        },
                    )
                }
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            when (turn.role) {
                                Role.USER -> {
                                    put("role", "user")
                                    put("content", turn.text)
                                }

                                Role.ASSISTANT -> {
                                    put("role", "assistant")
                                    if (turn.text.isNotEmpty()) {
                                        put("content", turn.text)
                                    } else {
                                        put("content", JsonNull)
                                    }
                                    if (turn.toolCalls.isNotEmpty()) {
                                        putJsonArray("tool_calls") {
                                            turn.toolCalls.forEach { call ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", call.id)
                                                        put("type", "function")
                                                        putJsonObject("function") {
                                                            put("name", call.name)
                                                            put("arguments", call.arguments)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                Role.TOOL -> {
                                    put("role", "tool")
                                    put("tool_call_id", turn.toolCallId.orEmpty())
                                    put("content", turn.text)
                                }
                            }
                        },
                    )
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { spec ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", spec.name)
                                    put("description", spec.description)
                                    put("parameters", spec.parameters)
                                }
                            },
                        )
                    }
                }
                put("tool_choice", "auto")
            }
            if (request.maxTokens > 0) {
                val modern = account.providerId == "openai" || account.providerId == "azure"
                val field = if (modern && (request.model.startsWith("o") || request.model.startsWith("gpt-5"))) {
                    "max_completion_tokens"
                } else {
                    "max_tokens"
                }
                put(field, request.maxTokens)
            }
        }
        val builder = Request.Builder()
            .url(url)
            .post(ProviderHttp.body(body))
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        return ProviderHttp.auth(builder, account, key).build()
    }

    override fun parse(type: String?, data: String): List<StreamEvent> = OpenAiParser.parse(data)
}
