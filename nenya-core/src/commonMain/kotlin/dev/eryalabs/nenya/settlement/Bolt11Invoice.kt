package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.text.strictUtf8OrNull
import dev.eryalabs.nenya.wire.encodeLowerHex

/**
 * Appendix C's amount multipliers, as the exact rational number of millisatoshis one unit is.
 *
 * ### A fraction and not a `Double`, because one of the five rows is not an integer
 *
 * `p` is 0.1 msat per unit, and §4.4's "Never floating point" rule is not suspended for it. So each
 * entry carries a [msatPerUnitNumerator] and a [msatPerUnitDenominator] and the arithmetic stays in
 * integers: a `p` amount is divided by ten **before** anything is multiplied, which is also the only
 * way the largest legal pico amount is reachable at all — the supply cap is 2.1 × 10^19 pico-BTC,
 * which is above `Long.MAX_VALUE`, while the same amount in millisatoshis is 2.1 × 10^18 and fits.
 *
 * Appendix C says a `p` amount "MUST be a multiple of 10, because msat is the smallest representable
 * unit"; that rule is [msatPerUnitDenominator] doing its job rather than a special case beside it.
 *
 * This table is held **equal to Appendix C's own**, parsed out of the specification at test time, so
 * a revision that changed a factor turns the suite red instead of leaving a stale copy here.
 */
public enum class Bolt11Multiplier(

    /** The multiplier letter, or `null` for Appendix C's *(none)* row — an amount in whole BTC. */
    public val letter: Char?,

    /** Millisatoshis per unit, numerator. */
    public val msatPerUnitNumerator: Long,

    /** Millisatoshis per unit, denominator — ten for `p`, one for every other row. */
    public val msatPerUnitDenominator: Long,
) {

    /** `m` — milli, 10^-3 BTC. */
    MILLI('m', 100_000_000L, 1L),

    /** `u` — micro, 10^-6 BTC. */
    MICRO('u', 100_000L, 1L),

    /** `n` — nano, 10^-9 BTC. */
    NANO('n', 100L, 1L),

    /** `p` — pico, 10^-12 BTC, and the one row whose unit is finer than a millisatoshi. */
    PICO('p', 1L, 10L),

    /** Appendix C's *(none)* row: a bare decimal figure is an amount in whole bitcoin. */
    WHOLE_BITCOIN(null, 100_000_000_000L, 1L);

    public companion object {

        /** The row [letter] names, or `null` for a letter Appendix C's table does not carry. */
        public fun of(letter: Char?): Bolt11Multiplier? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * The tagged fields [Bolt11Invoice] tracks — the two §9.2 reads, and the ones whose *presence* is
 * part of what an invoice says.
 *
 * Presence is published as a set of these rather than as a `Boolean` accessor apiece, and that is
 * §9.1's shape rule rather than taste: this package's structural sweep pins every published member
 * that answers a question with a bare `Boolean`, because a flag cannot carry *which* of several
 * things was seen. A set can, and it grows without widening the sweep.
 *
 * A fixed-length field of the **wrong** length is not present: BOLT-11's reader rule, which
 * Appendix C follows from revision `1.4`, is to skip such a field as though its type were unknown.
 */
public enum class Bolt11Field(

    /** The field's type character in Appendix C's tagged-field encoding. */
    public val type: Char,
) {

    /** `p` — §9.2 check 3's operand. Exactly one correct-length occurrence is REQUIRED. */
    PAYMENT_HASH('p'),

    /** `s` — the payment secret. REQUIRED; its value is deliberately not published (§12). */
    PAYMENT_SECRET('s'),

    /** `d` — a short description. Exactly one of this and [DESCRIPTION_HASH] is REQUIRED. */
    DESCRIPTION('d'),

    /** `h` — the hash of a long description. The other half of that rule. */
    DESCRIPTION_HASH('h'),

    /** `x` — expiry in seconds. OPTIONAL; absent means Appendix C's default of 3600. */
    EXPIRY('x'),

    /** `c` — `min_final_cltv_expiry_delta`. OPTIONAL; absent means BOLT-11's default of 18. */
    MIN_FINAL_CLTV_EXPIRY('c'),

    /** `9` — the feature bits. OPTIONAL; absent means no bit is set. */
    FEATURES('9'),
}

/**
 * A BOLT-11 invoice, decoded as far as Appendix C requires and no further.
 *
 * ### What this closes
 *
 * [Bolt11Reference] recognises a shape and parses nothing; its own KDoc names the MUST it leaves
 * open — "MUST still parse past it correctly and MUST verify the bech32 checksum". This type is that
 * parser. §9.2's checks 3, 4 and 5 all read the invoice: check 3's `payment_hash` is the `p` tagged
 * field, check 4's amount is the human-readable part's, and check 5's deadline is the timestamp plus
 * the `x` field. All three operands come from here.
 *
 * ### Unforgeable, for the reason `Settlement` is
 *
 * A value of this type is what a later check will believe about an invoice's amount and expiry, so a
 * client able to mint one could state that a provider's invoice asked for the price it was supposed
 * to ask for. It is therefore a `sealed interface` with no constructor to reach: [parse] is the only
 * door, and it takes a [Bolt11Reference] — a string this library has already recognised — rather
 * than anything a counterparty describes.
 *
 * ### What Nenya does **not** do, and MUST NOT be reported as done
 *
 * No signature is verified, no node key is recovered, no low-S rule is applied and no feature bit is
 * judged. Those are the paying wallet's business, and §9.2's evidence rule is the preimage rather
 * than the invoice's signature. Three of the vendored document's *invalid* examples are invalid for
 * exactly those reasons and therefore **parse** here; the tests assert that departure as a computed
 * set rather than as a list somebody wrote out. There is deliberately **no** `PaymentCheck` constant
 * for a signature: an order may not become `paid` while an applicable check is unperformed, so a
 * constant nothing could ever perform would strand every order for good.
 *
 * ### The recogniser's floor is an invariant here rather than a rejection
 *
 * [parse] takes a [Bolt11Reference], which cannot exist with a data part below
 * [Bolt11Reference.MIN_DATA_CHARACTERS] — a 7-character timestamp, a 104-character signature and a
 * 6-character checksum. Every slice below is therefore in range by construction, and this type
 * publishes no "too short" rejection: the one that exists is the recogniser's
 * [SettlementRejection.INVOICE_MALFORMED], reported before any [Bolt11Reference] exists at all.
 *
 * Pure computation: no clock, no randomness, no I/O, and no cryptography beyond SHA-256.
 */
public sealed interface Bolt11Invoice {

    /**
     * Appendix C's network prefix — `bc`, `tb`, `bcrt`, … — **without** the leading `ln`.
     *
     * Appendix C fixes the human-readable part as "`ln` + a network prefix + an OPTIONAL amount", so
     * the prefix is what is left when both of those are taken off. Nothing here judges it: a network
     * this implementation has never heard of is a fact about the invoice rather than a rejection,
     * and the embedding client is the one that knows which chain its wallet is on.
     */
    public val networkPrefix: String

    /**
     * The amount the human-readable part encodes, or `null` for a BOLT-11 "any amount" invoice.
     *
     * Absence is a **case** here and not a refusal. §9.2 check 4 rejects an any-amount invoice, and
     * that is check 4's rejection to make against the amount that payee is owed — a value this type
     * has never been told.
     */
    public val amount: Msat?

    /** Appendix C's 35-bit timestamp, in unix seconds. */
    public val timestamp: Long

    /**
     * The `x` field in seconds, or Appendix C's default of 3600 when the invoice carries none.
     *
     * **Saturated at [Long.MAX_VALUE].** A BOLT-11 `x` is bounded by its value rather than by its
     * field width, and a 64-bit one does not fit a signed `Long`; anything from 2^63 upward reads as
     * [Long.MAX_VALUE] and never as a negative number. For §9.2 check 5 that is the honest reading —
     * an expiry that large is one that never runs out — while a negative one would have inverted the
     * comparison and expired every such invoice instantly. A value of 2^64 or more is refused
     * outright as [SettlementRejection.EXPIRY_OUT_OF_RANGE]; a 13-group field holding 2^64 − 1 parses.
     */
    public val expirySeconds: Long

    /**
     * The `c` field — BOLT-11's `min_final_cltv_expiry_delta` — or its default of 18.
     *
     * Read and published for completeness, saturated exactly as [expirySeconds] is. Nenya routes no
     * payment and so acts on it nowhere; an embedding wallet does.
     */
    public val minFinalCltvExpiry: Long

    /** §9.2 check 3's operand: the 256 bits of the one correct-length `p` field. */
    public val paymentHash: PaymentHash

    /**
     * Which of [Bolt11Field]'s fields the invoice carries at its correct length.
     *
     * Always contains [Bolt11Field.PAYMENT_HASH] and [Bolt11Field.PAYMENT_SECRET], and exactly one
     * of [Bolt11Field.DESCRIPTION] and [Bolt11Field.DESCRIPTION_HASH]: [parse] refuses an invoice
     * missing any of them. The remaining three are genuinely optional.
     */
    public val fieldsPresent: Set<Bolt11Field>

    /**
     * The bit positions the `9` field sets, counted from the least significant bit of that field, or
     * an empty set when the invoice carries no `9`.
     *
     * Published so an embedding wallet can apply BOLT-11's own even/odd rule. **Nenya applies none
     * of it**: an unknown even bit makes an invoice unpayable by a wallet that does not understand
     * it, which is the wallet's judgement and not this library's.
     */
    public val featureBits: Set<Int>

    /**
     * The recovery id carried in the last byte of the signature field.
     *
     * Published, never acted on. See this type's note: no signature is checked anywhere here.
     */
    public val recoveryId: Int

    /** The 64 signature bytes (`r || s`), as a fresh copy. Never verified by this library. */
    public fun signature(): ByteArray

    /**
     * SHA-256 of the bytes BOLT-11 signs over — the human-readable part's own bytes, then the
     * timestamp and every tagged field as a bit stream zero-padded to a byte boundary — as a fresh
     * copy.
     *
     * Published so an embedding app that *has* a secp256k1 library can verify the signature or
     * recover the node key itself without re-deriving the encoding. Computing this digest is not
     * verifying anything, and nothing here claims otherwise.
     */
    public fun signedDataSha256(): ByteArray

    public companion object {

        /** Appendix C: the `x` default when an invoice carries no `x` field. */
        public const val DEFAULT_EXPIRY_SECONDS: Long = 3600L

        /** BOLT-11's `min_final_cltv_expiry_delta` default when an invoice carries no `c` field. */
        public const val DEFAULT_MIN_FINAL_CLTV_EXPIRY: Long = 18L

        /** A 256-bit fixed field: 52 groups, which is 260 bits — 256 plus four bits of padding. */
        internal const val HASH_GROUPS: Int = 52

        /** A tagged field's header: one type group and a two-group `data_length`. */
        internal const val FIELD_HEADER_GROUPS: Int = 3

        /**
         * The invoice [reference] decodes to.
         *
         * @throws SettlementException with [SettlementRejection.CHECKSUM_INVALID],
         *   [SettlementRejection.HRP_INVALID], [SettlementRejection.AMOUNT_SUB_MSAT],
         *   [SettlementRejection.AMOUNT_OUT_OF_RANGE], [SettlementRejection.FIELD_TRUNCATED],
         *   [SettlementRejection.PAYMENT_HASH_MISSING],
         *   [SettlementRejection.PAYMENT_HASH_DUPLICATED],
         *   [SettlementRejection.EXPIRY_DUPLICATED], [SettlementRejection.EXPIRY_OUT_OF_RANGE],
         *   [SettlementRejection.PAYMENT_SECRET_MISSING] or
         *   [SettlementRejection.DESCRIPTION_NOT_EXACTLY_ONE] — each naming the rule that refused it,
         *   and none quoting a character of the invoice (§12 items 1 and 11).
         */
        public fun parse(reference: Bolt11Reference): Bolt11Invoice = decodeInvoice(reference.text)
    }
}

// -------------------------------------------------------------------------------------------
// The implementation. Package-private, so no client can mint one; see Bolt11Invoice's note.
// -------------------------------------------------------------------------------------------

private class ParsedBolt11Invoice(
    override val networkPrefix: String,
    override val amount: Msat?,
    override val timestamp: Long,
    override val expirySeconds: Long,
    override val minFinalCltvExpiry: Long,
    override val paymentHash: PaymentHash,
    override val fieldsPresent: Set<Bolt11Field>,
    override val featureBits: Set<Int>,
    override val recoveryId: Int,
    private val signatureBytes: ByteArray,
    private val signedDigest: ByteArray,
) : Bolt11Invoice {

    override fun signature(): ByteArray = signatureBytes.copyOf()

    override fun signedDataSha256(): ByteArray = signedDigest.copyOf()

    /**
     * Deliberately carries nothing of the invoice.
     *
     * §12 items 1 and 11 and STOP RULE 14: a BOLT-11 string, a payment hash and a payment secret are
     * per-order correlators visible to the payer's node, the payee's node and every routing hop, so
     * printing any of them into a host application's log links an order to a Lightning payment for
     * free. The named accessors are there for the caller that genuinely needs a value.
     */
    override fun toString(): String = "Bolt11Invoice(redacted)"
}

// -------------------------------------------------------------------------------------------
// bech32, as BIP-173 defines it and BOLT-11 uses it: checksum constant 1, and no length limit.
// -------------------------------------------------------------------------------------------

/** BIP-173's `bech32_polymod` generator. */
private val GENERATOR: IntArray =
    intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

/**
 * BOLT-11's checksum constant is BIP-173's `1`, **not** bech32m's `0x2bc830a3`.
 *
 * BOLT-11 predates bech32m and never moved. The vendored vectors pin it: with bech32m's constant
 * every one of them is refused.
 */
private const val CHECKSUM_CONSTANT: Int = 1

private fun polymod(values: List<Int>): Int {
    var checksum = 1
    for (value in values) {
        val top = checksum shr 25
        checksum = ((checksum and 0x1ffffff) shl 5) xor value
        for (bit in 0..4) if ((top shr bit) and 1 == 1) checksum = checksum xor GENERATOR[bit]
    }
    return checksum
}

/** BIP-173's human-readable-part expansion: high bits, a zero separator, then low bits. */
private fun expand(hrp: String): List<Int> {
    val out = ArrayList<Int>(2 * hrp.length + 1)
    for (character in hrp) out.add(character.code shr 5)
    out.add(0)
    for (character in hrp) out.add(character.code and 31)
    return out
}

/** [groups] read big-endian as one number. Only ever called with a width a `Long` holds. */
private fun numberOf(groups: List<Int>): Long {
    var value = 0L
    for (group in groups) value = (value shl 5) or group.toLong()
    return value
}

/**
 * [groups] as bytes, **dropping** the leftover bits at the end.
 *
 * Appendix C pads a field's data with zero bits up to the next five-bit boundary, so a 256-bit
 * payment hash occupies 52 groups — 260 bits — and the four trailing bits are padding, not value.
 */
private fun bytesOf(groups: List<Int>): ByteArray {
    val out = ByteArray(groups.size * 5 / 8)
    var accumulator = 0
    var bits = 0
    var at = 0
    for (group in groups) {
        accumulator = (accumulator shl 5) or group
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out[at++] = ((accumulator shr bits) and 0xff).toByte()
        }
    }
    return out
}

/** [groups] as bytes, **zero-padding** the last partial byte — BOLT-11's signing-data rule. */
private fun paddedBytesOf(groups: List<Int>): ByteArray {
    val out = ByteArray((groups.size * 5 + 7) / 8)
    var accumulator = 0
    var held = 0
    var at = 0
    for (group in groups) {
        accumulator = (accumulator shl 5) or group
        held += 5
        while (held >= 8) {
            held -= 8
            out[at++] = ((accumulator shr held) and 0xff).toByte()
        }
    }
    if (held > 0) out[at] = ((accumulator shl (8 - held)) and 0xff).toByte()
    return out
}

// -------------------------------------------------------------------------------------------
// The parse itself.
// -------------------------------------------------------------------------------------------

/** Appendix C's separator, and BIP-173's: the **last** `1` in the string. */
private const val SEPARATOR: Char = '1'

/** Appendix C's human-readable part opens with these two characters, before the network prefix. */
private const val LIGHTNING_PREFIX: String = "ln"

/**
 * §4.3 requires a bound on the size of anything parsed. The longest legal amount figure is the
 * supply cap in pico-BTC — 21 000 000 000 000 000 000, twenty digits — so a twenty-first digit is
 * above supply whichever multiplier follows it, and is refused rather than scanned.
 */
private const val MAX_AMOUNT_DIGITS: Int = 20

/**
 * Thirteen five-bit groups is 65 bits, which is the narrowest field that can hold a value of 2^64 or
 * more — and therefore the only width at which the value bound can bite from below.
 */
private const val SIXTY_FIVE_BIT_GROUPS: Int = 13

/**
 * 2^64 = 16 × 2^60, so a 13-group field whose leading group is 16 or more holds 2^64 or more. The
 * bound is on the **value**: a 13-group field holding 2^64 − 1, whose leading group is 15, parses.
 */
private const val LEADING_GROUP_AT_TWO_TO_THE_64: Int = 16

/** 2^63 = 8 × 2^60, which is where a signed `Long` stops being able to hold the value. */
private const val LEADING_GROUP_AT_TWO_TO_THE_63: Int = 8

private fun decodeInvoice(text: String): Bolt11Invoice {
    // Every invariant this function relies on is `Bolt11Reference.recognise`'s, and it runs in that
    // type's `init` on every construction path: the string is all lowercase, it carries the
    // separator, its human-readable part matches Appendix C's pattern, its data part is drawn from
    // the bech32 alphabet, and that data part is at least MIN_DATA_CHARACTERS long. Every slice
    // below is in range because of the last of those, which is why this parser has no "too short"
    // rejection of its own to publish.
    val separator = text.lastIndexOf(SEPARATOR)
    val hrp = text.substring(0, separator)
    val data = text.substring(separator + 1)

    val groups = ArrayList<Int>(data.length)
    for (character in data) groups.add(Bolt11Reference.BECH32_ALPHABET.indexOf(character))

    if (polymod(expand(hrp) + groups) != CHECKSUM_CONSTANT) {
        throw settlementRefusal(
            SettlementRejection.CHECKSUM_INVALID,
            "Appendix C requires an implementation that skips the signature to verify the bech32 " +
                "checksum, and this reference's does not check out over its human-readable and data " +
                "parts — so at least one character of it is not the character the issuer wrote",
        )
    }

    val body = groups.subList(0, groups.size - Bolt11Reference.CHECKSUM_CHARACTERS)
    val covered = body.subList(0, body.size - Bolt11Reference.SIGNATURE_CHARACTERS)
    val signatureGroups = body.subList(body.size - Bolt11Reference.SIGNATURE_CHARACTERS, body.size)
    val timestampGroups = covered.subList(0, Bolt11Reference.TIMESTAMP_CHARACTERS)
    val middle = covered.subList(Bolt11Reference.TIMESTAMP_CHARACTERS, covered.size)

    val networkPrefix = networkPrefixOf(hrp)
    val amount = amountOf(hrp)
    val fields = readTaggedFields(middle)

    // 104 groups is 520 bits, which is 65 bytes exactly: 64 of `r || s`, then the recovery byte.
    val packed = bytesOf(signatureGroups)
    val signedData = (strictUtf8OrNull(hrp) ?: throw hrpInvalid()) + paddedBytesOf(covered)

    return ParsedBolt11Invoice(
        networkPrefix = networkPrefix,
        amount = amount,
        timestamp = numberOf(timestampGroups),
        expirySeconds = fields.expiry ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS,
        minFinalCltvExpiry = fields.cltv ?: Bolt11Invoice.DEFAULT_MIN_FINAL_CLTV_EXPIRY,
        paymentHash = PaymentHash.ofHex(encodeLowerHex(bytesOf(fields.paymentHash))),
        fieldsPresent = readOnlySetOf(fields.present),
        featureBits = readOnlySetOf(fields.featureBits),
        recoveryId = packed[packed.size - 1].toInt() and 0xff,
        signatureBytes = packed.copyOf(packed.size - 1),
        signedDigest = sha256(signedData),
    )
}

/** `ln` and the network prefix, up to the optional amount, with the `ln` taken off. */
private fun networkPrefixOf(hrp: String): String {
    val network = hrp.substring(0, amountStart(hrp))
    // Guaranteed by the recogniser's `ln[a-z]+…` pattern; restated so a loosening there cannot fall
    // through to a substring that silently starts in the wrong place.
    if (!network.startsWith(LIGHTNING_PREFIX) || network.length <= LIGHTNING_PREFIX.length) {
        throw hrpInvalid()
    }
    return network.substring(LIGHTNING_PREFIX.length)
}

/** Where Appendix C's OPTIONAL amount begins, or the whole length when there is none. */
private fun amountStart(hrp: String): Int {
    val firstDigit = hrp.indexOfFirst { it in '0'..'9' }
    return if (firstDigit < 0) hrp.length else firstDigit
}

/**
 * Appendix C's OPTIONAL amount, or `null` when the human-readable part carries none.
 *
 * BOLT-11 requires a present amount to be "a positive decimal integer with no leading zeroes", which
 * the recogniser's `[0-9]+[munp]?` pattern does **not** enforce — so both a leading zero and an
 * amount of zero are refused here, as [SettlementRejection.HRP_INVALID].
 */
private fun amountOf(hrp: String): Msat? {
    val at = amountStart(hrp)
    if (at == hrp.length) return null
    val written = hrp.substring(at)
    val last = written[written.length - 1]
    val multiplier: Bolt11Multiplier
    val digits: String
    if (last in '0'..'9') {
        multiplier = Bolt11Multiplier.WHOLE_BITCOIN
        digits = written
    } else {
        multiplier = Bolt11Multiplier.of(last) ?: throw hrpInvalid()
        digits = written.substring(0, written.length - 1)
    }
    if (digits.isEmpty() || digits[0] == '0') throw hrpInvalid()
    return millisatoshisOf(multiplier, digits)
}

/**
 * [digits] units of [multiplier], in millisatoshis.
 *
 * The division comes **first**, and that is not an optimisation: the supply cap is 2.1 × 10^19
 * pico-BTC, above `Long.MAX_VALUE`, so a pico amount at the cap has no `Long` to be read into before
 * it is divided. Dividing by a power of ten is dropping that many trailing zeros, and a figure with
 * none to drop is the sub-millisatoshi amount Appendix C refuses.
 */
private fun millisatoshisOf(multiplier: Bolt11Multiplier, digits: String): Msat {
    var scaled = digits
    var divisor = multiplier.msatPerUnitDenominator
    while (divisor > 1L) {
        if (scaled[scaled.length - 1] != '0') {
            throw settlementRefusal(
                SettlementRejection.AMOUNT_SUB_MSAT,
                "Appendix C requires a `p` amount to be a multiple of ten, because a millisatoshi is " +
                    "the smallest unit this protocol can express, and this invoice's is not — it names " +
                    "a fraction of a millisatoshi, which §4.4 forbids rounding away",
            )
        }
        scaled = scaled.substring(0, scaled.length - 1)
        divisor /= 10L
    }
    val units = unitsOf(scaled)
    // Bounded before the multiply rather than after: after is too late, the value has already
    // wrapped and the evidence is gone. §8.3 names exactly this failure on a different product.
    if (units > Msat.SUPPLY_CAP_MSAT / multiplier.msatPerUnitNumerator) throw amountOutOfRange()
    return Msat.ofMsat(units) * multiplier.msatPerUnitNumerator
}

/** [digits] as a `Long`, refusing rather than wrapping, and length-bounded first per §4.3. */
private fun unitsOf(digits: String): Long {
    if (digits.length > MAX_AMOUNT_DIGITS) throw amountOutOfRange()
    var value = 0L
    for (character in digits) {
        val digit = (character - '0').toLong()
        if (value > (Long.MAX_VALUE - digit) / 10L) throw amountOutOfRange()
        value = value * 10L + digit
    }
    return value
}

/** What the walk over the tagged fields found. */
private class TaggedFields(
    val paymentHash: List<Int>,
    val present: Set<Bolt11Field>,
    val featureBits: Set<Int>,
    val expiry: Long?,
    val cltv: Long?,
)

/**
 * Appendix C's `(type, data_length, data)` sequence, walked to the start of the signature.
 *
 * **A fixed-length field of the wrong length is skipped as though its type were unknown.** That is
 * BOLT-11's own reader rule and, from revision `1.4`, Appendix C's. Every Lightning wallet does it,
 * and the vendored valid example 14 is an invoice carrying eight such fields deliberately. Evidence
 * is not weakened by it: exactly one well-formed 52-group `p` is still required, a missing one or a
 * second correct-length one is still refused, and the paying wallet reads that same one.
 *
 * Repeats are resolved three different ways, and each is Appendix C's rather than a default. A
 * second `x` is **refused**, because §9.2 check 5 measures one deadline and two candidates for it
 * would have two readers disagreeing. A second `d` or `h` is refused too, by the "exactly one of the
 * two" rule, which counts occurrences and not kinds. A second `c`, `9` or `s` takes BOLT-11's own
 * reader rule — the first wins — because nothing this library decides turns on any of them; a
 * repeated `c` is still bounds-checked, so the value rule applies to the field rather than only to
 * the occurrence that won.
 */
private fun readTaggedFields(middle: List<Int>): TaggedFields {
    var paymentHash: List<Int>? = null
    var secret = false
    var descriptions = 0
    var expiry: Long? = null
    var cltv: Long? = null
    var featureBits: Set<Int>? = null
    val present = LinkedHashSet<Bolt11Field>()

    var at = 0
    while (at < middle.size) {
        if (at + Bolt11Invoice.FIELD_HEADER_GROUPS > middle.size) throw fieldTruncated()
        val type = Bolt11Reference.BECH32_ALPHABET[middle[at]]
        val length = middle[at + 1] * 32 + middle[at + 2]
        val from = at + Bolt11Invoice.FIELD_HEADER_GROUPS
        if (from + length > middle.size) throw fieldTruncated()
        val value = middle.subList(from, from + length)
        at = from + length

        when {
            type == Bolt11Field.PAYMENT_HASH.type && length == Bolt11Invoice.HASH_GROUPS -> {
                if (paymentHash != null) {
                    throw settlementRefusal(
                        SettlementRejection.PAYMENT_HASH_DUPLICATED,
                        "§9.2 check 3 compares a preimage against **the** payment hash of the stored " +
                            "invoice, and this one carries two well-formed ones; two readers " +
                            "resolving that by first and by last would disagree about which payment " +
                            "settles the order, which is the ambiguity §4.3's duplicate rule refuses",
                    )
                }
                paymentHash = value
                present.add(Bolt11Field.PAYMENT_HASH)
            }
            type == Bolt11Field.PAYMENT_SECRET.type && length == Bolt11Invoice.HASH_GROUPS -> {
                secret = true
                present.add(Bolt11Field.PAYMENT_SECRET)
            }
            type == Bolt11Field.DESCRIPTION_HASH.type && length == Bolt11Invoice.HASH_GROUPS -> {
                descriptions++
                present.add(Bolt11Field.DESCRIPTION_HASH)
            }
            // `d` is variable-length, so there is no wrong length for it to be skipped at.
            type == Bolt11Field.DESCRIPTION.type -> {
                descriptions++
                present.add(Bolt11Field.DESCRIPTION)
            }
            type == Bolt11Field.EXPIRY.type -> {
                if (expiry != null) {
                    throw settlementRefusal(
                        SettlementRejection.EXPIRY_DUPLICATED,
                        "§9.2 check 5 measures one deadline, and this invoice states two expiries; " +
                            "taking the first and taking the last are both defensible, which is " +
                            "exactly why neither may be chosen silently (§4.3)",
                    )
                }
                expiry = boundedSeconds(value)
                present.add(Bolt11Field.EXPIRY)
            }
            type == Bolt11Field.MIN_FINAL_CLTV_EXPIRY.type -> {
                // Bounded on every occurrence and not only on the one that wins: Appendix C's rule
                // is about the field, so a reader that skipped the check for a repeat would accept
                // an invoice the specification says to reject and disagree with a reader that took
                // the last. `x` gets this for free, because a second one is refused outright.
                val seconds = boundedSeconds(value)
                if (cltv == null) cltv = seconds
                present.add(Bolt11Field.MIN_FINAL_CLTV_EXPIRY)
            }
            type == Bolt11Field.FEATURES.type -> {
                if (featureBits == null) featureBits = bitsOf(value)
                present.add(Bolt11Field.FEATURES)
            }
            // Everything else: a type this implementation does not read, and a `p`, `h`, `s` or `n`
            // of the wrong length — both skipped by the length they declare, and never guessed at.
            else -> Unit
        }
    }

    if (paymentHash == null) {
        throw settlementRefusal(
            SettlementRejection.PAYMENT_HASH_MISSING,
            "§9.2 check 3's operand is the 256-bit `p` tagged field of the invoice, and this one " +
                "carries no `p` field of the correct length at all — so no preimage could ever be " +
                "checked against it, and no payment against it could ever be evidenced",
        )
    }
    if (!secret) {
        throw settlementRefusal(
            SettlementRejection.PAYMENT_SECRET_MISSING,
            "BOLT-11 requires a payment secret (`s`), and every deployed wallet refuses an invoice " +
                "without one: it is what stops a probing node claiming a partial payment was its " +
                "own. The vendored document lists such an invoice among its invalid examples",
        )
    }
    if (descriptions != 1) {
        throw settlementRefusal(
            SettlementRejection.DESCRIPTION_NOT_EXACTLY_ONE,
            "BOLT-11 requires exactly one of a short description and a description hash, and this " +
                "invoice carries $descriptions of them; with both, the two say different things " +
                "about what is being paid for and no reader can tell which the issuer meant",
        )
    }
    return TaggedFields(paymentHash, present, featureBits ?: emptySet(), expiry, cltv)
}

/**
 * A big-endian `x` or `c` value, saturated at [Long.MAX_VALUE] and refused at 2^64.
 *
 * The bound is on the **value** and never on the field's width: a 13-group field holding 2^64 − 1
 * parses, and a 20-group field of leading zeros holding 60 parses as 60.
 */
private fun boundedSeconds(groups: List<Int>): Long {
    var first = 0
    while (first < groups.size && groups[first] == 0) first++
    val significant = groups.size - first
    if (significant > SIXTY_FIVE_BIT_GROUPS) throw expiryOutOfRange()
    if (significant == SIXTY_FIVE_BIT_GROUPS) {
        val leading = groups[first]
        if (leading >= LEADING_GROUP_AT_TWO_TO_THE_64) throw expiryOutOfRange()
        if (leading >= LEADING_GROUP_AT_TWO_TO_THE_63) return Long.MAX_VALUE
    }
    return numberOf(groups.subList(first, groups.size))
}

/**
 * The bit positions [groups] sets, counted from the least significant bit of the whole field.
 *
 * BOLT-11 numbers feature bits from the end of the field, so a bit's position depends on how wide
 * the field is — which is why this is computed from `groups.size` rather than from the left.
 */
private fun bitsOf(groups: List<Int>): Set<Int> {
    val bits = groups.size * 5
    val out = LinkedHashSet<Int>()
    for (index in groups.indices) {
        for (bit in 0..4) {
            if ((groups[index] shr (4 - bit)) and 1 == 1) out.add(bits - 1 - (5 * index + bit))
        }
    }
    return out
}

// -------------------------------------------------------------------------------------------
// Refusals. None of these messages quotes a character of the invoice (§12 items 1 and 11).
// -------------------------------------------------------------------------------------------

private fun settlementRefusal(reason: SettlementRejection, detail: String): SettlementException =
    SettlementException(reason, SettlementVocabulary.PAYMENT, detail)

private fun hrpInvalid(): SettlementException = settlementRefusal(
    SettlementRejection.HRP_INVALID,
    "Appendix C fixes the human-readable part as `ln`, a network prefix and an OPTIONAL amount, and " +
        "BOLT-11 requires a present amount to be a positive decimal integer with no leading zeroes; " +
        "this invoice's is not one",
)

private fun amountOutOfRange(): SettlementException = settlementRefusal(
    SettlementRejection.AMOUNT_OUT_OF_RANGE,
    "this invoice's human-readable part names more millisatoshis than the ${Msat.SUPPLY_CAP_MSAT} " +
        "msat of total bitcoin supply, which §4.4 bounds every read amount by",
)

private fun fieldTruncated(): SettlementException = settlementRefusal(
    SettlementRejection.FIELD_TRUNCATED,
    "a tagged field is a type group, a two-group `data_length` and that many groups of data, and one " +
        "of this invoice's states a length that runs past the start of the signature — so the rest " +
        "of the data part cannot be walked, and Appendix C's `MUST still parse past it correctly` " +
        "cannot be honoured",
)

private fun expiryOutOfRange(): SettlementException = settlementRefusal(
    SettlementRejection.EXPIRY_OUT_OF_RANGE,
    "an `x` or `c` field of 2^64 seconds or more names a number no 64-bit reader can hold; the bound " +
        "is on the value and not on the field's width, so a wider field holding a smaller number is " +
        "read normally",
)
