package com.kilocoder.android.data.provider

import android.os.SystemClock
import com.kilocoder.android.data.db.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

enum class Role {
    USER,
    ASSISTANT,
    TOOL,
}

@Serializable
data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatTurn(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val isError: Boolean = false,
)

data class ChatToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ChatRequest(
    val model: String,
    val system: String,
    val turns: List<ChatTurn>,
    val maxTokens: Int = 4096,
    val tools: List<ChatToolSpec> = emptyList(),
)

sealed interface StreamEvent {
    data class Text(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    ) : StreamEvent

    data class Finish(val reason: String?) : StreamEvent
    data object Done : StreamEvent
    data class Failure(val error: ProviderFailure) : StreamEvent
}

data class ProviderFailure(
    val message: String,
    val status: Int? = null,
    val retryable: Boolean = true,
    val retryAfterSeconds: Long? = null,
)

data class TestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long,
)

interface ProviderClient {
    fun stream(account: AccountEntity, key: String, request: ChatRequest): Flow<StreamEvent>
    suspend fun test(account: AccountEntity, key: String): TestResult
}

object ProviderHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val testClient: OkHttpClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun body(value: JsonObject) = value.toString().toRequestBody("application/json".toMediaType())

    fun baseUrl(account: AccountEntity): String =
        account.baseUrl.ifBlank { ProviderCatalog.byId(account.providerId)?.baseUrl.orEmpty() }

    fun endpoint(base: String, path: String, marker: String = path): String {
        val clean = base.trimEnd('/')
        if (clean.contains(marker)) return clean
        return clean + path
    }

    fun auth(builder: Request.Builder, account: AccountEntity, key: String): Request.Builder {
        when (runCatching { AuthStyle.valueOf(account.authStyle) }.getOrDefault(AuthStyle.BEARER)) {
            AuthStyle.BEARER -> builder.header("Authorization", "Bearer $key")
            AuthStyle.X_API_KEY -> builder.header("x-api-key", key)
            AuthStyle.API_KEY -> builder.header("api-key", key)
            AuthStyle.X_GOOG_API_KEY -> builder.header("x-goog-api-key", key)
        }
        return builder
    }

    fun extractError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]
            val message = when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            message ?: root["message"]?.jsonPrimitive?.contentOrNull ?: body.take(300)
        } catch (e: Exception) {
            body.take(300)
        }
    }

    fun failure(t: Throwable?, response: Response?): ProviderFailure {
        val status = response?.code
        val body = try {
            response?.body?.string().orEmpty()
        } catch (e: Exception) {
            ""
        }
        val message = extractError(body)
            ?: t?.message
            ?: if (status != null) "HTTP $status" else "Netzwerkfehler"
        val retryable = status == null ||
            status == 401 ||
            status == 403 ||
            status == 408 ||
            status == 409 ||
            status == 429 ||
            status >= 500
        return ProviderFailure(
            message = message,
            status = status,
            retryable = retryable,
            retryAfterSeconds = response?.header("Retry-After")?.toLongOrNull(),
        )
    }
}

abstract class BaseSseClient(protected val http: OkHttpClient) : ProviderClient {
    protected abstract fun buildRequest(
        account: AccountEntity,
        key: String,
        request: ChatRequest,
        stream: Boolean,
    ): Request

    protected abstract fun parse(type: String?, data: String): List<StreamEvent>

    override fun stream(account: AccountEntity, key: String, request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val req = try {
            buildRequest(account, key, request, true)
        } catch (e: Exception) {
            trySend(StreamEvent.Failure(ProviderFailure(e.message ?: "Ungültige Anfrage.", retryable = false)))
            close()
            return@callbackFlow
        }

        var terminal = false
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (terminal) return
                if (data == "[DONE]") {
                    terminal = true
                    trySend(StreamEvent.Done)
                    close()
                    return
                }
                val events = try {
                    parse(type, data)
                } catch (e: Exception) {
                    listOf(
                        StreamEvent.Failure(
                            ProviderFailure("Antwort konnte nicht gelesen werden: ${e.message}", retryable = false),
                        ),
                    )
                }
                for (event in events) {
                    if (terminal) return
                    trySend(event)
                    if (event is StreamEvent.Done || event is StreamEvent.Failure) {
                        terminal = true
                        close()
                        return
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (terminal) return
                terminal = true
                trySend(StreamEvent.Done)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (terminal) return
                terminal = true
                trySend(StreamEvent.Failure(ProviderHttp.failure(t, response)))
                close()
            }
        }

        val source = try {
            EventSources.createFactory(http).newEventSource(req, listener)
        } catch (e: Exception) {
            trySend(StreamEvent.Failure(ProviderFailure(e.message ?: "Netzwerkfehler", retryable = true)))
            close()
            return@callbackFlow
        }
        awaitClose { source.cancel() }
    }

    override suspend fun test(account: AccountEntity, key: String): TestResult = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        val request = ChatRequest(
            model = account.defaultModel,
            system = "",
            turns = listOf(ChatTurn(Role.USER, "ping")),
            maxTokens = 1,
        )
        val req = try {
            buildRequest(account, key, request, false)
        } catch (e: Exception) {
            return@withContext TestResult(false, e.message ?: "Ungültige Anfrage.", 0L)
        }
        try {
            ProviderHttp.testClient.newCall(req).execute().use { response ->
                val body = response.body.string()
                val elapsed = SystemClock.elapsedRealtime() - start
                if (response.isSuccessful) {
                    TestResult(true, "HTTP ${response.code}", elapsed)
                } else {
                    TestResult(false, ProviderHttp.extractError(body) ?: "HTTP ${response.code}", elapsed)
                }
            }
        } catch (e: Exception) {
            TestResult(false, e.message ?: "Netzwerkfehler", SystemClock.elapsedRealtime() - start)
        }
    }
}
