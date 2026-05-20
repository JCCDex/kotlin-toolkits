package com.jccdex.toolkits.nft.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class NftModelsTest {
    @Test
    fun nft_equalsAndHashCode() {
        val left =
            Nft(
                contract = "0xabc",
                tokenId = "1",
                name = "name",
                uri = "uri",
                issuanceDate = "2025-01-01",
                image = "img",
                hasLocal = true,
                chainId = 1L
            )
        val right = left.copy()
        val other = left.copy(tokenId = "2")

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
        assertThat(left).isNotEqualTo(other)
    }

    @Test
    fun avatarCandidate_holdsOptionalFields() {
        val candidate =
            AvatarCandidate(
                image = null,
                name = "avatar",
                contract = "0xcontract",
                tokenId = "99",
                issuer = null,
                tokenName = "Avatar",
                chainId = 1L,
                isSwtc = false
            )

        assertThat(candidate.image).isNull()
        assertThat(candidate.isSwtc).isFalse()
        assertThat(candidate.copy(name = "new").name).isEqualTo("new")
    }
}
