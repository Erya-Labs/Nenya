package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.DeliveryFailure
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.order.TransitionRejection
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §11.2's two doors across the JavaScript boundary: [openOrder] and [stepOrder] answer exactly what
 * `OrderMachine.open` and `OrderMachine.on` answer — including **every refusal §11.2 can return**.
 *
 * ### Why the refusal sweep is the centre of this file
 *
 * The order machine is the one layer that decides whether a trade advanced, and the facade must
 * never be a second opinion about it. An equality over the happy path would be satisfied by a
 * crossing that reported every refusal as one constant; an equality over *every* refusal cannot be.
 * So [`stepOrder reaches every refusal §11_2 can return`] drives one scenario per
 * `TransitionRejection`, compares each against the Kotlin answer field for field, and then asserts
 * the set of constants reached is `TransitionRejection.entries` minus a residue it pins and
 * justifies — so a constant added to §11.2 without a crossed scenario turns this red.
 *
 * ### Where the mutation T43 names lands
 *
 * "Make `stepOrder` report `paid` from a refused outcome and confirm the equality test turns red."
 * Every scenario below ends in [JsFlowFixtures.assertSameOutcome], whose first comparison after the
 * discriminator is the order's `state` read off the value `OrderMachine.on` returned. There is no
 * shape of the facade that satisfies it by agreeing with itself: the expected side never calls a
 * facade function.
 */
class JsOrderEqualityTest {

    /**
     * §11.2's genesis row across the boundary: a `type=1` opens an order in `proposed`.
     *
     * Three fee shapes, because §8.1's distinction between an absent term and a stated zero is
     * "itself a signed statement" and it is the one T42's proof found an inherited facade losing.
     * Every published field of the opened order is compared, which includes §8.3's computed fee and
     * total — the crossing carries the *inputs* to that arithmetic and the library computes it, so
     * an order whose total disagreed with its own terms is not expressible.
     */
    @Test
    @JsName("openOrder_crosses_section_11_2_s_genesis_row")
    fun `openOrder crosses §11_2's genesis row`() {
        for ((what, terms) in listOf(
            "a priced order with a stated fee" to OrderFixtures.TERMS,
            "§8.1's signed zero" to OrderFixtures.zeroFeeTerms,
            "no fee term at all" to OrderFixtures.absentFeeTerms,
            "no deadlines at all" to undated(),
        )) {
            val expected = machine().open(
                OrderEvent.Proposal(OrderFixtures.ORDER_ID, terms, OrderFixtures.BEFORE_DEADLINES),
            )
            val crossed = openOrder(
                machine = crossedMachine(),
                orderIdHex = OrderFixtures.ORDER_ID.toHex(),
                terms = JsBoundaryFixtures.crossed(terms),
                createdAtSeconds = OrderFixtures.BEFORE_DEADLINES.toString(),
            )
            assertTrue(crossed.ok, "$what must open: ${crossed.reason} ${crossed.detail}")
            JsBoundaryFixtures.assertSameFields(
                JsFlowFixtures.orderFields(crossed.order ?: fail("$what: no order"), expected),
                what,
            )
            assertEquals(OrderState.PROPOSED.name, crossed.order?.state, "$what: §11.2's first row")
        }
    }

    /** `openOrder`'s refusals are the library's: §7.4's order id and §7.5's inverted deadlines. */
    @Test
    @JsName("openOrder_returns_the_library_s_refusals_as_values")
    fun `openOrder returns the library's refusals as values`() {
        val badId = openOrder(crossedMachine(), "not an order id", JsBoundaryFixtures.crossed(OrderFixtures.TERMS))
        assertFalse(badId.ok, "§7.4's order id is 32 bytes of hex")
        assertEquals("SeamRejection", badId.reasonVocabulary, "`OrderId.ofHex`'s own vocabulary")

        // §7.5's inverted-deadline rule, enforced by `OrderTerms`'s own `init` before any machine
        // is reached — the arm T42's proof discovered the boundary was letting escape as a throw.
        val inverted = openOrder(
            machine = crossedMachine(),
            orderIdHex = OrderFixtures.ORDER_ID.toHex(),
            terms = JsOrderTerms(
                priceMsat = OrderFixtures.PRICE.millisatoshis.toString(),
                expirationSeconds = OrderFixtures.DELIVER_BY.toString(),
                deliverBySeconds = OrderFixtures.EXPIRATION.toString(),
            ),
        )
        assertFalse(inverted.ok, "§7.5: `deliver_by` before `expiration` is not a pair of deadlines")
        assertEquals("OrderStateRejection", inverted.reasonVocabulary, "`OrderTerms`'s own vocabulary")

        // A non-positive timeout: §11.2 requires a window and `OrderMachine`'s `init` says a zero
        // one "is not a timeout, it disputes an order the moment it is paid".
        val zeroTimeout = openOrder(
            machine = JsOrderMachine(crossedEnvironment(), releaseTimeoutSeconds = "0"),
            orderIdHex = OrderFixtures.ORDER_ID.toHex(),
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        assertFalse(zeroTimeout.ok, "a zero release timeout is refused at the first call")
        assertEquals("OrderStateRejection", zeroTimeout.reasonVocabulary, "`OrderMachine`'s own init")
    }

    /**
     * §11.2's advancing rows across the boundary, driven as one order from `proposed` to `settled`.
     *
     * Each step is made twice — once through `OrderMachine.on` and once through [stepOrder] — and
     * the two orders are compared field for field before the next step is taken from the **Kotlin**
     * one. So a crossing that drifted by one field would be caught at that step rather than
     * accumulating silently, and no later step is taken against an order the facade produced.
     */
    @Test
    @JsName("stepOrder_crosses_section_11_2_s_advancing_rows_end_to_end")
    fun `stepOrder crosses §11_2's advancing rows end to end`() {
        val chain = OrderFixtures.chain()
        var order = machine().open(chain.proposal.asOrderEvent())

        for ((what, event) in advancingChain(chain)) {
            order = advancedBothWays(what, order, event)
        }
        assertEquals(OrderState.SETTLED.name, JsOrder(order).state, "the chain must reach §11.2's end")
    }

    /**
     * **Every refusal §11.2 can return, reached through [stepOrder].**
     *
     * One scenario per constant, each compared against the Kotlin answer in full, and the set of
     * constants reached held equal to `TransitionRejection.entries` minus [UNREACHABLE].
     *
     * The residue is pinned rather than waved at, and it is one constant with a reason that is not
     * about this boundary: see [UNREACHABLE].
     */
    @Test
    @JsName("stepOrder_reaches_every_refusal_section_11_2_can_return")
    fun `stepOrder reaches every refusal §11_2 can return`() {
        val reached = mutableSetOf<TransitionRejection>()
        for (scenario in refusalScenarios()) {
            val expected = scenario.machine.on(scenario.order, scenario.kotlinEvent)
            val crossed = stepOrder(
                machine = scenario.crossedMachine,
                order = JsOrder(scenario.order),
                event = scenario.jsEvent,
            )
            JsFlowFixtures.assertSameOutcome(crossed, expected, scenario.what)
            assertFalse(crossed.ok, "${scenario.what} must refuse, not advance")
            assertEquals(
                scenario.expected.name,
                crossed.reason,
                "${scenario.what} must refuse for the §11.2 reason it was built for",
            )
            assertEquals("TransitionRejection", crossed.reasonVocabulary, "${scenario.what}: §11.2's")
            reached += scenario.expected
        }

        assertEquals(
            TransitionRejection.entries.toSet() - UNREACHABLE,
            reached,
            "every refusal §11.2 can return must be reached through `stepOrder`. A constant in " +
                "neither this set nor UNREACHABLE is a §11.2 answer no crossed scenario produces, " +
                "so nothing says the facade reports it as §11.2 reported it.",
        )
        assertEquals(
            TransitionRejection.entries.size,
            reached.size + UNREACHABLE.size,
            "the two sets must partition §11.2's vocabulary, or one of them is stale",
        )
    }

    /**
     * Decision B's refusal carries the checks nobody performed, across the boundary.
     *
     * Its own test because `missingChecks` is the one field on [JsOrderOutcome] that is meaningful
     * for exactly one refusal and empty-and-misleading on the other twenty-seven. §9.2's set is
     * what lets a page say "the invoice amount was never checked" rather than "the order did not
     * move", and a crossing that published an empty array for it would lose that entirely while
     * agreeing about the constant.
     */
    @Test
    @JsName("decision_B_s_missing_check_set_crosses")
    fun `decision B's missing-check set crosses`() {
        val order = OrderFixtures.orders()[OrderState.AWAITING_PAYMENT]
            ?: fail("the fixture chain must reach awaiting_payment")
        val receipts = setOf(
            OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
            OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1),
        )
        val crossed = stepOrder(
            machine = crossedMachine(),
            order = JsOrder(order),
            event = JsOrderEvent(
                type = "receipts_verified",
                settlements = receipts.map { JsSettlement(it) }.toTypedArray(),
            ),
        )
        JsFlowFixtures.assertSameOutcome(
            crossed,
            machine().on(order, OrderEvent.ReceiptsVerified(receipts)),
            "a fee receipt that skipped check 6",
        )
        assertEquals(
            OrderFixtures.CHECK_SIX_UNPERFORMED.map { it.name }.toSet(),
            crossed.missingChecks.toSet(),
            "§9.2's three check-6 obligations, named rather than merely counted",
        )
    }

    /**
     * A settlement §9.4 says evidences nothing cannot be offered as §9.2 evidence — the crossing's
     * own refusal, reported rather than dropped.
     *
     * The alternative a tidier crossing would have taken is to filter the array, and that is the
     * failure §17 is about in the direction nobody checks: an order refused `RECEIPTS_INCOMPLETE`
     * because its unverified receipt was silently discarded tells the page the receipt was never
     * offered, when what happened is that the rail has no verification rule.
     */
    @Test
    @JsName("an_unverified_settlement_cannot_be_offered_as_section_9_2_evidence")
    fun `an unverified settlement cannot be offered as §9_2 evidence`() {
        val order = OrderFixtures.orders()[OrderState.AWAITING_PAYMENT]
            ?: fail("the fixture chain must reach awaiting_payment")
        val unverified = unverifiedSettlement()
        assertFalse(unverified.evidenced, "the fixture must be §9.4's Unverified, or this proves nothing")

        val crossed = stepOrder(
            machine = crossedMachine(),
            order = JsOrder(order),
            event = JsOrderEvent(type = "receipts_verified", settlements = arrayOf(unverified)),
        )
        assertFalse(crossed.ok, "§9.4: a rail with no rule evidences nothing")
        assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, crossed.reason, "the crossing's own reason")
        assertEquals(
            JsCrossing.VOCABULARY,
            crossed.reasonVocabulary,
            "a page must never read this as a §11.2 refusal about its order",
        )
        assertEquals(null, crossed.order, "no call was made, so there is no outcome to carry an order")
    }

    /**
     * A `type` no §11.2 trigger answers to, and a required field left out: both the crossing's own,
     * both before the machine is reached.
     */
    @Test
    @JsName("an_unconstructible_order_event_reaches_the_machine_not_at_all")
    fun `an unconstructible order event reaches the machine not at all`() {
        val order = OrderFixtures.orders()[OrderState.PROPOSED] ?: fail("proposed must exist")
        for ((what, event) in listOf(
            "a type §11.2 does not have" to JsOrderEvent(type = "settled"),
            "a status update with no token" to JsOrderEvent(type = "status_update", fromParty = "BUYER"),
            "a status update with no party" to JsOrderEvent(type = "status_update", statusToken = "cancelled"),
            "a commitment event with no commitment" to
                JsOrderEvent(type = "delivery_committed", fromParty = "PROVIDER"),
            "a party no §7.4 role answers to" to
                JsOrderEvent(type = "chat_message", fromParty = "RELAY"),
        )) {
            val crossed = stepOrder(crossedMachine(), JsOrder(order), event)
            assertFalse(crossed.ok, "$what must refuse")
            assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, crossed.reason, "$what: the crossing's reason")
            assertEquals(JsCrossing.VOCABULARY, crossed.reasonVocabulary, "$what: the crossing's vocabulary")
            assertEquals(null, crossed.order, "$what: no call was made")
            assertTrue(crossed.tag != null, "$what: the refusal must name the field it was reading")
        }
    }

    // -----------------------------------------------------------------------------------------
    // The scenarios.
    // -----------------------------------------------------------------------------------------

    /** One §11.2 refusal, with the same step expressed on both sides of the boundary. */
    private class Scenario(
        val what: String,
        val expected: TransitionRejection,
        val order: Order,
        val kotlinEvent: OrderEvent,
        val jsEvent: JsOrderEvent,
        val machine: OrderMachine,
        val crossedMachine: JsOrderMachine,
    )

    /**
     * §11.2's advancing rows as `(what, event)` pairs, from `proposed` to `settled`.
     *
     * Kotlin events only: [advancedBothWays] projects each onto the boundary a second time, so the
     * two translations stay independent.
     */
    private fun advancingChain(chain: OrderFixtures.OrderChain): List<Pair<String, OrderEvent>> = listOf(
        "§7.6's acceptance" to OrderEvent.AcceptanceReceived(chain.accepted),
        "§10.1's commitment" to
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER),
        "§8.6's payment requests" to OrderEvent.PaymentRequestsReceived(chain.requests),
        "§9.2's receipts" to OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        "§10.3's release" to
            OrderEvent.DeliverableReleased(OrderFixtures.matchingRelease(), Party.PROVIDER),
        "§10.4's evidence" to OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
    )

    /** One scenario per §11.2 refusal, every one of them reached through [stepOrder]. */
    private fun refusalScenarios(): List<Scenario> {
        val priced = OrderFixtures.orders()
        val chain = OrderFixtures.chain()
        val other = OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX)
        val free = OrderFixtures.orders(OrderFixtures.freeTerms())
        // Opened rather than taken from `orders`, which drives a chain all the way to `settled`
        // and cannot: an order with no `expiration` has no `→ expired` row to fire, which is the
        // very thing this scenario is about.
        val undatedOrder = machine().open(OrderEvent.Proposal(OrderFixtures.ORDER_ID, undated()))
        val out = mutableListOf<Scenario>()

        fun add(
            what: String,
            expected: TransitionRejection,
            order: Order,
            kotlinEvent: OrderEvent,
            jsEvent: JsOrderEvent,
            clock: NenyaClock? = FakeClock(OrderFixtures.BEFORE_DEADLINES),
        ) {
            out += Scenario(
                what,
                expected,
                order,
                kotlinEvent,
                jsEvent,
                OrderMachine(clock ?: NenyaClock.FAIL_CLOSED),
                JsOrderMachine(crossedEnvironment(clock)),
            )
        }

        fun state(name: OrderState): Order = priced[name] ?: fail("the chain must reach $name")

        // -------------------------------------------------- the four §11.2 answers to any event
        add(
            "a chat message, which §11.2 gives no row",
            TransitionRejection.EVENT_ADVANCES_NO_STATE,
            state(OrderState.PROPOSED),
            OrderEvent.ChatMessage(Party.BUYER),
            JsOrderEvent(type = "chat_message", fromParty = "BUYER"),
        )
        add(
            "a `status=paid` announcement, which decides nothing",
            TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING,
            state(OrderState.PROPOSED),
            OrderEvent.StatusUpdate(OrderState.PAID, Party.PROVIDER),
            JsOrderEvent(
                type = "status_update",
                statusToken = OrderStatusCodec.write(OrderState.PAID),
                fromParty = "PROVIDER",
            ),
        )
        add(
            "a `status=accepted` announcement, whose door §11.2 shut",
            TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE,
            state(OrderState.PROPOSED),
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER),
            JsOrderEvent(
                type = "status_update",
                statusToken = OrderStatusCodec.write(OrderState.ACCEPTED),
                fromParty = "PROVIDER",
            ),
        )
        add(
            "a second proposal for an order that already exists",
            TransitionRejection.ORDER_ALREADY_OPEN,
            state(OrderState.PROPOSED),
            chain.proposal.asOrderEvent(),
            JsOrderEvent(
                type = "proposal",
                orderIdHex = OrderFixtures.ORDER_ID.toHex(),
                terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
                createdAtSeconds = chain.proposal.createdAt.toString(),
            ),
        )

        // ----------------------------------------------------------------- the two state answers
        add(
            "a terminal order offered a cancellation",
            TransitionRejection.STATE_IS_TERMINAL,
            state(OrderState.SETTLED),
            cancellation(),
            crossedCancellation(),
        )
        add(
            "§11.1's unknown sink, which originates and receives no transition",
            TransitionRejection.STATE_IS_UNKNOWN,
            machine().unrecognised(OrderFixtures.ORDER_ID, OrderFixtures.TERMS),
            cancellation(),
            crossedCancellation(),
        )

        // -------------------------------------------------------------- §11.2's row-level answers
        add(
            "a commitment offered to an order nobody has accepted",
            TransitionRejection.WRONG_STATE_FOR_EVENT,
            state(OrderState.PROPOSED),
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.PROVIDER),
            JsOrderEvent(
                type = "delivery_committed",
                fromParty = "PROVIDER",
                commitment = JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
            ),
        )
        add(
            "a commitment from the buyer, which §11.2 makes the provider's",
            TransitionRejection.WRONG_SENDER,
            state(OrderState.ACCEPTED),
            OrderEvent.DeliveryCommitted(OrderFixtures.blob.commitment, Party.BUYER),
            JsOrderEvent(
                type = "delivery_committed",
                fromParty = "BUYER",
                commitment = JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
            ),
        )
        add(
            "a cancellation after the order was paid",
            TransitionRejection.CANCELLATION_TOO_LATE,
            state(OrderState.PAID),
            cancellation(),
            crossedCancellation(),
        )

        // ----------------------------------------------------------------------- §7.6's acceptance
        add(
            "an acceptance for another order entirely",
            TransitionRejection.ACCEPTANCE_FOR_ANOTHER_ORDER,
            state(OrderState.PROPOSED),
            OrderEvent.AcceptanceReceived(other.accepted),
            JsOrderEvent(
                type = "acceptance_received",
                proposalRumor = JsRumor(
                    ProposalFixtures.bound(other.proposalTags, OrderFixtures.OTHER_ORDER_INDEX),
                ),
                acceptanceRumor = JsRumor(
                    SettlementFixtures.boundSealedBy(
                        SettlementFixtures.provider(OrderFixtures.OTHER_ORDER_INDEX),
                        ProposalFixtures.acceptanceTags(
                            other.proposalTags,
                            OrderFixtures.OTHER_ORDER_INDEX,
                        ),
                    ),
                ),
                providerPubkey = SettlementFixtures.provider(OrderFixtures.OTHER_ORDER_INDEX),
            ),
        )

        // ------------------------------------------------------------------- §8.6's `type=2` rules
        add(
            "payment requests for another order",
            TransitionRejection.PAYMENT_REQUEST_FOR_ANOTHER_ORDER,
            state(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(other.requests),
            crossedRequests(other.requests),
        )
        add(
            "a provider request nobody but a stranger sealed",
            TransitionRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER,
            state(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(OrderFixtures.providerRequestUnderAStranger())),
            crossedRequests(setOf(OrderFixtures.providerRequestUnderAStranger())),
        )
        add(
            "two provider requests for one order",
            TransitionRejection.PAYMENT_REQUEST_PAYEE_DUPLICATED,
            state(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(
                setOf(chain.request(Payee.PROVIDER), OrderFixtures.secondProviderRequest()),
            ),
            crossedRequests(setOf(chain.request(Payee.PROVIDER), OrderFixtures.secondProviderRequest())),
        )
        add(
            "the provider's request with the fee recipient's missing",
            TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE,
            state(OrderState.COMMITTED),
            OrderEvent.PaymentRequestsReceived(setOf(chain.request(Payee.PROVIDER))),
            crossedRequests(setOf(chain.request(Payee.PROVIDER))),
        )
        add(
            "a request for an order that owes nobody anything",
            TransitionRejection.PAYMENT_REQUEST_NOT_REQUIRED,
            free[OrderState.COMMITTED] ?: fail("a free order must reach committed"),
            OrderEvent.PaymentRequestsReceived(setOf(chain.request(Payee.PROVIDER))),
            crossedRequests(setOf(chain.request(Payee.PROVIDER))),
        )

        // -------------------------------------------------------------------- §9.2's receipt rules
        add(
            "the provider's receipt with the fee recipient's missing",
            TransitionRejection.RECEIPTS_INCOMPLETE,
            state(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
            crossedReceipts(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        add(
            "a receipt for an order that owes nobody anything",
            TransitionRejection.RECEIPT_NOT_REQUIRED,
            free[OrderState.AWAITING_PAYMENT] ?: fail("a free order must reach awaiting_payment"),
            OrderEvent.ReceiptsVerified(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
            crossedReceipts(setOf(OrderFixtures.receipt(Payee.PROVIDER))),
        )
        add(
            "receipts verified for another order",
            TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER,
            state(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts(OrderFixtures.OTHER_ORDER_INDEX)),
            crossedReceipts(OrderFixtures.bothReceipts(OrderFixtures.OTHER_ORDER_INDEX)),
        )
        add(
            "two provider receipts for one order",
            TransitionRejection.RECEIPT_PAYEE_DUPLICATED,
            state(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 1),
                ),
            ),
            crossedReceipts(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 1),
                ),
            ),
        )
        add(
            "one payment offered as evidence of two",
            TransitionRejection.RECEIPT_PAYMENT_DUPLICATED,
            state(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receipt(Payee.FEE, stream = 0),
                ),
            ),
            crossedReceipts(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receipt(Payee.FEE, stream = 0),
                ),
            ),
        )
        add(
            "a fee receipt that skipped §9.2 check 6",
            TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED,
            state(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1),
                ),
            ),
            crossedReceipts(
                setOf(
                    OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
                    OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1),
                ),
            ),
        )

        // ------------------------------------------------------------------ §10.3's and §10.4's
        add(
            "a release whose `order` tag names another order",
            TransitionRejection.RELEASE_FOR_ANOTHER_ORDER,
            state(OrderState.PAID),
            OrderEvent.DeliverableReleased(
                release = OrderFixtures.matchingRelease(),
                from = Party.PROVIDER,
                createdAt = null,
                order = OrderFixtures.OTHER_ORDER_ID,
                checksPerformed = emptySet(),
            ),
            JsOrderEvent(
                type = "deliverable_released",
                fromParty = "PROVIDER",
                orderIdHex = OrderFixtures.OTHER_ORDER_ID.toHex(),
                release = JsBoundaryFixtures.crossed(OrderFixtures.matchingRelease()),
            ),
        )
        add(
            "evidence for a deliverable this order never committed to",
            TransitionRejection.EVIDENCE_IS_FOR_ANOTHER_COMMITMENT,
            state(OrderState.RELEASED),
            OrderEvent.DeliveryVerified(OrderFixtures.evidence(OrderFixtures.otherBlob)),
            JsOrderEvent(
                type = "delivery_verified",
                commitment = JsFlowFixtures.crossed(OrderFixtures.otherBlob.commitment),
                servedBytesHex = hex(OrderFixtures.otherBlob.served),
                plaintextBytesHex = hex(OrderFixtures.otherBlob.plaintext),
            ),
        )

        // ------------------------------------------------------------------------ §4.6's clock rows
        add(
            "a clock crossing before any deadline",
            TransitionRejection.DEADLINE_NOT_PASSED,
            state(OrderState.PROPOSED),
            OrderEvent.ClockChecked,
            JsOrderEvent(type = "clock_checked"),
        )
        add(
            "a clock crossing on an order with no deadline at all",
            TransitionRejection.NO_DEADLINE_TO_CHECK,
            undatedOrder,
            OrderEvent.ClockChecked,
            JsOrderEvent(type = "clock_checked"),
        )
        add(
            "a clock crossing with no clock supplied",
            TransitionRejection.CLOCK_UNAVAILABLE,
            state(OrderState.PROPOSED),
            OrderEvent.ClockChecked,
            JsOrderEvent(type = "clock_checked"),
            clock = null,
        )
        add(
            "a clock crossing with a clock that reports before 1970",
            TransitionRejection.CLOCK_READING_BEFORE_EPOCH,
            state(OrderState.PROPOSED),
            OrderEvent.ClockChecked,
            JsOrderEvent(type = "clock_checked"),
            clock = FakeClock(-1L),
        )

        return out
    }

    /**
     * The one §11.2 refusal no scenario above reaches, and the reason is not this boundary's.
     *
     * `NO_COMMITMENT_TO_CHECK_AGAINST` guards `release` and `settle` against an order holding no
     * commitment, and **no order the machine can produce is in that shape**: both rows are reached
     * only from `paid` or `released`, and the only path to either runs through `committed`, which a
     * `DeliveryCommitted` is what establishes. It is a defensive branch, reachable from neither the
     * Kotlin API nor this crossing — the whole repository references the constant exactly twice, at
     * its declaration and at the `refuse` call, with no test on either side of the boundary
     * reaching it.
     *
     * Pinned as a set rather than mentioned in a comment so that it cannot quietly grow: a second
     * constant added here turns the partition assertion red unless somebody writes down why.
     */
    private companion object {

        val UNREACHABLE: Set<TransitionRejection> =
            setOf(TransitionRejection.NO_COMMITMENT_TO_CHECK_AGAINST)
    }

    // -----------------------------------------------------------------------------------------
    // The step, made twice.
    // -----------------------------------------------------------------------------------------

    /**
     * [event] offered to [order] through both doors, the answers held equal, and the **Kotlin**
     * order returned so the chain never continues from one the facade produced.
     */
    private fun advancedBothWays(what: String, order: Order, event: OrderEvent): Order {
        val expected = machine().on(order, event)
        val crossed = stepOrder(crossedMachine(), JsOrder(order), crossed(event))
        JsFlowFixtures.assertSameOutcome(crossed, expected, what)
        assertTrue(crossed.ok, "$what must advance: ${crossed.reason} ${crossed.detail}")
        return expected.order
    }

    /**
     * [event] projected onto the boundary, written from the published fields of the Kotlin variant.
     *
     * The second translation the equality needs. It covers exactly the six advancing events
     * [advancingChain] produces; the refusal scenarios state both sides explicitly, because several
     * of them turn on a value — another order's requests, a release naming another order — that
     * cannot be read back off the event it was built into.
     */
    private fun crossed(event: OrderEvent): JsOrderEvent = when (event) {
        is OrderEvent.AcceptanceReceived -> {
            val chain = OrderFixtures.chain()
            JsOrderEvent(
                type = "acceptance_received",
                proposalRumor = JsRumor(
                    ProposalFixtures.bound(chain.proposalTags, OrderFixtures.ORDER_INDEX),
                ),
                acceptanceRumor = JsRumor(
                    SettlementFixtures.boundSealedBy(
                        SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
                        ProposalFixtures.acceptanceTags(
                            chain.proposalTags,
                            OrderFixtures.ORDER_INDEX,
                        ),
                    ),
                ),
                providerPubkey = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            )
        }

        is OrderEvent.DeliveryCommitted -> JsOrderEvent(
            type = "delivery_committed",
            fromParty = event.from.name,
            commitment = JsFlowFixtures.crossed(event.commitment),
            createdAtSeconds = event.createdAt?.toString(),
        )

        is OrderEvent.PaymentRequestsReceived -> crossedRequests(event.requests)
        is OrderEvent.ReceiptsVerified -> crossedReceipts(event.receipts)

        is OrderEvent.DeliverableReleased -> JsOrderEvent(
            type = "deliverable_released",
            fromParty = event.from.name,
            orderIdHex = event.order?.toHex(),
            release = JsBoundaryFixtures.crossed(event.release),
            createdAtSeconds = event.createdAt?.toString(),
        )

        is OrderEvent.DeliveryVerified -> JsOrderEvent(
            type = "delivery_verified",
            commitment = JsFlowFixtures.crossed(event.evidence.commitment),
            servedBytesHex = hex(OrderFixtures.blob.served),
            plaintextBytesHex = hex(OrderFixtures.blob.plaintext),
        )

        else -> fail("no crossing is written for $event; the refusal scenarios state theirs")
    }

    private fun crossedRequests(
        requests: Set<dev.eryalabs.nenya.settlement.AcceptedPaymentRequest>,
    ): JsOrderEvent = JsOrderEvent(
        type = "payment_requests_received",
        requests = requests.map { JsAcceptedRequest(it) }.toTypedArray(),
    )

    private fun crossedReceipts(
        receipts: Set<dev.eryalabs.nenya.settlement.Settlement.Evidenced>,
    ): JsOrderEvent = JsOrderEvent(
        type = "receipts_verified",
        settlements = receipts.map { JsSettlement(it) }.toTypedArray(),
    )

    /** §11.2's one status a `type=3` can move an order on, before `paid`. */
    private fun cancellation(): OrderEvent =
        OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER)

    private fun crossedCancellation(): JsOrderEvent = JsOrderEvent(
        type = "status_update",
        statusToken = OrderStatusCodec.write(OrderState.CANCELLED),
        fromParty = "BUYER",
    )

    /** Terms with neither §5.3's `expiration` nor §7.5's `deliver_by`. */
    private fun undated(): OrderTerms =
        OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS))

    private fun machine(): OrderMachine = OrderFixtures.machineBeforeDeadlines()

    private fun crossedMachine(): JsOrderMachine =
        JsOrderMachine(crossedEnvironment(FakeClock(OrderFixtures.BEFORE_DEADLINES)))

    private fun crossedEnvironment(clock: NenyaClock? = FakeClock(OrderFixtures.BEFORE_DEADLINES)) =
        JsFlowFixtures.crossedEnvironment(clock = clock)

    /** Bytes as the lowercase hex this boundary carries them as, written here and not via `JsHex`. */
    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4]).append(digits[value and 0xf])
        }
        return out.toString()
    }

    /** §9.4's answer: a receipt on a rail NENYA-1 v1 defines no verification rule for. */
    private fun unverifiedSettlement(): JsSettlement {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val crossed = verifySettlement(
            receipt = JsRumor(messages.receiptRumor(Payee.PROVIDER)),
            store = JsFlowFixtures.crossed(messages.store),
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        // The lightning receipt evidences; §9.4's case needs another rail, built below.
        assertTrue(crossed.ok, "the fixture receipt must verify first")
        return onAnotherRail()
    }

    /** The same order's receipt on §9.4's `bitcoin` rail, which evidences nothing. */
    private fun onAnotherRail(): JsSettlement {
        val index = OrderFixtures.ORDER_INDEX
        val rumor = SettlementFixtures.boundSealedBy(
            SettlementFixtures.buyer(index),
            SettlementFixtures.receiptTags(
                index = index,
                payment = SettlementFixtures.paymentTag(
                    reference = "a transaction id this library does not read",
                    proof = "a proof §9.4 defines no rule for",
                    medium = "bitcoin",
                ),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
            ),
            dev.eryalabs.nenya.tag.NenyaKind.RECEIPT,
        )
        val answer = verifySettlement(
            receipt = JsRumor(rumor),
            store = JsFlowFixtures.crossed(OrderFixtures.messages(index, 0).store),
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        return answer.settlement
            ?: fail("§9.4 says a receipt on an unruled rail MAY be parsed: ${answer.reason}")
    }
}
