package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.wire.WireEvent

/**
 * What [ListingOrderWriter] answers: the unsigned rumor, §5.6's deadline refusal, or the §7.4 rule
 * that refused the message itself.
 *
 * Three cases rather than [RumorBuild]'s two, because the two refusals are different facts with
 * different fixes: [DeadlineRefused] is about the **listing** — its `expiration` has passed, or the
 * injected clock could not say whether it had — and no change to the message would build it, while
 * [Refused] is about the message and is exactly what `RumorWriter.proposal` answers for the same
 * values. Folding the first into a `ChannelRejection` would invent a channel rule §7.4 does not state.
 *
 * A value rather than an exception, on decision **P**'s terms, as [RumorBuild] is.
 */
public sealed interface ListingOrderBuild {

    /**
     * The unsigned rumor, for the app to seal — byte for byte what `RumorWriter.proposal` builds with
     * `item` set to the listing's own coordinate.
     *
     * It claims that **§5.6's deadline did not refuse**, and nothing more. It is not "this listing is
     * open for business": a listing carrying `["status", "sold"]`, or parked as `kind:30403`, and no
     * `expiration` builds here, because that is §5.1's and `Listing.status`'s question rather than
     * §5.6's deadline — the same limit `DeadlineGate.Permitted` records. And it advances nothing:
     * nothing in `dev.eryalabs.nenya.order` calls this writer, so the order machine is not claimed to
     * enforce §5.6.
     */
    public class Built internal constructor(

        /** §4.1's five id-bearing fields, in `RumorWriter.PROPOSAL_TAG_ORDER`. */
        public val event: WireEvent,
    ) : ListingOrderBuild {

        /** Names the kind and a tag count. §12 item 11 keeps the order id out of a diagnostic. */
        override fun toString(): String = "ListingOrderBuild.Built(kind=${event.kind}, tags=${event.tags.size})"
    }

    /**
     * §5.6: the listing's deadline refused, so no message was built at all.
     *
     * [reason] is always one of `DeadlineRefusal`'s two **listing** constants — `LISTING_EXPIRED` or
     * `LISTING_CANNOT_SAY` — because this writer hands the gate no bid.
     */
    public class DeadlineRefused internal constructor(

        /** Which deadline rule refused. */
        public val reason: DeadlineRefusal,
    ) : ListingOrderBuild {

        override fun equals(other: Any?): Boolean = other is DeadlineRefused && other.reason == reason

        override fun hashCode(): Int = reason.hashCode()

        /** Names the rule, and no coordinate, deadline or key (§12 item 11). */
        override fun toString(): String = "ListingOrderBuild.DeadlineRefused($reason)"
    }

    /** The deadline permitted it and `RumorWriter` refused the message, carried unchanged. */
    public class Refused internal constructor(

        /** `RumorWriter`'s own refusal, reason, tag and detail as it reported them. */
        public val refusal: RumorBuild.Refused,
    ) : ListingOrderBuild {

        override fun toString(): String = "ListingOrderBuild.Refused(reason=${refusal.reason}, tag=${refusal.tag})"
    }
}

/**
 * §5.6's "MUST NOT … propose an order … against an expired listing", made the way a proposal against
 * a listing is written.
 *
 * `RumorWriter.proposal` takes a bare `ItemRef` and no clock, so it cannot know what listing the
 * coordinate names or when that listing expires; it is unchanged, and stays the entry point for a
 * caller that has no listing in hand. This one takes the **listing**, so the coordinate is read off
 * it rather than typed, and asks `DeadlineGate.check` — the same gate, against the same injected
 * clock — before anything is spelled.
 *
 * ### In the order the checks are made
 *
 * 1. `DeadlineGate.check(listing, null, clock)`. Anything but `Permitted` answers
 *    [ListingOrderBuild.DeadlineRefused] and builds nothing. A listing with no `expiration` never
 *    consults the clock, so it builds under `NenyaClock.FAIL_CLOSED`; one carrying an `expiration`
 *    under a silent or negative clock refuses as `LISTING_CANNOT_SAY`, never builds.
 * 2. `RumorWriter.proposal` with `item = ItemRef(listing.coordinate)`, every other argument passed
 *    through. Its `Built` is [ListingOrderBuild.Built] over the same event and its `Refused` is
 *    [ListingOrderBuild.Refused] carrying the same refusal.
 *
 * The deadline is judged against the clock's reading, never against [RumorEnvelope.createdAt]: §4.6
 * makes the injected clock the only source of "now", and a `created_at` is a value the caller chose.
 *
 * Pure computation over the injected clock: no randomness and no I/O.
 */
public object ListingOrderWriter {

    /**
     * §7.5's `kind:16` `type=1` order proposal against [listing], refused when §5.6's deadline does.
     *
     * @param listing the listing the order derives from; its §4.2 coordinate becomes §7.5's `item`.
     * @param clock §3's clock seam, read at most once.
     * @param envelope §7.4's envelope, as `RumorWriter.proposal` takes it.
     * @param order §7.4's order id.
     * @param terms §8.3's price and §7.5's two deadlines.
     * @param feeRecipient §8.1's third `fee` element, as `RumorWriter.proposal` takes it.
     * @param amountSat §7.5's GammaMarkets compatibility tag, as `RumorWriter.proposal` takes it.
     */
    public fun proposal(
        listing: Listing,
        clock: NenyaClock,
        envelope: RumorEnvelope,
        order: OrderId,
        terms: OrderTerms,
        feeRecipient: String? = null,
        amountSat: Long? = null,
    ): ListingOrderBuild {
        when (val gate = DeadlineGate.check(listing, null, clock)) {
            DeadlineGate.Permitted -> Unit
            is DeadlineGate.Refused -> return ListingOrderBuild.DeadlineRefused(gate.reason)
        }
        return when (
            val built = RumorWriter.proposal(envelope, order, ItemRef(listing.coordinate), terms, feeRecipient, amountSat)
        ) {
            is RumorBuild.Built -> ListingOrderBuild.Built(built.event)
            is RumorBuild.Refused -> ListingOrderBuild.Refused(built)
        }
    }
}
