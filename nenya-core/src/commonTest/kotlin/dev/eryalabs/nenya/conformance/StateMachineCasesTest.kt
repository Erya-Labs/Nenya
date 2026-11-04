package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.VendoredTestFiles
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §18's "State machine" row names cases an implementation SHOULD include, and each one is held here
 * to the tests in this repository that prove it.
 *
 * The row's cases follow "Include as named cases:", separated by `; `, the last opening `and `.
 * [namedCases] reads them from the vendored specification and [CASES] pins each one, by exact set
 * equality, to one or more `file#JsName` entries, the file named from [TEST_ROOT]. A case the
 * specification adds or drops turns this red until the map says which test proves it.
 *
 * That each `@JsName` really occurs in its file is checked in jvmTest by `StateMachineCasesSourceTest`,
 * which reads the test sources from disk; a common test cannot.
 *
 * [namedCases] takes the specification as text, so every control runs on text built here from the live
 * specification.
 */
class StateMachineCasesTest {

    internal companion object {

        /** Where every [CASES] entry's file is named from, relative to the module directory. */
        const val TEST_ROOT: String = "src/commonTest/kotlin/dev/eryalabs/nenya"

        const val ROW: String = "| State machine |"

        const val MARKER: String = "Include as named cases: "

        /** Each named case, as revision `1.15` states it, to the `file#JsName` of every test proving it. */
        val CASES: Map<String, List<String>> = mapOf(
            "a buyer's fee-bearing order reaching `awaiting_payment` and then `paid` (the §8.5 deadlock probe)" to listOf(
                "order/OrderControlsTest.kt#a_fee_bearing_order_reaches_awaiting_payment_and_then_paid",
            ),
            "a provider on the same terms releasing on its own receipt with no fee invoice or fee receipt" to listOf(
                "js/JsTradeWalkthroughTest.kt#with_no_fee_invoice_the_provider_s_own_receipt_is_evidenced_and_its_release_builds",
            ),
            "a buyer's order holding only the provider's receipt staying in `awaiting_payment`" to listOf(
                "order/OrderControlsTest.kt#an_incomplete_receipt_set_leaves_the_order_awaiting_payment",
                "js/JsTradeWalkthroughTest.kt#a_fee_bearing_trade_with_the_fee_unpaid_stays_awaiting_payment_and_refuses_the_release",
            ),
            "an order whose `fee_msat` computes to `0` reaching `paid` with no fee invoice (the §8.3 deadlock probe)" to listOf(
                "order/OrderControlsTest.kt#a_zero_fee_order_needs_no_fee_receipt_and_reaches_paid",
            ),
            "a `type=1` with no `fee` tag opening at zero fee" to listOf(
                "order/OrderControlsTest.kt#a_proposal_with_no_fee_tag_opens_at_zero_fee_and_is_not_refused_as_incomplete",
                "channel/ProposalCodecTest.kt#a_proposal_with_no_fee_tag_opens_at_zero_fee_and_is_not_a_stated_zero",
            ),
            "a `type=6` and a `kind:14` each advancing nothing from every state" to listOf(
                "order/OrderControlsTest.kt#a_private_bid_a_chat_message_and_a_type_4_advance_nothing_from_every_state",
            ),
            "an order in `released` whose served blob was refused for its length — which is no hash mismatch " +
                "(§10.4) — reaching `disputed` at the verification deadline, probed one second before the " +
                "deadline, at it exactly, and one second after" to listOf(
                    "order/VerificationDeadlineTest.kt#a_served_blob_of_the_wrong_length_is_no_hash_mismatch_and_leaves_released_at_the_deadline",
                ),
            "the same order under each fallback anchor in turn, with the candidate deadlines chosen so that " +
                "anchoring on the wrong one would be visible" to listOf(
                    "order/VerificationDeadlineTest.kt#the_deadline_is_anchored_on_the_moment_of_release",
                    "order/VerificationDeadlineTest.kt#with_no_reading_at_release_the_deadline_falls_back_to_paidat",
                    "order/VerificationDeadlineTest.kt#with_no_reading_at_paid_either_the_deadline_falls_back_to_deliver_by",
                ),
            "an order with no clock reading at `paid`, none at release and no `deliver_by`, reporting that it " +
                "has no deadline to check rather than that it is pending" to listOf(
                    "order/VerificationDeadlineTest.kt#with_no_reading_anywhere_and_no_deliver_by_there_is_no_deadline_to_check",
                ),
        )

        /**
         * The named cases of [spec]'s one "State machine" row, in order, the last with its `and ` removed.
         *
         * Refuses rather than guesses: no such row or two of them, a marker absent or repeated, a row not
         * ending in ` |`, an empty case or a last case not opening `and ` each throws.
         */
        fun namedCases(spec: String): List<String> {
            val cases = rowAfter(spec, ROW, MARKER).split("; ").toMutableList()
            require(cases.size >= 2 && cases.last().startsWith("and ")) { "the last case must open \"and \"" }
            cases[cases.lastIndex] = cases.last().removePrefix("and ")
            require(cases.none { it.isBlank() }) { "an empty case in the row" }
            return cases
        }

        /**
         * The text of [spec]'s one line opening [row], after its one [marker], with the closing ` |` removed.
         *
         * Shared with `MoneyVectorCasesTest`. Refuses rather than guesses: no such row or two of them, a
         * marker absent or repeated, or a row not ending in ` |` each throws.
         */
        fun rowAfter(spec: String, row: String, marker: String): String {
            val rows = spec.lines().filter { it.startsWith(row) }
            require(rows.size == 1) { "expected one \"$row\" row, found ${rows.size}" }
            val line = rows.single()
            val at = line.indexOf(marker)
            require(at >= 0 && line.indexOf(marker, at + 1) < 0) { "expected \"$marker\" exactly once in the row" }
            val body = line.substring(at + marker.length).trimEnd()
            require(body.endsWith(" |")) { "the row must end in \" |\"" }
            return body.removeSuffix(" |")
        }

        private val vendored: String by lazy { VendoredTestFiles.text("spec/NENYA-1.md") }
    }

    @JsName("the_row_s_named_cases_are_exactly_the_pinned_cases")
    @Test
    fun `the row's named cases are exactly the pinned cases`() {
        val cases = namedCases(vendored)
        assertEquals(9, cases.size, "revision 1.15's row names nine cases")
        assertEquals(cases.size, cases.toSet().size, "no case is named twice")
        assertEquals(CASES.keys, cases.toSet())
    }

    @JsName("every_case_names_at_least_one_well_formed_test")
    @Test
    fun `every case names at least one well-formed test`() {
        for ((case, tests) in CASES) {
            assertTrue(tests.isNotEmpty(), "\"$case\" names no test")
            for (entry in tests) {
                assertTrue(Regex("""^[a-z]+/[A-Za-z]+Test\.kt#[A-Za-z0-9_]+$""").matches(entry), "malformed entry $entry")
            }
            assertEquals(tests.size, tests.toSet().size, "\"$case\" names a test twice")
        }
    }

    /** The vendored specification with its one "State machine" row passed through [edit], which must change it. */
    private fun withRow(edit: (String) -> String): String {
        val row = vendored.lines().single { it.startsWith(ROW) }
        val edited = edit(row)
        assertNotEquals(row, edited, "the control must change the row")
        return vendored.replace(row, edited)
    }

    @JsName("a_case_added_to_the_row_fails")
    @Test
    fun `a case added to the row fails`() {
        val added = withRow { it.replace("; and ", "; an order reaching `settled` twice; and ") }
        assertEquals(10, namedCases(added).size)
        assertNotEquals(CASES.keys, namedCases(added).toSet())
    }

    @JsName("a_case_removed_from_the_row_fails")
    @Test
    fun `a case removed from the row fails`() {
        for (case in namedCases(vendored).dropLast(1)) {
            val removed = withRow { it.replace("$case; ", "") }
            assertEquals(8, namedCases(removed).size, "\"$case\" removed")
            assertNotEquals(CASES.keys, namedCases(removed).toSet(), "\"$case\" removed")
        }
    }

    @JsName("a_row_the_parser_cannot_read_is_refused_never_guessed")
    @Test
    fun `a row the parser cannot read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException>("no row") { namedCases(withRow { "" }) }
        assertFailsWith<IllegalArgumentException>("two rows") { namedCases(withRow { it + "\n" + it }) }
        assertFailsWith<IllegalArgumentException>("no marker") { namedCases(withRow { it.replace(MARKER, "") }) }
        assertFailsWith<IllegalArgumentException>("no last `and `") { namedCases(withRow { it.replace("; and ", "; ") }) }
        assertFailsWith<IllegalArgumentException>("an empty case") { namedCases(withRow { it.replace("; and ", "; ; and ") }) }
    }
}
