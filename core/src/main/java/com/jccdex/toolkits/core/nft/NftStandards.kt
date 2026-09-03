package com.jccdex.toolkits.core.nft

/**
 * Canonical NFT credential standard strings (C-16).
 *
 * On-chain / did_DApp payloads use these literals; comparisons are case-insensitive.
 */
object NftStandards {
    const val JINGTUM_NFT = "jingtumNFT"
    const val ERC721 = "ERC-721"

    fun isJingtumNft(standard: String?): Boolean = standard.equals(JINGTUM_NFT, ignoreCase = true)

    fun isErc721(standard: String?): Boolean = standard.equals(ERC721, ignoreCase = true)
}
