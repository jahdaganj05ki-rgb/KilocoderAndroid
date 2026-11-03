package com.kilocoder.android.domain

import com.kilocoder.android.data.db.AccountEntity
import com.kilocoder.android.data.db.ProviderPolicyEntity
import com.kilocoder.android.domain.account.AccountSelector
import com.kilocoder.android.domain.account.RotationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AccountSelectorTest {
    @Test
    fun manualUsesActiveAccount() {
        val picked = select(
            listOf(account("a", 0, active = true), account("b", 1)),
            RotationMode.MANUAL,
        )
        assertEquals("a", picked?.id)
    }

    @Test
    fun manualReturnsNullWhenActiveIsCooling() {
        val picked = select(
            listOf(account("a", 0, active = true, cooldown = 5_000), account("b", 1)),
            RotationMode.MANUAL,
        )
        assertNull(picked)
    }

    @Test
    fun failoverFallsBackToAnotherAccount() {
        val picked = select(
            listOf(account("a", 0, active = true, cooldown = 5_000), account("b", 1)),
            RotationMode.FAILOVER,
        )
        assertEquals("b", picked?.id)
    }

    @Test
    fun roundRobinPicksTheNextActiveAccount() {
        val picked = select(
            listOf(
                account("a", 0, active = true),
                account("b", 1),
                account("c", 2),
            ),
            RotationMode.ROUND_ROBIN,
        )
        assertEquals("b", picked?.id)
    }

    @Test
    fun roundRobinSkipsCoolingAccounts() {
        val picked = select(
            listOf(
                account("a", 0, active = true),
                account("b", 1, cooldown = 5_000),
                account("c", 2),
            ),
            RotationMode.ROUND_ROBIN,
        )
        assertEquals("c", picked?.id)
    }

    @Test
    fun leastUsedPicksTheLowestRequestCount() {
        val picked = select(
            listOf(
                account("a", 0, active = true, requests = 5),
                account("b", 1, requests = 2),
                account("c", 2, requests = 7),
            ),
            RotationMode.LEAST_USED,
        )
        assertEquals("b", picked?.id)
    }

    @Test
    fun excludedAccountsAreSkipped() {
        val picked = AccountSelector(Random(0)).choose(
            accounts = listOf(account("a", 0, active = true), account("b", 1)),
            policy = policy(RotationMode.FAILOVER),
            excluded = setOf("a"),
            now = 1_000,
        )
        assertEquals("b", picked?.id)
    }

    @Test
    fun allCoolingReturnsNull() {
        val picked = select(
            listOf(
                account("a", 0, active = true, cooldown = 5_000),
                account("b", 1, cooldown = 5_000),
            ),
            RotationMode.FAILOVER,
        )
        assertNull(picked)
    }

    @Test
    fun randomPicksFromTheAvailablePool() {
        val picked = AccountSelector(Random(0)).choose(
            accounts = listOf(account("a", 0), account("b", 1)),
            policy = policy(RotationMode.RANDOM),
            excluded = emptySet(),
            now = 1_000,
        )
        assertTrue(picked?.id in setOf("a", "b"))
    }

    @Test
    fun disabledAccountsAreSkipped() {
        val picked = select(
            listOf(account("a", 0, active = true, enabled = false), account("b", 1)),
            RotationMode.FAILOVER,
        )
        assertEquals("b", picked?.id)
    }

    private fun select(accounts: List<AccountEntity>, mode: RotationMode): AccountEntity? =
        AccountSelector(Random(0)).choose(accounts, policy(mode), emptySet(), 1_000)

    private fun policy(mode: RotationMode) = ProviderPolicyEntity(
        providerId = "openai",
        mode = mode.name,
        autoFailover = true,
        maxSwitches = 3,
        cooldownSeconds = 60,
    )

    private fun account(
        id: String,
        position: Int,
        active: Boolean = false,
        enabled: Boolean = true,
        cooldown: Long = 0,
        requests: Long = 0,
        weight: Int = 1,
    ) = AccountEntity(
        id = id,
        providerId = "openai",
        label = id,
        keyCipher = "",
        keyLast4 = "****",
        baseUrl = "https://api.openai.com/v1",
        authStyle = "BEARER",
        defaultModel = "gpt-5",
        enabled = enabled,
        active = active,
        position = position,
        weight = weight,
        requestCount = requests,
        successCount = 0,
        failureCount = 0,
        consecutiveFailures = 0,
        cooldownUntil = cooldown,
        lastUsedAt = 0,
        lastError = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
