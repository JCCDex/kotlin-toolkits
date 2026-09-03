package com.jccdex.toolkits.core.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WalletModelsTest {
    @Test
    fun chainType_fromBip44Code_and_chainId() {
        assertEquals(ChainType.ETH, ChainType.fromBip44Code(ChainType.ETH.bip44Code))
        assertEquals(ChainType.ETH, ChainType.fromChainId(1L))
        assertEquals(ChainType.SWTC, ChainType.fromBip44Code(ChainType.SWTC.bip44Code))
    }

    @Test
    fun walletAccount_generatesDistinctIdsByDefault() {
        val first =
            WalletAccount(
                address = "0x1",
                publicKey = "pk"
            )
        val second =
            WalletAccount(
                address = "0x2",
                publicKey = "pk"
            )

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun walletAccount_hdClassification() {
        val root =
            WalletAccount(
                address = "root",
                chain = ChainType.SWTC,
                isHD = true,
                path = Path.root(ChainType.SWTC),
                publicKey = "pk"
            )
        assertTrue(root.isRootHD())
        assertFalse(root.isSubHD())

        val child =
            WalletAccount(
                address = "child",
                chain = ChainType.ETH,
                isHD = true,
                parentId = root.id,
                path = Path(chain = ChainType.ETH.bip44Code, index = 1),
                publicKey = "pk2"
            )
        assertTrue(child.isSubHD())
        assertFalse(child.isTraditional())

        val hdWithParentOnly =
            WalletAccount(
                address = "hd-parent",
                chain = ChainType.ETH,
                isHD = true,
                parentId = root.id,
                path = Path.root(ChainType.ETH),
                publicKey = "pk3"
            )
        assertTrue(hdWithParentOnly.isSubHD())

        val traditional =
            WalletAccount(
                address = "trad",
                chain = ChainType.ETH,
                isHD = false,
                publicKey = "pk4"
            )
        assertTrue(traditional.isTraditional())
        assertFalse(traditional.isRootHD())
        assertFalse(traditional.isSubHD())
        assertTrue(traditional.isNonRoot())

        val hdNonRootPathNoParent =
            WalletAccount(
                address = "hd-path-only",
                chain = ChainType.ETH,
                isHD = true,
                parentId = null,
                path = Path(chain = ChainType.ETH.bip44Code, index = 1),
                publicKey = "pk5"
            )
        assertTrue(hdNonRootPathNoParent.isSubHD())
        assertFalse(hdNonRootPathNoParent.isRootHD())
        assertTrue(hdNonRootPathNoParent.isNonRoot())
    }

    @Test
    fun toBip44JsonArray_emptyList() {
        val array: JSONArray = emptyList<ChainType>().toBip44JsonArray()

        assertEquals(0, array.length())
    }

    @Test
    fun chainType_helpers_and_urls() {
        assertTrue(ChainType.ETH.isEvmChain())
        assertTrue(ChainType.BSC.isEvmChain())
        assertTrue(ChainType.POLYGON.isEvmChain())
        assertTrue(ChainType.ARB1.isEvmChain())
        assertTrue(ChainType.BASE.isEvmChain())
        assertTrue(ChainType.MOAC.isEvmChain())
        assertFalse(ChainType.SWTC.isEvmChain())
        assertTrue(ChainType.SWTC.isSwtcChain())
        assertFalse(ChainType.BSC.isSwtcChain())

        assertEquals("https://etherscan.io/address/0xabc", ChainType.ETH.getExplorerAddressUrl("0xabc"))
        assertEquals("https://bscscan.com/address/0xabc", ChainType.BSC.getExplorerAddressUrl("0xabc"))
        assertEquals("https://polygonscan.com/address/0xabc", ChainType.POLYGON.getExplorerAddressUrl("0xabc"))
        assertEquals("https://arbiscan.io/address/0xabc", ChainType.ARB1.getExplorerAddressUrl("0xabc"))
        assertEquals("https://basescan.org/address/0xabc", ChainType.BASE.getExplorerAddressUrl("0xabc"))
        assertEquals(
            "https://swtcscan.jccdex.cn/#/wallet/?wallet=jswtc",
            ChainType.SWTC.getExplorerAddressUrl("jswtc")
        )
        assertEquals("https://explorer.moac.io/addr/0xmoac", ChainType.MOAC.getExplorerAddressUrl("0xmoac"))
        assertEquals(null, ChainType.fromBip44Code(-1L))
        assertEquals(null, ChainType.fromChainId(-1L))
        assertEquals(ChainType.ETH, ChainType.fromChainId(1L))
        assertEquals(ChainType.BSC, ChainType.fromChainId(56L))
        assertEquals(ChainType.POLYGON, ChainType.fromChainId(137L))
        assertEquals(ChainType.MOAC, ChainType.fromChainId(99L))
        assertEquals(
            listOf(
                ChainType.ETH,
                ChainType.BSC,
                ChainType.POLYGON,
                ChainType.SWTC,
                ChainType.MOAC,
                ChainType.BASE,
                ChainType.ARB1
            ),
            ChainType.HD_CHILD_CHAINS
        )
    }

    @Test
    fun toBip44JsonArray_serializesCodes() {
        val array: JSONArray = listOf(ChainType.ETH, ChainType.SWTC).toBip44JsonArray()

        assertEquals(2, array.length())
        assertEquals(ChainType.ETH.bip44Code, array.getLong(0))
        assertEquals(ChainType.SWTC.bip44Code, array.getLong(1))
    }
}
