package com.jccdex.toolkits.account.orchestrator

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path

data class HdChildAccountId(
    val chain: ChainType,
    val accountId: String
)

data class ImportHdWalletResult(
    val rootAccountId: String,
    val children: List<HdChildAccountId>
)

data class DerivedSubAccount(
    val address: String,
    val chain: ChainType,
    val path: Path,
    val rootAccountId: String,
    val publicKey: String
)
