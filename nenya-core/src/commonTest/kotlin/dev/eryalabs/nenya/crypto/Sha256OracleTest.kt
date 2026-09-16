package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.crypto.NistSha256Vectors.hex
import dev.eryalabs.nenya.oracleSha256
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [sha256] and [constantTimeEquals] against an independent oracle, the platform's own SHA-256
 * ([oracleSha256]: the JDK's `java.security.MessageDigest` on the JVM, Node's `crypto.createHash` on
 * JavaScript), over deterministic seeded input.
 *
 * Every length from 0 to 130 bytes is covered, because that range holds each padding boundary
 * where hand-written SHA-256 breaks: 55/56 (the length field stops fitting in the final block)
 * and 63/64/65 (a block boundary), and again one block later at 119/120 and 127/128/129.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as `Sha256OracleTest`
 * on each target, so every test keeps its `Sha256OracleTest` name. On the JVM the oracle is still
 * `MessageDigest` itself, and the seeded input is `java.util.Random`'s sequence ([JdkRandom]), so
 * the JVM run compares exactly what it compared before. The `constantTimeEquals` test's oracle is
 * `MessageDigest.isEqual`, which only the JVM has; it is in the JVM `Sha256OracleTest`
 * (`src/jvmTest`).
 */
abstract class PortableSha256OracleTest {

    protected companion object {
        /** Change it and re-run: the properties must hold for any seed. */
        const val SEED: Long = 0x5eed_5a256L

        private fun oracle(bytes: ByteArray): ByteArray = oracleSha256(bytes)

        private fun randomBytes(random: JdkRandom, size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    }

    @JsName("agrees_with_messagedigest_for_every_length_from_0_to_130_bytes")
    @Test
    fun `agrees with MessageDigest for every length from 0 to 130 bytes`() {
        val random = JdkRandom(SEED)
        val lengths = (0..130).toList()
        var compared = 0
        for (length in lengths) {
            // Three inputs per length: random bytes, all zero bits and all one bits.
            val inputs = listOf(randomBytes(random, length), ByteArray(length), ByteArray(length) { -1 })
            for (input in inputs) {
                val before = input.copyOf()
                assertEquals(hex(oracle(input)), hex(sha256(input)), "length $length")
                assertTrue(input.contentEquals(before), "sha256 modified its input at length $length")
                compared++
            }
        }
        assertEquals(131 * 3, compared, "inputs compared")
        assertTrue(listOf(55, 56, 63, 64, 65).all { it in lengths })
    }

    @JsName("agrees_with_messagedigest_on_multi_block_inputs")
    @Test
    fun `agrees with MessageDigest on multi-block inputs`() {
        val random = JdkRandom(SEED xor 0x6d62L)
        val lengths = listOf(191, 192, 193, 1000, 4096 + 7, 65_536 + 55, 1_048_576 + 64)
        for (length in lengths) {
            val input = randomBytes(random, length)
            assertEquals(hex(oracle(input)), hex(sha256(input)), "length $length")
        }
        repeat(50) { round ->
            val input = randomBytes(random, random.nextInt(20_000))
            assertEquals(hex(oracle(input)), hex(sha256(input)), "random round $round, length ${input.size}")
        }
    }
}
