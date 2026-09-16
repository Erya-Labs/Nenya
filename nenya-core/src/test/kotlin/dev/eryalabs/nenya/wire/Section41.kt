package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §4.1, parsed out of `spec/NENYA-1.md` **at test time**.
 *
 * The whole reason §4.1 was written out longhand is that NIP-01's own sentence is wrong in a way
 * that costs interoperability, so a test anchored on the implementer's reading of NIP-01 — or on
 * a table the implementer transcribed from §4.1 — proves only that the code agrees with whoever
 * wrote the test. Parsing the document means a specification revision that changed an escape, a
 * code point or the field order turns this suite red rather than leaving it confidently wrong.
 *
 * Everything here fails loudly if the parse comes back empty, so a moved heading cannot make a
 * test pass vacuously. The one thing it cannot prove is that §4.1 agrees with the *network*: that
 * needs `nostr-tools` or `go-nostr` output, which is a vendored vector nobody has added yet, and
 * which is recorded under "Blocked on human" rather than faked here.
 */
internal object Section41 {

    private const val HEADING: String = "### 4.1 Event ids and signatures"

    /** An escape in backticks, then its code point in parentheses — rule 1's own notation. */
    private val SHORTCUT = Regex("""`(\\[^`]{1,2})` \(0x([0-9A-Fa-f]{2})\)""")

    /** The lines of §4.1, from its heading to the next `###` heading. */
    fun lines(): List<String> {
        val all = SpecAnchor.specFile().readLines()
        val start = all.indexOfFirst { it.trim() == HEADING }
        if (start < 0) {
            fail("no line reading \"$HEADING\" in ${SpecAnchor.specFile().absolutePath}")
        }
        val after = all.drop(start + 1).indexOfFirst { it.startsWith("### ") }
        val end = if (after < 0) all.size else start + 1 + after
        val section = all.subList(start, end)
        assertTrue(section.size > 10, "§4.1 parsed to ${section.size} lines, which is not a section")
        return section
    }

    /**
     * Rule 1's seven `(escape, code point)` pairs, scoped to rule 1's own block.
     *
     * Scoped rather than taken document-wide for two reasons. Rule 2, two lines below, prints
     * three more backticked six-character escapes as its own worked examples, and §4.1's prose
     * names `0x7f` and `0x20` in parentheses further down still — so a document-wide sweep for
     * "backticked escape, then a parenthesised code point" is one editorial change away from
     * finding an eighth pair that is not one of the seven. Rule 1 also spans two source lines,
     * the seventh pair sitting on its own, so the block runs from the line opening rule 1 to the
     * line opening rule 2 rather than being one line.
     */
    fun shortcutEscapes(): Map<String, Int> {
        val section = lines()
        val first = section.indexOfFirst { it.startsWith("1. the seven shortcut escapes") }
        if (first < 0) fail("§4.1 carries no line opening \"1. the seven shortcut escapes\"")
        val next = section.drop(first + 1).indexOfFirst { it.startsWith("2. ") }
        if (next < 0) fail("§4.1 carries no rule 2, so rule 1's block has no end")
        val block = section.subList(first, first + 1 + next).joinToString("\n")

        val pairs = SHORTCUT.findAll(block)
            .map { it.groupValues[1] to it.groupValues[2].toInt(16) }
            .toList()
        assertEquals(
            7,
            pairs.size,
            "§4.1 rule 1 says \"the seven shortcut escapes … and only these seven\"; the parse found " +
                "${pairs.size} in:\n$block",
        )
        val escapes = pairs.toMap()
        assertEquals(7, escapes.size, "two of the parsed escapes are the same string: $pairs")
        assertEquals(
            7,
            escapes.values.toSet().size,
            "two of the parsed escapes name the same code point: $pairs",
        )
        return escapes
    }

    /**
     * §4.1's serialisation template, split into its six elements.
     *
     * Returns the element **descriptions** as the document writes them — `<pubkey hex>`,
     * `<created_at number>` and so on — so a test can derive "this one is a bare number" from the
     * specification rather than from its own memory of NIP-01.
     */
    fun templateElements(): List<String> {
        val candidates = lines().map { it.trim() }.filter { it.startsWith("[0,") && it.endsWith("]") }
        assertEquals(
            1,
            candidates.size,
            "§4.1 must carry exactly one canonical-serialisation template line; found $candidates",
        )
        val elements = candidates.single().removePrefix("[").removeSuffix("]").split(",")
        assertEquals(
            6,
            elements.size,
            "§4.1's template is six elements; this parse found ${elements.size}: $elements",
        )
        return elements
    }
}
