package org.superdro.core.drawing.codec

/** An error in gzip or deflate data. */
class GzipException(message: String) : RuntimeException(message)

/** The output of [Gzip.decompress] is larger than the limit. */
class GzipSizeException(val limit: Int) : RuntimeException("The decompressed data is larger than $limit bytes.")

/**
 * The gzip reader of the wireframe documents (RFC 1952 and RFC 1951) in common Kotlin. It reads
 * all members of a gzip file and checks the CRC-32 and the size of each member.
 */
object Gzip {

    /**
     * Gives the decompressed bytes of the gzip data [bytes].
     *
     * @param maxOutput The maximum number of output bytes. More output gives [GzipSizeException].
     * @throws GzipException for bad gzip or deflate data.
     */
    fun decompress(bytes: ByteArray, maxOutput: Int = Int.MAX_VALUE): ByteArray {
        val output = ByteSink(maxOutput)
        var position = 0
        var members = 0
        while (position < bytes.size) {
            if (members > 0 && bytes.size - position < 2) break
            if (bytes.size - position < 18) throw GzipException("The gzip data is too short.")
            if (bytes[position] != 0x1F.toByte() || bytes[position + 1] != 0x8B.toByte()) {
                // Some programs add zero bytes after the last member.
                if (members > 0 && (position until bytes.size).all { index -> bytes[index] == 0.toByte() }) break
                throw GzipException("The data has no gzip magic at byte $position.")
            }
            if (bytes[position + 2] != 8.toByte()) throw GzipException("The gzip method is not deflate.")
            val flags = bytes[position + 3].toInt() and 0xFF
            if (flags and 0xE0 != 0) throw GzipException("The gzip flags have reserved bits.")
            var cursor = position + 10
            if (flags and FEXTRA != 0) {
                if (cursor + 2 > bytes.size) throw GzipException("The gzip extra field is not complete.")
                cursor += 2 + ((bytes[cursor].toInt() and 0xFF) or ((bytes[cursor + 1].toInt() and 0xFF) shl 8))
            }
            if (flags and FNAME != 0) cursor = skipZeroTerminated(bytes, cursor)
            if (flags and FCOMMENT != 0) cursor = skipZeroTerminated(bytes, cursor)
            if (flags and FHCRC != 0) cursor += 2
            if (cursor > bytes.size) throw GzipException("The gzip header is not complete.")
            val start = output.size
            val inflater = Inflater(bytes, cursor, output)
            cursor = inflater.inflate()
            if (cursor + 8 > bytes.size) throw GzipException("The gzip trailer is not complete.")
            val expectedCrc = readInt32(bytes, cursor)
            val expectedSize = readInt32(bytes, cursor + 4)
            val actualCrc = Crc32.of(output.buffer, start, output.size)
            if (actualCrc != expectedCrc) throw GzipException("The gzip CRC-32 is not correct.")
            if ((output.size - start) != expectedSize) throw GzipException("The gzip size is not correct.")
            position = cursor + 8
            members++
        }
        if (members == 0) throw GzipException("The data has no gzip member.")
        return output.toByteArray()
    }

    private fun skipZeroTerminated(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index < bytes.size && bytes[index] != 0.toByte()) index++
        if (index >= bytes.size) throw GzipException("A gzip header text has no end.")
        return index + 1
    }

    private fun readInt32(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
            ((bytes[index + 3].toInt() and 0xFF) shl 24)

    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
}

/** The CRC-32 of gzip (polynomial 0xEDB88320). */
internal object Crc32 {
    private val table = IntArray(256) { index ->
        var value = index
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
        value
    }

    fun of(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var crc = -1
        for (index in start until end) crc = table[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}

/** A growable byte buffer with a size limit. */
internal class ByteSink(private val limit: Int) {
    var buffer = ByteArray(4096)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size >= limit) throw GzipSizeException(limit)
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = value.toByte()
    }

    operator fun get(index: Int): Byte = buffer[index]

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** The deflate decoder (RFC 1951): stored, fixed Huffman, and dynamic Huffman blocks. */
internal class Inflater(private val input: ByteArray, private var position: Int, private val output: ByteSink) {
    private var bitBuffer = 0
    private var bitCount = 0
    private val memberStart = output.size

    /** Decodes the blocks up to the last block. Gives the input position after the data. */
    fun inflate(): Int {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LENGTHS, FIXED_DISTANCES)
                2 -> dynamic()
                else -> throw GzipException("A deflate block has the reserved type 3.")
            }
        } while (last == 0)
        return position
    }

    private fun bits(count: Int): Int {
        var value = bitBuffer
        while (bitCount < count) {
            if (position >= input.size) throw GzipException("The deflate data is not complete.")
            value = value or ((input[position++].toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
        bitBuffer = value ushr count
        bitCount -= count
        return value and ((1 shl count) - 1)
    }

    private fun stored() {
        bitBuffer = 0
        bitCount = 0
        if (position + 4 > input.size) throw GzipException("A stored deflate block is not complete.")
        val length = (input[position].toInt() and 0xFF) or ((input[position + 1].toInt() and 0xFF) shl 8)
        val complement = (input[position + 2].toInt() and 0xFF) or ((input[position + 3].toInt() and 0xFF) shl 8)
        position += 4
        if (length != complement.inv() and 0xFFFF) throw GzipException("A stored deflate block has a bad length.")
        if (position + length > input.size) throw GzipException("A stored deflate block is not complete.")
        for (index in 0 until length) output.add(input[position + index].toInt())
        position += length
    }

    private fun decode(table: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..MAX_BITS) {
            code = code or bits(1)
            val count = table.count[length]
            if (code - count < first) return table.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw GzipException("The deflate data has a bad Huffman code.")
    }

    private fun codes(lengths: Huffman, distances: Huffman) {
        while (true) {
            val symbol = decode(lengths)
            when {
                symbol < 256 -> output.add(symbol)
                symbol == 256 -> return
                else -> {
                    val lengthIndex = symbol - 257
                    if (lengthIndex >= 29) throw GzipException("The deflate data has a bad length symbol.")
                    val length = LENGTH_BASE[lengthIndex] + bits(LENGTH_EXTRA[lengthIndex])
                    val distanceSymbol = decode(distances)
                    if (distanceSymbol >= 30) throw GzipException("The deflate data has a bad distance symbol.")
                    val distance = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
                    if (distance > output.size - memberStart) throw GzipException("A deflate distance is too far back.")
                    val from = output.size - distance
                    for (index in 0 until length) output.add(output[from + index].toInt())
                }
            }
        }
    }

    private fun dynamic() {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeCount = bits(4) + 4
        if (literalCount > 286 || distanceCount > 30) throw GzipException("A dynamic deflate block has bad counts.")
        val lengths = IntArray(320)
        for (index in 0 until codeCount) lengths[CODE_LENGTH_ORDER[index]] = bits(3)
        val codeTable = Huffman.of(lengths, 0, 19)
        var index = 0
        while (index < literalCount + distanceCount) {
            var symbol = decode(codeTable)
            if (symbol < 16) {
                lengths[index++] = symbol
            } else {
                var length = 0
                when (symbol) {
                    16 -> {
                        if (index == 0) throw GzipException("A deflate repeat has no previous length.")
                        length = lengths[index - 1]
                        symbol = 3 + bits(2)
                    }
                    17 -> symbol = 3 + bits(3)
                    else -> symbol = 11 + bits(7)
                }
                if (index + symbol > literalCount + distanceCount) throw GzipException("A deflate repeat is too long.")
                repeat(symbol) { lengths[index++] = length }
            }
        }
        if (lengths[256] == 0) throw GzipException("A dynamic deflate block has no end code.")
        val literalTable = Huffman.of(lengths, 0, literalCount)
        val distanceTable = Huffman.of(lengths, literalCount, distanceCount)
        codes(literalTable, distanceTable)
    }

    /** A canonical Huffman table: the code count for each length and the symbols in code order. */
    class Huffman(val count: IntArray, val symbol: IntArray) {
        companion object {
            fun of(lengths: IntArray, offset: Int, size: Int): Huffman {
                val count = IntArray(MAX_BITS + 1)
                for (index in 0 until size) count[lengths[offset + index]]++
                val offsets = IntArray(MAX_BITS + 1)
                for (length in 1 until MAX_BITS) offsets[length + 1] = offsets[length] + count[length]
                val symbol = IntArray(size)
                for (index in 0 until size) {
                    val length = lengths[offset + index]
                    if (length != 0) symbol[offsets[length]++] = index
                }
                count[0] = 0
                return Huffman(count, symbol)
            }
        }
    }

    companion object {
        const val MAX_BITS = 15

        private val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DISTANCE_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
            6145, 8193, 12289, 16385, 24577,
        )
        private val DISTANCE_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )
        private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        private val FIXED_LENGTHS: Huffman = IntArray(288) { symbol ->
            when {
                symbol < 144 -> 8
                symbol < 256 -> 9
                symbol < 280 -> 7
                else -> 8
            }
        }.let { lengths -> Huffman.of(lengths, 0, 288) }

        private val FIXED_DISTANCES: Huffman = Huffman.of(IntArray(30) { 5 }, 0, 30)
    }
}
