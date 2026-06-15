package com.jccdex.toolkits.did.model

enum class CredentialAuthorizationType {
    SELF,
    OTHERS
}

enum class UsageRights(val value: String) {
    AVATAR("avatar"),
    NON_COMMERCIAL_DISPLAY("non-commercial-display");

    companion object {
        fun fromValue(value: String): UsageRights? = entries.firstOrNull { it.value == value }
    }
}

data class NftCredentialRestrictions(
    val commercial: Boolean = false,
    val derivative: Boolean = false,
    val sublicense: Boolean = false,
    val territories: List<String> = emptyList(),
    val platforms: List<String> = emptyList()
)

/**
 * Aligns with did_DApp [UnifiedNFTCredentialData].
 */
data class UnifiedNftCredentialData(
    val type: CredentialAuthorizationType,
    val granteeDid: String,
    val ownerDid: String,
    val chainId: Long,
    val tokenId: String,
    val standard: String,
    val status: String = "Active",
    val contractAddress: String? = null,
    val nftIssuer: String? = null,
    val tokenName: String? = null,
    val usageRights: List<UsageRights>? = null,
    val restrictions: NftCredentialRestrictions? = null,
    val expirationDurationMs: Long = 365L * 24 * 60 * 60 * 1000
)

data class CredentialVerificationResult(
    val verified: Boolean,
    val results: String? = null
)

data class GranteeCredentialUpdateResult(
    val isUpdate: Boolean,
    val credential: String? = null,
    /**
     * True when [isUpdate] could not be determined because the owner DID document
     * could not be fetched (network/resolution failure), as opposed to the credential
     * being genuinely revoked or superseded. Callers that want to avoid showing a
     * transient failure as "invalidated" should treat this as "status unknown".
     */
    val fetchFailed: Boolean = false
)

data class QueryVcidResult(
    val isValid: Boolean,
    val credential: String? = null
)
