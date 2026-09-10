package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.VerifiedPayment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    @Test
    fun `invariant 1 - proposed to paid is unreachable`() {
        val proposed = orders.getValue(OrderState.PROPOSED)
        val receipts = OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts())

        val refusal = OrderFixtures.refusal(machine, proposed, receipts)
        assertEquals(TransitionRejection.WRONG_STATE_FOR_EVENT, refusal.reason)
        assertEquals(OrderState.PROPOSED, refusal.order.state)

        // ...and that same event is not merely unimplemented: it works where §11.2 lists it.
        val paid = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            receipts,
        )
        assertEquals(OrderState.PAID, paid.state)
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
            OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        // ...and nowhere earlier (§8.5: never at `proposed`, `accepted`, or a future `held`).
        for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED)) {
            val refusal = OrderFixtures.refusal(
                machine,
                orders.getValue(state),
                OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
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
     * §17's honesty rule survives the layer change, which is this task's finding that matters most.
     *
     * Every `VerifiedPayment` says §9.2 check 4 — the invoice's **amount** — was not checked here,
     * because that needs a BOLT-11 parser this library does not have. So an order that reached
     * `paid` on check-2/3-only evidence is `paid` on partial evidence: a provider who sends a
     * `type=2` for ten times `price_msat` is caught by check 4 and by nothing this library yet
     * does. §17 says an implementation MUST NOT report unverified things as verified, and
     * honouring that one layer down while dropping it one layer up is the same lie with an extra
     * step.
     */
    @Test
    fun `an order that reached paid on check-2 and check-3 evidence reports itself amount-unverified`() {
        val paid = orders.getValue(OrderState.PAID)

        assertTrue(
            PaymentCheck.INVOICE_AMOUNT in paid.paymentChecksNotPerformedHere,
            "a `paid` order must carry forward that §9.2 check 4 was never performed",
        )
        assertTrue(PaymentCheck.INVOICE_IDENTITY in paid.paymentChecksNotPerformedHere)
        assertTrue(PaymentCheck.INVOICE_EXPIRY in paid.paymentChecksNotPerformedHere)
        assertEquals(
            setOf(PaymentCheck.PREIMAGE_SHAPE, PaymentCheck.PREIMAGE_HASH_COMPARISON),
            paid.paymentChecksPerformed,
            "and it must say which two checks it *did* perform, so the record is actionable",
        )
    }

    /**
     * The fee side of the same record. §9.2 check 6 is three obligations, and a `paid` order that
     * accepted a fee receipt must carry all three forward — an order recording only the three
     * invoice checks would silently imply check 6 had been performed.
     *
     * ### Both directions, as **exact** set equalities
     *
     * A membership assertion on a fee-bearing order alone cannot tell "carried forward from the
     * receipts" from "hardcoded to the fee constant", because for that order the two coincide.
     * The provider-only order below is what separates them: its record must be exactly the three
     * invoice checks and **must not** name check 6's three, which do not apply to a provider
     * receipt at all — recording an inapplicable obligation as "not performed" is a different
     * false statement, not a safer one.
     */
    @Test
    fun `the record is the receipts' own, asserted exactly on both a fee-bearing and a provider-only order`() {
        assertEquals(
            VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE,
            orders.getValue(OrderState.PAID).paymentChecksNotPerformedHere,
            "a fee-bearing order's record is the union of its two receipts', which for these two " +
                "is the fee receipt's superset — including §9.2 check 6's three obligations",
        )

        val providerOnly = OrderFixtures.orders(OrderFixtures.zeroFeeTerms)
            .getValue(OrderState.PAID)
        assertEquals(
            VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER,
            providerOnly.paymentChecksNotPerformedHere,
            "a provider-only order's record must be exactly the three invoice checks: naming " +
                "check 6's obligations for an order with no fee receipt is a false statement too",
        )
        for (check in listOf(
            PaymentCheck.FEE_TERM_MATCH,
            PaymentCheck.FEE_SEALING_KEY,
            PaymentCheck.FEE_STATE_PRECONDITION,
        )) {
            assertFalse(check in providerOnly.paymentChecksNotPerformedHere, "$check")
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
    @Test
    fun `an order that required no receipt carries an empty record in both directions`() {
        val nothingOwed = OrderTerms.of(Msat.ZERO, FeeTerm.of(250))
        assertEquals(emptySet(), Payee.requiredPayees(nothingOwed.split))

        val opened = machine.open(OrderEvent.Proposal(nothingOwed))
        val committed = OrderFixtures.advanced(
            machine,
            OrderFixtures.advanced(
                machine,
                opened,
                OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, nothingOwed),
            ),
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

    /** The record travels through `released` and into `settled`: §17 does not lapse on success. */
    @Test
    fun `the payment record survives into settled, and the delivery record joins it`() {
        val settled = orders.getValue(OrderState.SETTLED)

        assertTrue(PaymentCheck.INVOICE_AMOUNT in settled.paymentChecksNotPerformedHere)
        assertTrue(
            settled.deliveryChecksNotPerformedHere.isNotEmpty(),
            "§17 item 7 asks for the two hashes *plus* §10.2's encryption parameters, and this " +
                "library performs no encryption at all",
        )
        assertTrue(settled.deliveryChecksPerformed.isNotEmpty())
    }

    /** An order before `paid` claims nothing at all, rather than claiming an empty success. */
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
