package com.jccdex.toolkits.did.util

import android.util.Log
import com.jccdex.toolkits.core.nft.NftStandards
import com.jccdex.toolkits.did.model.CredentialAuthorizationType
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.NftCredentialRestrictions
import com.jccdex.toolkits.did.model.UnifiedNftCredentialData
import com.jccdex.toolkits.did.model.UsageRights
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object DidCredentialHelper {
    private const val TAG = "DidCredentialHelper"
    const val VC_TYPE_OWNERSHIP = "NFTOwnership"
    const val VC_TYPE_USAGE_AUTHORIZATION = "NFTUsageAuthorization"
    const val VC_TYPE_FILE_ACCESS_AUTHORIZATION = "FileAccessAuthorization"
    const val STANDARD_JINGTUM_NFT = NftStandards.JINGTUM_NFT
    const val STANDARD_ERC721 = NftStandards.ERC721
    const val CONTEXT_TYPE_OWNERSHIP = "ownership"
    const val CONTEXT_TYPE_USAGE_AUTHORIZATION = "usageAuthorization"

    fun isSwtcOwnerDid(ownerDid: String): Boolean = ownerDid.startsWith("did:swtc:")

    fun isEthrOwnerDid(ownerDid: String): Boolean = ownerDid.startsWith("did:ethr:")

    private val whitespaceRegex = "\\s+".toRegex()

    fun generateVcId(data: UnifiedNftCredentialData): String =
        if (isSwtcOwnerDid(data.ownerDid)) {
            val tokenNameClean = data.tokenName.orEmpty().replace(whitespaceRegex, "")
            "${data.ownerDid}#nft-$tokenNameClean-${data.nftIssuer.orEmpty()}-${data.tokenId}-${data.granteeDid}"
        } else {
            val checksumContract =
                data.contractAddress
                    ?.let { ChecksumUtils.toChecksumAddress(it) }
                    .orEmpty()
            "${data.ownerDid}#nft-$checksumContract-${data.tokenId}-${data.granteeDid}"
        }

    fun validateCredentialData(data: UnifiedNftCredentialData) {
        require(data.granteeDid.isNotBlank()) { "granteeDid is required" }
        require(data.ownerDid.isNotBlank()) { "ownerDid is required" }
        require(data.tokenId.isNotBlank()) { "tokenId is required" }
        require(data.standard.isNotBlank()) { "standard is required" }
        require(data.status.isNotBlank()) { "status is required" }
        require(data.chainId > 0) { "chainId is required" }

        if (isEthrOwnerDid(data.ownerDid)) {
            require(!data.contractAddress.isNullOrBlank()) { "contractAddress is required for EVM owner DID" }
        }
        if (isSwtcOwnerDid(data.ownerDid)) {
            require(!data.nftIssuer.isNullOrBlank()) { "nftIssuer is required for SWTC owner DID" }
            require(!data.tokenName.isNullOrBlank()) { "tokenName is required for SWTC owner DID" }
        }

        if (data.type == CredentialAuthorizationType.OTHERS) {
            require(!data.usageRights.isNullOrEmpty()) { "usageRights is required for others authorization" }
            require(data.restrictions != null) { "restrictions is required for others authorization" }
        }
    }

    fun buildNftSubject(data: UnifiedNftCredentialData): JSONObject {
        val base =
            JSONObject().apply {
                put("id", data.granteeDid)
                put("owner", data.ownerDid)
                put("chainId", data.chainId)
                put("tokenId", data.tokenId)
                put("status", data.status)
                put("standard", data.standard)
            }

        return if (isSwtcOwnerDid(data.ownerDid)) {
            base.apply {
                put("nftIssuer", data.nftIssuer.orEmpty())
                put("tokenName", data.tokenName.orEmpty())
                if (data.type == CredentialAuthorizationType.OTHERS) {
                    put("usageRights", usageRightsToJson(data.usageRights.orEmpty()))
                    put("restrictions", restrictionsToJson(data.restrictions!!))
                }
            }
        } else {
            val checksumContract =
                data.contractAddress
                    ?.let { ChecksumUtils.toChecksumAddress(it) }
                    .orEmpty()
            base.apply {
                put("contractAddress", checksumContract)
                if (data.type == CredentialAuthorizationType.OTHERS) {
                    put("usageRights", usageRightsToJson(data.usageRights.orEmpty()))
                    put("restrictions", restrictionsToJson(data.restrictions!!))
                }
            }
        }
    }

    fun vcTypesFor(data: UnifiedNftCredentialData): List<String> =
        if (data.type == CredentialAuthorizationType.SELF) {
            listOf("VerifiableCredential", VC_TYPE_OWNERSHIP)
        } else {
            listOf("VerifiableCredential", VC_TYPE_USAGE_AUTHORIZATION)
        }

    fun contextTypeFor(data: UnifiedNftCredentialData): String =
        if (data.type == CredentialAuthorizationType.SELF) {
            CONTEXT_TYPE_OWNERSHIP
        } else {
            CONTEXT_TYPE_USAGE_AUTHORIZATION
        }

    fun credentialIncludesType(
        credentialJson: String,
        type: String
    ): Boolean {
        return try {
            val types = JSONObject(credentialJson).optJSONArray("type") ?: return false
            (0 until types.length()).any { index ->
                types.optString(index).equals(type, ignoreCase = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun ownerDidFromCredentialId(credentialId: String): String {
        val sep =
            when {
                "#nft" in credentialId -> "#nft"
                "#file-access" in credentialId -> "#file-access"
                "#phone" in credentialId -> "#phone"
                else -> ""
            }
        return credentialId.substringBefore(sep, "")
    }

    data class EthrNftCredentialRef(
        val contractAddress: String,
        val tokenId: String
    )

    /**
     * Parses `{owner}#nft-{contract}-{tokenId}-{grantee}` for ERC-721 ownership / authorization VCs.
     */
    fun parseEthrNftRefFromCredentialId(credentialId: String): EthrNftCredentialRef? {
        val suffix = credentialId.substringAfter("#nft-", "")
        if (suffix.isBlank() || !suffix.startsWith("0x", ignoreCase = true)) {
            return null
        }
        val contractEnd = suffix.indexOf('-')
        if (contractEnd <= 2) {
            return null
        }
        val contract = suffix.substring(0, contractEnd).trim()
        if (contract.length != 42) {
            return null
        }
        val rest = suffix.substring(contractEnd + 1)
        val tokenIdEnd = rest.indexOf('-')
        if (tokenIdEnd <= 0) {
            return null
        }
        val tokenId = rest.substring(0, tokenIdEnd).trim()
        if (tokenId.isBlank()) {
            return null
        }
        return EthrNftCredentialRef(contractAddress = contract, tokenId = tokenId)
    }

    fun readCredentials(doc: String): JSONArray {
        return try {
            val root = JSONObject(doc)
            root.optJSONArray("credentials") ?: root.optJSONArray("credential") ?: JSONArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readCredentials failed", e)
            JSONArray()
        }
    }

    fun findCredentialIndex(
        credentials: JSONArray,
        credentialId: String
    ): Int {
        for (index in 0 until credentials.length()) {
            val item = credentials.optJSONObject(index) ?: continue
            if (item.optString("id").equals(credentialId, ignoreCase = true)) {
                return index
            }
        }
        return -1
    }

    fun findCredentialById(
        credentials: JSONArray,
        credentialId: String
    ): JSONObject? {
        val index = findCredentialIndex(credentials, credentialId)
        return if (index >= 0) credentials.optJSONObject(index) else null
    }

    fun clearPreferredAvatarIfMatches(
        services: JSONArray,
        credentialId: String
    ): JSONArray {
        val updated = JSONArray()
        for (index in 0 until services.length()) {
            val service = services.optJSONObject(index) ?: continue
            if (service.optString("type") != "Profile") {
                updated.put(service)
                continue
            }
            val endpoint = service.optJSONObject("serviceEndpoint") ?: JSONObject()
            if (endpoint.optString("preferredAvatar").equals(credentialId, ignoreCase = true)) {
                endpoint.put("preferredAvatar", "")
            }
            updated.put(
                JSONObject(service.toString()).apply {
                    put("serviceEndpoint", endpoint)
                }
            )
        }
        return updated
    }

    fun fromAvatarCredential(
        ownerDid: String,
        selectedAvatar: DidAvatarCredential
    ): UnifiedNftCredentialData =
        if (selectedAvatar.isSwtc) {
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = ownerDid,
                ownerDid = ownerDid,
                chainId = 315,
                tokenId = selectedAvatar.tokenId,
                standard = STANDARD_JINGTUM_NFT,
                nftIssuer = selectedAvatar.issuer,
                tokenName = selectedAvatar.tokenName
            )
        } else {
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = ownerDid,
                ownerDid = ownerDid,
                chainId = selectedAvatar.chainId ?: 1,
                tokenId = selectedAvatar.tokenId,
                standard = STANDARD_ERC721,
                contractAddress = selectedAvatar.contract
            )
        }

    private fun usageRightsToJson(values: List<UsageRights>): JSONArray =
        JSONArray().apply {
            values.forEach { put(it.value) }
        }

    private fun restrictionsToJson(restrictions: NftCredentialRestrictions): JSONObject =
        JSONObject().apply {
            put("commercial", restrictions.commercial)
            put("derivative", restrictions.derivative)
            put("sublicense", restrictions.sublicense)
            put("territories", JSONArray(restrictions.territories))
            put("platforms", JSONArray(restrictions.platforms))
        }
}
