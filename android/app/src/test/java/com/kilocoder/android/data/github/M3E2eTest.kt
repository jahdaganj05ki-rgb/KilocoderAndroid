package com.kilocoder.android.data.github

import com.kilocoder.android.data.workspace.WorkspaceRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live M3 test against a disposable GitHub repository.
 *
 * Normal unit-test runs skip this class. The manual android-m3-e2e workflow
 * sets KILOCODER_E2E=1 and provides a repository-scoped PAT through the
 * environment. The PAT is never written to a test artifact.
 */
class M3E2eTest {
    @Test(timeout = 30 * 60 * 1000)
    fun pushesBuildsLoadsLogsAndDownloadsApk() = runBlocking {
        assumeTrue(
            "Live GitHub E2E is disabled. Set KILOCODER_E2E=1 and the KILOCODER_E2E_* variables.",
            System.getenv("KILOCODER_E2E") == "1",
        )
        val token = required("KILOCODER_E2E_TOKEN")
        val owner = required("KILOCODER_E2E_OWNER")
        val repo = required("KILOCODER_E2E_REPO")
        val branch = optional("KILOCODER_E2E_BRANCH", "main")
        val workflow = optional("KILOCODER_E2E_WORKFLOW", "android.yml")
        val workspace = File(required("KILOCODER_E2E_WORKSPACE"))
        val output = File(required("KILOCODER_E2E_OUTPUT")).apply { mkdirs() }
        val api = GitHubApi()

        val user = api.user(token)
        assertTrue("GitHub returned an empty login.", user.login.isNotBlank())

        val project = api.repo(token, owner, repo)
        assertTrue(
            "Unexpected repository ${project.fullName}.",
            project.fullName.equals("$owner/$repo", ignoreCase = true),
        )

        val snapshot = WorkspaceRepository(workspace).snapshot()
        assertTrue("The E2E workspace snapshot is empty.", snapshot.files.isNotEmpty())
        assertTrue("The snapshot exceeds the app's 500-file limit.", snapshot.files.size <= 500)
        assertTrue(
            "The snapshot exceeds the app's 5 MB limit.",
            snapshot.files.sumOf { it.bytes.toLong() } <= 5_000_000,
        )

        val push = api.push(
            token = token,
            owner = owner,
            repo = repo,
            branch = branch,
            message = "KiloCoder M3 E2E ${optional("GITHUB_RUN_ID", "local")}",
            files = snapshot.files,
        )
        assertEquals("The pushed file count differs.", snapshot.files.size, push.files)
        assertTrue("GitHub returned an empty commit SHA.", push.commitSha.isNotBlank())
        assertEquals("The push target branch differs.", branch, push.branch)

        dispatchWithRetry(api, token, owner, repo, workflow, branch)
        val run = waitForRun(api, token, owner, repo, branch, push.commitSha)
        val runUrl = run.htmlUrl.orEmpty()
        assertTrue("The workflow run has no URL.", runUrl.isNotBlank())
        assertEquals("The temporary build failed. See $runUrl", "success", run.conclusion)

        val logs = api.logs(token, owner, repo, run.id)
        assertTrue("The build produced no logs. See $runUrl", logs.isNotBlank())
        assertTrue("The PAT appeared in the build logs.", !logs.contains(token))
        File(output, "build.log").writeText(logs)

        val artifacts = api.artifacts(token, owner, repo, run.id)
        assertTrue("The build produced no artifacts. See $runUrl", artifacts.isNotEmpty())
        val artifact = artifacts.firstOrNull { !it.expired && it.name.contains("apk", true) }
            ?: error("No unexpired APK artifact found: ${artifacts.joinToString { it.name }}")
        val apk = api.download(
            token = token,
            owner = owner,
            repo = repo,
            artifact = artifact,
            target = File(output, "kilocoder-debug.apk"),
        )
        assertTrue("The downloaded APK is empty.", apk.length() > 0)
        assertTrue("The downloaded artifact is not an APK/ZIP.", isZip(apk))

        File(output, "summary.md").writeText(
            buildString {
                appendLine("## M3 E2E")
                appendLine()
                appendLine("- Temporary repository: https://github.com/$owner/$repo")
                appendLine("- Commit: `${push.commitSha}`")
                appendLine("- Files pushed: ${push.files}")
                appendLine("- Workflow run: [${run.number}]($runUrl)")
                appendLine("- Log size: ${logs.length} chars")
                appendLine("- APK artifact: `${artifact.name}`")
                appendLine("- APK size: ${apk.length()} bytes")
            },
        )
    }

    private suspend fun dispatchWithRetry(
        api: GitHubApi,
        token: String,
        owner: String,
        repo: String,
        workflow: String,
        branch: String,
    ) {
        val deadline = System.currentTimeMillis() + DISPATCH_TIMEOUT_MS
        var last: GitHubException? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                api.dispatch(token, owner, repo, workflow, branch)
                return
            } catch (error: GitHubException) {
                if (error.status != 404 && error.status != 422) throw error
                last = error
                delay(DISPATCH_RETRY_MS)
            }
        }
        throw last ?: GitHubException("Timed out waiting for the workflow to become dispatchable.")
    }

    private suspend fun waitForRun(
        api: GitHubApi,
        token: String,
        owner: String,
        repo: String,
        branch: String,
        commit: String,
    ): GitHubRun {
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        var last: GitHubRun? = null
        while (System.currentTimeMillis() < deadline) {
            val run = api.runs(token, owner, repo, branch).firstOrNull {
                it.event == "workflow_dispatch" && it.sha == commit
            }
            if (run != null) {
                last = run
                if (run.status == "completed") return run
            }
            delay(RUN_POLL_MS)
        }
        val suffix = last?.let { " Last status: ${it.status.orEmpty()} (${it.htmlUrl.orEmpty()})." }.orEmpty()
        error("Timed out waiting for the temporary workflow run.$suffix")
    }

    private fun required(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing required environment variable $name.")

    private fun optional(name: String, fallback: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 'P'.code && input.read() == 'K'.code
    }

    private companion object {
        const val DISPATCH_TIMEOUT_MS = 90_000L
        const val DISPATCH_RETRY_MS = 3_000L
        const val RUN_TIMEOUT_MS = 20 * 60_000L
        const val RUN_POLL_MS = 5_000L
    }
}
