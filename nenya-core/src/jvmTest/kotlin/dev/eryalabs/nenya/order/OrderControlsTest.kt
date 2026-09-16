package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.LyingWallet
import dev.eryalabs.nenya.seam.SeamFixtures
import dev.eryalabs.nenya.seam.WalletPaymentState
import dev.eryalabs.nenya.seam.provided
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The negative controls §18 names for this row, and the ones §8, §7 and §4.6 imply.
 *
 * Every one asserts the *reason* rather than merely a failure: "it did not move" is satisfied by a
 * transition function that never moves anything, and the two deadlock probes below are the exact
 * case where a naive cross-product test passes and every real order stalls.
 */
class OrderControlsTest {

    private val machine = OrderFixtures.machineBeforeDeadlines()

    // ---------------------------------------------------------------- the two deadlock probes

    /**
     * §18's §8.5 probe — a **fee-bearing** order reaches `awaiting_payment` and then `paid`.
     *
     * §8.5 is explicit that a rule rejecting the fee `type=2` while the order is still `committed`
     * would make `awaiting_payment` unreachable for every fee-bearing order, and would make the
     * specification's own worked order (Appendix A, step 6) illegal. Revision `1.1` was worded
     * that way; this is the test that says `1.2` is not.
     */
    @Test
    fun `a fee-bearing order reaches awaiting_payment and then paid`() {
        val terms = OrderFixtures.TERMS
        assertTrue(terms.split.feePayeeRequired, "this probe needs an order that owes a fee")
        assertEquals(setOf(Payee.PROVIDER, Payee.FEE), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val awaiting = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(OrderState.PAID, paid.state)
    }

    /**
     * §18's §8.3 probe — an order whose `fee_msat` computes to `0` reaches `paid` with **no** fee
     * invoice and **no** fee receipt.
     *
     * §8.3's own example: `bps = 1`, `price_msat = 3000`, `floor(3000 × 1 / 10000) = 0`. The terms
     * *name* a fee payee and no fee invoice may legally exist. An implementation requiring an
     * invoice per **named** payee passes a naive cross-product test — every event it knows about
     * still works — and deadlocks every such order into `expired`.
     */
    @Test
    fun `a zero-fee order reaches paid with no fee invoice and no fee receipt`() {
        val terms = OrderFixtures.zeroFeeTerms
        assertTrue(terms.split.term.namesRecipient, "the terms must *name* a fee payee")
        assertEquals(Msat.ZERO, terms.split.fee, "and the computed fee must still be zero (§8.3)")
        assertEquals(setOf(Payee.PROVIDER), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val awaiting = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER)),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        assertEquals(OrderState.PAID, paid.state)
    }

    /** §8.6 and §8.3: a fee request for an expected amount of `0` MUST be rejected, not ignored. */
    @Test
    fun `a zero-fee order refuses a fee payment request and a fee receipt`() {
        val orders = OrderFixtures.orders(OrderFixtures.zeroFeeTerms)

        val request = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
        )
        assertEquals(TransitionRejection.PAYMENT_REQUEST_NOT_REQUIRED, request.reason)

        val receipt = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(OrderFixtures.receipt(Payee.PROVIDER, 0), OrderFixtures.receipt(Payee.FEE, 1)),
            ),
        )
        assertEquals(TransitionRejection.RECEIPT_NOT_REQUIRED, receipt.reason)
    }

    /**
     * §8.1 — a `type=1` with **no** `fee` tag opens an order at zero fee and MUST NOT be refused
     * as incomplete terms. Every fee invoice for it MUST subsequently be refused.
     */
    @Test
    fun `a proposal with no fee tag opens at zero fee and is not refused as incomplete`() {
        val terms = OrderFixtures.absentFeeTerms
        assertEquals(FeeTerm.Absent, terms.split.term)
        assertEquals(Msat.ZERO, terms.split.fee)

        val opened = machine.open(OrderEvent.Proposal(terms))
        assertEquals(OrderState.PROPOSED, opened.state)
        assertEquals(setOf(Payee.PROVIDER), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER, Payee.FEE)),
        )
        assertEquals(TransitionRejection.PAYMENT_REQUEST_NOT_REQUIRED, refusal.reason)
    }

    /** A required receipt still outstanding leaves the order where it was, with a named reason. */
    @Test
    fun `an incomplete receipt set leaves the order awaiting_payment`() {
        val orders = OrderFixtures.orders()
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        assertEquals(TransitionRejection.RECEIPTS_INCOMPLETE, refusal.reason)
        assertEquals(OrderState.AWAITING_PAYMENT, refusal.order.state)
    }

    /**
     * §8.6's non-custodial rule, in the form this library can actually enforce today: one payment
     * offered as evidence of two.
     *
     * Two distinct invoices cannot share a payment hash, so a receipt set in which the provider
     * and the fee recipient present the **same** hash and the **same** preimage is a single
     * payment being counted twice — the split-a-combined-invoice shape §8.6 says NENYA-1 has no
     * version of. §9.2 check 1 would also catch it and needs a persisted `type=2` store this
     * library does not have; this check needs only the arithmetic already done here.
     */
    @Test
    fun `one payment offered as evidence for both payees is refused`() {
        val preimage = OrderFixtures.preimage()
        val hash = OrderFixtures.paymentHashOf(preimage)
        val onePayment = setOf(
            VerifiedPayment.verify(Payee.PROVIDER, hash, preimage),
            VerifiedPayment.verify(Payee.FEE, hash, preimage),
        )

        val refusal = OrderFixtures.refusal(
            machine,
            OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(onePayment),
        )
        assertEquals(TransitionRejection.RECEIPT_PAYMENT_DUPLICATED, refusal.reason)
        assertEquals(OrderState.AWAITING_PAYMENT, refusal.order.state)
    }

    /**
     * The shared payment is reported as the shared payment even where a *second* rule also
     * applies.
     *
     * Offered to a zero-fee order, `{provider/H, fee/H}` breaks §8.6's two-payments rule **and**
     * §8.3's no-fee-invoice rule, and either refusal is correct behaviour. The reason is API a
     * client branches on, though, so the order of the two checks is pinned rather than left to
     * whichever happens to be written first: "one payment claimed twice" is the more serious
     * fact and the one a user needs told.
     */
    @Test
    fun `a shared payment is named as such even when the payee is also unrequired`() {
        val preimage = OrderFixtures.preimage()
        val hash = OrderFixtures.paymentHashOf(preimage)
        val refusal = OrderFixtures.refusal(
            machine,
            OrderFixtures.orders(OrderFixtures.zeroFeeTerms).getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    VerifiedPayment.verify(Payee.PROVIDER, hash, preimage),
                    VerifiedPayment.verify(Payee.FEE, hash, preimage),
                ),
            ),
        )
        assertEquals(TransitionRejection.RECEIPT_PAYMENT_DUPLICATED, refusal.reason)
    }

    /** §8.6 is one invoice per payee, so two receipts for one role are not a duplicate. */
    @Test
    fun `two receipts for the same payee are refused`() {
        val orders = OrderFixtures.orders()
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, 0),
                    OrderFixtures.receipt(Payee.PROVIDER, 1),
                ),
            ),
        )
        assertEquals(TransitionRejection.RECEIPT_PAYEE_DUPLICATED, refusal.reason)
    }

    // ---------------------------------------------------------------- §11.2's silent messages

    /**
     * §11.2 — a `kind:16` `type=6` private bid and a `kind:14` chat message "MUST advance no
     * state, in any state, from any key".
     *
     * Enumerated over all eleven states and all three keys rather than spot-checked, because that
     * is what the sentence says. §7.4's reserved `type=4` rides along: v1 MUST ignore it on read.
     */
    @Test
    fun `a private bid, a chat message and a type=4 advance nothing from every state`() {
        val orders = OrderFixtures.orders()
        var checked = 0
        for (state in OrderState.entries) {
            val order = orders.getValue(state)
            for (party in Party.entries) {
                for (event in listOf(
                    OrderEvent.PrivateBid(party, createdAt = OrderFixtures.AFTER_DEADLINES),
                    OrderEvent.ChatMessage(party, createdAt = OrderFixtures.AFTER_DEADLINES),
                    OrderEvent.ShippingUpdate(party, createdAt = OrderFixtures.AFTER_DEADLINES),
                )) {
                    checked++
                    val refusal = OrderFixtures.refusal(machine, order, event)
                    assertEquals(
                        TransitionRejection.EVENT_ADVANCES_NO_STATE,
                        refusal.reason,
                        "$event at $state",
                    )
                    assertEquals(state, refusal.order.state)
                }
            }
        }
        assertEquals(OrderState.entries.size * Party.entries.size * 3, checked)
    }

    // ---------------------------------------------------------------- §4.6, the injected clock

    /**
     * §11.2's `paid → disputed` fires when the **injected clock** passes `deliver_by` — and does
     * not fire because a counterparty's `created_at` says so (§4.6).
     *
     * §7.1 randomises gift-wrap timestamps up to two days into the past on purpose, so a
     * `created_at` is not merely untrusted, it is deliberately wrong. Here every rumor claims a
     * time well past `deliver_by` and the injected clock says otherwise; the order does not move
     * until the clock does.
     */
    @Test
    fun `the release deadline fires on the injected clock and never on a counterparty's created_at`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)

        for (event in listOf(
            OrderEvent.ChatMessage(Party.PROVIDER, createdAt = OrderFixtures.AFTER_DEADLINES),
            OrderEvent.StatusUpdate(
                OrderState.DISPUTED,
                Party.PROVIDER,
                createdAt = OrderFixtures.AFTER_DEADLINES,
            ),
        )) {
            assertEquals(OrderState.PAID, OrderFixtures.refusal(machine, paid, event).order.state)
        }

        val early = OrderFixtures.refusal(machine, paid, OrderEvent.ClockChecked)
        assertEquals(TransitionRejection.DEADLINE_NOT_PASSED, early.reason)

        val late = OrderFixtures.advanced(
            OrderFixtures.machineAfterDeadlines(),
            paid,
            OrderEvent.ClockChecked,
        )
        assertEquals(OrderState.DISPUTED, late.state)
        assertEquals(DisputeGround.RELEASE_DEADLINE_PASSED, late.disputeGround)
    }

    /** §11.2's `→ expired` rows, on the same clock and with the same refusal before it passes. */
    @Test
    fun `the acceptance deadline fires only once the injected clock passes it`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)

        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(machine, proposed, OrderEvent.ClockChecked).reason,
        )
        assertEquals(
            OrderState.EXPIRED,
            OrderFixtures.advanced(
                OrderFixtures.machineAfterDeadlines(),
                proposed,
                OrderEvent.ClockChecked,
            ).state,
        )
    }

    /**
     * §4.6 makes the injected clock authoritative and this library has no fallback. The
     * fail-closed default answers `Unavailable`, and the order simply does not expire — it does
     * not expire against an ambient clock either, which is the failure this refusal exists to make
     * impossible.
     */
    @Test
    fun `a fail-closed clock expires nothing and says so`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val refusal = OrderFixtures.refusal(OrderMachine(), proposed, OrderEvent.ClockChecked)

        assertEquals(TransitionRejection.CLOCK_UNAVAILABLE, refusal.reason)
        assertEquals(OrderState.PROPOSED, refusal.order.state)
    }

    /**
     * A clock answering before 1970 is broken, and this library fails closed on it just as it does
     * on a silent one — with its own reason, because "inject a clock" and "the clock you injected
     * is wrong" are different fixes. Every deadline edge is probed: `→ expired` from `proposed`,
     * `paid → disputed` on `deliver_by`, and `paid → disputed` on the own-release timeout, where a
     * paid order under the broken clock must also have recorded no `paidAt`.
     *
     * Before this rule the reading was used as given, so `-1` compared below every deadline and
     * came back `DEADLINE_NOT_PASSED` — the right state for the wrong reason, which is exactly what
     * asserting the reason rather than the state catches.
     */
    @Test
    fun `a clock reading before 1970 advances and expires nothing and says why`() {
        for (reading in listOf(-1L, Long.MIN_VALUE)) {
            val broken = OrderMachine(FakeClock(reading))
            val orders = OrderFixtures.orders()

            for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED, OrderState.PAID)) {
                val refusal = OrderFixtures.refusal(broken, orders.getValue(state), OrderEvent.ClockChecked)
                assertEquals(TransitionRejection.CLOCK_READING_BEFORE_EPOCH, refusal.reason, "at $state, reading $reading")
                assertEquals(state, refusal.order.state, "at $state, reading $reading")
            }

            val noDeliverBy = OrderTerms.of(
                OrderFixtures.PRICE,
                FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
                expiration = OrderFixtures.EXPIRATION,
                deliverBy = null,
            )
            val awaiting = OrderFixtures.orders(noDeliverBy).getValue(OrderState.AWAITING_PAYMENT)
            val paid = OrderFixtures.advanced(broken, awaiting, OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()))
            assertEquals(OrderState.PAID, paid.state, "verified receipts still move the order; no deadline is involved")
            assertEquals(null, paid.paidAt, "a broken clock's reading must not be recorded as the time of payment")
            val refusal = OrderFixtures.refusal(broken, paid, OrderEvent.ClockChecked)
            assertEquals(TransitionRejection.CLOCK_READING_BEFORE_EPOCH, refusal.reason, "reading $reading")
            assertEquals(OrderState.PAID, refusal.order.state)
        }

        assertEquals(
            OrderState.EXPIRED,
            OrderFixtures.advanced(
                OrderMachine(FakeClock(OrderFixtures.AFTER_DEADLINES)),
                OrderFixtures.orders().getValue(OrderState.PROPOSED),
                OrderEvent.ClockChecked,
            ).state,
            "and the refusal is about the sign of the reading, not about the clock being a fake",
        )
    }

    /** Terms carrying no `expiration` have no `→ expired` edge to fire, and the refusal says so. */
    @Test
    fun `terms with no expiration expire on nothing`() {
        val terms = OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS))
        val proposed = OrderFixtures.machineAfterDeadlines().open(OrderEvent.Proposal(terms))
        val refusal = OrderFixtures.refusal(
            OrderFixtures.machineAfterDeadlines(),
            proposed,
            OrderEvent.ClockChecked,
        )

        assertEquals(TransitionRejection.NO_DEADLINE_TO_CHECK, refusal.reason)
    }

    /**
     * §11.2 — where the accepted terms carry no `deliver_by`, an implementation MUST apply a
     * release timeout of its own and MUST NOT leave the order in `paid` indefinitely.
     *
     * Local policy, so it is injected: the timeout runs from the clock reading taken when the
     * order became `paid`, and both are pinned here.
     */
    @Test
    fun `an order with no deliver_by disputes on this implementation's own release timeout`() {
        val terms = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val timeout = 2L * 24L * 60L * 60L
        val building = OrderMachine(FakeClock(OrderFixtures.BEFORE_DEADLINES), timeout)
        val paid = OrderFixtures.orders(terms).getValue(OrderState.PAID)
        assertNotNull(paid.paidAt, "the clock reading at `paid` is what the timeout runs from")

        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(building, paid, OrderEvent.ClockChecked).reason,
        )

        val afterTimeout = OrderMachine(
            FakeClock(paid.paidAt!! + timeout + 1L),
            timeout,
        )
        val disputed = OrderFixtures.advanced(afterTimeout, paid, OrderEvent.ClockChecked)
        assertEquals(OrderState.DISPUTED, disputed.state)
        assertEquals(DisputeGround.RELEASE_DEADLINE_PASSED, disputed.disputeGround)
    }

    /** §7.5 — `expiration` MUST fall strictly before `deliver_by`, and a violation is rejected. */
    @Test
    fun `terms whose expiration is not strictly before deliver_by are rejected`() {
        for (deliverBy in listOf(OrderFixtures.EXPIRATION, OrderFixtures.EXPIRATION - 1L)) {
            val failure = assertFailsWith<OrderStateException> {
                OrderTerms.of(OrderFixtures.PRICE, FeeTerm.Absent, OrderFixtures.EXPIRATION, deliverBy)
            }
            assertEquals(OrderStateRejection.DEADLINES_INVERTED, failure.reason)
        }
    }

    // ---------------------------------------------------------------- §7.6 and §10.3

    /** §7.6 — acceptance is a `type=3` from the **provider's** key, and from nobody else's. */
    @Test
    fun `an acceptance from the buyer's own key is refused as the wrong sender`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val refusal = OrderFixtures.refusal(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.BUYER, OrderFixtures.TERMS),
        )
        assertEquals(TransitionRejection.WRONG_SENDER, refusal.reason)
    }

    /**
     * §7.6 — an acceptance carrying different terms is a **counter-proposal**, and one carrying no
     * terms at all is not an acceptance either.
     */
    @Test
    fun `an acceptance with altered terms, or none, is refused as a counter-proposal`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)

        for (asserted in listOf(OrderFixtures.zeroFeeTerms, null)) {
            val refusal = OrderFixtures.refusal(
                machine,
                proposed,
                OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, asserted),
            )
            assertEquals(TransitionRejection.TERMS_NOT_IDENTICAL, refusal.reason, "asserted=$asserted")
        }
    }

    /**
     * §7.6 fixes the terms an acceptance must carry as `item`, `amount_msat`, `fee` and
     * `deliver_by` — and **not** `expiration`, which §7.5 makes the buyer's own acceptance
     * deadline, evaluated against the buyer's own clock (§4.6).
     *
     * This is the positive control the negative one above cannot supply: every other acceptance
     * fixture passes the *same* `OrderTerms` instance, so value-versus-reference is never
     * exercised on the happy path, and a comparison that also demanded `expiration` would refuse
     * every conformant acceptance and deadlock the order at `proposed` until it expired. It fails
     * closed, which is exactly why it would ship.
     */
    @Test
    fun `an acceptance echoing only the four terms section 7 6 names is accepted`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val echoed = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = null,
            deliverBy = OrderFixtures.DELIVER_BY,
        )
        assertNotEquals(OrderFixtures.TERMS, echoed, "the two must differ, or this proves nothing")

        val accepted = OrderFixtures.advanced(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, echoed),
        )
        assertEquals(OrderState.ACCEPTED, accepted.state)
        assertEquals(
            OrderFixtures.TERMS,
            accepted.terms,
            "the order keeps the terms it was proposed with; §7.6 makes altered terms a new order",
        )
    }

    /** A `deliver_by` that differs *is* on §7.6's list, so it is a counter-proposal. */
    @Test
    fun `an acceptance whose deliver_by differs is refused`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val altered = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = OrderFixtures.DELIVER_BY + 1L,
        )
        val refusal = OrderFixtures.refusal(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, altered),
        )
        assertEquals(TransitionRejection.TERMS_NOT_IDENTICAL, refusal.reason)
    }

    /** §7.4, §10.1 — the `type=5` commitment is the provider's. */
    @Test
    fun `a commitment from the buyer's key is refused as the wrong sender`() {
        val accepted = OrderFixtures.orders().getValue(OrderState.ACCEPTED)
        val refusal = OrderFixtures.refusal(
            machine,
            accepted,
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.BUYER),
        )
        assertEquals(TransitionRejection.WRONG_SENDER, refusal.reason)
    }

    /**
     * §10.3 — "Any mismatch in any of the four MUST move the order to `disputed`."
     *
     * That is a legal §11.2 `paid → disputed` pair reached by a trigger §10.3 states rather than
     * §11.2's own row, and the ground says which of the two it was.
     */
    @Test
    fun `a release that does not match the commitment disputes the order`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        val disputed = OrderFixtures.advanced(
            machine,
            paid,
            OrderEvent.DeliverableReleased(OrderFixtures.divergentRelease(), Party.PROVIDER),
        )

        assertEquals(OrderState.DISPUTED, disputed.state)
        assertEquals(DisputeGround.RELEASE_DOES_NOT_MATCH_COMMITMENT, disputed.disputeGround)
    }

    /** §7.4, §10.3 — the `kind:15` release is the provider's, whatever it carries. */
    @Test
    fun `a release from the buyer's key is refused before the identity check runs`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        val refusal = OrderFixtures.refusal(
            machine,
            paid,
            OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.BUYER),
        )
        assertEquals(TransitionRejection.WRONG_SENDER, refusal.reason)
        assertEquals(OrderState.PAID, refusal.order.state)
    }

    /** §10.4's three failure steps each dispute the order, and each says which step it was. */
    @Test
    fun `each of section 10 4's failures disputes the order and names its step`() {
        val released = OrderFixtures.orders().getValue(OrderState.RELEASED)
        val expected = mapOf(
            DeliveryFailure.SERVED_BYTES_HASH_MISMATCH to DisputeGround.SERVED_BYTES_HASH_MISMATCH,
            DeliveryFailure.BLOB_DID_NOT_DECRYPT to DisputeGround.BLOB_DID_NOT_DECRYPT,
            DeliveryFailure.PLAINTEXT_BYTES_HASH_MISMATCH to
                DisputeGround.PLAINTEXT_BYTES_HASH_MISMATCH,
        )
        assertEquals(
            DeliveryFailure.entries.toSet(),
            expected.keys,
            "every §10.4 failure must map to a ground, or one of them disputes anonymously",
        )

        for ((failure, ground) in expected) {
            val disputed = OrderFixtures.advanced(
                machine,
                released,
                OrderEvent.DeliveryRefused(failure),
            )
            assertEquals(OrderState.DISPUTED, disputed.state)
            assertEquals(ground, disputed.disputeGround)
        }
    }

    /** The two pre-`paid` `→ disputed` edges §11.2 states and gives no trigger for. */
    @Test
    fun `a locally raised dispute is legal exactly where section 11 2 gives no trigger`() {
        val orders = OrderFixtures.orders()

        for (state in listOf(OrderState.COMMITTED, OrderState.AWAITING_PAYMENT)) {
            val disputed = OrderFixtures.advanced(
                machine,
                orders.getValue(state),
                OrderEvent.LocallyDisputed,
            )
            assertEquals(OrderState.DISPUTED, disputed.state, "from $state")
            assertEquals(DisputeGround.RAISED_LOCALLY, disputed.disputeGround)
        }

        for (state in listOf(
            OrderState.PROPOSED,
            OrderState.ACCEPTED,
            OrderState.PAID,
            OrderState.RELEASED,
        )) {
            val refusal =
                OrderFixtures.refusal(machine, orders.getValue(state), OrderEvent.LocallyDisputed)
            assertEquals(TransitionRejection.WRONG_STATE_FOR_EVENT, refusal.reason, "from $state")
        }
    }

    /**
     * §10.3's binding is the `["order", ...]` tag and hash equality is a *check*, so evidence is
     * matched against the commitment **by value**.
     *
     * `DeliverableCommitment` has no `equals`, so `==` on two of them is JVM identity — which
     * would refuse a caller that rebuilt an equal commitment from the same four tags, and would
     * be a real bug for anything that persists an order and reloads it. This is the control that
     * pins the value comparison, since every other fixture threads one instance through.
     */
    @Test
    fun `evidence settles against a commitment rebuilt from the same four values`() {
        val original = OrderFixtures.blob.commitment
        val rebuilt = DeliverableCommitment(
            x = DeliverableHash.ofHex(original.x.toHex()),
            ox = DeliverableHash.ofHex(original.ox.toHex()),
            mimeType = original.mimeType,
            sizeBytes = original.sizeBytes,
        )
        assertNotSame(original, rebuilt)

        val elsewhereBuilt = DeliveryEvidence.verifyPlaintextBytes(
            ServedBytesVerified.verifyServedBytes(rebuilt, OrderFixtures.blob.served),
            OrderFixtures.blob.plaintext,
        )
        val settled = OrderFixtures.advanced(
            machine,
            OrderFixtures.orders().getValue(OrderState.RELEASED),
            OrderEvent.DeliveryVerified(elsewhereBuilt),
        )
        assertEquals(OrderState.SETTLED, settled.state)
    }

    /**
     * §11.2 says the transition function is total over every `(state, event)` pair, and the clock
     * is the embedding client's — so `paidAt` can be any non-negative `Long` it chose, including
     * `Long.MAX_VALUE`, where adding the release timeout overflows. Unchecked, the sum wraps to a
     * large negative deadline that every reading has passed, and the order would be disputed on
     * the spot; the refusal below is what proves the overflow was caught instead.
     *
     * An arithmetic exception escaping `on` would be precisely the "we forgot a case" §11.2's
     * totality rule exists to prevent. A deadline beyond every representable clock reading is one
     * no clock can pass, so the answer is a named refusal.
     */
    @Test
    fun `a paidAt at the end of time refuses rather than throwing`() {
        val terms = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val extreme = OrderMachine(FakeClock(Long.MAX_VALUE))
        val awaiting = OrderFixtures.orders(terms).getValue(OrderState.AWAITING_PAYMENT)
        val paid = OrderFixtures.advanced(
            extreme,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(Long.MAX_VALUE, paid.paidAt)

        val refusal = OrderFixtures.refusal(extreme, paid, OrderEvent.ClockChecked)
        assertEquals(TransitionRejection.DEADLINE_NOT_PASSED, refusal.reason)
        assertEquals(null, checkedDeadline(Long.MAX_VALUE, OrderMachine.DEFAULT_RELEASE_TIMEOUT_SECONDS))
    }

    /**
     * §11.2 requires an implementation with no `deliver_by` to "apply and display a release
     * timeout of its own". A zero or negative one is not a timeout: it disputes an order the
     * moment it is paid, which is a refusal to deliver wearing a deadline's clothes.
     */
    @Test
    fun `a machine cannot be built with a non-positive release timeout`() {
        for (timeout in listOf(0L, -86_400L, Long.MIN_VALUE)) {
            val failure = assertFailsWith<OrderStateException> {
                OrderMachine(FakeClock(OrderFixtures.BEFORE_DEADLINES), timeout)
            }
            assertEquals(OrderStateRejection.RELEASE_TIMEOUT_NOT_POSITIVE, failure.reason)
        }
    }

    /**
     * The exact edge of the overflow check, from both sides. A release deadline that lands on
     * `Long.MAX_VALUE` itself is representable and a clock reading of `Long.MAX_VALUE` reaches it;
     * one second later it is not representable, and the same reading is refused rather than
     * wrapping to a deadline in the distant past.
     */
    @Test
    fun `a release deadline landing exactly on Long MAX_VALUE fires and one second later refuses`() {
        val terms = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val timeout = 3_600L
        val awaiting = OrderFixtures.orders(terms).getValue(OrderState.AWAITING_PAYMENT)
        val atEndOfTime = OrderMachine(FakeClock(Long.MAX_VALUE), timeout)

        val paidOnTheEdge = OrderFixtures.advanced(
            OrderMachine(FakeClock(Long.MAX_VALUE - timeout), timeout),
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        val disputed = OrderFixtures.advanced(atEndOfTime, paidOnTheEdge, OrderEvent.ClockChecked)
        assertEquals(OrderState.DISPUTED, disputed.state)
        assertEquals(DisputeGround.RELEASE_DEADLINE_PASSED, disputed.disputeGround)

        val paidPastTheEdge = OrderFixtures.advanced(
            OrderMachine(FakeClock(Long.MAX_VALUE - timeout + 1L), timeout),
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(atEndOfTime, paidPastTheEdge, OrderEvent.ClockChecked).reason,
        )
    }

    /** The checked addition itself, at both ends of `Long`: a sum that does not fit is `null`. */
    @Test
    fun `deadline arithmetic never wraps at either end of Long`() {
        assertEquals(Long.MAX_VALUE, checkedDeadline(Long.MAX_VALUE - 5L, 5L))
        assertEquals(null, checkedDeadline(Long.MAX_VALUE - 5L, 6L))
        assertEquals(null, checkedDeadline(Long.MAX_VALUE, 1L))
        assertEquals(null, checkedDeadline(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, checkedDeadline(Long.MIN_VALUE + 5L, -5L))
        assertEquals(null, checkedDeadline(Long.MIN_VALUE + 5L, -6L))
        assertEquals(null, checkedDeadline(Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(-1L, checkedDeadline(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(OrderFixtures.DELIVER_BY, checkedDeadline(OrderFixtures.EXPIRATION, OrderFixtures.DELIVER_BY - OrderFixtures.EXPIRATION))
    }

    /**
     * §4.3 fixes a timestamp as a non-negative integer, and §7.5's two deadlines are timestamps.
     * `java.time.Instant` never prevented a pre-1970 deadline either, but a `Long` makes the
     * question explicit, so it is answered at construction.
     */
    @Test
    fun `terms carrying a negative expiration or deliver_by are rejected`() {
        val negatives = listOf(-1L to null, null to -1L, Long.MIN_VALUE to OrderFixtures.DELIVER_BY, -2L to -1L)
        for ((expiration, deliverBy) in negatives) {
            val failure = assertFailsWith<OrderStateException> {
                OrderTerms.of(OrderFixtures.PRICE, FeeTerm.Absent, expiration, deliverBy)
            }
            assertEquals(OrderStateRejection.NEGATIVE_TIMESTAMP, failure.reason)
        }
        assertEquals(0L, OrderTerms.of(OrderFixtures.PRICE, FeeTerm.Absent, 0L, 1L).expiration)
    }

    /** A rumor's `created_at` is a §4.3 timestamp too: a negative one was never on the wire. */
    @Test
    fun `a rumor carrying a negative created_at is rejected`() {
        val rumors = listOf<() -> OrderEvent.Rumor>(
            { OrderEvent.Proposal(OrderFixtures.TERMS, createdAt = -1L) },
            { OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, createdAt = -1L) },
            { OrderEvent.PrivateBid(Party.BUYER, createdAt = Long.MIN_VALUE) },
            { OrderEvent.ChatMessage(Party.BUYER, createdAt = -1L) },
            { OrderEvent.ShippingUpdate(Party.PROVIDER, createdAt = -1L) },
            { OrderEvent.PaymentRequestsReceived(emptySet(), createdAt = -1L) },
            { OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER, createdAt = -1L) },
            { OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.PROVIDER, createdAt = Long.MIN_VALUE) },
        )
        for (build in rumors) {
            val failure = assertFailsWith<OrderStateException> { build() }
            assertEquals(OrderStateRejection.NEGATIVE_TIMESTAMP, failure.reason)
        }
        assertEquals(0L, OrderEvent.ChatMessage(Party.BUYER, createdAt = 0L).createdAt)
        assertEquals(null, OrderEvent.ChatMessage(Party.BUYER).createdAt)
    }

    /**
     * §11.2 says "the injected clock **passes**" a deadline and fixes no tie-break, so either
     * reading is conformant. This library takes reached-or-passed, and the choice is pinned here
     * rather than left to be discovered — it is the boundary two implementations will disagree
     * on, and every other fixture sits an hour clear of it.
     */
    @Test
    fun `a clock reading exactly at a deadline counts as having passed it`() {
        val orders = OrderFixtures.orders()

        val atExpiration = OrderMachine(FakeClock(OrderFixtures.EXPIRATION))
        assertEquals(
            OrderState.EXPIRED,
            OrderFixtures.advanced(
                atExpiration,
                orders.getValue(OrderState.PROPOSED),
                OrderEvent.ClockChecked,
            ).state,
        )
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(
                OrderMachine(FakeClock(OrderFixtures.EXPIRATION - 1L)),
                orders.getValue(OrderState.PROPOSED),
                OrderEvent.ClockChecked,
            ).reason,
        )

        val atDeliverBy = OrderMachine(FakeClock(OrderFixtures.DELIVER_BY))
        assertEquals(
            OrderState.DISPUTED,
            OrderFixtures.advanced(
                atDeliverBy,
                orders.getValue(OrderState.PAID),
                OrderEvent.ClockChecked,
            ).state,
        )
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(
                OrderMachine(FakeClock(OrderFixtures.DELIVER_BY - 1L)),
                orders.getValue(OrderState.PAID),
                OrderEvent.ClockChecked,
            ).reason,
        )
    }

    /**
     * The two named refusals nothing else pins. Both behaviours are exercised by the
     * cross-product — the order does not move — but the *reason* is API a client branches on, and
     * an unasserted constant can be swapped for a neighbour and stay green.
     */
    @Test
    fun `an incomplete payment-request set and a second proposal are refused by name`() {
        val orders = OrderFixtures.orders()

        assertEquals(
            TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE,
            OrderFixtures.refusal(
                machine,
                orders.getValue(OrderState.COMMITTED),
                OrderEvent.PaymentRequestsReceived(setOf(Payee.PROVIDER)),
            ).reason,
            "§11.2 enters `awaiting_payment` on one valid type=2 per required payee",
        )

        for (state in OrderState.entries) {
            assertEquals(
                TransitionRejection.ORDER_ALREADY_OPEN,
                OrderFixtures.refusal(
                    machine,
                    orders.getValue(state),
                    OrderEvent.Proposal(OrderFixtures.TERMS),
                ).reason,
                "§7.6: a counter-proposal is a new type=1 with a new order id; at $state",
            )
        }
    }

    // ---------------------------------------------------------------- STOP RULE 12, with a subject

    /**
     * T5's `LyingWallet`, now with a subject: it reports every payment settled, every invoice paid
     * and every balance sufficient, **and moves the order out of `awaiting_payment` not at all**.
     *
     * It lies in the most dangerous way available to it — it does not merely say the payment
     * settled, it hands over 32 well-formed bytes and calls them the preimage. That is the shape a
     * compromised or merely buggy wallet actually takes, and the naive integration reads the claim,
     * sees a preimage-shaped value and advances the order. Here the bytes are hashed, the digest is
     * not the payment hash, no `VerifiedPayment` exists, and there is therefore nothing to build a
     * `ReceiptsVerified` out of.
     */
    @Test
    fun `a wallet that reports every payment settled moves the order not at all`() {
        val truth = OrderFixtures.receipt(Payee.PROVIDER)
        val fabricated = SeamFixtures.lowerHex(SeamFixtures.bytes(Preimage.BYTE_LENGTH, stream = 99L))
        val wallet = LyingWallet(fabricated)
        val awaiting = OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT)

        val claim = wallet.paymentStatus(SeamFixtures.NOT_AN_INVOICE).provided()
        assertEquals(WalletPaymentState.CLAIMS_SETTLED, claim.state)
        assertNotNull(claim.claimedPreimageHex, "it must offer something preimage-shaped")

        val refused = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(
                Payee.PROVIDER,
                truth.paymentHash,
                Preimage.ofHex(claim.claimedPreimageHex!!),
            )
        }
        assertEquals(
            dev.eryalabs.nenya.payment.PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "§9.2 check 3: the wallet's bytes are not the preimage of this payment hash",
        )

        // Nothing the wallet said is constructible into an event, so the strongest thing an
        // integration built on it can offer is an empty receipt set — which leaves the order put.
        val outcome = OrderFixtures.refusal(machine, awaiting, OrderEvent.ReceiptsVerified(emptySet()))
        assertEquals(TransitionRejection.RECEIPTS_INCOMPLETE, outcome.reason)
        assertEquals(OrderState.AWAITING_PAYMENT, outcome.order.state)
        assertTrue(wallet.calls > 0, "the wallet must actually have been consulted")
    }

    // ---------------------------------------------------------------- §12 item 11

    /**
     * §12 item 11 and STOP RULE 14: an order's terms and its state are private-channel content and
     * MUST NOT appear in a log, a crash report, or the string representation of anything this
     * library exposes.
     *
     * A default Kotlin `toString` on `OrderTerms` would print the price straight out of `FeeSplit`;
     * this is the control that catches the debugging one somebody adds later.
     */
    @Test
    fun `neither an order nor its terms prints an amount or a deadline`() {
        val orders = OrderFixtures.orders()
        val price = OrderFixtures.PRICE.millisatoshis.toString()
        val deadline = OrderFixtures.DELIVER_BY.toString()

        assertFalse(OrderFixtures.TERMS.toString().contains(price))
        assertFalse(OrderFixtures.TERMS.toString().contains(deadline))
        for ((state, order) in orders) {
            assertFalse(order.toString().contains(price), "$state printed the price")
            assertFalse(order.toString().contains(deadline), "$state printed a deadline")
        }
    }

    /** A refusal's `detail` names a rule; it never names a byte, an amount or a deadline. */
    @Test
    fun `a refusal's detail carries no amount and no deadline`() {
        val orders = OrderFixtures.orders()
        val price = OrderFixtures.PRICE.millisatoshis.toString()
        val deadline = OrderFixtures.DELIVER_BY.toString()

        for (state in OrderState.entries) {
            val refusal = OrderFixtures.refusal(
                machine,
                orders.getValue(state),
                OrderEvent.ChatMessage(Party.BUYER),
            )
            assertFalse(refusal.detail.contains(price), "$state")
            assertFalse(refusal.detail.contains(deadline), "$state")
        }
    }
}
