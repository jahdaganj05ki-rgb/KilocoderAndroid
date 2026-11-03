package com.kilocoder.android.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kilocoder.android.data.db.GitHubConfigEntity
import com.kilocoder.android.data.github.GitHubArtifact
import com.kilocoder.android.data.github.GitHubPush
import com.kilocoder.android.data.github.GitHubRepository
import com.kilocoder.android.data.github.GitHubRun
import com.kilocoder.android.data.install.ApkInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GitHubUiState(
    val loading: Boolean = true,
    val config: GitHubConfigEntity? = null,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val workflow: String = "android.yml",
    val token: String = "",
    val commit: String = "KiloCoder workspace update",
    val busy: Boolean = false,
    val note: String? = null,
    val error: String? = null,
    val runs: List<GitHubRun> = emptyList(),
    val selectedId: Long? = null,
    val logs: String = "",
    val artifacts: List<GitHubArtifact> = emptyList(),
    val artifactsLoaded: Boolean = false,
    val push: GitHubPush? = null,
)

class GitHubViewModel(
    private val repo: GitHubRepository,
    private val installer: ApkInstaller,
) : ViewModel() {
    private val state = MutableStateFlow(GitHubUiState())
    val stream: StateFlow<GitHubUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val config = repo.config()
                applyConfig(config)
                if (config != null) loadRunsInternal()
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = message(error)) }
            }
            repo.observe()
                .catch { error ->
                    state.update { it.copy(error = message(error)) }
                }
                .collect { applyConfig(it) }
        }
    }

    fun setOwner(value: String) {
        state.update { it.copy(owner = value) }
    }

    fun setRepo(value: String) {
        state.update { it.copy(repo = value) }
    }

    fun setBranch(value: String) {
        state.update { it.copy(branch = value) }
    }

    fun setWorkflow(value: String) {
        state.update { it.copy(workflow = value) }
    }

    fun setToken(value: String) {
        state.update { it.copy(token = value) }
    }

    fun setCommit(value: String) {
        state.update { it.copy(commit = value) }
    }

    fun connect() {
        val snapshot = state.value
        execute {
            val config = repo.connect(
                tokenInput = snapshot.token,
                owner = snapshot.owner,
                repo = snapshot.repo,
                branch = snapshot.branch,
                workflow = snapshot.workflow,
            )
            state.update {
                it.copy(
                    config = config,
                    owner = config.owner,
                    repo = config.repo,
                    branch = config.branch,
                    workflow = config.workflow,
                    token = "",
                    note = "GitHub connection saved.",
                )
            }
            loadRunsInternal()
        }
    }

    fun push() {
        val commit = state.value.commit
        execute {
            val push = repo.push(commit)
            state.update {
                it.copy(
                    push = push,
                    note = "Pushed ${push.files} files to ${push.branch} (${push.commitSha.take(7)}).",
                )
            }
        }
    }

    fun dispatch() {
        execute {
            repo.dispatch()
            state.update { it.copy(note = "Android build dispatched.") }
            delay(2_500)
            loadRunsInternal()
        }
    }

    fun refreshRuns() {
        if (state.value.config == null) return
        execute { loadRunsInternal() }
    }

    fun selectRun(id: Long) {
        if (state.value.selectedId == id) return
        state.update {
            it.copy(
                selectedId = id,
                logs = "",
                artifacts = emptyList(),
                artifactsLoaded = false,
            )
        }
    }

    fun loadLogs() {
        val run = selectedRun() ?: return
        execute {
            val logs = repo.logs(run.id)
            state.update {
                it.copy(
                    logs = logs,
                    note = if (logs.isBlank()) "No logs available." else "Logs loaded.",
                )
            }
        }
    }

    fun loadArtifacts() {
        val run = selectedRun() ?: return
        execute {
            val artifacts = repo.artifacts(run.id)
            state.update {
                it.copy(
                    artifacts = artifacts,
                    artifactsLoaded = true,
                    note = if (artifacts.isEmpty()) "No artifacts found." else "Artifacts loaded.",
                )
            }
        }
    }

    fun install(artifact: GitHubArtifact) {
        if (artifact.expired) {
            state.update { it.copy(error = "This artifact has expired.") }
            return
        }
        execute {
            val file = repo.download(artifact)
            installer.install(file).getOrElse { throw it }
            state.update { it.copy(note = "Android installer opened.") }
        }
    }

    fun disconnect() {
        execute {
            repo.clear()
            state.update {
                it.copy(
                    config = null,
                    token = "",
                    runs = emptyList(),
                    selectedId = null,
                    logs = "",
                    artifacts = emptyList(),
                    artifactsLoaded = false,
                    push = null,
                    note = "GitHub disconnected.",
                )
            }
        }
    }

    fun dismissNote() {
        state.update { it.copy(note = null) }
    }

    fun dismissError() {
        state.update { it.copy(error = null) }
    }

    private fun applyConfig(config: GitHubConfigEntity?) {
        state.update {
            it.copy(
                loading = false,
                config = config,
                owner = config?.owner ?: it.owner,
                repo = config?.repo ?: it.repo,
                branch = config?.branch ?: it.branch,
                workflow = config?.workflow ?: it.workflow,
            )
        }
    }

    private suspend fun loadRunsInternal() {
        val runs = repo.runs()
        state.update { current ->
            val selected = current.selectedId
                ?.takeIf { id -> runs.any { it.id == id } }
                ?: runs.firstOrNull()?.id
            val changed = selected != current.selectedId
            current.copy(
                runs = runs,
                selectedId = selected,
                logs = if (changed) "" else current.logs,
                artifacts = if (changed) emptyList() else current.artifacts,
                artifactsLoaded = if (changed) false else current.artifactsLoaded,
            )
        }
    }

    private fun selectedRun(): GitHubRun? =
        state.value.runs.firstOrNull { it.id == state.value.selectedId }

    private fun execute(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, note = null, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.update { it.copy(error = message(error)) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    private fun message(error: Throwable): String =
        error.message ?: "GitHub request failed."
}
