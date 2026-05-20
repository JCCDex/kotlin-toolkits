package com.jccdex.toolkits.nft.storage.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.nft.NftTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NftDaoTest {
    private lateinit var testDb: NftTestDatabase
    private lateinit var dao: NftDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = NftTestDatabase.inMemory(context)
        dao = testDb.nftDao
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun upsertNftMeta_batchAndQuery() =
        runTest {
            val first =
                NftMetaEntity(
                    contract = "0xabc",
                    tokenId = "1",
                    name = "n1",
                    image = "https://img/1.png",
                    tokenUri = "https://meta/1.json",
                    fullContent = """{"name":"n1"}"""
                )
            val second =
                NftMetaEntity(
                    contract = "0xdef",
                    tokenId = "2",
                    name = "n2",
                    image = null,
                    tokenUri = null,
                    fullContent = null
                )

            dao.upsertNftMeta(listOf(first, second))

            assertThat(dao.getNftMeta("0xabc", "1")?.name).isEqualTo("n1")
            assertThat(dao.getNftMeta("0xdef", "2")?.name).isEqualTo("n2")
        }

    @Test
    fun deleteNftMeta_removesRow() =
        runTest {
            dao.upsertNftMeta(
                NftMetaEntity(
                    contract = "issuer",
                    tokenId = "9",
                    name = "gone",
                    image = null,
                    tokenUri = null,
                    fullContent = null
                )
            )

            dao.deleteNftMeta("issuer", "9")

            assertThat(dao.getNftMeta("issuer", "9")).isNull()
        }

    @Test
    fun observeSwtcNfts_emitsPersistedRows() =
        runTest {
            dao.upsertSwtcNfts(
                listOf(
                    swtcEntity(owner = "jowner", tokenId = "1"),
                    swtcEntity(owner = "jowner", tokenId = "2")
                )
            )

            val rows = dao.observeSwtcNfts("jowner").first()

            assertThat(rows).hasSize(2)
            assertThat(rows.map { it.tokenId }).containsExactlyInAnyOrder("1", "2")
        }

    @Test
    fun collectionFlow_andTokenCountUpdate() =
        runTest {
            val collection =
                EvmNftCollectionEntity(
                    chainId = "0x1",
                    ownerAddress = "0xowner",
                    contractAddress = "0xcontract",
                    name = "Col",
                    symbol = "C",
                    tokenCount = 1,
                    ts = 10L
                )
            dao.insertCollections(listOf(collection))
            dao.updateTokenCount("0x1", "0xowner", "0xcontract", 5)

            val rows = dao.getNftCollectionsFlow("0x1", "0xowner").first()

            assertThat(rows).hasSize(1)
            assertThat(rows.first().tokenCount).isEqualTo(5)
        }

    @Test
    fun deleteByChainAndOwner_clearsCollections() =
        runTest {
            dao.insertCollections(
                listOf(
                    EvmNftCollectionEntity(
                        chainId = "0x1",
                        ownerAddress = "0xowner",
                        contractAddress = "0xc1",
                        name = "C1",
                        symbol = "C1",
                        tokenCount = 1,
                        ts = 1L
                    )
                )
            )

            dao.deleteByChainAndOwner("0x1", "0xowner")

            assertThat(dao.getNftCollectionsFlow("0x1", "0xowner").first()).isEmpty()
        }

    private fun swtcEntity(
        owner: String,
        tokenId: String
    ): SwtcNftEntity =
        SwtcNftEntity(
            ownerAddress = owner,
            tokenId = tokenId,
            fundCode = "FUND",
            fundCodeName = "Fund",
            issuer = "issuer",
            tokenOwner = owner,
            tokenSender = owner,
            flags = null,
            tokenInfos = null,
            metadataUri = null,
            image = "https://example.com/nft.png",
            name = "nft-$tokenId",
            description = null,
            time = 1L,
            hash = null,
            block = 1L,
            inservice = 1,
            ledgerIndex = null
        )
}
