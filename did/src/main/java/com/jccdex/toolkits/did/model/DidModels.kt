package com.jccdex.toolkits.did.model

data class AvatarNftCredential(
    val credentialId: String,
    val image: String?,
    val name: String,
    val contract: String?,
    val tokenId: String,
    val issuer: String?,
    val tokenName: String?,
    val chainId: Long?,
    val isSwtc: Boolean,
    val ownerDid: String
)

data class DidWriteResult(
    val success: Boolean,
    val didDocument: String? = null
)
