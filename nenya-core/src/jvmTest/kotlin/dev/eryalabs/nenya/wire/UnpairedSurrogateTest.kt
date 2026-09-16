package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.text.isWellFormedUtf16
import dev.eryalabs.nenya.text.strictUtf8OrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unpaired-surrogate test that needs the JVM, on top of the common ones in
 * [PortableUnpairedSurrogateTest].
 *
 * It holds the common encoder against the JVM encoder it replaced, `toByteArray(Charsets.UTF_8)`,
 * including that encoder's silent `?` substitution, which is the divergence the rule exists to close.
 */
class UnpairedSurrogateTest : PortableUnpairedSurrogateTest() {

    /**
     * The common encoder the wire package routes through, against the JVM encoder it replaced.
     * The three ill-formed strings are exactly the ones the JVM would have hashed with a `?` in
     * place — the divergence from JavaScript this rule exists to close.
     */
    @Test
    fun `the common encoder refuses exactly what the JVM encoder silently substituted`() {
        for (text in listOf("a${HIGH}b", "a${LOW}b", "a$LOW${HIGH}b", "ab$HIGH")) {
            assertFalse(isWellFormedUtf16(text), "ill-formed: ${text.map { it.code.toString(16) }}")
            assertNull(strictUtf8OrNull(text), "never encoded: ${text.map { it.code.toString(16) }}")
            assertTrue(
                text.toByteArray(Charsets.UTF_8).contains('?'.code.toByte()),
                "the JVM's lenient encoder substitutes `?`, which is why the check is needed",
            )
        }
        for (text in listOf("", "plain", "a${PALETTE}b", "é中$PALETTE$PALETTE")) {
            assertTrue(isWellFormedUtf16(text))
            assertContentEquals(text.toByteArray(Charsets.UTF_8), assertNotNull(strictUtf8OrNull(text)))
        }
        assertContentEquals(PALETTE_UTF8, strictUtf8OrNull(PALETTE))
    }
}
