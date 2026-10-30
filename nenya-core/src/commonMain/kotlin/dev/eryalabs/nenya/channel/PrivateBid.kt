package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §6.1's `kind:16` `type=6` private bid, decoded off a §7.2-attributed rumor — the read half of
 * `RumorWriter.privateBid`.
 *
 * ### Which layer enforces which rule, said out loud
 *
 * - **§4.1 and §7.2** are T8's and T13's: the id was recomputed and the seal's pubkey equals the
 *   rumor's before an `AttributedRumor.Bound` exists at all.
 * - **§7.4's envelope** is T13's, and so is §6.1's sharpest rule: "A `type=6` carrying an `order`
 *   tag MUST be rejected", which `AttributedRumor.attribute` refuses as
 *   [ChannelRejection.FORBIDDEN_ORDER_TAG] before this decoder is reached. §7.4's version-collision
 *   rule for a `type=6` is the same call's.
 * - **§5.3's Encoding column** is T9's, applied under the `type=6` context T13 declared — which is
 *   where §6.1's "exactly one `["item", "<coordinate>", "1"]`" is enforced, and where `price` is read
 *   as the satoshi-denominated public-bid row, refusing a part-satoshi rather than rounding it.
 * - **§6.1's own rules** are here: the `type`, and "it MUST carry `price`".
 *
 * ### The terms are a bid's, not an order's
 *
 * §6.1: a private bid "carries the same terms as the public bid above", and every §6 rule about the
 * terms "applies unchanged". So the terms are held the way `Bid` holds them — §8.3's [split] and the
 * two deadlines side by side — and **not** as an `OrderTerms`, whose `init` applies §7.5's rule that
 * `expiration` fall strictly before `deliver_by`. That rule is about a *proposal*, where `expiration`
 * is the deadline for acceptance. On a bid, `expiration` is the bid's own deadline (§4.6), and §6's
 * worked example carries one later than its `deliver_by`; refusing that here would refuse privately
 * the very terms `Bid.decode` accepts publicly.
 *
 * ### A private bid advances nothing
 *
 * §6.1: "A private bid is not an acceptance either, and creates no obligation on anyone", and it
 * "MUST NOT advance any state". So there is no `asOrderEvent` here, and no member hands back an
 * `OrderEvent` or an order id — a `type=6` carries none (§7.4). What this type claims is that a
 * well-formed bid arrived from [bidder]; whether the listing [item] names is live, or whether
 * [bidder] may bid on it, is not in this message and is not claimed.
 *
 * Pure computation: no clock, no randomness, no I/O. The one time-dependent question, [activity],
 * takes the clock as a parameter (§4.6).
 */
public class PrivateBid internal constructor(
    private val rumor: AttributedRumor.Bound,

    /**
     * §6.1's `["item", "<coordinate>", "1"]` — the listing this bid names. It "carries the binding
     * that `A`/`a`, `K`/`k` and `P`/`p` carry on a public bid".
     */
    public val item: ItemRef,

    /** §8.3's arithmetic on the bid's `price` under its `fee` term — what an order on it would pay. */
    public val split: FeeSplit,
) {

    /** §7.2's attribution: the key the seal carrying this bid was signed by, lowercase. */
    public val bidder: String get() = rumor.author

    /** §5.3's `price`, in millisatoshis — what the **provider receives** (§8.2). */
    public val price: Msat get() = split.price

    /** §8.1's `fee` term. Absent when the bid carried no `fee` tag, which is a different value from a stated zero. */
    public val fee: FeeTerm get() = split.term

    /** §8.1's `fee` recipient, or `null` when the term states zero basis points or there is none. */
    public val feeRecipient: String? get() = rumor.tags.feeRecipient

    /** §5.3's `expiration` — the bid's own deadline (§4.6) — in unix seconds, or `null`. §6 makes it a SHOULD. */
    public val expiration: Long? get() = rumor.tags.expiration

    /** §5.3's `deliver_by`, in unix seconds, or `null`. A term; nothing here evaluates it. */
    public val deliverBy: Long? get() = rumor.tags.deliverBy

    /** §7.4's `["p", ...]` — the listing author the bid was sealed to — as §5.3 decoded them. */
    public val counterparties: List<PubkeyRef> get() = rumor.counterparties

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6); no deadline here is evaluated against it. */
    public val createdAt: Long get() = rumor.createdAt

    /**
     * §4.6's bid deadline, evaluated against the injected clock and nothing else — the same answers
     * and the same rules as `Bid.activity`.
     *
     * No `expiration` is [ListingActivity.ACTIVE] without the clock being consulted. A silent clock,
     * or one answering a negative reading, is [ListingActivity.CANNOT_SAY] and **never**
     * [ListingActivity.ACTIVE] (§17). The named second is itself expired. [createdAt] is not read.
     *
     * @param clock §3's clock seam. The default reports no time at all.
     */
    public fun activity(clock: NenyaClock = NenyaClock.FAIL_CLOSED): ListingActivity {
        val deadline = expiration ?: return ListingActivity.ACTIVE
        return when (val reading = clock.now()) {
            is SeamAnswer.Unavailable -> ListingActivity.CANNOT_SAY
            is SeamAnswer.Provided ->
                if (reading.value < 0L) ListingActivity.CANNOT_SAY
                else if (reading.value >= deadline) ListingActivity.EXPIRED
                else ListingActivity.ACTIVE
        }
    }

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /** Names nothing: §12 items 1, 2 and 11 cover the price, the keys and the coordinate it holds. */
    override fun toString(): String = "PrivateBid(redacted)"

    public companion object {

        /**
         * §6.1's private bid, decoded.
         *
         * Takes an [AttributedRumor.Bound] for the reason `OrderProposal.decode` does: §4.1's id
         * check and §7.2's pubkey equality come first, and the way to enforce that is to take a type
         * only those steps produce.
         *
         * @throws ChannelException [ChannelRejection.NOT_A_PRIVATE_BID] for any other message, or
         *   [ChannelRejection.MISSING_REQUIRED_TAG] naming `price` or `item`.
         */
        public fun decode(rumor: AttributedRumor.Bound): PrivateBid {
            ChannelTerms.requireType(rumor, OrderMessageKind.PRIVATE_BID, ChannelRejection.NOT_A_PRIVATE_BID)
            // §5.3's "exactly one `item` on a type=6" is T9's, under the context T13 declared, so
            // this elvis cannot fire today. Written rather than asserted away, as in
            // `OrderProposal.decode`: a `!!` would turn a later loosening into a crash.
            val item = rumor.tags.item ?: throw missing(ChannelVocabulary.ITEM)
            val price = rumor.tags.price ?: throw missing(ChannelVocabulary.PRICE)
            // §8.1's read rule, carried by T9: no `fee` tag is `FeeTerm.Absent`, which is zero fee.
            return PrivateBid(rumor, item, rumor.tags.fee.splitOn(price))
        }

        /** §6.1 marks [tag] REQUIRED on a `type=6` and the event carries no readable one. */
        private fun missing(tag: String): ChannelException = ChannelException(
            ChannelRejection.MISSING_REQUIRED_TAG,
            tag,
            "§6.1 requires `$tag` on a kind:${RumorKind.ORDER_MESSAGE.kind} " +
                "`${ChannelVocabulary.TYPE}=${OrderMessageKind.PRIVATE_BID.type}` and this event " +
                "carries no readable one",
        )
    }
}
