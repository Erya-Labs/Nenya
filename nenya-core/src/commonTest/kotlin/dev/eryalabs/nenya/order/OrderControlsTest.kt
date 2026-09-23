package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.LyingWallet
import dev.eryalabs.nenya.seam.SeamFixtures
import dev.eryalabs.nenya.seam.WalletPaymentState
import dev.eryalabs.nenya.seam.provided
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
     * §18's §8.5 probe — a **fee-bearing** order reaches `awaiting_payment`, and then `paid`.
     *
     * §8.5 is explicit that a rule rejecting the fee `type=2` while the order is still `committed`
     * would make `awaiting_payment` unreachable for every fee-bearing order, and would make the
     * specification's own worked order (Appendix A, step 6) illegal. Revision `1.1` was worded
     * that way; this is the test that says `1.2` is not.
     *
     * The probe is **positive again on the priced chain**. T20 could only assert the negative half
     * — refused `PAYMENT_CHECKS_NOT_PERFORMED` and emphatically not `RECEIPTS_INCOMPLETE` — because
     * decision B held every priced order at `awaiting_payment` while check 3's provenance was
     * performed by nobody. It is performed now, so the order that §8.5 says must be payable is paid
     * here, which is a strictly stronger statement than "it was not told its receipts were
     * incomplete": an implementation that deadlocked the fee payee out of the required set fails
     * this by never reaching `paid` at all.
     */
    @JsName("a_fee_bearing_order_reaches_awaiting_payment_and_then_paid")
    @Test
    fun `a fee-bearing order reaches awaiting_payment and then paid`() {
        val terms = OrderFixtures.TERMS
        assertTrue(terms.split.feePayeeRequired, "this probe needs an order that owes a fee")
        assertEquals(setOf(Payee.PROVIDER, Payee.FEE), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val awaiting = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(OrderFixtures.chain(terms).requests),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )
        assertEquals(
            OrderState.PAID,
            paid.state,
            "§8.5's probe: a fee-bearing order whose two receipts have verified per §9.2 must " +
                "reach `paid`. An implementation that cannot admit the fee payee deadlocks it " +
                "into `expired` instead, which is the bug this test exists for",
        )
        assertEquals(
            setOf(Payee.PROVIDER, Payee.FEE),
            Payee.requiredPayees(paid.terms.split),
            "and it is still the fee-bearing order it started as: a `paid` order on substituted " +
                "free terms would satisfy the line above while proving nothing about §8.5",
        )
    }

    /**
     * §18's §8.3 probe — an order whose `fee_msat` computes to `0` needs **no** fee invoice and
     * **no** fee receipt, and is held short of `paid` only by §9.2's unperformed checks.
     *
     * §8.3's own example: `bps = 1`, `price_msat = 3000`, `floor(3000 × 1 / 10000) = 0`. The terms
     * *name* a fee payee and no fee invoice may legally exist. An implementation requiring an
     * invoice per **named** payee passes a naive cross-product test — every event it knows about
     * still works — and deadlocks every such order into `expired`.
     *
     * Positive again, for the same reason as the probe above: a provider-only receipt set is
     * accepted as **complete** — no fee receipt is waited for, which is the whole point — and it
     * now carries every §9.2 check that applies, so the order reaches `paid` with no fee invoice
     * and no fee receipt anywhere in the chain. `RECEIPTS_INCOMPLETE` would mean the fee payee had
     * been required after all, and never reaching `paid` is that bug's outcome.
     */
    @JsName("a_zero_fee_order_needs_no_fee_receipt_and_reaches_paid")
    @Test
    fun `a zero-fee order needs no fee receipt and reaches paid`() {
        val terms = OrderFixtures.zeroFeeTerms
        assertTrue(terms.split.term.namesRecipient, "the terms must *name* a fee payee")
        assertEquals(Msat.ZERO, terms.split.fee, "and the computed fee must still be zero (§8.3)")
        assertEquals(setOf(Payee.PROVIDER), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val awaiting = OrderFixtures.advanced(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(OrderFixtures.chain(terms).requests),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        assertEquals(
            OrderState.PAID,
            paid.state,
            "§8.3's probe: a provider-only receipt set is COMPLETE for a zero-fee order. An " +
                "implementation waiting for an invoice that may not legally exist never gets here",
        )
        assertTrue(
            paid.terms.split.term.namesRecipient,
            "and the order that reached `paid` is the one that *names* a fee payee — the shape " +
                "§8.3's failure mode is about, not free terms substituted for it",
        )
    }

    /**
     * §8.6 and §8.3: a fee request for an expected amount of `0` MUST be rejected, not ignored.
     *
     * The surplus record is a genuinely accepted one — minted by T24 for **this order id** under
     * fee-bearing terms, which is the shape a fee recipient who believes a fee is owed produces —
     * so the refusal is §8.3's rule about the *terms this order was opened with* and not an
     * accident of the fixture being unable to build the message at all.
     */
    @JsName("a_zero_fee_order_refuses_a_fee_payment_request_and_a_fee_receipt")
    @Test
    fun `a zero-fee order refuses a fee payment request and a fee receipt`() {
        val terms = OrderFixtures.zeroFeeTerms
        val orders = OrderFixtures.orders(terms)

        val request = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(
                OrderFixtures.chain(terms).requests + OrderFixtures.chain().request(Payee.FEE),
            ),
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
    @JsName("a_proposal_with_no_fee_tag_opens_at_zero_fee_and_is_not_refused_as_incomplete")
    @Test
    fun `a proposal with no fee tag opens at zero fee and is not refused as incomplete`() {
        val terms = OrderFixtures.absentFeeTerms
        assertEquals(FeeTerm.Absent, terms.split.term)
        assertEquals(Msat.ZERO, terms.split.fee)

        val opened = machine.open(OrderEvent.Proposal(OrderFixtures.ORDER_ID, terms))
        assertEquals(OrderState.PROPOSED, opened.state)
        assertEquals(setOf(Payee.PROVIDER), Payee.requiredPayees(terms.split))

        val orders = OrderFixtures.orders(terms)
        val refusal = OrderFixtures.refusal(
            machine,
            orders.getValue(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(
                OrderFixtures.chain(terms).requests + OrderFixtures.chain().request(Payee.FEE),
            ),
        )
        assertEquals(TransitionRejection.PAYMENT_REQUEST_NOT_REQUIRED, refusal.reason)
    }

    /** A required receipt still outstanding leaves the order where it was, with a named reason. */
    @JsName("an_incomplete_receipt_set_leaves_the_order_awaiting_payment")
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
     * version of.
     *
     * ### Neither check 1 nor check 3 subsumes it, and both now run
     *
     * Both receipts pass check 1: each names its **own** stored `type=2`, and the two invoice
     * strings genuinely differ — check 1 compares a receipt against its own stored invoice and asks
     * nothing about the other receipt in the set.
     *
     * Both pass check 3 too, and that is the half worth stating since the operand stopped being a
     * parameter. The fixture's two invoices at one stream carry the **same** `p` — two real
     * invoices for two different amounts, derived from one preimage by the composer decision D
     * requires, so nothing here is typed — and each receipt proves that one preimage against its
     * own invoice's own field. Check 3 asks whether the proof hashes to the hash this invoice
     * names, and for each receipt separately it does. The sentence §8.6 needs is about the *pair*,
     * and this rule is the only thing in the library that says it.
     */
    @JsName("one_payment_offered_as_evidence_for_both_payees_is_refused")
    @Test
    fun `one payment offered as evidence for both payees is refused`() {
        // The same preimage stream for both payees, so the two receipts prove one payment.
        val provider = OrderFixtures.receipt(Payee.PROVIDER, stream = 0)
        val fee = OrderFixtures.receipt(Payee.FEE, stream = 0)
        assertEquals(
            provider.payment.paymentHash,
            fee.payment.paymentHash,
            "the control is about one payment claimed twice; distinct hashes would test nothing",
        )
        assertTrue(
            PaymentCheck.INVOICE_IDENTITY in provider.checksPerformed &&
                PaymentCheck.INVOICE_IDENTITY in fee.checksPerformed,
            "and both passed check 1 against their own stored `type=2`, which is what makes this " +
                "check still load-bearing rather than subsumed",
        )
        assertNotEquals(
            OrderFixtures.invoice(OrderFixtures.ORDER_INDEX, Payee.PROVIDER),
            OrderFixtures.invoice(OrderFixtures.ORDER_INDEX, Payee.FEE),
            "§8.6's two separate invoices: if these were one string, check 1 would have caught " +
                "this set on its own and the refusal under test would prove nothing",
        )

        val refusal = OrderFixtures.refusal(
            machine,
            OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(setOf(provider, fee)),
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
    @JsName("a_shared_payment_is_named_as_such_even_when_the_payee_is_also_unrequired")
    @Test
    fun `a shared payment is named as such even when the payee is also unrequired`() {
        val refusal = OrderFixtures.refusal(
            machine,
            OrderFixtures.orders(OrderFixtures.zeroFeeTerms).getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receipt(Payee.FEE, stream = 0),
                ),
            ),
        )
        assertEquals(TransitionRejection.RECEIPT_PAYMENT_DUPLICATED, refusal.reason)
    }

    /** §8.6 is one invoice per payee, so two receipts for one role are not a duplicate. */
    @JsName("two_receipts_for_the_same_payee_are_refused")
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
    @JsName("a_private_bid_a_chat_message_and_a_type_4_advance_nothing_from_every_state")
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
    @JsName("the_release_deadline_fires_on_the_injected_clock_and_never_on_a_counterparty_s_created_at")
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
    @JsName("the_acceptance_deadline_fires_only_once_the_injected_clock_passes_it")
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
    @JsName("a_fail_closed_clock_expires_nothing_and_says_so")
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
    @JsName("a_clock_reading_before_1970_advances_and_expires_nothing_and_says_why")
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

            // Free terms, because decision B leaves no other order able to reach `paid` at all —
            // and what is under test here is the clock, not the price. `deliver_by` is absent so
            // the own-release timeout is the edge being probed, exactly as before.
            val noDeliverBy = OrderTerms.of(
                Msat.ZERO,
                FeeTerm.Absent,
                expiration = OrderFixtures.EXPIRATION,
                deliverBy = null,
            )
            val awaiting = OrderFixtures.orders(noDeliverBy).getValue(OrderState.AWAITING_PAYMENT)
            val paid = OrderFixtures.advanced(broken, awaiting, OrderEvent.ReceiptsVerified(emptySet()))
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
    @JsName("terms_with_no_expiration_expire_on_nothing")
    @Test
    fun `terms with no expiration expire on nothing`() {
        val terms = OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS))
        val proposed = OrderFixtures.machineAfterDeadlines()
            .open(OrderEvent.Proposal(OrderFixtures.ORDER_ID, terms))
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
    @JsName("an_order_with_no_deliver_by_disputes_on_this_implementation_s_own_release_timeout")
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
    @JsName("terms_whose_expiration_is_not_strictly_before_deliver_by_are_rejected")
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

    /**
     * The old door is **shut**, not narrowed: no `type=3` `status=accepted` a caller assembles
     * advances an order, whatever key it claims and whatever terms it carries.
     *
     * Four shapes, and the first is the one that used to work: the provider's key with terms
     * byte-identical to the proposal's. If that still advanced, everything below it would be a
     * narrowing rather than a closure, and gap G3 would survive — a caller that hand-built this
     * event skipped §7.6's byte comparison and §8.6's sender rule at once.
     */
    @JsName("no_status_update_announcing_accepted_advances_an_order")
    @Test
    fun `no status update announcing accepted advances an order`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val echoed = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = null,
            deliverBy = OrderFixtures.DELIVER_BY,
        )
        assertNotEquals(OrderFixtures.TERMS, echoed, "the two must differ, or this proves nothing")

        for (event in listOf(
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, OrderFixtures.TERMS),
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, echoed),
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.BUYER, OrderFixtures.TERMS),
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, null),
        )) {
            val refusal = OrderFixtures.refusal(machine, proposed, event)
            assertEquals(
                TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE,
                refusal.reason,
                "asserted=${event.assertedTerms}, from=${event.from}",
            )
            assertEquals(OrderState.PROPOSED, refusal.order.state)
        }
    }

    /**
     * §7.6, whole, through the door that now decides it: `OrderProposal.accepts` compares the seal
     * against the provider key the caller resolved, and a `type=3` sealed by anybody else is
     * refused before the order ever sees it.
     *
     * Decision H's two attackers are different people and both are exercised: the **buyer**, who
     * knows its own terms exactly and could otherwise accept its own order and then send itself the
     * `payee=provider` invoice; and a **stranger** who merely saw the order id. Each seals a
     * `type=3` carrying §7.6's four terms byte for byte, so the only thing wrong with either is who
     * sealed it — which is what makes this the sender check and not the terms comparison.
     *
     * The order-level half is the point: because `accepts` throws, there is no
     * `Acceptance.Accepted` to build an [OrderEvent.AcceptanceReceived] from, so the order cannot
     * be advanced by any means, and it is still `proposed` afterwards.
     */
    @JsName("an_acceptance_sealed_by_the_buyer_or_a_stranger_never_reaches_the_order")
    @Test
    fun `an acceptance sealed by the buyer or a stranger never reaches the order`() {
        val chain = OrderFixtures.chain()
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val provider = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX)

        // The positive control that makes the two below a *sender* test: the same four term tags,
        // sealed by the provider, accept. Each impostor message is built from
        // `chain.proposalTerms()` exactly as this one is, so the seal is the only difference.
        assertIs<Acceptance.Accepted>(
            chain.proposal.accepts(chain.update, provider),
            "the fixture's own acceptance must accept, or a refusal below proves nothing",
        )

        for (impostor in listOf(
            SettlementFixtures.buyer(OrderFixtures.ORDER_INDEX),
            SettlementFixtures.stranger(OrderFixtures.ORDER_INDEX),
        )) {
            val sealed = chain.acceptanceSealedBy(impostor)
            val refused = assertFailsWith<ChannelException> { chain.proposal.accepts(sealed, provider) }
            assertEquals(ChannelRejection.ACCEPTANCE_NOT_FROM_PROVIDER, refused.reason)

            // §7.6 refused, so there is no `Acceptance.Accepted` to build an `AcceptanceReceived`
            // out of — that is a compile-time fact, not one a test can assert. What a test *can*
            // assert is that the event a caller would reach for instead leaves the order put.
            val refusal = OrderFixtures.refusal(
                machine,
                proposed,
                OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, OrderFixtures.TERMS),
            )
            assertEquals(TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE, refusal.reason)
            assertEquals(OrderState.PROPOSED, refusal.order.state)
        }
    }

    /**
     * Gap G3, inverted — the whole reason acceptance moved into the codec.
     *
     * A `type=3` naming the **same basis points** and a **different fee recipient** is a
     * counter-proposal to `OrderProposal.accepts`, which compares §8.4's fee pair as a raw tag. It
     * was an acceptance to `OrderMachine`, whose `OrderTerms.namesTheSameDealAs` compares only the
     * points — so the same message was a counter-proposal in one half of this library and an
     * acceptance in the other, and the half that advanced the order was the one that could not see
     * the recipient. §8.6 then routes the fee payment to whoever the acceptance named.
     *
     * Now there is one answer: `CounterProposal`, no `AcceptanceReceived` to build, and the
     * `StatusUpdate` a caller might reach for instead refused. The order stays `proposed`.
     */
    @JsName("an_acceptance_naming_another_fee_recipient_is_a_counter_proposal_and_advances_nothing")
    @Test
    fun `an acceptance naming another fee recipient is a counter-proposal and advances nothing`() {
        val chain = OrderFixtures.chain()
        val swapped = ProposalFixtures.feeTag(
            OrderFixtures.OTHER_ORDER_INDEX,
            OrderFixtures.FEE_BASIS_POINTS,
        )
        assertEquals(
            chain.feeTag?.get(1),
            swapped[1],
            "the same basis points, which is what `namesTheSameDealAs` compares and all it does",
        )
        assertNotEquals(
            chain.feeTag,
            swapped,
            "and a different recipient, which is the element it cannot see",
        )

        val sealed = chain.acceptanceSealedBy(
            SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            ProposalFixtures.replacing(chain.proposalTerms(), swapped),
        )
        val answer = chain.proposal.accepts(sealed, SettlementFixtures.provider(OrderFixtures.ORDER_INDEX))
        val counter = assertIs<Acceptance.CounterProposal>(
            answer,
            "§8.4's fee pair diverged, so §7.6's answer is a counter-proposal",
        )
        assertTrue(ChannelVocabulary.FEE in counter.divergentTerms, "${counter.divergentTerms}")

        // The terms this acceptance asserts *do* satisfy `namesTheSameDealAs`, which is exactly
        // why the status-update door had to be shut rather than left with a stricter comparison.
        val asserted = OrderTerms.of(
            OrderFixtures.PRICE,
            FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = OrderFixtures.DELIVER_BY,
        )
        assertTrue(asserted.namesTheSameDealAs(OrderFixtures.TERMS), "the narrowing, made explicit")

        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val refusal = OrderFixtures.refusal(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, asserted),
        )
        assertEquals(TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE, refusal.reason)
        assertEquals(OrderState.PROPOSED, refusal.order.state)
    }

    /**
     * A checked acceptance is an ordinary value, so the one comparison §11.2 still makes is the
     * binding: an `Acceptance.Accepted` for another order advances nothing here.
     *
     * Paired with the positive half, because "it did not move" is satisfied by a machine with no
     * `→ accepted` edge at all: this order's own acceptance moves it, and records the provider key
     * §8.6 is checked against one state later.
     */
    @JsName("an_acceptance_for_another_order_is_refused_and_this_order_s_own_advances")
    @Test
    fun `an acceptance for another order is refused, and this order's own advances`() {
        val proposed = OrderFixtures.orders().getValue(OrderState.PROPOSED)
        val elsewhere = OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX).accepted
        assertNotEquals(proposed.id, elsewhere.order, "the two orders must differ in their id")

        val refusal = OrderFixtures.refusal(
            machine,
            proposed,
            OrderEvent.AcceptanceReceived(elsewhere),
        )
        assertEquals(TransitionRejection.ACCEPTANCE_FOR_ANOTHER_ORDER, refusal.reason)
        assertEquals(OrderState.PROPOSED, refusal.order.state)
        assertEquals(null, refusal.order.provider, "a refusal records nothing")

        val accepted = OrderFixtures.advanced(
            machine,
            proposed,
            OrderEvent.AcceptanceReceived(OrderFixtures.chain().accepted),
        )
        assertEquals(OrderState.ACCEPTED, accepted.state)
        assertEquals(
            SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            accepted.provider,
            "§7.6's checked key travels onto the order, which is what §8.6's `payee=provider` " +
                "rule is compared against at `committed → awaiting_payment`",
        )
        assertEquals(
            OrderFixtures.TERMS,
            accepted.terms,
            "the order keeps the terms it was proposed with; §7.6 makes altered terms a new order",
        )
    }

    /**
     * Decision I, at the order: a `payee=provider` record every check T24 makes accepts, and that
     * §11.2 still refuses because it was not sealed by **this** order's provider.
     *
     * The record is genuine — accepted under an `Acceptance.Accepted` for this same order id whose
     * resolved provider happened to be a stranger — so the settlement package has nothing left to
     * object to: the seal equals the key that acceptance was checked against, the invoice is this
     * order's own provider invoice, and the order id matches. The only operand that disagrees is
     * the key the order recorded when it advanced to `accepted`.
     *
     * Paired with the record accepted under the order's **own** acceptance, which enters
     * `awaiting_payment`, so that a check refusing everything cannot pass this.
     */
    @JsName("a_provider_request_sealed_by_another_key_is_refused_and_the_order_s_own_advances")
    @Test
    fun `a provider request sealed by another key is refused, and the order's own advances`() {
        val committed = OrderFixtures.orders().getValue(OrderState.COMMITTED)
        val chain = OrderFixtures.chain()
        val stranger = OrderFixtures.providerRequestUnderAStranger()

        assertEquals(committed.id, stranger.order, "same order id: the refusal must not be that")
        assertEquals(Payee.PROVIDER, stranger.payee)
        assertNotEquals(
            committed.provider,
            stranger.sealedBy,
            "and a different sealing key, which is the whole of the refusal",
        )
        assertEquals(
            chain.request(Payee.PROVIDER).invoice.text,
            stranger.invoice.text,
            "the same invoice too, so nothing about the amount or the expiry is what fires",
        )

        val refusal = OrderFixtures.refusal(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(setOf(stranger, chain.request(Payee.FEE))),
        )
        assertEquals(TransitionRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER, refusal.reason)
        assertEquals(OrderState.COMMITTED, refusal.order.state)

        val awaiting = OrderFixtures.advanced(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(chain.requests),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)
    }

    /**
     * §9.2 check 1 keys the store by `(order, payee)`: a record for another order is not this one's.
     *
     * Two sets, and the second is the one that discriminates. A set in which **every** record names
     * the other order is refused by a rule reading `all` as readily as by one reading `any`; the
     * mixed set — this order's provider record beside another order's fee record — is the shape a
     * client actually produces when it routes one message onto the wrong thread, and only `any`
     * catches it. Without it the set arithmetic below would find the payees complete and enter
     * `awaiting_payment` against an invoice §9.2 check 1 will never find for this order.
     */
    @JsName("a_payment_request_accepted_for_another_order_is_refused")
    @Test
    fun `a payment request accepted for another order is refused`() {
        val committed = OrderFixtures.orders().getValue(OrderState.COMMITTED)
        val chain = OrderFixtures.chain()
        val elsewhere = OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX)
        assertNotEquals(committed.id, elsewhere.request(Payee.PROVIDER).order)

        for (requests in listOf(
            elsewhere.requests,
            setOf(chain.request(Payee.PROVIDER), elsewhere.request(Payee.FEE)),
        )) {
            val refusal = OrderFixtures.refusal(
                machine,
                committed,
                OrderEvent.PaymentRequestsReceived(requests),
            )
            assertEquals(TransitionRejection.PAYMENT_REQUEST_FOR_ANOTHER_ORDER, refusal.reason)
            assertEquals(OrderState.COMMITTED, refusal.order.state)
        }
    }

    /**
     * §8.6 is one invoice per payee, and the trigger becoming a set of **records** is what makes
     * two of them for one role expressible at all.
     *
     * `AcceptedPaymentRequest.accept` refuses a second `type=2` for an `(order, payee)` pair, but it
     * asks *one* store — and this event is a set the caller assembles, so two records from two
     * stores arrive with nothing having compared them. Reducing them to payee roles, which is what
     * the required-set arithmetic does, collapses the two into one `Payee.PROVIDER` and the order
     * advances with §9.2 check 1 pointed at whichever invoice the store it later consults holds.
     *
     * The fee record is in the set deliberately: without it the order would be refused
     * `PAYMENT_REQUESTS_INCOMPLETE` and this control would pass with the new rule deleted.
     */
    @JsName("two_stored_requests_for_one_payee_are_refused")
    @Test
    fun `two stored requests for one payee are refused`() {
        val committed = OrderFixtures.orders().getValue(OrderState.COMMITTED)
        val chain = OrderFixtures.chain()
        val second = OrderFixtures.secondProviderRequest()

        assertEquals(Payee.PROVIDER, second.payee)
        assertEquals(committed.id, second.order, "same order, so that is not what fires")
        assertEquals(committed.provider, second.sealedBy, "and the right sealing key, nor that")
        assertNotEquals(
            chain.request(Payee.PROVIDER).invoice.text,
            second.invoice.text,
            "two genuinely different invoices: one of them bills for something else, which is " +
                "the whole of §8.6's rule",
        )

        val refusal = OrderFixtures.refusal(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(
                setOf(chain.request(Payee.PROVIDER), second, chain.request(Payee.FEE)),
            ),
        )
        assertEquals(TransitionRejection.PAYMENT_REQUEST_PAYEE_DUPLICATED, refusal.reason)
        assertEquals(OrderState.COMMITTED, refusal.order.state)
    }

    /** §7.4, §10.1 — the `type=5` commitment is the provider's. */
    @JsName("a_commitment_from_the_buyer_s_key_is_refused_as_the_wrong_sender")
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
    @JsName("a_release_that_does_not_match_the_commitment_disputes_the_order")
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
    @JsName("a_release_from_the_buyer_s_key_is_refused_before_the_identity_check_runs")
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
    @JsName("each_of_section_10_4_s_failures_disputes_the_order_and_names_its_step")
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
    @JsName("a_locally_raised_dispute_is_legal_exactly_where_section_11_2_gives_no_trigger")
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
    @JsName("evidence_settles_against_a_commitment_rebuilt_from_the_same_four_values")
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
    @JsName("a_paidat_at_the_end_of_time_refuses_rather_than_throwing")
    @Test
    fun `a paidAt at the end of time refuses rather than throwing`() {
        // Free terms: decision B leaves no priced order able to reach `paid`, and the arithmetic
        // under test is the clock's, which does not know what the order cost.
        val terms = OrderTerms.of(
            Msat.ZERO,
            FeeTerm.Absent,
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val extreme = OrderMachine(FakeClock(Long.MAX_VALUE))
        val awaiting = OrderFixtures.orders(terms).getValue(OrderState.AWAITING_PAYMENT)
        val paid = OrderFixtures.advanced(
            extreme,
            awaiting,
            OrderEvent.ReceiptsVerified(emptySet()),
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
    @JsName("a_machine_cannot_be_built_with_a_non_positive_release_timeout")
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
    @JsName("a_release_deadline_landing_exactly_on_long_max_value_fires_and_one_second_later_refuses")
    @Test
    fun `a release deadline landing exactly on Long MAX_VALUE fires and one second later refuses`() {
        // Free terms, for the reason the test above gives: the edge is in the deadline arithmetic.
        val terms = OrderTerms.of(
            Msat.ZERO,
            FeeTerm.Absent,
            expiration = OrderFixtures.EXPIRATION,
            deliverBy = null,
        )
        val timeout = 3_600L
        val awaiting = OrderFixtures.orders(terms).getValue(OrderState.AWAITING_PAYMENT)
        val atEndOfTime = OrderMachine(FakeClock(Long.MAX_VALUE), timeout)

        val paidOnTheEdge = OrderFixtures.advanced(
            OrderMachine(FakeClock(Long.MAX_VALUE - timeout), timeout),
            awaiting,
            OrderEvent.ReceiptsVerified(emptySet()),
        )
        val disputed = OrderFixtures.advanced(atEndOfTime, paidOnTheEdge, OrderEvent.ClockChecked)
        assertEquals(OrderState.DISPUTED, disputed.state)
        assertEquals(DisputeGround.RELEASE_DEADLINE_PASSED, disputed.disputeGround)

        val paidPastTheEdge = OrderFixtures.advanced(
            OrderMachine(FakeClock(Long.MAX_VALUE - timeout + 1L), timeout),
            awaiting,
            OrderEvent.ReceiptsVerified(emptySet()),
        )
        assertEquals(
            TransitionRejection.DEADLINE_NOT_PASSED,
            OrderFixtures.refusal(atEndOfTime, paidPastTheEdge, OrderEvent.ClockChecked).reason,
        )
    }

    /** The checked addition itself, at both ends of `Long`: a sum that does not fit is `null`. */
    @JsName("deadline_arithmetic_never_wraps_at_either_end_of_long")
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
    @JsName("terms_carrying_a_negative_expiration_or_deliver_by_are_rejected")
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
    @JsName("a_rumor_carrying_a_negative_created_at_is_rejected")
    @Test
    fun `a rumor carrying a negative created_at is rejected`() {
        val rumors = listOf<() -> OrderEvent.Rumor>(
            { OrderEvent.Proposal(OrderFixtures.ORDER_ID, OrderFixtures.TERMS, createdAt = -1L) },
            { OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, createdAt = -1L) },
            { OrderEvent.AcceptanceReceived(OrderFixtures.chain().accepted, createdAt = -1L) },
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
    @JsName("a_clock_reading_exactly_at_a_deadline_counts_as_having_passed_it")
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
    @JsName("an_incomplete_payment_request_set_and_a_second_proposal_are_refused_by_name")
    @Test
    fun `an incomplete payment-request set and a second proposal are refused by name`() {
        val orders = OrderFixtures.orders()

        assertEquals(
            TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE,
            OrderFixtures.refusal(
                machine,
                orders.getValue(OrderState.COMMITTED),
                // The provider's record alone, on an order that owes a fee as well — the fee
                // recipient's `type=2` has simply not arrived yet.
                OrderEvent.PaymentRequestsReceived(
                    setOf(OrderFixtures.chain().request(Payee.PROVIDER)),
                ),
            ).reason,
            "§11.2 enters `awaiting_payment` on one valid type=2 per required payee",
        )

        for (state in OrderState.entries) {
            assertEquals(
                TransitionRejection.ORDER_ALREADY_OPEN,
                OrderFixtures.refusal(
                    machine,
                    orders.getValue(state),
                    OrderEvent.Proposal(OrderFixtures.ORDER_ID, OrderFixtures.TERMS),
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
    @JsName("a_wallet_that_reports_every_payment_settled_moves_the_order_not_at_all")
    @Test
    fun `a wallet that reports every payment settled moves the order not at all`() {
        val truth = OrderFixtures.receipt(Payee.PROVIDER).payment
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
    @JsName("neither_an_order_nor_its_terms_prints_an_amount_or_a_deadline")
    @Test
    fun `neither an order nor its terms prints an amount or a deadline`() {
        val orders = OrderFixtures.orders()
        val price = OrderFixtures.PRICE.millisatoshis.toString()
        val deadline = OrderFixtures.DELIVER_BY.toString()
        // §12 item 2: the provider key is a counterparty pubkey, and it is a plain hex `String`
        // with no `toString` of its own to redact — so `Order.toString` is the only thing between
        // it and a log line.
        val provider = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX)
        assertEquals(
            provider,
            orders.getValue(OrderState.ACCEPTED).provider,
            "the order must actually be holding the key, or the assertion below is vacuous",
        )

        assertFalse(OrderFixtures.TERMS.toString().contains(price))
        assertFalse(OrderFixtures.TERMS.toString().contains(deadline))
        for ((state, order) in orders) {
            assertFalse(order.toString().contains(price), "$state printed the price")
            assertFalse(order.toString().contains(deadline), "$state printed a deadline")
            assertFalse(order.toString().contains(provider), "$state printed the provider's key")
        }
    }

    /** A refusal's `detail` names a rule; it never names a byte, an amount or a deadline. */
    @JsName("a_refusal_s_detail_carries_no_amount_and_no_deadline")
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
