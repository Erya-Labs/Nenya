package dev.eryalabs.nenya

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every `§` citation in the source tree and the documents beside the specification names something
 * the current revision of NENYA-1 actually has. Revision `1.12` condensed the specification by 45%;
 * this test is what found the two `§12.10` citations of a section that has only numbered items.
 *
 * ### What resolves
 *
 * - `§n` or `§n.m` is a heading of `spec/NENYA-1.md`.
 * - `§n item k`, and `§n items k, k and k`, is that, and every `k` is a number in the section's own
 *   numbered list — counted from the specification when the test runs, so a list that shrinks turns
 *   a citation of its last item red.
 * - `OPEN-n` is one of §16's `OPEN-n` headings.
 * - `§n of the companion`, or `§n of [the companion](…)`, with any whitespace between the words,
 *   is a heading of `spec/NENYA-1-companion.md`, never of NENYA-1.
 *
 * A `§` with no number after it is not a citation: the code that reads citations holds `§` in its
 * patterns, and its KDoc writes `§x.y` as a placeholder.
 *
 * A citation of another document's section — `RFC 8259 §7`, `FIPS 180-4 §6.2`, a `vol. n,` or a
 * `NIP-nn` before the `§`, and every citation in `crypto/Sha256.kt`, which cites only the hash's
 * standard — is not checked against NENYA-1. Each one is collected instead and held by exact set
 * equality to [OTHER_DOCUMENTS], so a new one is a deliberate pin change rather than a citation that
 * escapes checking because of a word in front of it.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest`. This one cannot be there: it reads the source tree,
 * the companion, the overview, the integration guide and the README from disk, which a common test
 * cannot do. The specification itself is read through [VendoredTestFiles], as every other test reads
 * it. The parser takes its text as a parameter, so every control below runs on text built in memory.
 */
class SpecCitationTest {

    internal companion object {

        /** Gradle runs JVM tests with the module directory as the working directory. */
        const val SOURCE_ROOT: String = "src"

        const val COMPANION_PATH: String = "../spec/NENYA-1-companion.md"

        /** The documents scanned besides the source tree and the specification, from the repository root. */
        val DOCUMENTS: List<String> = listOf("spec/NENYA-1-overview.md", "docs/INTEGRATING.md", "README.md")

        const val SPEC_PATH: String = "spec/NENYA-1.md"

        /** This file: its controls cite what must not resolve, on purpose. */
        const val SELF: String = "SpecCitationTest.kt"

        /** The one file whose every citation is of another document: SHA-256's standard. */
        const val SHA256_PATH: String = "nenya-core/src/commonMain/kotlin/dev/eryalabs/nenya/crypto/Sha256.kt"

        /**
         * Every citation of another document's section, by file, held by exact set equality. Growing
         * it is a declared pin change.
         */
        val OTHER_DOCUMENTS: Set<Pair<String, String>> = setOf(
            "nenya-core/src/commonMain/kotlin/dev/eryalabs/nenya/channel/DeliveryMessage.kt" to "RFC 4648 §4",
            SHA256_PATH to "§3.2",
            SHA256_PATH to "§4.2.2",
            SHA256_PATH to "§5.1.1",
            SHA256_PATH to "§5.3.3",
            SHA256_PATH to "§6.2.2",
            "nenya-core/src/commonMain/kotlin/dev/eryalabs/nenya/tag/TagValues.kt" to "RFC 3986 §3.1",
            "nenya-core/src/commonTest/kotlin/dev/eryalabs/nenya/JdkRandom.kt" to "vol. 2, §3.2.1",
            "nenya-core/src/commonTest/kotlin/dev/eryalabs/nenya/channel/DeliveryMessageFixtures.kt" to "RFC 4648 §4",
        )

        /** What a document's headings and numbered lists say a citation may name. */
        data class Index(
            /** `"4"`, `"4.1"`, `"7.5"`: every numbered heading. */
            val sections: Set<String>,
            /** For each numbered heading, the numbers of its own top-level numbered list; empty if none. */
            val items: Map<String, Set<Int>>,
            /** The `n` of every `OPEN-n` heading under §16. */
            val opens: Set<Int>,
        )

        /** [text]'s numbered headings, each section's list items, and §16's `OPEN-n` headings. */
        fun index(text: String): Index {
            val sections = linkedMapOf<String, MutableSet<Int>>()
            val opens = mutableSetOf<Int>()
            var current: String? = null
            var top: String? = null
            var fenced = false
            for (line in text.lines()) {
                if (line.trimStart().startsWith("```")) fenced = !fenced
                if (fenced) continue
                val heading = Regex("""^#{1,6}\s+(.*)$""").find(line)
                if (heading != null) {
                    val title = heading.groupValues[1]
                    val number = Regex("""^(\d+(?:\.\d+)*)\.?(?:\s|$)""").find(title)?.groupValues?.get(1)
                    current = number
                    if (number != null) {
                        sections.getOrPut(number) { mutableSetOf() }
                        if ('.' !in number) top = number
                    }
                    val open = Regex("""^`OPEN-(\d+)`""").find(title)
                    if (open != null && top == "16") opens += open.groupValues[1].toInt()
                    continue
                }
                val item = Regex("""^(\d+)\.\s""").find(line)
                if (item != null && current != null) sections.getValue(current) += item.groupValues[1].toInt()
            }
            return Index(sections.keys, sections.mapValues { it.value.toSet() }, opens)
        }

        /** One citation as written, and where. */
        data class Citation(val file: String, val text: String)

        /** What scanning a file found: citations that do not resolve, and citations of other documents. */
        data class Scan(val unresolved: List<Citation>, val others: Set<Pair<String, String>>)

        /** A name of another document directly before a `§`. */
        private val OTHER_DOCUMENT_BEFORE =
            Regex("""(?:RFC\s+\d+|FIPS(?:\s+PUB)?\s+[\d-]+|vol\.\s+\d+,|NIP-\d+)\s*$""")

        /**
         * Scans [text], the contents of [file], against the specification's [spec] index and the
         * companion's [companion] headings.
         */
        fun scan(file: String, text: String, spec: Index, companion: Set<String>): Scan {
            val unresolved = mutableListOf<Citation>()
            val others = mutableSetOf<Pair<String, String>>()
            val allOther = file == SHA256_PATH

            for (citation in Regex("""§\s*(\d+(?:\.\d+)*)""").findAll(text)) {
                val before = text.substring(maxOf(0, citation.range.first - 40), citation.range.first)
                val otherName = OTHER_DOCUMENT_BEFORE.find(before)
                if (allOther || otherName != null) {
                    others += file to ((otherName?.value ?: "") + citation.value).replace(Regex("""\s+"""), " ")
                    continue
                }
                val number = citation.groupValues[1]
                val after = text.substring(citation.range.last + 1, minOf(text.length, citation.range.last + 200))

                val ofCompanion = Regex("""^\s+of\s+(?:\[the companion]|the companion)""").find(after)
                if (ofCompanion != null) {
                    if (number !in companion) {
                        unresolved += Citation(file, (citation.value + ofCompanion.value).replace(Regex("""\s+"""), " "))
                    }
                    continue
                }
                if (number !in spec.sections) {
                    unresolved += Citation(file, citation.value)
                    continue
                }
                val items = Regex("""^,?\s+items?\s+(\d+(?:\s*,\s*\d+)*(?:,?\s+and\s+\d+)?)""").find(after)
                if (items != null) {
                    val cited = Regex("""\d+""").findAll(items.groupValues[1]).map { it.value.toInt() }.toList()
                    if (!spec.items.getValue(number).containsAll(cited)) {
                        unresolved += Citation(file, (citation.value + items.value).replace(Regex("""\s+"""), " "))
                    }
                }
            }
            for (open in Regex("""OPEN-(\d+)""").findAll(text)) {
                if (open.groupValues[1].toInt() !in spec.opens) unresolved += Citation(file, open.value)
            }
            return Scan(unresolved, others)
        }

        /** [file] as text. A missing file fails; it never skips. */
        fun read(file: File): String {
            assertTrue(
                file.isFile,
                "${file.path} was expected at ${file.absoluteFile.normalize()}; the working directory is " +
                    File(".").absoluteFile.normalize(),
            )
            return file.readText(Charsets.UTF_8)
        }

        /** Every scanned file's contents, keyed by its path from the repository root. */
        fun corpus(): Map<String, String> {
            val root = File(SOURCE_ROOT)
            assertTrue(root.isDirectory, "the source tree was expected at ${root.absoluteFile.normalize()}")
            val sources = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != SELF }
                .sortedBy { it.path }
                .associate { "nenya-core/" + it.invariantSeparatorsPath to read(it) }
            assertTrue(sources.keys.any { it.endsWith("/Bid.kt") }, "the source tree scan found no Bid.kt")
            return sources +
                (SPEC_PATH to VendoredTestFiles.text(SPEC_PATH)) +
                DOCUMENTS.associateWith { read(File("../$it")) }
        }

        val specIndex: Index by lazy { index(VendoredTestFiles.text(SPEC_PATH)) }

        val companionHeadings: Set<String> by lazy { index(read(File(COMPANION_PATH))).sections }

        /** Every file in [files] scanned against the specification and the companion. */
        fun scanAll(files: Map<String, String>): Scan {
            val scans = files.map { (file, text) -> scan(file, text, specIndex, companionHeadings) }
            return Scan(scans.flatMap { it.unresolved }, scans.flatMap { it.others }.toSet())
        }

        /** Fails unless [others] equals [OTHER_DOCUMENTS], naming what is unpinned and what is stale. */
        fun assertPinned(others: Set<Pair<String, String>>) {
            assertEquals(
                emptySet<Pair<String, String>>() to emptySet(),
                (others - OTHER_DOCUMENTS) to (OTHER_DOCUMENTS - others),
                "(citations of other documents not pinned) to (pinned citations no longer in their file)",
            )
        }

        /** What scanning [text] alone, as a Kotlin source, finds unresolved. */
        fun unresolvedIn(text: String): List<String> =
            scan("nenya-core/src/commonMain/Probe.kt", text, specIndex, companionHeadings).unresolved.map { it.text }
    }

    private val files: Map<String, String> by lazy { corpus() }

    @Test
    fun `every citation in the source tree and the documents resolves`() {
        assertEquals(emptyList(), scanAll(files).unresolved, "citations that name nothing in NENYA-1 or its companion")
    }

    @Test
    fun `every citation of another document is pinned exactly`() {
        assertPinned(scanAll(files).others)
    }

    /** The counts the controls below rely on are read from the specification, not assumed. */
    @Test
    fun `the index reads the headings, the numbered lists and the OPEN headings`() {
        assertEquals((1..11).toSet(), specIndex.items.getValue("12"), "§12's numbered list")
        assertEquals((1..12).toSet(), specIndex.items.getValue("14"), "§14's numbered list")
        assertEquals((1..10).toSet(), specIndex.items.getValue("17"), "§17's numbered list")
        assertEquals((1..9).toSet(), specIndex.opens, "§16's OPEN-n headings")
        assertTrue("4.6" in specIndex.sections && "7.5" in specIndex.sections && "4.7" !in specIndex.sections)
        assertTrue("13" in companionHeadings && "12" !in companionHeadings, "the companion omits §12")
        assertTrue(files.keys.containsAll(DOCUMENTS + SPEC_PATH + SHA256_PATH), "every named document is scanned")
        assertTrue(files.keys.none { it.endsWith("/$SELF") }, "this file is excluded by name")
    }

    /** Positive control: what does resolve is not reported, so the negative controls below are not vacuous. */
    @Test
    fun `citations of what the specification has resolve`() {
        val resolving = listOf(
            "§4.6", "§7.5's rule", "§12 item 10", "§12 items 2 and 10", "§12 items 1, 2 and 11", "§17, item 3",
            "OPEN-9", "§13 of the companion", "§0 of [the companion](NENYA-1-companion.md)", "§13 of\n[the companion](x)",
            "RFC 3986 §3.1",
        )
        for (text in resolving) assertEquals(emptyList(), unresolvedIn(text), "`$text` resolves")
    }

    /** Negative control: each citation that names nothing in the specification or its companion is reported. */
    @Test
    fun `citations of what the specification does not have fail`() {
        val failing = listOf(
            "§4.7", "§12 item 12", "§12 items 2 and 12", "§12.10", "OPEN-10",
            "§12 of the companion", "§12 of\n[the companion](NENYA-1-companion.md)",
        )
        for (text in failing) assertEquals(1, unresolvedIn("See $text here.").size, "`$text` must be reported")
    }

    /** Negative control: a pinned pair no longer in its file fails the equality, and so does a new one. */
    @Test
    fun `a pinned citation removed or an unpinned one added fails`() {
        val (file, citation) = OTHER_DOCUMENTS.first { it.first != SHA256_PATH }
        val removed = files + (file to files.getValue(file).replace(citation, "RFC"))
        assertTrue(file to citation !in scanAll(removed).others, "the citation must be gone")
        assertFailsWith<AssertionError> { assertPinned(scanAll(removed).others) }

        val added = files + (file to files.getValue(file) + "\n// RFC 8259 §7\n")
        assertFailsWith<AssertionError> { assertPinned(scanAll(added).others) }
    }
}
