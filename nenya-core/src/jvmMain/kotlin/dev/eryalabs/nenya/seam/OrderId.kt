package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.crypto.constantTimeEquals

/**
 * Why a seam refused to produce a value.
 *
 * The reason is part of the API and not merely diagnostic text, for the same reason
 * `MoneyRejection` and `PaymentRejection` are: "no randomness was injected" and "the injected
 * source returned the wrong number of bytes" are different failures with different fixes on the
 * caller's side. Tests assert on these constants rather than on message wording.
 */
public enum class SeamRejection {

    /**
     * The injected [Randomness] answered [SeamAnswer.Unavailable] — which is what the default
     * does. §7.4 requires 32 bytes from a cryptographically secure source, and there is no
     * fallback: a library that reached for an ambient source here would be minting order ids
     * from something the client never chose.
     */
    RANDOMNESS_UNAVAILABLE,

    /**
     * The injected [Randomness] returned a number of bytes other than the 32 it was asked for.
     *
     * Refused in **both** directions and never repaired. Padding a short draw would mint an
     * order id with known bytes in it; truncating a long one would silently discard entropy the
     * source thought it was providing, and either way the value would no longer be the 32 random
     * bytes §7.4 requires. §4.3 says the same thing about every value of the wrong length.
     */
    RANDOMNESS_WRONG_LENGTH,

    /** A hex value whose character count is not exactly [OrderId.HEX_LENGTH] (§4.3, §7.4). */
    WRONG_LENGTH,

    /** A character outside `0-9`, `a-f`, `A-F`. Not a hex string at all. */
    NOT_HEX,
}

/**
 * A seam refused to produce a value, carrying the [reason] as data.
 *
 * Extends [IllegalStateException] rather than [IllegalArgumentException], which is where this
 * differs from `MoneyException` and `PaymentException` deliberately: those report a bad value the
 * caller passed, while the two randomness reasons here report an injected component behaving
 * other than its contract. [SeamRejection.WRONG_LENGTH] and [SeamRejection.NOT_HEX] are
 * argument failures and travel in the same type because a caller wants one thing to catch and
 * one field to branch on.
 *
 * **The message never echoes the caller's input.** §12 item 11 names order ids alongside key
 * material and preimages as values that MUST NOT appear in a log or a crash report, and an
 * exception message is a crash report waiting to happen. A message here may name a *length* and
 * a *reason*; it may never name a byte.
 */
public class SeamException internal constructor(
    public val reason: SeamRejection,
    message: String,
) : IllegalStateException(message)

/**
 * The 32-byte order id of §7.4 — the handle every `kind:15`, `kind:16` and `kind:17` rumor in an
 * order thread carries, and the one value in NENYA-1 that MUST come from randomness.
 *
 * ### §7.4's rule is structural, not advisory
 *
 * > The **order id** MUST be 32 bytes from a cryptographically secure random source, generated
 * > by the buyer when proposing. It MUST NOT be derived from the coordinate, either pubkey, the
 * > price, or the time — a derived order id is a correlation handle for anyone who later learns
 * > the inputs.
 *
 * That last clause is a privacy rule with teeth: an id derived from, say, the listing coordinate
 * and the buyer's pubkey lets anyone who later learns those two values confirm the buyer bought
 * that listing — from a gift wrap they cannot decrypt, because the id travels in a tag. So
 * [mint] takes **the randomness source and nothing else**. There is no overload that accepts a
 * coordinate, a pubkey, a price or a clock reading, and the structural half of that rule is a
 * reflection test asserting this function's parameter list is exactly one [Randomness]: a rule
 * expressed as an absence needs a test that fails when the absence ends.
 *
 * ### It is redacted everywhere
 *
 * §12 item 11 names order ids in one sentence with key material, decryption keys and preimages
 * as values that MUST NOT appear in a log, a crash report, analytics, or the string
 * representation of any value the implementation exposes. [toString] therefore carries no hex,
 * and a test asserts it. A default Kotlin `toString` would not leak this; the debugging
 * `toString` somebody adds in six months would, and that is what the test catches.
 *
 * ### The length is checked in `init`
 *
 * A Kotlin class with a `private constructor` and a factory on its companion still emits a
 * **JVM-public synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which a
 * Java client reaches as `new OrderId(bytes, null)`. So "the factory is the only way in" is a
 * property of today's Kotlin call sites and not of this type. The invariant lives in `init`,
 * which every construction path runs, and the array is copied on the way in as well as on the
 * way out — the caller of that synthetic constructor still holds its own reference.
 */
public class OrderId private constructor(value: ByteArray) {

    private val value: ByteArray

    init {
        if (value.size != BYTE_LENGTH) {
            throw SeamException(
                SeamRejection.WRONG_LENGTH,
                "an order id is exactly $BYTE_LENGTH bytes (§7.4); this one is ${value.size}, and " +
                    "§4.3 requires rejecting a value of the wrong length rather than padding or " +
                    "truncating it",
            )
        }
        this.value = value.copyOf()
    }

    /** A fresh copy of the 32 bytes. Copied, so a caller cannot mutate this id. */
    public fun bytes(): ByteArray = value.copyOf()

    /**
     * The canonical lowercase hex form §7.4 puts in an `["order", ...]` tag, whatever case it
     * was read in (§4.3: implementations MUST emit lowercase hex).
     *
     * The one way to get the value out, and therefore the one place a leak can start. That is
     * deliberate: a caller that writes an order id into a log had to ask for it by name.
     */
    public fun toHex(): String = encodeLowerHex(value)

    override fun equals(other: Any?): Boolean =
        other is OrderId && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Redacted — §12 item 11. See this type's note; [toHex] is the way out. */
    override fun toString(): String = "OrderId(redacted)"

    public companion object {

        /** 32 bytes — §7.4's exact length. */
        public const val BYTE_LENGTH: Int = 32

        /** 64 characters — [BYTE_LENGTH] bytes of hex, the length §4.3 fixes for an order id. */
        public const val HEX_LENGTH: Int = BYTE_LENGTH * 2

        /**
         * Mint a fresh order id from the injected randomness source, and from nothing else.
         *
         * Exactly [BYTE_LENGTH] bytes are drawn, in one call. §7.4 forbids deriving the id from
         * the coordinate, either pubkey, the price or the time, so none of those is a parameter
         * — see this type's note on why that absence is asserted by a test rather than trusted.
         *
         * @param randomness the source, defaulting to [Randomness.FAIL_CLOSED], which mints
         *   nothing. A default that reached for `SecureRandom` would be a live ambient effect in
         *   a library whose whole test strategy is that there are none.
         * @throws SeamException [SeamRejection.RANDOMNESS_UNAVAILABLE] when the source declined,
         *   or [SeamRejection.RANDOMNESS_WRONG_LENGTH] when it returned some other number of
         *   bytes — never padded out to length, never truncated.
         */
        public fun mint(randomness: Randomness = Randomness.FAIL_CLOSED): OrderId {
            val drawn = randomness.randomBytes(BYTE_LENGTH)
            val bytes = when (drawn) {
                is SeamAnswer.Provided -> drawn.value
                is SeamAnswer.Unavailable -> throw SeamException(
                    SeamRejection.RANDOMNESS_UNAVAILABLE,
                    "the injected randomness source answered unavailable (${drawn.capability.name}), " +
                        "and §7.4 requires an order id be 32 bytes from a cryptographically secure " +
                        "random source; there is no fallback",
                )
            }
            if (bytes.size != BYTE_LENGTH) {
                throw SeamException(
                    SeamRejection.RANDOMNESS_WRONG_LENGTH,
                    "the injected randomness source was asked for $BYTE_LENGTH bytes and returned " +
                        "${bytes.size}; §7.4's order id is not padded out to length and not truncated " +
                        "to it",
                )
            }
            return OrderId(bytes)
        }

        /**
         * An order id read off the wire, from [HEX_LENGTH] hex characters in either case.
         *
         * Uppercase and mixed-case input is **accepted and normalised**. §4.3's exception list —
         * the BOLT-11 invoice string and the Lightning preimage — is exhaustive and explicitly
         * names the order id as following the general accept-and-normalise rule instead.
         *
         * @throws SeamException [SeamRejection.WRONG_LENGTH] if the character count is not
         *   [HEX_LENGTH], or [SeamRejection.NOT_HEX] for a non-hex character.
         */
        public fun ofHex(hex: String): OrderId {
            if (hex.length != HEX_LENGTH) {
                throw SeamException(
                    SeamRejection.WRONG_LENGTH,
                    "an order id is exactly $HEX_LENGTH hex characters, which is $BYTE_LENGTH bytes " +
                        "(§4.3, §7.4); this one is ${hex.length} characters",
                )
            }
            val bytes = ByteArray(BYTE_LENGTH)
            for (i in 0 until BYTE_LENGTH) {
                bytes[i] = ((nibble(hex[2 * i]) shl 4) or nibble(hex[2 * i + 1])).toByte()
            }
            return OrderId(bytes)
        }
    }
}

/**
 * §4.3's accept-and-normalise hex rule, for the one value in this package that follows it.
 *
 * File-private so it never becomes API, and it takes no label: unlike the payment package's
 * reader it has exactly one caller, and a message here must not name the value being read
 * (§12 item 11).
 */
private fun nibble(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> throw SeamException(
        SeamRejection.NOT_HEX,
        "an order id is hexadecimal characters only (§4.3); this one carries something else",
    )
}

/** Lowercase, unpadded — §4.3's canonical hex form, which implementations MUST emit. */
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
