package dev.eryalabs.nenya.tag

/**
 * Every nostr event kind NENYA-1 names, each set **once**.
 *
 * §5.2 makes that a testable rule rather than a style note: an implementation MUST expose the
 * request kind as a single named constant and MUST NOT hardcode the literal in more than one
 * place — "not because the value may change (Appendix B forbids that), but because a wire
 * constant duplicated across a codebase is how two call sites end up disagreeing." `KindConstantTest`
 * enforces it by sweeping the non-comment lines of `src/jvmMain/kotlin`.
 *
 * The kinds are here rather than in the wire package on purpose. §4.1 makes `kind` a bare JSON
 * number and the wire layer knows no kind vocabulary at all; which kinds Nenya defines is §5's
 * business, and the tag codec is the first thing that needs to know, because §5.3's cardinality
 * and requirement rules are a function of the kind.
 */
public object NenyaKind {

    /** NIP-99 classified listing — a Nenya **offer** (§5.1). Unmodified NIP-99. */
    public const val OFFER: Int = 30402

    /** NIP-99 draft/parked listing, identical in structure to [OFFER] (§5.1). */
    public const val OFFER_DRAFT: Int = 30403

    /**
     * The Nenya **request** kind (§5.2), decided in revision `1.1` as `OPEN-1` and normative.
     *
     * This declaration is the single named constant §5.2 requires. Appendix B forbids the value
     * changing after the first public release; a change would be a new major version and a
     * migration, not an edit.
     */
    public const val REQUEST: Int = 30404

    /** NIP-22 comment — a public bid, scoped to a listing coordinate (§6). */
    public const val PUBLIC_BID: Int = 1111

    /** NIP-17 free-text chat inside an order thread (§7.4). Carries no terms and moves no state. */
    public const val CHAT: Int = 14

    /** NIP-17 file message — the released deliverable (§7.4, §10.3). */
    public const val FILE_MESSAGE: Int = 15

    /** Structured order message, discriminated by a `type` tag (§7.4). */
    public const val ORDER_MESSAGE: Int = 16

    /** Payment receipt (§7.4, §9.2). */
    public const val RECEIPT: Int = 17

    /**
     * The three **listing** kinds, and the whole of §5.3's "Card. and Requirement are listing
     * rules" clause reduced to a membership test.
     */
    public val LISTING_KINDS: Set<Int> = setOf(OFFER, OFFER_DRAFT, REQUEST)

    /** `kind:16` `type` values §7.4 assigns. Held as constants for the same reason the kinds are. */
    public object OrderMessageType {

        /** Order proposal, from the buyer (§7.5). REQUIRED to open an order. */
        public const val PROPOSAL: Int = 1

        /** Payment request — exactly one BOLT-11 invoice (§8.6). */
        public const val PAYMENT_REQUEST: Int = 2

        /** Status update, carrying a `status` token from §11.1. */
        public const val STATUS: Int = 3

        /** GammaMarkets shipping update. Reserved; Nenya v1 MUST NOT emit it and MUST ignore it. */
        public const val SHIPPING: Int = 4

        /** Delivery commitment, from the provider (§10.1). */
        public const val DELIVERY_COMMITMENT: Int = 5

        /** Private bid (§6.1). The only `type` that carries no `order` tag. */
        public const val PRIVATE_BID: Int = 6
    }
}

/**
 * Which event a tag is being read on — the input §5.3 says a shared tag validator MUST take.
 *
 * §5.3 splits its own table in two, and the split is the reason this type exists:
 *
 * - the **Encoding** column is universal. Wherever a tag named in §5.3 appears — on a listing, on
 *   a `kind:1111` bid, on a private bid, on an order rumor — it MUST use exactly that encoding.
 * - the **Card.** and **Requirement** columns are **not**. They state what a `kind:30402`,
 *   `kind:30403` or `kind:30404` listing requires, and §5.3 says in as many words that they MUST
 *   NOT be applied to a bid or to an order rumor.
 *
 * §5.3 also says what happens to an implementation that ignores the split: "Applying the listing
 * rules everywhere rejects this document's own bid example", which legitimately carries a single
 * `["t", "nenya"]` and no `wtb`/`wts`. That is not a hypothetical — it is §6's worked example, and
 * a codec that rejects it cannot read the board.
 *
 * ### The kind is declared, not inferred
 *
 * The context is a parameter rather than something read off the event, for `kind:16` in
 * particular: the `type` lives in a tag, and deciding what an event *is* from the tags this codec
 * is about to validate would make the validation circular. [TagSet.read] checks the declared kind
 * against the event's own `kind` field and refuses a mismatch, so a wrong declaration is a
 * rejection rather than a silently different rule set.
 *
 * **The `type` is not checked the same way, and the asymmetry is deliberate rather than an
 * oversight.** `kind` is a top-level event field §4.1 covers by the id, so comparing it costs
 * nothing and catches a caller reading a bid under a listing's rules. `type` is an ordinary tag
 * that §5.3's table does not contain, so checking it here would mean this codec deciding what a
 * `["type", ...]` tag means before the layer that owns that vocabulary exists — and the mistake it
 * would catch is the embedding client mislabelling its own event, not a counterparty lying. Nenya
 * protects its user against counterparties and relays, not against the client embedding it. A
 * `kind:16` carrying `["type", "6"]` read under [orderMessage]`(1)` therefore gets the proposal's
 * mandatory-`item` rule; the fix is for the caller to declare what it read.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class TagContext(

    /** The event's `kind` (§4.1). */
    public val kind: Int,

    /**
     * For a `kind:16`, §7.4's `type` — REQUIRED there and forbidden everywhere else.
     *
     * Unknown values are permitted: §7.4 says an implementation MUST ignore a `kind:16` whose
     * `type` it does not implement and MUST NOT map it onto the nearest known one, so a context
     * has to be able to *name* a type it does not implement in order to ignore it correctly.
     */
    public val orderMessageType: Int? = null,
) {

    init {
        if (kind < 0) {
            throw TagException(
                TagRejection.MALFORMED_CONTEXT,
                "a nostr kind is a non-negative number; this context declares $kind",
            )
        }
        if (kind == NenyaKind.ORDER_MESSAGE && orderMessageType == null) {
            throw TagException(
                TagRejection.MALFORMED_CONTEXT,
                "§7.4 discriminates a kind:${NenyaKind.ORDER_MESSAGE} by its `type` tag, so a " +
                    "context for one must name the type",
            )
        }
        if (kind != NenyaKind.ORDER_MESSAGE && orderMessageType != null) {
            throw TagException(
                TagRejection.MALFORMED_CONTEXT,
                "only a kind:${NenyaKind.ORDER_MESSAGE} carries a `type` (§7.4); this context " +
                    "declares kind $kind",
            )
        }
        if (orderMessageType != null && orderMessageType < 1) {
            throw TagException(
                TagRejection.MALFORMED_CONTEXT,
                "§7.4's `type` values start at 1; this context declares $orderMessageType",
            )
        }
    }

    /** Whether §5.3's Card. and Requirement columns apply at all — §5.1 and §5.2's three kinds. */
    public val isListing: Boolean get() = kind in NenyaKind.LISTING_KINDS

    /**
     * Whether this listing is a **request** (§5.2) rather than an offer (§5.1).
     *
     * The one place §5.3's Requirement column differs between the two listing sides: `alt` is
     * "MUST on requests, SHOULD on offers", because `30404` is an unregistered kind and every
     * generic client that meets one renders whatever `alt` says, or nothing at all.
     */
    public val isRequest: Boolean get() = kind == NenyaKind.REQUEST

    /** Whether §5.3's `item` row requires exactly one `item` tag here (§7.5, §6.1). */
    public val requiresItem: Boolean
        get() = kind == NenyaKind.ORDER_MESSAGE &&
            (
                orderMessageType == NenyaKind.OrderMessageType.PROPOSAL ||
                    orderMessageType == NenyaKind.OrderMessageType.PRIVATE_BID
                )

    /**
     * Whether §5.3's `item` row forbids an `item` tag here: on a listing, because a listing
     * cannot reference itself, and on a public `kind:1111` bid, where `A`/`a` already carry the
     * binding and "a second binding that could disagree with the first is precisely what §4.2
     * exists to prevent".
     */
    public val forbidsItem: Boolean get() = isListing || kind == NenyaKind.PUBLIC_BID

    override fun equals(other: Any?): Boolean =
        other is TagContext && other.kind == kind && other.orderMessageType == orderMessageType

    override fun hashCode(): Int = kind * 31 + (orderMessageType ?: 0)

    override fun toString(): String =
        if (orderMessageType == null) "TagContext(kind=$kind)"
        else "TagContext(kind=$kind, type=$orderMessageType)"

    public companion object {

        /** A `kind:30402`, `kind:30403` or `kind:30404` listing — the one place Card. applies. */
        public fun listing(kind: Int): TagContext {
            if (kind !in NenyaKind.LISTING_KINDS) {
                throw TagException(
                    TagRejection.MALFORMED_CONTEXT,
                    "§5.3's listing rules apply to ${NenyaKind.LISTING_KINDS} and this is kind $kind",
                )
            }
            return TagContext(kind)
        }

        /** A public `kind:1111` bid (§6). §5.3's listing cardinality does not reach it. */
        public fun publicBid(): TagContext = TagContext(NenyaKind.PUBLIC_BID)

        /** A `kind:16` rumor of §7.4's [type]. */
        public fun orderMessage(type: Int): TagContext = TagContext(NenyaKind.ORDER_MESSAGE, type)

        /** A `kind:14`, `kind:15` or `kind:17` rumor (§7.4). */
        public fun rumor(kind: Int): TagContext = TagContext(kind)
    }
}
