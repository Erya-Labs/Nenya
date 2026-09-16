package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import kotlin.jvm.JvmSynthetic

/**
 * Why a rumor was refused (§7.2 and §7.4, and §4.3, §4.5 and §5.3 reached through them).
 *
 * The reason is part of the API and not merely diagnostic text, following the convention every
 * other package in this library already uses — `MoneyRejection`, `PaymentRejection`,
 * `DeliveryRejection`, `SeamRejection`, `OrderStateRejection`, `WireRejection`, `TagRejection`,
 * `ListingRejection`, `BidRejection`. Tests assert on these constants rather than on wording.
 *
 * Several exist because §7.2 and §7.4 give a *reason* and not merely a verdict, and an implementer
 * who collapses them tells the user something false. [IMPERSONATION] is the sharpest: a rumor whose
 * claimed `pubkey` is not the seal's is not a malformed event, it is somebody writing another
 * person's key into a message they sealed with their own — the single failure §7.2 exists to
 * prevent — and reporting it as a bad tag sends whoever reads the refusal looking for the wrong bug
 * in the wrong implementation.
 */
public enum class ChannelRejection {

    /**
     * §7.2's rule: "Implementations MUST verify that the `pubkey` of the `kind:13` seal equals the
     * `pubkey` of the rumor inside it, and MUST discard the message otherwise."
     *
     * Discarded, not repaired and not attributed to the seal's key anyway: "Without this check any
     * sender can impersonate any other by writing someone else's pubkey into the rumor." No
     * [AttributedRumor] is produced, so nothing downstream can read the message at all.
     */
    IMPERSONATION,

    /**
     * The seal pubkey handed in is not 64 hex characters (§4.3).
     *
     * Its own constant rather than [MALFORMED_TAG]: this one is the *caller's* argument, not
     * something a counterparty wrote, and the fix is in the client's gift-wrap code rather than in
     * the peer's.
     */
    MALFORMED_SEAL_PUBKEY,

    /** The event is not one of §7.4's four rumor kinds — `14`, `15`, `16` or `17`. */
    NOT_A_RUMOR_KIND,

    /** §7.4 marks a tag MUST on this rumor kind and the event carries none. See [ChannelException.tag]. */
    MISSING_REQUIRED_TAG,

    /**
     * §7.4's single exception, from the other side: a `kind:16` `type=6` carrying an `order` tag.
     *
     * Its own constant, because §7.4 states the *reason* — "an order id does not exist until the
     * buyer proposes" — and a private bid that names an order id is a peer claiming a binding that
     * cannot exist yet, not a peer with one tag too many.
     */
    FORBIDDEN_ORDER_TAG,

    /**
     * A second `order` or `type` tag.
     *
     * §5.3's table does not contain either, so §4.3's duplicate rule — stated over "any tag **this
     * document** marks with cardinality `1` or `0–1`" — does not literally reach them. The
     * rationale it gives does, unchanged: "First wins" and "last wins" are both defensible, which
     * is exactly the problem, because two implementations would then disagree about which order a
     * signed rumor was bound to, or about what kind of message it was. Same reading, and the same
     * refusal, as `BidRejection.DUPLICATE_TAG` over NIP-22's scope tags.
     */
    DUPLICATE_TAG,

    /**
     * A `type` value that is not §4.4's canonical decimal, or is below the first value §7.4's table
     * assigns.
     *
     * `"01"`, `"+1"` and `"1.0"` are refused rather than read as `1`: §4.3 requires rejecting a
     * malformed value rather than repairing it, and a `type` read permissively is a discriminator
     * two implementations can disagree about.
     */
    MALFORMED_TYPE,

    /**
     * §7.4's collision rule: a `type=5` or `type=6` carrying a `nenya` version this implementation
     * does not implement.
     *
     * §7.4: those two "are Nenya's own assignments in a vocabulary Nenya does not own, so a future
     * GammaMarkets `type=5` or `type=6` could collide. The `["nenya", "1"]` tag is what
     * disambiguates: an implementation MUST ignore a `type=5` or `type=6` message that does not
     * carry a `nenya` version it implements, and MUST NOT interpret a foreign one as a delivery
     * commitment or a bid."
     *
     * Deliberately **not** [MISSING_REQUIRED_TAG]. A rumor with no `nenya` tag at all is already
     * refused by §7.4's required set; this is the different case of a version tag that is present
     * and names a document this library has not implemented, and the two refusals send a reader to
     * different places.
     */
    UNSUPPORTED_VERSION,

    /** An `order` value whose character count is not 64 (§4.3, §7.4). Never padded, never truncated. */
    ORDER_ID_WRONG_LENGTH,

    /** An `order` value carrying a character outside `0-9`, `a-f`, `A-F` (§4.3). */
    ORDER_ID_NOT_HEX,

    /** §7.4 reserves `type=4` for GammaMarkets shipping: Nenya v1 MUST NOT emit it. */
    RESERVED_TYPE,

    /**
     * §7.5's decoder was handed a rumor that is not a `kind:16` `type=1`.
     *
     * The caller's mistake rather than the peer's, and its own constant for the reason
     * [MALFORMED_SEAL_PUBKEY] has one: a `type=2` is a perfectly well-formed message that this
     * entry point has nothing to say about, and reporting it as malformed would send a reader
     * looking for a bug in the sender's implementation.
     */
    NOT_A_PROPOSAL,

    /** §7.6's decoder was handed a rumor that is not a `kind:16` `type=3`. See [NOT_A_PROPOSAL]. */
    NOT_A_STATUS_UPDATE,

    /**
     * An `amount_msat` or `amount` value that is not §4.4's canonical decimal.
     *
     * Strict rather than permissive, and that is §7.6's byte-identity rule reaching down into the
     * parse: `["amount_msat", "090000000"]` and `["amount_msat", "90000000"]` are not
     * byte-identical, so a codec that read the first as the second would call a counter-proposal an
     * acceptance. §4.3 requires rejecting a malformed value rather than repairing it.
     */
    MALFORMED_AMOUNT,

    /**
     * An `amount_msat` or `amount` above §4.4's supply cap.
     *
     * Its own constant rather than [MALFORMED_AMOUNT], and the one that matters on the `amount`
     * side: §7.5's cross-check is `amount × 1000 = amount_msat`, a multiplication on a number a
     * stranger chose. `Long` multiplication wraps silently onto a *positive* value, and because
     * 1000 does not divide 2⁶⁴ evenly a crafted `amount` can be congruent to a legitimate
     * `amount_msat` and compare **equal**, walking straight through the one clause §7.5 wrote to
     * prevent a 1000× disagreement. `Msat.ofSat` bounds before it multiplies, so the crafted value
     * is refused here instead — which is why the reason exists and is asserted by name.
     */
    AMOUNT_ABOVE_SUPPLY,

    /**
     * §7.5's `amount × 1000 ≠ amount_msat`: "the implementation MUST reject the message. It MUST
     * NOT prefer one and continue."
     *
     * The clause an implementer resolves by taking the more specific field and shipping a silent
     * 1000× disagreement with its peer, so the refusal is named rather than folded into
     * [MALFORMED_AMOUNT].
     */
    AMOUNT_DISAGREEMENT,

    /**
     * §7.5's two deadlines, inverted: `expiration` at or after `deliver_by`.
     *
     * §7.5 requires `expiration` be **strictly** earlier when both are present and requires the
     * proposal be rejected otherwise — "an order that can still be accepted after its own delivery
     * deadline has passed is incoherent, and it puts the provider in a state where acceptance and
     * `expired` are simultaneously correct".
     *
     * Refused **here**, before [dev.eryalabs.nenya.order.OrderTerms] is constructed, so the answer
     * a caller gets is this package's named rejection and never the `OrderStateException` that
     * type's `init` throws for the same reason one layer down.
     */
    DEADLINES_INVERTED,

    /**
     * A `type=3` naming a different `order` id than the proposal it was offered against.
     *
     * Not a counter-proposal: §7.6's counter-proposal is a message *about this order* carrying
     * altered terms, and the correct response to it is a new `type=1` with a new order id. A status
     * update about some other order is not about this one at all, and answering "counter-proposal"
     * would tell the caller to re-propose on the strength of a message it must ignore.
     */
    DIFFERENT_ORDER,

    /** §7.4's unknown-`type` sink is a treatment of somebody else's value, so it has no wire form. */
    UNKNOWN_IS_NOT_EMITTABLE,

    /**
     * §4.3's fifth resource bound — more `image` tags than `TagLimits.maxImageTags` — exceeded.
     *
     * Its own constant rather than [MALFORMED_TAG], for the reason `BidRejection` gives it one:
     * §4.3 says the bounds SHOULD be configurable, so a rumor refused for carrying 70 images is a
     * caller that may want to raise its bound. Neither is truncated.
     */
    LIMIT_EXCEEDED,

    /**
     * A form §4.4 requires be treated as **unsupported on read** rather than as malformed: the
     * optional NIP-99 `<frequency>` fourth element of `price`, which Nenya v1 MUST NOT emit.
     *
     * Carried up from `TagRejection.UNSUPPORTED` rather than flattened, for the reason T9 gave the
     * constant: reporting a well-formed NIP-99 form this version does not implement as malformed
     * tells the user a conformant client is broken.
     */
    UNSUPPORTED,

    /**
     * §5.3's Encoding column refused a value — a malformed `p`, an `item` coordinate that does not
     * parse, a `fee` of the wrong arity.
     *
     * The precise §5.3 reason is **not** lost: it is the [TagRejection] on the `cause`, which is
     * the [TagException] the tag codec threw.
     */
    MALFORMED_TAG,
}

/**
 * A rumor was refused, carrying the [reason] and the [tag] it is about as data.
 *
 * One exception type for the whole channel surface, as every other package in this library does: a
 * caller has one thing to catch and one field to branch on.
 *
 * ### Why the tag name is a field rather than a sentence
 *
 * §7.4's required set is data — [ChannelVocabulary.REQUIRED] publishes it and `ChannelCodecTest`
 * walks it, removing each tag in turn and asserting the refusal names *that* tag. A message a test
 * has to pattern-match would make that proof a string comparison against the wording of the message
 * the same implementer wrote; a field makes it a fact.
 *
 * **The message never echoes the caller's input**, and here that is §12 rather than convention: a
 * rumor never reaches a relay, its `p` tag is the counterparty's pubkey and its `order` tag is the
 * order id, and §12 item 11 forbids both from appearing in a log or a crash report. A message may
 * name a tag *name*, a *kind*, a *count*, a *length* and a *reason*; it may never name a pubkey, an
 * order id or a byte of `content`.
 */
public class ChannelException internal constructor(
    public val reason: ChannelRejection,

    /** The §7.4 or §5.3 tag this refusal is about, or `null` where the rule names no single tag. */
    public val tag: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * §7.4's required set, which is **§7.4's own and not §5.3's** — and that is the point of the object.
 *
 * `ListingVocabulary` derives its required set from §5.3's Requirement column, because §5.3 states
 * the listing rules. This one cannot and MUST not: §5.3 says its Card. and Requirement columns
 * "MUST NOT be applied to a bid or to an order rumor, which carry their own required sets (§6,
 * §6.1, §7.4, §7.5)". §7.4 states this one in a fenced block, which the tests parse out of the
 * document and hold **equal** to [REQUIRED] — so the three names below are anchored to the
 * specification rather than to the hand that typed them.
 */
internal object ChannelVocabulary {

    /** §4.5's and §5.3's `nenya` row. §7.4: every `kind:15`, `kind:16` and `kind:17` carries it. */
    const val VERSION: String = "nenya"

    /** §5.3's `p` row — §7.4's counterparty pubkey. Likewise required. */
    const val COUNTERPARTY: String = "p"

    /** §7.4's own `order` row, which is not in §5.3's table. Required except on a `type=6`. */
    const val ORDER: String = ChannelTags.ORDER

    /** §7.4's own `type` row. REQUIRED on a `kind:16` and on nothing else. */
    const val TYPE: String = ChannelTags.TYPE

    /** §7.5's own `amount_msat` row, which is not in §5.3's table. REQUIRED on a `type=1`. */
    const val AMOUNT_MSAT: String = ChannelTags.AMOUNT_MSAT

    /** §7.5's GammaMarkets compatibility `amount` row, in satoshis. A MAY, and not in §5.3's table. */
    const val AMOUNT: String = ChannelTags.AMOUNT

    /** §5.3's `image` row, which carries §4.3's fifth resource bound. */
    const val IMAGE: String = "image"

    /** §5.3's `price` row, whose fourth element is NIP-99's recurring form. */
    const val PRICE: String = "price"

    /** §5.3's `item` row — §7.5's listing coordinate and quantity. REQUIRED on a `type=1`. */
    const val ITEM: String = "item"

    /** §5.3's and §8.1's `fee` row. A MAY on a proposal, and §8.1's read rule governs its absence. */
    const val FEE: String = "fee"

    /** §5.3's `deliver_by` row — §7.5's deadline for **release**. */
    const val DELIVER_BY: String = "deliver_by"

    /** §5.3's `expiration` row — §7.5's deadline for **acceptance**, a different deadline. */
    const val EXPIRATION: String = "expiration"

    /** §5.3's `status` row, whose vocabulary on a `type=3` is §11.1's and never §5.2's. */
    const val STATUS: String = "status"

    /**
     * NIP-17's `subject`, which §7.4 makes display metadata on any rumor and forbids deriving any
     * term or state from.
     *
     * A name and nothing more: it is in this package's readable set because §7.4 says it MAY appear
     * on any rumor and §4.3 requires it round-trip, and there is deliberately no accessor anywhere
     * in this package that hands a caller its value as a decoded term. `ChannelStructureTest`
     * asserts that by reflection over every published member's name.
     */
    const val SUBJECT: String = "subject"

    /**
     * §7.4's fenced required-tags block, in the order it prints them.
     *
     * Held **equal** to the tag names parsed out of that block at test time, with its `#` comments
     * stripped — equal in both directions, never containment, because containment passes over a
     * model carrying two of the three.
     */
    val REQUIRED: List<String> = listOf(VERSION, COUNTERPARTY, ORDER)

    /**
     * The tags whose second occurrence is a rejection here rather than in the tag codec.
     *
     * §5.3's table contains neither, so §4.3's duplicate rule does not reach them and this codec
     * must. See [ChannelRejection.DUPLICATE_TAG] for the rationale, which is §4.3's own.
     */
    val SINGLE_OCCURRENCE: List<String> = listOf(ORDER, TYPE)
}

/**
 * §5.3's and §4.3's refusals, mapped onto this package's vocabulary with the tag named and the
 * original kept.
 *
 * The tag name is *diagnosed* from [NenyaTags] rather than re-derived from a second copy of §5.3's
 * rules: the tag codec decided what was wrong, and this walks the same table to say which row it
 * was about. Two copies of a presence rule is how the two drift apart; one copy plus a diagnosis
 * cannot. Same shape as `TagException.asBidRejection` one package over.
 *
 * `MISSING_TOPIC` and `CARDINALITY_TOO_FEW` are §5.3 listing-only refusals that cannot fire under a
 * rumor context, and `KIND_MISMATCH` cannot fire because this decoder checks the kind itself before
 * reading a tag. They are mapped rather than dropped, because a `when` over an enum is the one
 * place a constant added to [TagRejection] later must not be able to fall through to "malformed".
 */
@JvmSynthetic
internal fun TagException.asChannelRejection(tags: List<List<String>>): ChannelException {
    // Counted with a plain loop rather than `groupingBy`, which Kotlin compiles to an extra
    // JVM-public class in this package; `ChannelStructureTest` enumerates the package's published
    // surface by name, and a compiler-generated `keyOf(String)` in it is a member nobody wrote
    // showing up in a list whose job is to make a human look at every new one.
    val counts = mutableMapOf<String, Int>()
    for (tag in tags) counts[tag[0]] = (counts[tag[0]] ?: 0) + 1
    return when (reason) {
        TagRejection.DUPLICATE_TAG -> {
            val duplicated = NenyaTags.ALL
                .firstOrNull { it.cardinality.singleOccurrence && (counts[it.name] ?: 0) > 1 }
                ?: NenyaTags.ALL.firstOrNull { it.name == "item" && (counts[it.name] ?: 0) > 1 }
            ChannelException(
                ChannelRejection.DUPLICATE_TAG,
                duplicated?.name,
                "§4.3 requires rejecting a rumor carrying more than one ${describe(duplicated?.name)} " +
                    "rather than resolving it by taking the first, the last or the smallest",
                this,
            )
        }

        TagRejection.LIMIT_EXCEEDED -> ChannelException(
            ChannelRejection.LIMIT_EXCEEDED,
            ChannelVocabulary.IMAGE,
            "§4.3's fifth resource bound on `${ChannelVocabulary.IMAGE}` tags was exceeded; §4.3 " +
                "requires rejecting rather than truncating, and says the bound SHOULD be configurable",
            this,
        )

        TagRejection.UNSUPPORTED -> ChannelException(
            ChannelRejection.UNSUPPORTED,
            ChannelVocabulary.PRICE.takeIf { name ->
                tags.any { it[0] == name && it.size > PRICE_ELEMENTS }
            },
            "this rumor uses a NIP-99 form Nenya v1 does not implement; §4.4 requires it be treated " +
                "as unsupported on read rather than as malformed",
            this,
        )

        // §5.3's `item` row states its own cardinality by context and §7.5 and §6.1 are where it is
        // exactly one; the tag codec enforces that under the context this decoder declares.
        TagRejection.MISSING_REQUIRED, TagRejection.FORBIDDEN_TAG -> ChannelException(
            ChannelRejection.MISSING_REQUIRED_TAG,
            NenyaTags.ALL.firstOrNull { it.name == "item" }?.name,
            "a §5.3 presence rule refused this rumor as ${reason.name}; the tag-layer reason is on " +
                "the cause and §7.4's own required set is what this decoder enforces",
            this,
        )

        TagRejection.KIND_MISMATCH, TagRejection.MALFORMED_CONTEXT -> ChannelException(
            ChannelRejection.NOT_A_RUMOR_KIND,
            null,
            "§7.4's rumor rules were applied to an event that is not one of its four kinds",
            this,
        )

        TagRejection.MISSING_TOPIC, TagRejection.CARDINALITY_TOO_FEW -> ChannelException(
            ChannelRejection.MISSING_REQUIRED_TAG,
            null,
            "a §5.3 listing presence rule refused this rumor as ${reason.name}; §5.3 forbids " +
                "applying those columns to an order rumor, so the tag-layer reason is on the cause",
            this,
        )

        else -> ChannelException(
            ChannelRejection.MALFORMED_TAG,
            null,
            "§5.3's Encoding column refused a value on this rumor as ${reason.name}; the tag-layer " +
                "reason is on the cause",
            this,
        )
    }
}

/**
 * A tag name for a message, or an honest phrase when the diagnosis found none.
 *
 * Never interpolates a `null` into a sentence: a message reading "more than one `null`" is what a
 * caller sees when a diagnosis cannot find its row, and the event that triggers it is chosen by a
 * stranger. The reason constant and the `tag` field carry the machine-readable answer.
 */
private fun describe(name: String?): String = if (name == null) "a tag §5.3 names" else "`$name`"

/** §4.4's `["price", "<sats>", "SAT"]`; a fourth element is NIP-99's recurring form. */
private const val PRICE_ELEMENTS: Int = 3
