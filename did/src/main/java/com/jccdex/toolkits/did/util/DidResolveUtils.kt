package com.jccdex.toolkits.did.util

/**
 * Recognizes resolve results that mean "no DID document on chain".
 *
 * [did-bridge.js] uses @jccdex/did [BaseDidResolver.noLink] and returns `null` for missing docs;
 * WebView bridge serializes that as the string `"null"`. Legacy IPFS tombstone `{}` is kept for
 * custom [IDidResolver] implementations and pre-0.2.11 behavior.
 */
object DidResolveUtils {
    fun isMissingDidDocument(doc: String): Boolean = doc == "{}" || doc == "null"
}
