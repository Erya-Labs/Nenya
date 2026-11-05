package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.order.Section11
import dev.eryalabs.nenya.settlement.FeeRecipientCopyRuleTest.CopyRule
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revision `1.16`'s text (decision V): a buyer's fee-bearing order waits in `committed` for the fee
 * request, the wait is surfaced and the buyer is offered a cancellation, and a late fee request
 * advances nothing.
 *
 * Each sentence decision V writes into §8.8, and §7.5's clause on `expiration`, is held to its home
 * section, stated **exactly once** in the whole document, as [FeeNotEnforcedRuleTest] holds decision
 * U's. Matching collapses line breaks and runs of spaces through [CopyRule.occurrences], so re-wrapping
 * a paragraph keeps it green and changing one word does not.
 *
 * [problem] takes the specification as lines, so every control runs on text built here from the live
 * specification.
 */
class FeeRequestWaitRuleTest {

    /** Each sentence with the heading prefix of the section it belongs to, as NENYA-1 `1.16` states them. */
    private val sentences: List<Pair<String, String>> = listOf(
        "#### 7.5 " to "`expiration` is the acceptance deadline, and §11.2 also applies it to an order not yet `paid`.",
        "### 8.8 " to "A buyer's order whose `fee_msat` (§8.3) is not `0` does not reach `awaiting_payment` without " +
            "a valid fee request (`kind:16` `type=2`, `payee=fee`; §11.2).",
        "### 8.8 " to "While its order waits in `committed` for that request, the buyer SHOULD NOT pay the " +
            "provider's invoice: a cancellation does not recover a payment.",
        "### 8.8 " to "An implementation MUST surface that wait as the distinct, named condition *waiting for the " +
            "fee request*, never as a generic failure, and MUST offer the buyer a cancellation while it lasts.",
        "### 8.8 " to "§8.4 governs a fee request refused for its `fee` tag.",
        "### 8.8 " to "The buyer cancels by sending the provider §11.2's sealed `type=3`.",
        "### 8.8 " to "A fee request received once the order is `cancelled`, `expired` or `disputed` advances " +
            "nothing (§11.3 invariant 3) and is not paid (§8.5).",
        "### 8.8 " to "The buyer MAY then propose a new order (§7.5), with no fee or another fee recipient; the " +
            "provider need not accept it.",
        "### 8.8 " to "A new order made from a bid that carried a `fee` tag keeps that term (§8.4), so changing " +
            "the fee needs another bid or, on an offer, a proposal not made from a bid.",
    )

    private val spec: List<String> = Section11.specificationLines()

    private val headingLine = Regex("""^#{1,6} """)

    private val whitespace = Regex("""\s+""")

    /** Why [lines] does not state every sentence once, in its home section; `null` when it does. */
    private fun problem(lines: List<String>): String? {
        for ((home, sentence) in sentences) {
            val where = CopyRule.occurrences(lines, sentence)
            if (where.size != 1 || !where.single().startsWith(home)) {
                return "\"$sentence\" must be stated exactly once, under \"$home\"; found under $where"
            }
        }
        return null
    }

    /** [lines] with each paragraph on one line, whitespace collapsed; headings and blank lines kept. */
    private fun unwrapped(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += whitespace.replace(paragraph.joinToString(" "), " ").trim()
            paragraph.clear()
        }
        for (line in lines) {
            if (line.isBlank() || headingLine.containsMatchIn(line)) {
                flush()
                out += line
            } else {
                paragraph += line
            }
        }
        flush()
        return out
    }

    private fun insertedAfter(lines: List<String>, headingPrefix: String, sentence: String): List<String> {
        val at = lines.indexOfFirst { it.startsWith(headingPrefix) }
        assertTrue(at >= 0, "no \"$headingPrefix\" heading to insert under")
        return lines.subList(0, at + 2) + sentence + "" + lines.subList(at + 2, lines.size)
    }

    @JsName("decision_v_sentences_are_each_stated_once_in_their_home_sections")
    @Test
    fun `decision V sentences are each stated once in their home sections`() {
        assertNull(problem(spec), "in ${Section11.specPath()}")
        for ((_, sentence) in sentences) {
            assertEquals(1, CopyRule.occurrences(spec, sentence).size, sentence)
        }
        assertNull(problem(unwrapped(spec)), "the specification with every paragraph on one line")
    }

    @JsName("deleting_any_one_decision_v_sentence_fails_the_anchor")
    @Test
    fun `deleting any one decision V sentence fails the anchor`() {
        val flat = unwrapped(spec)
        for ((_, sentence) in sentences) {
            val without = flat.map { it.replace(sentence, "") }
            assertTrue(without != flat, "the control must delete \"$sentence\"")
            assertNotNull(problem(without), "\"$sentence\" deleted")
        }
    }

    @JsName("moving_or_repeating_a_decision_v_sentence_fails_the_anchor")
    @Test
    fun `moving or repeating a decision V sentence fails the anchor`() {
        val flat = unwrapped(spec)
        for ((_, sentence) in sentences) {
            val without = flat.map { it.replace(sentence, "") }
            assertNotNull(problem(insertedAfter(without, "## 13. ", sentence)), "\"$sentence\" moved to §13")
            assertNotNull(problem(insertedAfter(flat, "## 13. ", sentence)), "\"$sentence\" repeated in §13")
        }
    }
}
