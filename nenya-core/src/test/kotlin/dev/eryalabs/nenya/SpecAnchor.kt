package dev.eryalabs.nenya

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The one fixture in this repository that nobody here authored.
 *
 * §18 of `spec/NENYA-1.md` publishes the SHA-256 of the vendored NIP-44 vector file. That
 * digest is an externally-checkable anchor for every SHA-256 assertion in the suite: a
 * reviewer can re-derive it with `sha256sum` and compare it against a published RFC, so a
 * SHA-256 path pinned to it is pinned to something outside this codebase's own opinion.
 *
 * The digest is **parsed out of the specification at test time** rather than transcribed.
 * Transcribing it would prove only that the test file agrees with the test file. Parsing it
 * means a specification revision that changed the vendored vectors — or a vendored file
 * somebody swapped — turns the suite red.
 *
 * The anchor is the literal `SHA-256 ` prefix, and the digest sits inside backticks. Both
 * matter: a bare 64-hex regex over the whole document matches four copies of Appendix A's
 * worked order id first, and an anchor that simply took the 64 characters following
 * `SHA-256 ` would capture a backtick and 63 hex digits.
 *
 * Every accessor here fails loudly with the absolute path it looked at, so a wrong working
 * directory cannot make a test pass vacuously.
 */
internal object SpecAnchor {

    /**
     * Gradle runs the tests with the module directory as the working directory, so the
     * specification is one level up. Confirmed rather than assumed: [specFile] reports the
     * absolute path it tried when it cannot find it.
     */
    private const val SPEC_PATH: String = "../spec/NENYA-1.md"

    private const val NIP44_VECTORS_PATH: String = "src/test/resources/vectors/nip44.vectors.json"

    /** `SHA-256 ` then a backticked lowercase digest — the §18 table row and nothing else. */
    private val PUBLISHED_DIGEST = Regex("SHA-256 `([0-9a-f]{64})`")

    fun specFile(): File = required(SPEC_PATH, "the NENYA-1 specification")

    fun nip44VectorsFile(): File = required(NIP44_VECTORS_PATH, "the vendored NIP-44 test vectors")

    /** The SHA-256 §18 publishes for `nip44.vectors.json`, in lowercase hex. */
    fun publishedNip44Digest(): String {
        val matches = PUBLISHED_DIGEST.findAll(specFile().readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            1,
            matches.size,
            "§18 must publish exactly one backticked SHA-256 digest for the NIP-44 vectors; " +
                "found ${matches.size} in ${specFile().absolutePath}",
        )
        return matches.single()
    }

    private fun required(path: String, what: String): File {
        val file = File(path)
        if (!file.isFile) {
            fail(
                "$what was expected at ${file.absolutePath} but is not there. The tests run with " +
                    "the module directory as the working directory; this one is " +
                    "${File(".").absoluteFile.normalize()}.",
            )
        }
        return file
    }
}
