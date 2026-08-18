package com.jccdex.toolkits.dappconnect.model

/**
 * Enumeration of supported DApp RPC methods.
 * Includes both Ethereum (EIP-1193) and SWTC chain methods.
 */
enum class DAppMethod {
    // SWTC methods
    SWTC_REQUESTACCOUNTS,
    SWTC_SENDTRANSACTION,
    SWTC_MULTISIGN,
    SWTC_SIGNMESSAGE,
    SWTC_GETPUBLICKEY,
    SWTC_BATCHTRANSACTIONS,
    SWTC_REQUESTNFTS,

    // ETH methods (EIP-1193)
    ETH_ACCOUNTS,
    ETH_REQUESTACCOUNTS,
    ETH_CHAINID,
    ETH_BLOCKNUMBER,
    ETH_PERSONAL_SIGN,
    ETH_PERSONAL_ECRECOVER,
    ETH_SIGNTYPEDDATA,
    ETH_SIGNTYPEDDATA_V3,
    ETH_SIGNTYPEDDATA_V4,
    ETH_SENDTRANSACTION,
    ETH_SIGNTRANSACTION,
    ETH_GET_ENCRYPTION_PUBLICKEY,
    ETH_DECRYPT,
    ETH_REQUESTNFTS,

    // Wallet methods
    WALLET_SWITCHETHEREUMCHAIN,

    // DID / IPFS methods
    DID_REQUESTACCOUNTNAME,
    DID_GETBASE58PUBLICKEY,
    DID_ISSUECREDENTIAL,
    IPFS_PERSONALSIGN,
    IPFS_GETPUBLICKEY,

    // Common
    WEB3_CLIENTVERSION,

    // Unknown method
    UNKNOWN;

    companion object {
        /**
         * Parse method name string to DAppMethod enum.
         */
        fun fromValue(value: String): DAppMethod =
            when (value) {
                // SWTC methods
                "swtc_requestAccounts" -> SWTC_REQUESTACCOUNTS
                "swtc_sendTransaction" -> SWTC_SENDTRANSACTION
                "swtc_multiSign" -> SWTC_MULTISIGN
                "swtc_signMessage" -> SWTC_SIGNMESSAGE
                "swtc_getPublicKey" -> SWTC_GETPUBLICKEY
                "swtc_batchTransactions" -> SWTC_BATCHTRANSACTIONS
                "swtc_requestNfts" -> SWTC_REQUESTNFTS

                // ETH methods
                "eth_requestAccounts" -> ETH_REQUESTACCOUNTS
                "eth_accounts" -> ETH_ACCOUNTS
                "eth_chainId" -> ETH_CHAINID
                "eth_blockNumber" -> ETH_BLOCKNUMBER
                "personal_sign" -> ETH_PERSONAL_SIGN
                "personal_ecRecover" -> ETH_PERSONAL_ECRECOVER
                "eth_signTypedData" -> ETH_SIGNTYPEDDATA
                "eth_signTypedData_v3" -> ETH_SIGNTYPEDDATA_V3
                "eth_signTypedData_v4" -> ETH_SIGNTYPEDDATA_V4
                "eth_sendTransaction" -> ETH_SENDTRANSACTION
                "eth_signTransaction" -> ETH_SIGNTRANSACTION
                "eth_decrypt" -> ETH_DECRYPT
                "eth_getEncryptionPublicKey" -> ETH_GET_ENCRYPTION_PUBLICKEY
                "eth_requestNfts" -> ETH_REQUESTNFTS

                // Wallet methods
                "wallet_switchEthereumChain" -> WALLET_SWITCHETHEREUMCHAIN

                // DID / IPFS methods
                "did_requestAccountName" -> DID_REQUESTACCOUNTNAME
                "did_getBase58PublicKey" -> DID_GETBASE58PUBLICKEY
                "did_issueCredential" -> DID_ISSUECREDENTIAL
                "ipfs_personalSign" -> IPFS_PERSONALSIGN
                "ipfs_getPublicKey" -> IPFS_GETPUBLICKEY

                // Common
                "web3_clientVersion" -> WEB3_CLIENTVERSION

                else -> UNKNOWN
            }
    }
}
