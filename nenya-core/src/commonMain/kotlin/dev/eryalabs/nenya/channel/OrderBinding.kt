package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.seam.OrderId

/**
 * The checks that bind a received message to one order before it is offered to `OrderMachine`. T82.
 *
 * A client holding an order, the `type=1` it was opened from and a message it just received makes
 * four checks before the message may move the order: the ids name this order, the proposal is the one
 * the order was opened from, the provider key is the one the order's acceptance was checked against,
 * and the message's attributed sender is exactly one of the proposal's parties. They are here, rather
 * than in the JavaScript layer's `stepOrderWith…` doors that first made them (T76, T77, T79, T80), so a
 * Kotlin client makes the same calls a page does — decision P's "the same call the JVM side makes".
 *
 * Neither function reads a message, a status or a clock, and neither moves an order. What passes is
 * still offered to `OrderMachine.on`, whose own sender rules decide what that party may do.
 */
public object OrderBinding {

    /**
     * The first three checks, in this order, each throwing before the next is made:
     *
     * 1. [proposal]'s `order` id and every one of [messageOrders] against [order]'s — another is
     *    [ChannelRejection.DIFFERENT_ORDER] (T76, T77). The proposal's id is always compared; a message
     *    whose own id is bound by a later call — §10.3's release, through
     *    `DeliverableReleaseMessage.asOrderEvent` — is simply not passed;
     * 2. [proposal] against the `type=1` [order] was opened from — an order recording no buyer (only
     *    `OrderProposal.asOrderEvent` records one), another author or other terms is
     *    [ChannelRejection.NOT_THIS_ORDERS_PROPOSAL] (T80). The fee recipient is not compared:
     *    `OrderTerms` carries none;
     * 3. once [order] is accepted, [providerPubkey], read and normalised as `OrderProposal.partyOf`
     *    reads it (a malformed one still [ChannelRejection.MALFORMED_PROVIDER_PUBKEY]), against the key
     *    its acceptance was checked against — another is [ChannelRejection.PROVIDER_NOT_THIS_ORDERS]
     *    (T79). At `proposed` no key is recorded, so none is compared.
     *
     * @param order the order as it stands.
     * @param proposal the `type=1` the caller holds as the one [order] was opened from.
     * @param providerPubkey the provider key the caller resolved **independently of every message**, as
     *   `OrderProposal.accepts` takes it.
     * @param messageOrders the `order` ids of the received messages to bind, besides the proposal's.
     * @throws ChannelException [ChannelRejection.DIFFERENT_ORDER],
     *   [ChannelRejection.NOT_THIS_ORDERS_PROPOSAL], [ChannelRejection.MALFORMED_PROVIDER_PUBKEY],
     *   [ChannelRejection.PROVIDER_IS_BUYER] or [ChannelRejection.PROVIDER_NOT_THIS_ORDERS].
     */
    public fun check(order: Order, proposal: OrderProposal, providerPubkey: String, vararg messageOrders: OrderId) {
        requireSameOrder(order, proposal.order, *messageOrders)
        requireThisOrdersProposal(order, proposal)
        requireThisOrdersProvider(order, proposal, providerPubkey)
    }

    /**
     * The fourth check: the party `OrderProposal.partyOf` maps [sender] to, or
     * [ChannelRejection.SENDER_NOT_A_PARTY] naming [tag] (T76, T77).
     *
     * @param proposal the `type=1` [check] bound to the order.
     * @param sender the key §7.2 attributed the received message to.
     * @param providerPubkey as [check]'s, and refused as `partyOf` refuses it.
     * @param tag the tag the refusal names — the update's `status` — or `null` for a message whose
     *   sender no single tag is about.
     * @throws ChannelException [ChannelRejection.SENDER_NOT_A_PARTY], or `partyOf`'s two refusals of
     *   [providerPubkey].
     */
    public fun sender(proposal: OrderProposal, sender: String, providerPubkey: String, tag: String?): Party =
        proposal.partyOf(sender, providerPubkey) ?: throw ChannelException(
            ChannelRejection.SENDER_NOT_A_PARTY,
            tag,
            "§11.2's sender rules are written in parties, and the key §7.2 attributed this message " +
                "to is not exactly one of the proposal's buyer, the provider and the fee recipient; " +
                "there is no party to offer it from, and none is chosen on its behalf",
        )

    /** [ChannelRejection.DIFFERENT_ORDER] unless every one of [ids] is [order]'s own. */
    private fun requireSameOrder(order: Order, vararg ids: OrderId) {
        if (ids.any { it != order.id }) {
            throw ChannelException(
                ChannelRejection.DIFFERENT_ORDER,
                ChannelVocabulary.ORDER,
                "§7.4's `${ChannelVocabulary.ORDER}` id binds every message to one order, and a " +
                    "message offered here names a different one than the order being stepped; a " +
                    "message about another order moves nothing on this one",
            )
        }
    }

    /**
     * [ChannelRejection.NOT_THIS_ORDERS_PROPOSAL] unless [proposal] is the `type=1` [order] was opened
     * from: [order] records a buyer, [proposal]'s attributed author is that buyer, and its terms equal
     * [order]'s.
     */
    private fun requireThisOrdersProposal(order: Order, proposal: OrderProposal) {
        val recorded = order.buyer
        if (recorded == null || recorded != proposal.buyer || order.terms != proposal.terms) {
            throw ChannelException(
                ChannelRejection.NOT_THIS_ORDERS_PROPOSAL,
                null,
                "every sender is mapped against the proposal handed here, and it is not the type=1 this " +
                    "order was opened from — another author, other terms, or an order opened without " +
                    "one; mapping against it would let its author act as this order's buyer",
            )
        }
    }

    /**
     * Once [order] is accepted, [ChannelRejection.PROVIDER_NOT_THIS_ORDERS] unless [providerPubkey],
     * through `OrderProposal.resolvedProvider`, is the key its acceptance was checked against.
     */
    private fun requireThisOrdersProvider(order: Order, proposal: OrderProposal, providerPubkey: String) {
        val recorded = order.provider ?: return
        if (proposal.resolvedProvider(providerPubkey) != recorded) {
            throw ChannelException(
                ChannelRejection.PROVIDER_NOT_THIS_ORDERS,
                null,
                "§7.6 checked this order's acceptance against one provider key, and the order recorded " +
                    "it; the key handed here is another, and mapping a sender against it would make " +
                    "that key the provider of an order it never accepted",
            )
        }
    }
}
