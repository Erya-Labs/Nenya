package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat

/**
 * Which key a rumor in an order thread arrived from, as this implementation resolved it.
 *
 * Three constants rather than two, because §8.7 makes the fee recipient a **third key**: the
 * fee `type=2` is sealed by the fee recipient and not by the provider, and §11.2's cancellation
 * row says "from either party" — which is the buyer or the provider, and not the fee recipient,
 * who is not a party to the delivery and has no standing to end the order.
 *
 * This is a *resolution*, not a claim. Establishing that a rumor really came from a given key is
 * §7.2's rule — the seal's pubkey and the rumor's pubkey MUST be equal — and it belongs to the
 * gift-wrap machinery no task in this round builds. What arrives here is the answer that layer
 * reached; nothing in this package can check it, which is why the states this vocabulary is
 * allowed to unlock are the two §11.3 invariant 5 permits from an assertion alone.
 */
public enum class Party {

    /** The buyer: proposes (§7.5), pays (§8.6), verifies the deliverable (§10.4). */
    BUYER,

    /** The provider: accepts (§7.6), commits (§10.1), invoices (§8.6), releases (§10.3). */
    PROVIDER,

    /**
     * The fee recipient named by the `["fee", ...]` term (§8.1), who issues the fee `type=2`
     * (§8.6) and seals it with their own key (§8.7).
     *
     * Not a party to the order for the purposes of §11.2: a cancellation from this key is
     * refused, because §11.2 says "either party" and §8.5 explains what the fee recipient is —
     * somebody with no refund obligation and no part in the delivery.
     */
    FEE_RECIPIENT,
}

/**
 * The terms of one order, reduced to what §11's transition function actually reads: §8.3's
 * arithmetic and §7.5's two deadlines.
 *
 * ### Typed parameters, not a parse
 *
 * As with `DeliverableCommitment` in the delivery package, there is no tag codec in this library
 * yet, so the caller supplies what it read out of `["amount_msat", ...]`, `["fee", ...]`,
 * `["expiration", ...]` and `["deliver_by", ...]`. Building that codec here is out of scope. The
 * `item` coordinate and the counterparty pubkeys are deliberately absent: §11.2 reads neither,
 * and a coordinate held here would be a correlation handle (§7.4, §12) with no reader.
 *
 * ### Equality is §7.6's "byte-identical"
 *
 * §7.6 says an acceptance whose terms differ from the proposal's is a **counter-proposal**, not
 * an acceptance, and the correct response is a new `type=1` with a new order id. So this type has
 * value equality over all three fields, and [OrderMachine] compares the terms a `type=3`
 * `status=accepted` asserts against the terms the order was opened with. Narrowed honestly: this
 * compares *parsed values*, where §7.6 says byte-identical. `["amount_msat", "090000000"]` and
 * `["amount_msat", "90000000"]` are not byte-identical and would compare equal here — a gap this
 * type cannot close, because §4.3's rule that a canonical decimal integer is required makes the
 * leading-zero form refusable **on parse**, and the parse is the tag codec's.
 *
 * ### Both deadlines are nullable, and §7.5's ordering rule is enforced
 *
 * §7.5 names `expiration` and `deliver_by` as two distinct deadlines and requires `expiration` to
 * be strictly earlier when both are present — an order that can still be accepted after its own
 * delivery deadline has passed is incoherent. Neither is made REQUIRED here, because §7.5 does
 * not: a proposal carrying no `expiration` simply has no `proposed → expired` edge a clock can
 * fire, and §11.2's release-timeout clause is written precisely for terms carrying no
 * `deliver_by`. Both absences are handled by [OrderMachine] with a named refusal rather than by
 * inventing a deadline here.
 */
public class OrderTerms(

    /** §8.3's three amounts, and §9.2's answer to which payees a receipt is required from. */
    public val split: FeeSplit,

    /**
     * §7.5's `["expiration", ...]` — the deadline for **acceptance**, in unix seconds, or `null`
     * if none.
     */
    public val expiration: Long? = null,

    /**
     * §7.5's `["deliver_by", ...]` — the deadline for **release**, in unix seconds, or `null` if
     * none.
     */
    public val deliverBy: Long? = null,
) {

    init {
        if ((expiration != null && expiration < 0L) || (deliverBy != null && deliverBy < 0L)) {
            throw OrderStateException(
                OrderStateRejection.NEGATIVE_TIMESTAMP,
                "§4.3 fixes a timestamp as a non-negative integer of unix seconds, and §7.5's " +
                    "expiration and deliver_by are both timestamps",
            )
        }
        if (expiration != null && deliverBy != null && expiration >= deliverBy) {
            throw OrderStateException(
                OrderStateRejection.DEADLINES_INVERTED,
                "§7.5 requires a proposal's expiration to fall strictly before its deliver_by, and " +
                    "requires an implementation to reject a proposal that violates it: an order " +
                    "that can still be accepted after its own delivery deadline has passed puts the " +
                    "provider in a state where acceptance and expiry are simultaneously correct",
            )
        }
    }

    /**
     * §7.6's identity check: are [other]'s terms the ones this order was proposed with?
     *
     * **`expiration` is deliberately not compared, and that is §7.6's own list.** §7.6 fixes the
     * terms an acceptance must carry byte-identically as `item`, `amount_msat`, `fee` and
     * `deliver_by`. `expiration` is not among them: §7.5 makes it the deadline for *acceptance*,
     * which the buyer evaluates against its own injected clock (§4.6) and which a conformant
     * provider has no reason to echo. Comparing it would refuse every conformant acceptance and
     * deadlock every order at `proposed` until it expired — a failure that fails *closed* and is
     * therefore exactly the kind that ships.
     *
     * `item` is not compared either, for the reason this type gives above: no coordinate is held
     * here, because §11.2 reads none and a coordinate with no reader is a correlation handle. A
     * caller that holds the coordinate MUST compare it itself; that obligation is stated here
     * rather than silently absorbed.
     *
     * **And the omission that routes money: the `fee` term is compared by its basis points and
     * not by its recipient.** §8.1's `["fee", "<bps>", "<recipient-pubkey-hex>"]` carries both,
     * and `FeeTerm` deliberately holds only the first — validating a 64-character x-only key,
     * and §8.1's reachability check, belong to the tag codec. So an acceptance echoing the same
     * basis points with a **different** fee recipient compares equal here. §8.4 requires the fee
     * pair to be byte-identical at all four points it appears, and §8.7 binds the fee invoice to
     * the named recipient's key; neither is this method's, and a caller that advances an order on
     * this comparison alone has performed neither. Of the three narrowings on this method it is the
     * only one on the path a payment takes.
     *
     * Where they **are** performed, since the narrowing above is no longer the whole story:
     * `dev.eryalabs.nenya.settlement.FeeTermAgreement` compares the raw `(bps, recipient)` tags
     * across every point §8.4 names, and `Settlement.checkFeePaymentRequest` and
     * `Settlement.verifyFeeReceipt` refuse a divergence as the terms mismatch §8.4 requires. This
     * method is unchanged and stays deliberately narrower: it answers §7.6's question about parsed
     * terms, and §8.4's question is about bytes.
     *
     * [equals] remains full value equality over all three fields — it is the whole terms object,
     * and two orders differing only in acceptance deadline are two different orders.
     */
    public fun namesTheSameDealAs(other: OrderTerms): Boolean =
        other.split == split && other.deliverBy == deliverBy

    override fun equals(other: Any?): Boolean =
        other is OrderTerms &&
            other.split == split &&
            other.expiration == expiration &&
            other.deliverBy == deliverBy

    override fun hashCode(): Int {
        var hash = split.hashCode()
        hash = hash * 31 + (expiration?.hashCode() ?: 0)
        hash = hash * 31 + (deliverBy?.hashCode() ?: 0)
        return hash
    }

    /**
     * Carries no amount and no deadline.
     *
     * §12 item 1 keeps an order's price out of anything public and §12's whole point is that the
     * terms live in the private channel; a price and a delivery deadline together are enough to
     * identify an order in a log beside a listing that is public. `FeeSplit.toString` prints its
     * amounts because §8.3 arithmetic has to be debuggable in isolation; a whole order's terms
     * are a different object with a different audience.
     */
    override fun toString(): String = "OrderTerms(redacted)"

    public companion object {

        /**
         * Terms for an order at [price] under [fee], with §7.5's two optional deadlines.
         *
         * @param fee defaults to [FeeTerm.Absent], which is §8.1's read rule for a `type=1`
         *   carrying no `fee` tag: zero fee, **not** incomplete terms, and every fee invoice for
         *   the order refused thereafter. A proposal with no fee tag opens an order here exactly
         *   as one carrying `["fee", "0"]` does.
         */
        public fun of(
            price: Msat,
            fee: FeeTerm = FeeTerm.Absent,
            expiration: Long? = null,
            deliverBy: Long? = null,
        ): OrderTerms = OrderTerms(fee.splitOn(price), expiration, deliverBy)
    }
}
