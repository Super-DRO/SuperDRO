package org.superdro.core.store.files

import org.superdro.core.util.Bytes

/**
 * SHA-256 (FIPS 180-4) in common code. The probe profile import uses it to check the digest of a
 * calibration session. Kotlin common code has no digest
 * function.
 */
object Sha256 {

    private val K: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L,
        0x923f82a4L, 0xab1c5ed5L, 0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L,
        0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L, 0xe49b69c1L, 0xefbe4786L,
        0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L,
        0x06ca6351L, 0x14292967L, 0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L,
        0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L, 0xa2bfe8a1L, 0xa81a664bL,
        0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL,
        0x5b9cca4fL, 0x682e6ff3L, 0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L,
        0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).map(Long::toInt).toIntArray()

    /** Gives the 32 bytes of the digest of [input]. */
    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (index in 0 until 8) {
            padded[padded.size - 1 - index] = (bitLength ushr (8 * index)).toByte()
        }
        val h = longArrayOf(
            0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
            0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
        ).map(Long::toInt).toIntArray()
        val w = IntArray(64)
        for (block in 0 until padded.size / 64) {
            for (t in 0 until 16) {
                val offset = block * 64 + t * 4
                w[t] = ((padded[offset].toInt() and 0xFF) shl 24) or ((padded[offset + 1].toInt() and 0xFF) shl 16) or
                    ((padded[offset + 2].toInt() and 0xFF) shl 8) or (padded[offset + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choice = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + choice + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + majority
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        return ByteArray(32) { index -> (h[index / 4] ushr (24 - 8 * (index % 4))).toByte() }
    }

    /** Gives the digest of the UTF-8 bytes of [text] as 64 lowercase hexadecimal digits. */
    fun hex(text: String): String = Bytes.toHex(digest(text.encodeToByteArray()))
}
