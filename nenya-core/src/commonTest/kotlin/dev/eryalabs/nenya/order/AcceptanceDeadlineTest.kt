package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §7.5's deadline for acceptance, enforced by the order machine itself.
 *
 * ### The gap it closes
 *
 * §7.5 makes a proposal's `expiration` "the deadline for **acceptance**", and §4.6 makes the
 * injected clock its only judge. Before this, `proposed → accepted` never read the clock: a page
 * that offered a checked acceptance without first offering a [OrderEvent.ClockChecked] reached
 * `accepted` on an order §11.2's `proposed → expired` row had already expired. Which of the two
 * answers an order got depended on the order in which the page asked two questions.
 *
 * ### The boundary
 *
 * The `→ expired` rows fire at `now >= expiration`, so acceptance is refused at exactly that
 * reading and allowed one second earlier: no single reading leaves an order both acceptable and
 * expired. Every deadline below is read from [OrderFixtures.EXPIRATION] rather than restated.
 */
class AcceptanceDeadlineTest {

    /** A clock that answers [unixSeconds] and counts how often it was asked. */
    private class CountingClock(private val unixSeconds: Long) : NenyaClock {
        var reads: Int = 0
            private set

        override fun now(): SeamAnswer<Long> {
            reads++
            return SeamAnswer.Provided(unixSeconds)
        }
    }

    private fun machineAt(unixSeconds: Long): OrderMachine = OrderMachine(FakeClock(unixSeconds))

    private fun proposed(terms: OrderTerms = OrderFixtures.TERMS): Order =
        OrderFixtures.machineBeforeDeadlines().open(OrderFixtures.chain(terms).proposal.asOrderEvent())

    private fun acceptance(terms: OrderTerms = OrderFixtures.TERMS): OrderEvent =
        OrderEvent.AcceptanceReceived(OrderFixtures.chain(terms).accepted)

    /** Terms with neither §5.3's `expiration` nor §7.5's `deliver_by`. */
    private fun undated(): OrderTerms =
        OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS))

    @JsName("an_acceptance_one_second_before_expiration_advances")
    @Test
    fun `an acceptance one second before expiration advances`() {
        val accepted = OrderFixtures.advanced(
            machineAt(OrderFixtures.EXPIRATION - 1),
            proposed(),
            acceptance(),
        )
        assertEquals(OrderState.ACCEPTED, accepted.state)
        assertEquals(
            SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            accepted.provider,
            "the checked provider key travels onto the order as before",
        )
    }

    /**
     * At `expiration` the acceptance is refused, the order is left exactly where it was, and the
     * very same machine then expires it on a [OrderEvent.ClockChecked] — the row the refusal says
     * fires here.
     */
    @JsName("an_acceptance_at_expiration_is_refused_and_the_clock_then_expires_the_order")
    @Test
    fun `an acceptance at expiration is refused and the clock then expires the order`() {
        val machine = machineAt(OrderFixtures.EXPIRATION)
        val order = proposed()
        val refused = OrderFixtures.refusal(machine, order, acceptance())
        assertEquals(TransitionRejection.ACCEPTANCE_DEADLINE_PASSED, refused.reason)
        assertEquals(OrderState.PROPOSED, refused.order.state, "a refusal leaves the order alone")
        assertNull(refused.order.provider, "a refused acceptance records no provider key")

        val expired = OrderFixtures.advanced(machine, refused.order, OrderEvent.ClockChecked)
        assertEquals(OrderState.EXPIRED, expired.state, "§11.2's `proposed → expired` at the same reading")
    }

    @JsName("an_acceptance_long_past_expiration_is_refused")
    @Test
    fun `an acceptance long past expiration is refused`() {
        val refused = OrderFixtures.refusal(
            OrderFixtures.machineAfterDeadlines(),
            proposed(),
            acceptance(),
        )
        assertEquals(TransitionRejection.ACCEPTANCE_DEADLINE_PASSED, refused.reason)
    }

    /**
     * The negative control: with an `expiration` to judge and no clock to judge it by, the answer is
     * §4.6's clock refusal, never `accepted`.
     */
    @JsName("a_silent_or_negative_clock_never_accepts_terms_carrying_an_expiration")
    @Test
    fun `a silent or negative clock never accepts terms carrying an expiration`() {
        val silent = OrderFixtures.refusal(OrderMachine(NenyaClock.FAIL_CLOSED), proposed(), acceptance())
        assertEquals(TransitionRejection.CLOCK_UNAVAILABLE, silent.reason)
        assertEquals(OrderState.PROPOSED, silent.order.state)

        val negative = OrderFixtures.refusal(machineAt(-1L), proposed(), acceptance())
        assertEquals(TransitionRejection.CLOCK_READING_BEFORE_EPOCH, negative.reason)
        assertEquals(OrderState.PROPOSED, negative.order.state)
    }

    /**
     * Terms with no `expiration` have no deadline for acceptance, so the clock is not read at all —
     * and an order under the fail-closed default still accepts, exactly as it did before.
     */
    @JsName("terms_without_expiration_read_no_clock")
    @Test
    fun `terms without expiration read no clock`() {
        val clock = CountingClock(Long.MAX_VALUE)
        val accepted = OrderFixtures.advanced(OrderMachine(clock), proposed(undated()), acceptance(undated()))
        assertEquals(OrderState.ACCEPTED, accepted.state)
        assertEquals(0, clock.reads, "no `expiration`, no clock reading")

        val failClosed = OrderFixtures.advanced(
            OrderMachine(NenyaClock.FAIL_CLOSED),
            proposed(undated()),
            acceptance(undated()),
        )
        assertEquals(OrderState.ACCEPTED, failClosed.state)
    }

    /** An acceptance for terms carrying an `expiration` reads the clock exactly once. */
    @JsName("terms_with_expiration_read_the_clock_once")
    @Test
    fun `terms with expiration read the clock once`() {
        val clock = CountingClock(OrderFixtures.BEFORE_DEADLINES)
        OrderFixtures.advanced(OrderMachine(clock), proposed(), acceptance())
        assertEquals(1, clock.reads)
    }

    /**
     * The binding is still asked first: an acceptance for another order is refused as that, past
     * the deadline too, because "it is not this order's" is the more useful answer.
     */
    @JsName("an_acceptance_for_another_order_is_refused_as_that_before_the_clock_is_read")
    @Test
    fun `an acceptance for another order is refused as that before the clock is read`() {
        val clock = CountingClock(OrderFixtures.AFTER_DEADLINES)
        val refused = OrderFixtures.refusal(
            OrderMachine(clock),
            proposed(),
            OrderEvent.AcceptanceReceived(OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX).accepted),
        )
        assertEquals(TransitionRejection.ACCEPTANCE_FOR_ANOTHER_ORDER, refused.reason)
        assertEquals(0, clock.reads)
    }
}
