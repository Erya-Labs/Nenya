package dev.eryalabs.nenya.crypto

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reader for the NIST CAVP SHA-256 byte-oriented response files vendored under
 * `src/jvmTest/resources/vectors/nist-sha256/` (see `vectors/PROVENANCE.md`).
 *
 * Deliberately shares no code with the implementation: hex is decoded here by hand, and
 * nothing in this file hashes anything. Files are opened by path from the module directory,
 * as `SpecAnchor` does, and a missing file fails with the absolute path it looked at.
 */
internal object NistSha256Vectors {

    const val DIRECTORY: String = "src/jvmTest/resources/vectors/nist-sha256"
    const val PROVENANCE: String = "src/jvmTest/resources/vectors/PROVENANCE.md"

    /** One `Len` / `Msg` / `MD` triple. [bits] is NIST's `Len`, always a whole number of bytes here. */
    class MessageVector(val bits: Int, val message: ByteArray, val digest: ByteArray)

    /** The Monte Carlo file: its seed and its checkpoints in `COUNT` order. */
    class MonteVectors(val seed: ByteArray, val checkpoints: List<ByteArray>)

    fun file(name: String): File {
        val file = File("$DIRECTORY/$name")
        if (!file.isFile) {
            fail(
                "NIST vector file $name was expected at ${file.absolutePath} but is not there; the " +
                    "working directory is ${File(".").absoluteFile.normalize()}",
            )
        }
        return file
    }

    /** `name = value` pairs in file order, comments, headers and blank lines dropped. */
    private fun fields(name: String): List<Pair<String, String>> =
        file(name).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") }
            .map { line ->
                val eq = line.indexOf('=')
                if (eq < 0) fail("$name: unparseable line '$line'")
                line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }

    fun messages(name: String): List<MessageVector> {
        val out = ArrayList<MessageVector>()
        val all = fields(name)
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

    fun monte(name: String): MonteVectors {
        val all = fields(name)
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

    /** The digest PROVENANCE.md records for `nist-sha256/<name>`, in lowercase hex. */
    fun recordedChecksum(name: String): String {
        val pattern = Regex("^([0-9a-f]{64})  nist-sha256/${Regex.escape(name)}$")
        val found = File(PROVENANCE).readLines().mapNotNull { pattern.find(it.trim())?.groupValues?.get(1) }
        assertEquals(1, found.size, "PROVENANCE.md must record exactly one checksum for nist-sha256/$name")
        return found.single()
    }

    fun unhex(hex: String): ByteArray {
        assertTrue(hex.length % 2 == 0, "odd-length hex")
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            assertTrue(hi >= 0 && lo >= 0, "not hex: $hex")
            ((hi shl 4) or lo).toByte()
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
