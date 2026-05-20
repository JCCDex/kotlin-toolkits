package com.jccdex.toolkits.account.storage.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.core.model.WalletAccount

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    val id: String,
    val address: String,
    val chain: Long,
    val name: String,
    val isHD: Boolean,
    val parentId: String?,
    val pathAccount: Int?,
    val pathChange: Int?,
    val pathIndex: Int?,
    val publicKey: String
) {
    fun toWalletAccount(): WalletAccount {
        val chainType = ChainType.fromBip44Code(chain) ?: ChainType.ETH
        val path =
            if (pathIndex != null) {
                Path(
                    chain = chain,
                    account = pathAccount ?: 0,
                    change = pathChange ?: 0,
                    index = pathIndex
                )
            } else {
                null
            }

        return WalletAccount(
            id = id,
            address = address,
            chain = chainType,
            name = name,
            isHD = isHD,
            parentId = parentId,
            path = path,
            publicKey = publicKey
        )
    }

    companion object {
        fun fromWalletAccount(account: WalletAccount): AccountEntity =
            AccountEntity(
                id = account.id,
                address = account.address,
                chain = account.chain.bip44Code,
                name = account.name,
                isHD = account.isHD,
                parentId = account.parentId,
                pathAccount = account.path?.account,
                pathChange = account.path?.change,
                pathIndex = account.path?.index,
                publicKey = account.publicKey
            )
    }
}
