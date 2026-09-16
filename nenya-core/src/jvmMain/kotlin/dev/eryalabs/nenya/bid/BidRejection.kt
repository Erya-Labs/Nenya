package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection

/**
 * Why a public bid was refused (§6, and §4.2, §4.3 and §5.3 reached through it).
 *
 * The reason is part of the API and not merely diagnostic text, following the convention every
 * other package in this library already uses — `MoneyRejection`, `PaymentRejection`,
 * `DeliveryRejection`, `SeamRejection`, `OrderStateRejection`, `WireRejection`, `TagRejection`,
 * `ListingRejection`. Tests assert on these constants rather than on wording.
 *
 * Several of them exist because §6 gives a *reason* and not merely a verdict, and an implementer
 * who collapses them tells the user something false. [SCOPED_BY_EVENT_ID] is the sharpest: a bid
 * carrying `["e", ...]` instead of `["a", ...]` is not a bid with a missing tag, it is a bid that
 * "detaches the moment the listing is edited", and reporting it as the former sends whoever reads
 * the refusal looking for the wrong bug in the wrong implementation.
 */
public enum class BidRejection {

    /**
     * The event is not a `kind:1111` (§6).
     *
     * §6's required set is §6's own — §5.3's Card. and Requirement columns MUST NOT be applied to
     * a bid — so this decoder refuses rather than quietly applying the bid rules to a listing or
     * to an order rumor.
     */
    NOT_A_BID_KIND,

    /**
     * The bid carries `["E", ...]` or `["e", ...]` — §6: "A bid MUST scope to a coordinate
     * (`A`/`a`), never to an event id (`E`/`e`). A bid scoped by event id detaches the moment the
     * listing is edited."
     *
     * Its own constant, and deliberately **not** reported as a missing `a` tag, which is the
     * conflation §6's own rationale exists to prevent. The two refusals send a reader to different
     * places: one says the peer omitted something, the other says the peer chose the referencing
     * scheme §4.2 was written to replace, and NIP-15's auction warning is the worked example of
     * what that costs.
     *
     * A NIP-22 **reply to a bid** lands here too, and that is correct rather than incidental: a
     * reply's parent is the bid's event id, so it carries `["e", ...]`. It is not a Nenya bid. See
     * [Bid] for the scope statement.
     */
    SCOPED_BY_EVENT_ID,

    /** §6 marks a tag MUST on a bid and the event carries none. See [BidException.tag]. */
    MISSING_REQUIRED_TAG,

    /** §4.3: a tag §5.3 marks cardinality `1` or `0–1`, or one of §6's scope tags, appears twice. */
    DUPLICATE_TAG,

    /**
     * The root (`A`, `P`) and parent (`a`, `p`) scope tags name different things.
     *
     * §6: "A top-level bid has the listing as both root and parent, so the uppercase and lowercase
     * tags carry the same values." Only top-level bids are in scope here, so a disagreement is a
     * shape this codec does not read rather than a shape it silently picks one half of.
     */
    SCOPE_DISAGREEMENT,

    /** §6: `K` and `k` "MUST both equal the listing's kind as a decimal string", and do not. */
    LISTING_KIND_MISMATCH,

    /** §6: `P` and `p` "MUST both equal the listing author's pubkey", and do not. */
    LISTING_AUTHOR_MISMATCH,

    /**
     * The coordinate names a kind that is not one of §5.1's or §5.2's listing kinds.
     *
     * §6 scopes a bid to a listing and to nothing else: "Bids are made **against requests** in the
     * normal flow, and MAY also be made against offers as a counter-offer", and an implementation
     * MUST support parsing both. A bid scoped to some other kind is not a shape this document
     * defines, and accepting one would mean this library handing a caller a "bid" on an event that
     * carries no price, no `d` and no side.
     */
    NOT_A_LISTING_COORDINATE,

    /** §6: "A bid MUST carry `price`, `t=nenya` and `nenya`" — the `t` tags do not include `nenya`. */
    MISSING_TOPIC,

    /**
     * An `item` tag on a public bid — §5.3 gives it cardinality `0` there, with the reason:
     * "`A`/`a` already carry the binding and a second binding that could disagree with the first is
     * precisely what §4.2 exists to prevent".
     *
     * Its own constant rather than a forbidden-tag shrug, for the same reason `ListingRejection`
     * separates `SELF_REFERENCE`: §5.3 states *why*, and a client that renders "this bid carries a
     * tag I do not understand" has hidden the fact that the bid carried two bindings that could
     * point at two different listings.
     */
    DOUBLE_BINDING,

    /**
     * §4.3's fifth resource bound — more `image` tags than `TagLimits.maxImageTags` — exceeded.
     *
     * Its own constant rather than [MALFORMED_TAG], because §4.3 says the bounds SHOULD be
     * configurable: a bid refused for carrying 70 images is a caller that may want to raise its
     * bound, and one refused for a `data:` URI is a peer that broke a MUST. Neither is truncated.
     */
    LIMIT_EXCEEDED,

    /**
     * A form §4.4 requires be treated as **unsupported on read** rather than as malformed: the
     * optional NIP-99 `<frequency>` fourth element of `price`, which Nenya v1 MUST NOT emit.
     *
     * Carried up from `TagRejection.UNSUPPORTED` rather than flattened into [MALFORMED_TAG], for
     * the reason T9 gave the constant: reporting a well-formed NIP-99 form this version does not
     * implement as malformed tells the user a conformant client is broken.
     */
    UNSUPPORTED,

    /**
     * §5.3's Encoding column refused a value — a malformed `price`, a pubkey of the wrong length,
     * a coordinate that does not parse.
     *
     * The precise §5.3 reason is **not** lost: it is the [TagRejection] on the `cause`, which is
     * the [TagException] the tag codec threw. See [BidException].
     */
    MALFORMED_TAG,
}

/**
 * A bid was refused, carrying the [reason] and the [tag] it is about as data.
 *
 * One exception type for the whole bid surface, as every other package in this library does: a
 * caller has one thing to catch and one field to branch on.
 *
 * ### Why the tag name is a field rather than a sentence
 *
 * §6's required set is data — [Bid.requiredTags] publishes it, and `BidCodecTest` walks it,
 * removing each REQUIRED tag in turn and asserting the refusal names *that* tag. A message a test
 * has to pattern-match would make that proof a string comparison against the wording of the
 * message the same implementer wrote; a field makes it a fact.
 *
 * ### The §5.3 reason survives, on the cause
 *
 * Every refusal the tag codec raises is mapped onto this vocabulary with the original
 * [TagException] attached as the cause, exactly as `TagException.asListingRejection` does one
 * package over. Nothing is flattened away: a caller that wants [TagRejection] still has it.
 *
 * **The message never echoes the caller's input.** A bid is a public event, but this decoder's
 * messages are held to the same rule as every other in this library (§12, STOP RULE 14): a tag
 * *name*, a *kind*, a *count* and a *reason* may appear; a coordinate, a `d` value, a pubkey or an
 * arbitrary tag value may not.
 */
public class BidException internal constructor(
    public val reason: BidRejection,

    /** The §6 or §5.3 tag this refusal is about, or `null` where the rule names no single tag. */
    public val tag: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * §6's required set, which is **§6's own and not §5.3's** — and that is the point of the object.
 *
 * `ListingVocabulary` derives its required set from §5.3's Requirement column, because §5.3 states
 * the listing rules. This one cannot and MUST not: §5.3 says its Card. and Requirement columns
 * "MUST NOT be applied to a bid or to an order rumor, which carry their own required sets (§6,
 * §6.1, §7.4, §7.5)". §6 states this one in prose rather than in a table, so it is transcribed
 * here, in one place, and `BidCodecTest` asserts it against §6's worked example — which carries a
 * single `["t", "nenya"]` and no `wtb`/`wts`, and which §5.3 names as the event a codec applying
 * the listing rules everywhere would reject.
 */
internal object BidVocabulary {

    /** NIP-22's root scope: the listing coordinate (§6). Uppercase. */
    const val ROOT_COORDINATE: String = "A"

    /** NIP-22's parent scope: the listing coordinate again, on a top-level bid (§6). */
    const val PARENT_COORDINATE: String = "a"

    /** NIP-22's root kind — the listing's kind as a decimal string (§6). */
    const val ROOT_KIND: String = "K"

    /** NIP-22's parent kind (§6). */
    const val PARENT_KIND: String = "k"

    /** NIP-22's root author — the listing author's pubkey (§6). */
    const val ROOT_AUTHOR: String = "P"

    /** NIP-22's parent author (§6). Also §5.3's `p` row, so the tag codec decodes it as well. */
    const val PARENT_AUTHOR: String = "p"

    /** NIP-22's root scope by **event id**, which §6 forbids on a bid. */
    const val ROOT_EVENT_ID: String = "E"

    /** NIP-22's parent scope by **event id**, which §6 forbids on a bid. */
    const val PARENT_EVENT_ID: String = "e"

    /** §5.3's `price` row. §6: "A bid MUST carry `price`". */
    const val PRICE: String = "price"

    /** §5.3's `t` row. §6 requires `t=nenya` specifically, which is [TOPIC] plus [MISSING_TOPIC]. */
    const val TOPIC: String = "t"

    /** §4.5's and §5.3's `nenya` row. §6: "A bid MUST carry ... `nenya`". */
    const val VERSION: String = "nenya"

    /** §5.3's `item` row, cardinality `0` on a public bid. */
    const val ITEM: String = "item"

    /** §5.3's `image` row, which carries §4.3's fifth resource bound. */
    const val IMAGE: String = "image"

    /**
     * §6's MUSTs, in the order [Bid.decode] checks them.
     *
     * The six scope tags come first because §6 states them first and because a bid that scopes to
     * nothing cannot be checked against anything; `price`, `t` and `nenya` follow. The order is
     * part of the contract only in that `BidCodecTest` removes each in turn and expects the
     * refusal to name that tag, which holds for any order provided each check names its own tag.
     */
    val REQUIRED: List<String> = listOf(
        ROOT_COORDINATE,
        PARENT_COORDINATE,
        ROOT_KIND,
        PARENT_KIND,
        ROOT_AUTHOR,
        PARENT_AUTHOR,
        PRICE,
        TOPIC,
        VERSION,
    )

    /** The two §6 forbids, checked before the coordinate tags so the refusal can name the reason. */
    val EVENT_ID_SCOPES: List<String> = listOf(ROOT_EVENT_ID, PARENT_EVENT_ID)

    /**
     * The scope tags whose second occurrence is a rejection.
     *
     * §5.3's table does not contain them — they are NIP-22's, and §4.3's duplicate rule is stated
     * over "any tag **this document** marks with cardinality `1` or `0–1`" — so the tag codec does
     * not reach them and this codec must. The rationale §4.3 gives applies unchanged and is the
     * reason: "First wins" and "last wins" are both defensible, which is exactly the problem,
     * because two conformant implementations would then disagree about which listing a signed bid
     * was scoped to.
     *
     * `p` is on the list although §5.3 gives it cardinality `0–n`, because that cardinality is a
     * **listing** rule and §6 states its own: `p` "MUST equal the listing author's pubkey", which a
     * second `p` naming somebody else contradicts. See [Bid] for the narrowing said out loud.
     *
     * A **byte-identical** repeat is refused too, and that is a stated choice rather than a side
     * effect. §4.3's rationale does not reach it — two implementations cannot disagree about which
     * listing a bid was scoped to when both occurrences say the same thing — but a codec that
     * de-duplicates has to decide *which* duplicates count as identical, and on a coordinate that
     * decision is §4.3's normalisation question all over again, one layer up and with no rule to
     * appeal to. Refusing is the answer that needs no such rule.
     */
    val SINGLE_OCCURRENCE: List<String> = listOf(
        ROOT_COORDINATE,
        PARENT_COORDINATE,
        ROOT_KIND,
        PARENT_KIND,
        ROOT_AUTHOR,
        PARENT_AUTHOR,
    )
}

/**
 * §5.3's and §4.3's refusals, mapped onto this package's vocabulary with the tag named and the
 * original kept.
 *
 * The tag name is *diagnosed* from [NenyaTags] rather than re-derived from a second copy of §5.3's
 * rules: the tag codec decided what was wrong, and this walks the same table to say which row it
 * was about. Two copies of a presence rule is how the two drift apart; one copy plus a diagnosis
 * cannot.
 *
 * Four branches are unreachable from [Bid.decode] and are mapped rather than dropped, because a
 * `when` over an enum is the one place a constant added to [TagRejection] later must not be able to
 * fall through to "malformed": §5.3's listing-only refusals (`MISSING_REQUIRED`, `MISSING_TOPIC`,
 * `CARDINALITY_TOO_FEW`) cannot fire under a `TagContext.publicBid()`, and `KIND_MISMATCH` cannot
 * fire because this decoder checks the kind itself before reading a tag.
 */
@JvmSynthetic
internal fun TagException.asBidRejection(tags: List<List<String>>): BidException {
    val counts = tags.map { it[0] }.groupingBy { it }.eachCount()
    val items = counts[BidVocabulary.ITEM] ?: 0
    return when (reason) {
        // §5.3's `item` row on a public bid, arriving by either of the two routes the tag codec
        // can refuse it on: cardinality `0` for the first one, and §4.3's duplicate clause — which
        // §5.3's own row extends to `item` in so many words — for the second.
        TagRejection.FORBIDDEN_TAG -> if (items > 0) doubleBinding(items, this) else malformed(this)

        TagRejection.DUPLICATE_TAG -> if (items > 1) {
            doubleBinding(items, this)
        } else {
            val duplicated = NenyaTags.ALL
                .firstOrNull { it.cardinality.singleOccurrence && (counts[it.name] ?: 0) > 1 }
            BidException(
                BidRejection.DUPLICATE_TAG,
                duplicated?.name,
                "§4.3 requires rejecting a bid carrying more than one ${describe(duplicated?.name)} " +
                    "rather than resolving it by taking the first, the last or the smallest",
                this,
            )
        }

        TagRejection.LIMIT_EXCEEDED -> BidException(
            BidRejection.LIMIT_EXCEEDED,
            BidVocabulary.IMAGE,
            "§4.3's fifth resource bound on `${BidVocabulary.IMAGE}` tags was exceeded; §4.3 " +
                "requires rejecting rather than truncating, and says the bound SHOULD be configurable",
            this,
        )

        TagRejection.UNSUPPORTED -> BidException(
            BidRejection.UNSUPPORTED,
            BidVocabulary.PRICE.takeIf { name ->
                tags.any { it[0] == name && it.size > PRICE_ELEMENTS }
            },
            "this bid uses a NIP-99 form Nenya v1 does not implement; §4.4 requires it be treated " +
                "as unsupported on read rather than as malformed",
            this,
        )

        TagRejection.KIND_MISMATCH, TagRejection.MALFORMED_CONTEXT -> BidException(
            BidRejection.NOT_A_BID_KIND,
            null,
            "§6's bid rules were applied to an event that is not a public bid",
            this,
        )

        TagRejection.MISSING_REQUIRED, TagRejection.MISSING_TOPIC, TagRejection.CARDINALITY_TOO_FEW ->
            BidException(
                BidRejection.MISSING_REQUIRED_TAG,
                null,
                "a §5.3 presence rule refused this bid as ${reason.name}; those columns are listing " +
                    "rules and §5.3 forbids applying them to a bid, so the tag-layer reason is on " +
                    "the cause and §6's own required set is what this decoder enforces",
                this,
            )

        else -> malformed(this)
    }
}

/** §5.3's `item` row on a public bid, refused for the reason §5.3 gives rather than as a shrug. */
private fun doubleBinding(count: Int, cause: TagException): BidException = BidException(
    BidRejection.DOUBLE_BINDING,
    BidVocabulary.ITEM,
    "§5.3 gives `${BidVocabulary.ITEM}` cardinality 0 on a public bid, where " +
        "`${BidVocabulary.ROOT_COORDINATE}`/`${BidVocabulary.PARENT_COORDINATE}` already carry the " +
        "binding and a second binding that could disagree with the first is precisely what §4.2 " +
        "exists to prevent; this bid carries $count",
    cause,
)

private fun malformed(cause: TagException): BidException = BidException(
    BidRejection.MALFORMED_TAG,
    null,
    "§5.3's Encoding column refused a value on this bid as ${cause.reason.name}; the tag-layer " +
        "reason is on the cause",
    cause,
)

/**
 * A tag name for a message, or an honest phrase when the diagnosis found none.
 *
 * Never interpolates a `null` into a sentence. A message reading "more than one `null`" is what a
 * caller sees when a diagnosis cannot find its row, and the event that triggers it is chosen by a
 * stranger. The reason constant and the `tag` field carry the machine-readable answer; this is only
 * wording.
 */
private fun describe(name: String?): String = if (name == null) "a tag §5.3 names" else "`$name`"

/** §4.4's `["price", "<sats>", "SAT"]`; a fourth element is NIP-99's recurring form. */
private const val PRICE_ELEMENTS: Int = 3
