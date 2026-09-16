package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §8.6's, §9.2's and §9.4's `payment` and `payee` tag shapes, parsed out of `spec/NENYA-1.md` **at
 * test time**.
 *
 * The same anchor `Section74` puts under §7.4's tables, `Section75` under §7.5's worked example and
 * `Section53` under §5.3's: this package's vocabulary is not transcribed into a test and asserted
 * against the code the same hand wrote, it is read out of the document. A revision that renamed a
 * payee role, changed an arity or added a rail turns the suite red rather than leaving this codec
 * quietly agreeing with itself.
 *
 * ### Values are never fixtures here
 *
 * `<bolt11>`, `<fee-recipient-pubkey-hex>`, `<medium-reference>` and `<proof>` are placeholders,
 * not values: none would survive the codec, and none is fed to it. What is asserted over is the
 * **shape** — the tag names, the vocabulary tokens and the element counts — which is the half that
 * is normative.
 *
 * ### The mechanical trap, which is a silent wrong answer rather than a crash
 *
 * §9.2 prints a worked `kind:17` inside a ```` ```json ```` fence whose lines include
 * `["payment", "lightning", "lnbc900u1p...", …]`. A parser that took "every bracketed array in the
 * section" would read the example's four-element tag as the normative shape and then agree with
 * itself for the wrong reason. So the normative shapes are read from **backticked inline** arrays
 * only, which the fenced example's lines are not, and the fenced block of §8.6 is read as its own
 * thing and asserted to be the only fence in that section.
 *
 * Every accessor fails loudly, naming the file it read, so a wrong path cannot make a test pass
 * vacuously.
 */
internal object SpecTagShapes {

    /** A markdown heading of any level, which is what bounds a section. Column zero, `#` then a space. */
    private val HEADING_LINE = Regex("""^#{1,6} """)

    /** A bracketed tag array inside single backticks — the document's inline, normative form. */
    private val INLINE_TAG = Regex("""`(\[[^`\]]*])`""")

    /** A bracketed tag array on a line of its own, which is how a fenced block prints one. */
    private val BLOCK_TAG = Regex("""^\s*(\[[^\]]*])\s*$""")

    /** A double-quoted element of a tag array. */
    private val ELEMENT = Regex(""""([^"]*)"""")

    private const val FENCE: String = "```"

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /** Where every failure message below points. */
    fun specPath(): String = SpecAnchor.specFile().location

    /** A section's body: from its heading to the next line opening with `#`, whatever its level. */
    fun sectionLines(headingPrefix: String): List<String> {
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

    /**
     * Every **backticked inline** tag array in [headingPrefix]'s section whose first element is
     * [name], decoded into its elements.
     *
     * Filtered by the tag name rather than taken whole, so a section that gains an unrelated inline
     * array — §8.6 already carries a backticked `lud16` — does not change what this returns.
     */
    fun inlineTags(headingPrefix: String, name: String): List<List<String>> =
        sectionLines(headingPrefix)
            .flatMap { line -> INLINE_TAG.findAll(line).map { it.groupValues[1] }.toList() }
            .map { elements(it) }
            .filter { it.firstOrNull() == name }

    /**
     * The tag arrays in the **single** fenced block of [headingPrefix]'s section.
     *
     * "Single" is asserted rather than assumed: a section with two fences would otherwise silently
     * hand back the first one's contents.
     */
    fun fencedTags(headingPrefix: String): List<List<String>> {
        val lines = sectionLines(headingPrefix)
        val fences = lines.withIndex().filter { it.value.trim() == FENCE }.map { it.index }
        if (fences.size != 2) {
            fail(
                "\"$headingPrefix\" in ${specPath()} must carry exactly one fenced block — an " +
                    "opening and a closing fence; found ${fences.size} fence line(s)",
            )
        }
        val tags = lines.subList(fences.first() + 1, fences.last())
            .mapNotNull { BLOCK_TAG.find(it)?.groupValues?.get(1) }
            .map { elements(it) }
        if (tags.isEmpty()) {
            fail("the fenced block of \"$headingPrefix\" in ${specPath()} parsed to no tag array")
        }
        return tags
    }

    private fun elements(array: String): List<String> =
        ELEMENT.findAll(array).map { it.groupValues[1] }.toList()
}

/**
 * §8.6's two payee roles and its payment-request shape, as the document prints them.
 *
 * §8.6 is where the `payee` vocabulary is defined and where the request's `payment` tag is fixed at
 * exactly one `lightning` reference, so this is the anchor `PaymentRequestCodecTest` holds
 * `Payee.PROVIDER.token`, `Payee.FEE.token`, `PaymentMedium.LIGHTNING.token` and the three arity
 * constants against.
 */
internal object Section86 {

    /** §8.6's heading, matched on its prefix so the em dash in the title need not be transcribed. */
    const val HEADING: String = "### 8.6 "

    /** §8.6's fenced block: `["payee", "provider"]` and `["payee", "fee", "<pubkey>"]`. */
    val payeeTags: List<List<String>> by lazy {
        val tags = SpecTagShapes.fencedTags(HEADING)
        if (tags.size != PAYEE_ROLES) {
            fail(
                "§8.6's fenced block in ${SpecTagShapes.specPath()} parsed to ${tags.size} tag " +
                    "array(s) where $PAYEE_ROLES were expected: $tags",
            )
        }
        tags
    }

    /** §8.6's `["payment", "lightning", "<bolt11>"]`, the one shape a `type=2` may carry. */
    val paymentRequestTag: List<String> by lazy {
        val tags = SpecTagShapes.inlineTags(HEADING, PAYMENT_TAG_NAME)
        if (tags.size != 1) {
            fail(
                "§8.6 in ${SpecTagShapes.specPath()} must print exactly one inline " +
                    "`$PAYMENT_TAG_NAME` tag — the request's — and printed ${tags.size}: $tags",
            )
        }
        tags.single()
    }

    /** §8.6 gives two payee roles and no third. Asserted on the parse, never consumed as the count. */
    private const val PAYEE_ROLES: Int = 2

    /** The tag name the parse filters on. Not a vocabulary claim: the tokens are what is asserted. */
    private const val PAYMENT_TAG_NAME: String = "payment"
}

/** §9.2's GammaMarkets `payment` shape — the four-element form a `kind:17` receipt carries. */
internal object Section92 {

    const val HEADING: String = "### 9.2 "

    /**
     * `["payment", "<medium>", "<medium-reference>", "<proof>"]`, read from §9.2's backticked
     * sentence and **not** from its worked `kind:17`, whose lines are inside a fence.
     */
    val paymentShape: List<String> by lazy {
        val tags = SpecTagShapes.inlineTags(HEADING, PAYMENT_TAG_NAME)
        if (tags.size != 1) {
            fail(
                "§9.2 in ${SpecTagShapes.specPath()} must print exactly one inline " +
                    "`$PAYMENT_TAG_NAME` shape and printed ${tags.size}: $tags",
            )
        }
        tags.single()
    }

    private const val PAYMENT_TAG_NAME: String = "payment"
}

/** §9.4's two other rails, which v1 parses and evidences nothing from. */
internal object Section94 {

    const val HEADING: String = "### 9.4 "

    /** The `bitcoin` and `ecash` forms, in the order §9.4 prints them. */
    val railTags: List<List<String>> by lazy {
        val tags = SpecTagShapes.inlineTags(HEADING, PAYMENT_TAG_NAME)
        if (tags.size != RAILS) {
            fail(
                "§9.4 in ${SpecTagShapes.specPath()} must print $RAILS inline `$PAYMENT_TAG_NAME` " +
                    "forms and printed ${tags.size}: $tags",
            )
        }
        tags
    }

    /** §9.4 names two other rails. Asserted on the parse, never consumed as the count. */
    private const val RAILS: Int = 2

    private const val PAYMENT_TAG_NAME: String = "payment"
}
