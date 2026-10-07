package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §5.1's and §5.2's **worked listings**, parsed out of `spec/NENYA-1.md` at test time.
 *
 * The same anchor `Section53` puts under §5.3's table, `Section74` under §7.4's and `Section75`
 * under §7.5's worked example: nothing here is transcribed. §5.2 licenses this directly — "This
 * example is copy-pasteable as written, and fixtures MAY be built against it" — and the two things
 * these examples are used for are both ones a transcription could not prove:
 *
 * 1. **`ListingWriter.TAG_ORDER` is the document's order**, not one somebody chose. The tag order is
 *    wire-visible, because §4.1 hashes the tags in the order they appear, so the writer had to pick
 *    one; the one it picks is the order both of these examples print, held equal here.
 * 2. **The writer reproduces the document's own listings byte for byte.** A listing decoded from one
 *    of these examples and rebuilt by the writer emits the identical tag array — which is the
 *    inverse of the generated round trip, and the half that a generator agreeing with itself cannot
 *    reach.
 *
 * ### The mechanical trap, and it is a silent wrong answer
 *
 * The tag arrays here sit inside a ```` ```json ```` fence, one per line, and every line but the
 * last carries a **trailing comma**. `SpecTagShapes.fencedTags` in the settlement test package
 * matches a bracketed array alone on its line — §8.6's fenced block prints them that way — so
 * pointing it at this fence would silently return the single array that happens to be last. A
 * parser for one document's examples is not a parser for another's, so this one is its own, and the
 * count it found is asserted rather than assumed.
 *
 * Every accessor fails loudly naming the file it read, so a wrong path cannot make a test pass
 * vacuously.
 */
internal object Section51 {

    /** §5.1's worked `kind:30402` offer. */
    const val OFFER_HEADING: String = "### 5.1 Offers"

    /** §5.2's worked `kind:30404` request. */
    const val REQUEST_HEADING: String = "### 5.2 Requests"

    /** A markdown heading of any level, which is what bounds a section. Column zero, `#` then a space. */
    private val HEADING_LINE = Regex("""^#{1,6} """)

    /** A bracketed array on a line of its own, with the trailing comma a JSON example line carries. */
    private val BLOCK_TAG = Regex("""^\s*(\[[^\]]*\]),?\s*$""")

    /** A double-quoted element of a tag array. */
    private val ELEMENT = Regex(""""([^"]*)"""")

    /** The example object's own `"kind": <n>,` line, so a renumbered example cannot pass unnoticed. */
    private val KIND_FIELD = Regex("""^\s*"kind":\s*([0-9]+),?\s*$""")

    private const val FENCE: String = "```"

    /** The fewest tag arrays either worked listing prints. A parser that found one row must fail. */
    private const val MIN_TAGS: Int = 10

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /** Where every failure message below points. */
    fun specPath(): String = SpecAnchor.specFile().location

    /** One worked listing: the kind its JSON names and its tag arrays, in the order it prints them. */
    data class Example(val heading: String, val kind: Int, val tags: List<List<String>>)

    /** §5.1's offer example. */
    fun offer(): Example = example(OFFER_HEADING)

    /** §5.2's request example. */
    fun request(): Example = example(REQUEST_HEADING)

    /** Both, in document order, which is every worked listing the document prints. */
    fun all(): List<Example> = listOf(offer(), request())

    private fun example(headingPrefix: String): Example {
        val lines = fencedBlock(headingPrefix)
        val kinds = lines.mapNotNull { KIND_FIELD.find(it)?.groupValues?.get(1) }
        if (kinds.size != 1) {
            fail(
                "the fenced example under \"$headingPrefix\" in ${specPath()} must carry exactly one " +
                    "`\"kind\": <n>` line; found ${kinds.size}",
            )
        }
        val tags = lines.mapNotNull { BLOCK_TAG.find(it)?.groupValues?.get(1) }.map { elements(it) }
        if (tags.size < MIN_TAGS) {
            fail(
                "the fenced example under \"$headingPrefix\" in ${specPath()} parsed to ${tags.size} " +
                    "tag array(s) and at least $MIN_TAGS were expected. A trailing comma on every " +
                    "line but the last is the usual cause of a parser finding only one.",
            )
        }
        for (tag in tags) {
            if (tag.isEmpty()) {
                fail("a tag array under \"$headingPrefix\" in ${specPath()} parsed to no elements")
            }
        }
        return Example(headingPrefix, kinds.single().toInt(), tags)
    }

    /**
     * The body of the **single** fenced block in [headingPrefix]'s section.
     *
     * "Single" is asserted rather than assumed: a section with two fences would otherwise hand back
     * the first one's contents without saying so, and §5.2 already carries two block quotes around
     * its example.
     */
    private fun fencedBlock(headingPrefix: String): List<String> {
        val section = sectionLines(headingPrefix)
        val fences = section.withIndex().filter { it.value.trim().startsWith(FENCE) }.map { it.index }
        if (fences.size != 2) {
            fail(
                "\"$headingPrefix\" in ${specPath()} must carry exactly one fenced block — an " +
                    "opening and a closing fence; found ${fences.size} fence line(s)",
            )
        }
        return section.subList(fences.first() + 1, fences.last())
    }

    /** A section's body: from its heading to the next line opening with `#`, whatever its level. */
    private fun sectionLines(headingPrefix: String): List<String> {
        val headingIndex = specLines.indexOfFirst { it.startsWith(headingPrefix) }
        if (headingIndex < 0) fail("no line opening \"$headingPrefix\" in ${specPath()}")
        val out = mutableListOf<String>()
        var index = headingIndex + 1
        while (index < specLines.size && !HEADING_LINE.containsMatchIn(specLines[index])) {
            out += specLines[index]
            index++
        }
        if (out.isEmpty()) fail("\"$headingPrefix\" in ${specPath()} is followed by no body at all")
        return out
    }

    private fun elements(array: String): List<String> =
        ELEMENT.findAll(array).map { it.groupValues[1] }.toList()
}
