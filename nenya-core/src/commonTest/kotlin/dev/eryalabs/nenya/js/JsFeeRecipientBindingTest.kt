package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.FeeRecipientCopy
import dev.eryalabs.nenya.channel.OrderBinding
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T90: an order records the fee recipient its `type=1` named (`Order.feeRecipient`), and
 * `OrderBinding.check` compares it. `OrderTerms` carries no recipient, so before T90 a second `type=1`
 * the buyer wrote under the same id and terms naming another recipient passed the binding, was handed
 * out as §8.7's copy, and made that key `FEE_RECIPIENT` in `partyOf`.
 *
 * The other recipient is `EnvelopeFixtures`' stranger stream; every rumor is built by the fixtures, and
 * the honest recipient is the key the fixture wrote into the fee term, never a call to the code under test.
 */
class JsFeeRecipientBindingTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val orders = OrderFixtures.orders()
    private val buyer = SettlementFixtures.buyer(index)
    private val provider = SettlementFixtures.provider(index)
    private val feeRecipient = SettlementFixtures.feeRecipient(index)
    private val stranger = EnvelopeFixtures.strangerKey()
    private val proposalRumor = JsRumor(ProposalFixtures.bound(chain.proposalTags, index))
    private val machine = JsOrderMachine(
        JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)),
    )

    /** The buyer's own second `type=1`: same id, same price and rate, the stranger as fee recipient. */
    private val redirectedRumor = JsRumor(
        ProposalFixtures.bound(
            chain.proposalTags.map {
                if (it[0] == ChannelVocabulary.FEE) listOf(it[0], it[1], stranger) else it
            },
            index,
        ),
    )
    private val redirected = OrderProposal.decode(redirectedRumor.asBoundRumor("the redirected proposal"))

    private fun assertBindingRefused(what: String, call: () -> Unit) {
        val refused = assertFailsWith<ChannelException>(what) { call() }
        assertEquals(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL, refused.reason, what)
        assertNull(refused.tag, "$what: the rule names no tag")
        for (key in listOf(buyer, provider, feeRecipient, stranger)) {
            assertFalse(key in refused.message.orEmpty(), "$what: the refusal names no key")
        }
    }

    /** `Order.feeRecipient` is the proposal's, carried by every step, never printed; no other door sets one. */
    @Test
    @JsName("the_order_records_the_proposal_s_fee_recipient_and_never_prints_it")
    fun `the order records the proposal's fee recipient, and never prints it`() {
        assertEquals(feeRecipient, chain.proposal.feeRecipient, "the fixture's fee term names the fee recipient")
        for ((state, order) in orders) {
            if (state == OrderState.UNKNOWN) {
                assertNull(order.feeRecipient, "the unknown sink read no proposal")
                continue
            }
            assertEquals(feeRecipient, order.feeRecipient, "an order in $state carries it")
            assertFalse(feeRecipient in order.toString(), "an order in $state prints no fee recipient")
        }
        val opened = openOrderFromProposal(machine, proposalRumor).order ?: fail("the fixture proposal opens an order")
        assertEquals(feeRecipient, opened.order.feeRecipient, "the crossed door records the same key")
        assertFalse(feeRecipient in opened.toString(), "nor does the crossed order print it")

        assertNull(OrderEvent.Proposal(chain.proposal.order, chain.terms).feeRecipient, "the published constructor sets none")
        assertNull(OrderMachine().open(OrderEvent.Proposal(chain.proposal.order, chain.terms)).feeRecipient)
        val noFee = OrderFixtures.chain(OrderFixtures.absentFeeTerms)
        assertNull(noFee.proposal.asOrderEvent().feeRecipient, "a proposal with no fee term names none")
        OrderBinding.check(
            OrderFixtures.orders(OrderFixtures.absentFeeTerms).getValue(OrderState.COMMITTED),
            noFee.proposal,
            provider,
        )
    }

    /** The task's first control: `check` refuses the buyer's second `type=1` that names another fee recipient. */
    @Test
    @JsName("the_buyer_s_second_proposal_naming_another_fee_recipient_is_refused_by_check")
    fun `the buyer's second proposal naming another fee recipient is refused by check`() {
        assertEquals(buyer, redirected.buyer, "the same author")
        assertEquals(chain.proposal.order, redirected.order, "the same id")
        assertEquals(chain.terms, redirected.terms, "the same terms: OrderTerms carries no recipient")
        assertEquals(stranger, redirected.feeRecipient, "another fee recipient")
        assertNotEquals(chain.proposal.feeRecipient, redirected.feeRecipient)

        for ((state, order) in orders) {
            if (state == OrderState.UNKNOWN) continue
            OrderBinding.check(order, chain.proposal, provider)
            assertBindingRefused("the redirected proposal against an order in $state") {
                OrderBinding.check(order, redirected, provider)
            }
        }
    }

    /** The second: no §8.7 copy is made from it, so the stranger is sent nothing. */
    @Test
    @JsName("no_fee_recipient_copy_is_made_from_the_redirected_proposal")
    fun `no fee-recipient copy is made from the redirected proposal`() {
        val committed = orders.getValue(OrderState.COMMITTED)
        assertEquals(feeRecipient, FeeRecipientCopy.of(committed, chain.proposal, provider).recipient)
        assertBindingRefused("§8.7's copy of the redirected proposal") {
            FeeRecipientCopy.of(committed, redirected, provider)
        }

        val crossed = proposalForFeeRecipient(JsOrder(committed), redirectedRumor, provider)
        assertFalse(crossed.ok, "the crossed door refuses it too")
        assertEquals(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL.name, crossed.reason)
        assertNull(crossed.recipientPubkey, "no key to send to")
        assertNull(crossed.event, "and no copy")
    }

    /** The third: `stepOrderWithStatusUpdate` against it steps nothing, where mapped against it the stranger is `FEE_RECIPIENT`. */
    @Test
    @JsName("stepOrderWithStatusUpdate_against_the_redirected_proposal_steps_nothing")
    fun `stepOrderWithStatusUpdate against the redirected proposal steps nothing`() {
        val proposed = openOrderFromProposal(machine, proposalRumor).order ?: fail("the fixture proposal opens an order")
        for ((who, author) in listOf("the buyer" to buyer, "the stranger" to stranger)) {
            val cancellation = JsRumor(
                SettlementFixtures.boundSealedBy(author, ProposalFixtures.acceptanceTags(chain.proposalTags, index, status = "cancelled")),
            )
            val answer = stepOrderWithStatusUpdate(machine, proposed, redirectedRumor, cancellation, provider)
            assertFalse(answer.ok, "$who's cancellation against the redirected proposal")
            assertEquals("refused", answer.kind)
            assertEquals(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL.name, answer.reason)
            assertNull(answer.order, "no event was offered")
        }
        assertEquals(Party.FEE_RECIPIENT, redirected.partyOf(stranger, provider), "the hole the comparison closes")

        val honest = stepOrderWithStatusUpdate(
            machine, proposed, proposalRumor,
            JsRumor(SettlementFixtures.boundSealedBy(provider, ProposalFixtures.acceptanceTags(chain.proposalTags, index))),
            provider,
        )
        assertTrue(honest.ok, "the honest acceptance still steps: ${honest.reason}")
        assertEquals("ACCEPTED", honest.order?.state)
    }
}
