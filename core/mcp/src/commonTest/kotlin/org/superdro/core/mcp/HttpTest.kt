package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The HTTP/1.1 request reader, the response writer, and the SSE frames. */
class HttpTest {

    private fun link(text: String): FakeLink = FakeLink("192.168.1.30").also { link ->
        link.send(text.encodeToByteArray())
        link.disconnect()
    }

    @Test
    fun theReaderGivesTheRequestLineTheHeadersAndTheBody() = runTest {
        val reader = HttpReader(link("POST /mcp?x=1 HTTP/1.1\r\nHost: 192.168.1.20:5810\r\ncontent-type: application/json\r\nContent-Length: 5\r\n\r\nhello"))
        val head = reader.readHead()!!
        assertEquals("POST", head.method)
        assertEquals("/mcp?x=1", head.target)
        assertEquals("/mcp", head.path)
        assertEquals("HTTP/1.1", head.version)
        assertEquals("application/json", head.headers["Content-Type"])
        assertEquals(5L, head.contentLength)
        assertTrue(head.keepAlive)
        assertEquals("hello", reader.readBody(5).decodeToString())
        assertNull(reader.readHead())
    }

    @Test
    fun theReaderServesTwoRequestsOnOneConnectionAlsoWhenTheBytesComeInParts() = runTest {
        val link = FakeLink("192.168.1.30")
        val first = "GET / HTTP/1.1\r\nHost: a\r\n\r\nPOST /mcp HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\n"
        link.send(first.substring(0, 10).encodeToByteArray())
        link.send(first.substring(10).encodeToByteArray())
        link.send("ab".encodeToByteArray())
        link.disconnect()
        val reader = HttpReader(link)
        assertEquals("GET", reader.readHead()!!.method)
        val second = reader.readHead()!!
        assertEquals("POST", second.method)
        assertEquals("ab", reader.readBody(2).decodeToString())
        assertNull(reader.readHead())
    }

    @Test
    fun theReaderAcceptsBareLineFeedsAndHttp10ClosesByDefault() = runTest {
        val reader = HttpReader(link("GET / HTTP/1.0\nHost: a\n\n"))
        val head = reader.readHead()!!
        assertEquals("HTTP/1.0", head.version)
        assertFalse(head.keepAlive)
        val keep = HttpReader(link("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")).readHead()!!
        assertTrue(keep.keepAlive)
        val close = HttpReader(link("GET / HTTP/1.1\r\nConnection: close\r\n\r\n")).readHead()!!
        assertFalse(close.keepAlive)
    }

    @Test
    fun aChunkedBodyAndAPostWithNoLengthGive411() = runTest {
        val chunked = assertFailsWith<HttpRequestException> {
            HttpReader(link("POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")).readHead()
        }
        assertEquals(411, chunked.status)
        val noLength = assertFailsWith<HttpRequestException> { HttpReader(link("POST /mcp HTTP/1.1\r\nHost: a\r\n\r\n")).readHead() }
        assertEquals(411, noLength.status)
    }

    @Test
    fun aBadRequestLineABadHeaderAndABadLengthGive400() = runTest {
        listOf("HELLO\r\n\r\n", "GET /\r\n\r\n", "GET / HTTP/2\r\n\r\n", "GET / HTTP/1.1\r\nNoColon\r\n\r\n", "POST / HTTP/1.1\r\nContent-Length: x\r\n\r\n", "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n").forEach { text ->
            val exception = assertFailsWith<HttpRequestException>(text) { HttpReader(link(text)).readHead() }
            assertEquals(400, exception.status, text)
        }
    }

    @Test
    fun aStreamThatEndsInTheHeadOrTheBodyGives400() = runTest {
        val head = assertFailsWith<HttpRequestException> { HttpReader(link("GET / HTTP/1.1\r\nHost: a\r\n")).readHead() }
        assertEquals(400, head.status)
        val reader = HttpReader(link("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"))
        reader.readHead()
        val body = assertFailsWith<HttpRequestException> { reader.readBody(10) }
        assertEquals(400, body.status)
    }

    @Test
    fun aHeadAboveTheLimitGives431() = runTest {
        val big = "GET / HTTP/1.1\r\nX-A: " + "a".repeat(200) + "\r\n\r\n"
        val exception = assertFailsWith<HttpRequestException> { HttpReader(link(big), headerLimit = 100).readHead() }
        assertEquals(431, exception.status)
    }

    @Test
    fun theWriterGivesTheStatusLineTheHeadersAndTheBody() {
        val text = Http.encode(HttpResponse.text(404, "Not found", listOf("Allow" to "POST")), keepAlive = true).decodeToString()
        assertEquals(
            "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 10\r\nConnection: keep-alive\r\nAllow: POST\r\n\r\nNot found\n",
            text,
        )
        val empty = Http.encode(HttpResponse.empty(202), keepAlive = false).decodeToString()
        assertEquals("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", empty)
    }

    @Test
    fun theSseFramesHaveTheEventNameAndTheKeepAliveIsAComment() {
        assertTrue(Http.sseHead().decodeToString().startsWith("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"))
        assertTrue("Connection: close\r\n" in Http.sseHead().decodeToString())
        assertEquals("event: message\ndata: {\"a\":1}\n\n", Http.sseEvent("{\"a\":1}").decodeToString())
        assertTrue(Http.sseKeepAlive().decodeToString().startsWith(":"))
    }

    @Test
    fun theHostOfAnAuthorityHasNoPortNoBracketsAndNoZone() {
        assertEquals("192.168.1.20", Http.hostOf("192.168.1.20:5810"))
        assertEquals("192.168.1.20", Http.hostOf("192.168.1.20"))
        assertEquals("fe80::1", Http.hostOf("[fe80::1]:5810"))
        assertEquals("fe80::1", Http.hostOf("fe80::1%wlan0"))
        assertEquals("localhost", Http.hostOf("LocalHost:5810"))
    }

    @Test
    fun aHeaderValueInTheBase64SentinelFormIsDecoded() {
        assertEquals("get_weather", Http.decodeHeaderValue("get_weather"))
        assertEquals("Hello, 世界", Http.decodeHeaderValue("=?base64?SGVsbG8sIOS4lueVjA==?="))
        assertEquals(" padded ", Http.decodeHeaderValue("=?base64?IHBhZGRlZCA=?="))
        assertNull(Http.decodeHeaderValue("=?base64?***?="))
    }

    @Test
    fun theContentTypeOfARequestHasNoParameters() {
        val head = HttpHead("POST", "/mcp", "HTTP/1.1", HttpHeaders(listOf("Content-Type" to "Application/JSON; charset=utf-8")))
        assertEquals("application/json", HttpRequest(head, ByteArray(0)).contentType)
        assertNull(HttpRequest(HttpHead("GET", "/", "HTTP/1.1", HttpHeaders.EMPTY), ByteArray(0)).contentType)
    }
}
