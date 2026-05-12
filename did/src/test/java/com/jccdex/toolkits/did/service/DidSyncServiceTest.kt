package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.DidSdk
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.WalletAccount
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
    fun `syncAccounts returns resolved did entries with nickname`() = runTest {
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
    fun `syncAccounts skips empty resolve results`() = runTest {
        val account = WalletAccount(address = "jswtc", chain = ChainType.SWTC, publicKey = "pub")

        every { didSdk.toDid(account) } returns "did:swtc:jswtc"
        coEvery { didSdk.resolveDid("did:swtc:jswtc") } returns null

        val result = service.syncAccounts(listOf(account))

        assertThat(result.entries).isEmpty()
        assertThat(result.addressesLower).isEmpty()
    }
}
