package dev.eryalabs.nenya.order

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private val spec = Section11.transitions

    private val file = Section11.transcribed()

    // ---------------------------------------------------------------- non-vacuity floor

    /**
     * "Empty equals empty" must not be a passing state, so both sides are asserted non-empty
     * before any equality is claimed of them. A restructured §11.2, a moved heading or a
     * missing data file would otherwise satisfy every assertion below.
     */
    @Test
    fun `the specification parse yields a non-zero number of transitions`() {
        assertTrue(
            spec.isNotEmpty(),
            "§11.2's row block in ${Section11.specPath()} parsed to no transitions at all",
        )
    }

    @Test
    fun `the transcribed file yields a non-zero number of transitions`() {
        assertTrue(file.isNotEmpty(), "the transcribed §11.2 table carries no data lines")
    }

    /**
     * Set equality alone would let a duplicated line hide: two copies of one transition and
     * a missing one are indistinguishable once both are collapsed into a set.
     */
    @Test
    fun `no transition is transcribed twice`() {
        val duplicates = file.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "these lines appear more than once: $duplicates")
    }

    // ---------------------------------------------------------------- the completeness proof

    /**
     * The headline. Both directions, over `(spec line, from, to)` triples, so the line
     * numbers a reviewer diffs by eye are inside the proof rather than beside it.
     */
    @Test
    fun `the transcription equals section 11 2 triple for triple`() {
        val transcribed = file.toSet()
        val missing = (spec - transcribed).sortedBy { it.specLine }
        val extra = (transcribed - spec).sortedBy { it.specLine }
        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            buildString {
                append("the transcribed §11.2 table and ${Section11.specPath()} disagree.")
                if (missing.isNotEmpty()) append("\n  in the specification, not transcribed: $missing")
                if (extra.isNotEmpty()) append("\n  transcribed, not in the specification: $extra")
            },
        )
        assertEquals(spec, transcribed)
    }

    /**
     * The same equality over bare `(from, to)` pairs — which is the shape T7 consumes, and
     * the shape its own reachability floor is stated in. Implied by the triple equality
     * today and asserted separately because the two could drift apart if the file ever
     * carried a transition the specification states on more than one line.
     */
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
