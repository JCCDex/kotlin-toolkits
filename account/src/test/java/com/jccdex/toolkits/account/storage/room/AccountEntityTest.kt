package com.jccdex.toolkits.account.storage.room

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.core.model.WalletAccount
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountEntityTest {
    @Test
    fun toWalletAccount_unknownChain_throwsUnknownChainCodeException() {
        val entity =
            baseEntity().copy(
                chain = -1L,
                pathIndex = null,
                pathAccount = null,
                pathChange = null
            )

        // M-15A: unknown chain code must fail observably instead of silently routing to ETH.
        val ex = assertThrows(UnknownChainCodeException::class.java) { entity.toWalletAccount() }
        assertThat(ex.chainCode).isEqualTo(-1L)
    }

    @Test
    fun toWalletAccount_pathDefaultsAccountAndChangeToZero() {
        val entity =
            baseEntity().copy(
                chain = ChainType.ETH.bip44Code,
                pathIndex = 3,
                pathAccount = null,
                pathChange = null
            )

        val account = entity.toWalletAccount()

        assertThat(account.path).isNotNull
        assertThat(account.path?.account).isEqualTo(0)
        assertThat(account.path?.change).isEqualTo(0)
        assertThat(account.path?.index).isEqualTo(3)
        assertThat(account.path?.chain).isEqualTo(ChainType.ETH.bip44Code)
    }

    @Test
    fun toWalletAccount_nullPathIndex_yieldsNullPath() {
        val entity =
            baseEntity().copy(
                pathIndex = null,
                pathAccount = null,
                pathChange = null
            )

        assertThat(entity.toWalletAccount().path).isNull()
    }

    @Test
    fun fromWalletAccount_roundTripsMappingFields() {
        val original =
            AccountEntity
                .fromWalletAccount(
                    WalletAccount(
                        id = "round-trip",
                        address = "0xabc",
                        chain = ChainType.BSC,
                        name = "name",
                        isHD = true,
                        parentId = "parent",
                        path =
                            Path(
                                chain = ChainType.BSC.bip44Code,
                                account = 1,
                                change = 2,
                                index = 3
                            ),
                        publicKey = "pub"
                    )
                ).toWalletAccount()

        assertThat(original.id).isEqualTo("round-trip")
        assertThat(original.address).isEqualTo("0xabc")
        assertThat(original.chain).isEqualTo(ChainType.BSC)
        assertThat(original.parentId).isEqualTo("parent")
        assertThat(original.path?.account).isEqualTo(1)
        assertThat(original.path?.change).isEqualTo(2)
        assertThat(original.path?.index).isEqualTo(3)
    }

    private fun baseEntity(): AccountEntity =
        AccountEntity(
            id = "entity-id",
            address = "0xaddr",
            chain = ChainType.ETH.bip44Code,
            name = "name",
            isHD = false,
            parentId = null,
            pathAccount = null,
            pathChange = null,
            pathIndex = null,
            publicKey = "pub"
        )
}
