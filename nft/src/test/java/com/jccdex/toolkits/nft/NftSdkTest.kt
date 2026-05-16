package com.jccdex.toolkits.nft

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.nft.model.ChainType
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.WalletAccount
import com.jccdex.toolkits.nft.storage.room.EvmNftItemEntity
import com.jccdex.toolkits.nft.storage.room.NftRoomDatabase
import com.jccdex.toolkits.nft.storage.room.SwtcNftEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NftSdkTest {
    @Test
    fun `resolveEthrAvatar uses token uri instead of metadata json`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database =
            Room.inMemoryDatabaseBuilder(context, NftRoomDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val tokenUriResolver = mockk<EthTokenUriResolver>()
        val sdk = NftSdk.create(database.nftDao(), tokenUriResolver)

        try {
            database.nftDao().upsertEvmNftItems(
                listOf(
                    EvmNftItemEntity(
                        chainId = "0x1",
                        ownerAddress = "0xowner",
                        contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        tokenId = "1",
                        imageUrl = "https://example.com/avatar.png",
                        metadata = """{"name":"avatar","image":"https://example.com/avatar.png"}""",
                        title = "avatar"
                    )
                )
            )
            coEvery {
                tokenUriResolver.resolveEthrTokenUri(
                    "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "1",
                    1L
                )
            } returns "https://example.com/token.json"

            val result =
                sdk.resolveEthrAvatar(
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

            assertEquals("https://example.com/token.json", result?.uri)
        } finally {
            database.close()
        }
    }

    @Test
    fun `fetchAndCacheNftMeta caches remote metadata`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database =
            Room.inMemoryDatabaseBuilder(context, NftRoomDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val sdk = NftSdk.create(database.nftDao())
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"avatar","image":"https://example.com/avatar.png"}""")
        )
        server.start()

        try {
            val tokenUri = server.url("/meta.json").toString()
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
                        metadataUri = tokenUri,
                        image = null,
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

            assertEquals("https://example.com/avatar.png", sdk.fetchAndCacheNftMeta("issuer", "1", tokenUri)?.image)
        } finally {
            server.shutdown()
            database.close()
        }
    }

    @Test
    fun `getAvatarCandidates maps swtc nft rows`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database =
            Room.inMemoryDatabaseBuilder(context, NftRoomDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val sdk = NftSdk.create(database.nftDao())
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

        val account =
            WalletAccount(
                address = "jcccc",
                chain = ChainType.SWTC,
                publicKey = "pub"
            )

        val candidates = sdk.getAvatarCandidates(account)
        assertEquals(1, candidates.size)
        assertEquals("avatar", candidates.first().name)
    }
}
