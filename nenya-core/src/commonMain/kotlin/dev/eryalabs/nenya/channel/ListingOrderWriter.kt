package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.wire.WireEvent

/**
 * What [ListingOrderWriter] answers: the unsigned rumor, a proposal naming another listing, §5.6's
 * deadline refusal, §7.5's deadline for acceptance, or the §7.4 rule that refused the message itself.
 *
 * [ForAnotherListing] and [ProposalDeadlineRefused] are reachable only from
 * [ListingOrderWriter.agreement]: `proposal` reads its `item` off the listing it is handed, so it has
 * no second coordinate to disagree with, and it is writing the deadline rather than answering one.
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
