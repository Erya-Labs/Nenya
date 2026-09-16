package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.VendoredFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reader for the NIST CAVP SHA-256 byte-oriented response files vendored under
 * `src/commonTest/resources/vectors/nist-sha256/` (see `vectors/PROVENANCE.md`).
 *
 * Deliberately shares no code with the implementation: hex is decoded here by hand, and
 * nothing in this file hashes anything.
 *
 * A common test cannot open a file, so `SHA256ShortMsg.rsp` and `SHA256Monte.rsp` are read from
 * their generated copies in `VendoredTestFiles` ([lines]), which `VendoredTestFilesTest` proves are
 * the files on disk byte for byte. `SHA256LongMsg.rsp` (426 KB) is not generated: the JVM reads it
 * from disk (`NistSha256Files`) and hands its lines to the same parser, so every file goes through
 * one parser whichever way it was read.
 */
internal object NistSha256Vectors {

    /** The vendored directory, as a path from the repository root (the `VendoredTestFiles` key). */
    const val DIRECTORY: String = "nenya-core/src/commonTest/resources/vectors/nist-sha256"

    /** One `Len` / `Msg` / `MD` triple. [bits] is NIST's `Len`, always a whole number of bytes here. */
    class MessageVector(val bits: Int, val message: ByteArray, val digest: ByteArray)

    /** The Monte Carlo file: its seed and its checkpoints in `COUNT` order. */
    class MonteVectors(val seed: ByteArray, val checkpoints: List<ByteArray>)

    /**
     * The lines of the generated copy of [name]. Fails loudly, naming the generated files, for a
     * file that is not generated, `SHA256LongMsg.rsp` among them.
     */
    fun lines(name: String): List<String> = VendoredFile("$DIRECTORY/$name").readLines()

    /** `name = value` pairs in file order, comments, headers and blank lines dropped. */
    private fun fields(name: String, lines: List<String>): List<Pair<String, String>> =
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") }
            .map { line ->
                val eq = line.indexOf('=')
                if (eq < 0) fail("$name: unparseable line '$line'")
                line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }

    fun messages(name: String, lines: List<String> = lines(name)): List<MessageVector> {
        val out = ArrayList<MessageVector>()
        val all = fields(name, lines)
        var i = 0
        while (i < all.size) {
            val (lenKey, lenValue) = all[i]
            val (msgKey, msgValue) = all.getOrElse(i + 1) { fail("$name: truncated after $lenKey") }
            val (mdKey, mdValue) = all.getOrElse(i + 2) { fail("$name: truncated after $msgKey") }
            assertEquals(listOf("Len", "Msg", "MD"), listOf(lenKey, msgKey, mdKey), "$name: field order at entry ${out.size}")
            val bits = lenValue.toInt()
            assertEquals(0, bits % 8, "$name: byte-oriented file carries a bit length $bits")
            // NIST writes the empty message as "Msg = 00"; Len is what counts.
            val message = unhex(msgValue).copyOf(bits / 8)
            if (bits > 0) assertEquals(bits / 4, msgValue.length, "$name: Msg length disagrees with Len = $bits")
            out += MessageVector(bits, message, unhex(mdValue).also { assertEquals(32, it.size, "$name: MD size") })
            i += 3
        }
        return out
    }

    fun monte(name: String, lines: List<String> = lines(name)): MonteVectors {
        val all = fields(name, lines)
        val (seedKey, seedValue) = all.first()
        assertEquals("Seed", seedKey, "$name: first field")
        val rest = all.drop(1)
        assertEquals(0, rest.size % 2, "$name: COUNT/MD pairs")
        val checkpoints = rest.chunked(2).mapIndexed { index, pair ->
            assertEquals("COUNT" to index.toString(), pair[0], "$name: checkpoint order")
            assertEquals("MD", pair[1].first, "$name: checkpoint field")
            unhex(pair[1].second)
        }
        return MonteVectors(unhex(seedValue), checkpoints)
    }

    fun unhex(hex: String): ByteArray {
        assertTrue(hex.length % 2 == 0, "odd-length hex")
        return ByteArray(hex.length / 2) { i ->
            val hi = hex[2 * i].digitToIntOrNull(16) ?: -1
            val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: -1
            assertTrue(hi >= 0 && lo >= 0, "not hex: $hex")
            ((hi shl 4) or lo).toByte()
        }
    }

    fun hex(bytes: ByteArray): String = TestText.lowerHex(bytes)
}
