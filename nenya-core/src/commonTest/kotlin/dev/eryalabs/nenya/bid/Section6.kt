package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §6's worked bid and its MUST bullets, parsed out of the specification's lines at test time.
 *
 * `listing/Section51.kt`'s design — nothing transcribed, every accessor failing loudly naming what it
 * read — with one difference that is the reason this is a class: **the lines are a parameter.**
 * [fromSpec] reads `spec/NENYA-1.md`; a test can also hand it §6 with one word changed and watch an
 * assertion that held turn red, which is the only way to show this parser reads the prose rather than
 * agreeing with whatever it is compared to.
 *
 * Two things are read, and both are things `BidVocabulary` and `BidWriter` used to state on nobody's
 * authority but their own:
 *
 * 1. **The tag names §6's worked bid prints, in order** — the fenced example's arrays, first element
 *    of each. Names only: the example's values are `<…-pubkey-hex>` placeholders, three of them, one
 *    inside a coordinate, and none is a value §4.3 accepts, so the byte-for-byte rebuild
 *    `ListingWriterTest` does for §5.1 and §5.2 is not available here.
 * 2. **The tags §6's MUST bullets require a bid to carry.** A sentence in the `Rules:` list counts
 *    when it says MUST together with `carry`, `be present` or `scope to`; the tags it names are its
 *    backticked tokens before any `never` (§6's first rule names `E`/`e` as what a bid must *not*
 *    scope to), with `t=nenya` read as the tag `t`. That is every presence rule §6 states and none of
 *    the others: `deliver_by`'s "when present … MUST be evaluated" is a deadline rule, `fee`'s is a
 *    MAY, `alt`'s and `expiration`'s are SHOULDs.
 */
internal class Section6(private val specLines: List<String>, private val source: String) {

    /** The tag names of §6's worked bid, in the order it prints them. */
    fun exampleTagNames(): List<String> {
        val lines = fencedBlock()
        val kinds = lines.mapNotNull { KIND_FIELD.find(it)?.groupValues?.get(1) }
        if (kinds != listOf(PUBLIC_BID_KIND)) {
            fail("§6's worked bid in $source must carry one `\"kind\": $PUBLIC_BID_KIND` line; found $kinds")
        }
        val names = lines.mapNotNull { BLOCK_TAG.find(it)?.groupValues?.get(1) }
        if (names.size < MIN_TAGS) {
            fail(
                "§6's worked bid in $source parsed to ${names.size} tag array(s) and at least $MIN_TAGS " +
                    "were expected",
            )
        }
        return names
    }

    /** The tags §6's MUST bullets require, in the order the bullets name them. */
    fun requiredTagNames(): List<String> {
        val named = mutableListOf<String>()
        for (bullet in ruleBullets()) {
            for (sentence in bullet.split(SENTENCE_END)) {
                if (MUST !in sentence || PRESENCE.none { it.containsMatchIn(sentence) }) continue
                val clause = sentence.substringBefore(NEVER)
                for (match in BACKTICKED.findAll(clause)) {
                    val name = match.groupValues[1].substringBefore('=')
                    if (TAG_NAME.matches(name) && name !in named) named += name
                }
            }
        }
        if (named.isEmpty()) fail("§6's rules in $source name no tag a bid MUST carry")
        return named
    }

    /** The `Rules:` list under §6, one string per bullet with its continuation lines joined. */
    private fun ruleBullets(): List<String> {
        val section = sectionLines()
        val start = section.indexOfFirst { it.trim() == RULES_HEADING }
        if (start < 0) fail("no `$RULES_HEADING` line under \"$HEADING\" in $source")
        val bullets = mutableListOf<StringBuilder>()
        for (line in section.drop(start + 1)) {
            when {
                line.startsWith("- ") -> bullets += StringBuilder(line.removePrefix("- "))
                line.startsWith("  ") && bullets.isNotEmpty() -> bullets.last().append(' ').append(line.trim())
                line.isBlank() && bullets.isEmpty() -> Unit
                else -> break
            }
        }
        if (bullets.size < MIN_RULES) {
            fail("\"$HEADING\" in $source lists ${bullets.size} rule(s) and at least $MIN_RULES were expected")
        }
        return bullets.map { it.toString() }
    }

    /** The body of the single fenced block in §6, asserted single. */
    private fun fencedBlock(): List<String> {
        val section = sectionLines()
        val fences = section.withIndex().filter { it.value.trim().startsWith(FENCE) }.map { it.index }
        if (fences.size != 2) {
            fail("\"$HEADING\" in $source must carry exactly one fenced block; found ${fences.size} fence line(s)")
        }
        return section.subList(fences.first() + 1, fences.last())
    }

    /** §6's body: from its heading to the next markdown heading of any level, which is §6.1's. */
    private fun sectionLines(): List<String> {
        val headingIndex = specLines.indexOfFirst { it.startsWith(HEADING) }
        if (headingIndex < 0) fail("no line opening \"$HEADING\" in $source")
        val out = mutableListOf<String>()
        var index = headingIndex + 1
        while (index < specLines.size && !HEADING_LINE.containsMatchIn(specLines[index])) {
            out += specLines[index]
            index++
        }
        if (out.isEmpty()) fail("\"$HEADING\" in $source is followed by no body at all")
        return out
    }

    companion object {

        /** §6's heading, which names its kind. */
        const val HEADING: String = "## 6. Bids"

        /** The specification itself. */
        fun fromSpec(): Section6 = Section6(SpecAnchor.specFile().readLines(), SpecAnchor.specFile().location)

        private const val RULES_HEADING: String = "Rules:"
        private const val FENCE: String = "```"
        private const val MUST: String = "MUST"
        private const val NEVER: String = "never"
        private const val SENTENCE_END: String = ". "
        private const val PUBLIC_BID_KIND: String = "1111"

        /** The fewest tag arrays §6's example could print: §6's nine MUSTs. */
        private const val MIN_TAGS: Int = 9

        /** The fewest bullets §6's rules could hold: the three presence rules. */
        private const val MIN_RULES: Int = 3

        private val HEADING_LINE = Regex("""^#{1,6} """)

        /** A tag array alone on its line, trailing comma allowed, capturing its first element. */
        private val BLOCK_TAG = Regex("""^\s*\["([^"]*)"[^\]]*\],?\s*$""")

        private val KIND_FIELD = Regex("""^\s*"kind":\s*([0-9]+),?\s*$""")

        private val BACKTICKED = Regex("""`([^`]+)`""")

        private val TAG_NAME = Regex("""[A-Za-z_]+""")

        /** The three verbs §6 states a presence rule with. */
        private val PRESENCE: List<Regex> = listOf(
            Regex("""\bcarry\b"""),
            Regex("""\bbe present\b"""),
            Regex("""\bscope to\b"""),
        )
    }
}
