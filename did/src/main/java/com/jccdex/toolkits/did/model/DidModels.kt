package com.jccdex.toolkits.did.model

data class DidAvatarCredential(
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

data class DidSyncEntry(
    val did: String,
    val addressLower: String,
    val document: String,
    val nickname: String
)

data class DidSyncResult(
    val entries: List<DidSyncEntry>
) {
    val addressesLower: Set<String> = entries.mapTo(linkedSetOf()) { it.addressLower }
}
