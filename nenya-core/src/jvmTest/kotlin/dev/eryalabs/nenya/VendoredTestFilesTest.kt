package dev.eryalabs.nenya

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The generated [VendoredTestFiles] is a faithful copy of the files it was generated from.
 *
 * Common tests read the specification and the vendored vectors through string constants compiled
 * in by `:nenya-core:generateVendoredTestFiles`, because a common test cannot open a file. That
 * substitution is only safe if the constants can never drift from the files, so this JVM test —
 * which *can* open files — reads each original from disk and compares it byte for byte. The
 * generator runs before every test compilation, so a drift would need a broken generator, and a
 * broken generator is exactly what this catches.
 */
class VendoredTestFilesTest {

    /**
     * The exact list the build generates. Kept here too, deliberately: a file dropped from the
     * build's list would otherwise vanish from the generated object without any test noticing.
     */
    private val expected = listOf(
        "spec/NENYA-1.md",
        "nenya-core/src/commonTest/resources/spec/nenya-1-11.2-transitions.psv",
        "nenya-core/src/commonTest/resources/vectors/PROVENANCE.md",
        "nenya-core/src/commonTest/resources/vectors/bip340-vectors.csv",
        "nenya-core/src/commonTest/resources/vectors/nip44.vectors.json",
        "nenya-core/src/commonTest/resources/vectors/nist-sha256/SHA256ShortMsg.rsp",
        "nenya-core/src/commonTest/resources/vectors/nist-sha256/SHA256Monte.rsp",
        "nenya-core/src/commonTest/resources/vectors/bolt11/11-payment-encoding.md",
    )

    /** Gradle runs JVM tests with the module directory as the working directory. */
    private fun onDisk(path: String): File {
        val file = File("..", path)
        assertTrue(
            file.isFile,
            "$path was expected at ${file.absoluteFile.normalize()}; the working directory is " +
                "${File(".").absoluteFile.normalize()}",
        )
        return file
    }

    @Test
    fun `the generated file set is exactly the vendored list`() {
        assertEquals(expected, VendoredTestFiles.paths)
    }

    @Test
    fun `every generated constant equals its file on disk byte for byte`() {
        for (path in VendoredTestFiles.paths) {
            val raw = onDisk(path).readBytes()
            val generated = VendoredTestFiles.text(path)
            assertTrue(raw.isNotEmpty(), "$path is empty, which proves nothing")
            assertContentEquals(raw, generated.toByteArray(Charsets.UTF_8), "$path drifted from its generated constant")
            assertEquals(String(raw, Charsets.UTF_8), generated, "$path drifted from its generated constant")
        }
    }

    @Test
    fun `the specification constant is the whole specification and not a prefix`() {
        val spec = VendoredTestFiles.text("spec/NENYA-1.md")
        assertEquals(onDisk("spec/NENYA-1.md").length(), spec.toByteArray(Charsets.UTF_8).size.toLong())
        assertEquals(onDisk("spec/NENYA-1.md").readLines().last(), TestText.lines(spec).last())
    }

    @Test
    fun `an unlisted path is refused rather than answered with empty text`() {
        assertFailsWith<IllegalArgumentException> {
            VendoredTestFiles.text("nenya-core/src/commonTest/resources/vectors/nist-sha256/SHA256LongMsg.rsp")
        }
    }
}
