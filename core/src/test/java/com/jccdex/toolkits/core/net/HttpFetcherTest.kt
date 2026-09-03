package com.jccdex.toolkits.core.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFetcherTest {
    @Test
    fun `httpsOnly rejects non-https urls before any connection`() {
        // Mirrors the removed app-update openHttps H-W3/L-4 rejection test.
        val fetcher = HttpFetcher()
        assertTrue(fetcher.get("http://example.com/checksums") is HttpResult.Failure)
        assertTrue(fetcher.get("ftp://example.com/checksums") is HttpResult.Failure)
        assertTrue(fetcher.get("") is HttpResult.Failure)
        assertTrue(fetcher.get("not a url") is HttpResult.Failure)
    }

    @Test
    fun `ssrfCheck blocks disallowed urls before connecting`() {
        val fetcher = HttpFetcher(ssrfCheck = { false })
        val result = fetcher.get("https://safe.example")
        assertTrue(result is HttpResult.Failure)
        assertEquals(HttpError.SsrfBlocked, (result as HttpResult.Failure).error)
    }

    @Test
    fun `postJson rejects non-https urls`() {
        val fetcher = HttpFetcher()
        assertTrue(fetcher.postJson("http://example.com", "{}") is HttpResult.Failure)
    }

    @Test
    fun `postJson sends POST body before reading response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"0x1","id":1}"""))
        server.start()
        try {
            val fetcher = HttpFetcher(httpsOnly = false)
            val body = """{"jsonrpc":"2.0","method":"eth_call","params":[],"id":1}"""
            val result = fetcher.postJson(server.url("/").toString(), body)
            assertTrue(result is HttpResult.Success)
            assertEquals("""{"jsonrpc":"2.0","result":"0x1","id":1}""", (result as HttpResult.Success).value)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("application/json; charset=UTF-8", recorded.getHeader("Content-Type"))
            assertEquals(body, recorded.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `map transforms success value`() {
        val input: HttpResult<String> = HttpResult.Success("12345")
        assertEquals(HttpResult.Success(5), input.map { it.length })
        val failure: HttpResult<String> = HttpResult.Failure(HttpError.InvalidUrl)
        assertEquals(HttpResult.Failure(HttpError.InvalidUrl), failure.map { it.length })
    }

    @Test
    fun `downloadToFile streams body with progress and respects size cap`() {
        val server = MockWebServer()
        val payload = "x".repeat(20_000)
        server.enqueue(MockResponse().setBody(payload))
        server.start()
        val target = java.io.File.createTempFile("http-dl-", ".bin")
        try {
            val progress = mutableListOf<Long>()
            val fetcher = HttpFetcher(httpsOnly = false, maxResponseBytes = 50_000)
            val result =
                fetcher.downloadToFile(
                    server.url("/apk").toString(),
                    target,
                    onProgress = { read, _ -> progress.add(read) }
                )
            assertTrue(result is HttpResult.Success)
            assertEquals(payload.length.toLong(), target.length())
            assertTrue(progress.isNotEmpty())
            assertEquals(payload.length.toLong(), progress.last())
        } finally {
            target.delete()
            server.shutdown()
        }
    }

    @Test
    fun `downloadToFile returns SizeExceeded without keeping partial file`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("y".repeat(8_000)))
        server.start()
        val target = java.io.File.createTempFile("http-dl-over-", ".bin")
        try {
            val fetcher = HttpFetcher(httpsOnly = false, maxResponseBytes = 100)
            val result = fetcher.downloadToFile(server.url("/apk").toString(), target)
            assertTrue(result is HttpResult.Failure)
            assertEquals(HttpError.SizeExceeded, (result as HttpResult.Failure).error)
            assertTrue(!target.exists())
        } finally {
            target.delete()
            server.shutdown()
        }
    }

    @Test
    fun `downloadToFile propagates cancelCheck CancellationException`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        val target = java.io.File.createTempFile("http-dl-cancel-", ".bin")
        try {
            val fetcher = HttpFetcher(httpsOnly = false)
            try {
                fetcher.downloadToFile(
                    server.url("/apk").toString(),
                    target,
                    cancelCheck = { throw java.util.concurrent.CancellationException("cancelled") }
                )
                throw AssertionError("expected CancellationException")
            } catch (_: java.util.concurrent.CancellationException) {
                // expected
            }
            assertTrue(!target.exists())
        } finally {
            target.delete()
            server.shutdown()
        }
    }
}
