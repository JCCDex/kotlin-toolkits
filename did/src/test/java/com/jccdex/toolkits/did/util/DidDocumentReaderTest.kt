package com.jccdex.toolkits.did.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DidDocumentReaderTest {
    private val profileDoc =
        """
        {
          "service": [
            {
              "type": "Profile",
              "serviceEndpoint": {
                "nickname": "alice",
                "preferredAvatar": "cred-avatar-1"
              }
            }
          ],
          "credentials": [{"id":"cred-1"}],
          "verificationMethod": [{"id":"vm-1"}]
        }
        """.trimIndent()

    @Test
    fun readProfileField_returnsProfileServiceValues() {
        assertEquals("alice", DidDocumentReader.readProfileField(profileDoc, "nickname"))
        assertEquals("cred-avatar-1", DidDocumentReader.readProfileField(profileDoc, "preferredAvatar"))
        assertNull(DidDocumentReader.readProfileField(profileDoc, "missing"))
    }

    @Test
    fun readProfileField_supportsServicesPluralKey() {
        val doc =
            """
            {
              "services": [
                {
                  "type": "Profile",
                  "serviceEndpoint": {"nickname": "bob"}
                }
              ]
            }
            """.trimIndent()
        assertEquals("bob", DidDocumentReader.readProfileField(doc, "nickname"))
    }

    @Test
    fun readJsonArray_readsPrimaryAndPluralKeys() {
        assertEquals(1, DidDocumentReader.readJsonArray(profileDoc, "credentials").length())
        assertEquals(1, DidDocumentReader.readJsonArray(profileDoc, "verificationMethod").length())
        assertEquals(0, DidDocumentReader.readJsonArray(profileDoc, "missing").length())
    }

    @Test
    fun readServices_prefersServiceOverServices() {
        val root =
            org.json.JSONObject(
                """
                {
                  "service": [{"type":"Profile","serviceEndpoint":{"nickname":"first"}}],
                  "services": [{"type":"Profile","serviceEndpoint":{"nickname":"second"}}]
                }
                """.trimIndent()
            )
        assertEquals(1, DidDocumentReader.readServices(root).length())
        assertEquals("first", DidDocumentReader.readProfileField(root.toString(), "nickname"))
    }
}
