package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.tag.NenyaKind

/**
 * §5.1's and §5.2's **listing** status vocabulary. This is not §11.1's order-state vocabulary.
 *
 * ### The conflation trap, from the other side
 *
 * §11.1 says one `status` codec MUST NOT serve both vocabularies, and this type is the half of
 * that rule the listing side owns. The two ride the *same tag name*, `["status", ...]`, and share
 * exactly one token — `cancelled` — with a different meaning: a cancelled **listing** was
 * withdrawn from the board, a cancelled **order** ended before `paid`. That single token is
 * precisely where the conflation looks correct and passes a careless test.
 *
 * `OrderState` made the rule executable in the direction it could reach: every listing token
 * lands in `OrderState.UNKNOWN`. This closes the other direction — every one of §11.1's ten order
 * tokens read here lands in [UNKNOWN], `cancelled` reads as [CANCELLED] *of this type*, and
 * nothing converts between the two. There is no function anywhere in this library that takes one
 * and returns the other, and `ListingStructureTest` is what keeps it that way.
 *
 * ### Which tokens are legal where
 *
 * §5.1 gives an offer `active | sold` "and nothing else", because a third value makes the event
 * non-conformant in every existing NIP-99 client. §5.2 gives a request the extension
 * `active | awarded | fulfilled | cancelled`, "legitimate only because the kind is Nenya's own".
 * So the vocabulary is a function of the kind, and [ListingStatusCodec] takes it.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public enum class ListingStatus(

    /**
     * The canonical lowercase token §5.1 or §5.2 fixes, or `null` for [UNKNOWN], which has none.
     *
     * Nullable for the reason `OrderState.token` is: §5.2 defines `unknown` as the *treatment* of
     * an unrecognised value rather than as a value, so a sentinel `"unknown"` string here would
     * put an invented token within reach of anything that writes a `status` tag.
     */
    public val token: String?,
) {

    /** On both kinds. §5.3: an absent `status` means this. */
    ACTIVE(token = "active"),

    /** Offers only (§5.1). A sold offer is off the board; see §5.6 on why that is two actions. */
    SOLD(token = "sold"),

    /** Requests only (§5.2): the buyer has awarded the job. Carries no order state with it. */
    AWARDED(token = "awarded"),

    /** Requests only (§5.2): the job is done, as an announcement to the board. */
    FULFILLED(token = "fulfilled"),

    /**
     * Requests only (§5.2): the buyer withdrew the request.
     *
     * The one token shared with §11.1's order vocabulary, with a different meaning. §11.1
     * additionally forbids deriving order state from a listing status under any circumstances — a
     * buyer republishing their request as `cancelled` is an announcement to the board, never a
     * transition of an order that referenced it.
     */
    CANCELLED(token = "cancelled"),

    /**
     * §5.2's required treatment of a `status` value this vocabulary does not name: "Implementations
     * MUST treat an unrecognised `status` value as `unknown` and MUST NOT treat it as `active`."
     *
     * Carries no token, so nothing in this package can emit one.
     */
    UNKNOWN(token = null),
}

/**
 * Which side of the board a listing is on, as §5.3's `t` row states it and §5.1 and §5.2 fix it
 * per kind.
 *
 * §5.3 requires exactly one of the pair and leaves which one to §5.1 and §5.2, which are
 * unambiguous: `wtb` is REQUIRED on a request and `wts` is REQUIRED on an offer. The tag codec
 * enforces the "exactly one" half without knowing the kind's answer; [requiredOn] is the other
 * half, and `Listing.decode` refuses a listing whose side disagrees with its kind.
 */
public enum class ListingSide(

    /** §5.3's `t` token, lowercase — relay tag indexes are byte-exact (§5.3, §5.5). */
    public val token: String,
) {

    /** `wtb`, want to buy: a `kind:30404` request (§5.2). */
    WANT_TO_BUY(token = "wtb"),

    /** `wts`, want to sell: a `kind:30402` or `kind:30403` offer (§5.1). */
    WANT_TO_SELL(token = "wts");

    public companion object {

        /** The side whose token [kind] REQUIRES (§5.1, §5.2), or `null` if [kind] is not a listing. */
        public fun requiredOn(kind: Int): ListingSide? = when (kind) {
            NenyaKind.REQUEST -> WANT_TO_BUY
            NenyaKind.OFFER, NenyaKind.OFFER_DRAFT -> WANT_TO_SELL
            else -> null
        }

        /** The side [token] names, matched case-insensitively per §5.3's read rule, or `null`. */
        public fun byToken(token: String): ListingSide? {
            val normalised = token.lowercase()
            return entries.firstOrNull { it.token == normalised }
        }
    }
}

/**
 * §5.1's and §5.2's `status` codec — kind-aware, because the two kinds have different vocabularies.
 *
 * ### Strict on write, permissive on read — with the one exception §5.1 states as a MUST
 *
 * The asymmetry is §4.4's, applied to a vocabulary instead of to money, and it is **not**
 * symmetric between the two kinds, because the two sections are not:
 *
 * - [write] emits only what the kind's own section gives it. Emitting `sold` on a `kind:30404`
 *   would be this library inventing a rule §5.2 does not state.
 * - [read] answers [ListingStatus.UNKNOWN] for any token this kind's vocabulary does not name —
 *   §5.2 says so in as many words, and adds the half an implementer forgets: it MUST NOT be
 *   treated as `active`. A request carrying `["status", "sold"]` is a peer this implementation
 *   does not understand, not a malformed event, and refusing the whole listing over it would
 *   throw away its `d`, `title` and `price` too.
 * - **Except** on an offer, where §5.1 closes the vocabulary itself: an offer's status "MUST be
 *   either `active` or `sold`, and nothing else", because a third value makes the event
 *   non-conformant in every existing NIP-99 client — the distribution §5.1 exists to keep. So a
 *   `kind:30402` carrying `awarded`, `fulfilled` or `cancelled` — tokens **this document
 *   defines**, on the one kind it forbids them — is [ListingRejection.STATUS_OUTSIDE_VOCABULARY].
 *
 * That last narrowing is deliberately as small as the rule that licenses it. A token *no* section
 * defines still reads as [ListingStatus.UNKNOWN] on an offer as well: §5.1's "nothing else" is
 * about the values this document assigns meanings to elsewhere, and a peer speaking a dialect
 * Nenya has not written yet is exactly what §5.2's unknown-treatment clause is for.
 *
 * ### Case is not normalised
 *
 * §5.1 and §5.2 fix the tokens as lowercase and §4.3's accept-and-normalise rule is about hex and
 * its two named exceptions, not about a controlled vocabulary. So `Active` is not `active`: it is
 * a token this vocabulary does not name, and §5.2's answer for that is [ListingStatus.UNKNOWN] —
 * never [ListingStatus.ACTIVE]. Same treatment `OrderStatusCodec` gives a mixed-case order token,
 * for the same reason.
 */
public object ListingStatusCodec {

    /** §5.1: an offer's `status` MUST be one of these two, "and nothing else". */
    public val OFFER_VOCABULARY: Set<ListingStatus> = setOf(ListingStatus.ACTIVE, ListingStatus.SOLD)

    /** §5.2's request extension, legitimate only because `30404` is Nenya's own kind. */
    public val REQUEST_VOCABULARY: Set<ListingStatus> = setOf(
        ListingStatus.ACTIVE,
        ListingStatus.AWARDED,
        ListingStatus.FULFILLED,
        ListingStatus.CANCELLED,
    )

    private val BY_TOKEN: Map<String, ListingStatus> =
        ListingStatus.entries.mapNotNull { status -> status.token?.let { it to status } }.toMap()

    /**
     * The statuses §5.1 or §5.2 permits on [kind].
     *
     * @throws ListingException [ListingRejection.NOT_A_LISTING_KIND] for anything that is not a
     *   `kind:30402`, `kind:30403` or `kind:30404`. A bid and an order rumor carry no listing
     *   status at all, and answering "the offer vocabulary, probably" for one would be this codec
     *   guessing.
     */
    public fun vocabulary(kind: Int): Set<ListingStatus> = when (kind) {
        NenyaKind.REQUEST -> REQUEST_VOCABULARY
        NenyaKind.OFFER, NenyaKind.OFFER_DRAFT -> OFFER_VOCABULARY
        else -> throw ListingException(
            ListingRejection.NOT_A_LISTING_KIND,
            STATUS_TAG,
            "§5.1 and §5.2 define a `$STATUS_TAG` vocabulary for a listing; kind:$kind is not one",
        )
    }

    /**
     * The status [statusToken] names on [kind], with an absent tag read as [ListingStatus.ACTIVE].
     *
     * Permissive, per §5.2: a token this kind's vocabulary does not name reads as
     * [ListingStatus.UNKNOWN] rather than refusing the listing — never as [ListingStatus.ACTIVE].
     * The single exception is §5.1's closed offer vocabulary; see this object's note.
     *
     * @param statusToken the raw `["status", ...]` value, or `null` where the event carries no
     *   `status` tag at all — §5.3's "Absent means `active`".
     * @throws ListingException [ListingRejection.STATUS_OUTSIDE_VOCABULARY] for one of §5.2's
     *   request tokens on an **offer**, which §5.1 forbids by name, or
     *   [ListingRejection.NOT_A_LISTING_KIND] for a kind that has no listing vocabulary.
     */
    public fun read(statusToken: String?, kind: Int): ListingStatus {
        val vocabulary = vocabulary(kind)
        if (statusToken == null) return ListingStatus.ACTIVE
        val named = BY_TOKEN[statusToken] ?: return ListingStatus.UNKNOWN
        if (named in vocabulary) return named
        // §5.1's "and nothing else" is a rule about what a NIP-99 offer *is*, and it is the only
        // closed vocabulary of the two. §5.2 gives requests no such clause and does give the
        // unknown-treatment MUST, so the request side reads permissively rather than symmetrically
        // — a symmetry this codec invented would be a rule the document does not state.
        if (kind == NenyaKind.REQUEST) return ListingStatus.UNKNOWN
        throw ListingException(
            ListingRejection.STATUS_OUTSIDE_VOCABULARY,
            STATUS_TAG,
            "§5.1 says an offer's `$STATUS_TAG` MUST be one of " +
                "${vocabulary.mapNotNull { it.token }.sorted()} and nothing else; this one names a " +
                "value §5.2 defines for a request, which is non-conformant in every existing " +
                "NIP-99 client",
        )
    }

    /**
     * The `["status", "<token>"]` tag for [status] on [kind].
     *
     * The write half is held to the same vocabulary as the read half, and that is the point of it
     * being here rather than in a caller: §5.1's rule binds emission hardest, because a
     * `kind:30402` carrying `awarded` is not merely unread by Nenya clients — it is non-conformant
     * in every existing NIP-99 client, which is the distribution §5.1 exists to keep.
     *
     * @throws ListingException [ListingRejection.UNKNOWN_IS_NOT_EMITTABLE] for
     *   [ListingStatus.UNKNOWN] — §5.2's `unknown` is the treatment of somebody else's
     *   unrecognised token, and emitting it would republish their value as though this
     *   implementation had understood it — or [ListingRejection.STATUS_OUTSIDE_VOCABULARY] for a
     *   status this kind does not permit.
     */
    public fun write(status: ListingStatus, kind: Int): List<String> {
        val vocabulary = vocabulary(kind)
        val token = status.token ?: throw ListingException(
            ListingRejection.UNKNOWN_IS_NOT_EMITTABLE,
            STATUS_TAG,
            "§5.2's `unknown` is the required treatment of an unrecognised value, not a token: " +
                "there is nothing to emit for it",
        )
        if (status !in vocabulary) {
            throw ListingException(
                ListingRejection.STATUS_OUTSIDE_VOCABULARY,
                STATUS_TAG,
                "§5.1 and §5.2 give kind:$kind the vocabulary " +
                    "${vocabulary.mapNotNull { it.token }.sorted()}, and `$token` is not in it",
            )
        }
        return listOf(STATUS_TAG, token)
    }
}

/** §5.3's `status` row, named once so nothing in this package spells it twice. */
internal const val STATUS_TAG: String = "status"
