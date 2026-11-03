package com.kilocoder.android.domain.account

import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.ProviderPolicyEntity
import kotlin.random.Random

class AccountSelector(
    private val random: Random = Random.Default,
) {
    fun choose(
        accounts: List<AccountEntity>,
        policy: ProviderPolicyEntity,
        excluded: Set<String>,
        now: Long,
    ): AccountEntity? {
        val pool = accounts.filter {
            it.enabled && it.id !in excluded && it.cooldownUntil <= now
        }
        if (pool.isEmpty()) return null
        return when (runCatching { RotationMode.valueOf(policy.mode) }.getOrDefault(RotationMode.FAILOVER)) {
            RotationMode.MANUAL, RotationMode.NEXT -> pool.firstOrNull { it.active }

            RotationMode.FAILOVER -> pool.firstOrNull { it.active } ?: pool.first()

            RotationMode.ROUND_ROBIN -> {
                val ordered = accounts
                    .filter { it.enabled && it.id !in excluded }
                    .sortedBy { it.position }
                val index = ordered.indexOfFirst { it.active }
                val rotated = if (index < 0) ordered else ordered.drop(index + 1) + ordered.take(index + 1)
                rotated.firstOrNull { it.cooldownUntil <= now }
            }

            RotationMode.LEAST_USED -> pool.minWithOrNull(
                compareBy<AccountEntity> { it.requestCount }.thenBy { it.position },
            )

            RotationMode.RANDOM -> {
                val total = pool.sumOf { it.weight.coerceAtLeast(1) }
                var roll = random.nextInt(total)
                for (account in pool) {
                    roll -= account.weight.coerceAtLeast(1)
                    if (roll < 0) return account
                }
                pool.last()
            }
        }
    }
}
