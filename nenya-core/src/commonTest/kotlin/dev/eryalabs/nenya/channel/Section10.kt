package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §10.1's and §10.3's worked delivery messages, parsed out of `spec/NENYA-1.md` **at test time**.
 *
 * The same anchor `Section75` puts under §7.5's worked proposal and `Section74` under §7.4's tables,
 * applied to the two examples §10 prints. `DeliveryMessageCodecTest` holds the ten tag names each
 * example carries **equal** to the set the matching decoder requires or accepts, minus exactly one
 * named extra, so a §10 revision that adds or drops a tag turns the suite red rather than leaving a
 * codec quietly stale agreeing with itself.
 *
 * ### The example's *values* are not fixtures and must never be fed to the codec
 *
 * `<sha256 hex of the ENCRYPTED bytes exactly as served>`, `<64 hex chars>`, `<buyer-pubkey-hex>`
 * and `<same value as the commitment>` are placeholders, not values: none survives §4.3, and the
 * `x`/`ox` ones are not even hexadecimal. So what is asserted over is the tag **names**, which is
 * the load-bearing half, and the fixtures compute their own values (see `DeliveryMessageFixtures`).
 * The two values that *are* real in the examples — `["m", "video/mp4"]` and `["size", "18342912"]` —
 * are used by the fixtures as defaults, which is the one place an example value is safe: neither is
 * an encoding anybody had to reason about.
 *
 * ### Two mechanical traps, each a silent wrong answer rather than a crash
 *
 * 1. **The headings are `###`, and §10.2 sits between them.** A slice bounded on `##` would run from
 *    §10.1 to §11 and swallow both examples into one; [sectionLines] opens at the heading asked for
 *    and stops at the next line opening with **any** number of `#`.
 * 2. **§10's two fences are both ```` ```json ````.** Anchoring on a bare ```` ``` ```` would be
 *    ambiguous, so the opening fence is required to be the `json` one and to appear exactly once
 *    inside the slice — which is what makes taking "the" example well defined.
 *
 * Every accessor fails loudly, naming the file it read, so a wrong path cannot make a test pass
 * vacuously.
 */
internal object Section10 {

    /** §10.1's heading, matched on its prefix so the backticked `type=5` need not be transcribed. */
    private const val COMMITMENT_HEADING: String = "### 10.1 "

    /** §10.3's heading, likewise. */
    private const val RELEASE_HEADING: String = "### 10.3 "

    /** A markdown heading of any level, which is what bounds a subsection. Column zero. */
    private val HEADING_LINE = Regex("""^#{1,6} """)

    /** A tag array's opening element: `["<name>", …`. The tag name is its first quoted token. */
    private val TAG_NAME = Regex("""^\s*\[\s*"([^"]+)"""")

    private const val JSON_FENCE: String = "```json"

    private const val FENCE: String = "```"

    /**
     * Each example prints ten tag arrays, and the count is asserted rather than assumed: a regex
     * that stopped matching would otherwise hand the codec test an empty set and make its equality
     * "empty equals empty". The ten **names** are never transcribed — only how many.
     */
    private const val EXAMPLE_TAG_NAMES: Int = 10

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    internal fun specPath(): String = SpecAnchor.specFile().location

    /** §10.1 proper: from its heading to the next line opening with `#`, whatever its level. */
    internal val commitmentSectionLines: List<String> by lazy { readSection(COMMITMENT_HEADING) }

    /** §10.3 proper. */
    internal val releaseSectionLines: List<String> by lazy { readSection(RELEASE_HEADING) }

    /** The tag names of §10.1's worked commitment, in the order the example prints them. */
    internal val commitmentTagNames: List<String> by lazy {
        readExampleTagNames(COMMITMENT_HEADING, commitmentSectionLines)
    }

    /** The tag names of §10.3's worked release, in the order the example prints them. */
    internal val releaseTagNames: List<String> by lazy {
        readExampleTagNames(RELEASE_HEADING, releaseSectionLines)
    }

    private fun readSection(heading: String): List<String> {
        val headingIndex = specLines.indexOfFirst { it.startsWith(heading) }
        if (headingIndex < 0) fail("no line opening \"$heading\" in ${specPath()}")
        val out = mutableListOf<String>()
        var index = headingIndex + 1
        while (index < specLines.size && !HEADING_LINE.containsMatchIn(specLines[index])) {
            out += specLines[index]
            index++
        }
        if (out.isEmpty()) fail("\"$heading\" in ${specPath()} is followed by no body at all")
        return out
    }

    private fun readExampleTagNames(heading: String, lines: List<String>): List<String> {
        val opens = lines.withIndex().filter { it.value.trim() == JSON_FENCE }.map { it.index }
        if (opens.size != 1) {
            fail(
                "\"$heading\" in ${specPath()} must carry exactly one $JSON_FENCE fence — its " +
                    "worked example; found ${opens.size}",
            )
        }
        val open = opens.single()
        val close = lines.withIndex()
            .firstOrNull { it.index > open && it.value.trim() == FENCE }
            ?.index
            ?: fail("\"$heading\"'s $JSON_FENCE fence in ${specPath()} is never closed")
        val names = lines.subList(open + 1, close).mapNotNull { TAG_NAME.find(it)?.groupValues?.get(1) }
        if (names.size != EXAMPLE_TAG_NAMES) {
            fail(
                "\"$heading\"'s worked example in ${specPath()} parsed to ${names.size} tag " +
                    "name(s) ($names) where $EXAMPLE_TAG_NAMES were expected",
            )
        }
        if (names.size != names.toSet().size) {
            fail("\"$heading\"'s worked example in ${specPath()} names a tag twice: $names")
        }
        return names
    }
}
