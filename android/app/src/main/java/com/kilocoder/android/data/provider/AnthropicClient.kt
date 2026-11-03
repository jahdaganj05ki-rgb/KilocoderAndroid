package com.kilocoder.android.data.provider

import com.kilocoder.android.data.db.AccountEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens.coerceAtLeast(1))
            put("stream", stream)
            if (request.system.isNotBlank()) put("system", request.system)
            putJsonArray("messages") {
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("role", if (turn.role == Role.USER) "user" else "assistant")
                            put("content", turn.text)
                        },
                    )
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
}
