package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.crypto.NistSha256Vectors.hex
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SHA-256 oracle test that needs the JVM, on top of the common ones in
 * [PortableSha256OracleTest]: [constantTimeEquals] against `MessageDigest.isEqual`, which common
 * Kotlin does not have.
 */
class Sha256OracleTest : PortableSha256OracleTest() {

    private companion object {
        fun randomBytes(random: Random, size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
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
