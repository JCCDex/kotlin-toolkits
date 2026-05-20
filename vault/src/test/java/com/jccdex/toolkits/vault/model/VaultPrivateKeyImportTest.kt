package com.jccdex.toolkits.vault.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class VaultPrivateKeyImportTest {
    @Test
    fun equals_returnsTrueForSameAddressAndKeyBytes() {
        val left = VaultPrivateKeyImport("0xabc", byteArrayOf(1, 2, 3))
        val right = VaultPrivateKeyImport("0xabc", byteArrayOf(1, 2, 3))

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
    }

    @Test
    fun equals_returnsFalseWhenAddressDiffers() {
        val first = VaultPrivateKeyImport("0xabc", byteArrayOf(1, 2, 3))
        val second = VaultPrivateKeyImport("0xdef", byteArrayOf(1, 2, 3))

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun equals_returnsFalseWhenPrivateKeyDiffers() {
        val first = VaultPrivateKeyImport("0xabc", byteArrayOf(1, 2, 3))
        val second = VaultPrivateKeyImport("0xabc", byteArrayOf(4, 5, 6))

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun equals_returnsFalseForDifferentTypeOrReference() {
        val value = VaultPrivateKeyImport("0xabc", byteArrayOf(1))

        assertThat(value.equals(value)).isTrue()
        assertThat(value.equals("other")).isFalse()
        assertThat(value.equals(null)).isFalse()
    }

    @Test
    fun hashCode_changesWhenKeyContentChanges() {
        val first = VaultPrivateKeyImport("0xabc", byteArrayOf(1))
        val second = VaultPrivateKeyImport("0xabc", byteArrayOf(2))

        assertThat(first.hashCode()).isNotEqualTo(second.hashCode())
    }
}
