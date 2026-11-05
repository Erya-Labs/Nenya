package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.AcceptedPaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.SettlementException
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T118: a fee-bearing order waits at `committed` for a fee request that has not arrived, and the
 * clock goes on (decision V).
 *
 * §11.2 enters `awaiting_payment` only on one valid `type=2` per required payee, so the buyer's
 * order holding the provider's request alone stays `committed`. While it waits, the only deadline
 * that can end it is §7.5's `expiration`, through §11.2's `committed → expired` row and only when
 * stepped with `clock_checked`. `deliver_by` is `paid`'s row and fires on nothing here. A
 * provider invoice that lapses during the wait does not stop the order entering
 * `awaiting_payment` once the fee request arrives — §9.2 check 5 was made when the record was
 * accepted and §11.2 does not re-read it — and §8.6 does not let the provider replace it. A
 * cancel still ends the order (§11.2), and an order that reached a terminal state takes neither
 * record.
 *
 * Every invoice is composed by [SettlementFixtures.invoice] from a vendored example (decision D);
 * every order is walked by hand through [OrderFixtures.chain], as `feeJudgementOrder` is.
 */
class FeeRequestWaitDeadlineTest {

    private fun machineAt(unixSeconds: Long): OrderMachine = OrderMachine(FakeClock(unixSeconds))

    /**
     * [terms]' order walked to `committed` before both deadlines, then shown to be waiting: the
     * provider's stored request alone is refused `PAYMENT_REQUESTS_INCOMPLETE`.
     */
    private fun waiting(terms: OrderTerms = OrderFixtures.TERMS): Order {
        val machine = OrderFixtures.machineBeforeDeadlines()
        val chain = OrderFixtures.chain(terms)
        val proposed = machine.open(chain.proposal.asOrderEvent())
        val accepted =
            OrderFixtures.advanced(machine, proposed, OrderEvent.AcceptanceReceived(chain.accepted))
        val committed = OrderFixtures.advanced(
            machine,
            accepted,
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER),
        )
        assertEquals(OrderState.COMMITTED, committed.state)
        assertTrue(Payee.FEE in Payee.requiredPayees(terms.split), "the terms must owe a fee")
        val alone = OrderFixtures.refusal(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(setOf(chain.request(Payee.PROVIDER))),
        )
        assertEquals(
            TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE,
            alone.reason,
            "with no fee request the order waits at `committed` (§11.2)",
        )
        return committed
    }

    /** Fee-bearing terms carrying `deliver_by` and no `expiration`. */
    private fun noExpiration(): OrderTerms = OrderTerms.of(
        OrderFixtures.PRICE,
        FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
        expiration = null,
        deliverBy = OrderFixtures.DELIVER_BY,
    )

    // ---------------------------------------------------------------- deadlines during the wait

    @JsName("clock_checked_on_a_waiting_order_is_refused_one_second_before_expiration_and_expires_it_at_expiration")
    @Test
    fun `clock_checked on a waiting order is refused one second before expiration and expires it at expiration`() {
        val order = waiting()
        val early = OrderFixtures.refusal(
            machineAt(OrderFixtures.EXPIRATION - 1),
            order,
            OrderEvent.ClockChecked,
        )
        assertEquals(TransitionRejection.DEADLINE_NOT_PASSED, early.reason)
        assertEquals(OrderState.COMMITTED, order.state)

        val expired = OrderFixtures.advanced(
            machineAt(OrderFixtures.EXPIRATION),
            order,
            OrderEvent.ClockChecked,
        )
        assertEquals(OrderState.EXPIRED, expired.state, "§11.2's `committed → expired` row")
        assertNull(expired.disputeGround)
    }

    /**
     * No `expiration`, the clock past `deliver_by`: §11.2's `→ expired` rows have nothing to fire
     * on, and `deliver_by` is `paid`'s row, so the order stays `committed` and is not disputed.
     */
    @JsName("past_deliver_by_a_waiting_order_with_no_expiration_has_no_deadline_to_check")
    @Test
    fun `past deliver_by a waiting order with no expiration has no deadline to check`() {
        val terms = noExpiration()
        val order = waiting(terms)
        assertNull(order.terms.expiration, "the chain must carry the absence of `expiration`")
        assertEquals(OrderFixtures.DELIVER_BY, order.terms.deliverBy)

        val refused = OrderFixtures.refusal(
            OrderFixtures.machineAfterDeadlines(),
            order,
            OrderEvent.ClockChecked,
        )
        assertEquals(TransitionRejection.NO_DEADLINE_TO_CHECK, refused.reason)
        assertEquals(OrderState.COMMITTED, order.state)
        assertNull(order.disputeGround, "`deliver_by` disputes only a `paid` order (§11.2)")
    }

    /** Both deadlines passed: §7.5 puts `expiration` first, so the waiting order expires. */
    @JsName("past_both_deadlines_a_waiting_order_expires_and_is_not_disputed")
    @Test
    fun `past both deadlines a waiting order expires and is not disputed`() {
        val expired = OrderFixtures.advanced(
            OrderFixtures.machineAfterDeadlines(),
            waiting(),
            OrderEvent.ClockChecked,
        )
        assertEquals(OrderState.EXPIRED, expired.state)
        assertNull(expired.disputeGround, "expiry is not a dispute")
    }

    // ---------------------------------------------------------------- a lapsed provider invoice

    /** When the provider's request was accepted: before both deadlines. */
    private val acceptedAt: Long = OrderFixtures.BEFORE_DEADLINES

    /** The provider invoice's timestamp, [SettlementFixtures.INVOICE_AGE_SECONDS] before [acceptedAt]. */
    private val stamped: Long = acceptedAt - SettlementFixtures.INVOICE_AGE_SECONDS

    /** One second past the provider invoice's expiry, and still before `expiration`. */
    private val lapsedAt: Long = stamped + SettlementFixtures.INVOICE_EXPIRY_SECONDS + 1

    private val chain: OrderFixtures.OrderChain = OrderFixtures.chain()

    /** A `type=2` for [payee] on [chain]'s order, its invoice drawn at [stream] and stamped [timestamp]. */
    private fun request(payee: Payee, stream: Int, timestamp: Long): PaymentRequest =
        SettlementFixtures.requestSealedBy(
            when (payee) {
                Payee.PROVIDER -> SettlementFixtures.provider(chain.index)
                Payee.FEE -> SettlementFixtures.feeRecipient(chain.index)
            },
            SettlementFixtures.requestTags(
                index = chain.index,
                payment = SettlementFixtures.requestPaymentTag(
                    SettlementFixtures.invoice(
                        OrderFixtures.preimageHex(chain.index, stream),
                        SettlementFixtures.amountFor(payee, chain.terms.split),
                        timestamp = timestamp,
                    ),
                ),
                payeeTag = SettlementFixtures.payeeTag(payee, chain.index),
                fee = if (payee == Payee.FEE) chain.feeTag else null,
            ),
            chain.terms.split,
        )

    /** The provider's request whose invoice lapses one second before [lapsedAt]. */
    private fun lapsingProviderRequest(): PaymentRequest = request(Payee.PROVIDER, 0, stamped)

    /**
     * One store holding the provider's request, accepted at [acceptedAt], and a fee request stamped
     * live at [lapsedAt] and accepted then — as a buyer's client keeps them.
     */
    private class Wait(
        val store: PaymentRequestStore,
        val provider: AcceptedPaymentRequest,
        val fee: AcceptedPaymentRequest,
    )

    private fun lapsedWait(): Wait {
        val store = PaymentRequestStore.inMemory()
        val provider = AcceptedPaymentRequest.accept(
            lapsingProviderRequest(),
            chain.accepted,
            store,
            FakeClock(acceptedAt),
        )
        val fee = AcceptedPaymentRequest.accept(
            request(Payee.FEE, 0, lapsedAt - SettlementFixtures.INVOICE_AGE_SECONDS),
            chain.accepted,
            store,
            FakeClock(lapsedAt),
            chain.signedPoints(),
        )
        return Wait(store, provider, fee)
    }

    private fun refusedAccepting(request: PaymentRequest, into: PaymentRequestStore): SettlementRejection =
        assertFailsWith<SettlementException> {
            AcceptedPaymentRequest.accept(request, chain.accepted, into, FakeClock(lapsedAt))
        }.reason

    @JsName("a_provider_invoice_that_lapses_during_the_wait_still_takes_the_order_to_awaiting_payment")
    @Test
    fun `a provider invoice that lapses during the wait still takes the order to awaiting_payment`() {
        assertTrue(lapsedAt < OrderFixtures.EXPIRATION, "the order itself has not expired at this clock")
        val wait = lapsedWait()
        assertEquals(acceptedAt, wait.provider.acceptedAt)
        assertEquals(lapsedAt, wait.fee.acceptedAt)
        // Non-vacuity: at this clock the provider's invoice really has lapsed — a fresh store
        // accepting the same request now refuses it for §9.2 check 5.
        assertEquals(
            SettlementRejection.INVOICE_EXPIRED,
            refusedAccepting(lapsingProviderRequest(), PaymentRequestStore.inMemory()),
        )

        val awaiting = OrderFixtures.advanced(
            machineAt(lapsedAt),
            waiting(),
            OrderEvent.PaymentRequestsReceived(setOf(wait.provider, wait.fee)),
        )
        assertEquals(
            OrderState.AWAITING_PAYMENT,
            awaiting.state,
            "§9.2 check 5 was made at acceptance and §11.2 does not re-read it",
        )
    }

    @JsName("at_the_lapsed_clock_a_second_provider_request_is_refused_and_a_cancel_ends_the_order")
    @Test
    fun `at the lapsed clock a second provider request is refused and a cancel ends the order`() {
        val wait = lapsedWait()
        val live = request(Payee.PROVIDER, 1, lapsedAt - SettlementFixtures.INVOICE_AGE_SECONDS)
        assertEquals(
            SettlementRejection.REQUEST_ALREADY_STORED,
            refusedAccepting(live, wait.store),
            "§8.6: a live replacement is still a replacement",
        )
        assertEquals(
            SettlementRejection.INVOICE_EXPIRED,
            refusedAccepting(lapsingProviderRequest(), wait.store),
            "the provider re-sending its lapsed invoice fails §9.2 check 5 first",
        )
        val kept = assertNotNull(wait.store.find(chain.accepted.order, Payee.PROVIDER))
        assertEquals(wait.provider.invoice, kept.invoice, "the first record survives")
        assertEquals(acceptedAt, kept.acceptedAt)

        val cancel = OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER)
        val machine = machineAt(lapsedAt)
        val waiting = waiting()
        assertEquals(OrderState.CANCELLED, OrderFixtures.advanced(machine, waiting, cancel).state)
        val awaiting = OrderFixtures.advanced(
            machine,
            waiting,
            OrderEvent.PaymentRequestsReceived(setOf(wait.provider, wait.fee)),
        )
        assertEquals(OrderState.CANCELLED, OrderFixtures.advanced(machine, awaiting, cancel).state)
    }

    @JsName("an_expired_or_locally_disputed_order_refuses_both_records_as_terminal")
    @Test
    fun `an expired or locally disputed order refuses both records as terminal`() {
        val wait = lapsedWait()
        val expired = OrderFixtures.advanced(
            machineAt(OrderFixtures.EXPIRATION),
            waiting(),
            OrderEvent.ClockChecked,
        )
        val disputed = OrderFixtures.advanced(
            OrderFixtures.machineBeforeDeadlines(),
            waiting(),
            OrderEvent.LocallyDisputed,
        )
        assertEquals(DisputeGround.RAISED_LOCALLY, disputed.disputeGround)

        val both = OrderEvent.PaymentRequestsReceived(setOf(wait.provider, wait.fee))
        for ((order, state) in listOf(expired to OrderState.EXPIRED, disputed to OrderState.DISPUTED)) {
            assertEquals(state, order.state)
            val refused = OrderFixtures.refusal(machineAt(lapsedAt), order, both)
            assertEquals(TransitionRejection.STATE_IS_TERMINAL, refused.reason, "from $state")
            assertEquals(state, order.state, "the state unchanged")
        }
    }
}
