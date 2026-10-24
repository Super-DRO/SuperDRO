package org.superdro.core.ui.mcp

import kotlin.math.abs

/**
 * A QR code (ISO/IEC 18004) in the byte mode with the error correction level M, versions 1 to
 * 10. The settings screen shows the Claude Code command as a QR code. The class has no
 * dependency: the project adds no library for it.
 *
 * [size] is the number of modules on one side: `17 + 4 * version`. [get] gives true for a dark
 * module. A reader needs a quiet zone of four light modules around the code.
 */
class QrCode private constructor(val version: Int, val mask: Int, val size: Int, private val modules: BooleanArray) {

    /** True when the module in column [x] and row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /** The largest version that [encode] gives. Version 10 holds 213 bytes. */
        const val MAX_VERSION: Int = 10

        private const val MODE_BYTE: Int = 0x4
        private const val PENALTY_N1: Int = 3
        private const val PENALTY_N2: Int = 3
        private const val PENALTY_N3: Int = 40
        private const val PENALTY_N4: Int = 10

        /** The number of codewords of each version. */
        private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)

        /** The number of error correction codewords of each block, level M. */
        private val EC_CODEWORDS_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

        /** The number of blocks of each version, level M. */
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        /** The center positions of the alignment patterns of each version. */
        private val ALIGNMENT_POSITIONS: List<IntArray> = listOf(
            intArrayOf(),
            intArrayOf(6, 18),
            intArrayOf(6, 22),
            intArrayOf(6, 26),
            intArrayOf(6, 30),
            intArrayOf(6, 34),
            intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
        )

        /** Gives the number of data codewords of [version] at level M. */
        fun dataCodewords(version: Int): Int = TOTAL_CODEWORDS[version - 1] - EC_CODEWORDS_PER_BLOCK[version - 1] * BLOCKS[version - 1]

        /** Gives the number of bytes that [version] holds in the byte mode at level M. */
        fun byteCapacity(version: Int): Int = (dataCodewords(version) * 8 - 4 - countBits(version)) / 8

        private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

        /** Encodes the UTF-8 bytes of [text]. Null when the text needs more than version [MAX_VERSION]. */
        fun encode(text: String): QrCode? = encode(text.encodeToByteArray())

        /** Encodes [data]. Null when the data needs more than version [MAX_VERSION]. */
        fun encode(data: ByteArray): QrCode? {
            val version = (1..MAX_VERSION).firstOrNull { candidate -> data.size <= byteCapacity(candidate) } ?: return null
            val codewords = addErrorCorrection(dataBits(data, version), version)
            val size = 17 + 4 * version
            val canvas = Canvas(size)
            canvas.drawFunctionPatterns(version)
            canvas.drawCodewords(codewords)
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                canvas.applyMask(mask)
                canvas.drawFormatBits(mask)
                val penalty = canvas.penalty()
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    best = mask
                }
                canvas.applyMask(mask)
            }
            canvas.applyMask(best)
            canvas.drawFormatBits(best)
            return QrCode(version, best, size, canvas.modules)
        }

        /** Gives the data codewords: the mode, the count, the bytes, the terminator, and the pad bytes. */
        internal fun dataBits(data: ByteArray, version: Int): ByteArray {
            val bits = BitBuffer()
            bits.append(MODE_BYTE, 4)
            bits.append(data.size, countBits(version))
            data.forEach { byte -> bits.append(byte.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            bits.append(0, minOf(4, capacity - bits.length))
            while (bits.length % 8 != 0) bits.append(0, 1)
            var pad = 0xEC
            while (bits.length < capacity) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return bits.toBytes()
        }

        /** Splits [data] into the blocks, adds the error correction of each block, and interleaves the result. */
        internal fun addErrorCorrection(data: ByteArray, version: Int): ByteArray {
            val blocks = BLOCKS[version - 1]
            val ecLength = EC_CODEWORDS_PER_BLOCK[version - 1]
            val total = TOTAL_CODEWORDS[version - 1]
            val shortBlocks = blocks - total % blocks
            val shortLength = total / blocks
            val divisor = rsDivisor(ecLength)
            val dataBlocks = ArrayList<ByteArray>(blocks)
            val ecBlocks = ArrayList<ByteArray>(blocks)
            var offset = 0
            for (index in 0 until blocks) {
                val length = shortLength - ecLength + (if (index < shortBlocks) 0 else 1)
                val block = data.copyOfRange(offset, offset + length)
                offset += length
                dataBlocks += block
                ecBlocks += rsRemainder(block, divisor)
            }
            val result = ByteArray(total)
            var position = 0
            val longest = shortLength - ecLength + 1
            for (column in 0 until longest) {
                dataBlocks.forEach { block -> if (column < block.size) result[position++] = block[column] }
            }
            for (column in 0 until ecLength) {
                ecBlocks.forEach { block -> result[position++] = block[column] }
            }
            return result
        }

        /**
         * Gives the Reed-Solomon generator polynomial of [degree] over GF(256) with the modulus
         * 0x11D: the coefficients from the highest power to the lowest, without the leading 1.
         */
        internal fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (step in 0 until degree) {
                for (index in result.indices) {
                    var value = multiply(result[index].toInt() and 0xFF, root)
                    if (index + 1 < result.size) value = value xor (result[index + 1].toInt() and 0xFF)
                    result[index] = value.toByte()
                }
                root = multiply(root, 2)
            }
            return result
        }

        /** Gives the Reed-Solomon error correction codewords of [data] with [divisor]. */
        internal fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            data.forEach { byte ->
                val factor = (byte.toInt() and 0xFF) xor (result[0].toInt() and 0xFF)
                result.copyInto(result, 0, 1, result.size)
                result[result.size - 1] = 0
                for (index in result.indices) {
                    result[index] = ((result[index].toInt() and 0xFF) xor multiply(divisor[index].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /** The product of [x] and [y] in GF(256) with the modulus 0x11D. */
        internal fun multiply(x: Int, y: Int): Int {
            var product = 0
            for (bit in 7 downTo 0) {
                product = (product shl 1) xor ((product ushr 7) * 0x11D)
                product = product xor (((y ushr bit) and 1) * x)
            }
            return product
        }

        /** The 15 format bits of level M and [mask]: BCH(15,5) with the generator 0x537, masked with 0x5412. */
        internal fun formatBits(mask: Int): Int {
            val data = mask
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
            return ((data shl 10) or remainder) xor 0x5412
        }

        /** The 18 version bits of [version] (7 and more): BCH(18,6) with the generator 0x1F25. */
        internal fun versionBits(version: Int): Int {
            var remainder = version
            repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
            return (version shl 12) or remainder
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }

        /** The modules of one code while the encoder draws them. */
        private class Canvas(val size: Int) {
            val modules = BooleanArray(size * size)
            private val function = BooleanArray(size * size)

            private fun set(x: Int, y: Int, dark: Boolean) {
                modules[y * size + x] = dark
                function[y * size + x] = true
            }

            private fun get(x: Int, y: Int): Boolean = modules[y * size + x]

            fun drawFunctionPatterns(version: Int) {
                for (index in 0 until size) {
                    set(6, index, index % 2 == 0)
                    set(index, 6, index % 2 == 0)
                }
                drawFinder(3, 3)
                drawFinder(size - 4, 3)
                drawFinder(3, size - 4)
                val positions = ALIGNMENT_POSITIONS[version - 1]
                val last = positions.size - 1
                for (i in positions.indices) {
                    for (j in positions.indices) {
                        val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                        if (!corner) drawAlignment(positions[i], positions[j])
                    }
                }
                drawFormatBits(0)
                if (version >= 7) {
                    val bits = versionBits(version)
                    for (index in 0 until 18) {
                        val dark = (bits ushr index) and 1 != 0
                        val a = size - 11 + index % 3
                        val b = index / 3
                        set(a, b, dark)
                        set(b, a, dark)
                    }
                }
            }

            private fun drawFinder(centerX: Int, centerY: Int) {
                for (dy in -4..4) {
                    for (dx in -4..4) {
                        val x = centerX + dx
                        val y = centerY + dy
                        if (x !in 0 until size || y !in 0 until size) continue
                        val distance = maxOf(abs(dx), abs(dy))
                        set(x, y, distance != 2 && distance != 4)
                    }
                }
            }

            private fun drawAlignment(centerX: Int, centerY: Int) {
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        set(centerX + dx, centerY + dy, maxOf(abs(dx), abs(dy)) != 1)
                    }
                }
            }

            fun drawFormatBits(mask: Int) {
                val bits = formatBits(mask)
                fun bit(index: Int): Boolean = (bits ushr index) and 1 != 0
                for (index in 0..5) set(8, index, bit(index))
                set(8, 7, bit(6))
                set(8, 8, bit(7))
                set(7, 8, bit(8))
                for (index in 9..14) set(14 - index, 8, bit(index))
                for (index in 0..7) set(size - 1 - index, 8, bit(index))
                for (index in 8..14) set(8, size - 15 + index, bit(index))
                set(8, size - 8, true)
            }

            fun drawCodewords(data: ByteArray) {
                var bitIndex = 0
                var right = size - 1
                while (right >= 1) {
                    if (right == 6) right = 5
                    for (vertical in 0 until size) {
                        for (step in 0..1) {
                            val x = right - step
                            val upward = (right + 1) and 2 == 0
                            val y = if (upward) size - 1 - vertical else vertical
                            if (!function[y * size + x] && bitIndex < data.size * 8) {
                                modules[y * size + x] = (data[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1 != 0
                                bitIndex++
                            }
                        }
                    }
                    right -= 2
                }
            }

            /** Inverts each data module that the mask selects. A second call removes the mask. */
            fun applyMask(mask: Int) {
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        if (!function[y * size + x] && maskBit(mask, x, y)) modules[y * size + x] = !modules[y * size + x]
                    }
                }
            }

            /** The penalty score of the current modules: the lower, the better the mask. */
            fun penalty(): Int {
                var result = 0
                for (y in 0 until size) {
                    result += runPenalty { x -> get(x, y) }
                    result += finderPenalty { x -> get(x, y) }
                }
                for (x in 0 until size) {
                    result += runPenalty { y -> get(x, y) }
                    result += finderPenalty { y -> get(x, y) }
                }
                for (y in 0 until size - 1) {
                    for (x in 0 until size - 1) {
                        val color = get(x, y)
                        if (color == get(x + 1, y) && color == get(x, y + 1) && color == get(x + 1, y + 1)) result += PENALTY_N2
                    }
                }
                val dark = modules.count { module -> module }
                val total = size * size
                val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
                result += k * PENALTY_N4
                return result
            }

            private inline fun runPenalty(module: (Int) -> Boolean): Int {
                var result = 0
                var runColor = false
                var runLength = 0
                for (index in 0 until size) {
                    val color = module(index)
                    if (index > 0 && color == runColor) {
                        runLength++
                        if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
                    } else {
                        runColor = color
                        runLength = 1
                    }
                }
                return result
            }

            /** Counts the patterns dark-light-dark-dark-dark-light-dark with four light modules on one side. */
            private inline fun finderPenalty(module: (Int) -> Boolean): Int {
                var result = 0
                val line = BooleanArray(size) { index -> module(index) }
                for (start in 0..size - 11) {
                    val a = matches(line, start, PATTERN_A)
                    val b = matches(line, start, PATTERN_B)
                    if (a || b) result += PENALTY_N3
                }
                return result
            }

            private fun matches(line: BooleanArray, start: Int, pattern: BooleanArray): Boolean =
                pattern.indices.all { index -> line[start + index] == pattern[index] }
        }

        private val PATTERN_A = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        private val PATTERN_B = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
    }

    /** A growing list of bits, the first bit first. */
    private class BitBuffer {
        private val bits = ArrayList<Boolean>()

        val length: Int get() = bits.size

        /** Appends the lowest [count] bits of [value], the highest first. */
        fun append(value: Int, count: Int) {
            for (index in count - 1 downTo 0) bits += (value ushr index) and 1 != 0
        }

        fun toBytes(): ByteArray {
            val result = ByteArray((bits.size + 7) / 8)
            bits.forEachIndexed { index, bit -> if (bit) result[index / 8] = (result[index / 8].toInt() or (0x80 ushr (index % 8))).toByte() }
            return result
        }
    }
}
