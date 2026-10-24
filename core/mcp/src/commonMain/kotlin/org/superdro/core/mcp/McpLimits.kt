package org.superdro.core.mcp

/** The limits of the MCP server. */
object McpLimits {
    /** The largest body of `POST /mcp`. A larger body gives 413. */
    const val MCP_BODY_BYTES: Int = 2 * 1024 * 1024

    /** The largest body of `POST /upload`. A larger body gives 413. */
    const val UPLOAD_BYTES: Int = 8 * 1024 * 1024

    /** The life of an upload or a download in the file store. */
    const val FILE_TTL_MS: Long = 60 * 60 * 1000L

    /** The time that a write tool waits for the operator. */
    const val CONFIRM_TIMEOUT_MS: Long = 60_000L

    /** The most requests from one client address in one second. More give 429. */
    const val REQUESTS_PER_SECOND: Int = 20

    /** The time between two keep-alive comments on an SSE stream. */
    const val SSE_KEEP_ALIVE_MS: Long = 15_000L

    /** The failed token checks from one address in [AUTH_LOCK_MS] that lock the address. */
    const val AUTH_FAILURES: Int = 10

    /** The window of the failed token checks, and the time of the lock. */
    const val AUTH_LOCK_MS: Long = 60_000L

    /** The largest request line and header block. A larger head gives 431. */
    const val HEADER_BYTES: Int = 16 * 1024

    /** A client stays in the client list for this time after its last request. */
    const val CLIENT_AGE_MS: Long = 60_000L
}
