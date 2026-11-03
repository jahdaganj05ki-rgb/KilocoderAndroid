package com.kilocoder.android.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.kilocoder.android.data.crypto.SecretBox
import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.AppDatabase
import com.kilocoder.android.data.db.ProviderPolicyEntity
import com.kilocoder.android.data.provider.AuthStyle
import com.kilocoder.android.data.provider.ProviderCatalog
import com.kilocoder.android.data.provider.ProviderFailure
import com.kilocoder.android.domain.Clock
import com.kilocoder.android.domain.WallClock
import com.kilocoder.android.domain.account.RotationMode
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class AccountDraft(
    val providerId: String,
    val label: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val enabled: Boolean = true,
)

class AccountException(message: String) : Exception(message)

class AccountRepository(
    private val db: AppDatabase,
    private val box: SecretBox,
    private val clock: Clock = WallClock,
) {
    fun observeAccounts(): Flow<List<AccountEntity>> = db.accounts().observeAll()

    fun observePolicies(): Flow<List<ProviderPolicyEntity>> = db.policies().observeAll()

    suspend fun byProvider(providerId: String): List<AccountEntity> = db.accounts().byProvider(providerId)

    suspend fun byId(id: String): AccountEntity? = db.accounts().byId(id)

    suspend fun policy(providerId: String): ProviderPolicyEntity =
        db.policies().byId(providerId) ?: defaultPolicy(providerId)

    suspend fun add(draft: AccountDraft): String {
        val spec = specFor(draft.providerId)
        val key = draft.apiKey.trim()
        if (key.isBlank()) throw AccountException("API-Key fehlt.")
        val base = normalizeBase(draft, spec)
        val model = draft.model.trim().ifBlank { spec.defaultModel }
        if (model.isBlank()) throw AccountException("Modell fehlt.")
        val label = draft.label.trim().ifBlank { spec.name }
        val cipher = try {
            box.encrypt(key)
        } catch (e: Exception) {
            throw AccountException("API-Key konnte nicht verschlüsselt werden.")
        }
        val id = UUID.randomUUID().toString()
        val now = clock.now()
        db.withTransaction {
            val count = db.accounts().count(spec.id)
            val hasActive = db.accounts().byProvider(spec.id).any { it.active && it.enabled }
            val position = db.accounts().maxPosition(spec.id) + 1
            db.accounts().insert(
                AccountEntity(
                    id = id,
                    providerId = spec.id,
                    label = label,
                    keyCipher = cipher,
                    keyLast4 = if (key.length <= 4) "****" else key.takeLast(4),
                    baseUrl = base,
                    authStyle = draft.authStyle.name,
                    defaultModel = model,
                    enabled = draft.enabled,
                    active = false,
                    position = position,
                    weight = 1,
                    requestCount = 0,
                    successCount = 0,
                    failureCount = 0,
                    consecutiveFailures = 0,
                    cooldownUntil = 0,
                    lastUsedAt = 0,
                    lastError = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (draft.enabled && (count == 0 || !hasActive)) {
                db.accounts().clearActive(spec.id)
                db.accounts().activate(id)
            }
            if (db.policies().byId(spec.id) == null) {
                db.policies().upsert(defaultPolicy(spec.id))
            }
        }
        return id
    }

    suspend fun update(id: String, draft: AccountDraft) {
        val existing = db.accounts().byId(id) ?: throw AccountException("Konto nicht gefunden.")
        val spec = specFor(existing.providerId)
        val base = normalizeBase(draft.copy(providerId = existing.providerId), spec)
        val model = draft.model.trim().ifBlank { existing.defaultModel.ifBlank { spec.defaultModel } }
        if (model.isBlank()) throw AccountException("Modell fehlt.")
        val label = draft.label.trim().ifBlank { spec.name }
        val key = draft.apiKey.trim()
        val cipher = if (key.isBlank()) {
            existing.keyCipher
        } else {
            try {
                box.encrypt(key)
            } catch (e: Exception) {
                throw AccountException("API-Key konnte nicht verschlüsselt werden.")
            }
        }
        val last4 = if (key.isBlank()) existing.keyLast4 else if (key.length <= 4) "****" else key.takeLast(4)
        val now = clock.now()
        db.withTransaction {
            db.accounts().update(
                existing.copy(
                    label = label,
                    keyCipher = cipher,
                    keyLast4 = last4,
                    baseUrl = base,
                    authStyle = draft.authStyle.name,
                    defaultModel = model,
                    enabled = draft.enabled,
                    cooldownUntil = 0,
                    consecutiveFailures = 0,
                    lastError = null,
                    updatedAt = now,
                ),
            )
            ensureActive(existing.providerId, id.takeIf { draft.enabled })
        }
    }

    suspend fun delete(id: String) {
        db.withTransaction {
            val account = db.accounts().byId(id) ?: return@withTransaction
            db.accounts().delete(account)
            val rest = db.accounts().byProvider(account.providerId)
            rest.forEachIndexed { index, item ->
                if (item.position != index) db.accounts().setPosition(item.id, index)
            }
            ensureActive(account.providerId)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        db.withTransaction {
            val account = db.accounts().byId(id) ?: return@withTransaction
            db.accounts().update(account.copy(enabled = enabled, updatedAt = clock.now()))
            ensureActive(account.providerId, id.takeIf { enabled })
        }
    }

    suspend fun setActive(id: String) {
        val account = db.accounts().byId(id) ?: throw AccountException("Konto nicht gefunden.")
        if (!account.enabled) throw AccountException("Konto ist deaktiviert.")
        db.withTransaction {
            db.accounts().clearActive(account.providerId)
            db.accounts().activate(id)
        }
    }

    suspend fun next(providerId: String) {
        db.withTransaction {
            val list = db.accounts().byProvider(providerId).filter { it.enabled }
            if (list.isEmpty()) return@withTransaction
            val now = clock.now()
            val available = list.filter { it.cooldownUntil <= now }.ifEmpty { list }
            val index = available.indexOfFirst { it.active }
            val next = available[(index + 1).mod(available.size)]
            db.accounts().clearActive(providerId)
            db.accounts().activate(next.id)
        }
    }

    suspend fun move(id: String, up: Boolean) {
        db.withTransaction {
            val account = db.accounts().byId(id) ?: return@withTransaction
            val list = db.accounts().byProvider(account.providerId)
            val index = list.indexOfFirst { it.id == id }
            val target = if (up) index - 1 else index + 1
            if (index < 0 || target !in list.indices) return@withTransaction
            val a = list[index]
            val b = list[target]
            db.accounts().setPosition(a.id, b.position)
            db.accounts().setPosition(b.id, a.position)
        }
    }

    suspend fun setPolicy(policy: ProviderPolicyEntity) {
        db.policies().upsert(
            policy.copy(
                maxSwitches = policy.maxSwitches.coerceIn(0, 5),
                cooldownSeconds = policy.cooldownSeconds.coerceAtLeast(0),
            ),
        )
    }

    suspend fun markUsed(id: String) {
        db.accounts().markUsed(id, clock.now())
    }

    suspend fun success(id: String) {
        db.accounts().markSuccess(id)
    }

    suspend fun failure(id: String, failure: ProviderFailure, policy: ProviderPolicyEntity) {
        val current = db.accounts().byId(id) ?: return
        val now = clock.now()
        val cooldown = if (!failure.retryable) {
            0L
        } else {
            when {
                failure.retryAfterSeconds != null -> failure.retryAfterSeconds.coerceIn(0, 3600) * 1000L
                failure.status == 401 || failure.status == 403 -> 300_000L
                failure.status != null && failure.status >= 500 ->
                    (30_000L shl current.consecutiveFailures.coerceAtMost(4)).coerceAtMost(600_000L)
                else -> policy.cooldownSeconds.coerceAtLeast(10) * 1000L
            }
        }
        db.accounts().markFailure(id, now + cooldown, failure.message.take(300))
    }

    suspend fun key(account: AccountEntity): String? = try {
        box.decrypt(account.keyCipher)
    } catch (e: Exception) {
        Log.e("AccountRepository", "Unable to decrypt account key", e)
        null
    }

    suspend fun clearAll() {
        db.withTransaction {
            db.accounts().clear()
            db.policies().clear()
        }
    }

    private suspend fun ensureActive(providerId: String, preferred: String? = null) {
        val list = db.accounts().byProvider(providerId)
        if (list.any { it.active && it.enabled }) return
        val next = preferred?.let { id -> list.firstOrNull { it.id == id && it.enabled } }
            ?: list.firstOrNull { it.enabled }
            ?: list.firstOrNull()
        db.accounts().clearActive(providerId)
        if (next != null) db.accounts().activate(next.id)
    }

    private fun specFor(providerId: String) =
        ProviderCatalog.byId(providerId) ?: throw AccountException("Unbekannter Provider.")

    private fun normalizeBase(draft: AccountDraft, spec: com.kilocoder.android.data.provider.ProviderSpec): String {
        val base = draft.baseUrl.trim().ifBlank { spec.baseUrl }.trimEnd('/')
        if (base.isBlank()) throw AccountException("Base-URL fehlt.")
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw AccountException("Base-URL muss mit http:// oder https:// beginnen.")
        }
        return base
    }

    companion object {
        fun defaultPolicy(providerId: String) = ProviderPolicyEntity(
            providerId = providerId,
            mode = RotationMode.FAILOVER.name,
            autoFailover = true,
            maxSwitches = 3,
            cooldownSeconds = 60,
        )
    }
}
