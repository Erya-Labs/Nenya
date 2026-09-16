package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.MoneyRejection

/**
 * Why a tag, a tag value or a tag set was refused (§4.3, §4.4, §5.3, §8.1).
 *
 * The reason is part of the API and not merely diagnostic text, exactly as for `MoneyRejection`,
 * `PaymentRejection`, `DeliveryRejection`, `SeamRejection`, `OrderStateRejection` and
 * `WireRejection`. §8.1 makes that concrete in a way no other section does: a client refusing a
 * legal-but-large fee under its own policy MUST NOT report it as a malformed fee tag, "or any
 * other reason that would make a legal peer look broken". A caller that cannot tell *which* rule
 * refused the event cannot honour that. Tests assert on these constants rather than on wording.
 */
public enum class TagRejection {

    /** A [TagContext] that does not describe any event §5.3, §6 or §7.4 defines. */
    MALFORMED_CONTEXT,

    /**
     * The context declares one kind and the event carries another. §5.3's cardinality and
     * requirement rules are a function of the kind, so reading an event under the wrong one
     * silently applies the wrong rule set — the failure this check exists to make loud.
     */
    KIND_MISMATCH,

    /**
     * §4.3: a tag §5.3 marks with cardinality `1` or `0–1` appears more than once.
     *
     * Rejected, never "resolved" by taking the first, the last or the smallest. §4.3 gives the
     * reason: "First wins" and "last wins" are both defensible, which is exactly the problem —
     * two conformant implementations would then disagree about the price of the same signed
     * event.
     */
    DUPLICATE_TAG,

    /** A listing is missing a tag §5.3's Requirement column marks MUST for its kind. */
    MISSING_REQUIRED,

    /**
     * A tag §5.3 marks Card. `0` for this context is present: `g` or `location` on a listing
     * (a location leak with no compensating function), or `item` where §5.3's own row forbids it.
     */
    FORBIDDEN_TAG,

    /** A listing carries fewer occurrences than §5.3's Card. column requires — `t`'s `≥2`. */
    CARDINALITY_TOO_FEW,

    /** §5.3: a listing's `t` tags do not include `nenya` and exactly one of `wtb` / `wts`. */
    MISSING_TOPIC,

    /**
     * A tag has the wrong number of elements for §5.3's Encoding column.
     *
     * §8.1 names the two `fee` cases in so many words: a three-element `["fee", "0", "<pubkey>"]`
     * and a two-element `["fee", "250"]` "are both malformed and MUST be rejected".
     */
    WRONG_ARITY,

    /**
     * A hex value whose character count is not the 64 §4.3 fixes for a pubkey. §4.3: reject
     * rather than pad or truncate.
     */
    WRONG_LENGTH,

    /** A character outside `0-9`, `a-f`, `A-F`. Not a hex string at all. */
    NOT_HEX,

    /**
     * §4.3: a timestamp tag value that is not a non-negative decimal integer. A floating-point,
     * exponential or signed form is this, and never silently coerced.
     */
    MALFORMED_TIMESTAMP,

    /** A decimal field — `fee` basis points, a coordinate kind, a version, an image dimension. */
    MALFORMED_NUMBER,

    /** A `price` amount that is not a decimal number at all (§4.4). */
    MALFORMED_AMOUNT,

    /** A `price` unit token outside §4.4's permissive-on-read list. */
    UNKNOWN_UNIT,

    /**
     * A form §4.4 says MUST be treated as **unsupported on read** rather than as malformed: the
     * optional NIP-99 `<frequency>` fourth element of `price`, which Nenya v1 MUST NOT emit.
     *
     * Distinct from [WRONG_ARITY] on purpose. A recurring-price listing is a well-formed NIP-99
     * event this version of Nenya does not implement, and reporting it as malformed would make a
     * conformant NIP-99 client look broken — §8.1's rule about not making a legal peer look
     * broken, applied to the one other place the document draws the same distinction.
     */
    UNSUPPORTED,

    /**
     * §4.4's "Never lossy": a read that would lose precision. An `msat` value used where a
     * satoshi-denominated field is REQUIRED and the value is not a multiple of 1000, or a `BTC`
     * value finer than one millisatoshi. It MUST fail loudly and MUST NOT round.
     */
    LOSSY,

    /** §4.4's bound: an amount above the total bitcoin supply in millisatoshis. */
    ABOVE_SUPPLY,

    /**
     * §8.1: a `fee` term above `10000` basis points — more than 100%.
     *
     * Never reported for a value merely considered large. §8.1 fixes the legal range at
     * `0..10000` inclusive, forbids refusing an in-range value on the ground that it is too high,
     * and requires a client's own lower policy limit to surface as its own distinct, named
     * condition. This library holds no such limit, so this constant means one thing only.
     */
    BPS_ABOVE_MAXIMUM,

    /** A value §5.3 requires be non-empty — a `d` value, a `t` token — is the empty string. */
    EMPTY_VALUE,

    /**
     * §5.3: a `t` token "MUST be lowercase with no whitespace", because relay tag indexes are
     * byte-exact and a token carrying a space is simply invisible to §5.5's `{"#t": [...]}` filter.
     *
     * Its own constant rather than [MALFORMED_NUMBER], which it briefly was: a `t` token is not a
     * number, and this vocabulary is branched on by callers rather than read as prose.
     */
    MALFORMED_TOKEN,

    /**
     * [TagWriter.fee] was asked to write a `FeeTerm.Absent`, which has no wire form at all.
     *
     * §8.1's absence is written by emitting **no** `fee` tag; a stated `["fee", "0"]` is a
     * different thing — a signed statement that no fee applies — and §8.4 requires the pair be
     * reproduced byte-identically at four separate points, aborting the order on any divergence.
     * So "write the absent term" is a caller error with one right answer, and answering it with
     * the stated-zero tag would put a term on the wire the proposer never signed.
     */
    ABSENT_FEE_TERM,

    /** §5.3: an `image` MUST be an `https:` URL, "never inline bytes and never a `data:` URI". */
    NOT_HTTPS,

    /** §4.3's fifth resource bound: more `image` tags than [TagLimits.maxImageTags]. Never truncated. */
    LIMIT_EXCEEDED,

    /** A [TagLimits] value below one — a decoder that refuses every event is not a stricter one. */
    NON_POSITIVE_LIMIT,
}

/**
 * A tag was refused, carrying the [reason] as data.
 *
 * One exception type for the whole tag surface, following the convention every other package in
 * this library already uses: a caller has one thing to catch and one field to branch on.
 *
 * **The message never echoes the caller's input.** A listing arrives from a stranger and an order
 * rumor's tags carry the order id and the counterparty pubkey — §12 items 2 and 11, and STOP RULE
 * 14. A rejection message may name a tag *name*, a *count*, a *length* and a *bound*; it may
 * never name a pubkey, a coordinate, an order id or an arbitrary tag value.
 */
public class TagException internal constructor(
    public val reason: TagRejection,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * §4.4's money rules reached through a tag, with the reason carried across rather than flattened.
 *
 * T1 and T2 already own every one of these refusals; re-deriving them here would be a second copy
 * of the supply bound and of the "never lossy" rule, which is how two copies drift apart. So the
 * money types are called and their [MoneyException] is mapped onto the tag vocabulary, with the
 * original attached as the `cause` so nothing is lost — a caller that wants `MoneyRejection`
 * still has it.
 */
@JvmSynthetic
internal fun MoneyException.asTagRejection(what: String): TagException {
    val mapped = when (reason) {
        MoneyRejection.NOT_WHOLE_SATOSHIS, MoneyRejection.SUB_MILLISATOSHI -> TagRejection.LOSSY
        MoneyRejection.ABOVE_SUPPLY -> TagRejection.ABOVE_SUPPLY
        MoneyRejection.BPS_ABOVE_MAXIMUM -> TagRejection.BPS_ABOVE_MAXIMUM
        MoneyRejection.NEGATIVE, MoneyRejection.MALFORMED, MoneyRejection.OVERFLOW ->
            TagRejection.MALFORMED_AMOUNT
    }
    return TagException(mapped, "$what was refused by §4.4's money rules as ${reason.name}", this)
}
