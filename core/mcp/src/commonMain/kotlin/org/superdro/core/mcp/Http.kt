package org.superdro.core.mcp

import kotlinx.serialization.json.JsonElement
import org.superdro.core.dataoutput.ByteLink

/** The headers of one HTTP message. The names compare without case. */
class HttpHeaders(private val entries: List<Pair<String, String>>) {

    /** The first value of [name], or null. */
    operator fun get(name: String): String? = entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.second

    /** All values of [name]. */
    fun all(name: String): List<String> = entries.filter { (key, _) -> key.equals(name, ignoreCase = true) }.map { (_, value) -> value }

    /** Gives true when the message has the header [name]. */
    fun has(name: String): Boolean = entries.any { (key, _) -> key.equals(name, ignoreCase = true) }

    val size: Int get() = entries.size

    companion object {
        val EMPTY: HttpHeaders = HttpHeaders(emptyList())
    }
}

/**
 * The request line and the headers of one request.
 *
 * @property target The request target, for example `/download/abc?x=1`.
 * @property contentLength The declared body length, or 0 when the request has no body.
 * @property keepAlive True when the connection stays open after the response.
 */
class HttpHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: HttpHeaders,
) {
    /** The path of [target], with no query. */
    val path: String get() = target.substringBefore('?')

    val contentLength: Long = headers["Content-Length"]?.trim()?.toLongOrNull()?.takeIf { length -> length >= 0L } ?: 0L

    val keepAlive: Boolean
        get() {
            val connection = headers["Connection"]?.lowercase() ?: return version != "HTTP/1.0"
            return when {
                "close" in connection -> false
                "keep-alive" in connection -> true
                else -> version != "HTTP/1.0"
            }
        }
}

/** One parsed request: the head and the body. */
class HttpRequest(val head: HttpHead, val body: ByteArray) {
    val method: String get() = head.method
    val path: String get() = head.path
    val headers: HttpHeaders get() = head.headers

    /** The media type of the body with no parameters, in lowercase, or null. */
    val contentType: String? get() = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()?.takeIf { type -> type.isNotEmpty() }
}

/** One response with a full body. */
class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        /** A `text/plain` response. The text gets a final line feed. */
        fun text(status: Int, text: String, headers: List<Pair<String, String>> = emptyList()): HttpResponse =
            HttpResponse(status, "$text\n".encodeToByteArray(), "text/plain; charset=utf-8", headers)

        /** An `application/json` response. */
        fun json(status: Int, body: JsonElement, headers: List<Pair<String, String>> = emptyList()): HttpResponse =
            HttpResponse(status, McpJson.encodeToString(JsonElement.serializer(), body).encodeToByteArray(), "application/json", headers)

        /** A response with no body. */
        fun empty(status: Int, headers: List<Pair<String, String>> = emptyList()): HttpResponse = HttpResponse(status, headers = headers)
    }
}

/**
 * A request that the server cannot read. The connection answers [status] with [message] and
 * closes.
 */
class HttpRequestException(val status: Int, message: String) : Exception(message)

/** The HTTP/1.1 text forms. */
object Http {
    private val CRLF = "\r\n".encodeToByteArray()

    /** The reason phrase of [status]. */
    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "Status $status"
    }

    /** The bytes of [response]: the status line, the headers, the blank line, and the body. */
    fun encode(response: HttpResponse, keepAlive: Boolean): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            if (response.contentType != null) append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        return head.encodeToByteArray() + response.body
    }

    /** The head of an SSE response. The connection closes at the end of the stream. */
    fun sseHead(): ByteArray =
        (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "\r\n"
            ).encodeToByteArray()

    /** One SSE frame `event: message` with [data] as one line. */
    fun sseEvent(data: String): ByteArray = "event: message\ndata: $data\n\n".encodeToByteArray()

    /** The SSE comment of the keep-alive. */
    fun sseKeepAlive(): ByteArray = ": keep-alive\n\n".encodeToByteArray()

    /** The sentinel form of a header value: `=?base64?<text>?=`. */
    private const val SENTINEL_START = "=?base64?"
    private const val SENTINEL_END = "?="

    /**
     * Gives the decoded value of a header that can carry the Base64 sentinel form
     * (`Mcp-Name`, `Mcp-Param-*`). A plain value stays. A sentinel that is not Base64 gives null.
     */
    fun decodeHeaderValue(value: String): String? {
        if (!value.startsWith(SENTINEL_START) || !value.endsWith(SENTINEL_END) || value.length < SENTINEL_START.length + SENTINEL_END.length) return value
        val inner = value.substring(SENTINEL_START.length, value.length - SENTINEL_END.length)
        return Base64Text.decode(inner)?.decodeToString()
    }

    /** Gives the host part of a `Host` header or an origin authority: no port, no brackets, no zone. */
    fun hostOf(authority: String): String {
        val trimmed = authority.trim()
        val bare = if (trimmed.startsWith("[")) {
            trimmed.substringAfter('[').substringBefore(']')
        } else if (trimmed.count { char -> char == ':' } == 1) {
            trimmed.substringBefore(':')
        } else {
            trimmed
        }
        return bare.substringBefore('%').lowercase()
    }
}

/**
 * Reads HTTP/1.1 requests from a [ByteLink], one after the other. The reader keeps the bytes
 * that arrive after one request for the next request.
 */
class HttpReader(private val link: ByteLink, private val headerLimit: Int = McpLimits.HEADER_BYTES) {
    private var buffer = ByteArray(0)
    private var position = 0

    /**
     * Reads the request line and the headers. Gives null when the stream ends before a request
     * starts (the client closed the connection).
     *
     * @throws HttpRequestException 400 for a malformed line, 411 for a chunked body or a POST
     *   with no `Content-Length`, 431 for a head above the limit.
     */
    suspend fun readHead(): HttpHead? {
        var requestLine = readLine(first = true) ?: return null
        // A client can send an empty line after a body. The reader skips it.
        while (requestLine.isEmpty()) requestLine = readLine(first = true) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0].isEmpty() || !parts[1].startsWith("/") || !parts[2].startsWith("HTTP/1.")) {
            throw HttpRequestException(400, "The request line is not valid")
        }
        val entries = mutableListOf<Pair<String, String>>()
        var headBytes = requestLine.length + 2
        while (true) {
            val line = readLine(first = false) ?: throw HttpRequestException(400, "The request ended in the headers")
            headBytes += line.length + 2
            if (headBytes > headerLimit) throw HttpRequestException(431, "The request head is too large")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpRequestException(400, "A header line is not valid")
            entries += line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        val headers = HttpHeaders(entries)
        if (headers.has("Transfer-Encoding")) throw HttpRequestException(411, "The server needs Content-Length")
        val length = headers["Content-Length"]
        if (length != null && (length.trim().toLongOrNull() == null || length.trim().toLong() < 0)) {
            throw HttpRequestException(400, "Content-Length is not valid")
        }
        if (parts[0] == "POST" && length == null) throw HttpRequestException(411, "The server needs Content-Length")
        return HttpHead(parts[0], parts[1], parts[2], headers)
    }

    /**
     * Reads [length] body bytes.
     *
     * @throws HttpRequestException 400 when the stream ends before the body is complete.
     */
    suspend fun readBody(length: Int): ByteArray {
        while (available() < length) {
            if (!fill()) throw HttpRequestException(400, "The request ended in the body")
        }
        val body = buffer.copyOfRange(position, position + length)
        position += length
        compact()
        return body
    }

    private fun available(): Int = buffer.size - position

    /** Reads one line up to CRLF (or LF), with no line end. Null at the end of the stream. */
    private suspend fun readLine(first: Boolean): String? {
        while (true) {
            val end = indexOfLineFeed()
            if (end >= 0) {
                var lineEnd = end
                if (lineEnd > position && buffer[lineEnd - 1] == '\r'.code.toByte()) lineEnd--
                val line = buffer.decodeToString(position, lineEnd)
                position = end + 1
                compact()
                return line
            }
            if (available() > headerLimit) throw HttpRequestException(431, "The request head is too large")
            if (!fill()) {
                if (first && available() == 0) return null
                throw HttpRequestException(400, "The request ended in a line")
            }
        }
    }

    private fun indexOfLineFeed(): Int {
        for (index in position until buffer.size) if (buffer[index] == '\n'.code.toByte()) return index
        return -1
    }

    /** Reads more bytes from the link. Gives false at the end of the stream. */
    private suspend fun fill(): Boolean {
        val chunk = ByteArray(CHUNK_BYTES)
        val count = link.read(chunk)
        if (count < 0) return false
        if (count == 0) return true
        buffer = buffer.copyOfRange(position, buffer.size) + chunk.copyOf(count)
        position = 0
        return true
    }

    private fun compact() {
        if (position == 0) return
        buffer = buffer.copyOfRange(position, buffer.size)
        position = 0
    }

    private companion object {
        const val CHUNK_BYTES = 8192
    }
}
