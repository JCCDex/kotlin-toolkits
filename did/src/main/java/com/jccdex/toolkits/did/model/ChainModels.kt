package com.jccdex.toolkits.did.model

typealias ChainType = com.jccdex.toolkits.core.model.ChainType

typealias WalletAccount = com.jccdex.toolkits.core.model.WalletAccount

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
