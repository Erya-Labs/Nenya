package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderOutcome
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.order.TransitionRejection
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T76: §11.2's status rows stepped from the sealed `type=3` by [stepOrderWithStatusUpdate], its
 * sender's party and its order read off the messages rather than typed by the page.
 *
 * Every advancing answer is compared field for field with two others: [stepOrder] over the
 * `JsOrderEvent` a page would fill in by hand, and Kotlin's `OrderMachine.on`. The expected party is
 * the role the **fixture** gave the sealing key, never a call to the code under test.
 */
class JsStatusUpdateStepEqualityTest {

    private val clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)
    private val kotlinMachine = OrderMachine(clock)
    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(
            clock = clock,
            randomness = RecordingRandomness(),
            secp = FakeSecp256k1Ops(),
            ephemeral = FakeEphemeralSigners(),
        ),
    )

    /** One fixture order's proposal, with its three keys. */
    private class Trade(val index: Int, stream: Int) {
        val messages = OrderFixtures.messages(index, stream)
        val proposal: JsRumor = JsRumor(messages.proposalRumor)
        val buyer: String = SettlementFixtures.buyer(index)

        /** The key that really sealed this order's acceptance, so it is the provider §7.6 checks. */
        val provider: String = messages.acceptanceRumor.author
        val feeRecipient: String = SettlementFixtures.feeRecipient(index)
        val orderIdHex: String = messages.proposal.order.toHex()

        /** A `type=3` naming this order and [status], sealed by [author]. */
        fun update(author: String, status: String): AttributedRumor.Bound = SettlementFixtures.boundSealedBy(
            author,
            ProposalFixtures.acceptanceTags(messages.proposalTags, index, status = status),
        )
    }

    private fun opened(trade: Trade): JsOrder =
        openOrderFromProposal(machine, trade.proposal).order ?: fail("the fixture proposal must open an order")

    /** The door's answer, [stepOrder]'s over the hand-filled event, and Kotlin's, all equal. */
    private fun assertThreeAgree(
        trade: Trade,
        order: JsOrder,
        update: AttributedRumor.Bound,
        handFilled: JsOrderEvent,
        kotlinEvent: OrderEvent,
        what: String,
    ): JsOrderOutcome {
        val expected = kotlinMachine.on(order.order, kotlinEvent)
        val door = stepOrderWithStatusUpdate(machine, order, trade.proposal, JsRumor(update), trade.provider)
        JsFlowFixtures.assertSameOutcome(door, expected, "$what: the door against Kotlin `on`")
        JsFlowFixtures.assertSameOutcome(
            stepOrder(machine, order, handFilled),
            expected,
            "$what: `stepOrder` over the hand-filled event against Kotlin `on`",
        )
        return door
    }

    @Test
    @JsName("buyer_and_provider_cancellations_and_the_honest_acceptance_equal_stepOrder_and_Kotlin_on")
    fun `buyer and provider cancellations and the honest acceptance equal stepOrder and Kotlin on`() {
        var reached = 0
        for (index in 0..OrderFixtures.OTHER_ORDER_INDEX) {
            for (stream in 0 until OrderFixtures.STREAMS) {
                val trade = Trade(index, stream)
                val proposed = opened(trade)
                val acceptance = trade.messages.acceptanceRumor
                val accepted = assertThreeAgree(
                    trade,
                    proposed,
                    acceptance,
                    JsOrderEvent(
                        type = "acceptance_received",
                        createdAtSeconds = acceptance.createdAt.toString(),
                        proposalRumor = trade.proposal,
                        acceptanceRumor = JsRumor(acceptance),
                        providerPubkey = trade.provider,
                    ),
                    OrderEvent.AcceptanceReceived(
                        trade.messages.proposal.accepts(trade.messages.acceptance, trade.provider) as?
                            Acceptance.Accepted ?: fail("the fixture acceptance accepts"),
                        acceptance.createdAt,
                    ),
                    "$index/$stream the honest acceptance",
                ).order ?: fail("an acceptance outcome carries its order")
                assertEquals("ACCEPTED", accepted.state, "$index/$stream: §11.2's `proposed → accepted`")

                for ((from, party) in listOf(trade.buyer to Party.BUYER, trade.provider to Party.PROVIDER)) {
                    for (order in listOf(proposed, accepted)) {
                        val update = trade.update(from, "cancelled")
                        val what = "$index/$stream a ${party.name} cancellation of a ${order.state} order"
                        val answer = assertThreeAgree(
                            trade,
                            order,
                            update,
                            JsOrderEvent(
                                type = "status_update",
                                createdAtSeconds = update.createdAt.toString(),
                                statusToken = "cancelled",
                                fromParty = party.name,
                            ),
                            OrderEvent.StatusUpdate(OrderState.CANCELLED, party, createdAt = update.createdAt),
                            what,
                        )
                        assertEquals("CANCELLED", answer.order?.state, "$what: §11.2 cancels from either party")
                        reached++
                    }
                }
            }
        }
        assertEquals(16, reached, "two orders, two streams, two parties, two states")
    }

    /**
     * The control the task names: a `cancelled` sealed by a stranger's [FakeCryptoSigner] and opened
     * through the crossed envelope is no party's, so the door offers the order nothing — where
     * [stepOrder], told `BUYER` by the page, cancels it. A door that skipped `partyOf` would cancel.
     */
    @Test
    @JsName("a_stranger_s_sealed_cancellation_is_SENDER_NOT_A_PARTY_where_stepOrder_told_BUYER_cancels")
    fun `a stranger's sealed cancellation is SENDER_NOT_A_PARTY, where stepOrder told BUYER cancels`() {
        val trade = Trade(OrderFixtures.ORDER_INDEX, stream = 0)
        val proposed = opened(trade)
        val stranger = FakeCryptoSigner(FakeKey(EnvelopeFixtures.STRANGER_STREAM))
        val recipient = FakeCryptoSigner(FakeKey(EnvelopeFixtures.RECIPIENT_STREAM))
        assertFalse(
            stranger.key.hex in listOf(trade.buyer, trade.provider, trade.feeRecipient),
            "the stranger holds none of the order's three roles",
        )

        val built = buildStatusUpdate(
            JsRumorEnvelope(stranger.key.hex, OrderFixtures.BEFORE_DEADLINES.toString(), arrayOf(arrayOf(recipient.key.hex))),
            trade.orderIdHex,
            "cancelled",
        )
        val event = built.event ?: fail("the stranger's update must build: ${built.reason}")
        val sealed = seal(event, recipient.key.hex, JsFlowFixtures.crossed(stranger), machine.environment)
        val wrap = sealed.toRecipient ?: fail("the update must seal: ${sealed.reason}")
        val opened = open(wrap.json, JsFlowFixtures.crossed(recipient), machine.environment)
        val update = opened.rumor ?: fail("the recipient must open it: ${opened.reason}")
        assertEquals(stranger.key.hex, update.author, "§7.2: attributed to the stranger's seal")
        assertEquals("SIGNATURE_VERIFIED", update.attribution)
        assertEquals(trade.orderIdHex, update.orderIdHex, "it names this order")
        assertNull(trade.messages.proposal.partyOf(update.author, trade.provider), "Kotlin: no party")

        val door = stepOrderWithStatusUpdate(machine, proposed, trade.proposal, update, trade.provider)
        assertFalse(door.ok, "a stranger's update steps nothing")
        assertEquals("refused", door.kind)
        assertEquals(ChannelRejection.SENDER_NOT_A_PARTY.name, door.reason)
        assertEquals("ChannelRejection", door.reasonVocabulary)
        assertEquals(ChannelVocabulary.STATUS, door.tag)
        assertNotNull(door.detail)
        assertNull(door.order, "no event was offered, so there is no outcome order")
        assertNull(door.deliveryFailure)
        assertFalse(stranger.key.hex in door.toString(), "the answer's text names no key")

        val trusting = stepOrder(
            machine,
            proposed,
            JsOrderEvent(type = "status_update", statusToken = "cancelled", fromParty = "BUYER"),
        )
        assertTrue(trusting.ok, "`stepOrder` takes the page's word for the sender: ${trusting.reason}")
        assertEquals("CANCELLED", trusting.order?.state, "which is the hole this door closes")
    }

    /** The fee recipient's cancellation is offered as the fee recipient's, and §11.2 refuses that sender. */
    @Test
    @JsName("the_fee_recipient_s_cancellation_is_refused_WRONG_SENDER_by_the_machine")
    fun `the fee recipient's cancellation is refused WRONG_SENDER by the machine`() {
        val trade = Trade(OrderFixtures.ORDER_INDEX, stream = 0)
        val proposed = opened(trade)
        val update = trade.update(trade.feeRecipient, "cancelled")
        val answer = assertThreeAgree(
            trade,
            proposed,
            update,
            JsOrderEvent(
                type = "status_update",
                createdAtSeconds = update.createdAt.toString(),
                statusToken = "cancelled",
                fromParty = "FEE_RECIPIENT",
            ),
            OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.FEE_RECIPIENT, createdAt = update.createdAt),
            "the fee recipient's cancellation",
        )
        assertFalse(answer.ok)
        assertEquals(TransitionRejection.WRONG_SENDER.name, answer.reason)
        assertEquals("TransitionRejection", answer.reasonVocabulary, "§11.2's own answer, not this door's")
        assertEquals("PROPOSED", answer.order?.state, "the order is unchanged")
    }

    /** A key holding two roles is no party: the provider named as fee recipient cancels nothing here. */
    @Test
    @JsName("a_cancellation_from_a_key_holding_two_roles_is_SENDER_NOT_A_PARTY")
    fun `a cancellation from a key holding two roles is SENDER_NOT_A_PARTY`() {
        val index = OrderFixtures.ORDER_INDEX
        val provider = SettlementFixtures.provider(index)
        val tags = ProposalFixtures.proposalTags(
            index = index,
            fee = listOf(ChannelVocabulary.FEE, OrderFixtures.FEE_BASIS_POINTS.toString(), provider),
        )
        val proposalRumor = ProposalFixtures.bound(tags, index)
        assertEquals(provider, OrderProposal.decode(proposalRumor).feeRecipient, "the provider is also fee recipient")
        val proposal = JsRumor(proposalRumor)
        val order = openOrderFromProposal(machine, proposal).order ?: fail("the proposal must open an order")
        val update = SettlementFixtures.boundSealedBy(
            provider,
            ProposalFixtures.acceptanceTags(tags, index, status = "cancelled"),
        )
        val door = stepOrderWithStatusUpdate(machine, order, proposal, JsRumor(update), provider)
        assertEquals(ChannelRejection.SENDER_NOT_A_PARTY.name, door.reason, "neither role is picked")
        assertNull(door.order, "nothing was offered")
    }

    /**
     * Another order's update, and another order's proposal beside this order's update, are each
     * `DIFFERENT_ORDER` with no step — whatever the update's status, the acceptance included.
     */
    @Test
    @JsName("another_order_s_update_or_proposal_is_DIFFERENT_ORDER_with_no_step")
    fun `another order's update or proposal is DIFFERENT_ORDER with no step`() {
        val trade = Trade(OrderFixtures.ORDER_INDEX, stream = 0)
        val other = Trade(OrderFixtures.OTHER_ORDER_INDEX, stream = 0)
        assertTrue(trade.orderIdHex != other.orderIdHex, "two different orders")
        val proposed = opened(trade)
        val accepted = stepOrderWithStatusUpdate(
            machine, proposed, trade.proposal, JsRumor(trade.messages.acceptanceRumor), trade.provider,
        ).order ?: fail("the honest acceptance steps")

        val cases = listOf(
            Triple("another order's cancellation", trade.proposal, JsRumor(other.update(other.buyer, "cancelled"))),
            Triple("another order's acceptance", trade.proposal, JsRumor(other.messages.acceptanceRumor)),
            Triple("another order's proposal with this order's cancellation", other.proposal, JsRumor(trade.update(trade.buyer, "cancelled"))),
            Triple("another order's proposal with this order's acceptance", other.proposal, JsRumor(trade.messages.acceptanceRumor)),
        )
        for (order in listOf(proposed, accepted)) {
            for ((what, proposal, update) in cases) {
                val door = stepOrderWithStatusUpdate(machine, order, proposal, update, trade.provider)
                assertFalse(door.ok, "$what, ${order.state}")
                assertEquals(ChannelRejection.DIFFERENT_ORDER.name, door.reason, "$what, ${order.state}")
                assertEquals("ChannelRejection", door.reasonVocabulary, "$what, ${order.state}")
                assertEquals(ChannelVocabulary.ORDER, door.tag, "$what, ${order.state}")
                assertNull(door.order, "$what, ${order.state}: no event was offered")
            }
        }
    }

    /**
     * A `status=accepted` takes `acceptance_received`'s path exactly, refusals included: sealed by the
     * buyer it is §7.6's `ACCEPTANCE_NOT_FROM_PROVIDER` on both doors, never a status update offered
     * from the buyer.
     */
    @Test
    @JsName("an_acceptance_the_buyer_sealed_is_refused_as_acceptance_received_refuses_it")
    fun `an acceptance the buyer sealed is refused as acceptance_received refuses it`() {
        val trade = Trade(OrderFixtures.ORDER_INDEX, stream = 0)
        val proposed = opened(trade)
        val update = JsRumor(trade.update(trade.buyer, "accepted"))
        val door = stepOrderWithStatusUpdate(machine, proposed, trade.proposal, update, trade.provider)
        val viaStepOrder = stepOrder(
            machine,
            proposed,
            JsOrderEvent(
                type = "acceptance_received",
                proposalRumor = trade.proposal,
                acceptanceRumor = update,
                providerPubkey = trade.provider,
            ),
        )
        assertEquals(ChannelRejection.ACCEPTANCE_NOT_FROM_PROVIDER.name, door.reason)
        assertEquals(viaStepOrder.reason, door.reason, "the same refusal `acceptance_received` makes")
        assertEquals(viaStepOrder.reasonVocabulary, door.reasonVocabulary)
        assertEquals(viaStepOrder.tag, door.tag)
        assertNull(door.order)
        assertTrue(
            kotlinMachine.on(opened(trade).order, OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.BUYER))
                is OrderOutcome.Refused,
            "and the status-update row would refuse it too",
        )
    }
}
