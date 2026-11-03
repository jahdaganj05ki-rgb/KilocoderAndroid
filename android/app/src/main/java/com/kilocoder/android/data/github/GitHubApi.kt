package com.kilocoder.android.data.github

import com.kilocoder.android.data.provider.ProviderHttp
import com.kilocoder.android.data.workspace.WorkspaceFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class GitHubException(message: String, val status: Int? = null) : Exception(message)

@Serializable
data class GitHubUser(
    val login: String,
    val name: String? = null,
)

@Serializable
data class GitHubRepo(
    @SerialName("full_name") val fullName: String,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class GitHubRun(
    val id: Long,
    @SerialName("run_number") val number: Int = 0,
    @SerialName("display_title") val title: String = "",
    @SerialName("workflow_name") val workflow: String? = null,
    val event: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("head_branch") val branch: String? = null,
    @SerialName("head_sha") val sha: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
private data class GitHubRuns(
    @SerialName("workflow_runs") val runs: List<GitHubRun> = emptyList(),
)

@Serializable
data class GitHubArtifact(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val size: Long = 0,
    val expired: Boolean = false,
)

@Serializable
private data class GitHubArtifacts(
    val artifacts: List<GitHubArtifact> = emptyList(),
)

@Serializable
private data class GitHubRef(
    @SerialName("object") val target: GitHubRefTarget,
)

@Serializable
private data class GitHubRefTarget(val sha: String)

@Serializable
private data class GitHubTree(val sha: String)

@Serializable
private data class GitHubCommit(val sha: String)

data class GitHubPush(
    val commitSha: String,
    val treeSha: String,
    val branch: String,
    val files: Int,
    val skipped: Int = 0,
)

class GitHubApi(private val http: OkHttpClient = ProviderHttp.client) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val redirects = http.newBuilder().followRedirects(false).build()

    suspend fun user(token: String): GitHubUser = withContext(Dispatchers.IO) {
        json.decodeFromString(body(token, "GET", "/user"))
    }

    suspend fun repo(token: String, owner: String, repo: String): GitHubRepo =
        withContext(Dispatchers.IO) {
            json.decodeFromString(body(token, "GET", "/repos/${seg(owner)}/${seg(repo)}"))
        }

    suspend fun push(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        files: List<WorkspaceFile>,
    ): GitHubPush = withContext(Dispatchers.IO) {
        val parent = ref(token, owner, repo, branch)
        val tree = body(
            token = token,
            method = "POST",
            path = "/repos/${seg(owner)}/${seg(repo)}/git/trees",
            payload = buildJsonObject {
                putJsonArray("tree") {
                    files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("path", file.path)
                                put("mode", "100644")
                                put("type", "blob")
                                put("content", file.content)
                            },
                        )
                    }
                }
            },
        )
        val treeSha = json.decodeFromString<GitHubTree>(tree).sha
        val commit = body(
            token = token,
            method = "POST",
            path = "/repos/${seg(owner)}/${seg(repo)}/git/commits",
            payload = buildJsonObject {
                put("message", message)
                put("tree", treeSha)
                putJsonArray("parents") {
                    if (parent != null) add(JsonPrimitive(parent))
                }
            },
        )
        val commitSha = json.decodeFromString<GitHubCommit>(commit).sha
        if (parent == null) {
            body(
                token = token,
                method = "POST",
                path = "/repos/${seg(owner)}/${seg(repo)}/git/refs",
                payload = buildJsonObject {
                    put("ref", "refs/heads/$branch")
                    put("sha", commitSha)
                },
            )
        } else {
            body(
                token = token,
                method = "PATCH",
                path = "/repos/${seg(owner)}/${seg(repo)}/git/refs/heads/${path(branch)}",
                payload = buildJsonObject {
                    put("sha", commitSha)
                    put("force", false)
                },
            )
        }
        GitHubPush(commitSha, treeSha, branch, files.size)
    }

    suspend fun dispatch(
        token: String,
        owner: String,
        repo: String,
        workflow: String,
        branch: String,
    ) {
        withContext(Dispatchers.IO) {
            val name = workflow.substringAfterLast('/').ifBlank { "android.yml" }
            body(
                token = token,
                method = "POST",
                path = "/repos/${seg(owner)}/${seg(repo)}/actions/workflows/${seg(name)}/dispatches",
                payload = buildJsonObject { put("ref", branch) },
            )
        }
    }

    suspend fun runs(
        token: String,
        owner: String,
        repo: String,
        branch: String,
    ): List<GitHubRun> = withContext(Dispatchers.IO) {
        val filter = branch.trim()
            .takeIf { it.isNotBlank() }
            ?.let { "&branch=${seg(it)}" }
            .orEmpty()
        json.decodeFromString<GitHubRuns>(
            body(
                token = token,
                method = "GET",
                path = "/repos/${seg(owner)}/${seg(repo)}/actions/runs?per_page=15$filter",
            ),
        ).runs
    }

    suspend fun logs(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
    ): String = withContext(Dispatchers.IO) {
        openBlob(
            token = token,
            path = "/repos/${seg(owner)}/${seg(repo)}/actions/runs/$runId/logs",
        ).use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body.string())
            val length = response.body.contentLength()
            if (length > MAX_LOG_BYTES) throw GitHubException("Build logs are too large.")
            val bytes = response.body.bytes()
            if (bytes.size > MAX_LOG_BYTES) throw GitHubException("Build logs are too large.")
            renderLogs(bytes)
        }
    }

    suspend fun artifacts(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
    ): List<GitHubArtifact> = withContext(Dispatchers.IO) {
        json.decodeFromString<GitHubArtifacts>(
            body(
                token = token,
                method = "GET",
                path = "/repos/${seg(owner)}/${seg(repo)}/actions/runs/$runId/artifacts",
            ),
        ).artifacts
    }

    suspend fun download(
        token: String,
        owner: String,
        repo: String,
        artifact: GitHubArtifact,
        target: File,
    ): File = withContext(Dispatchers.IO) {
        try {
            openBlob(
                token = token,
                path = "/repos/${seg(owner)}/${seg(repo)}/actions/artifacts/${artifact.id}/zip",
            ).use { response ->
                if (!response.isSuccessful) throw failure(response.code, response.body.string())
                val length = response.body.contentLength()
                if (length > MAX_ARTIFACT_BYTES) throw GitHubException("Artifact is too large.")
                ZipInputStream(response.body.byteStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) {
                            continue
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> copy(zip, output) }
                        return@withContext target
                    }
                }
            }
            throw GitHubException("The artifact does not contain an APK.")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun ref(token: String, owner: String, repo: String, branch: String): String? {
        val response = http.newCall(
            build(
                token = token,
                method = "GET",
                path = "/repos/${seg(owner)}/${seg(repo)}/git/ref/heads/${path(branch)}",
            ),
        ).execute()
        response.use {
            if (it.code == 404 || it.code == 409) return null
            val text = it.body.string()
            if (!it.isSuccessful) throw failure(it.code, text)
            return json.decodeFromString<GitHubRef>(text).target.sha
        }
    }

    private fun body(
        token: String,
        method: String,
        path: String,
        payload: JsonObject? = null,
    ): String {
        http.newCall(build(token, method, path, payload)).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, text)
            return text
        }
    }

    private fun build(
        token: String,
        method: String,
        path: String,
        payload: JsonObject? = null,
    ): Request {
        val builder = Request.Builder()
            .url("$BASE$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "KiloCoder-Android")
        if (method == "GET") {
            builder.get()
        } else {
            builder.method(method, ProviderHttp.body(payload ?: buildJsonObject { }))
        }
        return builder.build()
    }

    private fun openBlob(token: String, path: String): Response {
        val first = redirects.newCall(build(token, "GET", path)).execute()
        if (!first.isRedirect) return first
        val location = first.header("Location")
        first.close()
        if (location == null) {
            throw GitHubException("GitHub did not return a download location.")
        }
        if (!location.startsWith("https://")) {
            throw GitHubException("Unsafe download location.")
        }
        return http.newCall(Request.Builder().url(location).get().build()).execute()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_APK_BYTES) throw GitHubException("APK is too large.")
            output.write(buffer, 0, read)
        }
    }

    private fun renderLogs(bytes: ByteArray): String {
        if (bytes.size < 4 ||
            bytes[0] != 'P'.code.toByte() ||
            bytes[1] != 'K'.code.toByte()
        ) {
            return bytes.toString(Charsets.UTF_8).take(MAX_LOG_CHARS)
        }
        val out = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (out.length < MAX_LOG_CHARS) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".txt", true) &&
                    !entry.name.endsWith(".log", true)
                ) {
                    continue
                }
                out.append("## ").append(entry.name).append('\n')
                val buffer = ByteArray(8_192)
                while (out.length < MAX_LOG_CHARS) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    val take = minOf(read, MAX_LOG_CHARS - out.length)
                    out.append(String(buffer, 0, take, Charsets.UTF_8))
                }
                out.append('\n')
            }
        }
        return out.toString()
    }

    private fun failure(code: Int, text: String): GitHubException {
        val message = runCatching {
            json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return GitHubException(message ?: "GitHub HTTP $code", code)
    }

    private fun seg(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun path(value: String): String =
        value.split('/').joinToString("/") { seg(it) }

    private companion object {
        const val BASE = "https://api.github.com"
        const val MAX_LOG_BYTES = 50_000_000L
        const val MAX_LOG_CHARS = 200_000
        const val MAX_ARTIFACT_BYTES = 200_000_000L
        const val MAX_APK_BYTES = 200_000_000L
    }
}
