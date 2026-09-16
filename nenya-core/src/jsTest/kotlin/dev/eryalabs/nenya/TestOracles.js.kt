package dev.eryalabs.nenya

import dev.eryalabs.nenya.nodecrypto.createHash

/**
 * Node's own SHA-256. Node's `Hash.update` accepts any typed array, and a Kotlin/JS `ByteArray`
 * is an `Int8Array` at run time, so the bytes go in unconverted; the digest comes back as a
 * `Buffer` and is copied out byte by byte.
 */
internal actual fun oracleSha256(bytes: ByteArray): ByteArray {
    val digest: dynamic = createHash("sha256").update(bytes).digest()
    val size = digest.length as Int
    return ByteArray(size) { index -> (digest[index] as Int).toByte() }
}
