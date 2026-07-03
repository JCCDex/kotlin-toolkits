package com.jccdex.toolkits.dappconnect

/** Notified after DApp mutates a DID document (e.g. ipfs_personalSign during IPFS publish). */
fun interface DidDocumentMutationListener {
    fun onDidDocumentMutated()
}
