package com.kilocoder.android

import android.content.Context
import androidx.room.Room
import com.kilocoder.android.data.crypto.AndroidSecretBox
import com.kilocoder.android.data.db.AppDatabase
import com.kilocoder.android.data.provider.AnthropicClient
import com.kilocoder.android.data.provider.GeminiClient
import com.kilocoder.android.data.provider.OpenAiClient
import com.kilocoder.android.data.provider.Protocol
import com.kilocoder.android.data.provider.ProviderHttp
import com.kilocoder.android.data.repo.AccountRepository
import com.kilocoder.android.data.repo.ChatRepository

class AppContainer(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "kilocoder.db",
    ).build()

    val accounts = AccountRepository(db, AndroidSecretBox())

    val chat = ChatRepository(
        accounts = accounts,
        clients = mapOf(
            Protocol.OPENAI to OpenAiClient(ProviderHttp.client),
            Protocol.ANTHROPIC to AnthropicClient(ProviderHttp.client),
            Protocol.GEMINI to GeminiClient(ProviderHttp.client),
        ),
    )
}
