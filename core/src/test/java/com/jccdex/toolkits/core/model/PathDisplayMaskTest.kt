package com.jccdex.toolkits.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Path.toString()` must display the BIP44 coin type with the hardened bit cleared,
 * mirroring the JS wallet bridge (`path.chain & 0x7FFFFFFF`) and Swift
 * `Path.derivationPath` (`let displayChain = self.chain & 0x7FFF_FFFF`).
 *
 * Ported from swift-toolkits `Tests/SwiftCoreTests/SwiftCoreTests.swift`
 * ("修复 derivePath" regression suite) so both SDKs stay byte-identical.
 */
class PathDisplayMaskTest {
    @Test
    fun toString_clearsHardenedBitForEveryChainType() {
        assertEquals("m/44'/60'/0'/0/0", Path(chain = ChainType.ETH.bip44Code).toString())
        assertEquals("m/44'/9006'/0'/0/0", Path(chain = ChainType.BSC.bip44Code).toString())
        assertEquals("m/44'/966'/0'/0/0", Path(chain = ChainType.POLYGON.bip44Code).toString())
        assertEquals("m/44'/9001'/0'/0/0", Path(chain = ChainType.ARB1.bip44Code).toString())
        assertEquals("m/44'/8453'/0'/0/0", Path(chain = ChainType.BASE.bip44Code).toString())
        assertEquals("m/44'/315'/0'/0/0", Path(chain = ChainType.SWTC.bip44Code).toString())
        assertEquals("m/44'/314'/0'/0/0", Path(chain = ChainType.MOAC.bip44Code).toString())
    }

    @Test
    fun toString_masksRawHardenedValue() {
        // 2147483708 == 0x8000003C (ETH bip44Code); 0x8000003C and 0x7FFFFFFF == 60.
        assertEquals("m/44'/60'/0'/0/3", Path(chain = 2147483708L, index = 3).toString())
    }

    @Test
    fun toString_leavesAlreadyUnmaskedChainUntouched() {
        assertEquals("m/44'/60'/0'/0/0", Path(chain = 60).toString())
        assertEquals("m/44'/0'/0'/0/0", Path(chain = 0).toString())
    }

    @Test
    fun toString_rendersAccountAndChangeSegmentsLikeSdkFormat() {
        val path = Path(chain = ChainType.SWTC.bip44Code, account = 1, change = 2, index = 3)

        assertEquals("m/44'/315'/1'/2/3", path.toString())
    }

    @Test
    fun toString_negativeChainIsDefensiveOnly() {
        // -4 and 0x7FFFFFFF == 2147483644; negative chain never occurs in practice.
        assertEquals("m/44'/2147483644'/0'/0/0", Path(chain = -4).toString())
    }
}
