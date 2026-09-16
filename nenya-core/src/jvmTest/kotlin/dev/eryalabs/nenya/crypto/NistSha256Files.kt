package dev.eryalabs.nenya.crypto

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The NIST CAVP SHA-256 files as the JVM reads them: from disk, by path from the module directory,
 * as `VendoredTestFilesTest` does. A missing file fails with the absolute path it looked at.
 *
 * Used for what a common test cannot do: `SHA256LongMsg.rsp`, which is too large to be worth
 * generating into Kotlin, and the checksums `PROVENANCE.md` records, which are checked against the
 * files on disk rather than against their generated copies. Parsing is [NistSha256Vectors]'s, shared.
 */
internal object NistSha256Files {

    const val DIRECTORY: String = "src/commonTest/resources/vectors/nist-sha256"
    const val PROVENANCE: String = "src/commonTest/resources/vectors/PROVENANCE.md"

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

    /** [name]'s message vectors, read from disk and parsed by [NistSha256Vectors.messages]. */
    fun messages(name: String): List<NistSha256Vectors.MessageVector> =
        NistSha256Vectors.messages(name, file(name).readLines())

    /** The digest PROVENANCE.md records for `nist-sha256/<name>`, in lowercase hex. */
    fun recordedChecksum(name: String): String {
        val pattern = Regex("^([0-9a-f]{64})  nist-sha256/${Regex.escape(name)}$")
        val found = File(PROVENANCE).readLines().mapNotNull { pattern.find(it.trim())?.groupValues?.get(1) }
        assertEquals(1, found.size, "PROVENANCE.md must record exactly one checksum for nist-sha256/$name")
        return found.single()
    }
}
