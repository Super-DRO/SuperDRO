package org.superdro.core.connection.ble

import kotlin.math.min

/**
 * The chunks of a BLE write. One ATT write carries the MTU less the 3 bytes of the ATT header.
 */
object BleChunks {

    /** The ATT MTU of a link before the MTU exchange. */
    const val DEFAULT_MTU: Int = 23

    /** The bytes of the ATT header in a write. */
    const val ATT_HEADER_BYTES: Int = 3

    /** The payload of one write at [DEFAULT_MTU]: 20 bytes. */
    const val DEFAULT_PAYLOAD_BYTES: Int = DEFAULT_MTU - ATT_HEADER_BYTES

    /**
     * Gives the size of one chunk.
     *
     * - With a known [mtu]: `mtu - 3`. When the engine also gives [maxWriteLength], the smaller of the two.
     * - With no [mtu]: [maxWriteLength], else 20 bytes.
     *
     * A value that is not positive counts as not known. The result is 1 or more.
     */
    fun chunkSize(mtu: Int?, maxWriteLength: Int?): Int {
        val fromMtu = mtu?.minus(ATT_HEADER_BYTES)?.takeIf { size -> size > 0 }
        val fromEngine = maxWriteLength?.takeIf { size -> size > 0 }
        return when {
            fromMtu != null && fromEngine != null -> min(fromMtu, fromEngine)
            fromMtu != null -> fromMtu
            fromEngine != null -> fromEngine
            else -> DEFAULT_PAYLOAD_BYTES
        }
    }

    /** Splits [bytes] into chunks of [size] bytes. The last chunk can be shorter. No bytes give no chunk. */
    fun split(bytes: ByteArray, size: Int): List<ByteArray> {
        require(size > 0) { "The chunk size must be 1 or more, not $size." }
        return (bytes.indices step size).map { start -> bytes.copyOfRange(start, min(start + size, bytes.size)) }
    }
}
