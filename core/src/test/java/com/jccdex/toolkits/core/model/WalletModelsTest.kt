package com.jccdex.toolkits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletModelsTest {
    @Test
    fun chainType_fromBip44Code_and_chainId() {
        assertEquals(ChainType.ETH, ChainType.fromBip44Code(ChainType.ETH.bip44Code))
        assertEquals(ChainType.ETH, ChainType.fromChainId(1L))
        assertEquals(ChainType.SWTC, ChainType.fromBip44Code(ChainType.SWTC.bip44Code))
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
    }
}
