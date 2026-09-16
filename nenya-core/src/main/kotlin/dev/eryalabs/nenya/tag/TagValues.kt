package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.Msat

/**
 * §4.2's addressable coordinate: `<kind>:<pubkey-hex>:<d-value>`.
 *
 * §4.2 requires that everything referring to a listing refer to it **by coordinate, never by
 * event id**, and says why: NIP-15 binds bids to an event id and has to warn that an auction
 * cannot be edited once bid on, because every edit detaches every bid. Coordinate-scoping makes
 * that failure unrepresentable.
 *
 * ### The `d` value is opaque, and that is why the split is bounded
 *
 * §5.3 says `d` is opaque and only that it MUST be non-empty, so a `d` value may legitimately
 * contain a colon. The parse therefore splits into **exactly three** fields and leaves whatever
 * follows the second colon alone; an unbounded split would silently truncate such a listing's
 * address to its first segment, and every reference to it would then address a different event.
 *
 * ### The kind field is canonical
 *
 * A coordinate is a relay address key, compared byte-exactly by every implementation that holds
 * one. So the kind is read in §4.4's strict decimal form — `0|[1-9][0-9]*` — rather than
 * permissively: accepting `030404` would produce two spellings of one address, which is the same
 * disagreement §4.3's duplicate rule exists to prevent one layer up.
 *
 * The pubkey follows §4.3's general rule and **not** its exception list, which names only the
 * BOLT-11 invoice string and the Lightning preimage: uppercase is accepted on read and normalised
 * to lowercase.
 */
public class Coordinate(kind: Int, pubkey: String, dValue: String) {

    /** The addressed event's kind — one of §5.1's or §5.2's listing kinds in practice. */
    public val kind: Int

    /** The listing author's x-only public key, 64 lowercase hex characters (§4.3). */
    public val pubkey: String

    /** The `d` tag value, opaque and non-empty (§5.3). Colons inside it are ordinary characters. */
    public val dValue: String

    init {
        if (kind < 0) {
            throw TagException(
                TagRejection.MALFORMED_NUMBER,
                "a coordinate names a non-negative nostr kind; this one names $kind",
            )
        }
        if (dValue.isEmpty()) {
            throw TagException(
                TagRejection.EMPTY_VALUE,
                "§5.3 says a `d` value MUST be non-empty, and a coordinate is addressed by it",
            )
        }
        this.kind = kind
        this.pubkey = readPubkeyHex(pubkey, "a coordinate's pubkey")
        this.dValue = dValue
    }

    /** §4.2's wire form, with the pubkey in §4.3's canonical lowercase. */
    public fun toTagValue(): String = "$kind:$pubkey:$dValue"

    override fun equals(other: Any?): Boolean =
        other is Coordinate && other.kind == kind && other.pubkey == pubkey && other.dValue == dValue

    override fun hashCode(): Int = (kind * 31 + pubkey.hashCode()) * 31 + dValue.hashCode()

    /**
     * Names the kind and nothing else.
     *
     * §12 item 2 names the counterparty pubkey among the values that MUST NOT appear outside an
     * encrypted event, and STOP RULE 14 adds the listing id. A coordinate is both of those in one
     * string, and a `kind:16` `type=6` private bid carries one. [toTagValue] is there for the
     * caller that genuinely needs the value; a `toString` is never that caller.
     */
    override fun toString(): String = "Coordinate(kind=$kind, redacted)"

    public companion object {

        /** §4.2's three fields; the `d` value is everything after the second colon. */
        private const val FIELDS: Int = 3

        /**
         * A coordinate read from `<kind>:<pubkey-hex>:<d-value>`.
         *
         * @throws TagException [TagRejection.WRONG_ARITY] with fewer than three fields,
         *   [TagRejection.MALFORMED_NUMBER] for a non-canonical kind, [TagRejection.WRONG_LENGTH]
         *   or [TagRejection.NOT_HEX] for the pubkey, [TagRejection.EMPTY_VALUE] for an empty `d`.
         */
        public fun parse(value: String): Coordinate {
            val fields = value.split(":", limit = FIELDS)
            if (fields.size != FIELDS) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§4.2's coordinate is `<kind>:<pubkey-hex>:<d-value>`, three colon-separated " +
                        "fields; this one parsed to ${fields.size}",
                )
            }
            val kind = readStrictDecimal(fields[0], "a coordinate's kind", TagRejection.MALFORMED_NUMBER)
            if (kind > Int.MAX_VALUE) {
                throw TagException(
                    TagRejection.MALFORMED_NUMBER,
                    "a coordinate's kind is a nostr kind and does not fit in 32 bits",
                )
            }
            return Coordinate(kind.toInt(), fields[1], fields[2])
        }
    }
}

/**
 * §5.3's `image` tag: `["image", "<url>", "<width>x<height>"]`.
 *
 * §5.3 fixes the scheme and gives no latitude: an `image` MUST be an `https:` URL, "never inline
 * bytes and never a `data:` URI". §12.10 is the reason — a `data:` URI puts arbitrary bytes into
 * a public listing, and a plain `http:` URL discloses the reader's interest to a network observer
 * as well as to the host.
 *
 * ### One stated narrowing: the dimensions are optional on read
 *
 * §5.3's Encoding cell prints three elements, and §5.1 says a Nenya offer is an **unmodified**
 * NIP-99 classified listing, where the dimensions element is optional. Rejecting a two-element
 * `image` tag would therefore reject a conformant NIP-99 offer — the cheapest distribution §5.1
 * says the project will ever get. So two elements are accepted and [dimensions] is `null`; a
 * *present* third element is held to the `<width>x<height>` form the Encoding column fixes.
 */
public class ImageRef(url: String, dimensions: String? = null) {

    /** An `https:` URL (§5.3). Never a `data:` URI and never inline bytes. */
    public val url: String

    /** `<width>x<height>`, or `null` where NIP-99 permits the element to be absent. */
    public val dimensions: String?

    init {
        if (url.isEmpty()) {
            throw TagException(TagRejection.EMPTY_VALUE, "an `image` tag carries a URL, not an empty string")
        }
        // Schemes are case-insensitive (RFC 3986 §3.1), so the comparison is too. The rule being
        // enforced is about the scheme, not about how a publisher spelled it.
        if (!url.lowercase().startsWith(HTTPS_SCHEME)) {
            throw TagException(
                TagRejection.NOT_HTTPS,
                "§5.3 says an `image` MUST be an `https:` URL, never inline bytes and never a " +
                    "`data:` URI",
            )
        }
        if (dimensions != null && !DIMENSIONS.matches(dimensions)) {
            throw TagException(
                TagRejection.MALFORMED_NUMBER,
                "§5.3 encodes an `image` tag's third element as `<width>x<height>`",
            )
        }
        this.url = url
        this.dimensions = dimensions
    }

    /** §5.3's wire form, two elements or three. */
    public fun toTag(): List<String> =
        if (dimensions == null) listOf("image", url) else listOf("image", url, dimensions)

    override fun equals(other: Any?): Boolean =
        other is ImageRef && other.url == url && other.dimensions == dimensions

    override fun hashCode(): Int = url.hashCode() * 31 + (dimensions?.hashCode() ?: 0)

    override fun toString(): String = "ImageRef(dimensions=$dimensions, url redacted)"

    private companion object {
        const val HTTPS_SCHEME: String = "https://"
        val DIMENSIONS: Regex = Regex("[0-9]+x[0-9]+")
    }
}

/**
 * §5.3's `p` tag: `["p", "<pubkey-hex>", "<relay-url>"]`.
 *
 * The relay hint is optional: §7.4 requires every `kind:15`, `kind:16` and `kind:17` rumor to
 * carry `["p", "<counterparty-pubkey-hex>"]` with no third element, and §5.3's Encoding column is
 * universal across both. So both arities are read.
 */
public class PubkeyRef(pubkey: String, relay: String? = null) {

    /** An x-only public key, 64 lowercase hex characters (§4.3), normalised on read. */
    public val pubkey: String

    /** The optional relay hint. Opaque here; §12 notes that publishing one leaks a relay set. */
    public val relay: String?

    init {
        this.pubkey = readPubkeyHex(pubkey, "a `p` tag's pubkey")
        this.relay = relay
    }

    /** §5.3's wire form, two elements or three. */
    public fun toTag(): List<String> =
        if (relay == null) listOf("p", pubkey) else listOf("p", pubkey, relay)

    override fun equals(other: Any?): Boolean =
        other is PubkeyRef && other.pubkey == pubkey && other.relay == relay

    override fun hashCode(): Int = pubkey.hashCode() * 31 + (relay?.hashCode() ?: 0)

    /** §12 item 2: a counterparty pubkey MUST NOT appear outside an encrypted event. */
    override fun toString(): String = "PubkeyRef(redacted)"
}

/**
 * §5.3's `item` tag: `["item", "<coordinate>", "<quantity>"]`.
 *
 * §5.3 fixes the quantity as the **string** `"1"` in Nenya v1 and says anything else MUST be
 * rejected, so the comparison is against that string rather than against a parsed number: `"01"`
 * and `"1.0"` are both rejections, and neither is normalised into the legal form.
 */
public class ItemRef(

    /** The listing this references, by §4.2 coordinate. */
    public val coordinate: Coordinate,

    /** The quantity. Exactly `"1"` in Nenya v1 (§5.3). */
    public val quantity: String = CANONICAL_QUANTITY,
) {

    init {
        if (quantity != CANONICAL_QUANTITY) {
            throw TagException(
                TagRejection.MALFORMED_NUMBER,
                "§5.3 says an `item` quantity MUST be the string \"$CANONICAL_QUANTITY\" in Nenya " +
                    "v1 and MUST be rejected otherwise",
            )
        }
    }

    /** §5.3's wire form. */
    public fun toTag(): List<String> = listOf("item", coordinate.toTagValue(), quantity)

    override fun equals(other: Any?): Boolean =
        other is ItemRef && other.coordinate == coordinate && other.quantity == quantity

    override fun hashCode(): Int = coordinate.hashCode() * 31 + quantity.hashCode()

    override fun toString(): String = "ItemRef(quantity=$quantity, coordinate=$coordinate)"

    public companion object {

        /** §5.3: the only legal quantity in Nenya v1. */
        public const val CANONICAL_QUANTITY: String = "1"
    }
}

/**
 * The **write** half of §5.3's Encoding column: the canonical form of each tag this library emits.
 *
 * Read and write are deliberately asymmetric, and §4.4 is explicit about why. A price is *strict
 * on write* — exactly the token `SAT`, exactly `0|[1-9][0-9]*` with no sign, separators, decimal
 * point or leading zeros — and *permissive on read*, accepting eight unit tokens and a decimal
 * `BTC` value. Hex is lowercase on write and accepted in either case on read (§4.3). A `t` token
 * is lowercased on write and matched case-insensitively on read (§5.3).
 *
 * None of this is what [TagSet.republish] does. Re-publishing somebody else's event MUST emit
 * their bytes verbatim (§4.3), because normalising a legal-but-permissive spelling would change
 * the event id and detach every signature. These functions are for events **this client authors**.
 */
public object TagWriter {

    /** `["price", "<sats>", "SAT"]` — §4.4 strict, refusing rather than rounding a part-satoshi. */
    public fun price(amount: Msat): List<String> {
        val satoshis = try {
            amount.toSatoshis()
        } catch (refused: MoneyException) {
            throw refused.asTagRejection("a `price` tag value")
        }
        return listOf("price", satoshis.toString(), CANONICAL_UNIT)
    }

    /**
     * `["fee", "<bps>"]` at zero basis points and `["fee", "<bps>", "<recipient>"]` above it —
     * §8.1's two legal arities, and the only two.
     *
     * [FeeTerm.Absent] has **no** wire form and is refused rather than written, which is the one
     * thing this function must not get wrong. `Absent` and `Stated(0)` agree on the number and
     * disagree on the wire: the first carries no tag, the second carries the two-element
     * `["fee", "0"]` that §8.1 says a proposal SHOULD carry "so that the absence of a fee is
     * itself a signed statement". Emitting the stated form for an absent term would put a term on
     * the wire the proposer never signed — and §8.4 requires that pair be reproduced
     * byte-identically at four separate points and aborts the order on any divergence. So an
     * absent term is written by emitting no `fee` tag; asking for one is a caller error.
     *
     * @throws TagException [TagRejection.ABSENT_FEE_TERM] for [FeeTerm.Absent], or
     *   [TagRejection.WRONG_ARITY] if [recipient] disagrees with the term: §8.1 makes it REQUIRED
     *   above zero bps and requires it be OMITTED at zero.
     */
    public fun fee(term: FeeTerm, recipient: String? = null): List<String> {
        if (term == FeeTerm.Absent) {
            throw TagException(
                TagRejection.ABSENT_FEE_TERM,
                "§8.1's absent fee term is written by emitting no `fee` tag at all; the " +
                    "two-element `[\"fee\", \"0\"]` is a stated zero, which is a different and " +
                    "signed statement",
            )
        }
        if (term.namesRecipient && recipient == null) {
            throw TagException(
                TagRejection.WRONG_ARITY,
                "§8.1 makes the fee recipient REQUIRED when basis points are greater than zero",
            )
        }
        if (!term.namesRecipient && recipient != null) {
            throw TagException(
                TagRejection.WRONG_ARITY,
                "§8.1 requires the fee recipient be OMITTED at zero basis points, so the canonical " +
                    "no-fee form is the two-element `[\"fee\", \"0\"]`",
            )
        }
        val bps = term.basisPoints.toString()
        return if (recipient == null) listOf("fee", bps)
        else listOf("fee", bps, readPubkeyHex(recipient, "a fee recipient"))
    }

    /** `["<name>", "<unix seconds>"]` — §4.3's decimal, non-negative, no other form. */
    public fun timestamp(name: String, unixSeconds: Long): List<String> {
        if (unixSeconds < 0L) {
            throw TagException(
                TagRejection.MALFORMED_TIMESTAMP,
                "§4.3 fixes a timestamp tag value as a non-negative decimal integer",
            )
        }
        return listOf(name, unixSeconds.toString())
    }

    /**
     * `["t", "<token>"]`, lowercased.
     *
     * §5.3: tokens MUST be lowercase with no whitespace, and implementations MUST lowercase on
     * write — because relay tag indexes are byte-exact, so `["t", "Nenya"]` is simply invisible to
     * the `{"#t": ["nenya"]}` filter §5.5 builds the whole board on.
     */
    public fun topic(token: String): List<String> {
        if (token.isEmpty()) {
            throw TagException(TagRejection.EMPTY_VALUE, "a `t` token is not the empty string")
        }
        if (token.any { it.isWhitespace() }) {
            throw TagException(
                TagRejection.MALFORMED_TOKEN,
                "§5.3 says a `t` token carries no whitespace; a relay tag index is byte-exact",
            )
        }
        return listOf("t", token.lowercase())
    }

    /** `["nenya", "1"]` — §4.5's version tag, at this document's major version. */
    public fun version(major: Int): List<String> {
        if (major < 0) {
            throw TagException(
                TagRejection.MALFORMED_NUMBER,
                "§4.5's `nenya` value is the decimal major version of the document",
            )
        }
        return listOf("nenya", major.toString())
    }

    /** `["d", "<opaque>"]` — §5.3's addressability key, which MUST be non-empty. */
    public fun d(value: String): List<String> {
        if (value.isEmpty()) {
            throw TagException(TagRejection.EMPTY_VALUE, "§5.3 says a `d` value MUST be non-empty")
        }
        return listOf("d", value)
    }

    /** §4.4's unit token on write. Exactly this, in exactly this case. */
    public const val CANONICAL_UNIT: String = "SAT"
}

/**
 * §4.4's permissive-on-read unit tokens, exactly the eight the document lists.
 *
 * The list is treated as exhaustive rather than as a sample. §4.4 enumerates the eight an
 * implementation MUST accept and offers no rule for generating more, so `Sat`, `mSat` and `msats`
 * are [TagRejection.UNKNOWN_UNIT] rather than guesses — a guess here is a 1000× money bug that
 * looks like generosity.
 */
private val SATOSHI_UNITS: Set<String> = setOf("SAT", "sat", "sats", "SATS")
private val MILLISATOSHI_UNITS: Set<String> = setOf("msat", "MSAT")
private val BITCOIN_UNITS: Set<String> = setOf("BTC", "btc")

/**
 * §4.4's amount read: a value and one of the eight unit tokens, normalised to millisatoshis.
 *
 * Every refusal comes from T1's `Msat` rather than from a second copy of §4.4's rules here, and
 * is mapped onto the tag vocabulary with the original attached as the cause.
 */
@JvmSynthetic
internal fun readAmount(value: String, unit: String, what: String): Msat {
    try {
        return when (unit) {
            in SATOSHI_UNITS -> Msat.ofSat(readPermissiveDecimal(value, what))
            in MILLISATOSHI_UNITS -> Msat.ofMsat(readPermissiveDecimal(value, what))
            in BITCOIN_UNITS -> Msat.parseBtc(value)
            else -> throw TagException(
                TagRejection.UNKNOWN_UNIT,
                "§4.4 lists the unit tokens an implementation MUST accept on read; $what carries " +
                    "one that is not among them",
            )
        }
    } catch (refused: MoneyException) {
        throw refused.asTagRejection(what)
    }
}

/**
 * §4.3's timestamp rule: a non-negative decimal integer, never a floating-point, exponential or
 * signed form.
 *
 * Leading zeros are accepted, deliberately. §4.3 requires "a non-negative decimal integer" and
 * says what MUST NOT be accepted — a sign, a point, an exponent — without making the canonical
 * form of §4.4 binding here. Refusing `01757000000` would refuse a peer the document does not say
 * is wrong, which §8.1's "never make a legal peer look broken" clause is the nearest rule to.
 */
@JvmSynthetic
internal fun readTimestamp(text: String, what: String): Long {
    if (text.isEmpty() || !text.isAsciiDigits()) {
        throw TagException(
            TagRejection.MALFORMED_TIMESTAMP,
            "§4.3 fixes $what as a non-negative decimal integer and forbids a floating-point, " +
                "exponential or signed form",
        )
    }
    return text.trimStart('0').ifEmpty { "0" }.toLongOrNull()
        ?: throw TagException(
            TagRejection.MALFORMED_TIMESTAMP,
            "$what does not fit in a 64-bit signed integer of seconds",
        )
}

/**
 * §4.4's strict form — `0|[1-9][0-9]*` — for the fields the document fixes it for.
 *
 * "Not a number at all" and "a number above this field's bound" are separated deliberately, and
 * [tooLargeReason] is how. §8.1 names the second condition itself — a `fee` above `10000` basis
 * points — and a twenty-digit `fee` value reported as merely malformed tells the caller the peer
 * is broken when in fact the peer asked for 10^19%. Because the form is canonical by the time the
 * length is measured, a digit count is an exact bound: no leading zeros can inflate it.
 */
@JvmSynthetic
internal fun readStrictDecimal(
    text: String,
    what: String,
    reason: TagRejection,
    maxDigits: Int = MAX_LONG_DIGITS,
    tooLargeReason: TagRejection = reason,
): Long {
    val canonical = text.isNotEmpty() &&
        text.isAsciiDigits() &&
        (text == "0" || text[0] != '0')
    if (!canonical) {
        throw TagException(
            reason,
            "§4.4's strict form is `0|[1-9][0-9]*` — no sign, no separators, no decimal point and " +
                "no leading zeros other than the single digit `0`; $what is not in it",
        )
    }
    if (text.length > maxDigits) {
        throw TagException(tooLargeReason, "$what carries ${text.length} digits and at most $maxDigits are in range")
    }
    return text.toLongOrNull()
        ?: throw TagException(tooLargeReason, "$what does not fit in a 64-bit signed integer")
}

/** `Long.MAX_VALUE` has nineteen digits, so anything longer certainly does not fit. */
private const val MAX_LONG_DIGITS: Int = 19

/** Permissive on read (§4.4): ASCII digits, leading zeros allowed, nothing else. */
private fun readPermissiveDecimal(text: String, what: String): Long {
    if (text.isEmpty() || !text.isAsciiDigits()) {
        throw TagException(
            TagRejection.MALFORMED_AMOUNT,
            "$what is a decimal amount: ASCII digits, no sign, no separators, no exponent",
        )
    }
    return text.trimStart('0').ifEmpty { "0" }.toLongOrNull()
        ?: throw TagException(TagRejection.ABOVE_SUPPLY, "$what has more digits than any amount §4.4 permits")
}

/**
 * §4.3's fixed-length hex for a 64-character x-only public key.
 *
 * Uppercase is **accepted and normalised**: §4.3's no-normalisation exception list names exactly
 * the BOLT-11 invoice string and the Lightning preimage, states that it is exhaustive, and names
 * pubkeys explicitly among the values that follow the general accept-and-normalise rule.
 */
@JvmSynthetic
internal fun readPubkeyHex(text: String, what: String): String {
    if (text.length != PUBKEY_HEX_LENGTH) {
        throw TagException(
            TagRejection.WRONG_LENGTH,
            "$what is exactly $PUBKEY_HEX_LENGTH hex characters; this one is ${text.length}, and " +
                "§4.3 requires rejecting a value of the wrong length rather than padding or " +
                "truncating it",
        )
    }
    val out = StringBuilder(PUBKEY_HEX_LENGTH)
    for (character in text) {
        val lowered = when (character) {
            in '0'..'9', in 'a'..'f' -> character
            in 'A'..'F' -> character + ('a' - 'A')
            else -> throw TagException(
                TagRejection.NOT_HEX,
                "$what is hexadecimal characters only; this one carries something else",
            )
        }
        out.append(lowered)
    }
    return out.toString()
}

/** 64 characters — §4.3 fixes a pubkey as exactly that, a 32-byte x-only key. */
private const val PUBKEY_HEX_LENGTH: Int = 64

/**
 * Deliberately not `Char.isDigit()`, which accepts Arabic-Indic and other non-ASCII digits that
 * `String.toLong()` then happily parses. The same trap `Msat.parseBtc` documents.
 */
private fun String.isAsciiDigits(): Boolean = all { it in '0'..'9' }
