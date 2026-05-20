package com.jccdex.toolkits.did.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DidModelsTest {
    @Test
    fun didWriteResult_holdsSuccessPayload() {
        val result = DidWriteResult(success = true, didDocument = """{"did":"did:test:1"}""")

        assertThat(result.success).isTrue
        assertThat(result.didDocument).contains("did:test:1")
    }

    @Test
    fun didSyncResult_exposesLowercaseAddresses() {
        val result =
            DidSyncResult(
                entries =
                    listOf(
                        DidSyncEntry(
                            did = "did:ethr:0xAbC",
                            addressLower = "0xabc",
                            document = "{}",
                            nickname = "alice"
                        ),
                        DidSyncEntry(
                            did = "did:swtc:jswtc",
                            addressLower = "jswtc",
                            document = "{}",
                            nickname = ""
                        )
                    )
            )

        assertThat(result.addressesLower).containsExactlyInAnyOrder("0xabc", "jswtc")
    }
}
