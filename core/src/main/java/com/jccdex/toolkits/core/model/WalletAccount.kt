package com.jccdex.toolkits.core.model

import org.json.JSONArray
import java.util.UUID

/**
 * Wallet account metadata (no private keys; those live in [com.jccdex.toolkits.vault]).
 */
data class WalletAccount(
    val id: String = UUID.randomUUID().toString(),
    val address: String,
    val chain: ChainType = ChainType.ETH,
    val name: String = "",
    val isHD: Boolean = false,
    val parentId: String? = null,
    val path: Path? = null,
    val publicKey: String = ""
) {
    fun isRootHD(): Boolean = AccountClassification.isRootHD(isHD, parentId, path?.account, path?.change, path?.index)

    fun isSubHD(): Boolean = AccountClassification.isSubHD(isHD, parentId, path?.account, path?.change, path?.index)

    fun isTraditional(): Boolean = AccountClassification.isTraditional(isHD)

    /** Traditional or sub-HD account (not an HD root). */
    fun isNonRoot(): Boolean = AccountClassification.isNonRoot(isHD, parentId, path?.account, path?.change, path?.index)
}

fun List<ChainType>.toBip44JsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put(it.bip44Code) }
    return array
}
