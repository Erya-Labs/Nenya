package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer

/** Which of the two deadlines [DeadlineGate.check] evaluates refused. */
public enum class DeadlineSide {

    /** The listing's own `expiration` (§5.6). */
    LISTING,

    /** The bid's own `expiration` (§4.6). */
    BID,
}

/**
 * Why [DeadlineGate.check] refused, each constant naming the [side] it is about.
 *
 * "Expired" and "cannot say" are separate constants because §17 makes them separate facts: the
 * first is a deadline this library evaluated and found passed, the second a deadline it could not
 * evaluate because the injected clock was silent or broken. Both refuse.
 */
public enum class DeadlineRefusal(public val side: DeadlineSide) {

    /** §5.6: the injected clock has reached the listing's `expiration`. */
    LISTING_EXPIRED(DeadlineSide.LISTING),

    /** The listing carries an `expiration` and the injected clock could not say whether it passed. */
    LISTING_CANNOT_SAY(DeadlineSide.LISTING),

    /** §4.6: the injected clock has reached the bid's `expiration`. */
    BID_EXPIRED(DeadlineSide.BID),

    /** The bid carries an `expiration` and the injected clock could not say whether it passed. */
    BID_CANNOT_SAY(DeadlineSide.BID),

    /**
     * The bid is scoped to a different coordinate from the listing handed in beside it.
     *
     * Not a deadline, and refused for that reason: §5.6's rule is about the listing the bid is
     * **against**, and a gate permitting a bid on one listing because a different listing is live
     * would be answering a question nobody asked.
     */
    BID_FOR_ANOTHER_LISTING(DeadlineSide.BID),
}

/**
 * §5.6's refusal to transact against an expired listing, and §4.6's bid deadline beside it.
 *
 * §5.6: an implementation "MUST NOT accept a bid, propose an order, or advance an order against an
 * expired listing". That is the listing arm. The bid arm is a local tightening on §4.6, which names
 * "`expiration` … on a bid" among the deadlines that MUST be evaluated against the injected clock:
 * acting on a bid whose own deadline has passed is acting on an offer its author withdrew.
 *
 * ### [Permitted] says less than its name
 *
 * It means **neither deadline refuses**, and nothing more. It is not "this listing is open for
 * business" — a `kind:30402` carrying `["status", "sold"]` and no `expiration` is permitted here and
 * withdrawn by §5.1, which is `Listing.status`'s question (see [ListingActivity]). It is not "this
 * bid is acceptable", and it advances nothing: a bid binds nobody (§6), and nothing in
 * `dev.eryalabs.nenya.order` calls this gate, so the order machine is not claimed to enforce it.
 *
 * ### Fail-closed, and one reading
 *
 * Permitted only when both answers are [ListingActivity.ACTIVE]; [ListingActivity.CANNOT_SAY] on
 * either side refuses, so `NenyaClock.FAIL_CLOSED` never permits anything carrying an `expiration`.
 * The clock is read **at most once** and both deadlines are judged against that one reading: two
 * reads could straddle a deadline and answer for two different instants. A pair carrying no
 * `expiration` on either side never consults the clock, exactly as `Listing.activity` does not.
 *
 * The listing is checked before the bid, so an expired listing is refused naming the listing
 * whatever the bid's deadline says — §5.6's MUST is the one the document states.
 */
public sealed class DeadlineGate {

    /** Neither deadline refuses. See the class note on how little that claims. */
    public object Permitted : DeadlineGate() {

        override fun toString(): String = "DeadlineGate.Permitted"
    }

    /** One deadline refused, named by [reason] and its [side]. */
    public class Refused internal constructor(

        /** Which rule refused. */
        public val reason: DeadlineRefusal,
    ) : DeadlineGate() {

        /** The listing or the bid. */
        public val side: DeadlineSide get() = reason.side

        override fun equals(other: Any?): Boolean = other is Refused && other.reason == reason

        override fun hashCode(): Int = reason.hashCode()

        /** Names the rule, and no coordinate, deadline or key (§12 item 11). */
        override fun toString(): String = "DeadlineGate.Refused($reason)"
    }

    public companion object {

        /**
         * Whether [listing], and [bid] when there is one, are both still inside their deadlines.
         *
         * @param bid the bid being acted on, or `null` when the action is against the listing alone
         *   (proposing an order, advancing one). A bid scoped to a different listing is refused as
         *   [DeadlineRefusal.BID_FOR_ANOTHER_LISTING].
         * @param clock §3's clock seam. The default reports no time at all.
         */
        public fun check(
            listing: Listing,
            bid: Bid? = null,
            clock: NenyaClock = NenyaClock.FAIL_CLOSED,
        ): DeadlineGate {
            if (bid != null && bid.listing != listing.coordinate) {
                return Refused(DeadlineRefusal.BID_FOR_ANOTHER_LISTING)
            }
            val once = OneReading(clock)
            when (listing.activity(once)) {
                ListingActivity.ACTIVE -> Unit
                ListingActivity.EXPIRED -> return Refused(DeadlineRefusal.LISTING_EXPIRED)
                ListingActivity.CANNOT_SAY -> return Refused(DeadlineRefusal.LISTING_CANNOT_SAY)
            }
            return when (bid?.activity(once) ?: ListingActivity.ACTIVE) {
                ListingActivity.ACTIVE -> Permitted
                ListingActivity.EXPIRED -> Refused(DeadlineRefusal.BID_EXPIRED)
                ListingActivity.CANNOT_SAY -> Refused(DeadlineRefusal.BID_CANNOT_SAY)
            }
        }
    }
}

/** [clock], read on first use and never again, so both deadlines are judged at one instant. */
private class OneReading(private val clock: NenyaClock) : NenyaClock {

    private var reading: SeamAnswer<Long>? = null

    override fun now(): SeamAnswer<Long> = reading ?: clock.now().also { reading = it }

    override fun toString(): String = "OneReading(redacted)"
}
