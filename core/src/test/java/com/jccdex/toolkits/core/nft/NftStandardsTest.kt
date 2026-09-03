package com.jccdex.toolkits.core.nft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NftStandardsTest {
    @Test
    fun standardMatchers_areCaseInsensitive() {
        assertTrue(NftStandards.isJingtumNft("jingtumNFT"))
        assertTrue(NftStandards.isJingtumNft("jingtumnft"))
        assertFalse(NftStandards.isJingtumNft("ERC-721"))

        assertTrue(NftStandards.isErc721("ERC-721"))
        assertTrue(NftStandards.isErc721("erc-721"))
        assertFalse(NftStandards.isErc721("jingtumNFT"))
    }
}
