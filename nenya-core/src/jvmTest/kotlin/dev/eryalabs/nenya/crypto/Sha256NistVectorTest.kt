package dev.eryalabs.nenya.crypto

import dev.eryalabs.nenya.crypto.NistSha256Vectors.hex
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The NIST SHA-256 tests that need the JVM, on top of the common ones in
 * [PortableSha256NistVectorTest].
 *
 * Both read the vendored files from disk: `SHA256LongMsg.rsp` is not generated into Kotlin, and the
 * checksums `PROVENANCE.md` records are taken over the files on disk with `MessageDigest`.
 */
class Sha256NistVectorTest : PortableSha256NistVectorTest() {

    @Test
    fun `every SHA256LongMsg vector hashes to NIST's digest`() {
        assertAllMatch(LONG_MSG, LONG_MSG_COUNT, NistSha256Files.messages(LONG_MSG))
    }

    /**
     * The vendored files are the ones PROVENANCE.md describes. Checked with MessageDigest, not
     * with the implementation under test, so a substituted file is caught independently of it.
     */
    @Test
    fun `the vendored NIST files match the checksums PROVENANCE records`() {
        for (name in listOf(SHORT_MSG, LONG_MSG, MONTE)) {
            val actual = hex(MessageDigest.getInstance("SHA-256").digest(NistSha256Files.file(name).readBytes()))
            assertEquals(NistSha256Files.recordedChecksum(name), actual, "checksum of $name")
        }
    }
}
