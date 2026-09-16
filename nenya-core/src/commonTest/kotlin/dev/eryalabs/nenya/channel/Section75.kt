package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §7.5's worked order proposal, parsed out of `spec/NENYA-1.md` **at test time**.
 *
 * The same anchor `Section74` puts under §7.4's tables and `Section41` under §4.1's escapes, applied
 * where the document actually prints an example. `ProposalCodecTest` holds the nine tag names this
 * example carries **equal** to the set [OrderProposal.readableTags] declares, minus exactly two
 * named extras, so a §7.5 revision that adds or drops a term turns the suite red rather than leaving
 * the codec quietly stale agreeing with itself.
 *
 * ### The example's *values* are not fixtures and must never be fed to the codec
 *
 * `<provider-pubkey-hex>`, `<buyer-pubkey-hex>` and `<fee-recipient-pubkey-hex>` are placeholders,
 * not keys: none is 64 hex characters and none would survive §4.3. So what is asserted over is the
 * tag **names**, which is the load-bearing half, and the fixtures take real keys from the vendored
 * `bip340-vectors.csv` (see `ProposalFixtures`), as T9 does.
 *
 * ### Two mechanical traps, each a silent wrong answer rather than a crash
 *
 * 1. **§7.5 is a `####` subsection.** A slice bounded on `###` would run from §7.4 through §7.6 and
 *    beyond; one bounded on `####` would miss §7.5's own heading level. [sectionLines] opens at
 *    §7.5's heading and stops at the next line opening with **any** number of `#`, which is §7.6.
 * 2. **The fence is ```` ```json ````, and §7.4's required-tags block four lines earlier is a plain
 *    ```` ``` ````.** Anchoring on the plain fence would find the wrong block, so the opening fence
 *    is required to be the `json` one and to appear exactly once inside the slice.
 *
 * Every accessor fails loudly, naming the file it read, so a wrong path cannot make a test pass
 * vacuously.
 */
internal object Section75 {

    /** §7.5's heading, matched on its prefix so the backticked `type=1` need not be transcribed. */
    private const val HEADING_PREFIX: String = "#### 7.5 "

    /** A markdown heading of any level, which is what bounds §7.5. Column zero, `#` then a space. */
    private val HEADING_LINE = Regex("""^#{1,6} """)

    /** A tag array's opening element: `["<name>", …`. The tag name is its first quoted token. */
    private val TAG_NAME = Regex("""^\s*\[\s*"([^"]+)"""")

    /** §7.5's example is fenced as JSON; §7.4's required-tags block four lines up is not. */
    private const val JSON_FENCE: String = "```json"

    private const val FENCE: String = "```"

    /**
     * The example prints nine tag arrays, and the count is asserted rather than assumed: a regex
     * that stopped matching would otherwise hand `ProposalCodecTest` an empty set and make its
     * equality "empty equals empty". The nine **names** are never transcribed — only how many.
     */
    private const val EXAMPLE_TAG_NAMES: Int = 9

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    internal fun specPath(): String = SpecAnchor.specFile().location

    /** §7.5 proper: from its heading to the next line opening with `#`, whatever its level. */
    internal val sectionLines: List<String> by lazy { readSection() }

    /** The tag names of §7.5's worked proposal, in the order the example prints them. */
    internal val exampleTagNames: List<String> by lazy { readExampleTagNames() }

    private fun readSection(): List<String> {
        val headingIndex = specLines.indexOfFirst { it.startsWith(HEADING_PREFIX) }
        if (headingIndex < 0) fail("no line opening \"$HEADING_PREFIX\" in ${specPath()}")
        val out = mutableListOf<String>()
        var index = headingIndex + 1
        while (index < specLines.size && !HEADING_LINE.containsMatchIn(specLines[index])) {
            out += specLines[index]
            index++
        }
        if (out.isEmpty()) fail("\"$HEADING_PREFIX\" in ${specPath()} is followed by no body at all")
        return out
    }

    private fun readExampleTagNames(): List<String> {
        val opens = sectionLines.withIndex().filter { it.value.trim() == JSON_FENCE }.map { it.index }
        if (opens.size != 1) {
            fail(
                "§7.5 in ${specPath()} must carry exactly one $JSON_FENCE fence — its worked " +
                    "proposal; found ${opens.size}",
            )
        }
        val open = opens.single()
        val close = sectionLines.withIndex()
            .firstOrNull { it.index > open && it.value.trim() == FENCE }
            ?.index
            ?: fail("§7.5's $JSON_FENCE fence in ${specPath()} is never closed")
        val names = sectionLines.subList(open + 1, close)
            .mapNotNull { TAG_NAME.find(it)?.groupValues?.get(1) }
        if (names.size != EXAMPLE_TAG_NAMES) {
            fail(
                "§7.5's worked proposal in ${specPath()} parsed to ${names.size} tag name(s) " +
                    "($names) where $EXAMPLE_TAG_NAMES were expected",
            )
        }
        if (names.size != names.toSet().size) {
            fail("§7.5's worked proposal in ${specPath()} names a tag twice: $names")
        }
        return names
    }
}
