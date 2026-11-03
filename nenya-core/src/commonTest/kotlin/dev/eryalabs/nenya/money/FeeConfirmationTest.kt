package dev.eryalabs.nenya.money

import dev.eryalabs.nenya.SpecAnchor
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §8.1's confirmation line: "Above `500` bps, an implementation MUST require explicit user
 * confirmation of the fee … before proposing or accepting the order."
 *
 * [FeeTerm.confirmationRequired] answers it and [FeeTerm.CONFIRMATION_ABOVE_BPS] holds the figure.
 * The figure is read out of the specification's own sentence at test time rather than written here a
 * second time, so a revision that moved the line turns this red instead of leaving a page asking at
 * the old one.
 */
class FeeConfirmationTest {

    private companion object {

        /** §8.1's sentence, the figure captured. Whitespace runs stand for a wrapped line. */
        val SENTENCE = Regex("""Above `(\d+)` bps, an implementation MUST require explicit user\s+confirmation""")

        /** §8.1's heading, and the next heading of the same depth, which ends the section. */
        val SECTION_8_1 = Regex("""^### 8\.1 """)
        val NEXT_SECTION = Regex("""^### """)

        /**
         * The figure §8.1's sentence states, read from [lines] — taken as a parameter so a control can
         * feed an edited document. Fails unless the sentence appears exactly once in the whole document
         * and that once is inside §8.1.
         */
        fun publishedThreshold(lines: List<String>): Int {
            val joined = lines.joinToString(" ")
            val everywhere = SENTENCE.findAll(joined).toList()
            assertEquals(
                1,
                everywhere.size,
                "the specification must state §8.1's confirmation line exactly once; found ${everywhere.size}",
            )
            val start = lines.indexOfFirst { SECTION_8_1.containsMatchIn(it) }
            assertTrue(start >= 0, "no §8.1 heading in the specification")
            val end = (start + 1 until lines.size).firstOrNull { NEXT_SECTION.containsMatchIn(lines[it]) } ?: lines.size
            val inSection = SENTENCE.findAll(lines.subList(start, end).joinToString(" ")).toList()
            assertEquals(1, inSection.size, "§8.1's confirmation line must be inside §8.1")
            return inSection.single().groupValues[1].toInt()
        }

        /** The check this file makes against the document: the constant is the figure. */
        fun assertConstantIsPublished(lines: List<String>) {
            assertEquals(
                publishedThreshold(lines),
                FeeTerm.CONFIRMATION_ABOVE_BPS,
                "FeeTerm.CONFIRMATION_ABOVE_BPS must be the figure §8.1's sentence states",
            )
        }

        fun specLines(): List<String> = SpecAnchor.specFile().readLines()
    }

    @JsName("the_confirmation_constant_is_the_figure_section_8_1_states")
    @Test
    fun `the confirmation constant is the figure section 8_1 states`() {
        assertConstantIsPublished(specLines())
    }

    @JsName("the_anchor_fails_when_section_8_1_states_another_figure")
    @Test
    fun `the anchor fails when section 8_1 states another figure`() {
        val other = FeeTerm.CONFIRMATION_ABOVE_BPS + 1
        val edited = specLines().map { it.replace(Regex("""Above `\d+` bps"""), "Above `$other` bps") }
        assertTrue(edited != specLines(), "the edit must have changed the document")
        assertFailsWith<AssertionError> { assertConstantIsPublished(edited) }
    }

    @JsName("the_anchor_fails_when_the_sentence_is_missing_or_stated_twice")
    @Test
    fun `the anchor fails when the sentence is missing or stated twice`() {
        val lines = specLines()
        val at = lines.indexOfFirst { SENTENCE.containsMatchIn(it) }
        assertTrue(at >= 0, "the sentence's first line must be found")

        val missing = lines.filterIndexed { index, _ -> index != at }
        assertFailsWith<AssertionError> { publishedThreshold(missing) }

        val twice = lines.subList(0, at + 2) + lines.subList(at, lines.size)
        assertFailsWith<AssertionError> { publishedThreshold(twice) }

        // Moved out of §8.1, to the end of the document: still once, no longer in §8.1.
        val moved = missing + lines.subList(at, at + 2)
        assertFailsWith<AssertionError> { publishedThreshold(moved) }
    }

    @JsName("five_hundred_bps_needs_no_confirmation_and_five_hundred_and_one_does")
    @Test
    fun `five hundred bps needs no confirmation and five hundred and one does`() {
        assertFalse(FeeTerm.of(500).confirmationRequired, "§8.1: above 500, so 500 itself is not")
        assertTrue(FeeTerm.of(501).confirmationRequired)
        assertTrue(FeeTerm.of(FeeTerm.MAX_BASIS_POINTS).confirmationRequired, "10000 bps")
        assertFalse(FeeTerm.of(1).confirmationRequired)
    }

    @JsName("no_fee_term_and_a_stated_zero_need_no_confirmation")
    @Test
    fun `no fee term and a stated zero need no confirmation`() {
        assertFalse(FeeTerm.Absent.confirmationRequired, "an absent term is zero fee (§8.1)")
        assertFalse(FeeTerm.of(0).confirmationRequired)
    }

    @JsName("the_answer_turns_at_five_hundred_across_the_whole_range")
    @Test
    fun `the answer turns at five hundred across the whole range`() {
        for (bps in 0..FeeTerm.MAX_BASIS_POINTS) {
            assertEquals(bps > 500, FeeTerm.of(bps).confirmationRequired, "at $bps bps")
        }
    }
}
