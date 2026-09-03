package com.jccdex.toolkits.nft.remote

import android.app.Application
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwtcChainNftClientTest {
    @Test
    fun parseErcInfoMetadataUri_extractsTokenUriFromTokenInfos() {
        val response =
            JSONObject(
                """
                {
                  "result": {
                    "TokenInfo": {
                      "TokenInfos": [
                        {
                          "TokenInfo": {
                            "InfoType": "746f6b656e557269",
                            "InfoData": "697066733a2f2f62616679626569676d61676963326c6c6d6133777632326e33726a776c6f69333570766336356c75796573617978366562327233646674752f383838382e706e67"
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
            )

        val uri = SwtcChainNftClient.parseErcInfoMetadataUri(response)

        assertThat(uri).isEqualTo(
            "https://ipfs.jccdex.cn/ipfs/bafybeigmagic2llma3wv22n3rjwloi35pvc65luyesayx6eb2r3dftu/8888.png"
        )
    }

    @Test
    fun parseErcInfoMetadataUri_returnsNullWhenTokenInfosMissing() {
        val response = JSONObject("""{"result":{"TokenInfo":{}}}""")

        assertThat(SwtcChainNftClient.parseErcInfoMetadataUri(response)).isNull()
    }

    // ── M-10N: RPC nodes must be https ──

    @Test
    fun publicFactory_rejectsHttpRpcNode() {
        assertThrows(IllegalArgumentException::class.java) {
            SwtcChainNftClient.create(rpcNodes = listOf("http://localhost:8080"))
        }
    }

    @Test
    fun internalSeam_allowsHttpNodeForMockWebServer() {
        // M-10N: internal createForTest seam for http-only MockWebServer tests.
        // Construction must not throw (the public factory rejects http nodes).
        SwtcChainNftClient.createForTest(rpcNodes = listOf("http://localhost:8080"))
    }
}
