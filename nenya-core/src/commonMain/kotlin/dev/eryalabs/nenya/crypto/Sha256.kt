package dev.eryalabs.nenya.crypto

/*
 * SHA-256 exactly as FIPS 180-4 specifies it (Secure Hash Standard, August 2015,
 * https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf): §4.2.2 constants, §5.1.1
 * padding, §5.3.3 initial hash value, §6.2.2 computation.
 *
 * Plain common Kotlin so that JVM and JavaScript hash identically and synchronously: no
 * java.*, no Web Crypto (asynchronous), no dependency. Every 32-bit word is an Int; addition
 * wraps modulo 2^32 on both targets, and every right shift is an explicit `ushr`.
 *
 * Proven against the NIST CAVP byte-oriented vectors (ShortMsg, LongMsg, Monte Carlo) and
 * against java.security.MessageDigest in jvmTest.
 */

/** §4.2.2: the first 32 bits of the fractional parts of the cube roots of the first 64 primes. */
private val K: IntArray = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

/** §5.3.3: the first 32 bits of the fractional parts of the square roots of the first 8 primes. */
private val H0: IntArray = intArrayOf(
    0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
    0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
)

/** The SHA-256 digest (32 bytes) of [message]. The input is never modified. */
internal fun sha256(message: ByteArray): ByteArray {
    val h = H0.copyOf()
    val w = IntArray(64)

    // Every complete 64-byte block is hashed straight out of the input, without a copy.
    val fullBlocks = message.size / 64
    for (block in 0 until fullBlocks) compress(h, w, message, block * 64)

    // §5.1.1: the remaining bytes, then 0x80, then zeros, then the length in bits as a
    // big-endian 64-bit integer. That fits in one final block, or needs two when fewer than
    // 9 bytes of the last block are free (remainder >= 56).
    val remainder = message.size - fullBlocks * 64
    val tail = ByteArray(if (remainder < 56) 64 else 128)
    message.copyInto(tail, 0, fullBlocks * 64, message.size)
    tail[remainder] = 0x80.toByte()
    // Bit length = size * 8, split into 32-bit halves without Long arithmetic. size is a
    // non-negative Int, so the high half is size ushr 29 and the low half wraps as size shl 3.
    writeWord(tail, tail.size - 8, message.size ushr 29)
    writeWord(tail, tail.size - 4, message.size shl 3)
    for (offset in 0 until tail.size step 64) compress(h, w, tail, offset)

    val digest = ByteArray(32)
    for (i in 0 until 8) writeWord(digest, i * 4, h[i])
    return digest
}

/** §6.2.2 steps 1-4 for the block at [offset] of [bytes], updating [h] in place. */
private fun compress(h: IntArray, w: IntArray, bytes: ByteArray, offset: Int) {
    for (t in 0 until 16) {
        val j = offset + t * 4
        w[t] = ((bytes[j].toInt() and 0xff) shl 24) or ((bytes[j + 1].toInt() and 0xff) shl 16) or
            ((bytes[j + 2].toInt() and 0xff) shl 8) or (bytes[j + 3].toInt() and 0xff)
    }
    for (t in 16 until 64) {
        val x = w[t - 2]
        val y = w[t - 15]
        val sigma1 = rotr(x, 17) xor rotr(x, 19) xor (x ushr 10)
        val sigma0 = rotr(y, 7) xor rotr(y, 18) xor (y ushr 3)
        w[t] = sigma1 + w[t - 7] + sigma0 + w[t - 16]
    }
    var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
    var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
    for (t in 0 until 64) {
        val ch = (e and f) xor (e.inv() and g)
        val maj = (a and b) xor (a and c) xor (b and c)
        val t1 = hh + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + ch + K[t] + w[t]
        val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + maj
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
}

/** §3.2 ROTR^n(x) on a 32-bit word, with an explicit unsigned right shift. */
private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

private fun writeWord(out: ByteArray, offset: Int, word: Int) {
    out[offset] = (word ushr 24).toByte()
    out[offset + 1] = (word ushr 16).toByte()
    out[offset + 2] = (word ushr 8).toByte()
    out[offset + 3] = word.toByte()
}

/**
 * The replacement for `java.security.MessageDigest.isEqual`: false when the lengths differ,
 * otherwise every byte is compared with no early exit, so the time taken does not reveal
 * where two equal-length arrays first differ.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
