package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.crypto.NistSha256Vectors.hex
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [sha256] and [constantTimeEquals] against an independent oracle, the JDK's own
 * `java.security.MessageDigest`, over deterministic seeded input.
 *
 * Every length from 0 to 130 bytes is covered, because that range holds each padding boundary
 * where hand-written SHA-256 breaks: 55/56 (the length field stops fitting in the final block)
 * and 63/64/65 (a block boundary), and again one block later at 119/120 and 127/128/129.
 */
class Sha256OracleTest {

    private companion object {
        /** Change it and re-run: the properties must hold for any seed. */
        const val SEED: Long = 0x5eed_5a256L

        fun oracle(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun randomBytes(random: Random, size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    }

    @Test
    fun `agrees with MessageDigest for every length from 0 to 130 bytes`() {
        val random = Random(SEED)
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

    @Test
    fun `agrees with MessageDigest on multi-block inputs`() {
        val random = Random(SEED xor 0x6d62L)
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

    @Test
    fun `constantTimeEquals agrees with MessageDigest isEqual`() {
        val random = Random(SEED xor 0xe9L)
        val cases = ArrayList<Pair<ByteArray, ByteArray>>()
        cases += ByteArray(0) to ByteArray(0)
        cases += ByteArray(0) to ByteArray(1)
        repeat(200) {
            val a = randomBytes(random, random.nextInt(40))
            val b = when (random.nextInt(4)) {
                0 -> a.copyOf()
                1 -> a.copyOf().also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = (it[0] + 1).toByte() }
                2 -> a.copyOf(a.size + 1)
                else -> randomBytes(random, a.size)
            }
            cases += a to b
        }
        var equal = 0
        for ((a, b) in cases) {
            val expected = MessageDigest.isEqual(a, b)
            assertEquals(expected, constantTimeEquals(a, b), "${hex(a)} vs ${hex(b)}")
            assertEquals(expected, constantTimeEquals(b, a), "${hex(b)} vs ${hex(a)}")
            if (expected) equal++
        }
        assertTrue(equal in 1 until cases.size, "the sample must contain both equal and unequal pairs")
    }
}
