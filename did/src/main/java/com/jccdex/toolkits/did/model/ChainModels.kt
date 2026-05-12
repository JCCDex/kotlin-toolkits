package com.jccdex.toolkits.did.model

import java.util.UUID

enum class ChainType(
    val label: String,
    val nativeSymbol: String,
    val evmChainId: Long? = null
) {
    ETH("Ethereum", "ETH", 1L),
    BSC("Binance", "BNB", 56L),
    POLYGON("Polygon", "POL", 137L),
    ARB1("Arbitrum", "ETH", 42161L),
    BASE("Base", "ETH", 8453L),
    SWTC("SWTC", "SWTC"),
    MOAC("MOAC", "MOAC", 99L);

    fun isEvmChain(): Boolean = this != SWTC
}

data class WalletAccount(
    val id: String = UUID.randomUUID().toString(),
    val address: String,
    val chain: ChainType = ChainType.ETH,
    val name: String = "",
    val isHD: Boolean = false,
    val parentId: String? = null,
    val publicKey: String = ""
)

data class GenerateBase58PKResult(
    val type: String,
    val publicKeyBase58: String
)

data class PublishDidResult(
    val code: String,
    val message: String
)

data class DidStatResult(
    val cid: String?
)

data class VerificationMethod(
    val id: String,
    val controller: String,
    val type: String,
    val publicKeyBase58: String,
    val isSelf: Boolean
)

data class Did(
    val id: String,
    val created: String,
    val updated: String,
    val verificationMethods: List<VerificationMethod>
)

data class Profile(
    val nickname: String,
    val preferredAvatar: String
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

data class ProfileVC(
    val nickname: String,
    val bio: String,
    val createdTime: String,
    val nft: Nft?
)
