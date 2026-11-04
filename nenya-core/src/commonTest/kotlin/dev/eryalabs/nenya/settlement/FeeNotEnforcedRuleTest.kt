package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.order.Section11
import dev.eryalabs.nenya.settlement.FeeRecipientCopyRuleTest.CopyRule
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Revision `1.15`'s text (decision U): a fee is not enforced against the buyer, a provider's release
 * needs only its own receipt, and §11's table is the buyer's order.
 *
 * Each sentence decision U writes into §8's lead, §9.2, §10.3 and §11's lead is held to its home
 * section, stated **exactly once** in the whole document, as [FeeRecipientCopyRuleTest] holds §8.7's.
 * Matching collapses line breaks and runs of spaces through [CopyRule.occurrences], so re-wrapping a
 * paragraph keeps it green and changing one word does not.
 *
 * [problem] takes the specification as lines, so every control runs on text built here from the live
 * specification.
 *
 * `capabilities()` says the same: item 5's note that the fee is not enforced, and item 6's that its
 * `paid` is the buyer's, are held in their own items' notes as `SettlementCheckOneTest` holds item 6's
 * `PaymentRequestStore.inMemory`.
 */
class FeeNotEnforcedRuleTest {

    /** Each sentence with the heading prefix of the section it belongs to, as NENYA-1 `1.15` states them. */
    private val sentences: List<Pair<String, String>> = listOf(
        "## 8. " to "A fee is not enforced against the buyer.",
        "## 8. " to "A provider need not see the fee paid before it releases the deliverable, and a buyer that " +
            "does not pay the fee can still receive the deliverable.",
        "## 8. " to "§9.2 states which receipts each needs.",
        "### 9.2 " to "A buyer's order becomes `paid` only when each payee named in the terms with a non-zero " +
            "expected amount (§8.3) has a verified receipt.",
        "### 9.2 " to "A provider's release needs only a verified receipt for its `payee=provider` payment request, " +
            "when `price_msat` is not `0`.",
        "### 9.2 " to "The six checks do not bind a fee recipient in that role (§11).",
        "### 10.3 " to "Sent by the provider, as a NIP-17 file message, once the payment evidence §9.2 requires " +
            "for a release has verified.",
        "### 10.3 " to "A buyer MAY hold a release received before its order is `paid` and apply it when the order " +
            "becomes `paid`, before it evaluates the `paid → disputed` deadline; this is an exception to §4.6's " +
            "receipt sequence.",
        "## 11. " to "§11.1 and §11.2 state the buyer's order.",
        "## 11. " to "A provider MAY keep an order by the same table; where `fee_msat` is not `0`, that order does " +
            "not pass `committed` without the fee request, and the release does not depend on it (§9.2).",
        "## 11. " to "A fee recipient holds no order state in that role.",
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

    @JsName("decision_u_sentences_are_each_stated_once_in_their_home_sections")
    @Test
    fun `decision U sentences are each stated once in their home sections`() {
        assertNull(problem(spec), "in ${Section11.specPath()}")
        for ((_, sentence) in sentences) {
            assertEquals(1, CopyRule.occurrences(spec, sentence).size, sentence)
        }
        assertNull(problem(unwrapped(spec)), "the specification with every paragraph on one line")
    }

    @JsName("deleting_any_one_decision_u_sentence_fails_the_anchor")
    @Test
    fun `deleting any one decision U sentence fails the anchor`() {
        val flat = unwrapped(spec)
        for ((_, sentence) in sentences) {
            val without = flat.map { it.replace(sentence, "") }
            assertTrue(without != flat, "the control must delete \"$sentence\"")
            assertNotNull(problem(without), "\"$sentence\" deleted")
        }
    }

    @JsName("moving_or_repeating_a_decision_u_sentence_fails_the_anchor")
    @Test
    fun `moving or repeating a decision U sentence fails the anchor`() {
        val flat = unwrapped(spec)
        for ((_, sentence) in sentences) {
            val without = flat.map { it.replace(sentence, "") }
            assertNotNull(problem(insertedAfter(without, "## 13. ", sentence)), "\"$sentence\" moved to §13")
            assertNotNull(problem(insertedAfter(flat, "## 13. ", sentence)), "\"$sentence\" repeated in §13")
        }
    }

    /** The opening of each sentence decision U adds to a §17 item's `capabilities()` note, with the item's number. */
    private val capabilityOpenings: List<Pair<Int, String>> = listOf(
        5 to "The fee is not enforced against the buyer;",
        6 to "This is the buyer's `paid`.",
        6 to "A provider's release rests on the evidence `PaymentWriter.release` takes",
    )

    private val notes: Map<Int, String> = capabilityOpenings.map { it.first }.toSet().associateWith { number ->
        Capabilities.item(number)?.note ?: fail("§17 item $number is not published")
    }

    /** Why [notes] does not hold every opening in its own item's note; `null` when it does. */
    private fun capabilityProblem(notes: Map<Int, String>): String? {
        for ((number, opening) in capabilityOpenings) {
            if (!whitespace.replace(notes.getValue(number), " ").contains(opening)) {
                return "item $number's note must say \"$opening\""
            }
        }
        return null
    }

    @JsName("capabilities_items_5_and_6_say_the_fee_is_not_enforced_and_whose_paid_it_is")
    @Test
    fun `capabilities items 5 and 6 say the fee is not enforced and whose paid it is`() {
        assertNull(capabilityProblem(notes))
    }

    @JsName("either_capabilities_sentence_removed_fails")
    @Test
    fun `either capabilities sentence removed fails`() {
        for ((number, opening) in capabilityOpenings) {
            val without = notes + (number to notes.getValue(number).replace(opening, ""))
            assertTrue(without != notes, "the control must delete \"$opening\"")
            assertNotNull(capabilityProblem(without), "\"$opening\" removed from item $number")
        }
    }
}
