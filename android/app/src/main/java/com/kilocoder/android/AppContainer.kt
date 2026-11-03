package com.kilocoder.android

import android.content.Context
import androidx.room.Room
import com.kilocoder.android.data.agent.AgentRepository
import com.kilocoder.android.data.agent.WorkspaceTools
import com.kilocoder.android.data.crypto.AndroidSecretBox
import com.kilocoder.android.data.db.AppDatabase
import com.kilocoder.android.data.github.GitHubApi
import com.kilocoder.android.data.github.GitHubRepository
import com.kilocoder.android.data.install.ApkInstaller
import com.kilocoder.android.data.provider.AnthropicClient
import com.kilocoder.android.data.provider.GeminiClient
import com.kilocoder.android.data.provider.OpenAiClient
import com.kilocoder.android.data.provider.Protocol
import com.kilocoder.android.data.provider.ProviderHttp
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.data.repo.ChatRepository
import com.kilocoder.android.data.repo.SessionRepository
import com.kilocoder.android.data.workspace.WorkspaceRepository
import java.io.File

class AppContainer(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "kilocoder.db",
    ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()

    private val clients = mapOf(
        Protocol.OPENAI to OpenAiClient(ProviderHttp.client),
        Protocol.ANTHROPIC to AnthropicClient(ProviderHttp.client),
        Protocol.GEMINI to GeminiClient(ProviderHttp.client),
    )

    private val box = AndroidSecretBox()

    val accounts = AccountRepository(db, box)
    val workspace = WorkspaceRepository(File(context.applicationContext.filesDir, "workspace"))
    val sessions = SessionRepository(db)
    val chat = ChatRepository(accounts = accounts, clients = clients)
    val agent = AgentRepository(
        accounts = accounts,
        clients = clients,
        tools = WorkspaceTools(workspace),
    )
    val github = GitHubRepository(
        db = db,
        box = box,
        api = GitHubApi(),
        workspace = workspace,
        updates = File(context.applicationContext.cacheDir, "github"),
    )
    val installer = ApkInstaller(context.applicationContext)
}
