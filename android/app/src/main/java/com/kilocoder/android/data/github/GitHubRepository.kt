package com.kilocoder.android.data.github

import com.kilocoder.android.data.crypto.SecretBox
import com.kilocoder.android.data.db.AppDatabase
import com.kilocoder.android.data.db.GitHubConfigEntity
import com.kilocoder.android.data.workspace.WorkspaceRepository
import com.kilocoder.android.domain.Clock
import com.kilocoder.android.domain.WallClock
import java.io.File
import kotlinx.coroutines.flow.Flow

class GitHubRepository(
    private val db: AppDatabase,
    private val box: SecretBox,
    private val api: GitHubApi,
    private val workspace: WorkspaceRepository,
    private val updates: File,
    private val clock: Clock = WallClock,
) {
    fun observe(): Flow<GitHubConfigEntity?> = db.github().observe()

    suspend fun config(): GitHubConfigEntity? = db.github().get()

    suspend fun connect(
        tokenInput: String,
        owner: String,
        repo: String,
        branch: String,
        workflow: String,
    ): GitHubConfigEntity {
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim()
        if (cleanOwner.isBlank()) throw GitHubException("Repository owner is required.")
        if (cleanRepo.isBlank()) throw GitHubException("Repository name is required.")
        if (cleanOwner.contains('/') || cleanRepo.contains('/')) {
            throw GitHubException("Owner and repository must be separate values.")
        }
        val existing = db.github().get()
        val token = tokenInput.trim().ifBlank {
            existing?.let { decrypt(it) }.orEmpty()
        }
        if (token.isBlank()) throw GitHubException("Personal access token is required.")
        val user = api.user(token)
        val project = api.repo(token, cleanOwner, cleanRepo)
        val cleanBranch = branch.trim().ifBlank { project.defaultBranch ?: "main" }
        val cleanWorkflow = workflow.trim().ifBlank { "android.yml" }.substringAfterLast('/')
        val entity = GitHubConfigEntity(
            id = 1,
            owner = cleanOwner,
            repo = cleanRepo,
            branch = cleanBranch,
            workflow = cleanWorkflow,
            tokenCipher = box.encrypt(token),
            tokenLast4 = if (token.length <= 4) "****" else token.takeLast(4),
            login = user.login,
            updatedAt = clock.now(),
        )
        db.github().save(entity)
        return entity
    }

    suspend fun push(message: String): GitHubPush {
        val (cfg, token) = credentials()
        val snapshot = workspace.snapshot()
        if (snapshot.files.isEmpty()) {
            throw GitHubException("The workspace has no text files to push.")
        }
        val result = api.push(
            token = token,
            owner = cfg.owner,
            repo = cfg.repo,
            branch = cfg.branch,
            message = message.trim().ifBlank { "KiloCoder workspace update" },
            files = snapshot.files,
        )
        return result.copy(skipped = snapshot.skipped)
    }

    suspend fun dispatch() {
        val (cfg, token) = credentials()
        api.dispatch(token, cfg.owner, cfg.repo, cfg.workflow, cfg.branch)
    }

    suspend fun runs(): List<GitHubRun> {
        val (cfg, token) = credentials()
        return api.runs(token, cfg.owner, cfg.repo, cfg.branch)
    }

    suspend fun logs(runId: Long): String {
        val (cfg, token) = credentials()
        return api.logs(token, cfg.owner, cfg.repo, runId)
    }

    suspend fun artifacts(runId: Long): List<GitHubArtifact> {
        val (cfg, token) = credentials()
        return api.artifacts(token, cfg.owner, cfg.repo, runId)
    }

    suspend fun download(artifact: GitHubArtifact): File {
        val (cfg, token) = credentials()
        updates.mkdirs()
        return api.download(
            token = token,
            owner = cfg.owner,
            repo = cfg.repo,
            artifact = artifact,
            target = File(updates, "app-${artifact.id}.apk"),
        )
    }

    suspend fun clear() {
        db.github().clear()
        updates.listFiles()?.forEach { it.delete() }
    }

    private suspend fun credentials(): Pair<GitHubConfigEntity, String> {
        val cfg = db.github().get() ?: throw GitHubException("Connect GitHub first.")
        return cfg to decrypt(cfg)
    }

    private fun decrypt(cfg: GitHubConfigEntity): String = try {
        box.decrypt(cfg.tokenCipher)
    } catch (error: Exception) {
        throw GitHubException("The stored GitHub token could not be decrypted.")
    }
}
