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
 * §8.4's seven points — the four it marks REQUIRED and the three it marks OPTIONAL — parsed out of
 * `spec/NENYA-1.md` at test time and kept apart.
 *
 * ### Derived, never transcribed, and never counted into the model
 *
 * `FeeTermPoint` is held **equal** to what this object parses, so a codec covering two of the four
 * cannot pass and a §8.4 revision that added a point, moved one between REQUIRED and OPTIONAL, or
 * renamed one turns the suite red. The counts below are asserted **on the parse** as a control that
 * the parser found the list it was pointed at; the model's own counts are derived from its
 * `requirement` field and never from a number written here.
 *
 * ### How each point is reduced to something comparable
 *
 * §8.4 states the REQUIRED points as a numbered list whose items wrap and carry trailing clauses,
 * and the OPTIONAL ones in the sentence that follows. Each point is therefore cut to its own
 * **first parenthetical group** — every one of the four numbered items ends at its first `)` — and
 * then normalised: backticks dropped, a parenthetical that is only a `§x.y` reference dropped
 * whole, other parentheses and commas dropped, a leading article dropped, whitespace collapsed,
 * lowercased. `1. the order proposal (`type=1`) — subject to §8.1's read rule…` becomes
 * `order proposal type=1`, which is the discriminator §8.4 itself puts in the parenthetical.
 *
 * The normalisation is stated here rather than left to be read out of the regexes because it is
 * the one place this anchor could be made to agree with the model by weakening it: a rule that
 * stripped everything but the first noun would make `fee receipt` and `fee payment request` equal.
 *
 * Every accessor fails loudly, naming the file it read.
 */
internal object Section84 {

    /** §8.4's heading, matched on its prefix so the rest of the title need not be transcribed. */
    const val HEADING: String = "### 8.4 "

    /** The line that opens the numbered list. */
    private const val REQUIRED_LEAD_IN: String = "It is **REQUIRED** on:"

    /** The phrase that opens the sentence carrying the other three. */
    private const val OPTIONAL_LEAD_IN: String = "It is **OPTIONAL** on "

    /** `1. `, `2. ` — a numbered item opening at column zero. */
    private val ITEM_OPENS = Regex("""^(\d+)\.\s+(.*)$""")

    /** A parenthetical that is only a section reference, such as `(§6)`, which carries no point. */
    private val SECTION_ONLY_PARENTHETICAL = Regex("""\(\s*§\d+(\.\d+)*\s*\)""")

    /** The clause separators of §8.4's OPTIONAL sentence: `, on ` and `, and on `. */
    private val OPTIONAL_SEPARATOR = Regex(""",\s+(?:and\s+)?on\s+""")

    /** Articles §8.4 opens its phrases with, which carry no discriminator. */
    private val LEADING_ARTICLE = Regex("""^(the|a|an)\s+""")

    /** §8.4 numbers four REQUIRED points. Asserted on the parse, never consumed as the count. */
    private const val REQUIRED_POINTS: Int = 4

    /** And names three OPTIONAL ones. Likewise. */
    private const val OPTIONAL_POINTS: Int = 3

    /** §8.4's four REQUIRED points, normalised, in the order the document numbers them. */
    val requiredDescriptors: List<String> by lazy {
        val lines = SpecTagShapes.sectionLines(HEADING)
        // The lead-in ends a wrapped sentence rather than sitting on a line of its own, so the
        // anchor is "the line this phrase ends" and not "the line equal to this phrase".
        val leadIn = lines.indexOfFirst { it.trimEnd().endsWith(REQUIRED_LEAD_IN) }
        if (leadIn < 0) {
            fail("§8.4 in ${SpecTagShapes.specPath()} carries no line ending \"$REQUIRED_LEAD_IN\"")
        }
        val items = mutableListOf<String>()
        var index = leadIn + 1
        while (index < lines.size && lines[index].isBlank()) index++
        val current = StringBuilder()
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            val opens = ITEM_OPENS.find(line.trimEnd())
            when {
                opens != null -> {
                    if (current.isNotEmpty()) items += current.toString()
                    current.setLength(0)
                    current.append(opens.groupValues[2])
                }
                // A wrapped continuation: indented, and only meaningful inside an open item.
                current.isNotEmpty() && line.first().isWhitespace() -> current.append(' ').append(line.trim())
                else -> break
            }
            index++
        }
        if (current.isNotEmpty()) items += current.toString()
        if (items.size != REQUIRED_POINTS) {
            fail(
                "§8.4's REQUIRED list in ${SpecTagShapes.specPath()} parsed to ${items.size} " +
                    "item(s) where $REQUIRED_POINTS were expected: $items",
            )
        }
        items.map { normalise(untilFirstParenthetical(it)) }
    }

    /** §8.4's three OPTIONAL points, normalised, in the order its sentence names them. */
    val optionalDescriptors: List<String> by lazy {
        val body = SpecTagShapes.sectionLines(HEADING).joinToString(" ")
        val opens = body.indexOf(OPTIONAL_LEAD_IN)
        if (opens < 0) {
            fail("§8.4 in ${SpecTagShapes.specPath()} carries no \"$OPTIONAL_LEAD_IN\" clause")
        }
        val start = opens + OPTIONAL_LEAD_IN.length
        val end = body.indexOf(':', start)
        if (end < 0) {
            fail("§8.4's OPTIONAL sentence in ${SpecTagShapes.specPath()} does not end in a colon")
        }
        val clauses = body.substring(start, end).split(OPTIONAL_SEPARATOR)
        if (clauses.size != OPTIONAL_POINTS) {
            fail(
                "§8.4's OPTIONAL sentence in ${SpecTagShapes.specPath()} parsed to " +
                    "${clauses.size} clause(s) where $OPTIONAL_POINTS were expected: $clauses",
            )
        }
        clauses.map { normalise(it) }
    }

    /**
     * §8.4's own sentence on what a divergence is and the three answers it forbids, so a revision
     * that softened the rule is visible here rather than leaving a checker with no rule to enforce.
     */
    val divergenceClause: String by lazy {
        val body = SpecTagShapes.sectionLines(HEADING).joinToString(" ")
        if ("MUST abort the order" !in body) {
            fail("§8.4 in ${SpecTagShapes.specPath()} no longer requires a divergence abort the order")
        }
        body
    }

    /** The text up to and including the first `)`, which is where every one of §8.4's items ends. */
    private fun untilFirstParenthetical(item: String): String {
        val close = item.indexOf(')')
        return if (close < 0) item else item.substring(0, close + 1)
    }

    private fun normalise(phrase: String): String = phrase
        .replace(SECTION_ONLY_PARENTHETICAL, " ")
        .replace("`", "")
        .replace("(", " ")
        .replace(")", " ")
        .replace(",", " ")
        .trim()
        .replace(LEADING_ARTICLE, "")
        .split(Regex("""\s+"""))
        .joinToString(" ")
        .lowercase()
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
