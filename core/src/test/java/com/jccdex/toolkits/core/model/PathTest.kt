package com.jccdex.toolkits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTest {
    @Test
    fun isRoot_returnsTrueOnlyForDefaultSegments() {
        assertTrue(Path(chain = ChainType.ETH.bip44Code).isRoot())
        assertFalse(Path(chain = ChainType.ETH.bip44Code, account = 1).isRoot())
        assertFalse(Path(chain = ChainType.ETH.bip44Code, change = 1).isRoot())
        assertFalse(Path(chain = ChainType.ETH.bip44Code, index = 1).isRoot())
    }

    @Test
    fun root_factoryUsesChainBip44Code() {
        val path = Path.root(ChainType.SWTC)

        assertEquals(ChainType.SWTC.bip44Code, path.chain)
        assertTrue(path.isRoot())
    }

    @Test
    fun toString_formatsBip44Path() {
        val path = Path(chain = 2147483708L, account = 1, change = 2, index = 3)

        assertEquals("m/44'/2147483708'/1'/2/3", path.toString())
    }
}
