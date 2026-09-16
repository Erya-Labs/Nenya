package dev.eryalabs.nenya.payment

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.oracleSha256

/**
 * The generator behind every preimage fixture in this package.
 *
 * This is not a scratch file: the queue's Definition of done forbids a hash, a preimage or
 * any other encoded value appearing in a test as something somebody typed out. Every fixture
 * here is therefore *computed* — the preimage bytes by `java.util.Random`'s algorithm
 * ([JdkRandom]) pinned to [SEED], and the payment hash by the platform's SHA-256 ([oracleSha256],
 * `MessageDigest` on the JVM). A reviewer can change [SEED], re-run
 * the suite, and every property must still hold.
 *
 * The one fixture that is *not* generated here is the externally-authored anchor: the SHA-256
 * of the vendored `vectors/nip44.vectors.json`, which §18 of `spec/NENYA-1.md` publishes and
 * which `PaymentEvidenceTest` parses out of the specification at test time rather than
 * transcribing. See [SpecAnchor].
 */
internal object PaymentFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm rather than
     * `kotlin.random` is deliberate: it is specified by the JDK, so this file produces the same
     * 32-byte runs on any JVM a reviewer re-runs it on — and, as [JdkRandom], on JavaScript.
     */
    const val SEED: Long = 20260910L

    /** A run of distinct preimages, in lowercase hex — the form §9.2 check 2 requires. */
    fun preimageHex(count: Int): List<String> {
        val random = JdkRandom(SEED)
        val bytes = ByteArray(Preimage.BYTE_LENGTH)
        return List(count) {
            random.nextBytes(bytes)
            lowerHex(bytes)
        }
    }

    /** The same run, read through [Preimage.ofHex] so the reader is on every fixture's path. */
    fun preimages(count: Int): List<Preimage> = preimageHex(count).map(Preimage::ofHex)

    /**
     * The payment hash a real invoice would commit to for [preimage]: `SHA-256(preimage)`,
     * computed here rather than typed. Deliberately built through [PaymentHash.ofHex] from
     * hex, so the fixture path and the production path share no code beyond the platform SHA-256.
     */
    fun paymentHashOf(preimage: Preimage): PaymentHash =
        PaymentHash.ofHex(lowerHex(sha256(preimage.bytes())))

    fun sha256(bytes: ByteArray): ByteArray = oracleSha256(bytes)

    /** §4.3's canonical form: lowercase, unpadded. */
    fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
