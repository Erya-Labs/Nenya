package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §5.6's question — "may I still act on this listing?" — with the three answers it actually has.
 *
 * §5.6 makes the consumer responsible: "an implementation MUST treat an event whose `expiration`
 * has passed as inactive **regardless of the relay serving it**", and MUST NOT accept a bid,
 * propose an order or advance an order against an expired listing. Relays SHOULD honour NIP-40
 * and many do not, so a listing arriving from a relay is not evidence that it is live.
 *
 * ### Why there are three and not two
 *
 * §4.6 makes the *injected* clock authoritative, and T5's default answers
 * `SeamAnswer.Unavailable` — a client that injected no clock has one that reports no time at all.
 * A two-valued answer would have to round that to something, and both roundings are wrong:
 * "expired" hides a live listing, and "active" reports a deadline as unexpired that this library
 * never evaluated, which is the §17 over-claim the whole library is built to avoid. So the
 * unavailable clock has its own answer, and it is the fail-closed rule of T5 reaching a decoder
 * for the first time.
 *
 * ### This answers the deadline question and only the deadline question
 *
 * [ACTIVE] here means "not past its `expiration`". It does **not** mean the listing is open for
 * business: a `kind:30402` carrying `["status", "sold"]` and no `expiration` is [ACTIVE] by this
 * enum and withdrawn by §5.1. §5.6 makes that split itself — un-listing "requires two actions",
 * republishing with a terminal `status` **and** a NIP-09 deletion — so a caller asking "may I bid
 * on this?" has to read [Listing.status] as well, and a bid codec written against this predicate
 * alone would bid on sold work. The two are separate because the document separates them: the
 * deadline is the consumer's to enforce and the status is the publisher's to state.
 */
public enum class ListingActivity {

    /** The listing carries no `expiration`, or the injected clock says it has not arrived yet. */
    ACTIVE,

    /** The injected clock has reached or passed the `expiration`. §5.6: inactive, whatever the relay says. */
    EXPIRED,

    /**
     * No clock was injected, or the injected clock answered a negative reading (before 1970, which
     * §4.3 has no timestamp for, so the clock is broken), so this library will not say. **Never**
     * rounded to [ACTIVE] or [EXPIRED] (§17).
     */
    CANNOT_SAY,
}

/**
 * A `kind:30402` offer, its `kind:30403` draft, or a `kind:30404` request (§5.1, §5.2), decoded.
 *
 * ### The door is [decode], and what comes in is a [CheckedEvent]
 *
 * §4.1 requires an implementation to reject a received event whose `id` does not match its
 * recomputed value **before any other processing**, and T8 enforces that ordering by type: the
 * only way to obtain a [CheckedEvent] is to have had this library recompute the id. This decoder
 * takes one, so there is no path from a relay's bytes to a `Listing` that skips the check.
 *
 * ### Decoding is not re-encoding
 *
 * [encode] reproduces the **identical event**, id included, because it re-publishes the tag
 * codec's verbatim tags rather than the values this class parsed out of them (§4.3: an
 * implementation MUST NOT drop tags it does not understand when re-publishing, and dropping one
 * "silently strips extensions and changes the event id"). Normalising an uppercase pubkey or a
 * permissively-spelled price on the way back out would detach the author's signature from their
 * own event.
 *
 * ### What this type does and does not claim
 *
 * It claims the event's id was recomputed (T8), that its tags satisfy §5.3's Card. and
 * Requirement columns for this kind, and that its values decode under §5.3's Encoding column. It
 * claims **nothing** about the signature: no signature is checked, produced or claimed anywhere
 * in this library — `Secp256k1Ops` answers `Unavailable` — so a `Listing` is an event somebody
 * published, not an event a particular key published. §17 permits that omission provided the
 * implementation does not report unverified things as verified, which is why it is said here.
 *
 * Pure computation: no clock, no randomness, no I/O. The one time-dependent question, [activity],
 * takes the clock as a parameter (§4.6).
 */
public class Listing internal constructor(

    /** The id T8 recomputed and found to match the relay's claim (§4.1). */
    public val id: EventId,

    /** The author's x-only pubkey, exactly as it appeared in the event (§4.1, and [WireEvent.pubkey]). */
    public val authorPubkey: String,

    /**
     * §4.1's `created_at`, verbatim.
     *
     * A **claim**, and §4.6 says so: a counterparty's `created_at` orders nothing and decides no
     * deadline. It is here because §5.3's `published_at` row defines an edit in terms of it — "a
     * later `created_at` with an unchanged `published_at` is how an edit is recognised" — and for
     * no other purpose. [activity] does not read it.
     */
    public val createdAt: Long,

    /** §5.4's human-readable description, verbatim. Carries no terms: anything acted on is a tag. */
    public val content: String,

    /** Every tag, decoded under §5.3 and preserved verbatim for [encode] (§4.3). */
    public val tags: TagSet,

    /** §4.2's coordinate for this listing: `<kind>:<pubkey-hex>:<d-value>`, the only way to reference it. */
    public val coordinate: Coordinate,

    /** §5.3's `title`, REQUIRED on both kinds. Plain text, no markup. */
    public val title: String,

    /**
     * §5.3's `price`, REQUIRED on both kinds, in millisatoshis.
     *
     * §8.2: this is what the provider **receives**. On a request it is the buyer's budget and "a
     * ceiling for negotiation, not a commitment" (§5.2) — a bid MAY name a different price.
     */
    public val price: Msat,

    /** §5.1's or §5.2's `status`, read under this kind's vocabulary. Absent means [ListingStatus.ACTIVE]. */
    public val status: ListingStatus,

    /** Which side of the board §5.3's `t` tokens put this listing on, cross-checked against the kind. */
    public val side: ListingSide,
) {

    /** §5.1's and §5.2's kinds: `30402`, `30403` or `30404`. */
    public val kind: Int get() = coordinate.kind

    /** §5.3's `d`, the addressability key — opaque, non-empty and stable across edits. */
    public val dValue: String get() = coordinate.dValue

    /** §5.3's `summary`, or `null`. */
    public val summary: String? get() = tags.summary

    /** §5.3's `published_at` — the time of **first** publication, which MUST NOT change on edit. */
    public val publishedAt: Long? get() = tags.publishedAt

    /**
     * The raw `status` token as it arrived, or `null` if the event carried no `status` tag.
     *
     * Published alongside [status] because [ListingStatus.UNKNOWN] is deliberately lossy: §5.2
     * requires an unrecognised value be *treated* as unknown, not that the value be discarded, and
     * a client that wants to show the user what the publisher actually wrote needs it. Reading
     * meaning out of it is the thing §5.2 forbids.
     */
    public val statusToken: String? get() = tags.status

    /** §5.3's `t` tokens, verbatim and in order. See [TagSet.normalisedTopics] for matching. */
    public val topics: List<String> get() = tags.topics

    /** §5.3's `m` — the desired or produced MIME type, NIP-94 semantics and relay-filterable. */
    public val mimeType: String? get() = tags.mimeType

    /** §5.3's `image` tags, in order. Each is an `https:` URL; §12.10 forbids auto-fetching them. */
    public val images: List<ImageRef> get() = tags.images

    /** §5.3's `expiration` (NIP-40), in Unix seconds. Enforced by [activity], never by the relay (§5.6). */
    public val expiration: Long? get() = tags.expiration

    /** §5.3's `alt` (NIP-31) — MUST on a request, SHOULD on an offer. */
    public val alt: String? get() = tags.alt

    /** §4.5's `nenya` version. Deciding whether to implement it is the caller's (§4.5). */
    public val nenyaVersion: Int? get() = tags.nenyaVersion

    /** §5.3's `p` tags: a directed listing, addressed to specific counterparties. Still public. */
    public val pubkeyRefs: List<PubkeyRef> get() = tags.pubkeyRefs

    /**
     * §5.3's and §8.1's `fee` term, **advisory** on a listing: "only the order copy binds" (§5.3).
     *
     * [FeeTerm.Absent] and a stated `["fee", "0"]` are different values here as everywhere else
     * (§8.1): the second is a signed statement that no fee applies.
     */
    public val fee: FeeTerm get() = tags.fee

    /** The `fee` tag's recipient pubkey, present exactly when the term names one (§8.1). */
    public val feeRecipient: String? get() = tags.feeRecipient

    /** §5.3's `license` token. Vocabulary is `OPEN-5`, so it is carried and not interpreted. */
    public val license: String? get() = tags.license

    /** §5.3's `deliver_by` — advisory on a listing, binding only when copied into accepted terms. */
    public val deliverBy: Long? get() = tags.deliverBy

    /** The tags §5.3 does not name, verbatim and in order (§4.3). Preserved by [encode]. */
    public val unknownTags: List<List<String>> get() = tags.unknownTags

    /**
     * §5.6's rule, evaluated against §4.6's injected clock and nothing else.
     *
     * A listing carrying no `expiration` is [ListingActivity.ACTIVE] without the clock being
     * consulted: there is no deadline to evaluate, so there is nothing for an unavailable clock to
     * be unable to say. A listing that carries one is [ListingActivity.CANNOT_SAY] when the clock
     * is silent — never [ListingActivity.ACTIVE], which would report a deadline as unexpired that
     * this library never looked at. The same holds when the clock answers a negative reading: a
     * time before 1970 is no §4.3 timestamp, the clock is broken, and a broken clock is not
     * compared with a deadline in either direction.
     *
     * **`created_at` is not consulted, and that is §4.6.** A counterparty's `created_at` is a
     * claim; gift-wrap timestamps are deliberately randomised into the past (§7.1); and a listing
     * whose own `created_at` is *later* than the clock reading is not thereby expired, merely
     * published by a peer whose clock disagrees with this one.
     *
     * At exactly the named second the listing is treated as expired. NIP-40 fixes the instant
     * rather than the interval either side of it, and of the two readings this is the one that
     * errs toward refusing to act rather than toward acting on a listing whose deadline is at
     * hand — §5.6 forbids bidding, proposing or advancing against an expired listing, and none of
     * those is urgent enough to want the other rounding.
     *
     * @param clock §3's clock seam. The default reports no time at all, so the default answer for
     *   a listing with an `expiration` is [ListingActivity.CANNOT_SAY].
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

    /**
     * The event this listing was decoded from, byte for byte — same tags, same order, same id.
     *
     * §4.3 requires unknown tags be preserved verbatim when re-publishing an event this
     * implementation did not author, and states the consequence itself: dropping one changes the
     * id. `ListingPropertyTest` proves this the way the rule states it, by recomputing the id
     * through T8 over ten thousand generated listings rather than by comparing lists.
     */
    public fun encode(): WireEvent =
        WireEvent(authorPubkey, createdAt, kind, tags.republish(), content)

    /**
     * Names the kind, the status and the side, and no value the publisher wrote.
     *
     * A listing is a public event and none of this is secret, so this is convention rather than
     * §12: every type in this library redacts by default, because the one that does not is the
     * one that later holds a coordinate somebody pasted into a log. [coordinate] and [title] are
     * there for the caller that genuinely needs them.
     */
    override fun toString(): String = "Listing(kind=$kind, status=$status, side=$side)"

    public companion object {

        /**
         * Decode a §5.1 offer, §5.1 draft or §5.2 request from an id-checked event.
         *
         * §5.3's Card. and Requirement columns are applied **here**, which is where §5.3 says they
         * belong: they state what a listing requires and MUST NOT be applied to a bid or an order
         * rumor. The required set is derived from §5.3's table through T9's vocabulary rather than
         * listed here, so a row whose Requirement changes reaches this decoder without an edit.
         *
         * @param limits §4.3's fifth resource bound — 64 `image` tags — injected because §4.3 says
         *   the bounds SHOULD be configurable. Exceeding it is a rejection, never a truncation.
         * @throws ListingException naming which §5.1, §5.2, §5.3 or §4.3 rule refused the event,
         *   and the tag it was about. The tag layer's own [dev.eryalabs.nenya.tag.TagRejection] is
         *   preserved on the cause.
         */
        public fun decode(event: CheckedEvent, limits: TagLimits = TagLimits.DEFAULT): Listing {
            val kind = event.event.kind
            if (kind !in NenyaKind.LISTING_KINDS) {
                throw ListingException(
                    ListingRejection.NOT_A_LISTING_KIND,
                    null,
                    "§5.1 and §5.2 define listings as kinds ${NenyaKind.LISTING_KINDS.sorted()}; " +
                        "this event is kind:$kind, and §5.3's cardinality and requirement rules " +
                        "MUST NOT be applied to a bid or an order rumor",
                )
            }
            val tags = try {
                TagSet.read(event, TagContext.listing(kind), limits)
            } catch (refused: TagException) {
                throw refused.asListingRejection(event.event.tags, kind)
            }

            // §5.3 marks these MUST on every listing kind, and the tag codec has already refused
            // an event missing one. The checks are here anyway because the codec's types are
            // nullable and a decoder that answered `null` for a REQUIRED value would push §5.3's
            // rule onto every caller — and because a null reaching a `!!` here is a crash rather
            // than a rejection, which §4.3 calls a denial of service in the buyer's client.
            val dValue = tags.dValue ?: throw missing("d", kind)
            val title = tags.title ?: throw missing("title", kind)
            val price = tags.price ?: throw missing("price", kind)

            val status = ListingStatusCodec.read(tags.status, kind)
            val side = side(tags, kind)
            val coordinate = try {
                Coordinate(kind, event.event.pubkey, dValue)
            } catch (refused: TagException) {
                throw refused.asListingRejection(event.event.tags, kind)
            }

            return Listing(
                id = event.id,
                authorPubkey = event.event.pubkey,
                createdAt = event.event.createdAt,
                content = event.event.content,
                tags = tags,
                coordinate = coordinate,
                title = title,
                price = price,
                status = status,
                side = side,
            )
        }

        /**
         * The tags §5.3 marks MUST on [kind], derived from §5.3's table and never hand-listed.
         *
         * Published because it is the set this decoder enforces, and a caller building a listing
         * needs the same one — and because `ListingCodecTest` walks it, removing each in turn, so
         * the proof that the decoder requires them is a loop over the specification's own table
         * rather than over a list somebody kept in sync by hand.
         *
         * @throws ListingException [ListingRejection.NOT_A_LISTING_KIND] for a non-listing kind.
         */
        public fun requiredTags(kind: Int): List<String> {
            checkListingKind(kind)
            return ListingVocabulary.required(kind)
        }

        /**
         * The tags §5.3 marks MUST NOT on a listing: `item` (a listing cannot reference itself),
         * `g` and `location` (a location leak with no compensating function).
         *
         * @throws ListingException [ListingRejection.NOT_A_LISTING_KIND] for a non-listing kind.
         */
        public fun forbiddenTags(kind: Int): List<String> {
            checkListingKind(kind)
            return ListingVocabulary.forbidden(kind)
        }

        private fun checkListingKind(kind: Int) {
            if (kind !in NenyaKind.LISTING_KINDS) {
                throw ListingException(
                    ListingRejection.NOT_A_LISTING_KIND,
                    null,
                    "§5.3's Card. and Requirement columns are listing rules; kind:$kind is not one " +
                        "of ${NenyaKind.LISTING_KINDS.sorted()}",
                )
            }
        }

        /**
         * §5.1's and §5.2's side rule, which §5.3's `t` row states only half of.
         *
         * The tag codec has already required `nenya` and exactly one of `wtb` / `wts`; which one
         * belongs on which kind is §5.1's and §5.2's, and both make it REQUIRED rather than
         * conventional.
         */
        private fun side(tags: TagSet, kind: Int): ListingSide {
            val sides = tags.normalisedTopics.mapNotNull { ListingSide.byToken(it) }.distinct()
            val side = sides.singleOrNull() ?: throw ListingException(
                ListingRejection.MISSING_TOPIC,
                TOPIC_TAG,
                "§5.3 requires exactly one of `${ListingSide.WANT_TO_BUY.token}` / " +
                    "`${ListingSide.WANT_TO_SELL.token}` on a listing; this one names ${sides.size}",
            )
            // Non-null by the time this runs — `decode` refuses a non-listing kind before calling
            // it — and unwrapped rather than interpolated anyway. A message reading "`null` is
            // REQUIRED" is exactly the shape the duplicate-`item` diagnosis was fixed for, and
            // this is the line that would regress if a wider entry point ever reached here.
            val required = ListingSide.requiredOn(kind) ?: throw ListingException(
                ListingRejection.NOT_A_LISTING_KIND,
                TOPIC_TAG,
                "§5.1 and §5.2 fix a side token per listing kind; kind:$kind is not one of " +
                    "${NenyaKind.LISTING_KINDS.sorted()}",
            )
            if (side != required) {
                throw ListingException(
                    ListingRejection.SIDE_MISMATCH,
                    TOPIC_TAG,
                    "§5.1 and §5.2 make `${required.token}` REQUIRED on a kind:$kind listing and " +
                        "this one carries `${side.token}`",
                )
            }
            return side
        }

        private fun missing(tag: String, kind: Int): ListingException = ListingException(
            ListingRejection.MISSING_REQUIRED_TAG,
            tag,
            "§5.3 marks `$tag` MUST on a kind:$kind listing and this event carries no readable one",
        )
    }
}
