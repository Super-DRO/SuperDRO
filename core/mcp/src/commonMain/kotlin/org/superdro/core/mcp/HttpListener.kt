package org.superdro.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.superdro.core.dataoutput.ByteLink

/** Sends the events of one SSE stream. */
fun interface SseSink {
    /** Sends [event] as one `event: message` frame. */
    suspend fun send(event: JsonObject)
}

/** The reply of the handler to one request. */
sealed interface HttpReply {
    /** One response with a full body. The connection stays open when the client asked for it. */
    class Full(val response: HttpResponse) : HttpReply

    /**
     * An SSE stream. [produce] sends the events and ends the stream when it returns. The
     * connection closes at the end of the stream, and a keep-alive comment goes out each
     * [McpLimits.SSE_KEEP_ALIVE_MS].
     */
    class Stream(val produce: suspend (SseSink) -> Unit) : HttpReply
}

/**
 * Serves the HTTP/1.1 requests of one connection, one after the other, until the client closes
 * the connection, a request is not readable, or a stream ends.
 *
 * @param bodyLimit Gives the largest body for a request head. A larger body gives 413.
 * @param handler Gives the reply of one request. An exception gives 500.
 */
class HttpConnection(
    private val link: ByteLink,
    private val bodyLimit: (HttpHead) -> Int,
    private val handler: suspend (HttpRequest) -> HttpReply,
    private val keepAliveMs: Long = McpLimits.SSE_KEEP_ALIVE_MS,
) {
    /** Serves the connection and closes the link at the end. */
    suspend fun serve() {
        val reader = HttpReader(link)
        try {
            while (true) {
                val head = reader.readHead() ?: return
                val keepAlive = head.keepAlive
                if (head.contentLength > bodyLimit(head)) {
                    write(HttpResponse.text(413, "The body is too large"), keepAlive = false)
                    return
                }
                val body = reader.readBody(head.contentLength.toInt())
                val reply = try {
                    handler(HttpRequest(head, body))
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    HttpReply.Full(HttpResponse.text(500, "The server failed: ${exception.message ?: exception::class.simpleName}"))
                }
                when (reply) {
                    is HttpReply.Full -> {
                        write(reply.response, keepAlive)
                        if (!keepAlive) return
                    }
                    is HttpReply.Stream -> {
                        stream(reply)
                        return
                    }
                }
            }
        } catch (exception: HttpRequestException) {
            runCatching { write(HttpResponse.text(exception.status, exception.message ?: Http.reason(exception.status)), keepAlive = false) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // A read error or a write error closes the connection.
        } finally {
            link.close()
        }
    }

    private suspend fun write(response: HttpResponse, keepAlive: Boolean) {
        link.write(Http.encode(response, keepAlive))
    }

    /**
     * Writes the SSE head, then the events of [reply]. A keep-alive comment goes out after each
     * [keepAliveMs] with no event. The end of the inbound stream (the client closed) stops the
     * producer.
     */
    private suspend fun stream(reply: HttpReply.Stream) {
        link.write(Http.sseHead())
        val writeLock = Mutex()
        coroutineScope {
            val producer = launch {
                var lastWrite = 0L
                val sink = SseSink { event ->
                    writeLock.withLock {
                        link.write(Http.sseEvent(McpJson.encodeToString(JsonElement.serializer(), event)))
                        lastWrite += 1
                    }
                }
                val keepAlive = launch {
                    while (true) {
                        val seen = lastWrite
                        delay(keepAliveMs)
                        if (seen == lastWrite) writeLock.withLock { link.write(Http.sseKeepAlive()) }
                    }
                }
                try {
                    reply.produce(sink)
                } finally {
                    keepAlive.cancel()
                }
            }
            val watcher = launch {
                val buffer = ByteArray(1024)
                try {
                    while (link.read(buffer) >= 0) {
                        // The client sends nothing of use on a stream. The server reads to see the end.
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // A read error is the end of the connection.
                }
                producer.cancel()
            }
            producer.join()
            watcher.cancel()
        }
    }
}
