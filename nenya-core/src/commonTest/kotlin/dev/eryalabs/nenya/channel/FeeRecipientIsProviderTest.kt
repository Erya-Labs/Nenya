package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.js.JsFlowFixtures
import dev.eryalabs.nenya.js.JsOrderMachine
import dev.eryalabs.nenya.js.JsRumor
import dev.eryalabs.nenya.js.decodeProposal
import dev.eryalabs.nenya.js.openOrderFromProposal
import dev.eryalabs.nenya.js.stepOrderWithStatusUpdate
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.seam.FakeClock
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
 * T108 — §8.1's "It MAY be the provider's pubkey only in a genuine agent relationship, and
 * implementations SHOULD warn when it is", answered by `OrderProposal.feeRecipientIsProvider` and
 * `Order.feeRecipientIsProvider`, and crossed on `JsOrderProposal` and `JsOrder`.
 *
 * A warning and never a refusal: a proposal naming its provider as fee recipient still decodes, opens
 * and is accepted. The expected answer is always the role the **fixture** gave a key, never a call to
 * the code under test.
 */
class FeeRecipientIsProviderTest {

    private companion object {
        val INDEX: Int = OrderFixtures.ORDER_INDEX
        val PROVIDER: String = SettlementFixtures.provider(INDEX)
        val THIRD_KEY: String = SettlementFixtures.feeRecipient(INDEX)

        /** A generated proposal whose `fee` names [recipient], and [basisPoints], as the term. */
        fun proposalTags(recipient: String, basisPoints: Int = OrderFixtures.FEE_BASIS_POINTS): List<List<String>> =
            ProposalFixtures.proposalTags(
                index = INDEX,
                fee = listOf(ChannelVocabulary.FEE, basisPoints.toString(), recipient),
            )

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(160).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }
    }

    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)),
    )

    /** Over every fee-bearing generated proposal: the crossed answer equals Kotlin's, for every key. */
    @Test
    @JsName("the_crossed_answer_equals_kotlin_s_over_every_fee_bearing_generated_proposal")
    fun `the crossed answer equals Kotlin's over every fee-bearing generated proposal`() {
        val feeBearing = PROPOSALS.filter { it.feeRecipient != null }
        assertTrue(feeBearing.size >= 5, "the corpus must hold fee-bearing proposals; it has ${feeBearing.size}")
        for (case in feeBearing) {
            val rumor = RumorWriterFixtures.bound(RumorWriterFixtures.build(case))
            val kotlin = OrderProposal.decode(rumor)
            val crossed = decodeProposal(JsRumor(rumor)).proposal ?: fail("$case must decode")
            val recipient = case.feeRecipient ?: fail("$case names a recipient")
            val provider = case.envelope.counterparties.first().pubkey
            val expected = mapOf(
                recipient to true,
                recipient.uppercase() to true,
                kotlin.buyer to false,
                provider to (provider.lowercase() == recipient.lowercase()),
                THIRD_KEY to (THIRD_KEY == recipient),
            )
            for ((key, answer) in expected) {
                assertEquals(answer, kotlin.feeRecipientIsProvider(key), "$case: Kotlin")
                assertEquals(answer, crossed.feeRecipientIsProvider(key), "$case: crossed")
            }
        }
    }

    /** A proposal naming its provider: true, in either case; it still decodes, opens and is accepted. */
    @Test
    @JsName("a_proposal_naming_its_provider_answers_true_and_its_accepted_order_too")
    fun `a proposal naming its provider answers true, and its accepted order too`() {
        val tags = proposalTags(PROVIDER)
        val rumor = ProposalFixtures.bound(tags, INDEX)
        val proposal = OrderProposal.decode(rumor)
        assertEquals(PROVIDER, proposal.feeRecipient, "the fixture names the provider as recipient")
        assertTrue(proposal.feeRecipientIsProvider(PROVIDER))
        assertTrue(proposal.feeRecipientIsProvider(PROVIDER.uppercase()), "§4.3's lowercase, as proposalForBid compares")
        val crossed = decodeProposal(JsRumor(rumor)).proposal ?: fail("the proposal must decode")
        assertTrue(crossed.feeRecipientIsProvider(PROVIDER.uppercase()))
        assertFalse(crossed.toString().contains(PROVIDER), "no key printed")

        val opened = openOrderFromProposal(machine, JsRumor(rumor)).order ?: fail("the proposal must open an order")
        assertNull(opened.order.provider, "no acceptance yet")
        assertFalse(opened.feeRecipientIsProvider, "an order with no provider recorded answers false")
        assertFalse(opened.order.feeRecipientIsProvider)

        val acceptance = SettlementFixtures.boundSealedBy(PROVIDER, ProposalFixtures.acceptanceTags(tags, INDEX))
        val step = stepOrderWithStatusUpdate(machine, opened, JsRumor(rumor), JsRumor(acceptance), PROVIDER)
        val accepted = step.order ?: fail("the provider's acceptance must advance: ${step.reason}")
        assertEquals(OrderState.ACCEPTED.name, accepted.state, "a warning, not a refusal")
        assertTrue(accepted.feeRecipientIsProvider, "the crossed order")
        assertTrue(accepted.order.feeRecipientIsProvider, "the Kotlin order")
        assertFalse(accepted.order.toString().contains(PROVIDER), "no key printed")
    }

    /** The buyer's key, a third key, and a proposal with no recipient each answer false. */
    @Test
    @JsName("the_buyer_a_third_key_and_a_proposal_naming_no_recipient_answer_false")
    fun `the buyer, a third key and a proposal naming no recipient answer false`() {
        val proposal = OrderProposal.decode(ProposalFixtures.bound(proposalTags(THIRD_KEY), INDEX))
        assertNotEquals(PROVIDER, THIRD_KEY)
        assertFalse(proposal.feeRecipientIsProvider(proposal.buyer), "the buyer's key")
        assertFalse(proposal.feeRecipientIsProvider(PROVIDER), "the provider, when a third key is the recipient")
        assertTrue(proposal.feeRecipientIsProvider(THIRD_KEY), "the sense is not inverted")

        val noRecipient = PROPOSALS.filter { it.feeRecipient == null }
        assertTrue(noRecipient.isNotEmpty(), "the corpus must hold proposals naming no recipient")
        for (case in noRecipient) {
            val kotlin = OrderProposal.decode(RumorWriterFixtures.bound(RumorWriterFixtures.build(case)))
            assertNull(kotlin.feeRecipient, "$case names none")
            for (key in listOf(case.envelope.counterparties.first().pubkey, kotlin.buyer, THIRD_KEY, "")) {
                assertFalse(kotlin.feeRecipientIsProvider(key), "$case: no recipient is no provider")
            }
        }

        val zero = OrderProposal.decode(
            ProposalFixtures.bound(
                ProposalFixtures.proposalTags(index = INDEX, fee = listOf(ChannelVocabulary.FEE, "0")),
                INDEX,
            ),
        )
        assertNull(zero.feeRecipient, "a stated zero names no recipient")
        assertFalse(zero.feeRecipientIsProvider(PROVIDER), "fee `0`")

        // The fixture order: a third key as fee recipient, the provider recorded at acceptance.
        val orders = OrderFixtures.orders()
        val accepted = orders.getValue(OrderState.ACCEPTED)
        assertEquals(PROVIDER, accepted.provider)
        assertEquals(THIRD_KEY, accepted.feeRecipient)
        for ((state, order) in orders) {
            assertFalse(order.feeRecipientIsProvider, "a $state order whose recipient is a third key")
        }
    }
}
