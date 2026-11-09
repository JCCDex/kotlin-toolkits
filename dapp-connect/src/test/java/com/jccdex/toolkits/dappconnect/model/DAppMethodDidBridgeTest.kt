package com.jccdex.toolkits.dappconnect.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 6 个 DID bridge 能力的方法名映射(与 `did-bridge.js` 的 publishDid / didResolve / didStat /
 * generateVC / verifyCredential / generateDidDoc 一一对应)。
 */
class DAppMethodDidBridgeTest {
    @Test
    fun `did bridge methods map to their enum values`() {
        assertEquals(DAppMethod.DID_PUBLISH, DAppMethod.fromValue("did_publish"))
        assertEquals(DAppMethod.DID_RESOLVE, DAppMethod.fromValue("did_resolve"))
        assertEquals(DAppMethod.DID_STAT, DAppMethod.fromValue("did_stat"))
        assertEquals(DAppMethod.DID_GENERATEVC, DAppMethod.fromValue("did_generateVC"))
        assertEquals(DAppMethod.DID_VERIFYCREDENTIAL, DAppMethod.fromValue("did_verifyCredential"))
        assertEquals(DAppMethod.DID_GENERATEDIDDOC, DAppMethod.fromValue("did_generateDidDoc"))
    }

    @Test
    fun `unknown names still fall back to UNKNOWN`() {
        assertEquals(DAppMethod.UNKNOWN, DAppMethod.fromValue("did_publishNothing"))
        assertEquals(DAppMethod.UNKNOWN, DAppMethod.fromValue("publishDid"))
    }
}
