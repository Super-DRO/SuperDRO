package org.superdro.core.functions.tipcal

/**
 * The SHA-256 hash of FIPS 180-4, in common Kotlin. The tip calibration uses it for the
 * digest of the session JSON. SuperDRO adds no library for it.
 */
object Sha256 {

    /** The length of a digest in bytes. */
    const val DIGEST_BYTES: Int = 32

    private const val BLOCK_BYTES = 64

    private val roundConstants: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).toIntArray()

    private val initialHash: IntArray = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
    ).toIntArray()

    /** Changes each 32-bit word, written as an unsigned Long, to its Int bit pattern. */
    private fun LongArray.toIntArray(): IntArray = IntArray(size) { index -> this[index].toInt() }

    /** Gives the 32-byte digest of [input]. */
    fun digest(input: ByteArray): ByteArray {
        val hash = initialHash.copyOf()
        val words = IntArray(64)
        val padded = pad(input)
        for (blockStart in padded.indices step BLOCK_BYTES) {
            compress(padded, blockStart, hash, words)
        }
        val result = ByteArray(DIGEST_BYTES)
        for (i in hash.indices) {
            result[i * 4] = (hash[i] ushr 24).toByte()
            result[i * 4 + 1] = (hash[i] ushr 16).toByte()
            result[i * 4 + 2] = (hash[i] ushr 8).toByte()
            result[i * 4 + 3] = hash[i].toByte()
        }
        return result
    }

    /** Gives the digest of [input] as 64 lowercase hex digits. */
    fun hex(input: ByteArray): String = digest(input).joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        HEX_DIGITS[value ushr 4].toString() + HEX_DIGITS[value and 0x0f]
    }

    /** Gives the digest of the UTF-8 bytes of [text] as 64 lowercase hex digits. */
    fun hexOfUtf8(text: String): String = hex(text.encodeToByteArray())

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Adds the bit 1, the zero bytes, and the 64-bit length in bits (big endian). */
    private fun pad(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val zeroCount = (BLOCK_BYTES - ((input.size + 9) % BLOCK_BYTES)) % BLOCK_BYTES
        val padded = ByteArray(input.size + 1 + zeroCount + 8)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }
        return padded
    }

    private fun compress(block: ByteArray, start: Int, hash: IntArray, words: IntArray) {
        for (t in 0 until 16) {
            val offset = start + t * 4
            words[t] = ((block[offset].toInt() and 0xff) shl 24) or
                ((block[offset + 1].toInt() and 0xff) shl 16) or
                ((block[offset + 2].toInt() and 0xff) shl 8) or
                (block[offset + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val w15 = words[t - 15]
            val w2 = words[t - 2]
            val sigma0 = w15.rotateRight(7) xor w15.rotateRight(18) xor (w15 ushr 3)
            val sigma1 = w2.rotateRight(17) xor w2.rotateRight(19) xor (w2 ushr 10)
            words[t] = words[t - 16] + sigma0 + words[t - 7] + sigma1
        }
        var a = hash[0]
        var b = hash[1]
        var c = hash[2]
        var d = hash[3]
        var e = hash[4]
        var f = hash[5]
        var g = hash[6]
        var h = hash[7]
        for (t in 0 until 64) {
            val bigSigma1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = h + bigSigma1 + choice + roundConstants[t] + words[t]
            val bigSigma0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = bigSigma0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        hash[0] += a
        hash[1] += b
        hash[2] += c
        hash[3] += d
        hash[4] += e
        hash[5] += f
        hash[6] += g
        hash[7] += h
    }
}
