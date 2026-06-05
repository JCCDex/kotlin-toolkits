package com.jccdex.toolkits.nft.model

typealias ChainType = com.jccdex.toolkits.core.model.ChainType

typealias WalletAccount = com.jccdex.toolkits.core.model.WalletAccount

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

data class NftMetadataFields(
    val image: String?,
    val name: String?,
    val description: String?
)

data class CredentialImageRequest(
    val imageUrl: String?,
    val metadataUri: String?,
    val chainId: Long? = null,
    val contractAddress: String? = null,
    val tokenId: String? = null
)

data class ResolvedCredentialImage(
    val url: String,
    val cacheKey: String
)

interface EthTokenUriResolver {
    suspend fun resolveEthrTokenUri(
        contract: String,
        tokenId: String,
        chainId: Long
    ): String?
}
