package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.sdk.DidSdk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DidSyncServiceTest {
    private val didSdk = mockk<DidSdk>()
    private val service = DidSyncService(didSdk)

    @Test
    fun `syncAccounts returns resolved did entries with nickname`() =
        runTest {
            val account = WalletAccount(address = "0xabc", chain = ChainType.ETH, publicKey = "pub")
            val document = """{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice"}}]}"""

            every { didSdk.toDid(account) } returns "did:ethr:0xabc"
            coEvery { didSdk.resolveDid("did:ethr:0xabc") } returns document
            every { didSdk.nickname(document) } returns "alice"

            val result = service.syncAccounts(listOf(account))

            assertThat(result.entries).hasSize(1)
            assertThat(result.entries.first().did).isEqualTo("did:ethr:0xabc")
            assertThat(result.entries.first().addressLower).isEqualTo("0xabc")
            assertThat(result.entries.first().nickname).isEqualTo("alice")
            assertThat(result.addressesLower).containsExactly("0xabc")
        }

    @Test
    fun `syncAccounts returns empty result for empty input`() =
        runTest {
            val result = service.syncAccounts(emptyList())

            assertThat(result.entries).isEmpty()
            assertThat(result.addressesLower).isEmpty()
        }

    @Test
    fun `syncAccounts skips blank did and resolve failures`() =
        runTest {
            val blankDidAccount = WalletAccount(address = "", chain = ChainType.ETH, publicKey = "pub")
            val failingAccount =
                WalletAccount(address = "0xdef", chain = ChainType.ETH, publicKey = "pub")

            every { didSdk.toDid(blankDidAccount) } returns ""
            every { didSdk.toDid(failingAccount) } returns "did:ethr:0xdef"
            coEvery { didSdk.resolveDid("did:ethr:0xdef") } throws IllegalStateException("offline")

            val result = service.syncAccounts(listOf(blankDidAccount, failingAccount))

            assertThat(result.entries).isEmpty()
        }

    @Test
    fun `syncAccounts skips empty resolve results`() =
        runTest {
            val account = WalletAccount(address = "jswtc", chain = ChainType.SWTC, publicKey = "pub")

            every { didSdk.toDid(account) } returns "did:swtc:jswtc"
            coEvery { didSdk.resolveDid("did:swtc:jswtc") } returns null

            val result = service.syncAccounts(listOf(account))

            assertThat(result.entries).isEmpty()
            assertThat(result.addressesLower).isEmpty()
        }

    @Test
    fun `syncAccounts isolates a corrupt account instead of aborting the batch`() =
        runTest {
            val corrupt = WalletAccount(address = "0xbad", chain = ChainType.ETH, publicKey = "pub")
            val valid = WalletAccount(address = "0xabc", chain = ChainType.ETH, publicKey = "pub")
            val document = """{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice"}}]}"""

            // Corrupt EVM address → toDid throws (data corruption, M-DID7 root cause).
            every { didSdk.toDid(corrupt) } throws IllegalArgumentException("bad address")
            every { didSdk.toDid(valid) } returns "did:ethr:0xabc"
            coEvery { didSdk.resolveDid("did:ethr:0xabc") } returns document
            every { didSdk.nickname(document) } returns "alice"

            val result = service.syncAccounts(listOf(corrupt, valid))

            // The valid account still syncs; the corrupt one is counted, not aborting the batch.
            assertThat(result.entries).hasSize(1)
            assertThat(result.entries.first().did).isEqualTo("did:ethr:0xabc")
            assertThat(result.failedCount).isEqualTo(1)
        }
}
