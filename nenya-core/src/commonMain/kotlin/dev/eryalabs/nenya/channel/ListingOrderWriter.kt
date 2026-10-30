package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.WireEvent

/**
 * What [ListingOrderWriter] answers: the unsigned rumor, a proposal naming another listing, a
 * proposal sent between the wrong keys, §5.6's deadline refusal, §7.5's deadline for acceptance, or
 * the §7.4 rule that refused the message itself.
 *
 * [ForAnotherListing] and [ProposalDeadlineRefused] are reachable only from
 * [ListingOrderWriter.agreement]: `proposal` and `proposalForBid` read their `item` off the listing
 * they are handed, so they have no second coordinate to disagree with, and they write the deadline
 * for acceptance rather than answering one. [WrongParties] is reachable only from
 * [ListingOrderWriter.proposalForBid], the one writer handed a bid, and so the one that can say who
 * the buyer and the provider are.
 *
 * More cases than [RumorBuild]'s two, because the refusals are different facts with different
 * fixes: [DeadlineRefused] is about the **listing** — its `expiration` has passed, or the
 * injected clock could not say whether it had — and no change to the message would build it, while
 * [Refused] is about the message and is exactly what `RumorWriter.proposal` answers for the same
 * values. Folding the first into a `ChannelRejection` would invent a channel rule §7.4 does not state.
 *
 * A value rather than an exception, on decision **P**'s terms, as [RumorBuild] is.
 */
public sealed interface ListingOrderBuild {

    /**
     * The unsigned rumor, for the app to seal — byte for byte what `RumorWriter.proposal` builds with
     * `item` set to the listing's own coordinate, or, from [ListingOrderWriter.agreement], what
     * `RumorWriter.statusUpdate` builds with `status=accepted` and the proposal's own terms.
     *
     * It claims that **§5.6's deadline did not refuse** — and, from `agreement`, that §7.5's deadline
     * for acceptance did not either — and nothing more. It is not "this listing is
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
     * The proposal's §7.5 `item` names a listing other than the one handed in, so nothing was built and
     * the deadline was never asked.
     *
     * Checked before the deadline, because the listing's `expiration` says nothing about a proposal
     * made against some other listing: judging it would answer §5.6 about the wrong subject — the
     * reason `OrderProposal.accepts` refuses `DIFFERENT_ORDER` before it reads anything else. It
     * carries no value, neither the coordinate the proposal named nor the listing's, because §12
     * item 11 keeps a listing coordinate out of a diagnostic.
     */
    public object ForAnotherListing : ListingOrderBuild {

        override fun toString(): String = "ListingOrderBuild.ForAnotherListing"
    }

    /**
     * §7.4's proposal is the buyer's, sent to the provider, and this one was not: [RumorEnvelope]'s
     * author is not the buyer the listing and the bid name, or its `p` counterparties are not exactly
     * the provider they name, or the two are one key. Nothing was built and the clock was never read.
     *
     * Decision **H**'s resolution of the two parties: on a request the listing's author is the buyer
     * and the bidder the provider; on an offer, where §6 makes the bid a counter-offer, the bidder is
     * the buyer and the offer's author the provider. A proposal sealed by anyone else would carry the
     * bid's terms to an order its author never asked for.
     *
     * Carries no value — neither which key was wrong nor what was expected — because §12 item 11
     * keeps a counterparty pubkey out of a diagnostic.
     */
    public object WrongParties : ListingOrderBuild {

        override fun toString(): String = "ListingOrderBuild.WrongParties"
    }

    /**
     * §5.6: the listing's deadline refused, so no message was built at all — or, from
     * [ListingOrderWriter.proposalForBid], §4.6's deadline on the bid did, or the bid is scoped to
     * another listing.
     *
     * From [ListingOrderWriter.proposal] and [ListingOrderWriter.agreement], [reason] is always one of
     * `DeadlineRefusal`'s two **listing** constants — `LISTING_EXPIRED` or `LISTING_CANNOT_SAY` —
     * because they hand the gate no bid. `proposalForBid` hands it one, so any of the five is possible
     * there, and `reason.side` says which message refused.
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

    /**
     * §7.5: the proposal's own `expiration`, "the deadline for acceptance", refused, so no agreement
     * was built at all.
     *
     * Reachable only from [ListingOrderWriter.agreement], and only after the listing's deadline
     * permitted: a proposal is its buyer's deadline, a listing its author's, and §11.2 moves an order
     * `proposed → expired` on the first of them. Separate from [DeadlineRefused] because it is about a
     * different message — no change to the listing would build it.
     */
    public class ProposalDeadlineRefused internal constructor(

        /** Whether the deadline has passed or the injected clock could not say. */
        public val reason: ProposalDeadlineRefusal,
    ) : ListingOrderBuild {

        override fun equals(other: Any?): Boolean = other is ProposalDeadlineRefused && other.reason == reason

        override fun hashCode(): Int = reason.hashCode()

        /** Names the rule, and no order id, deadline or key (§12 item 11). */
        override fun toString(): String = "ListingOrderBuild.ProposalDeadlineRefused($reason)"
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
 * Why [ListingOrderWriter.agreement] refused on the proposal's own `expiration` (§7.5).
 *
 * Two constants for the reason `DeadlineRefusal` has two per side: one is a deadline this library
 * evaluated and found passed, the other one it could not evaluate (§17). Both refuse.
 */
public enum class ProposalDeadlineRefusal {

    /** The injected clock has reached the proposal's `expiration`, on `OrderMachine`'s `>=` boundary. */
    PROPOSAL_EXPIRED,

    /** The proposal carries an `expiration` and the injected clock was silent or negative. */
    PROPOSAL_CANNOT_SAY,
}

/**
 * §5.6's "MUST NOT … propose an order, or advance an order against an expired listing", made the way
 * a proposal against a listing, and the provider's agreement to one, are written.
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
 * [agreement] is the provider's side of the same MUST, and only its acceptance arm: §7.6's
 * acceptance is what moves an order `proposed → accepted` (§11.2), so it too is refused once the
 * listing has expired, and once the proposal's own `expiration` — §7.5's deadline for acceptance —
 * has. The other arms of "advance" would need the order machine to hold the listing, and nothing
 * here claims them.
 *
 * [proposalForBid] is [proposal] for the buyer who chose a public bid (§6 → §7.5): the price, `fee`,
 * fee recipient and `deliver_by` are copied off the decoded bid rather than typed a second time, so
 * the terms the bidder signed are the terms proposed.
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

    /**
     * §7.5's `kind:16` `type=1` order proposal made from [bid], the public bid on [listing] the buyer
     * chose, with the bid's terms carried across and never retyped.
     *
     * ### In the order the checks are made
     *
     * 1. [bid]'s coordinate against [listing]'s. A bid on another listing answers
     *    [ListingOrderBuild.DeadlineRefused] with `BID_FOR_ANOTHER_LISTING` — the answer
     *    `DeadlineGate.check` gives for the same pair — and never reads the clock: the parties below
     *    are read off the listing, and would say nothing about a bid on some other one.
     * 2. The parties, per decision **H** and §7.4: on a request (`kind:30404`) the buyer is the
     *    listing's author and the provider the bidder; on an offer, where §6 makes the bid a
     *    counter-offer, the buyer is the bidder and the provider the offer's author. Unless
     *    [envelope]'s author is that buyer, its counterparties are exactly that provider, and the two
     *    are different keys, the answer is [ListingOrderBuild.WrongParties] and the clock is not read.
     * 3. `DeadlineGate.check(listing, bid, clock)`: anything but `Permitted` answers
     *    [ListingOrderBuild.DeadlineRefused] — the listing's §5.6 deadline first, then the bid's §4.6
     *    one — and builds nothing. The clock is read at most once.
     * 4. [acceptBy], §7.5's deadline for acceptance: a negative one is refused as
     *    `ChannelRejection.MALFORMED_TAG` and one at or after the bid's `deliver_by` as
     *    `ChannelRejection.DEADLINES_INVERTED` — §7.5's "strictly earlier" — both naming `expiration`
     *    and both as [ListingOrderBuild.Refused], never thrown.
     * 5. `RumorWriter.proposal` with `item` = the listing's coordinate, and the bid's price, `fee`,
     *    fee recipient and `deliver_by` (§7.5: "`deliver_by` is the bid's own … carried across
     *    byte-identically from §6"), `expiration` = [acceptBy], and no `amount` tag.
     *
     * ### What it does not claim
     *
     * The bid's `expiration` is judged and never copied: it is the bidder's deadline for being taken
     * up, not the buyer's deadline for acceptance, and §6's own example bid expires after its
     * `deliver_by`, which §7.5 would refuse as a proposal's. It does not check the bid's `p`/`P`
     * against anything beyond what `Bid.decode` already checked, nor that the listing is still
     * `active` (§5.1's `status`). And it advances nothing: a bid binds nobody (§6), and nothing in
     * `dev.eryalabs.nenya.order` calls this writer.
     *
     * @param listing the listing [bid] was made on; its §4.2 coordinate becomes §7.5's `item`.
     * @param bid the §6 public bid the buyer chose, as `Bid.decode` answered it.
     * @param clock §3's clock seam, read at most once, and only once the parties are right.
     * @param envelope §7.4's envelope: the buyer's key, its `created_at`, and the provider as `p`.
     * @param order §7.4's order id, drawn by the buyer.
     * @param acceptBy §7.5's `expiration`, the deadline for acceptance, in unix seconds, or `null` for none.
     */
    public fun proposalForBid(
        listing: Listing,
        bid: Bid,
        clock: NenyaClock,
        envelope: RumorEnvelope,
        order: OrderId,
        acceptBy: Long? = null,
    ): ListingOrderBuild {
        if (bid.listing != listing.coordinate) {
            return ListingOrderBuild.DeadlineRefused(DeadlineRefusal.BID_FOR_ANOTHER_LISTING)
        }
        // Both keys normalised as §4.3 normalises a key this client compares: the coordinate's already
        // is, and a bidder's is the event's own spelling.
        val author = listing.coordinate.pubkey
        val bidder = bid.bidderPubkey.lowercase()
        val buyer = if (listing.coordinate.kind == NenyaKind.REQUEST) author else bidder
        val provider = if (listing.coordinate.kind == NenyaKind.REQUEST) bidder else author
        if (
            buyer == provider ||
            envelope.authorPubkey.lowercase() != buyer ||
            envelope.counterparties.map { it.pubkey } != listOf(provider)
        ) {
            return ListingOrderBuild.WrongParties
        }
        when (val gate = DeadlineGate.check(listing, bid, clock)) {
            DeadlineGate.Permitted -> Unit
            is DeadlineGate.Refused -> return ListingOrderBuild.DeadlineRefused(gate.reason)
        }
        val deliverBy = bid.deliverBy
        if (acceptBy != null && acceptBy < 0L) {
            return ListingOrderBuild.Refused(
                RumorBuild.Refused(
                    ChannelRejection.MALFORMED_TAG,
                    ChannelVocabulary.EXPIRATION,
                    "§4.3 fixes a timestamp as a non-negative integer of unix seconds, and §7.5's " +
                        "`${ChannelVocabulary.EXPIRATION}` is one",
                ),
            )
        }
        if (acceptBy != null && deliverBy != null && acceptBy >= deliverBy) {
            return ListingOrderBuild.Refused(
                RumorBuild.Refused(
                    ChannelRejection.DEADLINES_INVERTED,
                    ChannelVocabulary.EXPIRATION,
                    "§7.5 requires a proposal's `${ChannelVocabulary.EXPIRATION}` to fall **strictly** " +
                        "before its `${ChannelVocabulary.DELIVER_BY}`, and the one asked for here is at " +
                        "or after the `${ChannelVocabulary.DELIVER_BY}` the bid carries",
                ),
            )
        }
        val terms = OrderTerms(bid.fee.splitOn(bid.price), acceptBy, deliverBy)
        return when (
            val built = RumorWriter.proposal(envelope, order, ItemRef(listing.coordinate), terms, bid.feeRecipient)
        ) {
            is RumorBuild.Built -> ListingOrderBuild.Built(built.event)
            is RumorBuild.Refused -> ListingOrderBuild.Refused(built)
        }
    }

    /**
     * §7.6's acceptance of [proposal] — a `kind:16` `type=3` carrying `status=accepted` — refused when
     * the proposal names another listing, when §5.6's deadline refuses, or when §7.5's deadline for
     * acceptance does.
     *
     * Not named `accept…`: `OrderProposal.accepts` is §7.6's one answer to "were these terms
     * accepted", and `ChannelStructureTest` pins that name to it alone. This writes the message that
     * question is asked about; it answers nothing.
     *
     * ### In the order the checks are made
     *
     * 1. [proposal]'s `item` against [listing]'s coordinate. A different listing answers
     *    [ListingOrderBuild.ForAnotherListing], builds nothing and never reads the clock.
     * 2. `DeadlineGate.check(listing, null, clock)`, exactly as [proposal] makes it: anything but
     *    `Permitted` answers [ListingOrderBuild.DeadlineRefused] and builds nothing.
     * 3. §7.5's deadline for acceptance: where [proposal]'s terms carry an `expiration`, a silent or
     *    negative clock answers [ListingOrderBuild.ProposalDeadlineRefused] with `PROPOSAL_CANNOT_SAY`,
     *    and a reading at or past it with `PROPOSAL_EXPIRED` — the `>=` boundary on which
     *    `OrderMachine` expires the order and refuses its acceptance — and builds nothing. Terms with
     *    no `expiration` add no clock read.
     * 4. `RumorWriter.statusUpdate` with `OrderState.ACCEPTED` and the proposal's own `order`, `item`,
     *    `terms` and `feeRecipient`. None of the four is a parameter here, so §7.6's repeated terms
     *    come off the decoded proposal rather than being typed a second time.
     *
     * Steps 2 and 3 judge one reading: the clock is read at most once, so the two deadlines are never
     * answered for two different instants.
     *
     * ### What it does not claim
     *
     * The terms are repeated **as this library spells them**. A proposal this library built — through
     * [proposal] or `RumorWriter.proposal` — carries exactly that spelling, so `OrderProposal.accepts`
     * answers `Accepted` for the agreement. A stranger's proposal that decodes but is spelled some
     * other way (an uppercase pubkey inside the coordinate, say) is answered `CounterProposal`
     * instead, because §7.6 compares bytes and this writer re-spells what it decoded rather than
     * copying the stranger's bytes. That is the closed direction.
     *
     * Nor does it check who [envelope]'s author is. The provider is the listing's author on an offer
     * and the chosen bidder on a request, so the listing alone cannot say; `OrderProposal.accepts`
     * refuses an acceptance sealed by anyone but the provider the buyer resolved. And it advances
     * nothing: nothing in `dev.eryalabs.nenya.order` calls it.
     *
     * @param listing the listing [proposal] was made against.
     * @param clock §3's clock seam, read at most once and only after the `item` comparison.
     * @param envelope §7.4's envelope: the provider's key, its `created_at`, and the buyer as `p`.
     * @param proposal the §7.5 proposal being agreed to, as `OrderProposal.decode` answered it.
     */
    public fun agreement(
        listing: Listing,
        clock: NenyaClock,
        envelope: RumorEnvelope,
        proposal: OrderProposal,
    ): ListingOrderBuild {
        if (proposal.item.coordinate != listing.coordinate) return ListingOrderBuild.ForAnotherListing
        val once = OneReading(clock)
        when (val gate = DeadlineGate.check(listing, null, once)) {
            DeadlineGate.Permitted -> Unit
            is DeadlineGate.Refused -> return ListingOrderBuild.DeadlineRefused(gate.reason)
        }
        val deadline = proposal.terms.expiration
        if (deadline != null) {
            val refusal = when (val reading = once.now()) {
                is SeamAnswer.Unavailable -> ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY
                is SeamAnswer.Provided ->
                    if (reading.value < 0L) ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY
                    else if (reading.value >= deadline) ProposalDeadlineRefusal.PROPOSAL_EXPIRED
                    else null
            }
            if (refusal != null) return ListingOrderBuild.ProposalDeadlineRefused(refusal)
        }
        return when (
            val built = RumorWriter.statusUpdate(
                envelope,
                proposal.order,
                OrderState.ACCEPTED,
                proposal.item,
                proposal.terms,
                proposal.feeRecipient,
            )
        ) {
            is RumorBuild.Built -> ListingOrderBuild.Built(built.event)
            is RumorBuild.Refused -> ListingOrderBuild.Refused(built)
        }
    }
}

/** [clock], read on first use and never again, so both of `agreement`'s deadlines are judged at one instant. */
private class OneReading(private val clock: NenyaClock) : NenyaClock {

    private var reading: SeamAnswer<Long>? = null

    override fun now(): SeamAnswer<Long> = reading ?: clock.now().also { reading = it }

    override fun toString(): String = "OneReading(redacted)"
}
