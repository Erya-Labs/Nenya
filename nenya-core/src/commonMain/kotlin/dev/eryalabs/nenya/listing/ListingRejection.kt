package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.TagCardinality
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.TagRequirement
import kotlin.jvm.JvmSynthetic

/**
 * Why a listing was refused (§5.1, §5.2, §5.3, §5.6).
 *
 * The reason is part of the API and not merely diagnostic text, following the convention every
 * other package in this library already uses — `MoneyRejection`, `PaymentRejection`,
 * `DeliveryRejection`, `SeamRejection`, `OrderStateRejection`, `WireRejection`, `TagRejection`.
 * Tests assert on these constants rather than on wording.
 *
 * Two of them exist because §5.3 gives a *reason* and not merely a verdict, and an implementer
 * who collapses them tells the user something false: [SELF_REFERENCE] is not [FORBIDDEN_TAG] and
 * neither is "unknown tag". A client that displays "this listing carries a tag I do not
 * understand" for an `item` tag has hidden the fact that somebody published a listing that
 * references a listing.
 */
public enum class ListingRejection {

    /**
     * The event is not a `kind:30402`, `kind:30403` or `kind:30404` (§5.1, §5.2).
     *
     * §5.3's Card. and Requirement columns are listing rules and MUST NOT be applied elsewhere,
     * so this decoder refuses rather than quietly applying them to a bid or an order rumor.
     */
    NOT_A_LISTING_KIND,

    /** §5.3 marks a tag MUST for this listing kind and the event carries none. See [ListingException.tag]. */
    MISSING_REQUIRED_TAG,

    /** §5.3's `≥2` cardinality: the listing carries fewer occurrences than the table requires. */
    TOO_FEW_OCCURRENCES,

    /** §5.3: the `t` tags do not include `nenya` and exactly one of `wtb` / `wts`. */
    MISSING_TOPIC,

    /**
     * The side token disagrees with the kind: §5.2 makes `["t", "wtb"]` REQUIRED on a request and
     * §5.1 makes `["t", "wts"]` REQUIRED on an offer.
     *
     * §5.3's own row requires only *exactly one* of the pair, which the tag codec enforces without
     * knowing which one belongs here; the kind-specific half is §5.1's and §5.2's and is therefore
     * this package's. A `kind:30404` advertising itself as want-to-sell is not a side this
     * document defines, and §5.5's discovery filters would put it on the wrong half of the board.
     */
    SIDE_MISMATCH,

    /** §5.3 marks a tag MUST NOT on a listing: `g` or `location`, a location leak with no function. */
    FORBIDDEN_TAG,

    /**
     * An `item` tag on a listing — §5.3: "a listing cannot reference itself".
     *
     * Its own constant rather than [FORBIDDEN_TAG] or an unknown-tag shrug, because the three
     * mean different things to whoever reads the refusal. `g` is a privacy rule about the
     * publisher; this is a structural one about what a listing *is*, and §5.3 states it in those
     * words.
     */
    SELF_REFERENCE,

    /** §4.3: a tag §5.3 marks cardinality `1` or `0–1` appears more than once. Never resolved. */
    DUPLICATE_TAG,

    /**
     * One of §5.2's request `status` tokens on an **offer**, or a status a kind does not permit
     * handed to the writer (§5.1, §5.2).
     *
     * `awarded`, `fulfilled` and `cancelled` are the request extension §5.2 calls "legitimate only
     * because the kind is Nenya's own", and §5.1 forbids the same extension on a `kind:30402`: an
     * offer's `status` "MUST be either `active` or `sold`, and nothing else", because a third value
     * makes the event non-conformant in every existing NIP-99 client.
     *
     * Distinct from [ListingStatus.UNKNOWN] on purpose, and the line between them is narrow on
     * purpose too. A token **no** section of this document defines is §5.2's *unknown* treatment on
     * either kind — a fact about a peer speaking a dialect, not a conformance failure. This
     * constant is for a token the document **does** define, used on the one kind §5.1 closes
     * against it. Reading a request's `["status", "sold"]` this way would be the symmetric rule,
     * and §5.2 does not state it — see `ListingStatusCodec`.
     */
    STATUS_OUTSIDE_VOCABULARY,

    /** [ListingStatus.UNKNOWN] handed to a writer: §5.2's `unknown` is a treatment, not a token. */
    UNKNOWN_IS_NOT_EMITTABLE,

    /**
     * §4.3's fifth resource bound — more `image` tags than `TagLimits.maxImageTags` — exceeded.
     *
     * Its own constant rather than [MALFORMED_TAG], because the two have different fixes and §4.3
     * says so: the bounds SHOULD be configurable, so a listing refused for carrying 70 images is a
     * caller that may want to raise its bound, and a listing refused for carrying a `data:` URI is
     * a peer that broke a MUST. Neither is truncated.
     */
    LIMIT_EXCEEDED,

    /**
     * A form §4.4 requires be treated as **unsupported on read** rather than as malformed: the
     * optional NIP-99 `<frequency>` fourth element of `price`, which Nenya v1 MUST NOT emit.
     *
     * Carried up from `TagRejection.UNSUPPORTED` rather than flattened into [MALFORMED_TAG],
     * because the distinction is the whole reason T9 has the constant: a recurring-price listing
     * is a well-formed NIP-99 event this version of Nenya does not implement, and reporting it as
     * malformed tells the user a conformant client is broken.
     */
    UNSUPPORTED,

    /**
     * §5.3's Encoding column refused a value — a malformed `price`, a `data:` URI in an `image`,
     * a coordinate that does not parse.
     *
     * The precise §5.3 reason is **not** lost: it is the [TagRejection] on the `cause`, which is
     * the [TagException] the tag codec threw. See [ListingException].
     */
    MALFORMED_TAG,
}

/**
 * A listing was refused, carrying the [reason] and the [tag] it is about as data.
 *
 * One exception type for the whole listing surface, as every other package in this library does:
 * a caller has one thing to catch and one field to branch on.
 *
 * ### Why the tag name is a field rather than a sentence
 *
 * §5.3's required set is data — it is derived from the table, and `ListingCodecTest` walks it,
 * removing each REQUIRED tag in turn and asserting the refusal names *that* tag. A message a test
 * has to pattern-match would make that proof a string comparison against the wording of the
 * message the same implementer wrote; a field makes it a fact. It also means a §5.3 revision that
 * T9's vocabulary picks up cannot leave this codec silently stale, because the loop is over the
 * derived set rather than over a list here.
 *
 * ### The §5.3 reason survives, on the cause
 *
 * Every refusal the tag codec raises is mapped onto this vocabulary with the original
 * [TagException] attached as the cause, exactly as `MoneyException.asTagRejection` maps §4.4's
 * refusals one layer down. Nothing is flattened away: a caller that wants [TagRejection] still
 * has it.
 *
 * **The message never echoes the caller's input.** A listing is public, but this decoder's
 * messages are held to the same rule as every other in this library (§12, STOP RULE 14): a tag
 * *name*, a *count* and a *reason* may appear; a `d` value, a pubkey, a coordinate or an
 * arbitrary tag value may not.
 */
public class ListingException internal constructor(
    public val reason: ListingRejection,

    /** The §5.3 tag this refusal is about, or `null` where the rule names no single tag. */
    public val tag: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * §5.3's Requirement column for one listing kind, **derived** rather than transcribed.
 *
 * Both halves read [NenyaTags], which `TagVocabularyTest` holds equal to the table parsed out of
 * §5.3 at test time. So a row added to the specification reaches this codec's required set
 * without anybody editing this package, and a row whose Requirement changed changes it here too.
 */
internal object ListingVocabulary {

    /** The tags §5.3 marks MUST on [kind] — for a request that includes `alt`, for an offer not. */
    fun required(kind: Int): List<String> = named(kind, TagRequirement.MUST)

    /** The tags §5.3 marks MUST NOT on a listing: `item`, `g`, `location`. */
    fun forbidden(kind: Int): List<String> = named(kind, TagRequirement.MUST_NOT)

    private fun named(kind: Int, requirement: TagRequirement): List<String> {
        val context = TagContext.listing(kind)
        return NenyaTags.ALL.filter { it.requirement.on(context) == requirement }.map { it.name }
    }
}

/**
 * §5.3's refusals, mapped onto this package's vocabulary with the tag named and the original kept.
 *
 * The tag name is *diagnosed* from [NenyaTags] rather than re-derived from a second copy of
 * §5.3's rules: the tag codec decided what was wrong, and this walks the same table in the same
 * order to say which row it was about. Two copies of a presence rule is how the two drift apart;
 * one copy plus a diagnosis cannot.
 */
@JvmSynthetic
internal fun TagException.asListingRejection(tags: List<List<String>>, kind: Int): ListingException {
    val names = tags.map { it[0] }
    val counts = names.groupingBy { it }.eachCount()
    return when (reason) {
        TagRejection.MISSING_REQUIRED -> {
            val missing = ListingVocabulary.required(kind).firstOrNull { it !in names }
            ListingException(
                ListingRejection.MISSING_REQUIRED_TAG,
                missing,
                "§5.3 marks ${describe(missing)} MUST on a kind:$kind listing and this event " +
                    "carries none",
                this,
            )
        }

        TagRejection.CARDINALITY_TOO_FEW -> {
            val short = NenyaTags.ALL
                .firstOrNull { it.cardinality == TagCardinality.TWO_OR_MORE && (counts[it.name] ?: 0) < 2 }
                ?.name
            ListingException(
                ListingRejection.TOO_FEW_OCCURRENCES,
                short,
                "§5.3 marks ${describe(short)} cardinality ${TagCardinality.TWO_OR_MORE.token} on " +
                    "a listing and this event carries ${counts[short] ?: 0}",
                this,
            )
        }

        TagRejection.MISSING_TOPIC -> ListingException(
            ListingRejection.MISSING_TOPIC,
            TOPIC_TAG,
            "§5.3 requires a listing's `$TOPIC_TAG` tags to include `nenya` and exactly one of " +
                "`wtb` / `wts`",
            this,
        )

        TagRejection.FORBIDDEN_TAG -> {
            val present = ListingVocabulary.forbidden(kind).firstOrNull { it in names }
            if (present == ITEM_TAG) {
                selfReference(kind, this)
            } else {
                ListingException(
                    ListingRejection.FORBIDDEN_TAG,
                    present,
                    "§5.3 marks ${describe(present)} MUST NOT on a listing: a geohash on a Nenya " +
                        "listing is a location leak with no compensating function",
                    this,
                )
            }
        }

        // §4.3's duplicate rule reaches `item` as well, although its cardinality is `0` rather
        // than `1` or `0–1`: §5.3's own row says "more than one is a rejection everywhere", and
        // the tag codec carries an explicit clause for it that fires **before** the listing rules.
        // So a listing carrying two `item` tags arrives here as a duplicate rather than as a
        // forbidden tag, and a diagnosis that looked only at single-occurrence rows would name no
        // tag at all — which is how the stranger who published the event gets to choose whether
        // this library can say what was wrong with it.
        TagRejection.DUPLICATE_TAG -> if ((counts[ITEM_TAG] ?: 0) > 1) {
            selfReference(kind, this)
        } else {
            val duplicated = NenyaTags.ALL
                .firstOrNull { it.cardinality.singleOccurrence && (counts[it.name] ?: 0) > 1 }
            ListingException(
                ListingRejection.DUPLICATE_TAG,
                duplicated?.name,
                "§4.3 requires rejecting a listing carrying more than one ${describe(duplicated?.name)} " +
                    "rather than resolving it by taking the first, the last or the smallest",
                this,
            )
        }

        TagRejection.LIMIT_EXCEEDED -> ListingException(
            ListingRejection.LIMIT_EXCEEDED,
            IMAGE_TAG,
            "§4.3's fifth resource bound on `$IMAGE_TAG` tags was exceeded; §4.3 requires " +
                "rejecting rather than truncating, and says the bound SHOULD be configurable",
            this,
        )

        // The context this package builds always names the event's own kind and always names a
        // listing kind, so neither of these is reachable from `Listing.decode`. They are mapped
        // rather than dropped because a `when` over an enum is the one place a constant added to
        // `TagRejection` later must not be able to fall through to "malformed".
        TagRejection.KIND_MISMATCH, TagRejection.MALFORMED_CONTEXT -> ListingException(
            ListingRejection.NOT_A_LISTING_KIND,
            null,
            "§5.3's listing rules were applied to kind:$kind, which is not one of §5.1's or " +
                "§5.2's listing kinds",
            this,
        )

        // §4.4's third answer, which is neither "fine" nor "malformed": the optional NIP-99
        // `<frequency>` element of `price`, which Nenya v1 MUST NOT emit and MUST treat as
        // **unsupported** on read. Reporting it as malformed would make a conformant NIP-99
        // client look broken, which is the failure T9 built the constant to avoid — and flattening
        // it here would undo that one layer up. It is the only source of this reason in §5.3's
        // Encoding column today, so the row is named when the event carries the shape that
        // produces it and left unnamed when it does not.
        TagRejection.UNSUPPORTED -> ListingException(
            ListingRejection.UNSUPPORTED,
            PRICE_TAG.takeIf { name -> tags.any { it[0] == name && it.size > PRICE_ELEMENTS } },
            "this kind:$kind listing uses a NIP-99 form Nenya v1 does not implement; §4.4 requires " +
                "it be treated as unsupported on read rather than as malformed",
            this,
        )

        else -> ListingException(
            ListingRejection.MALFORMED_TAG,
            null,
            "§5.3's Encoding column refused a value on this kind:$kind listing as ${reason.name}; " +
                "the tag-layer reason is on the cause",
            this,
        )
    }
}

/** §5.3's `item` row, refused for the reason §5.3 gives rather than as a tag nobody recognised. */
private fun selfReference(kind: Int, cause: TagException): ListingException = ListingException(
    ListingRejection.SELF_REFERENCE,
    ITEM_TAG,
    "§5.3: `$ITEM_TAG` is not a listing tag, because a listing cannot reference itself, and an " +
        "implementation MUST reject one on a kind:$kind event",
    cause,
)

/**
 * A tag name for a message, or an honest phrase when the diagnosis found none.
 *
 * Never interpolates a `null` into a sentence. Every branch above diagnoses its own row and each
 * is expected to find one — but a message reading "more than one `null`" is what a caller sees
 * when one of them cannot, and the event that triggers it is chosen by a stranger. The reason
 * constant and the `tag` field carry the machine-readable answer; this is only wording.
 */
private fun describe(name: String?): String = if (name == null) "a tag §5.3 names" else "`$name`"

/** §5.3's topic row, named once so the diagnosis and the side check cannot disagree about it. */
internal const val TOPIC_TAG: String = "t"

/** §5.3's `item` row — the one whose refusal has its own reason. */
internal const val ITEM_TAG: String = "item"

/** §5.3's `image` row, which carries §4.3's fifth resource bound. */
internal const val IMAGE_TAG: String = "image"

/** §5.3's `price` row — the one §4.4 gives an unsupported-on-read form. */
internal const val PRICE_TAG: String = "price"

/** §4.4's `["price", "<sats>", "SAT"]`; a fourth element is NIP-99's recurring form. */
internal const val PRICE_ELEMENTS: Int = 3
