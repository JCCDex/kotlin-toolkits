package com.jccdex.toolkits.did

import android.app.Application
import android.util.Log
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.port.DidAvatarAsset
import com.jccdex.toolkits.did.port.DidAvatarCredentialSource
import com.jccdex.toolkits.did.port.DidAvatarResolver
import com.jccdex.toolkits.did.port.DidBridge
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.service.DidCoreService
import com.jccdex.toolkits.did.util.ChecksumUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DidSdkTest {
    private val bridge = mockk<DidBridge>(relaxed = true)
    private val coreService = mockk<DidCoreService>(relaxed = true)
    private val avatarResolver = mockk<DidAvatarResolver>(relaxed = true)
    private val avatarCredentialSource = mockk<DidAvatarCredentialSource>(relaxed = true)

    private val sdk = DidSdk(bridge, coreService, avatarResolver, avatarCredentialSource)

    @Test
    fun `toDid formats wallet addresses`() {
        val evm =
            WalletAccount(
                address = "0x1234567890abcdef1234567890abcdef12345678",
                chain = ChainType.ETH,
                publicKey = "pub"
            )
        val swtc =
            WalletAccount(
                address = "jcccc",
                chain = ChainType.SWTC,
                publicKey = "pub"
            )

        assertEquals("did:ethr:0x1234567890AbcdEF1234567890aBcdef12345678", sdk.toDid(evm))
        assertEquals("did:swtc:jcccc", sdk.toDid(swtc))
        assertEquals("", sdk.toDid(null))
    }

    @Test
    fun `getProfile returns null without profile service`() {
        assertNull(sdk.getProfile("""{"service":[]}"""))
    }

    @Test
    fun `resolveDid prefers core service when available`() = runTest {
        coEvery { coreService.resolveAndSaveDid("did:test:1") } returns "resolved"

        assertEquals("resolved", sdk.resolveDid("did:test:1"))
    }

    @Test
    fun `generateSwtcNft delegates to avatar resolver`() = runTest {
        val expected =
            Nft(
                contract = "issuer",
                tokenId = "1",
                name = "avatar",
                uri = "https://example.com/meta.json",
                image = "https://example.com/avatar.png",
                hasLocal = true,
                issuanceDate = "2025-01-01T00:00:00Z",
                chainId = null
            )
        coEvery { avatarResolver.resolveSwtcAvatar(any()) } returns expected

        assertEquals(expected, sdk.generateSwtcNft("""{"credentialSubject":{}}"""))
    }

    @Test
    fun `getAvatarNftCredentials maps candidates into sdk credentials`() = runTest {
        val account =
            WalletAccount(
                address = "0x1234567890abcdef1234567890abcdef12345678",
                chain = ChainType.ETH,
                publicKey = "pub"
            )
        coEvery {
            avatarCredentialSource.getAvatarCandidates(account)
        } returns
            listOf(
                DidAvatarAsset(
                    image = "https://example.com/avatar.png",
                    name = "avatar",
                    contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    tokenId = "1",
                    issuer = null,
                    tokenName = "Avatar",
                    chainId = 1L,
                    isSwtc = false
                )
            )

        val result = sdk.getAvatarNftCredentials(account)
        val ownerDid = sdk.toDid(account)
        val checksumContract = ChecksumUtils.toChecksumAddress("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

        assertEquals(
            listOf(
                DidAvatarCredential(
                    credentialId = "$ownerDid#nft-$checksumContract-1",
                    image = "https://example.com/avatar.png",
                    name = "avatar",
                    contract = checksumContract,
                    tokenId = "1",
                    issuer = checksumContract,
                    tokenName = "Avatar",
                    chainId = 1L,
                    isSwtc = false,
                    ownerDid = ownerDid
                )
            ),
            result
        )
    }

    @Test
    fun `updateDidAvatar keeps address params for js bridge compatibility`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0

        val did = "did:ethr:0x1234567890AbcdEF1234567890aBcdef12345678"
        val selectedAvatar =
            DidAvatarCredential(
                credentialId = "$did#nft-0xAbcdefabcdefABCDefAbcdefAbcdefabcdefABCD-1",
                image = null,
                name = "avatar",
                contract = "0xAbcdefabcdefABCDefAbcdefAbcdefabcdefABCD",
                tokenId = "1",
                issuer = "0xAbcdefabcdefABCDefAbcdefAbcdefabcdefABCD",
                tokenName = "Avatar",
                chainId = 1L,
                isSwtc = false,
                ownerDid = did
            )
        val currentDoc =
            """
            {
              "service": [
                {
                  "id": "$did#profile",
                  "type": "Profile",
                  "serviceEndpoint": {
                    "nickname": "nick",
                    "preferredAvatar": ""
                  }
                }
              ],
              "credentials": []
            }
            """.trimIndent()

        val params = sdk.buildGenerateAvatarVcParams("0x1234", did, selectedAvatar)

        assertEquals(did.substringAfterLast(':'), params.getString("address"))
        assertEquals(did, params.getString("did"))
        assertEquals(selectedAvatar.credentialId, params.getString("id"))
    }
}
