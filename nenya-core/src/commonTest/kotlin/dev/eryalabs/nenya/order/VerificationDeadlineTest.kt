package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.delivery.DeliveryException
import dev.eryalabs.nenya.delivery.DeliveryRejection
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.seam.FakeClock
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §11.2's verification deadline out of `released` (revision `1.6`, the operator's decision E).
 *
 * ### The gap it closes, which is a real one and not a tidy-up
 *
 * Before this row an order could sit in `released` for ever, and three quite different things put
 * it there. A blob served at a length that disagrees with `size` is refused by §10.4
 * (`SIZE_DISAGREEMENT`) — and that is **not** one of `DeliveryFailure`'s three values, because it
 * is not a hash mismatch and the same blob may be re-served correctly, so there is no
 * `DeliveryRefused` to raise for it. A dead URL produces nothing at all. A buyer who never looks
 * produces nothing at all. In each case `ClockChecked` at `released` was `WRONG_STATE_FOR_EVENT`
 * and the order never moved again: the buyer's money had gone, the key had been released, and the
 * order's own record said it was still live.
 *
 * ### Why the deadline is local, and anchored where it is
 *
 * §11.2 makes it local rather than a fourth wire term, because at `released` the only outstanding
 * act is the buyer's own computation of `x` and `ox`; a term on the wire exists so that two
 * parties can agree in advance on when an act **the counterparty** owes is judged, and there is no
 * such act here. `deliver_by` alone would not do: a release sent one second before `deliver_by`
 * would be disputed one second later. So the anchor is the moment of release, falling back to the
 * moment of payment and then to `deliver_by`, and where none of the three was ever recorded the
 * order reports that it has **no** deadline rather than that it is pending — §11.2 requires that
 * distinction to reach the user, which is why it is a separate refusal here and not a silence.
 *
 * Every deadline below is computed from the order's own recorded readings and this library's
 * published window, never from a number written into the test, so a change to either constant
 * moves the assertions with it.
 */
class VerificationDeadlineTest {

    private companion object {

        /** §11.2's window, read from the library rather than restated. */
        val WINDOW: Long = OrderMachine.DEFAULT_VERIFICATION_TIMEOUT_SECONDS

        /**
         * How long after `paid` the anchor probe releases the deliverable.
         *
         * Strictly between zero and `DELIVER_BY - BEFORE_DEADLINES`, so that the three candidate
         * anchors — the reading at release, the reading at `paid` and `deliver_by` — are three
         * different numbers in a known order. The probe asserts that order rather than assuming
         * it.
         */
        const val RELEASE_DELAY: Long = 10_000L
    }

    private fun machineAt(unixSeconds: Long): OrderMachine = OrderMachine(FakeClock(unixSeconds))

    /** A `released` order reached the ordinary way: paid and released at [OrderFixtures.BEFORE_DEADLINES]. */
    private fun released(): Order = OrderFixtures.orders().getValue(OrderState.RELEASED)

    /** The release event every chain below uses; §10.3's four operands all match. */
    private fun release(): OrderEvent =
        OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.PROVIDER)

    // ---------------------------------------------------------------- gap G4, and the boundary

    /**
     * The gap, end to end: a blob of the wrong length is refused by §10.4, is not a
     * `DeliveryFailure`, and the order it leaves behind now has a moment at which it leaves
     * `released`.
     *
     * The boundary is probed at `deadline - 1`, `deadline` and `deadline + 1`. §11.2 says the
     * clock "passes" a deadline and fixes no tie-break, and this library takes reached-or-passed
     * everywhere else; the middle case is what pins that choice here too.
     */
    @JsName("a_served_blob_of_the_wrong_length_is_no_hash_mismatch_and_leaves_released_at_the_deadline")
    @Test
    fun `a served blob of the wrong length is no hash mismatch, and leaves released at the deadline`() {
        val released = released()
        val commitment = OrderFixtures.blob.commitment
        val servedElsewhere = OrderFixtures.otherBlob.served
        assertNotEquals(
            OrderFixtures.blob.served.size,
            servedElsewhere.size,
            "the two generated blobs must differ in length, or §10.4's size rule is never reached " +
                "and this control proves nothing",
        )

        val refused = assertFailsWith<DeliveryException> {
            ServedBytesVerified.verifyServedBytes(commitment, servedElsewhere)
        }
        assertEquals(
            DeliveryRejection.SIZE_DISAGREEMENT,
            refused.reason,
            "§10.4: an implementation MUST refuse a blob whose length disagrees with `size`",
        )

        // And there is nothing for the buyer to report: §10.4 has three steps, a length
        // disagreement is none of them, and `DeliveryFailure` carries exactly those three. So no
        // `DeliveryRefused` exists for this blob and the order stays where it is — which is the
        // whole of gap G4 and the reason the deadline below has to exist.
        assertEquals(
            3,
            DeliveryFailure.entries.size,
            "§10.4 has three steps; a fourth constant here would mean this refusal had quietly " +
                "become a hash mismatch",
        )
        assertTrue(
            DeliveryFailure.entries.none { it.name == DeliveryRejection.SIZE_DISAGREEMENT.name },
            "a size disagreement is not a hash mismatch (§10.4): it says nothing about whether " +
                "the provider committed to the bytes they served, and the blob may be re-served " +
                "correctly. It must not become a `DeliveryRefused`",
        )

        val releasedAt = assertNotNull(released.releasedAt, "the clock reading at release")
        val deadline = releasedAt + WINDOW

        val early = OrderFixtures.refusal(machineAt(deadline - 1L), released, OrderEvent.ClockChecked)
        assertEquals(TransitionRejection.DEADLINE_NOT_PASSED, early.reason)
        assertEquals(OrderState.RELEASED, early.order.state, "a refusal leaves the order alone")

        for (reading in listOf(deadline, deadline + 1L)) {
            val disputed =
                OrderFixtures.advanced(machineAt(reading), released, OrderEvent.ClockChecked)
            assertEquals(OrderState.DISPUTED, disputed.state, "at $reading")
            assertEquals(
                DisputeGround.VERIFICATION_DEADLINE_PASSED,
                disputed.disputeGround,
                "the ground must say the buyer never verified — not that the provider never " +
                    "released, which is what `RELEASE_DEADLINE_PASSED` tells a user",
            )
        }
    }

    // ---------------------------------------------------------------- the anchor, and its fallbacks

    /**
     * The anchor is the moment of **release**, and the probe is built so that anchoring on either
     * of the other two candidates is visible.
     *
     * The order is paid at one moment and released at a later one, so the three candidate
     * deadlines are three different numbers: `paidAt + w < releasedAt + w < deliverBy + w`. At
     * `paidAt + w` the correct answer is "not yet" and a `paidAt` anchor would have fired; at
     * `releasedAt + w` the correct answer is `disputed` and a `deliver_by` anchor would not have.
     */
    @JsName("the_deadline_is_anchored_on_the_moment_of_release")
    @Test
    fun `the deadline is anchored on the moment of release`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        val releasedAt = OrderFixtures.BEFORE_DEADLINES + RELEASE_DELAY
        val released = OrderFixtures.advanced(machineAt(releasedAt), paid, release())

        assertEquals(releasedAt, released.releasedAt)
        val onRelease = releasedAt + WINDOW
        val onPaid = assertNotNull(released.paidAt, "the chain records a `paidAt`") + WINDOW
        val onDeliverBy = assertNotNull(released.terms.deliverBy, "these terms carry one") + WINDOW
        assertTrue(
            onPaid < onRelease && onRelease < onDeliverBy,
            "the three candidate deadlines must be three different numbers in this order, or " +
                "neither assertion below can tell them apart: $onPaid, $onRelease, $onDeliverBy",
        )

        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(machineAt(onPaid), released, OrderEvent.ClockChecked).reason,
            "anchored on `paidAt` the order would already have been disputed here",
        )
        assertEquals(
            DisputeGround.VERIFICATION_DEADLINE_PASSED,
            OrderFixtures.advanced(machineAt(onRelease), released, OrderEvent.ClockChecked).disputeGround,
            "anchored on `deliver_by` the order would not have been disputed here",
        )
    }

    /**
     * First fallback: the clock said nothing at release, so `releasedAt` is null and the deadline
     * runs from `paidAt`.
     *
     * The two remaining candidates differ, and the assertion is made at the earlier of them, so an
     * implementation that skipped straight to `deliver_by` stays in `released` and fails.
     */
    @JsName("with_no_reading_at_release_the_deadline_falls_back_to_paidat")
    @Test
    fun `with no reading at release the deadline falls back to paidAt`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        // The fail-closed default: §4.6's clock with nothing to say. The order still releases.
        val released = OrderFixtures.advanced(OrderMachine(), paid, release())

        assertEquals(OrderState.RELEASED, released.state)
        assertNull(released.releasedAt, "a clock that answered unavailable records no reading")
        val onPaid = assertNotNull(released.paidAt, "the chain records a `paidAt`") + WINDOW
        val onDeliverBy = assertNotNull(released.terms.deliverBy, "these terms carry one") + WINDOW
        assertTrue(
            onPaid < onDeliverBy,
            "the two remaining candidates must differ: $onPaid, $onDeliverBy",
        )

        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(machineAt(onPaid - 1L), released, OrderEvent.ClockChecked).reason,
        )
        assertEquals(
            DisputeGround.VERIFICATION_DEADLINE_PASSED,
            OrderFixtures.advanced(machineAt(onPaid), released, OrderEvent.ClockChecked).disputeGround,
            "anchored on `deliver_by` the order would still be sitting in `released` here",
        )
    }

    /**
     * Second fallback: no reading at `paid` either, so the deadline runs from `deliver_by`.
     *
     * The control that makes it a statement rather than a coincidence is the reading at
     * `BEFORE_DEADLINES + w` — where the chain's *other* orders are disputed and this one is not,
     * because this one recorded no reading to run from.
     */
    @JsName("with_no_reading_at_paid_either_the_deadline_falls_back_to_deliver_by")
    @Test
    fun `with no reading at paid either the deadline falls back to deliver_by`() {
        val silent = OrderMachine()
        val awaiting = OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT)
        val paid = OrderFixtures.advanced(
            silent,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertNull(paid.paidAt, "a clock that answered unavailable records no time of payment")
        val released = OrderFixtures.advanced(silent, paid, release())
        assertNull(released.releasedAt)

        val onDeliverBy = assertNotNull(released.terms.deliverBy, "these terms carry one") + WINDOW
        val wouldBeOnTheChainsReading = OrderFixtures.BEFORE_DEADLINES + WINDOW
        assertTrue(
            wouldBeOnTheChainsReading < onDeliverBy,
            "the candidates must differ, or the reading below proves nothing",
        )

        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(
                machineAt(wouldBeOnTheChainsReading),
                released,
                OrderEvent.ClockChecked,
            ).reason,
            "there is no clock reading on this order at all; `deliver_by` is the only anchor left",
        )
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(machineAt(onDeliverBy - 1L), released, OrderEvent.ClockChecked).reason,
        )
        assertEquals(
            DisputeGround.VERIFICATION_DEADLINE_PASSED,
            OrderFixtures.advanced(machineAt(onDeliverBy), released, OrderEvent.ClockChecked).disputeGround,
        )
    }

    /**
     * The residual case §11.2 names: no reading at `paid`, none at release, and no `deliver_by`.
     *
     * There is nothing to run from and §4.6 permits no substitute, so the order reports
     * [TransitionRejection.NO_DEADLINE_TO_CHECK] — "never, on the readings I have" — rather than
     * [TransitionRejection.DEADLINE_NOT_PASSED], which would read to a user as "not yet". §11.2
     * requires the difference to reach them, and the row deliberately does not claim that an order
     * never stays in `released` indefinitely.
     */
    @JsName("with_no_reading_anywhere_and_no_deliver_by_there_is_no_deadline_to_check")
    @Test
    fun `with no reading anywhere and no deliver_by there is no deadline to check`() {
        val terms = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val silent = OrderMachine()
        val awaiting = OrderFixtures.orders(terms).getValue(OrderState.AWAITING_PAYMENT)
        val paid = OrderFixtures.advanced(
            silent,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        val released = OrderFixtures.advanced(silent, paid, release())

        assertNull(released.releasedAt)
        assertNull(released.paidAt)
        assertNull(released.terms.deliverBy)

        val refusal = OrderFixtures.refusal(
            OrderFixtures.machineAfterDeadlines(),
            released,
            OrderEvent.ClockChecked,
        )
        assertEquals(TransitionRejection.NO_DEADLINE_TO_CHECK, refusal.reason)
        assertEquals(OrderState.RELEASED, refusal.order.state)
    }

    // ---------------------------------------------------------------- §4.6, and the arithmetic

    /** §4.6 — a silent clock and a broken one each check no deadline, and say which they are. */
    @JsName("a_silent_or_broken_clock_checks_no_verification_deadline_and_says_which")
    @Test
    fun `a silent or broken clock checks no verification deadline, and says which`() {
        val released = released()

        val silent = OrderFixtures.refusal(OrderMachine(), released, OrderEvent.ClockChecked)
        assertEquals(TransitionRejection.CLOCK_UNAVAILABLE, silent.reason)
        assertEquals(OrderState.RELEASED, silent.order.state)

        for (reading in listOf(-1L, Long.MIN_VALUE)) {
            val broken = OrderFixtures.refusal(machineAt(reading), released, OrderEvent.ClockChecked)
            assertEquals(TransitionRejection.CLOCK_READING_BEFORE_EPOCH, broken.reason, "reading $reading")
            assertEquals(OrderState.RELEASED, broken.order.state, "reading $reading")
        }
    }

    /**
     * The injected clock is the embedding client's, so a `releasedAt` at the end of time is
     * constructible and the window added to it does not fit in a `Long`.
     *
     * Plain `+` would wrap to a deadline in the distant past that every reading has passed, and
     * dispute the order the instant it was released. `on` is total over every pair (§11.2), so the
     * honest answer is a named refusal rather than an exception — and the exact edge is pinned from
     * both sides, since a deadline landing **on** `Long.MAX_VALUE` is representable and reachable.
     */
    @JsName("a_releasedat_at_the_end_of_time_refuses_rather_than_throwing")
    @Test
    fun `a releasedAt at the end of time refuses rather than throwing`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)

        val extreme = machineAt(Long.MAX_VALUE)
        val releasedAtTheEnd = OrderFixtures.advanced(extreme, paid, release())
        assertEquals(Long.MAX_VALUE, releasedAtTheEnd.releasedAt)
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(extreme, releasedAtTheEnd, OrderEvent.ClockChecked).reason,
        )
        assertEquals(null, checkedDeadline(Long.MAX_VALUE, WINDOW))

        val releasedOnTheEdge =
            OrderFixtures.advanced(machineAt(Long.MAX_VALUE - WINDOW), paid, release())
        assertEquals(Long.MAX_VALUE, checkedDeadline(Long.MAX_VALUE - WINDOW, WINDOW))
        assertEquals(
            DisputeGround.VERIFICATION_DEADLINE_PASSED,
            OrderFixtures.advanced(extreme, releasedOnTheEdge, OrderEvent.ClockChecked).disputeGround,
        )
    }

    /**
     * §11.2 requires an implementation to "apply and display" a window of its own. A zero or
     * negative one is not a window: it disputes the order the instant the key reaches the buyer,
     * before any download could have finished.
     */
    @JsName("a_machine_cannot_be_built_with_a_non_positive_verification_timeout")
    @Test
    fun `a machine cannot be built with a non-positive verification timeout`() {
        for (timeout in listOf(0L, -1L, Long.MIN_VALUE)) {
            val failure = assertFailsWith<OrderStateException> {
                OrderMachine(
                    FakeClock(OrderFixtures.BEFORE_DEADLINES),
                    OrderMachine.DEFAULT_RELEASE_TIMEOUT_SECONDS,
                    timeout,
                )
            }
            assertEquals(OrderStateRejection.VERIFICATION_TIMEOUT_NOT_POSITIVE, failure.reason, "$timeout")
        }
        // The positive control: one second is a legal window, so the refusal is about the sign.
        OrderMachine(FakeClock(OrderFixtures.BEFORE_DEADLINES), 1L, 1L)
    }

    // ---------------------------------------------------------------- the race, and §12 item 11

    /**
     * The race §11.2 leaves open, answered the same way `paid` already answers it: the **event**
     * decides.
     *
     * A buyer whose verification finishes after the deadline, but before this client next reads
     * its clock, settles the order. The deadline is not a state the order is in — it is a question
     * [OrderEvent.ClockChecked] asks — and the answer to "did the bytes verify" does not become
     * "no" because nobody asked in time.
     */
    @JsName("a_verification_arriving_after_the_deadline_still_settles")
    @Test
    fun `a verification arriving after the deadline still settles`() {
        val released = released()
        val deadline = assertNotNull(released.releasedAt) + WINDOW
        val late = machineAt(deadline + 1_000L)

        assertEquals(
            DisputeGround.VERIFICATION_DEADLINE_PASSED,
            OrderFixtures.advanced(late, released, OrderEvent.ClockChecked).disputeGround,
            "the clock has passed it, so a clock check would dispute",
        )
        val settled = OrderFixtures.advanced(
            late,
            released,
            OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
        )
        assertEquals(OrderState.SETTLED, settled.state)
    }

    /**
     * §12 item 11 — a clock reading taken at a moment of this order's life is a correlation handle
     * for anyone who later reads the log, so it is never printed.
     */
    @JsName("an_order_never_prints_the_moment_it_was_released")
    @Test
    fun `an order never prints the moment it was released`() {
        val orders = OrderFixtures.orders()
        val released = orders.getValue(OrderState.RELEASED)
        val printed = assertNotNull(released.releasedAt, "there must be a reading to leak").toString()

        for ((state, order) in orders) {
            assertFalse(order.toString().contains(printed), "$state printed a clock reading")
        }
    }

    /**
     * §4.6 — the deadline fires on the injected clock and never on a counterparty's `created_at`.
     *
     * §7.1 randomises gift-wrap timestamps into the past on purpose, so a `created_at` is not
     * merely untrusted: it is deliberately wrong. Every rumor here claims a time well past the
     * deadline and the order does not move until the clock does.
     */
    @JsName("the_verification_deadline_fires_on_the_injected_clock_and_never_on_a_created_at")
    @Test
    fun `the verification deadline fires on the injected clock and never on a created_at`() {
        val released = released()
        val deadline = assertNotNull(released.releasedAt) + WINDOW
        val early = machineAt(deadline - 1L)

        for (event in listOf(
            OrderEvent.ChatMessage(Party.PROVIDER, createdAt = deadline + 100_000L),
            OrderEvent.StatusUpdate(OrderState.DISPUTED, Party.PROVIDER, createdAt = deadline + 100_000L),
            OrderEvent.ShippingUpdate(Party.PROVIDER, createdAt = deadline + 100_000L),
        )) {
            assertEquals(
                OrderState.RELEASED,
                OrderFixtures.refusal(early, released, event).order.state,
                "a counterparty's claim about the time moved the order",
            )
        }
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(early, released, OrderEvent.ClockChecked).reason,
        )
    }

    // ---------------------------------------------------------------- non-vacuity floor

    /**
     * The floor: the `(state × event)` cross-product `TransitionTotalityTest` runs really does
     * produce `(released, disputed)` from [OrderEvent.ClockChecked], a non-zero number of times.
     *
     * It is the one thing that whole proof cannot say for itself. `(released, disputed)` is
     * already produced by `DeliveryRefused`, so the set equality there is satisfied with or
     * without this edge, and at the clock reading the fixtures used before revision `1.6` the edge
     * was never reached — the branch could have been deleted and *that proof* stayed green. (The
     * tests above would not have: they exercise the edge directly.) This asserts that the reading
     * the cross-product runs at is past the verification deadline, which is what puts the new row
     * inside the totality proof as well as beside it.
     */
    @JsName("the_cross_product_reaches_released_to_disputed_through_a_clock_check")
    @Test
    fun `the cross-product reaches released to disputed through a clock check`() {
        val machine = OrderFixtures.machineAfterDeadlines()
        var produced = 0

        for (order in OrderFixtures.orders().values + OrderFixtures.orders(OrderFixtures.freeTerms()).values) {
            if (order.state != OrderState.RELEASED) continue
            val outcome = machine.on(order, OrderEvent.ClockChecked)
            if (outcome is OrderOutcome.Advanced && outcome.order.state == OrderState.DISPUTED) {
                produced++
            }
        }

        assertTrue(
            produced > 0,
            "the cross-product's clock reading never passed a verification deadline, so the one " +
                "edge revision `1.6` adds is the one edge the totality proof does not exercise",
        )
    }
}
