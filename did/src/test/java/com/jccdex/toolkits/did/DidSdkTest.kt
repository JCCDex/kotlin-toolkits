package com.jccdex.toolkits.did

import android.util.Log
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.model.Did
import com.jccdex.toolkits.did.model.GenerateBase58PKResult
import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.model.ProfileVC
import com.jccdex.toolkits.did.model.PublishDidResult
import com.jccdex.toolkits.did.port.DidAvatarAsset
import com.jccdex.toolkits.did.port.IDidAvatarCredentialSource
import com.jccdex.toolkits.did.port.IDidAvatarResolver
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.service.DidCoreService
import com.jccdex.toolkits.did.service.IDidResolver
import com.jccdex.toolkits.did.util.ChecksumUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DidSdkTest {
    private val bridge = mockk<IDidBridge>(relaxed = true)
    private val coreService = mockk<DidCoreService>(relaxed = true)
    private val avatarResolver = mockk<IDidAvatarResolver>(relaxed = true)
    private val avatarCredentialSource = mockk<IDidAvatarCredentialSource>(relaxed = true)
    private val sdk = DidSdk(bridge, coreService, avatarResolver, avatarCredentialSource)

    private class MemoryDidStore : com.jccdex.toolkits.did.store.IDidStore {
        private val state = MutableStateFlow<List<DidEntity>>(emptyList())
        private val items = linkedMapOf<String, DidEntity>()

        override fun observeAll() = state.asStateFlow()

        override fun observe(did: String) = state.map { list -> list.firstOrNull { it.did == did } }

        override suspend fun get(did: String): DidEntity? = items[did]

        override suspend fun upsert(entity: DidEntity) {
            items[entity.did] = entity
            state.value = items.values.toList()
        }

        override suspend fun delete(did: String) {
            items.remove(did)
            state.value = items.values.toList()
        }
    }

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
    fun `formatAddress shortens long addresses`() {
        assertEquals("0x12***5678", sdk.formatAddress("0x12345678"))
        assertEquals("abc", sdk.formatAddress("abc"))
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
    fun `generateSwtcNft falls back to built-in builder when resolver is missing`() = runTest {
        val sdkWithoutResolver = DidSdk(bridge, coreService)

        val result = sdkWithoutResolver.generateSwtcNft(
            """
            {
              "credentialSubject": {
                "tokenId": "1",
                "nftIssuer": "issuer",
                "tokenName": "avatar"
              },
              "issuanceDate": "2025-01-01T00:00:00Z"
            }
            """.trimIndent()
        )

        assertEquals("issuer", result?.contract)
        assertEquals("1", result?.tokenId)
    }

    @Test
    fun `generateEthrNft falls back to built-in builder when resolver is missing`() = runTest {
        val sdkWithoutResolver = DidSdk(bridge, coreService)

        val result = sdkWithoutResolver.generateEthrNft(
            """
            {
              "credentialSubject": {
                "tokenId": "1",
                "contractAddress": "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                "chainId": 1
              },
              "issuanceDate": "2025-01-01T00:00:00Z"
            }
            """.trimIndent()
        )

        assertEquals("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", result?.contract)
        assertEquals("1", result?.tokenId)
        assertEquals(1L, result?.chainId)
    }

    @Test
    fun `generateProfileVC returns nft from resolver`() = runTest {
        val resolver = mockk<IDidResolver>(relaxed = true)
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.jccdex.toolkits.did.model.DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = DidEntity(did = did, doc = """
                {
                  "service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}],
                  "credentials":[{"id":"cred-1","credentialSubject":{"tokenId":"1","contractAddress":"0xabcdefabcdefabcdefabcdefabcdefabcdefabcd","chainId":1},"issuanceDate":"2025-01-01T00:00:00Z"}]
                }
            """.trimIndent())
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val didCore = DidCoreService(store, resolver)
        val nft = Nft(
            contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tokenId = "1",
            name = "avatar",
            uri = "https://example.com/meta.json",
            image = "https://example.com/avatar.png",
            hasLocal = true,
            issuanceDate = "2025-01-01T00:00:00Z",
            chainId = 1L
        )
        coEvery { avatarResolver.resolveEthrAvatar(any()) } returns nft
        val localSdk = DidSdk(bridge, didCore, avatarResolver, avatarCredentialSource)

        val result = localSdk.generateProfileVC("did:ethr:0x123")

        assertEquals("alice", result?.nickname)
        assertEquals("2025-01-01T00:00:00Z", result?.createdTime)
        assertEquals(nft, result?.nft)
    }

    @Test
    fun `create_with_custom_store_wires accessors and resolve`() = runTest {
        val did = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
        val localDoc =
            """
            {
              "created":"2025-01-01T00:00:00Z",
              "updated":"2025-01-02T00:00:00Z",
              "service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}],
              "verificationMethod":[{"id":"vm1","controller":"$did","type":"Ed25519","publicKeyBase58":"pub"}]
            }
            """.trimIndent()
        val newerDoc =
            """
            {
              "updated":"2025-01-03T00:00:00Z",
              "service":[{"type":"Profile","serviceEndpoint":{"nickname":"bob","preferredAvatar":"cred-1"}}]
            }
            """.trimIndent()
        val store = MemoryDidStore()
        store.upsert(DidEntity(did = did, doc = localDoc))
        val resolver = object : IDidResolver {
            override suspend fun resolve(did: String): String = newerDoc
        }
        val created = DidSdk.create(bridge, store, resolver)

        assertEquals("alice", created.nickname(localDoc))
        assertEquals(localDoc, created.getDidDocument(did)?.doc)
        assertEquals(localDoc, created.observeDidDocument(did).first()?.doc)
        assertThat(created.observeAllDidDocuments().first()).hasSize(1)
        assertEquals(
            Did(
                id = did,
                created = "2025-01-01 08:00:00",
                updated = "2025-01-02 08:00:00",
                verificationMethods = listOf(
                    com.jccdex.toolkits.did.model.VerificationMethod(
                        id = "vm1",
                        controller = did,
                        type = "Ed25519",
                        publicKeyBase58 = "pub",
                        isSelf = true
                    )
                )
            ),
            created.generateDid(did)
        )
        assertEquals(newerDoc, created.resolveDid(did))
        assertEquals(newerDoc, created.getDidDocument(did)?.doc)
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
                    credentialId = "$ownerDid#nft-$checksumContract-1-$ownerDid",
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
    fun `buildAvatarCredentialId matches did_DApp generateVCId format`() {
        val ownerDid = "did:ethr:0xOwner"
        val granteeDid = "did:ethr:0xGrantee"
        val contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        val checksumContract = ChecksumUtils.toChecksumAddress(contract)
        val evmId =
            sdk.buildAvatarCredentialId(
                ownerDid = ownerDid,
                asset =
                    DidAvatarAsset(
                        image = null,
                        name = "nft",
                        contract = contract,
                        tokenId = "tokenABC",
                        issuer = null,
                        tokenName = null,
                        chainId = 1L,
                        isSwtc = false
                    ),
                granteeDid = granteeDid
            )
        assertEquals("did:ethr:0xOwner#nft-$checksumContract-tokenABC-did:ethr:0xGrantee", evmId)

        val swtcOwner = "did:swtc:jOwner"
        val swtcGrantee = "did:swtc:jGrantee"
        val swtcId =
            sdk.buildAvatarCredentialId(
                ownerDid = swtcOwner,
                asset =
                    DidAvatarAsset(
                        image = null,
                        name = "nft",
                        contract = "jIssuer",
                        tokenId = "tid1",
                        issuer = "jIssuer",
                        tokenName = "Golden Sands",
                        chainId = null,
                        isSwtc = true
                    ),
                granteeDid = swtcGrantee
            )
        assertEquals("did:swtc:jOwner#nft-GoldenSands-jIssuer-tid1-did:swtc:jGrantee", swtcId)
    }

    @Test
    fun `buildGenerateAvatarVcParams uses did and avatar metadata`() {
        val selectedAvatar =
            DidAvatarCredential(
                credentialId = "cred-1",
                image = "https://example.com/avatar.png",
                name = "avatar",
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                issuer = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenName = "Avatar",
                chainId = 1L,
                isSwtc = false,
                ownerDid = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
            )

        val params = sdk.buildGenerateAvatarVcParams("secret", selectedAvatar.ownerDid, selectedAvatar)

        assertEquals("cred-1", params.getString("id"))
        assertEquals("secret", params.getString("privateKey"))
        assertEquals("0x1234567890abcdef1234567890abcdef12345678", params.getString("address"))
        assertEquals(selectedAvatar.ownerDid, params.getString("did"))
    }

    @Test
    fun `buildGenerateAvatarVcParams builds swtc avatar subject`() {
        val selectedAvatar =
            DidAvatarCredential(
                credentialId = "cred-1",
                image = "https://example.com/avatar.png",
                name = "avatar",
                contract = "issuer",
                tokenId = "1",
                issuer = "issuer",
                tokenName = "Token Name",
                chainId = null,
                isSwtc = true,
                ownerDid = "did:swtc:jcccc"
            )

        val params = sdk.buildGenerateAvatarVcParams("secret", selectedAvatar.ownerDid, selectedAvatar)
        val subject = params.getJSONObject("subject")

        assertEquals("did:swtc:jcccc", subject.getString("id"))
        assertEquals("did:swtc:jcccc", subject.getString("owner"))
        assertEquals(315, subject.getInt("chainId"))
        assertEquals("issuer", subject.getString("nftIssuer"))
        assertEquals("Token Name", subject.getString("tokenName"))
        assertEquals("jingtumNFT", subject.getString("standard"))
    }

    @Test
    fun `buildGenerateAvatarVcParams builds evm avatar subject`() {
        val selectedAvatar =
            DidAvatarCredential(
                credentialId = "cred-1",
                image = "https://example.com/avatar.png",
                name = "avatar",
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                issuer = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenName = "Token Name",
                chainId = 1L,
                isSwtc = false,
                ownerDid = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
            )

        val params = sdk.buildGenerateAvatarVcParams("secret", selectedAvatar.ownerDid, selectedAvatar)
        val subject = params.getJSONObject("subject")
        val checksumContract = ChecksumUtils.toChecksumAddress(selectedAvatar.contract!!)

        assertEquals(selectedAvatar.ownerDid, subject.getString("id"))
        assertEquals(selectedAvatar.ownerDid, subject.getString("owner"))
        assertEquals(1, subject.getInt("chainId"))
        assertEquals(checksumContract, subject.getString("contractAddress"))
        assertEquals("ERC-721", subject.getString("standard"))
    }

    @Test
    fun `getAvatarNftCredentials falls back to nft sdk candidate mapping`() = runTest {
        val account =
            com.jccdex.toolkits.did.model.WalletAccount(
                address = "0x1234567890abcdef1234567890abcdef12345678",
                chain = com.jccdex.toolkits.did.model.ChainType.ETH,
                publicKey = "pub"
            )
        val fallbackNftSdk = mockk<com.jccdex.toolkits.nft.NftSdk>()
        coEvery {
            fallbackNftSdk.getAvatarCandidates(any())
        } returns
            listOf(
                com.jccdex.toolkits.nft.model.AvatarCandidate(
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
        val sdkWithNft = DidSdk(bridge, coreService, null, null, fallbackNftSdk)

        val result = sdkWithNft.getAvatarNftCredentials(account)
        val ownerDid = sdkWithNft.toDid(account)
        val checksumContract = ChecksumUtils.toChecksumAddress("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

        assertEquals(1, result.size)
        assertEquals("avatar", result.first().name)
        assertEquals(ownerDid, result.first().ownerDid)
        assertEquals(checksumContract, result.first().contract)
        assertEquals("$ownerDid#nft-$checksumContract-1-$ownerDid", result.first().credentialId)
    }

    @Test
    fun `generateEthrNft uses nft sdk fallback`() = runTest {
        val nftSdk = mockk<com.jccdex.toolkits.nft.NftSdk>()
        coEvery {
            nftSdk.resolveEthrAvatar(any())
        } returns
            com.jccdex.toolkits.nft.model.Nft(
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                name = "avatar",
                uri = "https://example.com/token.json",
                issuanceDate = "2025-01-01T00:00:00Z",
                image = "https://example.com/avatar.png",
                hasLocal = true,
                chainId = 1L
            )
        val sdkWithNft = DidSdk(bridge, coreService, null, null, nftSdk)

        val result =
            sdkWithNft.generateEthrNft(
                """
                {"credentialSubject":{"tokenId":"1","contractAddress":"0xabcdefabcdefabcdefabcdefabcdefabcdefabcd","chainId":1},"issuanceDate":"2025-01-01T00:00:00Z"}
                """.trimIndent()
            )

        assertEquals(
            Nft(
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                name = "avatar",
                uri = "https://example.com/token.json",
                image = "https://example.com/avatar.png",
                hasLocal = true,
                issuanceDate = "2025-01-01T00:00:00Z",
                chainId = 1L
            ),
            result
        )
    }

    @Test
    fun `getAvatarNftCredentials uses nft sdk swtc branch`() = runTest {
        val nftSdk = mockk<com.jccdex.toolkits.nft.NftSdk>()
        coEvery {
            nftSdk.getAvatarCandidates(any())
        } returns
            listOf(
                com.jccdex.toolkits.nft.model.AvatarCandidate(
                    image = "https://example.com/avatar.png",
                    name = "avatar",
                    contract = "issuer",
                    tokenId = "1",
                    issuer = "issuer",
                    tokenName = "Token Name",
                    chainId = null,
                    isSwtc = true
                )
            )
        val sdkWithNft = DidSdk(bridge, coreService, null, null, nftSdk)
        val account =
            com.jccdex.toolkits.did.model.WalletAccount(
                address = "jcccc",
                chain = com.jccdex.toolkits.did.model.ChainType.SWTC,
                publicKey = "pub"
            )

        val result = sdkWithNft.getAvatarNftCredentials(account)
        val ownerDid = sdkWithNft.toDid(account)

        assertEquals(1, result.size)
        assertEquals("avatar", result.first().name)
        assertEquals(ownerDid, result.first().ownerDid)
        assertEquals("$ownerDid#nft-TokenName-issuer-1-$ownerDid", result.first().credentialId)
    }

    @Test
    fun `uploadInitialDidDoc publishes generated did doc`() = runTest {
        coEvery { bridge.call("generateDidDoc", any()) } returns """{"did":"did:ethr:0x123"}"""
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "0", message = "ok")
        coEvery { bridge.callAs("generatePublicKeyBase58", any(), GenerateBase58PKResult::class.java) } returns
            GenerateBase58PKResult(type = "Ed25519VerificationKey2018", publicKeyBase58 = "pub")
        coEvery { bridge.callAs("didStat", any(), com.jccdex.toolkits.did.model.DidStatResult::class.java) } returns
            com.jccdex.toolkits.did.model.DidStatResult(cid = "cid")
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = null
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val sdkWithStore = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = sdkWithStore.uploadInitialDidDoc("secret", "did:ethr:0x123", "nick")

        assertThat(result).isTrue()
        coVerify { bridge.call("generateDidDoc", any()) }
        coVerify { bridge.callAs("publishDid", any(), PublishDidResult::class.java) }
    }

    @Test
    fun `updateDidAvatar keeps address params for js bridge compatibility`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0

        val did = "did:ethr:0x1234567890AbcdEF1234567890aBcdef12345678"
        val selectedAvatar =
            DidAvatarCredential(
                credentialId = "$did#nft-0xAbcdefabcdefABCDefAbcdefAbcdefabcdefABCD-1-$did",
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
        val params = sdk.buildGenerateAvatarVcParams("0x1234", did, selectedAvatar)

        assertEquals(did.substringAfterLast(':'), params.getString("address"))
        assertEquals(did, params.getString("did"))
        assertEquals(selectedAvatar.credentialId, params.getString("id"))
    }

    @Test
    fun `generateDid maps stored document to did`() = runTest {
        val resolver = mockk<IDidResolver>(relaxed = true)
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) =
                DidEntity(
                    did = did,
                    doc = """{"created":"2025-01-01T00:00:00Z","updated":"2025-01-02T00:00:00Z","verificationMethod":[{"id":"vm1","controller":"did:ethr:0x123","type":"Ed25519","publicKeyBase58":"pub"}]}"""
                )
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, resolver), avatarResolver, avatarCredentialSource)

        val result = localSdk.generateDid("did:ethr:0x123")

        assertEquals(
            Did(
                id = "did:ethr:0x123",
                created = "2025-01-01 08:00:00",
                updated = "2025-01-02 08:00:00",
                verificationMethods = listOf(
                    com.jccdex.toolkits.did.model.VerificationMethod(
                        id = "vm1",
                        controller = "did:ethr:0x123",
                        type = "Ed25519",
                        publicKeyBase58 = "pub",
                        isSelf = true
                    )
                )
            ),
            result
        )
    }

    @Test
    fun `publishDidDelete publishes delete payload and clears local doc`() = runTest {
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "0", message = "ok")
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = DidEntity(did = did, doc = """{"updated":"2025-01-01T00:00:00Z"}""")
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = localSdk.publishDidDelete("secret", "did:ethr:0x123")

        assertThat(result).isTrue()
        coVerify { bridge.callAs("publishDid", any(), PublishDidResult::class.java) }
    }

    @Test
    fun `updateDidNickname keeps previous cid and saves nickname`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "0", message = "ok")
        coEvery { bridge.callAs("didStat", any(), com.jccdex.toolkits.did.model.DidStatResult::class.java) } returns
            com.jccdex.toolkits.did.model.DidStatResult(cid = "cid-1")
        coEvery { bridge.call("didResolve", any()) } returns """{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}},{"type":"IpfsStorage","serviceEndpoint":{"ipns":"ipns"}}],"updated":"2025-01-01T00:00:00Z"}"""
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = DidEntity(did = did, doc = """{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}},{"type":"IpfsStorage","serviceEndpoint":{"ipns":"ipns"}}],"updated":"2025-01-01T00:00:00Z"}""")
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = localSdk.updateDidNickname("secret", "did:ethr:0x123", "bob", "")

        assertThat(result).isTrue()
        coVerify { bridge.callAs("publishDid", any(), PublishDidResult::class.java) }
    }

    @Test
    fun `updateDidNickname falls back to core document and updates previous cid`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        val did = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
        val store = MemoryDidStore()
        store.upsert(
            DidEntity(
                did = did,
                doc =
                    """
                    {
                      "service":[
                        {"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}},
                        {"type":"IpfsStorage","serviceEndpoint":{"ipns":"ipns"}}
                      ],
                      "updated":"2025-01-01T00:00:00Z"
                    }
                    """.trimIndent()
            )
        )
        coEvery { bridge.call("didResolve", any()) } returns "{}"
        coEvery { bridge.callAs("didStat", any(), com.jccdex.toolkits.did.model.DidStatResult::class.java) } returns
            com.jccdex.toolkits.did.model.DidStatResult(cid = "cid-1")
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "0", message = "ok")
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = localSdk.updateDidNickname("secret", did, "bob", "")

        assertThat(result).isTrue()
        assertThat(store.get(did)?.doc).contains("\"nickname\":\"bob\"")
        assertThat(store.get(did)?.doc).contains("\"previousCid\":\"cid-1\"")
    }

    @Test
    fun `updateDidAvatar rewrites stored avatar credential`() = runTest {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        val did = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
        val store = MemoryDidStore()
        store.upsert(
            DidEntity(
                did = did,
                doc =
                    """
                    {
                      "service":[
                        {"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"old-cred"}},
                        {"type":"IpfsStorage","serviceEndpoint":{"ipns":"ipns"}}
                      ],
                      "credentials":[
                        {"id":"old-cred","credentialSubject":{"tokenId":"1","contractAddress":"0xabcdefabcdefabcdefabcdefabcdefabcdefabcd","chainId":1},"issuanceDate":"2025-01-01T00:00:00Z"}
                      ],
                      "updated":"2025-01-01T00:00:00Z"
                    }
                    """.trimIndent()
            )
        )
        coEvery { bridge.call("didResolve", any()) } returns "{}"
        coEvery { bridge.callAs("didStat", any(), com.jccdex.toolkits.did.model.DidStatResult::class.java) } throws IllegalStateException("no stat")
        coEvery { bridge.call("generateVC", any()) } returns """{"id":"new-cred","credentialSubject":{"tokenId":"1","contractAddress":"0xabcdefabcdefabcdefabcdefabcdefabcdefabcd","chainId":1},"issuanceDate":"2025-01-01T00:00:00Z"}"""
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "0", message = "ok")
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)
        val avatar =
            DidAvatarCredential(
                credentialId = "new-cred",
                image = "https://example.com/avatar.png",
                name = "avatar",
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                issuer = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenName = "Avatar",
                chainId = 1L,
                isSwtc = false,
                ownerDid = did
            )

        val result = localSdk.updateDidAvatar("secret", did, "", avatar)

        assertThat(result).isTrue()
        assertThat(store.get(did)?.doc).contains("\"preferredAvatar\":\"new-cred\"")
        assertThat(store.get(did)?.doc).contains("\"new-cred\"")
        assertThat(store.get(did)?.doc).contains("\"old-cred\"")
    }

    @Test
    fun `getProfile reads services array alias`() {
        val profile =
            sdk.getProfile(
                """
                {
                  "services":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}]
                }
                """.trimIndent()
            )

        assertEquals("alice", profile?.nickname)
        assertEquals("cred-1", profile?.preferredAvatar)
    }

    @Test
    fun `toDid formats moac as evm did`() {
        val account =
            WalletAccount(
                address = "0x1234567890abcdef1234567890abcdef12345678",
                chain = ChainType.MOAC,
                publicKey = "pub"
            )

        assertEquals("did:ethr:0x1234567890AbcdEF1234567890aBcdef12345678", sdk.toDid(account))
    }

    @Test
    fun `generateProfileVC returns swtc nft`() = runTest {
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) =
                DidEntity(
                    did = did,
                    doc =
                        """
                        {
                          "service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}],
                          "credentials":[{"id":"cred-1","credentialSubject":{"tokenId":"1","nftIssuer":"issuer","tokenName":"avatar"},"issuanceDate":"2025-01-01T00:00:00Z"}]
                        }
                        """.trimIndent()
                )
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val swtcNft =
            Nft(
                contract = "issuer",
                tokenId = "1",
                name = "avatar",
                uri = "",
                image = null,
                hasLocal = true,
                issuanceDate = "2025-01-01T00:00:00Z",
                chainId = null
            )
        coEvery { avatarResolver.resolveSwtcAvatar(any()) } returns swtcNft
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = localSdk.generateProfileVC("did:swtc:jcccc")

        assertEquals("alice", result?.nickname)
        assertEquals(swtcNft, result?.nft)
    }

    @Test
    fun `generateProfileVC returns null when document is missing`() = runTest {
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = null
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        assertNull(localSdk.generateProfileVC("did:ethr:0x123"))
    }

    @Test
    fun `generateProfileVC fetches remote nft metadata when needed`() = runTest {
        val nftSdk = mockk<com.jccdex.toolkits.nft.NftSdk>()
        coEvery { nftSdk.fetchAndCacheNftMeta(any(), any(), any()) } returns null
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) =
                DidEntity(
                    did = did,
                    doc =
                        """
                        {
                          "service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}],
                          "credentials":[{"id":"cred-1","credentialSubject":{"tokenId":"1","contractAddress":"0xabcdefabcdefabcdefabcdefabcdefabcdefabcd","chainId":1},"issuanceDate":"2025-01-01T00:00:00Z"}]
                        }
                        """.trimIndent()
                )
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val remoteNft =
            Nft(
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                name = "avatar",
                uri = "https://example.com/meta.json",
                image = null,
                hasLocal = false,
                issuanceDate = "2025-01-01T00:00:00Z",
                chainId = 1L
            )
        coEvery { avatarResolver.resolveEthrAvatar(any()) } returns remoteNft
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource, nftSdk)

        localSdk.generateProfileVC("did:ethr:0x1234567890abcdef1234567890abcdef12345678")

        coVerify { nftSdk.fetchAndCacheNftMeta(remoteNft.contract, remoteNft.tokenId, remoteNft.uri) }
    }

    @Test
    fun `publishDidDelete returns false when bridge rejects publish`() = runTest {
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "1", message = "failed")
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = DidEntity(did = did, doc = """{"updated":"2025-01-01T00:00:00Z"}""")
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        assertThat(localSdk.publishDidDelete("secret", "did:ethr:0x123")).isFalse()
    }

    @Test
    fun `uploadInitialDidDoc returns false when publish fails`() = runTest {
        coEvery { bridge.call("generateDidDoc", any()) } returns """{"did":"did:ethr:0x123"}"""
        coEvery { bridge.callAs("publishDid", any(), PublishDidResult::class.java) } returns
            PublishDidResult(code = "9", message = "failed")
        coEvery { bridge.callAs("generatePublicKeyBase58", any(), GenerateBase58PKResult::class.java) } returns
            GenerateBase58PKResult(type = "Ed25519VerificationKey2018", publicKeyBase58 = "pub")
        val store = object : com.jccdex.toolkits.did.store.IDidStore {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
            override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun get(did: String) = null
            override suspend fun upsert(entity: DidEntity) = Unit
            override suspend fun delete(did: String) = Unit
        }
        val localSdk = DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        assertThat(localSdk.uploadInitialDidDoc("secret", "did:ethr:0x123", "nick")).isFalse()
    }
}
