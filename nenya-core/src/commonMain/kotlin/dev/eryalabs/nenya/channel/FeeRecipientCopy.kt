package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §8.7's copy of an order's `type=1` for the fee recipient, and the key to send it to. T86.
 *
 * Revision `1.14` (decision T): when an order whose `fee_msat` (§8.3) is not `0` enters `committed`,
 * the buyer MUST send the fee recipient named in its fee term that order's `type=1`, unchanged, by
 * §7.1's write procedure. Without a door a page types the recipient's key itself and picks which
 * rumor to send; this reads the key off the proposal's own fee term and hands back the proposal the
 * order was opened from, byte for byte, so neither is typed.
 *
 * It builds and signs nothing. [encode] is the rumor the buyer already holds — its own `toSelf` copy
 * of the proposal — and sealing it to [recipient] is §7.1's, as for every other message. The copy
 * states terms only: §8.7 says a fee recipient MUST NOT treat it as evidence of an acceptance, a
 * commitment or a payment, and nothing here claims one.
 */
public class FeeRecipientCopy internal constructor(

    /**
     * §8.1's fee recipient — the third element of the proposal's `fee` term, 64 lowercase hex
     * characters — and the key §8.7 says the copy goes to.
     */
    public val recipient: String,
    private val proposal: OrderProposal,
) {

    /** The `type=1` the order was opened from, unchanged — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = proposal.encode()

    /** Names nothing: the proposal holds the price, the order id and three keys (§12 items 1, 2, 11). */
    override fun toString(): String = "FeeRecipientCopy(redacted)"

    public companion object {

        /**
         * The copy §8.7 asks the buyer to send, or a refusal; three checks, in this order, each
         * throwing before the next is made:
         *
         * 1. `OrderBinding.check(order, proposal, providerPubkey)` — [proposal] must be the `type=1`
         *    [order] was opened from, and [providerPubkey] the key its acceptance was checked
         *    against, with that call's own refusals (T79, T80, T82). That call compares the author and
         *    the terms and **not the fee recipient** (`OrderTerms` carries none), so a second `type=1`
         *    the buyer itself wrote under the same id and terms, naming another recipient, passes it;
         * 2. [order] in `committed` — any other state is [ChannelRejection.ORDER_NOT_COMMITTED].
         *    Decision T sends the copy when the order enters `committed`, the first state §8.5 and
         *    §11.2 accept a fee invoice from;
         * 3. a fee to request — an order whose fee term names no recipient or whose `fee_msat` is `0`
         *    (`FeeSplit.feePayeeRequired`, the clause `Payee.requiredPayees` reads) is
         *    [ChannelRejection.NO_FEE_TO_REQUEST]: §8.7 sends no copy and §8.6 allows no fee invoice.
         *
         * Reads no clock: the proposal's `expiration` is a deadline for acceptance (§7.5), and an
         * order in `committed` was accepted before it.
         *
         * @param order the buyer's order as it stands.
         * @param proposal the `type=1` [order] was opened from — the buyer's own `toSelf` copy.
         * @param providerPubkey the provider key the caller resolved **independently of every
         *   message**, as `OrderBinding.check` takes it.
         * @throws ChannelException `OrderBinding.check`'s refusals,
         *   [ChannelRejection.ORDER_NOT_COMMITTED] or [ChannelRejection.NO_FEE_TO_REQUEST].
         */
        public fun of(order: Order, proposal: OrderProposal, providerPubkey: String): FeeRecipientCopy {
            OrderBinding.check(order, proposal, providerPubkey)
            if (order.state != OrderState.COMMITTED) {
                throw ChannelException(
                    ChannelRejection.ORDER_NOT_COMMITTED,
                    null,
                    "§8.7 sends the fee recipient the order's type=1 when the order enters " +
                        "`${OrderState.COMMITTED.token}`, and this order is in another state",
                )
            }
            val recipient = proposal.feeRecipient
            if (!order.terms.split.feePayeeRequired || recipient == null) {
                throw ChannelException(
                    ChannelRejection.NO_FEE_TO_REQUEST,
                    ChannelVocabulary.FEE,
                    "§8.7 sends the copy only for an order whose fee_msat (§8.3) is not 0, and this " +
                        "order's fee term owes the fee recipient nothing; §8.6 allows no fee invoice for it",
                )
            }
            return FeeRecipientCopy(recipient, proposal)
        }
    }
}
