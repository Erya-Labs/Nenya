package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T80: the three `stepOrderWith…` doors refuse a `type=1` that is not the one the order was opened
 * from — another author, other terms, or an order `openOrder` opened with no recorded buyer —
 * `ChannelRejection.NOT_THIS_ORDERS_PROPOSAL`, nothing offered. Before T80 they compared only its
 * `order` id, so a `type=1` a stranger wrote under a known id made the stranger `BUYER`.
 *
 * The stranger is `EnvelopeFixtures`' stranger stream; every rumor is built by the fixtures, and the
 * honest buyer is the key the fixture sealed the proposal with, never a call to the code under test.
 */
class JsProposalDoorTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val buyer = SettlementFixtures.buyer(index)
    private val provider = SettlementFixtures.provider(index)
    private val stranger = EnvelopeFixtures.strangerKey()
    private val proposalRumor = JsRumor(ProposalFixtures.bound(chain.proposalTags, index))
    private val kotlinMachine: OrderMachine = OrderFixtures.machineBeforeDeadlines()
    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)),
    )

    /** The order's own id and terms, sealed by the stranger: a `type=1` that differs only in its author. */
    private val strangersProposal = JsRumor(SettlementFixtures.boundSealedBy(stranger, chain.proposalTags))

    /** The buyer's own second `type=1` under the same id, one satoshi dearer. */
    private val dearerProposal = JsRumor(
        ProposalFixtures.bound(
            chain.proposalTags.map {
                if (it[0] == ChannelTags.AMOUNT_MSAT) listOf(it[0], (chain.terms.split.price.millisatoshis + 1_000L).toString()) else it
            },
            index,
        ),
    )

    private fun proposed(): JsOrder =
        openOrderFromProposal(machine, proposalRumor).order ?: fail("the fixture proposal must open an order")

    private fun accepted(): JsOrder {
        val acceptance = SettlementFixtures.boundSealedBy(provider, ProposalFixtures.acceptanceTags(chain.proposalTags, index))
        val answer = stepOrderWithStatusUpdate(machine, proposed(), proposalRumor, JsRumor(acceptance), provider)
        return answer.order?.takeIf { answer.ok } ?: fail("the honest acceptance steps: ${answer.reason}")
    }

    private fun paid(): JsOrder {
        fun advanced(answer: JsOrderOutcome): JsOrder =
            answer.order?.takeIf { answer.ok } ?: fail("the chain must advance: ${answer.reason} ${answer.detail}")

        val committed = advanced(
            stepOrderWithCommitment(machine, accepted(), proposalRumor, JsRumor(ReleaseFixtures.commitmentRumor()), provider),
        )
        val requested = advanced(
            stepOrder(
                machine,
                committed,
                JsOrderEvent(type = "payment_requests_received", requests = chain.requests.map { JsAcceptedRequest(it) }.toTypedArray()),
            ),
        )
        val paid = advanced(
            stepOrder(
                machine,
                requested,
                JsOrderEvent(type = "receipts_verified", settlements = OrderFixtures.bothReceipts().map { JsSettlement(it) }.toTypedArray()),
            ),
        )
        assertEquals("PAID", paid.state)
        return paid
    }

    private fun cancellation(author: String) = JsRumor(
        SettlementFixtures.boundSealedBy(author, ProposalFixtures.acceptanceTags(chain.proposalTags, index, status = "cancelled")),
    )

    private fun assertRefusedHere(door: JsOrderOutcome, what: String) {
        assertFalse(door.ok, what)
        assertEquals("refused", door.kind, what)
        assertEquals(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL.name, door.reason, what)
        assertEquals("ChannelRejection", door.reasonVocabulary, what)
        assertNull(door.tag, "$what: the rule names no tag")
        assertNull(door.order, "$what: no event was offered, so there is no outcome order")
        assertFalse(stranger in door.toString() || buyer in door.toString(), "$what: the answer's text names no key")
    }

    /** `Order.buyer` is the attributed author of the `type=1` the order was opened from, carried by every step, and never printed. */
    @Test
    @JsName("the_order_records_the_proposal_s_author_as_its_buyer_and_never_prints_it")
    fun `the order records the proposal's author as its buyer, and never prints it`() {
        val proposed = proposed()
        assertEquals(buyer, proposed.order.buyer)
        assertEquals(buyer, kotlinMachine.open(chain.proposal.asOrderEvent()).buyer, "the Kotlin door records the same key")
        assertEquals(buyer, accepted().order.buyer, "the acceptance carries it")
        assertEquals(buyer, paid().order.buyer, "and so does every step after")
        assertFalse(buyer in proposed.order.toString(), "Order.toString names no buyer key")
        assertFalse(buyer in proposed.toString(), "nor does the crossed order's")

        assertNull(OrderEvent.Proposal(chain.proposal.order, chain.terms).buyer, "the published constructor sets no buyer")
        assertNull(kotlinMachine.open(OrderEvent.Proposal(chain.proposal.order, chain.terms)).buyer)
        val bare = openOrder(machine, chain.proposal.order.toHex(), JsBoundaryFixtures.crossed(chain.terms)).order
            ?: fail("the bare id and terms open an order")
        assertNull(bare.order.buyer, "openOrder read no seal, so it records no buyer")
    }

    /**
     * The task's first control: a `type=1` and a `cancelled` sealed by the stranger under the order's
     * id and terms step nothing — where, mapped against the stranger's proposal as the door did before
     * T80, the stranger is `BUYER` and Kotlin `on` cancels the order.
     */
    @Test
    @JsName("a_stranger_s_proposal_and_cancellation_under_the_order_s_id_and_terms_are_NOT_THIS_ORDERS_PROPOSAL")
    fun `a stranger's proposal and cancellation under the order's id and terms are NOT_THIS_ORDERS_PROPOSAL`() {
        assertFalse(stranger in listOf(buyer, provider, SettlementFixtures.feeRecipient(index)), "the stranger holds no role")
        val forged = OrderProposal.decode(SettlementFixtures.boundSealedBy(stranger, chain.proposalTags))
        assertEquals(chain.proposal.order, forged.order, "the forgery names the order's own id")
        assertEquals(chain.terms, forged.terms, "and its own terms")

        val proposed = proposed()
        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, proposed, strangersProposal, cancellation(stranger), provider),
            "a stranger's cancellation against the stranger's proposal",
        )

        assertEquals(Party.BUYER, forged.partyOf(stranger, provider), "against its own proposal the stranger maps to BUYER")
        val unchecked = kotlinMachine.on(proposed.order, OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER))
        assertEquals(OrderState.CANCELLED, unchecked.order.state, "which is the hole this comparison closes")
    }

    /** The commitment and release doors make the same comparison, ahead of T79's provider-key check. */
    @Test
    @JsName("the_commitment_and_release_doors_refuse_the_stranger_s_proposal_too")
    fun `the commitment and release doors refuse the stranger's proposal too`() {
        val accepted = accepted()
        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, accepted, strangersProposal, cancellation(stranger), provider),
            "a stranger's cancellation of an accepted order",
        )
        assertRefusedHere(
            stepOrderWithCommitment(machine, accepted, strangersProposal, JsRumor(ReleaseFixtures.commitmentRumor()), stranger),
            "the honest commitment against the stranger's proposal, the stranger as provider",
        )
        val paid = paid()
        assertRefusedHere(
            stepOrderWithRelease(machine, paid, strangersProposal, JsRumor(ReleaseFixtures.releaseRumor()), provider),
            "the honest release against the stranger's proposal",
        )
        val release = DeliverableReleaseMessage.decode(ReleaseFixtures.releaseRumor())
        assertEquals(OrderState.RELEASED, kotlinMachine.on(paid.order, release.asOrderEvent(paid.order, Party.PROVIDER)).order.state)
        val commitment = DeliveryCommitmentMessage.decode(ReleaseFixtures.commitmentRumor())
        assertEquals(OrderState.COMMITTED, kotlinMachine.on(accepted.order, commitment.asOrderEvent(Party.PROVIDER)).order.state)
    }

    /** The second: the buyer's own second `type=1`, same id, another price, steps nothing — terms are compared, not only the author. */
    @Test
    @JsName("the_buyer_s_second_proposal_at_another_price_is_NOT_THIS_ORDERS_PROPOSAL")
    fun `the buyer's second proposal at another price is NOT_THIS_ORDERS_PROPOSAL`() {
        val dearer = OrderProposal.decode(dearerProposal.asBoundRumor("the dearer proposal"))
        assertEquals(buyer, dearer.buyer, "the same author")
        assertEquals(chain.proposal.order, dearer.order, "the same id")
        assertNotEquals(chain.terms, dearer.terms, "other terms")

        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, proposed(), dearerProposal, cancellation(buyer), provider),
            "the buyer's cancellation against its dearer proposal",
        )
        assertRefusedHere(
            stepOrderWithCommitment(machine, accepted(), dearerProposal, JsRumor(ReleaseFixtures.commitmentRumor()), provider),
            "the honest commitment against the dearer proposal",
        )
    }

    /** The third: an order `openOrder` opened records no buyer, so no proposal is its own, and every door refuses. */
    @Test
    @JsName("an_order_openOrder_opened_cannot_use_the_doors")
    fun `an order openOrder opened cannot use the doors`() {
        val bare = openOrder(machine, chain.proposal.order.toHex(), JsBoundaryFixtures.crossed(chain.terms)).order
            ?: fail("the bare id and terms open an order")
        assertEquals("PROPOSED", bare.state)
        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, bare, proposalRumor, cancellation(buyer), provider),
            "the buyer's cancellation of an openOrder order",
        )
        assertRefusedHere(
            stepOrderWithStatusUpdate(
                machine, bare, proposalRumor,
                JsRumor(SettlementFixtures.boundSealedBy(provider, ProposalFixtures.acceptanceTags(chain.proposalTags, index))),
                provider,
            ),
            "the honest acceptance of an openOrder order",
        )
    }

    /** The honest proposal still steps all three doors to `released`: the comparison refuses only what is not this order's. */
    @Test
    @JsName("the_honest_proposal_still_steps_every_door")
    fun `the honest proposal still steps every door`() {
        val released = stepOrderWithRelease(machine, paid(), proposalRumor, JsRumor(ReleaseFixtures.releaseRumor()), provider)
        assertTrue(released.ok, "the release: ${released.reason}")
        assertEquals("RELEASED", released.order?.state)
    }
}
