package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §17's numbered conformance list and its capability-surface clause, parsed out of
 * `spec/NENYA-1.md` at test time.
 *
 * Nothing here is transcribed, for the reason `Section11`, `Section41` and `Section53` give:
 * a hand-written copy of a specification list is a transcription, and a test written by the same
 * hand that wrote the copy proves only that the two agree. Parsing means a revision that added an
 * item, renumbered one, or moved an obligation from one section to another turns the suite red
 * instead of leaving [Capabilities] agreeing with itself.
 *
 * ### Two mechanical facts about this particular list
 *
 * 1. **Items wrap.** Item 1 runs over two lines and item 5 over six. A line-per-item parse would
 *    find ten items and then read item 5's section references as `§8.4` alone, which is the half
 *    of the anchor that actually discriminates.
 * 2. **The list ends at a blank line.** §17's items are contiguous and the paragraph that follows
 *    them — the MAY-omit clause this whole package exists because of — is separated by one. So a
 *    blank line terminates the list, and the clause is read separately by [capabilitySurfaceClause].
 *
 * Every accessor fails loudly, naming the absolute path it read, so a wrong working directory
 * cannot make a test pass vacuously.
 */
internal object Section17 {

    /** One §17 item: its number, the 1-based spec line it opens on, and its joined text. */
    internal data class Item(val number: Int, val specLine: Int, val text: String)

    private const val HEADING: String = "## 17. Conformance"

    /** §17's lead-in. The list starts after it and nowhere else in the document. */
    private const val LEAD_IN: String = "An implementation is **NENYA-1 conformant** if it:"

    /**
     * The sentence that makes this package a MUST rather than a nicety. Held as a fragment
     * rather than the whole sentence so ordinary editorial rewrapping does not turn it red.
     */
    private const val SURFACE_CLAUSE: String = "MUST expose a capability surface"

    private val ITEM_OPENS = Regex("""^(\d+)\.\s+(.*)$""")

    /** `§4.1`, `§10`, `§11.3` — a section reference, captured without its `§`. */
    private val SECTION_REFERENCE = Regex("""§(\d+(?:\.\d+)?)""")

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /** §17's numbered items, in document order. */
    internal val items: List<Item> by lazy { readItems() }

    /** The `x.y` references [number]'s text cites, without their `§`. */
    internal fun sectionsCitedBy(number: Int): Set<String> {
        val item = items.singleOrNull { it.number == number }
            ?: fail("§17 in ${specPath()} carries ${items.count { it.number == number }} items numbered $number")
        val cited = SECTION_REFERENCE.findAll(item.text).map { it.groupValues[1] }.toSet()
        if (cited.isEmpty()) {
            fail(
                "§17 item $number at line ${item.specLine} of ${specPath()} cites no section at " +
                    "all, so the surface's claim about it could not be anchored: ${item.text}",
            )
        }
        return cited
    }

    /**
     * §17's last paragraph, which requires an implementation omitting BIP-340 verification to
     * publish this surface. Read so that a revision deleting the requirement is visible here
     * rather than leaving a package with no reason to exist.
     */
    internal fun capabilitySurfaceClause(): String =
        specLines.firstOrNull { SURFACE_CLAUSE in it }
            ?: fail(
                "§17's \"$SURFACE_CLAUSE\" clause is not in ${specPath()}. It is the MUST this " +
                    "package implements; if the specification dropped it, that is a decision for a " +
                    "human and not something to route around.",
            )

    internal fun specPath(): String = SpecAnchor.specFile().absolutePath

    private fun readItems(): List<Item> {
        val headingIndex = specLines.indexOfFirst { it.trim() == HEADING }
        if (headingIndex < 0) fail("no `$HEADING` heading in ${specPath()}")
        val leadIndex = specLines.drop(headingIndex).indexOfFirst { it.trim() == LEAD_IN }
        if (leadIndex < 0) {
            fail("no `$LEAD_IN` line after `$HEADING` in ${specPath()}")
        }

        var index = headingIndex + leadIndex + 1
        while (index < specLines.size && specLines[index].isBlank()) index++

        val items = mutableListOf<Item>()
        val current = StringBuilder()
        var currentNumber = -1
        var currentLine = -1
        while (index < specLines.size) {
            val line = specLines[index]
            if (line.isBlank()) break
            val opens = ITEM_OPENS.find(line.trimEnd())
            when {
                opens != null -> {
                    if (currentNumber > 0) items += Item(currentNumber, currentLine, current.toString().trim())
                    current.setLength(0)
                    currentNumber = opens.groupValues[1].toInt()
                    currentLine = index + 1
                    current.append(opens.groupValues[2])
                }
                // A wrapped continuation: indented, and only meaningful inside an open item.
                currentNumber > 0 && line.first().isWhitespace() -> current.append(' ').append(line.trim())
                else -> break
            }
            index++
        }
        if (currentNumber > 0) items += Item(currentNumber, currentLine, current.toString().trim())

        if (items.isEmpty()) fail("§17's numbered list parsed to zero items in ${specPath()}")
        val numbers = items.map { it.number }
        if (numbers != numbers.sorted() || numbers.toSet().size != numbers.size) {
            fail("§17's items in ${specPath()} are not a strictly increasing numbering: $numbers")
        }
        return items
    }
}
