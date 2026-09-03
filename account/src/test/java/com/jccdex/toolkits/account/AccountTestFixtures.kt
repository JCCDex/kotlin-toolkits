package com.jccdex.toolkits.account

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.core.model.WalletAccount

internal object AccountTestFixtures {
    fun hdRoot(
        id: String = "461a1882-b181-4e6a-bf8e-5d0f66bdd470",
        address: String = "jJZEyWozV3g767aMAMNGoSEdXmKEZzR6eZ",
        chain: ChainType = ChainType.SWTC,
        name: String = "HD Root",
        publicKey: String = "02eb3f226cc818fa2afadb906be5c5f0a92182d9a987b77e3667dd3b13783e8d09"
    ): WalletAccount =
        WalletAccount(
            id = id,
            address = address,
            chain = chain,
            name = name,
            isHD = true,
            parentId = null,
            path = Path.root(chain),
            publicKey = publicKey
        )

    fun hdSub(
        id: String = "bfe81bc7-ddb4-4ba2-8032-595653c84580",
        address: String = "0x12898725cf301693733d951bb992c30310dbfb3b",
        chain: ChainType = ChainType.ETH,
        name: String = "eth-sub1",
        parentId: String = "461a1882-b181-4e6a-bf8e-5d0f66bdd470",
        index: Int = 0,
        publicKey: String = "02eb3f226cc818fa2afadb906be5c5f0a92182d9a987b77e3667dd3b13783e8d09"
    ): WalletAccount =
        WalletAccount(
            id = id,
            address = address,
            chain = chain,
            name = name,
            isHD = true,
            parentId = parentId,
            path = Path(chain = chain.bip44Code, index = index),
            publicKey = publicKey
        )

    fun hdDerivedWithoutParent(
        id: String = "derived-no-parent",
        address: String = "0xderivednoparent",
        chain: ChainType = ChainType.ETH,
        index: Int = 1,
        publicKey: String = "02eb3f226cc818fa2afadb906be5c5f0a92182d9a987b77e3667dd3b13783e8d09"
    ): WalletAccount =
        WalletAccount(
            id = id,
            address = address,
            chain = chain,
            name = "derived-sub",
            isHD = true,
            parentId = null,
            path = Path(chain = chain.bip44Code, index = index),
            publicKey = publicKey
        )

    fun traditional(
        id: String = "3135bfda-a99e-491f-8b72-5f0e3dc71a9d",
        address: String = "0x6a4f486f8f2e010c577afe8913886d977ba4b683",
        chain: ChainType = ChainType.ETH,
        name: String = "t-eth1",
        publicKey: String = "03dd36189261c6f1efc79ca430781a1114dde77ea05b8e6f7315dc033d396eefad"
    ): WalletAccount =
        WalletAccount(
            id = id,
            address = address,
            chain = chain,
            name = name,
            isHD = false,
            parentId = null,
            path = null,
            publicKey = publicKey
        )
}
