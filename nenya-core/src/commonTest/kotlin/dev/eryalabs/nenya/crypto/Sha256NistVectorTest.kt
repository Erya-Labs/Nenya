package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.crypto.NistSha256Vectors.hex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [sha256] against vectors this repository did not author: NIST's CAVP SHA-256 byte-oriented
 * response files, and the worked examples published with FIPS 180-4.
 *
 * Each test asserts how many vectors it parsed before it asserts anything else, so a parser
 * that silently read nothing — or half a file — cannot pass. Every mismatch is collected and
 * reported together, rather than stopping at the first.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as
 * `Sha256NistVectorTest` on each target, so every test keeps its `Sha256NistVectorTest` name, and the
 * library's own SHA-256 is held to NIST's vectors on JavaScript as well as on the JVM. Two tests are
 * in the JVM `Sha256NistVectorTest` (`src/jvmTest`): `SHA256LongMsg.rsp` is read from disk rather than
 * generated into Kotlin, and the `PROVENANCE.md` checksums are taken over the files on disk with
 * `MessageDigest`.
 */
abstract class PortableSha256NistVectorTest {

    protected companion object {
        const val SHORT_MSG = "SHA256ShortMsg.rsp"
        const val LONG_MSG = "SHA256LongMsg.rsp"
        const val MONTE = "SHA256Monte.rsp"

        const val SHORT_MSG_COUNT = 65
        const val LONG_MSG_COUNT = 64
        const val MONTE_CHECKPOINTS = 100

        /** [vectors] defaults to the generated copy of [name]; the JVM passes the ones it read from disk. */
        internal fun assertAllMatch(
            name: String,
            expectedCount: Int,
            vectors: List<NistSha256Vectors.MessageVector> = NistSha256Vectors.messages(name),
        ) {
            assertEquals(expectedCount, vectors.size, "$name: parsed vector count")
            val failures = vectors.filter { !sha256(it.message).contentEquals(it.digest) }
                .map { "Len = ${it.bits}: expected ${hex(it.digest)}, got ${hex(sha256(it.message))}" }
            assertEquals(emptyList(), failures, "$name: ${failures.size} of ${vectors.size} vectors failed")
        }
    }

    @JsName("every_sha256shortmsg_vector_hashes_to_nist_s_digest")
    @Test
    fun `every SHA256ShortMsg vector hashes to NIST's digest`() {
        assertAllMatch(SHORT_MSG, SHORT_MSG_COUNT)
        // ShortMsg is every byte length from 0 to 64 once, which is what makes it cover padding.
        assertEquals((0..64).map { it * 8 }, NistSha256Vectors.messages(SHORT_MSG).map { it.bits })
    }

    /**
     * The Monte Carlo test exactly as the CAVP SHAVS document specifies it:
     *
     * ```
     * Seed = random n bits
     * for (j = 0; j < 100; j++) {
     *     MD0 = MD1 = MD2 = Seed
     *     for (i = 3; i < 1003; i++) {
     *         Mi = MD(i-3) || MD(i-2) || MD(i-1)
     *         MDi = SHA(Mi)
     *     }
     *     MDj = Seed = MD1002
     *     OUTPUT: MDj
     * }
     * ```
     */
    @JsName("the_sha256monte_monte_carlo_test_reproduces_all_100_nist_checkpoints")
    @Test
    fun `the SHA256Monte Monte Carlo test reproduces all 100 NIST checkpoints`() {
        val monte = NistSha256Vectors.monte(MONTE)
        assertEquals(MONTE_CHECKPOINTS, monte.checkpoints.size, "$MONTE: parsed checkpoint count")
        assertEquals(32, monte.seed.size, "$MONTE: seed size")

        var seed = monte.seed
        var inner = 0
        val failures = ArrayList<String>()
        for (j in 0 until MONTE_CHECKPOINTS) {
            var md0 = seed
            var md1 = seed
            var md2 = seed
            for (i in 3 until 1003) {
                val mdi = sha256(md0 + md1 + md2)
                md0 = md1
                md1 = md2
                md2 = mdi
                inner++
            }
            seed = md2
            if (!seed.contentEquals(monte.checkpoints[j])) {
                failures += "COUNT = $j: expected ${hex(monte.checkpoints[j])}, got ${hex(seed)}"
            }
        }
        assertEquals(100_000, inner, "inner iterations performed")
        assertEquals(emptyList(), failures, "$MONTE: ${failures.size} of $MONTE_CHECKPOINTS checkpoints failed")
    }

    /**
     * The one-block, two-block and long-message examples published with FIPS 180-4 (NIST
     * "Examples with Intermediate Values", SHA256.pdf), kept as readable constants.
     */
    @JsName("fips_180_4_worked_examples_hash_to_their_published_digests")
    @Test
    fun `FIPS 180-4 worked examples hash to their published digests`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(sha256("abc".encodeToByteArray())),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
        )
    }
}
