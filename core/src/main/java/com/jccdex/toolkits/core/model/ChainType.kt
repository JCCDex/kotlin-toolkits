package com.jccdex.toolkits.core.model

/**
 * BIP44 chain identifiers shared across wallet / account / did / nft SDKs.
 */
enum class ChainType(
    val bip44Code: Long,
    val label: String,
    val nativeSymbol: String,
    val explorerUrl: String? = null,
    val evmChainId: Long? = null
) {
    ETH(2147483708L, "Ethereum", "ETH", "https://etherscan.io", 1L),
    BSC(2147492654L, "Binance", "BNB", "https://bscscan.com", 56L),
    POLYGON(
        2147484614L,
        "Polygon",
        "POL",
        "https://polygonscan.com",
        137L
    ),
    ARB1(
        2147492649L,
        "Arbitrum",
        "ETH",
        "https://arbiscan.io",
        42161L
    ),
    BASE(2147492101L, "Base", "ETH", "https://basescan.org", 8453L),
    SWTC(2147483963L, "SWTC", "SWTC", "https://swtcscan.jccdex.cn"),
    MOAC(2147483962L, "MOAC", "MOAC", "https://explorer.moac.io", 99L);

    fun isEvmChain(): Boolean =
        when (this) {
            ETH, BSC, POLYGON, ARB1, BASE, MOAC -> true
            SWTC -> false
        }

    fun isSwtcChain(): Boolean = this == SWTC

    fun getExplorerAddressUrl(address: String): String? {
        val baseUrl = explorerUrl ?: return null
        return when (this) {
            SWTC -> "$baseUrl/#/wallet/?wallet=$address"
            MOAC -> "$baseUrl/addr/$address"
            else -> "$baseUrl/address/$address"
        }
    }

    companion object {
        val HD_CHILD_CHAINS =
            listOf(
                ETH,
                BSC,
                POLYGON,
                SWTC,
                MOAC,
                BASE,
                ARB1
            )

        fun fromBip44Code(code: Long): ChainType? = entries.firstOrNull { it.bip44Code == code }

        fun fromChainId(chainId: Long): ChainType? = entries.firstOrNull { it.evmChainId == chainId }
    }
}

/** Lowercase hex EVM chain id (`0x1`), or null for non-EVM chains (C-20). */
fun ChainType.toEvmChainIdHex(): String? = evmChainId?.toEvmChainIdHex()

/** Formats a chain id as lowercase hex (`0x1`), consistent with M-13N normalization (C-20). */
fun Long.toEvmChainIdHex(): String = "0x${toString(16)}"
