package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * T82: the four checks the `stepOrderWith…` doors make, made by a Kotlin call — `OrderBinding.check`
 * and `OrderBinding.sender` — with no JavaScript layer in between.
 *
 * Every order comes from `OrderFixtures`' honest chain and every key from the fixtures; the stranger
 * is `EnvelopeFixtures`' stranger stream, which holds none of the order's three roles.
 */
class OrderBindingTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val orders = OrderFixtures.orders()
    private val buyer = SettlementFixtures.buyer(index)
    private val provider = SettlementFixtures.provider(index)
    private val feeRecipient = SettlementFixtures.feeRecipient(index)
    private val stranger = EnvelopeFixtures.strangerKey()

    private fun order(state: OrderState) = orders.getValue(state)

    private fun assertRefused(expected: ChannelRejection, tag: String?, what: String, call: () -> Unit) {
        val refused = assertFailsWith<ChannelException>(what) { call() }
        assertEquals(expected, refused.reason, what)
        assertEquals(tag, refused.tag, what)
        assertFalse(stranger in refused.message.orEmpty(), "$what: the refusal names no key")
    }

    /** Over the honest chain, `check` passes at `proposed`, `accepted` and `paid`, with each message's own id. */
    @Test
    @JsName("over_the_honest_chain_check_passes")
    fun `over the honest chain, check passes`() {
        val commitment = ReleaseFixtures.commitment()
        OrderBinding.check(order(OrderState.PROPOSED), chain.proposal, provider, chain.update.order)
        OrderBinding.check(order(OrderState.ACCEPTED), chain.proposal, provider, chain.update.order)
        OrderBinding.check(order(OrderState.ACCEPTED), chain.proposal, provider, commitment.order)
        OrderBinding.check(order(OrderState.PAID), chain.proposal, provider)
        OrderBinding.check(order(OrderState.PAID), chain.proposal, provider.uppercase())
        assertEquals(provider, order(OrderState.PAID).provider, "the paid order recorded the honest provider")
    }

    /** Over the honest chain, `sender` maps the buyer, the provider and the fee recipient to their parties. */
    @Test
    @JsName("over_the_honest_chain_sender_maps_all_three_parties")
    fun `over the honest chain, sender maps all three parties`() {
        assertEquals(Party.BUYER, OrderBinding.sender(chain.proposal, buyer, provider, ChannelVocabulary.STATUS))
        assertEquals(Party.PROVIDER, OrderBinding.sender(chain.proposal, provider, provider, null))
        assertEquals(Party.FEE_RECIPIENT, OrderBinding.sender(chain.proposal, feeRecipient, provider, null))
        assertEquals(Party.PROVIDER, OrderBinding.sender(chain.proposal, chain.update.sender, provider, null), "the acceptance's sealer")
    }

    /** Another order's id, on the message or on the proposal, is `DIFFERENT_ORDER` naming the `order` tag. */
    @Test
    @JsName("another_order_s_id_is_DIFFERENT_ORDER")
    fun `another order's id is DIFFERENT_ORDER`() {
        assertNotEquals(OrderFixtures.ORDER_ID, OrderFixtures.OTHER_ORDER_ID)
        val accepted = order(OrderState.ACCEPTED)
        assertRefused(ChannelRejection.DIFFERENT_ORDER, ChannelVocabulary.ORDER, "another order's message") {
            OrderBinding.check(accepted, chain.proposal, provider, chain.update.order, OrderFixtures.OTHER_ORDER_ID)
        }
        val othersProposal = OrderFixtures.chain(index = OrderFixtures.OTHER_ORDER_INDEX).proposal
        assertRefused(ChannelRejection.DIFFERENT_ORDER, ChannelVocabulary.ORDER, "another order's proposal") {
            OrderBinding.check(accepted, othersProposal, provider)
        }
    }

    /** The stranger's `type=1` under the order's own id and terms is `NOT_THIS_ORDERS_PROPOSAL`. */
    @Test
    @JsName("the_stranger_as_proposal_author_is_NOT_THIS_ORDERS_PROPOSAL")
    fun `the stranger as proposal author is NOT_THIS_ORDERS_PROPOSAL`() {
        assertFalse(stranger in listOf(buyer, provider, feeRecipient), "the stranger holds no role")
        val forged = OrderProposal.decode(SettlementFixtures.boundSealedBy(stranger, chain.proposalTags))
        assertEquals(chain.proposal.order, forged.order, "the forgery names the order's own id")
        assertEquals(chain.terms, forged.terms, "and its own terms")
        assertEquals(stranger, forged.buyer)

        for (state in listOf(OrderState.PROPOSED, OrderState.ACCEPTED)) {
            assertRefused(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL, null, "the stranger's proposal at $state") {
                OrderBinding.check(order(state), forged, provider, chain.update.order)
            }
        }
    }

    /** Once accepted, the stranger as `providerPubkey` is `PROVIDER_NOT_THIS_ORDERS`; at `proposed` no key is compared. */
    @Test
    @JsName("the_stranger_as_provider_once_accepted_is_PROVIDER_NOT_THIS_ORDERS")
    fun `the stranger as provider once accepted is PROVIDER_NOT_THIS_ORDERS`() {
        for (state in listOf(OrderState.ACCEPTED, OrderState.PAID)) {
            assertRefused(ChannelRejection.PROVIDER_NOT_THIS_ORDERS, null, "the stranger as provider at $state") {
                OrderBinding.check(order(state), chain.proposal, stranger)
            }
        }
        assertNull(order(OrderState.PROPOSED).provider, "no acceptance, no recorded key")
        OrderBinding.check(order(OrderState.PROPOSED), chain.proposal, stranger, chain.update.order)
    }

    /** The stranger as sender is `SENDER_NOT_A_PARTY`, naming the tag it was handed. */
    @Test
    @JsName("the_stranger_as_sender_is_SENDER_NOT_A_PARTY")
    fun `the stranger as sender is SENDER_NOT_A_PARTY`() {
        assertNull(chain.proposal.partyOf(stranger, provider), "partyOf maps the stranger to no party")
        assertRefused(ChannelRejection.SENDER_NOT_A_PARTY, ChannelVocabulary.STATUS, "the stranger's update") {
            OrderBinding.sender(chain.proposal, stranger, provider, ChannelVocabulary.STATUS)
        }
        assertRefused(ChannelRejection.SENDER_NOT_A_PARTY, null, "the stranger's commitment") {
            OrderBinding.sender(chain.proposal, stranger, provider, null)
        }
    }
}
