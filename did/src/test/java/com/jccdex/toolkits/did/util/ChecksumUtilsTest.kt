package com.jccdex.toolkits.did.util

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class ChecksumUtilsTest {
    @Test
    fun toChecksumAddress_appliesEip55MixedCase() {
        val raw = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

        assertThat(ChecksumUtils.toChecksumAddress(raw)).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
        assertThat(ChecksumUtils.toChecksumAddress(raw.removePrefix("0x")))
            .isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
    }

    @Test
    fun toChecksumAddress_rejectsInvalidLength() {
        assertThatThrownBy { ChecksumUtils.toChecksumAddress("0x1234") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid address length")
    }

    @Test
    fun toChecksumAddress_rejectsInvalidCharacters() {
        assertThatThrownBy {
            ChecksumUtils.toChecksumAddress("0xzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid characters")
    }
}
