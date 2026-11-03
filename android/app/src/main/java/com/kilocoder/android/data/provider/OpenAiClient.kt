package com.kilocoder.android.data.provider

import com.kilocoder.android.data.db.AccountEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
                            put("role", if (turn.role == Role.USER) "user" else "assistant")
                            put("content", turn.text)
                        },
                    )
                }
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
