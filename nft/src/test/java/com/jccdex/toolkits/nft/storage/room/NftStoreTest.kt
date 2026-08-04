package com.jccdex.toolkits.nft.storage.room

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.nft.NftSdk
import com.jccdex.toolkits.nft.model.ChainType
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.WalletAccount
import com.jccdex.toolkits.nft.remote.SsrfGuard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NftStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun wrapperMethods_delegateToDaoAndObserveCollections() =
        runTest {
            val database = newDatabase()
            val store = NftStore(database.nftDao())

            try {
                val swtc =
                    SwtcNftEntity(
                        ownerAddress = "jcccc",
                        tokenId = "1",
                        fundCode = "FUND",
                        fundCodeName = "Fund Name",
                        issuer = "issuer",
                        tokenOwner = "jcccc",
                        tokenSender = "jcccc",
                        flags = null,
                        tokenInfos = null,
                        metadataUri = "https://example.com/meta.json",
                        image = "https://example.com/avatar.png",
                        name = "avatar",
                        description = null,
                        time = 10L,
                        hash = null,
                        block = 1L,
                        inservice = 1,
                        ledgerIndex = null
                    )
                val evm =
                    EvmNftItemEntity(
                        chainId = "0x1",
                        ownerAddress = "0xowner",
                        contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        tokenId = "1",
                        objectId = "obj",
                        blockchainId = 1,
                        ownerTimestamp = 10L,
                        imageUrl = "https://example.com/avatar.png",
                        metadata = """{"name":"avatar"}""",
                        tokenProtocol = 1,
                        title = "avatar",
                        description = null
                    )
                val collection =
                    EvmNftCollectionEntity(
                        chainId = "0x1",
                        ownerAddress = "0xowner",
                        contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        name = "Collection",
                        symbol = "COL",
                        tokenCount = 1,
                        ts = 1L
                    )

                assertThat(store.observeSwtcNfts("jcccc").first()).isEmpty()
                assertThat(store.observeAllEvmNftItems("0x1", "0xowner").first()).isEmpty()

                store.upsertNftMeta(
                    NftMetaEntity(
                        contract = "issuer",
                        tokenId = "1",
                        name = "cached",
                        image = "https://example.com/avatar.png",
                        tokenUri = "https://example.com/meta.json",
                        fullContent = """{"name":"cached"}"""
                    )
                )
                store.upsertSwtcNfts(listOf(swtc))
                store.upsertEvmNftItems(listOf(evm))
                database.nftDao().insertCollections(listOf(collection))

                assertThat(store.getNftMeta("issuer", "1")?.name).isEqualTo("cached")
                assertThat(store.getSwtcNftByIssuerAndTokenId("issuer", "1")).isEqualTo(swtc)
                assertThat(store.getSwtcNftByTokenId("jcccc", "1")).isEqualTo(swtc)
                assertThat(
                    store.getEvmNftItemByContractAndTokenId("0x1", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                ).isEqualTo(evm)
                assertThat(
                    store.getEvmNftItem("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                ).isEqualTo(evm)

                assertThat(store.observeSwtcNfts("jcccc").first()).hasSize(1)
                assertThat(
                    store.observeEvmNftItems("0x1", "0xOWNER", "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD").first()
                ).hasSize(1)
                assertThat(store.observeAllEvmNftItems("0x1", "0xOWNER").first()).hasSize(1)
                assertThat(database.nftDao().getNftCollectionsFlow("0x1", "0xowner").first()).hasSize(1)

                database.nftDao().updateTokenCount("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", 2)
                assertThat(
                    database.nftDao().getItemCount("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
                ).isEqualTo(1)

                store.deleteSwtcNftsByOwner("jcccc")
                store.deleteEvmNftItemsByCollection("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
                database.nftDao().deleteByChainAndOwner("0x1", "0xowner")

                assertThat(store.getSwtcNftByTokenId("jcccc", "1")).isNull()
                assertThat(
                    store.getEvmNftItem("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                ).isNull()
            } finally {
                database.close()
            }
        }

    @Test
    fun create_with_context_initializes_room_backed_sdk() =
        runTest {
            val databaseName = "nft-create-" + System.currentTimeMillis()
            val sdk = NftSdk.create(context, databaseName = databaseName)
            val database = NftRoomDatabase.getInstance(context, databaseName)

            try {
                assertThat(sdk).isNotNull()
            } finally {
                database.close()
            }
        }

    @Test
    fun daoWrappers_roundTripAndDelete() =
        runTest {
            val database = newDatabase()
            val store = NftStore(database.nftDao())

            try {
                val swtc =
                    SwtcNftEntity(
                        ownerAddress = "jcccc",
                        tokenId = "1",
                        fundCode = "FUND",
                        fundCodeName = "Fund",
                        issuer = "issuer",
                        tokenOwner = "jcccc",
                        tokenSender = "jcccc",
                        flags = null,
                        tokenInfos = null,
                        metadataUri = "https://example.com/meta.json",
                        image = "https://example.com/avatar.png",
                        name = "avatar",
                        description = null,
                        time = 1L,
                        hash = null,
                        block = 1L,
                        inservice = 1,
                        ledgerIndex = null
                    )
                val evm =
                    EvmNftItemEntity(
                        chainId = "0x1",
                        ownerAddress = "0xowner",
                        contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        tokenId = "1",
                        objectId = "obj",
                        blockchainId = 1,
                        ownerTimestamp = 1L,
                        imageUrl = "https://example.com/avatar.png",
                        metadata = """{"name":"avatar"}""",
                        tokenProtocol = 1,
                        title = "avatar",
                        description = null
                    )

                store.upsertSwtcNfts(emptyList())
                store.upsertEvmNftItems(emptyList())
                store.upsertSwtcNfts(listOf(swtc))
                store.upsertEvmNftItems(listOf(evm))

                assertEquals(swtc, store.getSwtcNftByIssuerAndTokenId("issuer", "1"))
                assertEquals(swtc, store.getSwtcNftByTokenId("jcccc", "1"))
                assertEquals(
                    evm,
                    store.getEvmNftItemByContractAndTokenId("0x1", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                )
                assertEquals(
                    evm,
                    store.getEvmNftItem("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                )

                store.deleteSwtcNftsByOwner("jcccc")
                store.deleteEvmNftItemsByCollection("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

                assertNull(store.getSwtcNftByTokenId("jcccc", "1"))
                assertNull(store.getEvmNftItem("0x1", "0xowner", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1"))
            } finally {
                database.close()
            }
        }

    @Test
    fun getAvatarCandidates_mapsSwtcAndEvmRows() =
        runTest {
            val database = newDatabase()
            val store = NftStore(database.nftDao())

            try {
                database.nftDao().upsertSwtcNfts(
                    listOf(
                        SwtcNftEntity(
                            ownerAddress = "jcccc",
                            tokenId = "1",
                            fundCode = "FUND",
                            fundCodeName = "Fund",
                            issuer = "issuer",
                            tokenOwner = "jcccc",
                            tokenSender = "jcccc",
                            flags = null,
                            tokenInfos = null,
                            metadataUri = null,
                            image = "https://example.com/avatar.png",
                            name = null,
                            description = null,
                            time = 1L,
                            hash = null,
                            block = 1L,
                            inservice = 1,
                            ledgerIndex = null
                        )
                    )
                )
                database.nftDao().upsertEvmNftItems(
                    listOf(
                        EvmNftItemEntity(
                            chainId = "0x1",
                            ownerAddress = "0xowner",
                            contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                            tokenId = "1",
                            imageUrl = "https://example.com/avatar.png",
                            metadata = """{"name":"avatar"}""",
                            title = "avatar"
                        )
                    )
                )

                val swtcCandidates = store.getAvatarCandidates(WalletAccount(address = "jcccc", chain = ChainType.SWTC))
                val evmCandidates = store.getAvatarCandidates(WalletAccount(address = "0xowner", chain = ChainType.ETH))

                assertThat(swtcCandidates).hasSize(1)
                assertThat(swtcCandidates.first().isSwtc).isTrue()
                assertThat(swtcCandidates.first().name).isEqualTo("Fund")

                assertThat(evmCandidates).hasSize(1)
                assertThat(evmCandidates.first().isSwtc).isFalse()
                assertThat(evmCandidates.first().contract).isEqualTo("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
            } finally {
                database.close()
            }
        }

    @Test
    fun resolveSwtcAvatar_prefersLocalMetaAndFallsBackToRow() =
        runTest {
            val database = newDatabase()
            val store = NftStore(database.nftDao())

            try {
                val vc =
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
                database.nftDao().upsertNftMeta(
                    NftMetaEntity(
                        contract = "issuer",
                        tokenId = "1",
                        name = "cached",
                        image = "https://example.com/avatar.png",
                        tokenUri = "https://example.com/meta.json",
                        fullContent = """{"name":"cached"}"""
                    )
                )

                val cached = store.resolveSwtcAvatar(vc)
                assertThat(cached?.name).isEqualTo("cached")
                assertThat(cached?.hasLocal).isTrue()
                assertThat(cached?.uri).isEqualTo("https://example.com/meta.json")

                database.nftDao().deleteNftMeta("issuer", "1")
                database.nftDao().upsertSwtcNfts(
                    listOf(
                        SwtcNftEntity(
                            ownerAddress = "jcccc",
                            tokenId = "1",
                            fundCode = "FUND",
                            fundCodeName = "Fund",
                            issuer = "issuer",
                            tokenOwner = "jcccc",
                            tokenSender = "jcccc",
                            flags = null,
                            tokenInfos = null,
                            metadataUri = "https://example.com/meta.json",
                            image = "https://example.com/avatar.png",
                            name = "avatar",
                            description = null,
                            time = 1L,
                            hash = null,
                            block = 1L,
                            inservice = 1,
                            ledgerIndex = null
                        )
                    )
                )

                val fallback = store.resolveSwtcAvatar(vc)
                assertThat(fallback?.name).isEqualTo("avatar")
                assertThat(fallback?.hasLocal).isTrue()
            } finally {
                database.close()
            }
        }

    @Test
    fun resolveEthrAvatar_prefersLocalMetaAndFallsBackToEvmRow() =
        runTest {
            val database = newDatabase()
            val resolver = mockk<EthTokenUriResolver>()
            val store = NftStore(database.nftDao(), resolver)

            try {
                val vc =
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
                coEvery {
                    resolver.resolveEthrTokenUri("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1", 1L)
                } returns "https://example.com/token.json"
                database.nftDao().upsertNftMeta(
                    NftMetaEntity(
                        contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        tokenId = "1",
                        name = "cached",
                        image = "https://example.com/avatar.png",
                        tokenUri = """{"bad":"json"}""",
                        fullContent = """{"name":"cached"}"""
                    )
                )

                val cached = store.resolveEthrAvatar(vc)
                assertThat(cached?.name).isEqualTo("cached")
                assertThat(cached?.uri).isEqualTo("https://example.com/token.json")
                assertThat(cached?.hasLocal).isTrue()

                database.nftDao().deleteNftMeta("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1")
                database.nftDao().upsertEvmNftItems(
                    listOf(
                        EvmNftItemEntity(
                            chainId = "0x1",
                            ownerAddress = "0xowner",
                            contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                            tokenId = "1",
                            imageUrl = null,
                            metadata = "ipfs://meta",
                            title = "avatar"
                        )
                    )
                )

                val fallback = store.resolveEthrAvatar(vc)
                assertThat(fallback?.name).isEqualTo("avatar")
                assertThat(fallback?.uri).isEqualTo("https://example.com/token.json")
                assertThat(fallback?.hasLocal).isFalse()
            } finally {
                database.close()
            }
        }

    @Test
    fun getInstance_returnsRoomBackedStore() =
        runTest {
            val databaseName = "nft-store-inst-" + System.nanoTime()
            val store = NftStore.getInstance(context, databaseName)
            val database = NftRoomDatabase.getInstance(context, databaseName)
            val tokenId = "inst-${System.nanoTime()}"

            try {
                store.upsertNftMeta(
                    NftMetaEntity(
                        contract = "issuer",
                        tokenId = tokenId,
                        name = "from-instance",
                        image = null,
                        tokenUri = null,
                        fullContent = null
                    )
                )

                assertThat(store.getNftMeta("issuer", tokenId)?.name).isEqualTo("from-instance")
            } finally {
                database.close()
            }
        }

    @Test
    fun resolveSwtcAvatar_returnsNullForInvalidCredential() =
        runTest {
            val database = newDatabase()
            try {
                val store = NftStore(database.nftDao())

                assertThat(store.resolveSwtcAvatar("""{"credentialSubject":{}}""")).isNull()
                assertThat(
                    store.resolveSwtcAvatar(
                        """{"credentialSubject":{"tokenId":"1"},"issuanceDate":"2025-01-01T00:00:00Z"}"""
                    )
                ).isNull()
            } finally {
                database.close()
            }
        }

    @Test
    fun resolveEthrAvatar_filtersJsonLookingResolverUri() =
        runTest {
            val database = newDatabase()
            val resolver = mockk<EthTokenUriResolver>()
            val store = NftStore(database.nftDao(), resolver)

            try {
                val vc =
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
                coEvery {
                    resolver.resolveEthrTokenUri("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "1", 1L)
                } returns """{"not":"a-uri"}"""
                database.nftDao().upsertNftMeta(
                    NftMetaEntity(
                        contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        tokenId = "1",
                        name = "cached",
                        image = "https://example.com/avatar.png",
                        tokenUri = """{"not":"a-uri"}""",
                        fullContent = """{"name":"cached"}"""
                    )
                )

                val result = store.resolveEthrAvatar(vc)

                assertThat(result?.uri).isEmpty()
                assertThat(result?.image).isEqualTo("https://example.com/avatar.png")
                assertThat(result?.hasLocal).isTrue()
            } finally {
                database.close()
            }
        }

    @Test
    fun fetchAndCacheNftMeta_ssrfGuardBlocksPrivateUrlWithoutHttp() =
        runTest {
            SsrfGuard.enabled = true
            val database = newDatabase()
            val store = NftStore(database.nftDao())
            val server = MockWebServer()
            server.start()

            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"name":"should-not-fetch"}""")
                )
                // MockWebServer binds to loopback — SsrfGuard must reject before HTTP.
                val uri = server.url("/meta.json").toString()
                assertTrue(uri.contains("127.0.0.1") || uri.contains("localhost"))

                assertNull(store.fetchAndCacheNftMeta("issuer", "blocked", uri))
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
                database.close()
                SsrfGuard.enabled = true
            }
        }

    @Test
    fun fetchAndCacheNftMeta_doesNotFollowHttpRedirect() =
        runTest {
            // Allow loopback MockWebServer so we can observe redirect handling.
            SsrfGuard.enabled = false
            val database = newDatabase()
            val store = NftStore(database.nftDao())
            val server = MockWebServer()
            server.start()

            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "/private.json")
                )
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"name":"should-not-follow"}""")
                )
                val uri = server.url("/meta.json").toString()
                assertNull(store.fetchAndCacheNftMeta("issuer", "redir", uri))
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
                database.close()
                SsrfGuard.enabled = true
            }
        }

    @Test
    fun fetchAndCacheNftMeta_returnsNullForBlankResponseBody() =
        runTest {
            SsrfGuard.enabled = false
            val database = newDatabase()
            val store = NftStore(database.nftDao())
            val server = MockWebServer()
            server.start()

            try {
                server.enqueue(MockResponse().setResponseCode(200).setBody("   "))
                val uri = server.url("/empty.json").toString()

                assertThat(store.fetchAndCacheNftMeta("issuer", "blank", uri)).isNull()
            } finally {
                server.shutdown()
                database.close()
                SsrfGuard.enabled = true
            }
        }

    @Test
    fun fetchAndCacheNftMeta_insertsUpdatesAndHandlesFailures() =
        runTest {
            SsrfGuard.enabled = false
            val database = newDatabase()
            val store = NftStore(database.nftDao())
            val server = MockWebServer()
            server.start()

            try {
                assertNull(store.fetchAndCacheNftMeta("issuer", "1", ""))

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"name":"avatar","image":"https://example.com/avatar.png"}""")
                )
                val tokenUri = server.url("/meta.json").toString()
                val first = store.fetchAndCacheNftMeta("issuer", "1", tokenUri)
                assertThat(first?.name).isEqualTo("avatar")
                assertThat(first?.image).isEqualTo("https://example.com/avatar.png")

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"name":"updated","image":"https://example.com/avatar2.png"}""")
                )
                val second = store.fetchAndCacheNftMeta("issuer", "1", tokenUri)
                assertThat(second?.name).isEqualTo("updated")
                assertThat(second?.image).isEqualTo("https://example.com/avatar2.png")

                server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
                assertNull(store.fetchAndCacheNftMeta("issuer", "2", server.url("/missing.json").toString()))
            } finally {
                server.shutdown()
                database.close()
                SsrfGuard.enabled = true
            }
        }

    private fun newDatabase() =
        Room.inMemoryDatabaseBuilder(context, NftRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}
