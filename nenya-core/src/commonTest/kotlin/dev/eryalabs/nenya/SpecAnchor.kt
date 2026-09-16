package dev.eryalabs.nenya

import kotlin.test.assertEquals

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
 * ### Scoped to §18's `nip44.vectors.json` row
 *
 * The anchor is applied to the one §18 table row that names `nip44.vectors.json`, not to the
 * whole document. Both matter, and the narrowing is the point: this helper is *for* that
 * vector file, and a document-global "exactly one digest" assertion would turn every test that
 * uses it red the day a revision published a second digest — for `bip340-vectors.csv`, say —
 * for a reason unrelated to any code under test. Within the row the assertion is unchanged and
 * still exact: one row naming the file, one backticked digest on it.
 *
 * Every accessor here fails loudly naming the file it read, so a wrong path cannot make a test
 * pass vacuously.
 */
internal object SpecAnchor {

    /**
     * Paths from the repository root. A common test cannot open a file (on JavaScript there is no
     * working directory that holds it), so both files are compiled into [VendoredTestFiles] by
     * `:nenya-core:generateVendoredTestFiles`, and `VendoredTestFilesTest` proves on the JVM that
     * each constant is its file on disk byte for byte.
     */
    private const val SPEC_PATH: String = "spec/NENYA-1.md"

    private const val NIP44_VECTORS_PATH: String = "nenya-core/src/commonTest/resources/vectors/nip44.vectors.json"

    /** The file name §18's row names, and the name of the file this helper hands back. */
    private const val NIP44_VECTORS_NAME: String = "nip44.vectors.json"

    /** `SHA-256 ` then a backticked lowercase digest — the §18 table cell and nothing else. */
    private val PUBLISHED_DIGEST = Regex("SHA-256 `([0-9a-f]{64})`")

    fun specFile(): VendoredFile = VendoredFile(SPEC_PATH)

    fun nip44VectorsFile(): VendoredFile = VendoredFile(NIP44_VECTORS_PATH)

    /** The SHA-256 §18 publishes for `nip44.vectors.json`, in lowercase hex. */
    fun publishedNip44Digest(): String {
        val spec = specFile()
        val rows = spec.readLines().filter { NIP44_VECTORS_NAME in it && PUBLISHED_DIGEST.containsMatchIn(it) }
        assertEquals(
            1,
            rows.size,
            "§18 must carry exactly one row naming $NIP44_VECTORS_NAME alongside a backticked " +
                "SHA-256 digest; found ${rows.size} in ${spec.location}",
        )
        val matches = PUBLISHED_DIGEST.findAll(rows.single()).map { it.groupValues[1] }.toList()
        assertEquals(
            1,
            matches.size,
            "the §18 row for $NIP44_VECTORS_NAME must publish exactly one digest; found " +
                "${matches.size} in ${spec.location}",
        )
        return matches.single()
    }
}

/**
 * A vendored file as a common test sees it: the text compiled into [VendoredTestFiles], read with
 * the semantics the tests used on `java.io.File`. An unknown [path] fails at construction, naming
 * the files that do exist, so a wrong path can never read as empty text.
 */
internal class VendoredFile(val path: String) {

    val text: String = VendoredTestFiles.text(path)

    /** Where the text came from, for failure messages. */
    val location: String get() = "$path (compiled into VendoredTestFiles from the repository root)"

    /** The file's bytes: its text in UTF-8, which `VendoredTestFilesTest` proves are the file's bytes. */
    fun readBytes(): ByteArray = TestText.utf8(text)

    fun length(): Long = readBytes().size.toLong()

    /** `java.io.File.readLines()` semantics: no empty entry after the final line terminator. */
    fun readLines(): List<String> = TestText.lines(text)
}
