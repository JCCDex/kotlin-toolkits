package com.jccdex.toolkits.did.util

import com.jccdex.toolkits.did.model.CredentialAuthorizationType
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.NftCredentialRestrictions
import com.jccdex.toolkits.did.model.UnifiedNftCredentialData
import com.jccdex.toolkits.did.model.UsageRights
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            JSONArray(
                """
                [{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}]
                """.trimIndent()
            )
        val updated = DidCredentialHelper.clearPreferredAvatarIfMatches(services, "cred-1")
        assertEquals("", updated.getJSONObject(0).getJSONObject("serviceEndpoint").getString("preferredAvatar"))
    }

    @Test
    fun `clearPreferredAvatarIfMatches does not clear non matching id`() {
        val services =
            JSONArray(
                """
                [{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"cred-1"}}]
                """.trimIndent()
            )
        val updated = DidCredentialHelper.clearPreferredAvatarIfMatches(services, "cred-2")
        assertEquals("cred-1", updated.getJSONObject(0).getJSONObject("serviceEndpoint").getString("preferredAvatar"))
    }

    @Test
    fun `clearPreferredAvatarIfMatches skips non Profile services`() {
        val services =
            JSONArray(
                """
                [{"type":"Other","serviceEndpoint":{"preferredAvatar":"cred-1"}}]
                """.trimIndent()
            )
        val updated = DidCredentialHelper.clearPreferredAvatarIfMatches(services, "cred-1")
        assertEquals("cred-1", updated.getJSONObject(0).getJSONObject("serviceEndpoint").getString("preferredAvatar"))
    }

    @Test
    fun `clearPreferredAvatarIfMatches matches case insensitive`() {
        val services =
            JSONArray(
                """
                [{"type":"Profile","serviceEndpoint":{"nickname":"alice","preferredAvatar":"Cred-1"}}]
                """.trimIndent()
            )
        val updated = DidCredentialHelper.clearPreferredAvatarIfMatches(services, "CRED-1")
        assertEquals("", updated.getJSONObject(0).getJSONObject("serviceEndpoint").getString("preferredAvatar"))
    }

    // ---------- isSwtcOwnerDid / isEthrOwnerDid ----------

    @Test
    fun `isSwtcOwnerDid true for swtc prefix`() {
        assertTrue(DidCredentialHelper.isSwtcOwnerDid("did:swtc:jOwner"))
    }

    @Test
    fun `isSwtcOwnerDid false for non swtc prefix`() {
        assertFalse(DidCredentialHelper.isSwtcOwnerDid("did:ethr:0x1234"))
    }

    @Test
    fun `isEthrOwnerDid true for ethr prefix`() {
        assertTrue(DidCredentialHelper.isEthrOwnerDid("did:ethr:0x1234"))
    }

    @Test
    fun `isEthrOwnerDid false for non ethr prefix`() {
        assertFalse(DidCredentialHelper.isEthrOwnerDid("did:swtc:jOwner"))
    }

    // ---------- validateCredentialData ----------

    @Test
    fun `validateCredentialData passes for valid EVM self data`() {
        val data =
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = ownerDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721,
                contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
            )
        DidCredentialHelper.validateCredentialData(data) // should not throw
    }

    @Test
    fun `validateCredentialData passes for valid SWTC self data`() {
        val data =
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = "did:swtc:jGrantee",
                ownerDid = "did:swtc:jOwner",
                chainId = 315,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                nftIssuer = "jIssuer",
                tokenName = "MyNFT"
            )
        DidCredentialHelper.validateCredentialData(data) // should not throw
    }

    @Test
    fun `validateCredentialData passes for valid EVM others data`() {
        val data =
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.OTHERS,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721,
                contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                usageRights = listOf(UsageRights.AVATAR),
                restrictions = NftCredentialRestrictions()
            )
        DidCredentialHelper.validateCredentialData(data) // should not throw
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when granteeDid is blank`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = "",
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when ownerDid is blank`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = "",
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when tokenId is blank`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "",
                standard = DidCredentialHelper.STANDARD_ERC721
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when standard is blank`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = ""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when status is blank`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721,
                status = ""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when chainId is zero`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 0,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when EVM owner has no contract address`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when SWTC owner has no nftIssuer`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = "did:swtc:jOwner",
                chainId = 315,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                tokenName = "MyNFT"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when SWTC owner has no tokenName`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.SELF,
                granteeDid = granteeDid,
                ownerDid = "did:swtc:jOwner",
                chainId = 315,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                nftIssuer = "jIssuer"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when OTHERS has no usageRights`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.OTHERS,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721,
                contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                restrictions = NftCredentialRestrictions()
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validateCredentialData throws when OTHERS has no restrictions`() {
        DidCredentialHelper.validateCredentialData(
            UnifiedNftCredentialData(
                type = CredentialAuthorizationType.OTHERS,
                granteeDid = granteeDid,
                ownerDid = ownerDid,
                chainId = 1,
                tokenId = "1",
                standard = DidCredentialHelper.STANDARD_ERC721,
                contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                usageRights = listOf(UsageRights.AVATAR)
            )
        )
    }

    // ---------- vcTypesFor / contextTypeFor ----------

    @Test
    fun `vcTypesFor returns ownership types for SELF`() {
        val types =
            DidCredentialHelper.vcTypesFor(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.SELF,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721
                )
            )
        assertEquals(listOf("VerifiableCredential", "NFTOwnership"), types)
    }

    @Test
    fun `vcTypesFor returns usage authorization types for OTHERS`() {
        val types =
            DidCredentialHelper.vcTypesFor(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.OTHERS,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721
                )
            )
        assertEquals(listOf("VerifiableCredential", "NFTUsageAuthorization"), types)
    }

    @Test
    fun `contextTypeFor returns ownership for SELF`() {
        val ctx =
            DidCredentialHelper.contextTypeFor(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.SELF,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721
                )
            )
        assertEquals("ownership", ctx)
    }

    @Test
    fun `contextTypeFor returns usageAuthorization for OTHERS`() {
        val ctx =
            DidCredentialHelper.contextTypeFor(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.OTHERS,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721
                )
            )
        assertEquals("usageAuthorization", ctx)
    }

    // ---------- credentialIncludesType ----------

    @Test
    fun `credentialIncludesType finds matching type`() {
        val json = """{"type":["VerifiableCredential","NFTOwnership"]}"""
        assertTrue(DidCredentialHelper.credentialIncludesType(json, "NFTOwnership"))
    }

    @Test
    fun `credentialIncludesType false for missing type`() {
        val json = """{"type":["VerifiableCredential","NFTOwnership"]}"""
        assertFalse(DidCredentialHelper.credentialIncludesType(json, "OtherType"))
    }

    @Test
    fun `credentialIncludesType matches case insensitive`() {
        val json = """{"type":["VerifiableCredential","NFTOwnership"]}"""
        assertTrue(DidCredentialHelper.credentialIncludesType(json, "nftownership"))
    }

    @Test
    fun `credentialIncludesType false for invalid json`() {
        assertFalse(DidCredentialHelper.credentialIncludesType("not json", "NFTOwnership"))
    }

    @Test
    fun `credentialIncludesType false when type field missing`() {
        val json = """{"other":[]}"""
        assertFalse(DidCredentialHelper.credentialIncludesType(json, "NFTOwnership"))
    }

    // ---------- ownerDidFromCredentialId ----------

    @Test
    fun `ownerDidFromCredentialId extracts before nft prefix`() {
        val result = DidCredentialHelper.ownerDidFromCredentialId("did:ethr:0x1234#nft-contract-1-grantee")
        assertEquals("did:ethr:0x1234", result)
    }

    @Test
    fun `ownerDidFromCredentialId extracts before file access prefix`() {
        val result = DidCredentialHelper.ownerDidFromCredentialId("did:swtc:jOwner#file-access-123")
        assertEquals("did:swtc:jOwner", result)
    }

    @Test
    fun `ownerDidFromCredentialId extracts before phone prefix`() {
        val result = DidCredentialHelper.ownerDidFromCredentialId("did:swtc:jOwner#phone-13800138000")
        assertEquals("did:swtc:jOwner", result)
    }

    @Test
    fun `ownerDidFromCredentialId returns empty when no known prefix`() {
        val result = DidCredentialHelper.ownerDidFromCredentialId("did:ethr:0x1234-something-else")
        assertEquals("", result)
    }

    // ---------- readCredentials ----------

    @Test
    fun `readCredentials reads credentials array`() {
        val doc = """{"credentials":[{"id":"cred-1"},{"id":"cred-2"}]}"""
        val result = DidCredentialHelper.readCredentials(doc)
        assertEquals(2, result.length())
    }

    @Test
    fun `readCredentials falls back to credential array`() {
        val doc = """{"credential":[{"id":"cred-1"}]}"""
        val result = DidCredentialHelper.readCredentials(doc)
        assertEquals(1, result.length())
    }

    @Test
    fun `readCredentials prefers credentials over credential`() {
        val doc = """{"credentials":[{"id":"a"}],"credential":[{"id":"b"}]}"""
        val result = DidCredentialHelper.readCredentials(doc)
        assertEquals(1, result.length())
        assertEquals("a", result.getJSONObject(0).getString("id"))
    }

    @Test
    fun `readCredentials returns empty array when neither field exists`() {
        val doc = """{"other":[]}"""
        val result = DidCredentialHelper.readCredentials(doc)
        assertEquals(0, result.length())
    }

    @Test
    fun `readCredentials returns empty array for invalid json`() {
        val result = DidCredentialHelper.readCredentials("not json")
        assertEquals(0, result.length())
    }

    // ---------- findCredentialIndex ----------

    @Test
    fun `findCredentialIndex returns index when found`() {
        val arr =
            JSONArray(
                """[{"id":"cred-a"},{"id":"cred-b"},{"id":"cred-c"}]"""
            )
        assertEquals(1, DidCredentialHelper.findCredentialIndex(arr, "cred-b"))
    }

    @Test
    fun `findCredentialIndex returns minus one when not found`() {
        val arr =
            JSONArray(
                """[{"id":"cred-a"},{"id":"cred-b"}]"""
            )
        assertEquals(-1, DidCredentialHelper.findCredentialIndex(arr, "cred-x"))
    }

    @Test
    fun `findCredentialIndex matches case insensitive`() {
        val arr =
            JSONArray(
                """[{"id":"Cred-A"},{"id":"cred-b"}]"""
            )
        assertEquals(0, DidCredentialHelper.findCredentialIndex(arr, "CRED-a"))
    }

    @Test
    fun `findCredentialIndex returns minus one for empty array`() {
        assertEquals(-1, DidCredentialHelper.findCredentialIndex(JSONArray(), "cred-1"))
    }

    // ---------- fromAvatarCredential ----------

    @Test
    fun `fromAvatarCredential builds SWTC data`() {
        val avatar =
            DidAvatarCredential(
                credentialId = "did:swtc:jOwner#nft-MyNFT-jIssuer-1-did:swtc:jOwner",
                image = "https://example.com/nft.png",
                name = "MyNFT",
                contract = null,
                tokenId = "1",
                issuer = "jIssuer",
                tokenName = "MyNFT",
                chainId = null,
                isSwtc = true,
                ownerDid = "did:swtc:jOwner"
            )
        val result = DidCredentialHelper.fromAvatarCredential("did:swtc:jOwner", avatar)
        assertEquals(CredentialAuthorizationType.SELF, result.type)
        assertEquals("did:swtc:jOwner", result.granteeDid)
        assertEquals("did:swtc:jOwner", result.ownerDid)
        assertEquals(315, result.chainId)
        assertEquals("1", result.tokenId)
        assertEquals(DidCredentialHelper.STANDARD_JINGTUM_NFT, result.standard)
        assertEquals("jIssuer", result.nftIssuer)
        assertEquals("MyNFT", result.tokenName)
    }

    @Test
    fun `fromAvatarCredential builds ETH data`() {
        val avatar =
            DidAvatarCredential(
                credentialId = "did:ethr:0x1234#nft-0xAbCd-1-did:ethr:0x1234",
                image = "https://example.com/nft.png",
                name = "MyNFT",
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "7",
                issuer = null,
                tokenName = null,
                chainId = 1,
                isSwtc = false,
                ownerDid = "did:ethr:0x1234"
            )
        val result = DidCredentialHelper.fromAvatarCredential("did:ethr:0x1234", avatar)
        assertEquals(CredentialAuthorizationType.SELF, result.type)
        assertEquals("did:ethr:0x1234", result.granteeDid)
        assertEquals("did:ethr:0x1234", result.ownerDid)
        assertEquals(1, result.chainId)
        assertEquals("7", result.tokenId)
        assertEquals(DidCredentialHelper.STANDARD_ERC721, result.standard)
        assertEquals("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", result.contractAddress)
    }

    @Test
    fun `fromAvatarCredential ETH data defaults chainId to 1 when null`() {
        val avatar =
            DidAvatarCredential(
                credentialId = "did:ethr:0x1234#nft-0xAbCd-1-did:ethr:0x1234",
                image = null,
                name = "MyNFT",
                contract = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                tokenId = "1",
                issuer = null,
                tokenName = null,
                chainId = null,
                isSwtc = false,
                ownerDid = "did:ethr:0x1234"
            )
        val result = DidCredentialHelper.fromAvatarCredential("did:ethr:0x1234", avatar)
        assertEquals(1, result.chainId)
    }

    // ---------- buildNftSubject uncovered branches ----------

    @Test
    fun `buildNftSubject for SELF type excludes usage fields`() {
        val subject =
            DidCredentialHelper.buildNftSubject(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.SELF,
                    granteeDid = granteeDid,
                    ownerDid = ownerDid,
                    chainId = 1,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_ERC721,
                    contractAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                )
            )
        assertEquals(granteeDid, subject.getString("id"))
        assertFalse(subject.has("usageRights"))
        assertFalse(subject.has("restrictions"))
    }

    @Test
    fun `buildNftSubject for SWTC owner includes tokenName and nftIssuer`() {
        val swtcOwner = "did:swtc:jOwner"
        val subject =
            DidCredentialHelper.buildNftSubject(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.SELF,
                    granteeDid = granteeDid,
                    ownerDid = swtcOwner,
                    chainId = 315,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                    nftIssuer = "jIssuer",
                    tokenName = "Golden Sands"
                )
            )
        assertEquals(granteeDid, subject.getString("id"))
        assertEquals(swtcOwner, subject.getString("owner"))
        assertEquals("jIssuer", subject.getString("nftIssuer"))
        assertEquals("Golden Sands", subject.getString("tokenName"))
        assertFalse(subject.has("contractAddress"))
    }

    @Test
    fun `buildNftSubject for SWTC owner OTHERS includes usage fields`() {
        val swtcOwner = "did:swtc:jOwner"
        val subject =
            DidCredentialHelper.buildNftSubject(
                UnifiedNftCredentialData(
                    type = CredentialAuthorizationType.OTHERS,
                    granteeDid = granteeDid,
                    ownerDid = swtcOwner,
                    chainId = 315,
                    tokenId = "1",
                    standard = DidCredentialHelper.STANDARD_JINGTUM_NFT,
                    nftIssuer = "jIssuer",
                    tokenName = "Golden Sands",
                    usageRights = listOf(UsageRights.AVATAR),
                    restrictions = NftCredentialRestrictions(commercial = true)
                )
            )
        assertEquals(granteeDid, subject.getString("id"))
        assertEquals("jIssuer", subject.getString("nftIssuer"))
        assertEquals(1, subject.getJSONArray("usageRights").length())
        assertTrue(subject.getJSONObject("restrictions").getBoolean("commercial"))
    }
}
