package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.VendoredTestFiles
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §18's "Preimage verification" and "Fee arithmetic" rows name cases an implementation SHOULD pin, and
 * each one is held here to the tests in this repository that prove it, as [StateMachineCasesTest] holds
 * the "State machine" row.
 *
 * The preimage row names a known-preimage case, then five negative controls after `negative controls: `,
 * separated by `, `. The fee row names five cases after `boundary cases at `, separated by `, ` outside
 * parentheses — the fifth carries a `, ` inside its own — the last opening `and `. [preimageCases] and
 * [feeCases] read them from the vendored specification through [StateMachineCasesTest.rowAfter], and
 * [PREIMAGE_CASES] and [FEE_CASES] pin each one, by exact set equality, to one or more `file#JsName`
 * entries named from [StateMachineCasesTest.TEST_ROOT]. A case the specification adds or drops turns
 * this red until the map says which test proves it.
 *
 * That each `@JsName` really occurs in its file is checked in jvmTest by `StateMachineCasesSourceTest`.
 */
class MoneyVectorCasesTest {

    internal companion object {

        const val PREIMAGE_ROW: String = "| Preimage verification | "

        const val PREIMAGE_MARKER: String = "negative controls: "

        /** What ends the known-preimage case, before [PREIMAGE_MARKER]. */
        const val PREIMAGE_JOINER: String = ", **plus** "

        const val FEE_ROW: String = "| Fee arithmetic |"

        const val FEE_MARKER: String = "boundary cases at "

        /** Each preimage case, as revision `1.15` states it, to the `file#JsName` of every test proving it. */
        val PREIMAGE_CASES: Map<String, List<String>> = mapOf(
            "invoices with known preimages" to listOf(
                "settlement/SettlementCheckOneTest.kt#a_receipt_matching_its_stored_request_evidences_the_payment",
            ),
            "wrong preimage" to listOf(
                "payment/PaymentEvidenceTest.kt#a_preimage_that_is_thirty_two_bytes_but_wrong_is_rejected_as_a_mismatch",
            ),
            "31-byte preimage" to listOf(
                "payment/PaymentEvidenceTest.kt#a_thirty_one_byte_preimage_is_rejected_as_the_wrong_length_never_padded",
            ),
            "33-byte preimage" to listOf(
                "payment/PaymentEvidenceTest.kt#a_thirty_three_byte_preimage_is_rejected_as_the_wrong_length_never_truncated",
            ),
            "uppercase hex" to listOf(
                "payment/PaymentEvidenceTest.kt#the_correct_preimage_in_uppercase_hex_is_rejected_as_uppercase_not_as_malformed",
                "settlement/SettlementCheckOneTest.kt#an_uppercase_proof_is_refused_as_check_2s_case_rule",
            ),
            "preimage of the wrong invoice" to listOf(
                "settlement/SettlementCheckOneTest.kt#a_preimage_for_another_invoice_against_the_stored_one_yields_t3s_own_refusal",
            ),
        )

        /** Each fee case, as revision `1.15` states it, to the `file#JsName` of every test proving it. */
        val FEE_CASES: Map<String, List<String>> = mapOf(
            "`bps = 0`" to listOf(
                "money/FeeTest.kt#at_0_bps_the_fee_is_zero_at_every_price_up_to_the_supply_cap",
            ),
            "`bps = 10000`" to listOf(
                "money/FeeTest.kt#at_10000_bps_the_fee_is_exactly_the_price_and_the_total_twice_it",
                "money/FeeTest.kt#t_10000_bps_is_accepted_and_10001_is_rejected_as_above_the_maximum",
            ),
            "the msat rounding example in §8.3" to listOf(
                "money/FeeTest.kt#the_1_001_sat_at_137_bps_rounding_example_reproduces_exactly_rounding_down",
            ),
            "an overflow probe at the supply cap" to listOf(
                "money/FeeTest.kt#at_the_supply_cap_and_10000_bps_the_fee_is_the_whole_cap_where_the_naive_product_wraps",
            ),
            "the non-zero-`bps`-but-zero-`fee_msat` case (`bps = 1`, `price_msat = 3000`)" to listOf(
                "money/FeeTest.kt#a_non_zero_bps_can_compute_to_a_zero_fee_and_then_no_fee_payee_is_required",
            ),
        )

        /**
         * The cases of [spec]'s one "Preimage verification" row: the known-preimage case, then each
         * negative control, in order.
         *
         * Refuses rather than guesses: besides [StateMachineCasesTest.rowAfter]'s refusals, a known case
         * not ending in [PREIMAGE_JOINER], a [PREIMAGE_MARKER] absent or repeated, or an empty case throws.
         */
        fun preimageCases(spec: String): List<String> {
            val body = StateMachineCasesTest.rowAfter(spec, PREIMAGE_ROW, PREIMAGE_ROW)
            val at = body.indexOf(PREIMAGE_MARKER)
            require(at >= 0 && body.indexOf(PREIMAGE_MARKER, at + 1) < 0) {
                "expected \"$PREIMAGE_MARKER\" exactly once in the row"
            }
            val known = body.substring(0, at)
            require(known.endsWith(PREIMAGE_JOINER)) { "the known-preimage case must end \"$PREIMAGE_JOINER\"" }
            val cases = listOf(known.removeSuffix(PREIMAGE_JOINER)) +
                body.substring(at + PREIMAGE_MARKER.length).split(", ")
            require(cases.none { it.isBlank() }) { "an empty case in the row" }
            return cases
        }

        /**
         * The cases of [spec]'s one "Fee arithmetic" row, in order, the last with its `and ` removed,
         * the row split by [split] — [splitOutsideParentheses] unless a control says otherwise.
         *
         * Refuses rather than guesses: besides [StateMachineCasesTest.rowAfter]'s refusals and [split]'s,
         * a last case not opening `and ` or an empty case throws.
         */
        fun feeCases(spec: String, split: (String) -> List<String> = { splitOutsideParentheses(it) }): List<String> {
            val cases = split(StateMachineCasesTest.rowAfter(spec, FEE_ROW, FEE_MARKER)).toMutableList()
            require(cases.size >= 2 && cases.last().startsWith("and ")) { "the last case must open \"and \"" }
            cases[cases.lastIndex] = cases.last().removePrefix("and ")
            require(cases.none { it.isBlank() }) { "an empty case in the row" }
            return cases
        }

        /** [text] split at every `, ` not inside parentheses. Unbalanced parentheses throw. */
        fun splitOutsideParentheses(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            var i = 0
            while (i < text.length) {
                when {
                    text[i] == '(' -> depth++
                    text[i] == ')' -> {
                        depth--
                        require(depth >= 0) { "a ')' with no '(' at $i" }
                    }
                    depth == 0 && text.startsWith(", ", i) -> {
                        parts += text.substring(start, i)
                        start = i + 2
                        i++
                    }
                }
                i++
            }
            require(depth == 0) { "a '(' never closed" }
            parts += text.substring(start)
            return parts
        }

        private val vendored: String by lazy { VendoredTestFiles.text("spec/NENYA-1.md") }
    }

    @JsName("the_preimage_row_s_cases_are_exactly_the_pinned_cases")
    @Test
    fun `the preimage row's cases are exactly the pinned cases`() {
        val cases = preimageCases(vendored)
        assertEquals(6, cases.size, "revision 1.15's row names a known-preimage case and five negative controls")
        assertEquals(cases.size, cases.toSet().size, "no case is named twice")
        assertEquals(PREIMAGE_CASES.keys, cases.toSet())
    }

    @JsName("the_fee_row_s_cases_are_exactly_the_pinned_cases")
    @Test
    fun `the fee row's cases are exactly the pinned cases`() {
        val cases = feeCases(vendored)
        assertEquals(5, cases.size, "revision 1.15's row names five boundary cases")
        assertEquals(cases.size, cases.toSet().size, "no case is named twice")
        assertEquals(FEE_CASES.keys, cases.toSet())
    }

    @JsName("every_money_case_names_at_least_one_well_formed_test")
    @Test
    fun `every money case names at least one well-formed test`() {
        for ((case, tests) in PREIMAGE_CASES + FEE_CASES) {
            assertTrue(tests.isNotEmpty(), "\"$case\" names no test")
            for (entry in tests) {
                assertTrue(Regex("""^[a-z]+/[A-Za-z]+Test\.kt#[A-Za-z0-9_]+$""").matches(entry), "malformed entry $entry")
            }
            assertEquals(tests.size, tests.toSet().size, "\"$case\" names a test twice")
        }
    }

    /** The vendored specification with its one line opening [row] passed through [edit], which must change it. */
    private fun withRow(row: String, edit: (String) -> String): String {
        val line = vendored.lines().single { it.startsWith(row) }
        val edited = edit(line)
        assertNotEquals(line, edited, "the control must change the row")
        return vendored.replace(line, edited)
    }

    @JsName("a_case_added_to_either_row_fails")
    @Test
    fun `a case added to either row fails`() {
        val preimage = withRow(PREIMAGE_ROW) { it.replace("uppercase hex, ", "uppercase hex, a 32-byte preimage of all zeros, ") }
        assertEquals(7, preimageCases(preimage).size)
        assertNotEquals(PREIMAGE_CASES.keys, preimageCases(preimage).toSet())

        val fee = withRow(FEE_ROW) { it.replace(", and ", ", `bps = 5000`, and ") }
        assertEquals(6, feeCases(fee).size)
        assertNotEquals(FEE_CASES.keys, feeCases(fee).toSet())
    }

    @JsName("a_case_removed_from_either_row_fails")
    @Test
    fun `a case removed from either row fails`() {
        for (case in preimageCases(vendored).drop(1).dropLast(1)) {
            val removed = withRow(PREIMAGE_ROW) { it.replace("$case, ", "") }
            assertEquals(5, preimageCases(removed).size, "\"$case\" removed")
            assertNotEquals(PREIMAGE_CASES.keys, preimageCases(removed).toSet(), "\"$case\" removed")
        }
        val lastControl = withRow(PREIMAGE_ROW) { it.replace(", preimage of the wrong invoice", "") }
        assertNotEquals(PREIMAGE_CASES.keys, preimageCases(lastControl).toSet(), "the last control removed")

        for (case in feeCases(vendored).dropLast(1)) {
            val removed = withRow(FEE_ROW) { it.replace("$case, ", "") }
            assertEquals(4, feeCases(removed).size, "\"$case\" removed")
            assertNotEquals(FEE_CASES.keys, feeCases(removed).toSet(), "\"$case\" removed")
        }
    }

    @JsName("a_parser_splitting_inside_parentheses_yields_six_fee_cases_and_fails")
    @Test
    fun `a parser splitting inside parentheses yields six fee cases and fails`() {
        val naive = StateMachineCasesTest.rowAfter(vendored, FEE_ROW, FEE_MARKER).split(", ")
        assertEquals(6, naive.size, "the fifth case's \", \" inside its parentheses splits it in two")
        assertNotEquals(FEE_CASES.keys, naive.toSet())
        // Through the parser the sixth piece, the fifth case's tail, is the last and does not open `and `.
        assertFailsWith<IllegalArgumentException> { feeCases(vendored) { it.split(", ") } }
        assertEquals(5, feeCases(vendored).size, "splitting outside parentheses keeps it whole")
    }

    @JsName("a_money_row_the_parsers_cannot_read_is_refused_never_guessed")
    @Test
    fun `a money row the parsers cannot read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException>("no preimage row") { preimageCases(withRow(PREIMAGE_ROW) { "" }) }
        assertFailsWith<IllegalArgumentException>("two preimage rows") { preimageCases(withRow(PREIMAGE_ROW) { it + "\n" + it }) }
        assertFailsWith<IllegalArgumentException>("no negative-controls marker") {
            preimageCases(withRow(PREIMAGE_ROW) { it.replace(PREIMAGE_MARKER, "") })
        }
        assertFailsWith<IllegalArgumentException>("no **plus** joiner") {
            preimageCases(withRow(PREIMAGE_ROW) { it.replace(PREIMAGE_JOINER, " ") })
        }
        assertFailsWith<IllegalArgumentException>("an empty preimage case") {
            preimageCases(withRow(PREIMAGE_ROW) { it.replace("uppercase hex, ", "uppercase hex, , ") })
        }

        assertFailsWith<IllegalArgumentException>("no fee row") { feeCases(withRow(FEE_ROW) { "" }) }
        assertFailsWith<IllegalArgumentException>("no boundary marker") { feeCases(withRow(FEE_ROW) { it.replace(FEE_MARKER, "") }) }
        assertFailsWith<IllegalArgumentException>("no last `and `") { feeCases(withRow(FEE_ROW) { it.replace(", and ", ", ") }) }
        assertFailsWith<IllegalArgumentException>("an empty fee case") { feeCases(withRow(FEE_ROW) { it.replace(", and ", ", , and ") }) }
        assertFailsWith<IllegalArgumentException>("an unclosed parenthesis") {
            feeCases(withRow(FEE_ROW) { it.replace("`price_msat = 3000`)", "`price_msat = 3000`") })
        }
        assertFailsWith<IllegalArgumentException>("a stray closing parenthesis") {
            feeCases(withRow(FEE_ROW) { it.replace("(`bps = 1`", "`bps = 1`)") })
        }
    }
}
