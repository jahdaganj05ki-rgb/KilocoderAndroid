package com.kilocoder.android.data.provider

import com.kilocoder.android.data.db.AccountEntity
import kotlinx.serialization.json.buildJsonObject
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
        val body = buildJsonObject {
            if (request.system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", request.system) })
                    }
                }
            }
            putJsonArray("contents") {
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("role", if (turn.role == Role.USER) "user" else "model")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", turn.text) })
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
}
