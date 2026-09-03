package com.jccdex.toolkits.wallet.model

import com.jccdex.toolkits.core.model.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class WalletModelsTest {
    @Test
    fun path_toString_formatsBip44Path() {
        val path = Path(chain = 2147483708L, account = 1, change = 2, index = 3)

        assertThat(path.toString()).isEqualTo("m/44'/2147483708'/1'/2/3")
    }

    @Test
    fun keypair_and_mnemonic_equality() {
        val keypair = Keypair(privateKey = "priv", publicKey = "pub")
        val mnemonic = Mnemonic(value = "word word", language = "english")

        assertThat(keypair).isEqualTo(Keypair("priv", "pub"))
        assertThat(mnemonic).isEqualTo(Mnemonic("word word", "english"))
    }

    @Test
    fun subWallet_and_generateHdWalletResult_holdNestedData() {
        val sub =
            SubWallet(
                chain = 1L,
                address = "addr",
                path = Path(chain = 1L, index = 2),
                keypair = Keypair("priv", "pub")
            )
        val hd =
            GenerateHDWalletResult(
                mnemonic = "mnemonic",
                address = "root",
                language = "english",
                keypair = Keypair("priv", "pub"),
                accounts = listOf(sub)
            )

        assertThat(hd.accounts).containsExactly(sub)
        assertThat(hd.copy(language = "chinese").language).isEqualTo("chinese")
    }

    @Test
    fun traditionalDeriveResult_optionalFields() {
        val full =
            TraditionalDeriveResult(
                address = "addr",
                keypair = Keypair("priv", "pub"),
                mnemonic = Mnemonic("mnemonic", "english"),
                secret = "secret",
                path = Path(chain = 1L),
                sourcePrivateKey = "source"
            )
        val minimal =
            TraditionalDeriveResult(
                address = "addr",
                keypair = Keypair("priv", "pub")
            )

        assertThat(full.mnemonic?.value).isEqualTo("mnemonic")
        assertThat(full.path?.chain).isEqualTo(1L)
        assertThat(minimal.secret).isNull()
        assertThat(minimal.path).isNull()
        assertThat(full).isNotEqualTo(minimal)
    }

    @Test
    fun toString_masksSensitiveFields() {
        assertThat(Keypair("secret-key-value-123", "pub").toString())
            .doesNotContain("secret-key-value-123")
            .contains("pub")
        assertThat(Mnemonic("secret-mnemonic-words", "english").toString())
            .doesNotContain("secret-mnemonic-words")
            .contains("english")

        val subKey = "secret-sub-key-456"
        val hd =
            GenerateHDWalletResult(
                mnemonic = "secret-mnemonic-value",
                address = "root",
                language = "english",
                keypair = Keypair("secret-key-value-123", "pub"),
                accounts =
                    listOf(
                        SubWallet(
                            chain = 1L,
                            address = "sub-addr",
                            path = Path(chain = 1L, index = 2),
                            keypair = Keypair(subKey, "sub-pub")
                        )
                    )
            )
        assertThat(hd.toString())
            .doesNotContain("secret-mnemonic-value")
            .doesNotContain("secret-key-value-123")
            .doesNotContain(subKey)

        val trad =
            TraditionalDeriveResult(
                address = "addr",
                keypair = Keypair("secret-key-value-123", "pub"),
                secret = "secret-secret-value",
                sourcePrivateKey = "secret-source-value"
            )
        assertThat(trad.toString())
            .doesNotContain("secret-secret-value")
            .doesNotContain("secret-source-value")
            .doesNotContain("secret-key-value-123")
    }

    @Test
    fun toString_masking_doesNotAffectEqualityOrHashCode() {
        val a = Keypair("priv", "pub")
        val b = Keypair("priv", "pub")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
