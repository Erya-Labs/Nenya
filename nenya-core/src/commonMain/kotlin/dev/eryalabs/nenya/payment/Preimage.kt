package dev.eryalabs.nenya.payment

import dev.eryalabs.nenya.crypto.constantTimeEquals

/**
 * Why a payment-evidence value was refused.
 *
 * The reason is part of the API and not merely diagnostic text, for the same reason
 * `MoneyRejection` is: *uppercase hex* and *malformed hex* are different failures with
 * different fixes on the caller's side, and §9.2 check 2 turns on telling them apart. Tests
 * assert on these constants rather than on message wording.
 */
public enum class PaymentRejection {

    /**
     * A hex value whose character count is not exactly [PaymentHash.HEX_LENGTH]. §4.3: an
     * implementation MUST reject a value of the wrong length rather than padding or
     * truncating it. This covers §18's 31-byte and 33-byte preimage controls and any
     * odd-length input.
     */
    WRONG_LENGTH,

    /** A character outside `0-9`, `a-f`, `A-F`. Not a hex string at all. */
    NOT_HEX,

    /**
     * Uppercase or mixed-case hex where §4.3 permits no normalisation of any kind — which,
     * of the values this package holds, means the [Preimage] and only the [Preimage].
     *
     * §4.3 names exactly two such values, the BOLT-11 invoice string and the Lightning
     * preimage, and states that the exception list is exhaustive. Every other hex value in
     * NENYA-1 — including the payment hash — follows the general accept-and-normalise rule,
     * so [PaymentHash.ofHex] never reports this. Distinct from [NOT_HEX] on purpose: the
     * string *is* hex, and a caller told "malformed" would go looking for the wrong bug.
     */
    UPPERCASE_NOT_PERMITTED,

    /**
     * `SHA-256(preimage)` is not the payment hash it was checked against — §9.2 check 3.
     * This is the load-bearing refusal of the entire document.
     */
    PREIMAGE_MISMATCH,
}

/**
 * A payment-evidence value was refused, carrying the [reason] as data.
 *
 * One exception type for the whole payment-evidence surface, so a caller has one thing to
 * catch and one field to branch on, mirroring `MoneyException` in the money package.
 *
 * **The message never echoes the caller's input.** That is a §12 item 11 requirement here
 * and not merely the hygiene rule it is elsewhere: the input to [Preimage.ofHex] is a
 * preimage, and §12 forbids one appearing in a log or a crash report. A rejection message
 * may name a *length* and a *reason*; it may never name a byte.
 */
public class PaymentException internal constructor(
    public val reason: PaymentRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * The 256-bit `payment_hash` an invoice commits to (§9.2 check 3).
 *
 * ### In **this** package it is a parameter, not a parse
 *
 * §9.2 check 3 defines `payment_hash` as the 256-bit `p` tagged field **parsed out of the
 * BOLT-11 invoice** (Appendix C). This package parses no invoice, so the hash is taken from the
 * caller and the *comparison* is what it implements. That is a deliberate narrowing, stated
 * rather than papered over, and the constant that records it is
 * [PaymentCheck.PAYMENT_HASH_PROVENANCE] — named on every [VerifiedPayment] this package issues,
 * alongside [PaymentCheck.INVOICE_IDENTITY], [PaymentCheck.INVOICE_AMOUNT] and
 * [PaymentCheck.INVOICE_EXPIRY].
 *
 * The settlement package's two doors do the parse and therefore **do** subtract the provenance
 * from their own results: `Settlement.verify` and `Settlement.verifyFeeReceipt` take no payment
 * hash at all, and check 3's operand there is the `p` field of the invoice the client's store
 * holds for that `(order, payee)`. So a caller that wants §9.2 rather than its comparison alone
 * uses those; what stays true here, and is why this type is still published, is that a bare
 * [VerifiedPayment.verify] is handed its operand and can say nothing about where it came from.
 *
 * ### Case
 *
 * Uppercase and mixed-case input is **accepted and normalised**, unlike [Preimage]. §4.3's
 * two-item exception list — the BOLT-11 string and the preimage — is exhaustive, and the
 * payment hash is not on it, so it falls under the general accept-and-normalise rule. The
 * paired controls in this package's tests exist because the two rules are one line apart and
 * an implementation that applies either rule to both values is wrong in one direction or the
 * other.
 *
 * ### The length is checked in `init`, not only in the factory
 *
 * A Kotlin class with a `private constructor` and a factory on its companion still emits a
 * **JVM-public synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which
 * a Java client reaches as `new PaymentHash(bytes, null)`. So "the factory is the only way
 * in" is a property of today's Kotlin call sites and not of this type. The invariant lives in
 * `init`, which every construction path runs, and the array is copied on the way in as well
 * as on the way out — the caller of that synthetic constructor still holds its own reference.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class PaymentHash private constructor(value: ByteArray) {

    private val value: ByteArray

    init {
        requireThirtyTwoBytes(value, "payment hash")
        this.value = value.copyOf()
    }

    /** A fresh copy of the 32 bytes. Copied, so a caller cannot mutate this hash. */
    public fun bytes(): ByteArray = value.copyOf()

    /** The canonical lowercase hex form (§4.3), whatever case it was read in. */
    public fun toHex(): String = encodeLowerHex(value)

    override fun equals(other: Any?): Boolean =
        other is PaymentHash && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /**
     * Deliberately carries no hex.
     *
     * §12 item 11 names key material, decryption keys, preimages and order ids; a payment
     * hash is not on that list, and this type redacts anyway. A payment hash is a per-order
     * correlator visible to the payer's node, the payee's node and every routing hop, so
     * printing it into a host application's log links an order to a Lightning payment for
     * free. [toHex] is there for the caller who genuinely needs the value; a `toString` is
     * never that caller.
     */
    override fun toString(): String = "PaymentHash(redacted)"

    public companion object {

        /** 32 bytes — §9.2 check 3's 256 bits. */
        public const val BYTE_LENGTH: Int = 32

        /** 64 characters — [BYTE_LENGTH] bytes of hex, the length §4.3 fixes. */
        public const val HEX_LENGTH: Int = BYTE_LENGTH * 2

        /**
         * A payment hash read from [HEX_LENGTH] hex characters, in either case.
         *
         * @throws PaymentException [PaymentRejection.WRONG_LENGTH] if the character count is
         *   not [HEX_LENGTH], or [PaymentRejection.NOT_HEX] for a non-hex character.
         */
        public fun ofHex(hex: String): PaymentHash =
            PaymentHash(decodeThirtyTwoBytes(hex, HexCase.NORMALISE_UPPERCASE, "payment hash"))
    }
}

/**
 * A 32-byte Lightning preimage — the only payment evidence NENYA-1 v1 defines (§9.2).
 *
 * The asymmetry that makes it evidence at all: the preimage is a secret held by the **payee**
 * and released only on settlement, so a buyer who claims to have paid can obtain it only by
 * actually paying. Nothing about it is a claim; it is a value whose SHA-256 this library
 * computes for itself (§9.1: no boolean from a wallet, no status string from a counterparty
 * and no relay's acceptance is evidence of anything).
 *
 * ### Lowercase only, and that is not pedantry
 *
 * §9.2 check 2 requires the proof be **lowercase** hex decoding to exactly 32 bytes, and §4.3
 * lists the preimage as one of exactly two values where no normalisation of any kind is
 * permitted. Uppercase is [PaymentRejection.UPPERCASE_NOT_PERMITTED], never
 * [PaymentRejection.NOT_HEX] and never quietly folded: it rides in the same `payment` tag as
 * the byte-identically-compared BOLT-11 string of check 1 and is verified in the same step.
 *
 * ### There is deliberately no `toHex`
 *
 * §12 item 11 and STOP RULE 14: a preimage MUST NOT appear in a log, a crash report, or the
 * string representation of any value this library exposes. [bytes] is the only way out, so
 * the one path that can leak it is a path a caller had to ask for explicitly. [toString] is
 * redacted, and the tests assert that it is — a default Kotlin `toString` would not leak
 * this, but the debugging `toString` somebody adds later would.
 *
 * ### The length is checked in `init`, and that is load-bearing here
 *
 * See [PaymentHash]'s note on the JVM-public synthetic constructor. It matters more on this
 * type: [PaymentCheck.PREIMAGE_SHAPE] is a claim this library makes to its caller that §9.2
 * check 2 — lowercase hex decoding to **exactly** 32 bytes — was performed. A `Preimage` of
 * some other length would make that claim false, which is the §17 over-claim this package
 * exists to prevent. Checking in `init` makes the claim true by construction rather than true
 * of the paths somebody happened to look at.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class Preimage private constructor(value: ByteArray) {

    private val value: ByteArray

    init {
        requireThirtyTwoBytes(value, "preimage")
        this.value = value.copyOf()
    }

    /** A fresh copy of the 32 bytes. Copied, so a caller cannot mutate this preimage. */
    public fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Preimage && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Redacted — §12 item 11, STOP RULE 14. See this type's note. */
    override fun toString(): String = "Preimage(redacted)"

    public companion object {

        /** 32 bytes — §9.2 check 2's exact decoded length. */
        public const val BYTE_LENGTH: Int = PaymentHash.BYTE_LENGTH

        /** 64 characters — [BYTE_LENGTH] bytes of hex. */
        public const val HEX_LENGTH: Int = PaymentHash.HEX_LENGTH

        /**
         * A preimage read from [HEX_LENGTH] **lowercase** hex characters (§9.2 check 2).
         *
         * @throws PaymentException [PaymentRejection.WRONG_LENGTH] if the character count is
         *   not [HEX_LENGTH] — §18's 31-byte and 33-byte controls, and any odd length;
         *   [PaymentRejection.UPPERCASE_NOT_PERMITTED] for an `A`–`F`;
         *   [PaymentRejection.NOT_HEX] for anything else that is not a hex digit.
         */
        public fun ofHex(hex: String): Preimage =
            Preimage(decodeThirtyTwoBytes(hex, HexCase.REJECT_UPPERCASE, "preimage"))
    }
}

/**
 * What a hex reader does with `A`–`F`. An enum rather than a `Boolean` because this package
 * makes "no `Boolean` parameter anywhere on the published surface" a structural rule its own
 * tests enforce, and because `decode(text, true)` at a call site says nothing about which
 * way `true` points.
 */
private enum class HexCase { REJECT_UPPERCASE, NORMALISE_UPPERCASE }

/**
 * The 32-byte invariant, applied on every construction path including the JVM-public
 * synthetic constructor Kotlin emits for the private-constructor-plus-companion-factory
 * idiom. §4.3: reject a value of the wrong length, never pad and never truncate it.
 */
private fun requireThirtyTwoBytes(value: ByteArray, what: String) {
    if (value.size != PaymentHash.BYTE_LENGTH) {
        throw PaymentException(
            PaymentRejection.WRONG_LENGTH,
            "a $what is exactly ${PaymentHash.BYTE_LENGTH} bytes; this one is ${value.size}",
        )
    }
}

/**
 * Both 32-byte hex readers, kept file-private so neither becomes API. §4.3 fixes the length
 * at 64 characters and requires rejection rather than padding or truncation.
 *
 * [what] is a fixed label chosen by the caller in this file — never caller input — so the
 * message cannot echo a preimage back into a log (§12 item 11).
 */
private fun decodeThirtyTwoBytes(text: String, case: HexCase, what: String): ByteArray {
    if (text.length != PaymentHash.HEX_LENGTH) {
        throw PaymentException(
            PaymentRejection.WRONG_LENGTH,
            "a $what is exactly ${PaymentHash.HEX_LENGTH} hex characters, which is " +
                "${PaymentHash.BYTE_LENGTH} bytes; this one is ${text.length} characters, and §4.3 " +
                "requires rejecting a value of the wrong length rather than padding or truncating it",
        )
    }
    val bytes = ByteArray(PaymentHash.BYTE_LENGTH)
    for (i in 0 until PaymentHash.BYTE_LENGTH) {
        bytes[i] = ((nibble(text[2 * i], case, what) shl 4) or nibble(text[2 * i + 1], case, what)).toByte()
    }
    return bytes
}

private fun nibble(character: Char, case: HexCase, what: String): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> when (case) {
        HexCase.NORMALISE_UPPERCASE -> character - 'A' + 10
        HexCase.REJECT_UPPERCASE -> throw PaymentException(
            PaymentRejection.UPPERCASE_NOT_PERMITTED,
            "a $what MUST be lowercase hex and MUST NOT be normalised; §4.3 names it as one of " +
                "exactly two values where an uppercase or mixed-case form is rejected outright",
        )
    }
    else -> throw PaymentException(
        PaymentRejection.NOT_HEX,
        "a $what is hexadecimal characters only; this one carries something else",
    )
}

/** Lowercase, unpadded — §4.3's canonical hex form. */
private fun encodeLowerHex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS: String = "0123456789abcdef"
