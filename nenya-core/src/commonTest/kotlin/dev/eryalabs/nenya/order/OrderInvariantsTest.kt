package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.settlement.Settlement
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §11.3's five invariants, one named test each, plus §17's honesty rule surviving the layer
 * change.
 *
 * §11.3 calls these "invariants worth testing directly", and each of the five is the direct form
 * of a rule that a cross-product proof states only indirectly: the cross-product says which pairs
 * are reachable, and these say *why* the dangerous ones are not.
 */
class OrderInvariantsTest {

    private val machine = OrderFixtures.machineBeforeDeadlines()

    private val orders = OrderFixtures.orders()

    // ---------------------------------------------------------------- invariant 1

    /**
     * §11.3 invariant 1 — "There is **no** path to `paid` that does not pass through verified
     * evidence. `proposed → paid` is unreachable."
     *
     * Asserted two ways, because the first alone would pass over a machine that simply had no
     * `→ paid` edge at all: nothing offered to a `proposed` order reaches `paid`, **and** the one
     * event that does reach `paid` is refused there.
     */
    @JsName("invariant_1_proposed_to_paid_is_unreachable")
    @Test
    fun `invariant 1 - proposed to paid is unreachable`() {
        val proposed = orders.getValue(OrderState.PROPOSED)
        val receipts = OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts())

        val refusal = OrderFixtures.refusal(machine, proposed, receipts)
        assertEquals(TransitionRejection.WRONG_STATE_FOR_EVENT, refusal.reason)
        assertEquals(OrderState.PROPOSED, refusal.order.state)

        // ...and that same event is not merely unimplemented: it is **legal** where §11.2 lists it,
        // and traversable there. The same receipts, offered from `awaiting_payment`, move the
        // order. WRONG_STATE_FOR_EVENT there would mean the edge does not exist, which is what
        // would make the first half vacuous.
        val paid = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            receipts,
        )
        assertEquals(
            OrderState.PAID,
            paid.state,
            "the receipts event must be legal from `awaiting_payment` and must get there: a " +
                "machine with no `→ paid` edge at all would satisfy the first half of this " +
                "invariant trivially",
        )

        // And the order that traversed it owed a receipt. §9.2's non-zero clause leaves a free
        // order owing none, so `paid` on one of those says nothing about "no path to `paid` that
        // does not pass through verified evidence" — it is the priced order that says it.
        assertEquals(
            setOf(Payee.PROVIDER, Payee.FEE),
            Payee.requiredPayees(paid.terms.split),
        )
    }

    // ---------------------------------------------------------------- invariant 2

    /**
     * §11.3 invariant 2 — no `payee=fee` **receipt** is accepted before `awaiting_payment`.
     *
     * And the half §11.3 spells out in so many words: the fee **payment request** is a different
     * object, accepted exactly once as part of `committed → awaiting_payment`. A test that forbade
     * the request rather than the payment "makes every fee-bearing order unreachable and is
     * testing the wrong thing" (§8.5, §11.3).
     */
    @JsName("invariant_2_no_fee_receipt_before_awaiting_payment_while_the_fee_request_is_accepted_there")
    @Test
    fun `invariant 2 - no fee receipt before awaiting_payment, while the fee request is accepted there`() {
        val feeReceipt = OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.FEE)))

        for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED, OrderState.COMMITTED)) {
            val refusal = OrderFixtures.refusal(machine, orders.getValue(state), feeReceipt)
            assertEquals(
                TransitionRejection.WRONG_STATE_FOR_EVENT,
                refusal.reason,
                "§8.5: no fee may be *paid* before `awaiting_payment`; $state accepted one",
            )
        }

        // The request, at `committed`, is legal — and is what makes `awaiting_payment` reachable.
        val awaiting = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(OrderFixtures.chain().requests),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        // ...and nowhere earlier (§8.5: never at `proposed`, `accepted`, or a future `held`).
        for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED)) {
            val refusal = OrderFixtures.refusal(
                machine,
                orders.getValue(state),
                OrderEvent.PaymentRequestsReceived(OrderFixtures.chain().requests),
            )
            assertEquals(TransitionRejection.WRONG_STATE_FOR_EVENT, refusal.reason)
        }
    }

    // ---------------------------------------------------------------- invariant 3

    /**
     * §11.3 invariant 3 — no path from any terminal state to any other state.
     *
     * The whole-cross-product form is in `TransitionTotalityTest`; this is the named one, over
     * §11.1's own `isTerminal` mark rather than a list written here, so a revision that marked a
     * fifth state terminal would extend the test automatically.
     */
    @JsName("invariant_3_no_terminal_state_leads_anywhere")
    @Test
    fun `invariant 3 - no terminal state leads anywhere`() {
        val terminal = OrderState.entries.filter { it.isTerminal }
        assertTrue(terminal.isNotEmpty(), "§11.1 marks no state terminal, which cannot be right")

        val strongest = listOf(
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
            OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
            OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.PROVIDER),
        )
        for (state in terminal) {
            val order = orders.getValue(state)
            for (event in strongest) {
                val refusal = OrderFixtures.refusal(machine, order, event)
                assertEquals(TransitionRejection.STATE_IS_TERMINAL, refusal.reason, "$state + $event")
                assertEquals(state, refusal.order.state)
            }
        }
    }

    /**
     * The sink is not a terminal, and that distinction is §11.1's: being unreachable *as* a state
     * is a different fact from being an agreed terminal outcome, and reporting them the same way
     * would tell a user a garbled status update had settled or cancelled their order.
     */
    @JsName("the_unknown_sink_is_refused_as_unknown_and_not_as_terminal")
    @Test
    fun `the unknown sink is refused as unknown and not as terminal`() {
        assertFalse(OrderState.UNKNOWN.isTerminal)
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.UNKNOWN),
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(TransitionRejection.STATE_IS_UNKNOWN, refusal.reason)
    }

    // ---------------------------------------------------------------- invariant 4

    /**
     * §11.3 invariant 4 — `settled` is reachable only after the buyer's **own** hash computation,
     * never on the provider's assertion.
     *
     * Structural: the only event that reaches `settled` carries a `DeliveryEvidence`, which cannot
     * be constructed except by §10.4 step 1 and then step 3 both succeeding. So this test asserts
     * the two things a type system cannot: that a provider's status update saying so does not
     * settle, and that evidence from another commitment does not settle this order.
     */
    @JsName("invariant_4_settled_only_on_the_buyer_s_own_computation")
    @Test
    fun `invariant 4 - settled only on the buyer's own computation`() {
        val released = orders.getValue(OrderState.RELEASED)

        val announced = OrderFixtures.refusal(
            machine,
            released,
            OrderEvent.StatusUpdate(OrderState.SETTLED, Party.PROVIDER),
        )
        assertEquals(TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING, announced.reason)
        assertEquals(OrderState.RELEASED, announced.order.state)

        val elsewhere = OrderFixtures.refusal(
            machine,
            released,
            OrderEvent.DeliveryVerified(OrderFixtures.evidence(OrderFixtures.otherBlob)),
        )
        assertEquals(TransitionRejection.EVIDENCE_IS_FOR_ANOTHER_COMMITMENT, elsewhere.reason)

        val settled = OrderFixtures.advanced(
            machine,
            released,
            OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
        )
        assertEquals(OrderState.SETTLED, settled.state)
    }

    // ---------------------------------------------------------------- invariant 5

    /**
     * §11.3 invariant 5 — a `type=3` purporting to set `paid` or `settled`, from **any** key, MUST
     * NOT change state. Only `cancelled` is accepted from a counterparty's assertion alone, and
     * only before `paid`.
     *
     * Enumerated over every one of the eleven states and both keys, not spot-checked: the
     * invariant says "arriving from any key", and a test exercising one state proves nothing about
     * the other ten.
     */
    @JsName("invariant_5_a_status_update_announcing_paid_or_settled_changes_nothing_anywhere")
    @Test
    fun `invariant 5 - a status update announcing paid or settled changes nothing, anywhere`() {
        for (status in listOf(OrderState.PAID, OrderState.SETTLED)) {
            for (party in Party.entries) {
                for (state in OrderState.entries) {
                    val order = orders.getValue(state)
                    val refusal = OrderFixtures.refusal(
                        machine,
                        order,
                        OrderEvent.StatusUpdate(status, party),
                    )
                    assertEquals(state, refusal.order.state, "$status from $party moved $state")
                    assertEquals(
                        TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING,
                        refusal.reason,
                        "$status from $party at $state",
                    )
                }
            }
        }
    }

    /**
     * The control the task names, and the one an implementer would otherwise ship green: a
     * `type=3` `status=disputed`, from either key, in every state, changes nothing.
     *
     * §11.2 states `committed → disputed` and `awaiting_payment → disputed` and names no trigger
     * for either. Reaching for `type=3 status=disputed` to fill that gap would let a counterparty
     * drive an order to a **terminal** state on its assertion alone — and it would pass an
     * invariant-5 test that exercised only `paid` and `settled`.
     */
    @JsName("a_status_update_announcing_disputed_changes_nothing_anywhere")
    @Test
    fun `a status update announcing disputed changes nothing, anywhere`() {
        for (party in Party.entries) {
            for (state in OrderState.entries) {
                val refusal = OrderFixtures.refusal(
                    machine,
                    orders.getValue(state),
                    OrderEvent.StatusUpdate(OrderState.DISPUTED, party),
                )
                assertEquals(state, refusal.order.state, "disputed from $party moved $state")
                assertEquals(TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING, refusal.reason)
            }
        }
    }

    /** The other half of invariant 5: `cancelled` *is* accepted from an assertion, before `paid`. */
    @JsName("invariant_5_cancellation_is_accepted_from_either_party_and_only_before_paid")
    @Test
    fun `invariant 5 - cancellation is accepted from either party, and only before paid`() {
        val before = listOf(
            OrderState.PROPOSED,
            OrderState.ACCEPTED,
            OrderState.COMMITTED,
            OrderState.AWAITING_PAYMENT,
        )
        for (state in before) {
            for (party in listOf(Party.BUYER, Party.PROVIDER)) {
                val cancelled = OrderFixtures.advanced(
                    machine,
                    orders.getValue(state),
                    OrderEvent.StatusUpdate(OrderState.CANCELLED, party),
                )
                assertEquals(OrderState.CANCELLED, cancelled.state, "$state, from $party")
            }
        }

        for (state in listOf(OrderState.PAID, OrderState.RELEASED)) {
            val refusal = OrderFixtures.refusal(
                machine,
                orders.getValue(state),
                OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER),
            )
            assertEquals(TransitionRejection.CANCELLATION_TOO_LATE, refusal.reason, "at $state")
        }
    }

    /** §11.2 says "either **party**", and §8.5 says the fee recipient is not one. */
    @JsName("a_cancellation_from_the_fee_recipient_is_refused_as_the_wrong_sender")
    @Test
    fun `a cancellation from the fee recipient is refused as the wrong sender`() {
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.FEE_RECIPIENT),
        )
        assertEquals(TransitionRejection.WRONG_SENDER, refusal.reason)
    }

    // ---------------------------------------------------------------- §17 across the layer

    /**
     * §17's honesty rule, enforced by a **refusal** rather than carried forward on a record — and
     * the transition it guards, asserted beside it.
     *
     * Decision B is that §9.2's "MUST perform **all**" means what it says, so an order whose
     * evidence leaves any applicable check unperformed does not move. What is left unperformed has
     * shrunk to nothing on the ordinary path: `Settlement.verify` parses the stored invoice for
     * checks 4 and 5 and takes check 3's payment hash out of that same parse, so the complete
     * receipt set below **advances**. The gate is therefore asserted on the one incomplete shape
     * this library can still build — a fee receipt through plain `Settlement.verify`, which
     * performs no part of check 6 — and the positive half is what says the refusal is a gate rather
     * than a wall.
     *
     * Both halves are needed and neither is redundant. A gate deleted lets the incomplete set
     * through; a gate that refuses unconditionally stops the complete one. Only the pair sees both.
     */
    @JsName("an_order_is_refused_paid_on_incomplete_evidence_and_advances_on_complete")
    @Test
    fun `an order is refused paid on incomplete evidence, and advances on complete`() {
        val awaiting = orders.getValue(OrderState.AWAITING_PAYMENT)

        val refused = OrderFixtures.refusedForChecks(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1),
                ),
            ),
        )

        assertEquals(
            OrderFixtures.CHECK_SIX_UNPERFORMED,
            refused.missing,
            "exactly check 6's three: the fee receipt took the door that performs none of them, " +
                "and everything else §9.2 asks of either receipt was performed",
        )
        for (performed in listOf(
            PaymentCheck.INVOICE_IDENTITY,
            PaymentCheck.PAYMENT_HASH_PROVENANCE,
            PaymentCheck.INVOICE_AMOUNT,
            PaymentCheck.INVOICE_EXPIRY,
        )) {
            assertFalse(
                performed in refused.missing,
                "$performed IS performed, against the stored `type=2`. Demanding it would hold an " +
                    "order for a check that was done, which is the mirror of the over-claim §17 " +
                    "forbids",
            )
        }

        // The order did not move, and recorded nothing: a refusal is not a half-transition.
        assertEquals(OrderState.AWAITING_PAYMENT, refused.order.state)
        assertEquals(null, refused.order.paidAt)
        assertEquals(emptySet(), refused.order.paymentChecksPerformed)
        assertEquals(emptySet(), refused.order.paymentChecksNotPerformedHere)
        assertSame(awaiting, refused.order, "§11.2: a refusal carries the order **unchanged**")

        // The positive half, on the same order and the same provider receipt: the fee receipt
        // through `verifyFeeReceipt` closes check 6 and the order moves.
        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(OrderState.PAID, paid.state)
        assertEquals(
            emptySet(),
            paid.paymentChecksNotPerformedHere,
            "and nothing is left over: every §9.2 check that applied to either receipt was " +
                "performed, which is what makes this order's emptiness different from a free " +
                "order's — there it means nothing applied",
        )
        assertEquals(
            PaymentCheck.entries.toSet(),
            paid.paymentChecksPerformed,
            "the full applicable set for both payees, which over a fee-bearing order is every " +
                "constant §9.2 is modelled by: checks 1 to 5 for the provider and check 6's three " +
                "besides for the fee recipient. Written as the enum's own constants rather than " +
                "as `applicableChecks`, which is the function the gate itself subtracts from",
        )
    }

    /**
     * The fee side of the same statement: `missing` is computed from **the receipts offered** and
     * not from a constant.
     *
     * §9.2 check 6 is three obligations. They apply to a fee receipt and to nothing else, and what
     * proves the gate knows that is the **difference between two fee receipts**: one put through
     * `Settlement.verifyFeeReceipt`, which performs all three, and one put through plain
     * `Settlement.verify`, which performs none. The first leaves nothing missing at all; the second
     * leaves exactly those three.
     *
     * ### Why the comparison is what discriminates
     *
     * A single assertion on one fee-bearing order cannot tell "computed from the receipts offered"
     * from "hardcoded to a constant", because for that order the two coincide. Three orders
     * separate them, and each pins a different half:
     *
     * - the `verifyFeeReceipt` order shows check 6 subtracted when it was performed — and with
     *   nothing else outstanding, subtracted all the way to a transition;
     * - the plain-`verify` order shows check 6 **demanded** when it was not — a gate reading the
     *   receipt's own `checksNotPerformedHere` would agree here, which is why the mutation the
     *   queue names pairs this with a record that lies;
     * - the provider-only order shows check 6 absent from `missing` altogether. It was not
     *   performed and it was not skipped either: it does not apply to a provider receipt, and
     *   demanding an inapplicable obligation would deadlock every provider-only order for ever.
     *   With nothing else outstanding that order reaches `paid`, which is the strongest form of
     *   "check 6 was not demanded" available.
     */
    @JsName("the_missing_set_is_the_receipts_own_on_a_fee_bearing_and_a_provider_only_order")
    @Test
    fun `the missing set is the receipts' own, asserted exactly on a fee-bearing and a provider-only order`() {
        val feeBearing = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(
            OrderState.PAID,
            feeBearing.state,
            "both receipts went through the path that performs checks 1, 3, 4 and 5, and the fee " +
                "receipt through the one that performs check 6's three, so nothing remains",
        )

        val throughBareVerify = OrderFixtures.refusedForChecks(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, 0),
                    OrderFixtures.receiptThroughVerify(Payee.FEE, 1),
                ),
            ),
            missing = Settlement.CHECK_SIX,
        )
        assertTrue(
            Settlement.CHECK_SIX.all { it in throughBareVerify.missing },
            "a fee receipt that skipped `verifyFeeReceipt` performed no part of check 6, and the " +
                "gate must demand it rather than merely record it: ${throughBareVerify.missing}",
        )

        val providerOnly = OrderFixtures.advanced(
            OrderFixtures.machineBeforeDeadlines(),
            OrderFixtures.orders(OrderFixtures.zeroFeeTerms)
                .getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        assertEquals(
            OrderState.PAID,
            providerOnly.state,
            "and a provider-only order is missing nothing: check 6 does not apply to it. Demanding " +
                "an inapplicable obligation would deadlock every such order, which is §8.3's " +
                "failure mode with a different cause",
        )
        for (check in Settlement.CHECK_SIX) {
            assertFalse(
                check in providerOnly.paymentChecksPerformed,
                "$check does not apply to a provider receipt, so it must not be *claimed* either " +
                    "— an order that reached `paid` by recording work nobody did is §17's " +
                    "over-claim arriving one layer up",
            )
        }
    }

    /**
     * The third answer: an order that required no receipt at all carries an **empty** record in
     * both directions, and `paymentChecksPerformed` being empty is what distinguishes it from an
     * order whose checks all passed.
     *
     * §9.2's non-zero clause makes a zero-price, zero-fee order require nothing, so it reaches
     * `paid` on an empty receipt set. A client reading only `INVOICE_AMOUNT !in notPerformed`
     * would conclude the amount was checked; both sets are published so that it cannot.
     */
    @JsName("an_order_that_required_no_receipt_carries_an_empty_record_in_both_directions")
    @Test
    fun `an order that required no receipt carries an empty record in both directions`() {
        // A *stated* fee term with nothing owed, which is not `freeTerms`' absent one: §8.3's
        // zero-amount clause reaches both shapes, and this is the one that still names a payee.
        val nothingOwed = OrderTerms.of(
            Msat.ZERO,
            FeeTerm.of(250),
            OrderFixtures.EXPIRATION,
            OrderFixtures.DELIVER_BY,
        )
        assertEquals(emptySet(), Payee.requiredPayees(nothingOwed.split))

        val chain = OrderFixtures.chain(nothingOwed)
        assertEquals(emptySet(), chain.requests, "nobody is owed anything, so no `type=2` exists")

        val opened = machine.open(chain.proposal.asOrderEvent())
        val committed = OrderFixtures.advanced(
            machine,
            OrderFixtures.advanced(machine, opened, OrderEvent.AcceptanceReceived(chain.accepted)),
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER),
        )
        val awaiting = OrderFixtures.advanced(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(emptySet()),
        )
        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(emptySet()),
        )

        assertEquals(OrderState.PAID, paid.state)
        assertTrue(paid.paymentChecksPerformed.isEmpty(), "no receipt was verified, so none passed")
        assertTrue(
            paid.paymentChecksNotPerformedHere.isEmpty(),
            "and none was skipped either: the set answers a question about receipts that do not " +
                "exist, which is why the *performed* set is the flag a client must read",
        )
    }

    /**
     * Both records travel through `released` and into `settled`: §17 does not lapse on success.
     *
     * The payment half is back and says the opposite of what it used to. It once asserted that
     * `INVOICE_AMOUNT` was still *named as unperformed* on a `settled` order, because a priced
     * order reached `paid` without it; T20 removed it when no priced order could reach `paid` at
     * all. What holds now is the stronger statement — a `settled` priced order carries the whole of
     * §9.2 as **performed**, and carries it two transitions past the gate that demanded it.
     *
     * The delivery half is deliberately the other shape, and the pairing is why they are one test:
     * §10's obligations are genuinely *not* all performed here, so a record that had quietly become
     * "everything is fine everywhere" fails on the line below rather than passing both.
     */
    @JsName("the_delivery_record_survives_into_settled")
    @Test
    fun `the delivery record survives into settled`() {
        val settled = orders.getValue(OrderState.SETTLED)

        assertTrue(
            settled.deliveryChecksNotPerformedHere.isNotEmpty(),
            "§17 item 7 asks for the two hashes *plus* §10.2's encryption parameters, and this " +
                "library performs no encryption at all",
        )
        assertTrue(settled.deliveryChecksPerformed.isNotEmpty())

        assertEquals(
            PaymentCheck.entries.toSet(),
            settled.paymentChecksPerformed,
            "and the payment record survives two transitions past the gate intact, for both payees",
        )
        assertEquals(emptySet(), settled.paymentChecksNotPerformedHere)
    }

    /**
     * Decision B's invariant, over every order in the cross-product that reached `paid` or beyond:
     * `paymentChecksNotPerformedHere` is **empty**.
     *
     * It follows from the gate rather than from the fixture. `awaiting_payment → paid` refuses
     * while any applicable §9.2 check is unperformed, and an order's record is the union of what
     * its receipts did and did not perform, so an order past that gate has nothing to put in this
     * set. `released` and `settled` are downstream of `paid` and inherit it.
     *
     * **Empty here still does not mean six checks ran, and the sweep covers both reasons.** A free
     * order reaches `paid` owing no receipt, so no check applied to it and `paymentChecksPerformed`
     * is empty too; a priced one reaches `paid` because every check that applied was performed, and
     * that set is full. The performed set is the flag telling the two apart, and both shapes are
     * walked below. What the invariant forbids is the third: an order claiming `paid` while naming
     * a check nobody performed.
     *
     * Non-vacuity is asserted rather than assumed: the sweep must have inspected each of the three
     * states at least once, over more than one terms shape, **and** must have seen at least one
     * order past the gate whose performed set is non-empty. A cross-product that stopped producing
     * priced `paid` orders would otherwise pass this trivially — which is exactly the failure mode
     * decision B introduces, since refusing `paid` for everything but a free order would also
     * satisfy it.
     */
    @JsName("every_order_at_paid_or_later_carries_an_empty_not_performed_record")
    @Test
    fun `every order at paid or later carries an empty not-performed record`() {
        val pastTheGate = listOf(OrderState.PAID, OrderState.RELEASED, OrderState.SETTLED)
        val inspected = mutableMapOf<OrderState, Int>()
        var withEvidence = 0

        for (terms in listOf(
            OrderFixtures.TERMS,
            OrderFixtures.zeroFeeTerms,
            OrderFixtures.absentFeeTerms,
            OrderFixtures.freeTerms(),
        )) {
            for (state in pastTheGate) {
                val order = OrderFixtures.orders(terms).getValue(state)
                assertEquals(
                    emptySet(),
                    order.paymentChecksNotPerformedHere,
                    "an order in $state names a §9.2 check nobody performed. Decision B says such " +
                        "an order cannot exist: the gate refuses it at `awaiting_payment → paid`",
                )
                if (order.paymentChecksPerformed.isNotEmpty()) withEvidence++
                inspected[state] = (inspected[state] ?: 0) + 1
            }
        }

        for (state in pastTheGate) {
            assertTrue(
                (inspected[state] ?: 0) > 0,
                "the sweep never reached $state, so it proved nothing about it. A machine that " +
                    "refused `paid` unconditionally would satisfy the assertion above by vacuity",
            )
        }
        assertEquals(pastTheGate.size, inspected.size, "each of the three states, and no others")
        assertTrue(
            withEvidence > 0,
            "every order past the gate owed no receipt, so this invariant saw only the empty-set " +
                "case. Emptiness that nothing had to be subtracted to reach is not the invariant",
        )
    }

    /** An order before `paid` claims nothing at all, rather than claiming an empty success. */
    @JsName("an_order_before_paid_carries_no_payment_record")
    @Test
    fun `an order before paid carries no payment record`() {
        for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED, OrderState.COMMITTED)) {
            val order = orders.getValue(state)
            assertTrue(order.paymentChecksPerformed.isEmpty(), "at $state")
            assertTrue(order.paymentChecksNotPerformedHere.isEmpty(), "at $state")
            assertNull(order.paidAt, "at $state")
        }
    }

    /** §14 item 3: `disputed` resolves nothing, so what it must do is say which thing happened. */
    @JsName("disputeground_is_set_exactly_when_the_order_is_disputed")
    @Test
    fun `disputeGround is set exactly when the order is disputed`() {
        for ((state, order) in orders) {
            if (state == OrderState.DISPUTED) {
                assertNotNull(order.disputeGround, "a disputed order must say why")
            } else {
                assertNull(order.disputeGround, "$state carries a dispute ground")
            }
        }
    }
}
