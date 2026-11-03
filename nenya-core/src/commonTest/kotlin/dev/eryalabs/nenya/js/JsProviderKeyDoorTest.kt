package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T79: once an order is accepted, the three `stepOrderWith…` doors refuse a provider key other than
 * the one its acceptance was checked against — `ChannelRejection.PROVIDER_NOT_THIS_ORDERS`, nothing
 * offered — so a page that resolved the provider wrongly on a later message cannot make a stranger
 * this order's `PROVIDER`.
 *
 * The stranger is `EnvelopeFixtures`' stranger stream; the honest provider is the key the fixture
 * gave the acceptance's seal, never a call to the code under test.
 */
class JsProviderKeyDoorTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val provider = SettlementFixtures.provider(index)
    private val buyer = SettlementFixtures.buyer(index)
    private val stranger = EnvelopeFixtures.strangerKey()
    private val proposalRumor = JsRumor(ProposalFixtures.bound(chain.proposalTags, index))
    private val kotlinMachine: OrderMachine = OrderFixtures.machineBeforeDeadlines()
    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)),
    )

    private fun proposed(): JsOrder =
        openOrderFromProposal(machine, proposalRumor).order ?: fail("the fixture proposal must open an order")

    /** The fixture's honest acceptance, through the status door. */
    private fun accepted(): JsOrder {
        val acceptance = SettlementFixtures.boundSealedBy(provider, ProposalFixtures.acceptanceTags(chain.proposalTags, index))
        val answer = stepOrderWithStatusUpdate(machine, proposed(), proposalRumor, JsRumor(acceptance), provider)
        val order = answer.order ?: fail("the honest acceptance steps: ${answer.reason}")
        assertEquals("ACCEPTED", order.state)
        assertEquals(provider, order.order.provider, "the order records the key its acceptance was checked against")
        return order
    }

    /** [accepted], committed through the door, then the payment steps replayed: a `paid` order. */
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
                JsOrderEvent(
                    type = "payment_requests_received",
                    requests = chain.requests.map { JsAcceptedRequest(it) }.toTypedArray(),
                ),
            ),
        )
        val paid = advanced(
            stepOrder(
                machine,
                requested,
                JsOrderEvent(
                    type = "receipts_verified",
                    settlements = OrderFixtures.bothReceipts().map { JsSettlement(it) }.toTypedArray(),
                ),
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
        assertEquals(ChannelRejection.PROVIDER_NOT_THIS_ORDERS.name, door.reason, what)
        assertEquals("ChannelRejection", door.reasonVocabulary, what)
        assertNull(door.tag, "$what: the rule names no tag")
        assertNull(door.order, "$what: no event was offered, so there is no outcome order")
        assertFalse(stranger in door.toString() || provider in door.toString(), "$what: the answer's text names no key")
    }

    /**
     * The task's first control: after the honest acceptance, a commitment the stranger sealed, with the
     * stranger handed in as the provider, steps nothing — where, mapped against that key as the door
     * did before T79, it is `PROVIDER`'s and Kotlin `on` commits the order.
     */
    @Test
    @JsName("a_stranger_s_commitment_with_the_stranger_as_provider_is_PROVIDER_NOT_THIS_ORDERS")
    fun `a stranger's commitment with the stranger as provider is PROVIDER_NOT_THIS_ORDERS`() {
        assertFalse(
            stranger in listOf(buyer, provider, SettlementFixtures.feeRecipient(index)),
            "the stranger holds none of the order's three roles",
        )
        val accepted = accepted()
        val commitment = ReleaseFixtures.commitmentRumor(stranger)
        assertRefusedHere(
            stepOrderWithCommitment(machine, accepted, proposalRumor, JsRumor(commitment), stranger),
            "a stranger's commitment",
        )

        assertEquals(Party.PROVIDER, chain.proposal.partyOf(stranger, stranger), "against its own key the stranger maps to PROVIDER")
        val unchecked = kotlinMachine.on(accepted.order, DeliveryCommitmentMessage.decode(commitment).asOrderEvent(Party.PROVIDER))
        assertEquals(OrderState.COMMITTED, unchecked.order.state, "which is the hole this comparison closes")
    }

    /** The second: a stranger's `cancelled`, the stranger as provider, steps nothing; offered as `PROVIDER` it cancels. */
    @Test
    @JsName("a_stranger_s_cancellation_with_the_stranger_as_provider_is_PROVIDER_NOT_THIS_ORDERS")
    fun `a stranger's cancellation with the stranger as provider is PROVIDER_NOT_THIS_ORDERS`() {
        val accepted = accepted()
        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, accepted, proposalRumor, cancellation(stranger), stranger),
            "a stranger's cancellation",
        )
        assertRefusedHere(
            stepOrderWithStatusUpdate(machine, accepted, proposalRumor, cancellation(buyer), stranger),
            "the buyer's own cancellation, handed a wrong provider key",
        )
        val unchecked = kotlinMachine.on(accepted.order, OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.PROVIDER))
        assertEquals(OrderState.CANCELLED, unchecked.order.state, "offered as PROVIDER, the stranger would cancel")
    }

    /** The release door makes the same comparison: a stranger's release, the stranger as provider, releases nothing. */
    @Test
    @JsName("a_stranger_s_release_with_the_stranger_as_provider_is_PROVIDER_NOT_THIS_ORDERS")
    fun `a stranger's release with the stranger as provider is PROVIDER_NOT_THIS_ORDERS`() {
        val paid = paid()
        val release = ReleaseFixtures.releaseRumor(stranger)
        assertRefusedHere(
            stepOrderWithRelease(machine, paid, proposalRumor, JsRumor(release), stranger),
            "a stranger's release",
        )
        val unchecked = kotlinMachine.on(paid.order, DeliverableReleaseMessage.decode(release).asOrderEvent(paid.order, Party.PROVIDER))
        assertEquals(OrderState.RELEASED, unchecked.order.state, "offered as PROVIDER, the stranger would release")
    }

    /** §4.3 accepts and normalises an uppercase key, so the honest provider spelt in uppercase still steps every door. */
    @Test
    @JsName("the_honest_provider_key_in_uppercase_still_steps_every_door")
    fun `the honest provider key in uppercase still steps every door`() {
        val upper = provider.uppercase()
        assertTrue(upper != provider, "the fixture key has letters to raise")

        val accepted = accepted()
        val committed = stepOrderWithCommitment(machine, accepted, proposalRumor, JsRumor(ReleaseFixtures.commitmentRumor()), upper)
        assertTrue(committed.ok, "the commitment: ${committed.reason}")
        assertEquals("COMMITTED", committed.order?.state)

        val cancelled = stepOrderWithStatusUpdate(machine, accepted, proposalRumor, cancellation(provider), upper)
        assertTrue(cancelled.ok, "the provider's cancellation: ${cancelled.reason}")
        assertEquals("CANCELLED", cancelled.order?.state)

        val released = stepOrderWithRelease(machine, paid(), proposalRumor, JsRumor(ReleaseFixtures.releaseRumor()), upper)
        assertTrue(released.ok, "the release: ${released.reason}")
        assertEquals("RELEASED", released.order?.state)
    }

    /** At `proposed` no key is recorded, so none is compared: the buyer's cancellation steps whatever well-formed key is handed. */
    @Test
    @JsName("at_proposed_no_provider_key_is_compared")
    fun `at proposed no provider key is compared`() {
        val proposed = proposed()
        assertNull(proposed.order.provider, "no acceptance, no recorded key")
        val answer = stepOrderWithStatusUpdate(machine, proposed, proposalRumor, cancellation(buyer), stranger)
        assertTrue(answer.ok, "the buyer's cancellation: ${answer.reason}")
        assertEquals("CANCELLED", answer.order?.state)
    }

    /** A malformed key is still read as `partyOf` reads it: `MALFORMED_PROVIDER_PUBKEY`, before any comparison. */
    @Test
    @JsName("a_malformed_provider_key_on_an_accepted_order_is_still_MALFORMED_PROVIDER_PUBKEY")
    fun `a malformed provider key on an accepted order is still MALFORMED_PROVIDER_PUBKEY`() {
        val door = stepOrderWithCommitment(
            machine, accepted(), proposalRumor, JsRumor(ReleaseFixtures.commitmentRumor()), provider.dropLast(1),
        )
        assertEquals(ChannelRejection.MALFORMED_PROVIDER_PUBKEY.name, door.reason)
        assertNull(door.order)
    }
}
