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
import java.net.URLEncoder

class GeminiClient(http: OkHttpClient) : BaseSseClient(http) {
    override fun buildRequest(
        account: AccountEntity,
        key: String,
        request: ChatRequest,
        stream: Boolean,
    ): Request {
        val base = ProviderHttp.baseUrl(account)
        require(base.isNotBlank()) { "Base-URL fehlt." }
        val model = URLEncoder.encode(request.model, "UTF-8")
        val suffix = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        val url = "${base.trimEnd('/')}/models/$model$suffix"
        val contents = contents(request.turns)
        val body = buildJsonObject {
            if (request.system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", request.system) })
                    }
                }
            }
            putJsonArray("contents") {
                contents.forEach { add(it) }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                request.tools.forEach { spec ->
                                    add(
                                        buildJsonObject {
                                            put("name", spec.name)
                                            put("description", spec.description)
                                            put("parameters", spec.parameters)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens.coerceAtLeast(1))
            }
        }
        val builder = Request.Builder()
            .url(url)
            .post(ProviderHttp.body(body))
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        return ProviderHttp.auth(builder, account, key).build()
    }

    override fun parse(type: String?, data: String): List<StreamEvent> = GeminiParser.parse(data)

    private fun contents(turns: List<ChatTurn>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val results = mutableListOf<JsonObject>()

        fun flushResults() {
            if (results.isEmpty()) return
            out += buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    results.forEach { add(it) }
                }
            }
            results.clear()
        }

        turns.forEach { turn ->
            when (turn.role) {
                Role.TOOL -> {
                    results += buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", turn.name.orEmpty())
                            putJsonObject("response") {
                                put(
                                    "content",
                                    if (turn.isError) "ERROR: ${turn.text}" else turn.text,
                                )
                            }
                        }
                    }
                }

                Role.USER -> {
                    flushResults()
                    out += buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", turn.text) })
                        }
                    }
                }

                Role.ASSISTANT -> {
                    flushResults()
                    out += buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (turn.text.isNotBlank()) {
                                add(buildJsonObject { put("text", turn.text) })
                            }
                            turn.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", call.name)
                                            put("args", args(call.arguments))
                                        }
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
