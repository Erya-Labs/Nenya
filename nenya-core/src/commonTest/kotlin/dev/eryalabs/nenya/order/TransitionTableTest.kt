package dev.eryalabs.nenya.order

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The transcribed §11.2 table is checked for **completeness**, not for plausibility.
 *
 * That distinction is the whole of this file. A test that only proves each transcribed line
 * is real still passes over a file carrying five of §11.2's transitions — and T7's totality
 * proof is measured against that same file, so "the state machine is total" would become
 * true of a machine missing three quarters of its edges, with §17 item 8's conformance claim
 * resting on it.
 *
 * So the assertion is **set equality** against §11.2's own row block, parsed out of
 * `spec/NENYA-1.md` at test time by [Section11]: not that the file is a subset of the
 * specification, and not that the specification is a subset of the file. Deleting a
 * transcribed line, adding one, or shifting a line number by one all turn it red.
 *
 * No count is transcribed anywhere. Counts fall out of the two parses, because a file
 * asserted against a header comment the same hand wrote proves only that the file agrees
 * with itself.
 */
class TransitionTableTest {

    private companion object {

        /**
         * What tells §11.2's two `released → disputed` rows apart, in the specification's own
         * words: one names the verification deadline and the other names a hash mismatch.
         *
         * A phrase and not a line number, so the control below survives every edit that moves
         * the row and turns red for the one edit that removes the rule.
         */
        const val VERIFICATION_DEADLINE_MARK: String = "verification deadline"
    }

    private val spec = Section11.transitions

    private val file = Section11.transcribed()

    // ---------------------------------------------------------------- non-vacuity floor

    /**
     * "Empty equals empty" must not be a passing state, so both sides are asserted non-empty
     * before any equality is claimed of them. A restructured §11.2, a moved heading or a
     * missing data file would otherwise satisfy every assertion below.
     */
    @JsName("the_specification_parse_yields_a_non_zero_number_of_transitions")
    @Test
    fun `the specification parse yields a non-zero number of transitions`() {
        assertTrue(
            spec.isNotEmpty(),
            "§11.2's row block in ${Section11.specPath()} parsed to no transitions at all",
        )
    }

    @JsName("the_transcribed_file_yields_a_non_zero_number_of_transitions")
    @Test
    fun `the transcribed file yields a non-zero number of transitions`() {
        assertTrue(file.isNotEmpty(), "the transcribed §11.2 table carries no data lines")
    }

    /**
     * Set equality alone would let a duplicated line hide: two copies of one transition and
     * a missing one are indistinguishable once both are collapsed into a set.
     */
    @JsName("no_transition_is_transcribed_twice")
    @Test
    fun `no transition is transcribed twice`() {
        val duplicates = file.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "these lines appear more than once: $duplicates")
    }

    // ---------------------------------------------------------------- the completeness proof

    /**
     * The headline. Both directions, over `(spec line, from, to)` triples, so the line
     * numbers a reviewer diffs by eye are inside the proof rather than beside it.
     *
     * The diff itself now lives in [Section11.compare], a function of
     * `(specification text, psv lines)`, so that the stale-psv control below is measured by
     * **this** comparison rather than by a second implementation of it. The refactor moved
     * the arithmetic and removed no assertion: both directions are still reported by name
     * and the set equality is still claimed.
     */
    @JsName("the_transcription_equals_section_11_2_triple_for_triple")
    @Test
    fun `the transcription equals section 11 2 triple for triple`() {
        val comparison =
            Section11.compare(Section11.specificationLines(), Section11.transcribedLines())

        assertTrue(comparison.agrees, comparison.report(Section11.specPath()))
        assertEquals(spec, comparison.transcribed.toSet())
    }

    /**
     * The stale-psv control: a psv as it would read if this revision's new §11.2 line had
     * never been added is caught by the same comparison the test above runs.
     *
     * Without it, "the suite would go red if somebody edited the specification and forgot the
     * psv" is a claim about a test rather than a demonstration of one — and it is the claim
     * the Definition of done's transcription rule rests on.
     *
     * ### Everything here is derived from the live files, so no later revision can turn it red
     *
     * Nothing below is a pinned line number, a count, or a copy of either file. The inserted
     * row is found by its `(from, to)` pair **and** by the text of the specification line it
     * transcribes — there are two `released → disputed` rows now, and only one of them is the
     * verification deadline's. The stale psv is then built from the live one: that row removed,
     * and every row below it shifted back a line, which is what the file said before the line
     * existed. T29 and every later specification edit move these lines and the control moves
     * with them.
     *
     * ### What is asserted, and what is deliberately not
     *
     * Two things. The comparison **fails**; and its failure **names** every row the shift made
     * stale, computed here from the live psv rather than written down. The new row is last in
     * §11.2's table today, so no row follows it and that set is empty until a later revision
     * adds one below — which is why the assertion is made over the union with the removed row,
     * the one the failure certainly names today. Asserting the exact failure set would be
     * asserting a count, which is the thing this file refuses to do everywhere else.
     */
    @JsName("a_psv_missing_this_revision_s_new_line_fails_the_same_comparison")
    @Test
    fun `a psv missing this revision's new line fails the same comparison`() {
        val specification = Section11.specificationLines()
        val live = Section11.transcribed()

        // Found by pair and by text, never by line number: §11.2 carries two `released →
        // disputed` rows and `single` is what proves the text tells them apart.
        val inserted = live.single {
            it.from == "released" &&
                it.to == "disputed" &&
                VERIFICATION_DEADLINE_MARK in Section11.specLine(it.specLine)
        }
        val staleRows = live
            .filter { it != inserted }
            .map { if (it.specLine > inserted.specLine) it.copy(specLine = it.specLine - 1) else it }
        val stale = Section11.compare(specification, staleRows.map { it.toString() })

        assertFalse(
            stale.agrees,
            "a psv missing §11.2's verification-deadline line, with every line below it " +
                "renumbered, must not agree with the specification that has it",
        )
        val shifted = live
            .filter { it.specLine > inserted.specLine }
            .map { it.copy(specLine = it.specLine - 1) }
        assertTrue(
            stale.named.containsAll(shifted + inserted),
            "the failure must name every row the shift made stale, and the removed row. It " +
                "named ${stale.named}; the rows the shift touched were $shifted and the " +
                "removed row was $inserted",
        )

        assertTrue(
            Section11.compare(specification, Section11.transcribedLines()).agrees,
            "and the live psv passes against that same specification text, so the failure " +
                "above is the staleness and not the comparison",
        )
    }

    /**
     * The same equality over bare `(from, to)` pairs — which is the shape T7 consumes, and
     * the shape its own reachability floor is stated in. Implied by the triple equality
     * today and asserted separately because the two could drift apart if the file ever
     * carried a transition the specification states on more than one line.
     */
    @JsName("the_transcription_equals_section_11_2_pair_for_pair")
    @Test
    fun `the transcription equals section 11 2 pair for pair`() {
        assertEquals(
            spec.map { it.from to it.to }.toSet(),
            file.map { it.from to it.to }.toSet(),
        )
    }

    /**
     * The line numbers are what let a reviewer diff this file against the specification
     * without running anything, so they are checked literally as well as structurally: the
     * named line really does carry both tokens.
     */
    @JsName("each_transcribed_line_number_carries_its_own_two_tokens_in_the_specification")
    @Test
    fun `each transcribed line number carries its own two tokens in the specification`() {
        for (transition in file) {
            val text = Section11.specLine(transition.specLine)
            assertTrue(
                transition.from in text,
                "line ${transition.specLine} of the specification does not mention " +
                    "`${transition.from}`: $text",
            )
            assertTrue(
                transition.to in text,
                "line ${transition.specLine} of the specification does not mention " +
                    "`${transition.to}`: $text",
            )
        }
    }

    // ---------------------------------------------------------------- vocabulary hygiene

    /**
     * Every token in both columns is one of §11.1's ten states, the genesis marker excepted,
     * so a typo cannot enter the table unnoticed — it would otherwise sit there as a legal
     * transition to a state that does not exist.
     */
    @JsName("every_transcribed_token_is_a_section_11_1_state_genesis_excepted")
    @Test
    fun `every transcribed token is a section 11 1 state, genesis excepted`() {
        val known = Section11.states.keys
        assertTrue(known.isNotEmpty(), "§11.1's state table parsed to no tokens")
        for (transition in file) {
            assertTrue(
                transition.from == Section11.GENESIS || transition.from in known,
                "`${transition.from}` on line ${transition.specLine} is neither a §11.1 state " +
                    "nor the genesis marker; §11.1 names $known",
            )
            assertTrue(
                transition.to in known,
                "`${transition.to}` on line ${transition.specLine} is not a §11.1 state; " +
                    "§11.1 names $known",
            )
        }
    }

    /** A destination is always a state. The genesis marker belongs in the from-column only. */
    @JsName("the_genesis_marker_never_appears_as_a_destination")
    @Test
    fun `the genesis marker never appears as a destination`() {
        assertTrue(file.none { it.to == Section11.GENESIS }, "§11.2 transitions *into* states")
    }

    /**
     * Exactly one row has no from-state, and it is the one that brings an order into
     * existence at `proposed`. Pinned because the genesis row is the one row T7's
     * `(state × event)` cross-product cannot cover — it has no from-state to enumerate — and
     * a second one appearing silently would leave an edge outside both proofs.
     */
    @JsName("the_genesis_row_is_the_only_one_without_a_from_state")
    @Test
    fun `the genesis row is the only one without a from-state`() {
        val genesis = file.filter { it.from == Section11.GENESIS }
        assertEquals(1, genesis.size, "expected one genesis row, found $genesis")
        assertEquals("proposed", genesis.single().to)
    }

    /**
     * §11.3 invariant 3 — no path from any terminal state to any other state — at the level
     * this task can prove it: no state §11.1 marks **Terminal** appears in the from-column of
     * the transcribed table at all. T7 proves the transition function honours that; this
     * proves the table T7 is measured against does not contradict it in the first place.
     */
    @JsName("no_terminal_state_originates_a_transition")
    @Test
    fun `no terminal state originates a transition`() {
        val terminal = Section11.states.filterValues { it }.keys
        assertTrue(terminal.isNotEmpty(), "§11.1 marks no state terminal, which cannot be right")
        val offending = file.filter { it.from in terminal }
        assertTrue(offending.isEmpty(), "§11.3 invariant 3: terminal states lead nowhere; found $offending")
    }

    /**
     * The [OrderState.UNKNOWN] sink is non-transitionable, and this is where that is
     * checkable: it carries no token, so it appears in neither column of a table whose every
     * token is a §11.1 state. Nothing may transition into it and nothing may leave it.
     */
    @JsName("the_unknown_sink_appears_in_neither_column")
    @Test
    fun `the unknown sink appears in neither column`() {
        val tokens = file.flatMap { listOf(it.from, it.to) }.toSet()
        assertTrue(
            OrderState.UNKNOWN.token == null,
            "the sink must carry no token, or it could appear in §11.2's table",
        )
        for (token in tokens) {
            if (token == Section11.GENESIS) continue
            val state = OrderStatusCodec.read(token)
            if (state == OrderState.UNKNOWN) {
                fail("`$token` appears in the transition table and reads as the UNKNOWN sink")
            }
        }
    }
}
