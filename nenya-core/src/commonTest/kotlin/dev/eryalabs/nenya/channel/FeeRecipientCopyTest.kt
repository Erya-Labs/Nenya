package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T86: §8.7's copy of the `type=1` for the fee recipient (decision T), made by a Kotlin call —
 * `FeeRecipientCopy.of` — over `OrderFixtures`' honest chain, with no JavaScript layer in between.
 */
class FeeRecipientCopyTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val orders = OrderFixtures.orders()
    private val provider = SettlementFixtures.provider(index)
    private val stranger = EnvelopeFixtures.strangerKey()

    private fun assertRefused(expected: ChannelRejection, tag: String?, what: String, call: () -> Unit) {
        val refused = assertFailsWith<ChannelException>(what) { call() }
        assertEquals(expected, refused.reason, what)
        assertEquals(tag, refused.tag, what)
        for (key in listOf(provider, stranger, SettlementFixtures.feeRecipient(index), SettlementFixtures.buyer(index))) {
            assertFalse(key in refused.message.orEmpty(), "$what: the refusal names no key")
        }
    }

    /** The committed fee-bearing order: the fee term's recipient, and the proposal unchanged. */
    @Test
    @JsName("a_committed_fee_bearing_order_answers_its_fee_recipient_and_its_proposal_unchanged")
    fun `a committed fee-bearing order answers its fee recipient and its proposal unchanged`() {
        val committed = orders.getValue(OrderState.COMMITTED)
        assertTrue(committed.terms.split.feePayeeRequired, "the fixture order owes a fee")
        val copy = FeeRecipientCopy.of(committed, chain.proposal, provider)
        assertEquals(SettlementFixtures.feeRecipient(index), copy.recipient)
        assertEquals(chain.proposal.feeRecipient, copy.recipient, "read off the proposal's own fee term")
        assertEquals(
            chain.proposal.encode().canonicalSerialisation(),
            copy.encode().canonicalSerialisation(),
            "the type=1, byte for byte",
        )
        assertEquals("FeeRecipientCopy(redacted)", copy.toString())
    }

    /** Every other state §11.1 names is `ORDER_NOT_COMMITTED`; `unknown`, which records no buyer, is refused by the binding first. */
    @Test
    @JsName("every_state_but_committed_builds_nothing")
    fun `every state but committed builds nothing`() {
        for ((state, order) in orders) {
            when (state) {
                OrderState.COMMITTED -> continue
                OrderState.UNKNOWN -> assertRefused(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL, null, "an order in $state") {
                    FeeRecipientCopy.of(order, chain.proposal, provider)
                }
                else -> assertRefused(ChannelRejection.ORDER_NOT_COMMITTED, null, "an order in $state") {
                    FeeRecipientCopy.of(order, chain.proposal, provider)
                }
            }
        }
    }

    /** `OrderBinding.check`'s refusals come first: another order's proposal, a stranger's, a stranger as provider. */
    @Test
    @JsName("a_proposal_the_binding_check_refuses_builds_nothing")
    fun `a proposal the binding check refuses builds nothing`() {
        val committed = orders.getValue(OrderState.COMMITTED)
        val othersProposal = OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX).proposal
        assertRefused(ChannelRejection.DIFFERENT_ORDER, ChannelVocabulary.ORDER, "another order's proposal") {
            FeeRecipientCopy.of(committed, othersProposal, provider)
        }
        val forged = OrderProposal.decode(SettlementFixtures.boundSealedBy(stranger, chain.proposalTags))
        assertRefused(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL, null, "the stranger's proposal") {
            FeeRecipientCopy.of(committed, forged, provider)
        }
        assertRefused(ChannelRejection.PROVIDER_NOT_THIS_ORDERS, null, "the stranger as provider") {
            FeeRecipientCopy.of(committed, chain.proposal, stranger)
        }
    }

    /** No fee term, `["fee", "0"]` and §8.3's 1 bps on 3 000 msat each owe no fee: `NO_FEE_TO_REQUEST`, naming `fee`. */
    @Test
    @JsName("a_committed_order_with_no_fee_to_request_builds_nothing")
    fun `a committed order with no fee to request builds nothing`() {
        val zeroRate = OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(0), OrderFixtures.EXPIRATION, OrderFixtures.DELIVER_BY)
        for ((what, terms) in listOf(
            "no fee term" to OrderFixtures.absentFeeTerms,
            "a zero rate" to zeroRate,
            "a fee that rounds to 0" to OrderFixtures.zeroFeeTerms,
        )) {
            val committed = OrderFixtures.orders(terms).getValue(OrderState.COMMITTED)
            assertFalse(committed.terms.split.feePayeeRequired, "$what: no fee is owed")
            assertRefused(ChannelRejection.NO_FEE_TO_REQUEST, ChannelVocabulary.FEE, what) {
                FeeRecipientCopy.of(committed, OrderFixtures.chain(terms).proposal, provider)
            }
        }
    }
}
