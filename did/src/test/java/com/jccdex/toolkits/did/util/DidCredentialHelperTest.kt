package com.jccdex.toolkits.did.util

import com.jccdex.toolkits.did.model.CredentialAuthorizationType
import com.jccdex.toolkits.did.model.NftCredentialRestrictions
import com.jccdex.toolkits.did.model.UnifiedNftCredentialData
import com.jccdex.toolkits.did.model.UsageRights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DidCredentialHelperTest {
    private val ownerDid = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
    private val granteeDid = "did:swtc:jGrantee"

    @Test
    fun `generateVcId matches did_DApp evm format`() {
        val contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        val checksumContract = ChecksumUtils.toChecksumAddress(contract)
        val id =
            DidCredentialHelper.generateVcId(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.SELF,
                    granteeDid = ownerDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "7",
                    standard = DidCredentialHelper.STANDARD_ERC721,
                    contractAddress = contract
                )
            )

        assertEquals("$ownerDid#nft-$checksumContract-7-$ownerDid", id)
    }

    @Test
    fun `generateVcId matches did_DApp swtc format`() {
        val swtcOwner = "did:swtc:jOwner"
        val id =
            DidCredentialHelper.generateVcId(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.OTHERS,
                    granteeDid = granteeDid,
                    ownerDid = swtcOwner,
                    chainId = 315,
                    tokenId = "9",
                    standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                    nftIssuer = "jIssuer",
                    tokenName = "Golden Sands",
                    usageRights = listOf(UsageRights.AVATAR),
                    restrictions = NftCredentialRestrictions()
                )
            )

        assertEquals("$swtcOwner#nft-GoldenSands-jIssuer-9-$granteeDid", id)
    }

    @Test
    fun `buildNftSubject includes usage fields for others authorization`() {
        val subject =
            DidCredentialHelper.buildNftSubject(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.OTHERS,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721,
                    contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    usageRights = listOf(UsageRights.AVATAR, UsageRights.NON_COMMERCIAL_DISPLAY),
                    restrictions =
                        NftCredentialRestrictions(
                            commercial = false,
                            derivative = false,
                            sublicense = false,
                            territories = listOf("CN"),
                            platforms = listOf("mobile")
                        )
                )
            )

        assertEquals(granteeDid, subject.getString("id"))
        assertEquals(2, subject.getJSONArray("usageRights").length())
        assertFalse(subject.getJSONObject("restrictions").getBoolean("commercial"))
    }

    @Test
    fun `clearPreferredAvatarIfMatches clears profile avatar id`() {
        val services =
            org.json.JSONArray(
                """
                [{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}]
                """.trimIndent()
            )
        val updated = DidCredentialHelper.clearPreferredAvatarIfMatches(services, "cred-1")
        assertEquals("", updated.getJSONObject(0).getJSONObject("serviceEndpoint").getString("preferredAvatar"))
    }
}
