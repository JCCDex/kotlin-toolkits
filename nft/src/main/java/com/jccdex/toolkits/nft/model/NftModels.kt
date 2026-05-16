package com.jccdex.toolkits.nft.model

import java.util.UUID

enum class ChainType(
    val evmChainId: Long? = null
) {
    ETH(1L),
    BSC(56L),
    POLYGON(137L),
    ARB1(42161L),
    BASE(8453L),
    SWTC,
    MOAC(99L);

    fun isEvmChain(): Boolean = this != SWTC
}

data class WalletAccount(
    val id: String = UUID.randomUUID().toString(),
    val address: String,
    val chain: ChainType = ChainType.ETH,
    val isHD: Boolean = false,
    val parentId: String? = null,
    val publicKey: String = ""
)

data class Nft(
    val contract: String,
    val tokenId: String,
    val name: String,
    val uri: String,
    val issuanceDate: String,
    val image: String?,
    val hasLocal: Boolean,
    val chainId: Long?
)

data class AvatarCandidate(
    val image: String?,
    val name: String,
    val contract: String?,
    val tokenId: String,
    val issuer: String?,
    val tokenName: String?,
    val chainId: Long?,
    val isSwtc: Boolean
)

interface EthTokenUriResolver {
    suspend fun resolveEthrTokenUri(
        contract: String,
        tokenId: String,
        chainId: Long
    ): String?
}
