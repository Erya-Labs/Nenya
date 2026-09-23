package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.NenyaKind

/**
 * §7.4's four rumor kinds, modelled over the constants T9 already declares.
 *
 * **Not one of these numbers is written here.** §5.2 makes the single-named-constant rule testable
 * for `30404`, and it gives a reason that is about wire constants in general rather than about that
 * one: "a wire constant duplicated across a codebase is how two call sites end up disagreeing". A
 * second source of truth for `16` would be the same defect one field over, so every constant below
 * reads out of [NenyaKind].
 *
 * The set is held **equal** to the first of §7.4's two tables, parsed out of `spec/NENYA-1.md` at
 * test time — equal in both directions, because containment passes over a model carrying two of the
 * four.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public enum class RumorKind(

    /** The nostr `kind` number §7.4's table gives this row. */
    public val kind: Int,
) {

    /** `14` — free-text chat inside the order thread (§7.4). Carries no terms and moves no state. */
    CHAT(NenyaKind.CHAT),

    /** `15` — file message, the **released** deliverable (§7.4, §10.3). */
    FILE_MESSAGE(NenyaKind.FILE_MESSAGE),

    /** `16` — structured order message, discriminated by a `type` tag (§7.4). */
    ORDER_MESSAGE(NenyaKind.ORDER_MESSAGE),

    /** `17` — payment receipt (§7.4, §9.2). */
    RECEIPT(NenyaKind.RECEIPT);

    /**
     * Whether §7.4's required-tag rule reaches this kind at all.
     *
     * §7.4 states it over `kind:15`, `kind:16` and `kind:17` and then puts `kind:14` outside it in
     * so many words: chat "carries no terms and moves no state, so binding it is a display
     * convenience; an implementation MAY carry `order` on a `kind:14` and MUST NOT derive anything
     * from its presence or absence."
     */
    public val carriesRequiredTags: Boolean get() = this != CHAT

    public companion object {

        /** The kind [kind] names, or `null` for a number §7.4's table does not have. */
        public fun of(kind: Int): RumorKind? = entries.firstOrNull { it.kind == kind }
    }
}

/**
 * §7.4's six `kind:16` `type` values, plus the sink §7.4 requires for a seventh.
 *
 * ### The sink is the only new member of the vocabulary
 *
 * §7.4: "An implementation MUST ignore a `kind:16` whose `type` it does not implement, and MUST NOT
 * map an unknown `type` onto the nearest known one — the same rule §11.1 applies to an unknown
 * `status`." So this is `ListingStatus.UNKNOWN` and `OrderState.UNKNOWN` again, at a third layer,
 * and it is modelled the same way: [UNKNOWN] carries a `null` [type], so nothing in this package
 * can emit one. A sentinel number would put an invented `type` within reach of anything that writes
 * a `["type", ...]` tag, which is the mapping §7.4 forbids with extra steps.
 *
 * The six numbers come from [NenyaKind.OrderMessageType] and are not written here, for the reason
 * [RumorKind] gives. The **names** are this package's own — §7.4's Name column is prose
 * ("status update") rather than an identifier — and the set of `type` numbers is held **equal** to
 * the second of §7.4's two tables, parsed at test time.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public enum class OrderMessageKind(

    /** §7.4's `type` number, or `null` for [UNKNOWN], which has none and is not a wire value. */
    public val type: Int?,
) {

    /** `1` — order proposal, from the buyer. REQUIRED to open an order (§7.5). */
    PROPOSAL(NenyaKind.OrderMessageType.PROPOSAL),

    /** `2` — payment request, from the provider or the fee recipient; one BOLT-11 invoice (§8.6). */
    PAYMENT_REQUEST(NenyaKind.OrderMessageType.PAYMENT_REQUEST),

    /** `3` — status update from either party, carrying a `status` token from §11.1. */
    STATUS_UPDATE(NenyaKind.OrderMessageType.STATUS),

    /**
     * `4` — GammaMarkets shipping update. **Reserved**: §7.4 says Nenya v1 MUST NOT emit it and
     * MUST ignore it on read, so it is readable here and refused by [ChannelTags.type].
     */
    SHIPPING(NenyaKind.OrderMessageType.SHIPPING),

    /** `5` — delivery commitment, from the provider. A Nenya extension (§10.1). */
    DELIVERY_COMMITMENT(NenyaKind.OrderMessageType.DELIVERY_COMMITMENT),

    /** `6` — private bid (§6.1). The **only** `type` that carries no `order` tag. */
    PRIVATE_BID(NenyaKind.OrderMessageType.PRIVATE_BID),

    /**
     * §7.4's required treatment of a `type` this implementation does not implement.
     *
     * Carries no [type], so it has no wire form and nothing here can emit it — the same shape, and
     * the same reason, as `ListingStatus.UNKNOWN` and `OrderState.UNKNOWN`.
     */
    UNKNOWN(type = null);

    /**
     * §7.4's collision rule: `type=5` and `type=6` "are Nenya's own assignments in a vocabulary
     * Nenya does not own, so a future GammaMarkets `type=5` or `type=6` could collide. The
     * `["nenya", "1"]` tag is what disambiguates."
     *
     * For those two and only those two, a `nenya` version this implementation does not implement is
     * a message that MUST NOT be interpreted as a delivery commitment or a bid.
     */
    public val versionDisambiguated: Boolean get() = this == DELIVERY_COMMITMENT || this == PRIVATE_BID

    /**
     * Whether §7.4 requires an `order` tag on a `kind:16` of this type.
     *
     * `false` for [PRIVATE_BID] alone — §7.4's single exception, "because no order exists yet".
     * [UNKNOWN] answers `true`: §7.4's exception names `type=6` and nothing else, and a `type` this
     * library does not implement is still one of the messages the rule is stated over.
     */
    public val carriesOrderId: Boolean get() = this != PRIVATE_BID

    /**
     * Whether Nenya v1 may put this `type` on the wire.
     *
     * `false` for [SHIPPING], which §7.4 reserves by name, and for [UNKNOWN], which is a treatment
     * rather than a value. Read-and-ignore is what §7.4 asks for; emitting is what it forbids.
     */
    public val emittable: Boolean get() = this != SHIPPING && this != UNKNOWN

    public companion object {

        /**
         * The type [type] names, or [UNKNOWN] for a number §7.4's table does not have.
         *
         * Never the nearest known one — §7.4 forbids that in the same sentence it requires the
         * sink.
         */
        public fun of(type: Int): OrderMessageKind = entries.firstOrNull { it.type == type } ?: UNKNOWN
    }
}

/**
 * How a rumor's terms are attributed to a key: §7.2's two modes, and the door to the stronger one
 * is narrow on purpose.
 *
 * §7.2 states both, and states them asymmetrically:
 *
 * > "An implementation that cannot verify the seal's signature (no secp256k1; see §17) still MUST
 * > perform the pubkey-equality check, and MUST report messages in that mode as
 * > **authenticated-by-decryption only, never as signature-verified**." … "An implementation that
 * > **did** verify the seal's signature, and found it valid over the seal's recomputed id (§4.1),
 * > MAY report the message as signature-verified."
 *
 * So [AUTHENTICATED_BY_DECRYPTION] is always a truthful report and [SIGNATURE_VERIFIED] is one only
 * for a verdict that was actually obtained. This type carried one constant until `GiftWrap.open`
 * existed, because until then nothing in this library verified a signature and a constant for it
 * would have been a claim with no code behind it.
 *
 * ### The public [AttributedRumor.Companion.attribute] still cannot return [SIGNATURE_VERIFIED]
 *
 * It takes a seal pubkey and a rumor and performs §7.2's string comparison, which is all it can do:
 * it is handed no signature, no verifier and no seal to check one against. The stronger constant is
 * reachable only from `GiftWrap.open`, through an `internal` path that the envelope package takes
 * **after** the injected `Secp256k1Ops` answered `SignatureVerdict.VALID` over the seal's recomputed
 * id. `RumorVocabularyTest` pins both halves — that the constant exists, and that the public door
 * never yields it — because "reachable only from there" is otherwise a claim about today's call
 * sites rather than about the code.
 */
public enum class Attribution(

    /** A one-line description, for a diagnostic that carries no key and no identifier. */
    public val what: String,
) {

    /**
     * The seal decrypted, and its `pubkey` equals the rumor's (§7.2). **No signature was checked**
     * — either none was available (`Secp256k1Ops` answered `Unavailable`) or the caller performed
     * the decryption itself and this library was handed only the two keys.
     */
    AUTHENTICATED_BY_DECRYPTION(
        "the seal and rumor pubkeys agree; no signature was verified (§7.2, §17)",
    ),

    /**
     * The same check, **and** the `kind:13` seal's BIP-340 signature verified against the seal's
     * `pubkey` over the id this library recomputed (§4.1, §7.2, §7.1's read step 6).
     *
     * The gift wrap's signature is deliberately not part of this claim, whatever its verdict. §7.2:
     * "It is a signature by the throwaway key over the wrap, so a valid one says only that the wrap
     * reached the reader unaltered. It attributes nothing, it does not corroborate the seal, and an
     * implementation MUST NOT report a message as signature-verified on the strength of it."
     * `OpenedMessage` publishes the two verdicts separately for exactly that reason.
     */
    SIGNATURE_VERIFIED(
        "the seal and rumor pubkeys agree and the seal's signature verified over its recomputed " +
            "id (§7.2, §7.1 step 6)",
    ),
}

/**
 * The §7.4 tag names this package reads, kept **out** of `NenyaTags` on purpose.
 *
 * `NenyaTags` is held *equal* to §5.3's table parsed at test time, and `order`, `type`,
 * `amount_msat` and `amount` are not rows in it — they are §7.4's and §7.5's, as `payee` and
 * `payment` are §8.6's. Adding one to `NenyaTags` would turn `TagVocabularyTest` red for a reason
 * that has nothing to do with the tag: the table would no longer equal the document. So those names
 * live here, exactly as §6's NIP-22 scope tags live in `BidVocabulary`.
 *
 * The tags that **are** in §5.3 — `p`, `nenya`, `item`, `fee`, `expiration`, `deliver_by`,
 * `status` — are read through T9, whose Encoding column §5.3 makes universal across listings, bids
 * and order rumors alike. Nothing here re-implements one.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public object ChannelTags {

    /** §7.4's `["order", "<order-id-hex>"]` — 32 bytes, 64 hex characters. */
    public const val ORDER: String = "order"

    /** §7.4's `["type", "<n>"]`, which discriminates a `kind:16`. */
    public const val TYPE: String = "type"

    /**
     * §7.5's `["amount_msat", "<msat>"]` — "the price in millisatoshis, what the provider receives".
     *
     * The authoritative amount of a proposal, and the one §8.3's arithmetic runs on. Read in
     * §4.4's **strict** canonical decimal form, because §7.6 compares it byte-identically and a
     * codec that read `090000000` as `90000000` would call a counter-proposal an acceptance.
     */
    public const val AMOUNT_MSAT: String = "amount_msat"

    /**
     * §7.5's `["amount", "<sats>"]` — the GammaMarkets compatibility tag, denominated in satoshis.
     *
     * A MAY: an implementation that *required* it would refuse conformant peers, and one that
     * preferred it over [AMOUNT_MSAT] on a disagreement ships the silent 1000× divergence §7.5
     * wrote its cross-check to prevent. It is read only in order to be checked against
     * [AMOUNT_MSAT], and never as the price.
     */
    public const val AMOUNT: String = "amount"

    /**
     * §7.4's `order` tag for [id], in §4.3's canonical lowercase hex.
     *
     * Takes an [OrderId] rather than a `String`, so the only way to write this tag is to hold the
     * value T5 mints from injected randomness or reads from the wire — never a hex string a caller
     * assembled, which §7.4 forbids deriving from the coordinate, either pubkey, the price or the
     * time.
     */
    public fun order(id: OrderId): List<String> = listOf(ORDER, id.toHex())

    /**
     * §7.4's `type` tag for [message].
     *
     * @throws ChannelException [ChannelRejection.RESERVED_TYPE] for [OrderMessageKind.SHIPPING] —
     *   §7.4: "Nenya v1 MUST NOT emit `type=4`" — or [ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE]
     *   for [OrderMessageKind.UNKNOWN], which is §7.4's required *treatment* of somebody else's
     *   unrecognised value and not a value of its own. Emitting it would republish their number as
     *   though this implementation had understood it.
     */
    public fun type(message: OrderMessageKind): List<String> {
        if (message == OrderMessageKind.SHIPPING) {
            throw ChannelException(
                ChannelRejection.RESERVED_TYPE,
                TYPE,
                "§7.4 reserves `$TYPE=${message.type}` for GammaMarkets physical shipping: Nenya v1 " +
                    "MUST NOT emit it and MUST ignore it on read",
            )
        }
        val number = message.type ?: throw ChannelException(
            ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE,
            TYPE,
            "§7.4's unknown `$TYPE` is the required treatment of an unrecognised value, not a " +
                "value: there is nothing to emit for it",
        )
        return listOf(TYPE, number.toString())
    }
}
