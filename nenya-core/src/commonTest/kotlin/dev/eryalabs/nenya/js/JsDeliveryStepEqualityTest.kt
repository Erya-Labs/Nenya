package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.DeliveryTags
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.order.TransitionRejection
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T77: §10.1's commitment and §10.3's release stepped from their decoded rumors by
 * [stepOrderWithCommitment] and [stepOrderWithRelease], so a browser order records the checks their
 * decoders make.
 *
 * The positive case walks one `OrderFixtures` order to `settled` twice, step for step: once through
 * the two doors (the other steps through [stepOrder], as before), once through Kotlin's
 * `OrderMachine.on` over `DeliveryCommitmentMessage.asOrderEvent` and
 * `DeliverableReleaseMessage.asOrderEvent`. Each step's answers are held equal field for field, and
 * each side continues from its own order. The expected party is the role the **fixture** gave the
 * sealing key, never a call to the code under test.
 */
class JsDeliveryStepEqualityTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val provider = SettlementFixtures.provider(index)
    private val proposalRumor = JsRumor(ProposalFixtures.bound(chain.proposalTags, index))
    private val kotlinMachine: OrderMachine = OrderFixtures.machineBeforeDeadlines()
    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)),
    )

    /** The chain's orders, by state, as the doors produced them; each step held equal to Kotlin's. */
    private fun walk(): Map<OrderState, JsOrder> {
        val reached = linkedMapOf<OrderState, JsOrder>()
        var kotlin: Order = kotlinMachine.open(chain.proposal.asOrderEvent())
        var crossed: JsOrder = openOrderFromProposal(machine, proposalRumor).order
            ?: fail("the fixture proposal must open an order")
        JsBoundaryFixtures.assertSameFields(
            JsFlowFixtures.orderFields(crossed, kotlin),
            "the opened order",
        )
        reached[OrderState.PROPOSED] = crossed

        fun step(what: String, kotlinEvent: (Order) -> OrderEvent, door: (JsOrder) -> JsOrderOutcome) {
            val expected = kotlinMachine.on(kotlin, kotlinEvent(kotlin))
            val answer = door(crossed)
            JsFlowFixtures.assertSameOutcome(answer, expected, what)
            assertTrue(answer.ok, "$what must advance: ${answer.reason} ${answer.detail}")
            kotlin = expected.order
            crossed = answer.order ?: fail("$what: an advancing outcome carries its order")
            reached[kotlin.state] = crossed
        }

        step(
            "§7.6's acceptance",
            { OrderEvent.AcceptanceReceived(chain.accepted) },
            {
                stepOrder(
                    machine,
                    it,
                    JsOrderEvent(
                        type = "acceptance_received",
                        proposalRumor = proposalRumor,
                        acceptanceRumor = JsRumor(
                            SettlementFixtures.boundSealedBy(
                                provider,
                                ProposalFixtures.acceptanceTags(chain.proposalTags, index),
                            ),
                        ),
                        providerPubkey = provider,
                    ),
                )
            },
        )
        val commitment = ReleaseFixtures.commitmentRumor()
        step(
            "§10.1's commitment, through its door",
            { DeliveryCommitmentMessage.decode(commitment).asOrderEvent(Party.PROVIDER) },
            { stepOrderWithCommitment(machine, it, proposalRumor, JsRumor(commitment), provider) },
        )
        step(
            "§8.6's payment requests",
            { OrderEvent.PaymentRequestsReceived(chain.requests) },
            {
                stepOrder(
                    machine,
                    it,
                    JsOrderEvent(
                        type = "payment_requests_received",
                        requests = chain.requests.map { request -> JsAcceptedRequest(request) }.toTypedArray(),
                    ),
                )
            },
        )
        step(
            "§9.2's receipts",
            { OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()) },
            {
                stepOrder(
                    machine,
                    it,
                    JsOrderEvent(
                        type = "receipts_verified",
                        settlements = OrderFixtures.bothReceipts().map { receipt -> JsSettlement(receipt) }.toTypedArray(),
                    ),
                )
            },
        )
        val release = ReleaseFixtures.releaseRumor()
        step(
            "§10.3's release, through its door",
            { order -> DeliverableReleaseMessage.decode(release).asOrderEvent(order, Party.PROVIDER) },
            { stepOrderWithRelease(machine, it, proposalRumor, JsRumor(release), provider) },
        )
        step(
            "§10.4's evidence",
            { OrderEvent.DeliveryVerified(OrderFixtures.evidence()) },
            {
                stepOrder(
                    machine,
                    it,
                    JsOrderEvent(
                        type = "delivery_verified",
                        commitment = JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                        servedBytesHex = encodeLowerHex(OrderFixtures.blob.served),
                        plaintextBytesHex = encodeLowerHex(OrderFixtures.blob.plaintext),
                    ),
                )
            },
        )
        return reached
    }

    private fun at(state: OrderState): JsOrder = walk()[state] ?: fail("the chain must reach $state")

    /** The task's proof: both doors in the chain, and the settled order records both checks as performed. */
    @Test
    @JsName("a_chain_through_both_doors_settles_with_both_delivery_checks_performed_equal_to_Kotlin_on")
    fun `a chain through both doors settles with both delivery checks performed, equal to Kotlin on`() {
        val reached = walk()
        assertEquals(
            listOf(
                OrderState.PROPOSED, OrderState.ACCEPTED, OrderState.COMMITTED, OrderState.AWAITING_PAYMENT,
                OrderState.PAID, OrderState.RELEASED, OrderState.SETTLED,
            ),
            reached.keys.toList(),
            "§11.2's advancing rows, in order",
        )
        val committed = reached.getValue(OrderState.COMMITTED)
        assertEquals(
            listOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY.name),
            committed.deliveryChecksPerformed.toList(),
            "§10.1's key check is recorded as the commitment's door passes it",
        )
        val settled = reached.getValue(OrderState.SETTLED)
        val performed = settled.deliveryChecksPerformed.toSet()
        val notPerformed = settled.deliveryChecksNotPerformedHere.toSet()
        for (check in listOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY, DeliveryCheck.RELEASE_ORDER_BINDING)) {
            assertTrue(check.name in performed, "the settled order records ${check.name} as performed")
            assertFalse(check.name in notPerformed, "and does not also list ${check.name} as not performed")
        }
    }

    /**
     * §10.1's absolute rule, through the door: the key-carrying commitment `JsMessageEqualityTest`
     * crosses is the decoder's `COMMITMENT_CARRIES_KEY`, and nothing is offered — where [stepOrder],
     * handed the same commitment's four values, commits the order.
     */
    @Test
    @JsName("a_key_carrying_commitment_is_COMMITMENT_CARRIES_KEY_where_stepOrder_over_its_values_commits")
    fun `a key-carrying commitment is COMMITMENT_CARRIES_KEY, where stepOrder over its values commits`() {
        val accepted = at(OrderState.ACCEPTED)
        val fixture = DeliveryMessageFixtures.pairs(1).single()
        val carriesKey = DeliveryMessageFixtures.bound(
            DeliveryMessageFixtures.with(
                fixture.commitmentTags,
                listOf(
                    DeliveryTags.DECRYPTION_KEY,
                    DeliveryMessageFixtures.hex(DeliveryMessageFixtures.keyBytes(fixture.index)),
                ),
            ),
            NenyaKind.ORDER_MESSAGE,
            fixture.index,
        )
        val door = stepOrderWithCommitment(machine, accepted, proposalRumor, JsRumor(carriesKey), provider)
        assertFalse(door.ok, "§10.1: a commitment MUST NOT contain `decryption-key`")
        assertEquals("refused", door.kind)
        assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY.name, door.reason)
        assertEquals("ChannelRejection", door.reasonVocabulary)
        assertEquals(DeliveryTags.DECRYPTION_KEY, door.tag)
        assertNull(door.order, "no event was offered, so there is no outcome order")

        val values = DeliveryMessageFixtures.commitment(fixture.commitmentTags, fixture.index).commitment
        val trusting = stepOrder(
            machine,
            accepted,
            JsOrderEvent(
                type = "delivery_committed",
                fromParty = "PROVIDER",
                commitment = JsFlowFixtures.crossed(values),
            ),
        )
        assertTrue(trusting.ok, "`stepOrder` reads no tags: ${trusting.reason}")
        assertEquals("COMMITTED", trusting.order?.state, "which is the hole this door closes")
        assertTrue(
            trusting.order?.deliveryChecksPerformed?.isEmpty() == true,
            "and that order records no check",
        )
    }

    /** Another order's provider-sealed commitment, or another order's proposal, is `DIFFERENT_ORDER`, no step. */
    @Test
    @JsName("another_order_s_commitment_or_proposal_is_DIFFERENT_ORDER_with_no_step")
    fun `another order's commitment or proposal is DIFFERENT_ORDER with no step`() {
        val accepted = at(OrderState.ACCEPTED)
        val other = OrderFixtures.OTHER_ORDER_INDEX
        val otherProposal = JsRumor(ProposalFixtures.bound(OrderFixtures.chain(index = other).proposalTags, other))
        val cases = listOf(
            Triple(
                "another order's provider-sealed commitment",
                proposalRumor,
                ReleaseFixtures.commitmentRumor(SettlementFixtures.provider(other), other),
            ),
            Triple("another order's proposal with this order's commitment", otherProposal, ReleaseFixtures.commitmentRumor()),
        )
        for ((what, proposal, commitment) in cases) {
            val door = stepOrderWithCommitment(machine, accepted, proposal, JsRumor(commitment), provider)
            assertFalse(door.ok, what)
            assertEquals(ChannelRejection.DIFFERENT_ORDER.name, door.reason, what)
            assertEquals("ChannelRejection", door.reasonVocabulary, what)
            assertEquals(ChannelVocabulary.ORDER, door.tag, what)
            assertNull(door.order, "$what: no event was offered")
        }
        val release = stepOrderWithRelease(
            machine, at(OrderState.PAID), otherProposal, JsRumor(ReleaseFixtures.releaseRumor()), provider,
        )
        assertEquals(ChannelRejection.DIFFERENT_ORDER.name, release.reason, "another order's proposal with this order's release")
        assertNull(release.order)
    }

    /** A buyer-sealed commitment is offered as the buyer's, and §11.2 refuses that sender: `WRONG_SENDER`. */
    @Test
    @JsName("a_buyer_sealed_commitment_is_refused_WRONG_SENDER_by_the_machine")
    fun `a buyer-sealed commitment is refused WRONG_SENDER by the machine`() {
        val accepted = at(OrderState.ACCEPTED)
        val commitment = ReleaseFixtures.commitmentRumor(SettlementFixtures.buyer(index))
        val expected = kotlinMachine.on(
            accepted.order,
            DeliveryCommitmentMessage.decode(commitment).asOrderEvent(Party.BUYER),
        )
        val door = stepOrderWithCommitment(machine, accepted, proposalRumor, JsRumor(commitment), provider)
        JsFlowFixtures.assertSameOutcome(door, expected, "a buyer-sealed commitment")
        assertFalse(door.ok)
        assertEquals(TransitionRejection.WRONG_SENDER.name, door.reason)
        assertEquals("TransitionRejection", door.reasonVocabulary, "§11.2's own answer, not this door's")
        assertEquals("ACCEPTED", door.order?.state, "the order is unchanged")
    }

    /**
     * Another order's release, sealed by this order's provider, is §10.3's `RELEASE_FOR_ANOTHER_ORDER`
     * from `DeliverableReleaseMessage.asOrderEvent`, with no step.
     */
    @Test
    @JsName("another_order_s_release_is_RELEASE_FOR_ANOTHER_ORDER_with_no_step")
    fun `another order's release is RELEASE_FOR_ANOTHER_ORDER with no step`() {
        val paid = at(OrderState.PAID)
        val release = ReleaseFixtures.releaseRumor(provider, OrderFixtures.OTHER_ORDER_INDEX)
        val door = stepOrderWithRelease(machine, paid, proposalRumor, JsRumor(release), provider)
        assertFalse(door.ok)
        assertEquals(ChannelRejection.RELEASE_FOR_ANOTHER_ORDER.name, door.reason)
        assertEquals("ChannelRejection", door.reasonVocabulary)
        assertEquals(ChannelVocabulary.ORDER, door.tag)
        assertNull(door.order, "no event was offered")

        val early = stepOrderWithRelease(
            machine, at(OrderState.COMMITTED), proposalRumor, JsRumor(ReleaseFixtures.releaseRumor()), provider,
        )
        assertEquals(ChannelRejection.RELEASE_ORDER_NOT_PAID.name, early.reason, "§10.3: only a `paid` order is released")
        assertNull(early.order)
    }

    /** A commitment or release sealed by a key holding none of the order's roles is `SENDER_NOT_A_PARTY`. */
    @Test
    @JsName("a_stranger_s_commitment_or_release_is_SENDER_NOT_A_PARTY_with_no_step")
    fun `a stranger's commitment or release is SENDER_NOT_A_PARTY with no step`() {
        val stranger = SettlementFixtures.stranger(index)
        assertFalse(
            stranger in listOf(SettlementFixtures.buyer(index), provider, SettlementFixtures.feeRecipient(index)),
            "the stranger holds none of the order's three roles",
        )
        val answers: List<Pair<String, JsOrderOutcome>> = listOf(
            "commitment" to stepOrderWithCommitment(
                machine, at(OrderState.ACCEPTED), proposalRumor, JsRumor(ReleaseFixtures.commitmentRumor(stranger)), provider,
            ),
            "release" to stepOrderWithRelease(
                machine, at(OrderState.PAID), proposalRumor, JsRumor(ReleaseFixtures.releaseRumor(stranger)), provider,
            ),
        )
        for ((what, door) in answers) {
            assertFalse(door.ok, what)
            assertEquals(ChannelRejection.SENDER_NOT_A_PARTY.name, door.reason, what)
            assertEquals("ChannelRejection", door.reasonVocabulary, what)
            assertNull(door.tag, "$what: the rule names no tag")
            assertNull(door.order, "$what: no event was offered")
        }
    }
}
