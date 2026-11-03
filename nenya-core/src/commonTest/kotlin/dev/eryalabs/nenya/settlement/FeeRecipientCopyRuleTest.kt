package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.order.Section11
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revision `1.14`'s rule (decision T): the buyer sends the fee recipient the order's `type=1` once a
 * fee-bearing order is `committed`, and the fee recipient takes the copy as evidence of nothing else.
 *
 * The rule is held to §8.7, stated **exactly once** in the whole document: a second statement
 * elsewhere is the restatement decision S forbids, and a statement only elsewhere is the rule moved
 * out of its home section. Each of its two sentences is matched with line breaks and runs of spaces
 * collapsed, so re-wrapping the paragraph keeps it green and changing one word of it does not.
 *
 * The revision added four lines above §11.2, so the same commit regenerated
 * `nenya-1-11.2-transitions.psv`. The last two tests show that the comparison `TransitionTableTest`
 * runs refuses a psv that was not regenerated and one with a single row moved by hand.
 *
 * [CopyRule.problem] takes the specification as lines, so every control runs on text built here from
 * the live specification.
 */
class FeeRecipientCopyRuleTest {

    internal object CopyRule {

        /** §8.7's heading, matched on its prefix. */
        const val HOME_HEADING: String = "### 8.7 "

        /** The rule's two sentences, as NENYA-1 `1.14` states them. */
        val SENTENCES: List<String> = listOf(
            "When an order whose `fee_msat` (§8.3) is not `0` enters `committed`, the buyer MUST send the " +
                "fee recipient named in its fee term that order's `type=1`, unchanged, by §7.1's write procedure.",
            "A fee recipient MUST NOT treat that copy as evidence of an acceptance, a commitment or a payment.",
        )

        private val HEADING_LINE = Regex("""^#{1,6} """)

        private val WHITESPACE = Regex("""\s+""")

        /**
         * For [sentence], the heading of the section holding each occurrence, once per occurrence.
         * Text before the first heading is reported under the empty heading.
         */
        fun occurrences(lines: List<String>, sentence: String): List<String> {
            val wanted = WHITESPACE.replace(sentence, " ").trim()
            val found = mutableListOf<String>()
            var heading = ""
            val body = mutableListOf<String>()
            fun flush() {
                val text = WHITESPACE.replace(body.joinToString(" "), " ")
                var from = text.indexOf(wanted)
                while (from >= 0) {
                    found += heading
                    from = text.indexOf(wanted, from + 1)
                }
                body.clear()
            }
            for (line in lines) {
                if (HEADING_LINE.containsMatchIn(line)) {
                    flush()
                    heading = line
                } else {
                    body += line
                }
            }
            flush()
            return found
        }

        /** Why [lines] does not state the rule once, in §8.7; `null` when it does. */
        fun problem(lines: List<String>): String? {
            for (sentence in SENTENCES) {
                val where = occurrences(lines, sentence)
                if (where.size != 1 || !where.single().startsWith(HOME_HEADING)) {
                    return "\"$sentence\" must be stated exactly once, in §8.7; found under $where"
                }
            }
            return null
        }
    }

    private val spec: List<String> = Section11.specificationLines()

    /** The live §8.7 paragraph carrying the rule: from its first sentence's opening line to the blank line. */
    private fun ruleParagraph(): IntRange {
        val home = spec.indexOfFirst { it.startsWith(CopyRule.HOME_HEADING) }
        assertTrue(home >= 0, "no §8.7 heading in ${Section11.specPath()}")
        val opening = CopyRule.SENTENCES.first().substringBefore(" (")
        val first = (home + 1 until spec.size).first { spec[it].startsWith(opening) }
        val last = (first until spec.size).first { spec[it].isBlank() } - 1
        return first..last
    }

    private fun insertedAfter(lines: List<String>, headingPrefix: String, block: List<String>): List<String> {
        val at = lines.indexOfFirst { it.startsWith(headingPrefix) }
        assertTrue(at >= 0, "no \"$headingPrefix\" heading to insert under")
        return lines.subList(0, at + 2) + block + "" + lines.subList(at + 2, lines.size)
    }

    @JsName("the_fee_recipient_copy_rule_is_stated_once_in_section_8_7")
    @Test
    fun `the fee recipient copy rule is stated once in section 8 7`() {
        assertNull(CopyRule.problem(spec), "in ${Section11.specPath()}")
        for (sentence in CopyRule.SENTENCES) {
            assertEquals(1, CopyRule.occurrences(spec, sentence).size, sentence)
        }
    }

    @JsName("deleting_rewording_moving_or_repeating_the_copy_rule_fails_the_anchor")
    @Test
    fun `deleting, rewording, moving or repeating the copy rule fails the anchor`() {
        val paragraph = ruleParagraph()
        val block = spec.slice(paragraph)
        val without = spec.filterIndexed { index, _ -> index !in paragraph }

        assertNotNull(CopyRule.problem(without), "the rule deleted")

        val softened = spec.mapIndexed { index, line ->
            if (index in paragraph) line.replace("the buyer MUST send", "the buyer SHOULD send") else line
        }
        assertTrue(softened != spec, "the control must change the text it claims to change")
        assertNotNull(CopyRule.problem(softened), "MUST softened to SHOULD")

        assertNotNull(CopyRule.problem(insertedAfter(without, "### 8.6 ", block)), "the rule moved to §8.6")
        assertNotNull(CopyRule.problem(insertedAfter(spec, "### 11.2 ", block)), "the rule repeated in §11.2")

        val rewrapped = insertedAfter(without, CopyRule.HOME_HEADING, listOf(block.joinToString(" ")))
        assertNull(CopyRule.problem(rewrapped), "the rule on one line is still the rule")
    }

    @JsName("the_transitions_psv_not_regenerated_for_1_14_fails_the_section_11_2_comparison")
    @Test
    fun `the transitions psv not regenerated for 1 14 fails the section 11 2 comparison`() {
        val live = Section11.transcribed()
        val added = ruleParagraph().count() + 1
        val stale = live.map { it.copy(specLine = it.specLine - added).toString() }
        val comparison = Section11.compare(spec, stale)
        assertFalse(comparison.agrees, "a psv still at revision 1.13's line numbers must disagree")
        assertEquals(live.toSet(), comparison.missing.toSet(), "every row is reported missing")

        assertTrue(Section11.compare(spec, Section11.transcribedLines()).agrees, "the live psv agrees")
    }

    @JsName("one_transitions_psv_row_moved_by_hand_fails_the_section_11_2_comparison")
    @Test
    fun `one transitions psv row moved by hand fails the section 11 2 comparison`() {
        val live = Section11.transcribed()
        val moved = live.first()
        val edited = live.map { if (it == moved) it.copy(specLine = it.specLine + 1) else it }
        val comparison = Section11.compare(spec, edited.map { it.toString() })
        assertFalse(comparison.agrees, "a row moved one line must disagree")
        assertEquals(setOf(moved, moved.copy(specLine = moved.specLine + 1)), comparison.named)
    }
}
