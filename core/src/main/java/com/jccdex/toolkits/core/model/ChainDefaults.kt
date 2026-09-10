package com.jccdex.toolkits.core.model

/**
 * 链配置默认值（RPC节点、区块浏览器等）
 *
 * 职责：统一管理各链的默认配置，避免配置分散
 * 与ChainType分离，遵循单一职责原则
 *
 * 设计说明：
 * - ChainType: 只负责链类型标识（枚举）
 * - ChainDefaults: 只负责配置管理（RPC节点）
 * - 分离原因：避免ChainType枚举膨胀，配置可能需要动态更新
 *
 * @since 0.3.0
 */
object ChainDefaults {
    /**
     * EVM链配置（使用JSON-RPC协议）
     */
    object Evm {
        private val rpcNodes: Map<Long, List<String>> =
            mapOf(
                1L to
                    listOf(
                        "https://ethereum.publicnode.com",
                        "https://eth.llamarpc.com"
                    ),
                56L to
                    listOf(
                        "https://bsc-dataseed.binance.org",
                        "https://bsc-dataseed1.binance.org",
                        "https://bsc-dataseed2.binance.org"
                    ),
                137L to
                    listOf(
                        "https://polygon-bor-rpc.publicnode.com",
                        "https://1rpc.io/matic",
                        "https://polygon.publicnode.com"
                    ),
                8453L to
                    listOf(
                        "https://mainnet.base.org",
                        "https://base.publicnode.com",
                        "https://1rpc.io/base"
                    ),
                42161L to
                    listOf(
                        "https://arb1.arbitrum.io/rpc",
                        "https://arbitrum.publicnode.com",
                        "https://1rpc.io/arb"
                    ),
                99L to
                    listOf(
                        "https://moaca.jccdex.cn"
                    )
            )

        /**
         * 获取指定链的RPC节点列表
         *
         * @param chainId EVM链ID（如1=ETH, 56=BSC, 137=POLYGON）
         * @return RPC节点URL列表，未知链返回空列表
         */
        fun getRpcUrls(chainId: Long): List<String> = rpcNodes[chainId] ?: emptyList()

        /**
         * 获取指定链的第一个RPC节点（默认节点）
         *
         * @param chainId EVM链ID
         * @return RPC节点URL，未知链返回空字符串
         */
        fun getDefaultRpcUrl(chainId: Long): String = getRpcUrls(chainId).firstOrNull() ?: ""

        /**
         * 已废弃不再使用的官方默认 RPC 端点（服务商停用/不可靠）。
         * 宿主升级时应从本地节点库清理这些 URL，并补充 [getRpcUrls] 中的新默认端点。
         */
        private val deprecatedRpcNodes: Map<Long, Set<String>> =
            mapOf(
                137L to setOf("https://polygon-rpc.com")
            )

        /**
         * 获取指定链已废弃的官方默认 RPC 端点集合。
         *
         * @param chainId EVM链ID
         * @return 废弃 URL 集合，未知链返回空集合
         */
        fun getDeprecatedRpcUrls(chainId: Long): Set<String> = deprecatedRpcNodes[chainId] ?: emptySet()
    }

    /**
     * SWTC链配置（使用专用RPC协议）
     */
    object Swtc {
        private val rpcNodes: List<String> =
            listOf(
                "https://swtcrpc.jccdex.cn",
                "https://srje115qd43qw2.swtc.top"
            )

        /**
         * 获取SWTC链的RPC节点列表
         *
         * @return RPC节点URL列表
         */
        fun getRpcUrls(): List<String> = rpcNodes

        /**
         * 获取SWTC链的第一个RPC节点（默认节点）
         *
         * @return RPC节点URL
         */
        fun getDefaultRpcUrl(): String = rpcNodes.firstOrNull() ?: ""

        /**
         * 已废弃不再使用的官方默认 RPC 端点（服务商停用/不可靠）。
         * 宿主升级时应从本地节点库清理这些 URL，契约与 [Evm.getDeprecatedRpcUrls] 一致。
         *
         * 目前为空：保留该能力是为了让宿主的清理逻辑能对称覆盖 SWTC，
         * 不必在节点退役时再改一次宿主代码。
         */
        private val deprecatedRpcNodes: Set<String> = emptySet()

        /**
         * 获取 SWTC 链已废弃的官方默认 RPC 端点集合。
         *
         * @return 废弃 URL 集合（当前为空集合）
         */
        fun getDeprecatedRpcUrls(): Set<String> = deprecatedRpcNodes
    }
}
