package dev.eryalabs.nenya.money

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The §4.4 money test that needs the JVM, on top of the common ones in [PortableMsatTest].
 *
 * It proves its operands really overflow 64 bits with `java.math.BigInteger`, which common Kotlin
 * does not have.
 */
class MsatTest : PortableMsatTest() {

    @Test
    fun `multiplication reports overflow rather than returning a wrapped value`() {
        val base = Msat.ofMsat(2_000_000_000_000_000_000L)
        val scalar = 100L

        // Establish that this case genuinely overflows, and that the 64-bit route really
        // does produce a different number — otherwise the assertion below proves nothing.
        val exact = BigInteger.valueOf(base.millisatoshis).multiply(BigInteger.valueOf(scalar))
        assertTrue(exact > BigInteger.valueOf(Long.MAX_VALUE), "the chosen operands must exceed 2^63")
        val wrapped = BigInteger.valueOf(base.millisatoshis * scalar)
        assertNotEquals(exact, wrapped, "64-bit multiplication must be wrong here")

        val refused = assertFailsWith<MoneyException> { base * scalar }
        assertEquals(MoneyRejection.OVERFLOW, refused.reason)

        // The reported result is the failure. There is no accessor that hands back the
        // wrapped product, and the operation yielded no Msat at all.
        assertTrue(refused.message!!.contains("64-bit"), "was: ${refused.message}")
    }
}
