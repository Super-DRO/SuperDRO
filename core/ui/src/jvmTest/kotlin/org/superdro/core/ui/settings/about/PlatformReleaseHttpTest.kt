package org.superdro.core.ui.settings.about

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The JVM HTTP transport for the GitHub Releases API check. */
class PlatformReleaseHttpTest {

    @Test
    fun a200ResponseReturnsTheBodyAndSendsTheGitHubHeaders() = runTest {
        val accept = AtomicReference<String?>()
        val apiVersion = AtomicReference<String?>()
        val userAgent = AtomicReference<String?>()
        val method = AtomicReference<String?>()
        val expectedBody = """{"tag_name":"v26.42.2","html_url":"https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2"}"""

        withServer(
            handler = { exchange ->
                method.set(exchange.requestMethod)
                accept.set(exchange.requestHeaders.getFirst("Accept"))
                apiVersion.set(exchange.requestHeaders.getFirst("X-GitHub-Api-Version"))
                userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
                val bytes = expectedBody.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { out -> out.write(bytes) }
            },
        ) { url ->
            val body = fetchGitHubReleaseJson(url, timeoutMs = 2_000)
            assertEquals(expectedBody, body)
            assertEquals("GET", method.get())
            assertEquals("application/vnd.github+json", accept.get())
            assertEquals("2022-11-28", apiVersion.get())
            assertEquals("SuperDRO", userAgent.get())
        }
    }

    @Test
    fun a404Or500ResponseReturnsNull() = runTest {
        withServer(
            handler = { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            },
        ) { url ->
            assertNull(fetchGitHubReleaseJson(url, timeoutMs = 2_000))
        }

        withServer(
            handler = { exchange ->
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            },
        ) { url ->
            assertNull(fetchGitHubReleaseJson(url, timeoutMs = 2_000))
        }
    }

    @Test
    fun anUnreachablePortReturnsNullWithoutThrowing() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.stop(0)

        assertNull(fetchGitHubReleaseJson("http://127.0.0.1:$port/releases/latest", timeoutMs = 500))
        assertNull(fetchGitHubReleaseJson("not a valid url", timeoutMs = 500))
    }

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/releases/latest") { exchange ->
                handler(exchange)
            }
            server.start()
            block("http://127.0.0.1:${server.address.port}/releases/latest")
        } finally {
            server.stop(0)
        }
    }
}
