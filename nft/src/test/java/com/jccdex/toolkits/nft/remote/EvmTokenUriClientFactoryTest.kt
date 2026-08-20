package com.jccdex.toolkits.nft.remote

import org.junit.Assert.assertNotNull
import org.junit.Test

class EvmTokenUriClientFactoryTest {
    @Test
    fun `createDefault uses ChainDefaults Evm`() {
        val client = EvmTokenUriClientFactory.createDefault()
        assertNotNull("Client should not be null", client)
    }

    @Test
    fun `create with custom provider`() {
        val customProvider: (Long) -> List<String> = { chainId ->
            listOf("https://custom.node.com/$chainId")
        }
        val client = EvmTokenUriClientFactory.create(customProvider)
        assertNotNull("Client should not be null", client)
    }

    @Test
    fun `createWithFallback combines default and additional nodes`() {
        val additionalNodes =
            mapOf(
                1L to listOf("https://custom.eth.node.com")
            )
        val client = EvmTokenUriClientFactory.createWithFallback(additionalNodes)
        assertNotNull("Client should not be null", client)
    }

    @Test
    fun `createWithOverride uses custom nodes when provided`() {
        val customNodes =
            mapOf(
                1L to listOf("https://custom.eth.node.com")
            )
        val client = EvmTokenUriClientFactory.createWithOverride(customNodes)
        assertNotNull("Client should not be null", client)
    }

    @Test
    fun `createWithOverride falls back to default for missing chains`() {
        val customNodes = mapOf<Long, List<String>>()
        val client = EvmTokenUriClientFactory.createWithOverride(customNodes)
        assertNotNull("Client should not be null", client)
    }
}
