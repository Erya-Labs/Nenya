package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.payment.Payee
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §11.2's totality proof: every `(state, event)` pair produces either a new state or an explicit,
 * named rejection, and the set of transitions the function can produce is **exactly** the set
 * §11.2 states.
 *
 * ### Why a set equality and not a count
 *
 * No count survives a change of event model. Merge two events into one and every count moves;
 * split one and they move again. But the *pairs* are §11.2's, and T6's transcribed file — which
 * `TransitionTableTest` proves equal to §11.2's own row block, parsed out of the specification at
 * test time — is the authority for them. So the floor is:
 *
 * - every from-state pair in that file is reachable by at least one event, and
 * - no pair outside it is reachable by any event.
 *
 * A function that accepts everything fails the second half. One that accepts nothing fails the
 * first. Neither an extra event nor a merged one can make it pass by arithmetic coincidence.
 *
 * The genesis row `— → proposed` is excluded from both sides for the one reason
 * `TransitionTableTest` pins: it has no from-state, so no cross-product over states can cover it.
 * `OrderMachine.open` is what produces it, and it is asserted separately below.
 *
 * ### §11.4 is tested by a mutation, not by an assertion here
 *
 * §11.4 forbids transition logic that enumerates "everything after `accepted`" in a way a future
 * `held` state would silently join. Nothing a JUnit test can assert distinguishes an
 * ordinal-range implementation from a named-constant one — both answer identically for today's
 * enum. The only test available is to **reorder [OrderState]'s constants and re-run the suite**:
 * an implementation branching on ordinal turns red, and this one must stay green. That mutation
 * is run and recorded rather than written down here as a test that would prove nothing.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as
 * `TransitionTotalityTest` on each target, so every test keeps its `TransitionTotalityTest` name. The
 * one test that enumerates the declared [OrderEvent] classes walks the compiled main output tree with
 * `java.lang.reflect`, and is in the JVM `TransitionTotalityTest` (`src/jvmTest`).
 */
abstract class PortableTransitionTotalityTest {

    private val machine = OrderFixtures.machineAfterDeadlines()

    private val orders = OrderFixtures.orders()

    /**
     * One instance of every [OrderEvent] this package declares, plus the payload variants §11.2
     * distinguishes — a `status=accepted` and a `status=cancelled` are the same class and
     * different rows, and a release that matches the commitment and one that does not are the
     * same class and different destinations (§10.3).
     *
     * Every event here is **message-shaped**: a rumor kind and `type`, a clock crossing, or a
     * piece of evidence this library verified. None names a pair of states, which is what stops
     * the cross-product below from being a lookup of the file it is measured against.
     */
    protected val events: List<OrderEvent> = listOf(
        OrderEvent.Proposal(OrderFixtures.TERMS),
        OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, OrderFixtures.TERMS),
        OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.BUYER, OrderFixtures.TERMS),
        OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, OrderFixtures.zeroFeeTerms),
        OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER),
        OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.PROVIDER),
        OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.FEE_RECIPIENT),
        OrderEvent.StatusUpdate(OrderState.PAID, Party.PROVIDER),
        OrderEvent.StatusUpdate(OrderState.SETTLED, Party.PROVIDER),
        OrderEvent.StatusUpdate(OrderState.DISPUTED, Party.BUYER),
        OrderEvent.StatusUpdate(OrderState.UNKNOWN, Party.PROVIDER),
        OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER),
        OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.BUYER),
        OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
        OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER)),
        OrderEvent.PaymentRequestsReceived(emptySet()),
        OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        OrderEvent.ReceiptsVerified(emptySet()),
        OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.PROVIDER),
        OrderEvent.DeliverableReleased(OrderFixtures.divergentRelease(), Party.PROVIDER),
        OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.BUYER),
        OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
        OrderEvent.DeliveryVerified(OrderFixtures.evidence(OrderFixtures.otherBlob)),
        OrderEvent.DeliveryRefused(DeliveryFailure.SERVED_BYTES_HASH_MISMATCH),
        OrderEvent.DeliveryRefused(DeliveryFailure.BLOB_DID_NOT_DECRYPT),
        OrderEvent.DeliveryRefused(DeliveryFailure.PLAINTEXT_BYTES_HASH_MISMATCH),
        OrderEvent.ClockChecked,
        OrderEvent.LocallyDisputed,
        OrderEvent.PrivateBid(Party.BUYER),
        OrderEvent.PrivateBid(Party.PROVIDER),
        OrderEvent.ChatMessage(Party.BUYER),
        OrderEvent.ChatMessage(Party.PROVIDER),
        OrderEvent.ShippingUpdate(Party.PROVIDER),
    )

    /** Every `(from, to)` the function produced, over the whole cross-product. */
    private fun produced(): Set<Pair<String, String>> {
        val pairs = mutableSetOf<Pair<String, String>>()
        for (order in orders.values) {
            for (event in events) {
                val outcome = machine.on(order, event)
                if (outcome is OrderOutcome.Advanced) {
                    val from = order.state.token
                        ?: fail("an order in the UNKNOWN sink advanced, on $event")
                    val to = outcome.order.state.token
                        ?: fail("a transition landed in the UNKNOWN sink, on $event")
                    pairs += from to to
                }
            }
        }
        return pairs
    }

    // ---------------------------------------------------------------- non-vacuity floor

    @JsName("the_cross_product_enumerates_a_non_zero_number_of_pairs")
    @Test
    fun `the cross-product enumerates a non-zero number of pairs`() {
        assertEquals(OrderState.entries.size, orders.size, "one order per §11.1 state")
        assertTrue(events.isNotEmpty(), "the event vocabulary is empty")
        assertTrue(
            orders.size * events.size > 100,
            "the cross-product is ${orders.size} × ${events.size}, which is not a cross-product " +
                "over eleven states and a real event vocabulary",
        )
    }

    @JsName("t6_s_transcribed_table_parses_to_a_non_empty_set_of_from_state_pairs")
    @Test
    fun `T6's transcribed table parses to a non-empty set of from-state pairs`() {
        assertTrue(
            expectedPairs().isNotEmpty(),
            "T6's transcribed §11.2 table yielded no pairs with a from-state, so the equality " +
                "below would be empty-equals-empty",
        )
    }

    // ---------------------------------------------------------------- totality

    /**
     * §11.2: "every (state, event) pair produces either a new state or an explicit rejection".
     * Both halves are asserted — a refusal must name a reason **and** leave the order alone.
     */
    @JsName("every_pair_yields_either_a_new_state_or_a_named_rejection")
    @Test
    fun `every pair yields either a new state or a named rejection`() {
        var inspected = 0
        for (order in orders.values) {
            for (event in events) {
                inspected++
                when (val outcome = machine.on(order, event)) {
                    is OrderOutcome.Advanced -> assertTrue(
                        outcome.order.state != order.state,
                        "${order.state} + $event reported an advance that did not move",
                    )

                    is OrderOutcome.Refused -> {
                        assertEquals(
                            order.state,
                            outcome.order.state,
                            "a refusal must leave the order where it was",
                        )
                        assertTrue(
                            outcome.detail.isNotBlank(),
                            "${outcome.reason} from ${order.state} carried no explanation",
                        )
                    }
                }
            }
        }
        assertEquals(orders.size * events.size, inspected)
    }

    /**
     * The headline. The producible set equals T6's from-state pairs, in both directions.
     */
    @JsName("the_producible_transitions_equal_section_11_2_s_own")
    @Test
    fun `the producible transitions equal section 11 2's own`() {
        val expected = expectedPairs()
        val produced = produced()
        val unreachable = (expected - produced).sortedBy { "${it.first} -> ${it.second}" }
        val invented = (produced - expected).sortedBy { "${it.first} -> ${it.second}" }

        assertTrue(
            unreachable.isEmpty() && invented.isEmpty(),
            buildString {
                append("the transition function and §11.2 disagree.")
                if (unreachable.isNotEmpty()) {
                    append("\n  legal in §11.2 and reachable by no event: $unreachable")
                }
                if (invented.isNotEmpty()) {
                    append("\n  reachable and listed nowhere in §11.2: $invented")
                }
            },
        )
        assertEquals(expected, produced)
    }

    /** §11.2's genesis row, the one pair no cross-product over from-states can reach. */
    @JsName("the_genesis_row_is_what_open_produces_and_open_produces_nothing_else")
    @Test
    fun `the genesis row is what open produces, and open produces nothing else`() {
        val genesis = Section11.transcribed().single { it.from == Section11.GENESIS }
        val opened = OrderFixtures.machineBeforeDeadlines()
            .open(OrderEvent.Proposal(OrderFixtures.TERMS))

        assertEquals(genesis.to, opened.state.token)
        assertEquals(OrderState.PROPOSED, opened.state)
        assertEquals(OrderFixtures.TERMS, opened.terms)
    }

    /**
     * §11.1's sink originates no transition, from any event — the half of "non-transitionable"
     * that a table parse cannot reach, because the sink carries no token to appear in one.
     */
    @JsName("the_unknown_sink_advances_on_nothing")
    @Test
    fun `the unknown sink advances on nothing`() {
        val sink = orders.getValue(OrderState.UNKNOWN)
        for (event in events) {
            val refusal = OrderFixtures.refusal(machine, sink, event)
            assertEquals(
                sink.state,
                refusal.order.state,
                "the sink moved on $event",
            )
        }
    }

    /**
     * §11.3 invariant 3, over the whole cross-product: no terminal state leads anywhere,
     * including on evidence this library verified itself.
     *
     * The *reason* is not asserted uniformly here on purpose. Three rules are applied before the
     * state is looked at — §11.2's silent message classes, §11.3 invariant 5, and "a proposal
     * opens an order rather than advancing one" — because each of those is stated as holding in
     * **every** state and would be impossible to assert as one thing if a terminal check ran
     * first. So a chat message at `settled` is refused as `EVENT_ADVANCES_NO_STATE`, which is the
     * more specific and the more useful answer. `OrderInvariantsTest` pins
     * `STATE_IS_TERMINAL` against the three events that carry real evidence, which is where the
     * invariant bites.
     */
    @JsName("no_terminal_state_advances_on_any_event")
    @Test
    fun `no terminal state advances on any event`() {
        val terminals = OrderState.entries.filter { it.isTerminal }
        assertEquals(4, terminals.size, "§11.1 marks four states terminal")
        for (state in terminals) {
            val order = orders.getValue(state)
            for (event in events) {
                val refusal = OrderFixtures.refusal(machine, order, event)
                assertEquals(state, refusal.order.state, "$state + $event")
            }
        }
    }

    /**
     * T6's file, reduced to the bare `(from, to)` pairs that have a from-state.
     *
     * Read through [Section11] so that this test and `TransitionTableTest` measure the same file,
     * and so that the completeness proof there — set equality against §11.2's own row block,
     * parsed out of `spec/NENYA-1.md` — is what stands behind this one. A disagreement between the
     * file and the specification is STOP RULE 10 and is that test's to report, not this one's.
     */
    private fun expectedPairs(): Set<Pair<String, String>> =
        Section11.transcribed()
            .filter { it.from != Section11.GENESIS }
            .map { it.from to it.to }
            .toSet()
}
