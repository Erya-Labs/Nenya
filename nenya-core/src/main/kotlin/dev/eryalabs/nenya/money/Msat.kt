package dev.eryalabs.nenya.money

/**
 * Why a money conversion or a money arithmetic step refused a value.
 *
 * The reason is part of the API and not merely diagnostic text. "Finer than a
 * millisatoshi" and "malformed" are different failures with different fixes on the
 * caller's side, and a caller that cannot tell them apart will report the wrong one to
 * its user. Tests assert on these constants rather than on message wording.
 */
public enum class MoneyRejection {

    /** A negative amount, or a negative multiplier. Nenya has no negative money. */
    NEGATIVE,

    /** Above 21 000 000 BTC expressed in millisatoshis — §4.4's "Bounded" rule. */
    ABOVE_SUPPLY,

    /**
     * A `BTC` decimal carrying more than [Msat.MAX_BTC_FRACTIONAL_DIGITS] fractional
     * digits, i.e. a value finer than one millisatoshi. §4.4's "Never lossy" rule says
     * this MUST fail loudly and MUST NOT round, so it is rejected rather than truncated.
     * Distinct from [MALFORMED]: the string parsed fine, it just cannot be represented.
     */
    SUB_MILLISATOSHI,

    /**
     * A millisatoshi amount used where a satoshi-denominated field is REQUIRED, whose
     * value is not a whole number of satoshis. Also §4.4's "Never lossy" rule.
     */
    NOT_WHOLE_SATOSHIS,

    /** Not a decimal amount at all: empty, signed, spaced, exponential, non-ASCII digits. */
    MALFORMED,

    /** The exact result does not fit in a signed 64-bit integer. Reported, never wrapped. */
    OVERFLOW,
}

/**
 * A money value was refused, carrying the [reason] as data.
 *
 * One exception type for the whole money surface, so a caller has one thing to catch and
 * one field to branch on. It extends [IllegalArgumentException] because every case is a
 * bad input — including [MoneyRejection.OVERFLOW], which here means "these operands are
 * too large for this operation" rather than a machine fault.
 *
 * The message never echoes the caller's input string back. A malformed amount arrives
 * from a relay, from a stranger, and reflecting attacker-controlled text into a host
 * application's log is a hazard the reason code makes unnecessary.
 */
public class MoneyException internal constructor(
    public val reason: MoneyRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * An amount of money in Nenya, in integer millisatoshis, that is never negative.
 *
 * Every amount in this library is millisatoshis (§4.4). The failure this type exists to
 * make impossible is the bare `Long` that means satoshis in one function and
 * millisatoshis in the next: a silent 1000× money bug that appears at the seam between
 * two codebases, which is exactly where nobody is looking. There is no implicit
 * conversion in or out — [ofSat] and [toSatoshis] are the only doors, and [toSatoshis]
 * refuses rather than rounds.
 *
 * ### Two different bounds, on purpose
 *
 * §4.4 bounds an amount **read in** by the bitcoin supply cap, and all three read paths
 * ([ofMsat], [ofSat], [parseBtc]) enforce it. The type itself is bounded only by
 * [Long.MAX_VALUE], because §8.3's `total_msat = price_msat + fee_msat` legitimately
 * reaches 4.2 × 10^18 at the boundary — above the cap and still below 2^63. So an
 * above-cap `Msat` is reachable by arithmetic on in-range amounts and by nothing else,
 * and [requireWithinSupply] is available where a caller needs the §4.4 bound restated.
 *
 * ### Arithmetic
 *
 * [plus] and [times] report [MoneyRejection.OVERFLOW] rather than wrapping. Silent
 * wrapping is the specific danger here: `Long` overflow can land on a *positive* value
 * that survives a casual sanity check (§8.3 names this case), so a wrapped result must
 * never be returned at all.
 *
 * Pure arithmetic: no clock, no randomness, no I/O.
 */
@JvmInline
public value class Msat private constructor(public val millisatoshis: Long) : Comparable<Msat> {

    /**
     * Sum of two amounts, or [MoneyRejection.OVERFLOW] if the exact sum exceeds
     * [Long.MAX_VALUE].
     *
     * Note that two in-supply amounts always sum without overflow (2 × 2.1 × 10^18 is
     * well under 2^63), so §8.3's `total_msat` is always representable.
     */
    public operator fun plus(other: Msat): Msat {
        val sum = millisatoshis + other.millisatoshis
        // Both operands are non-negative, so a sum below either operand has wrapped.
        if (sum < millisatoshis) {
            throw MoneyException(
                MoneyRejection.OVERFLOW,
                "the sum of these two millisatoshi amounts exceeds the 64-bit range",
            )
        }
        return Msat(sum)
    }

    /**
     * This amount multiplied by a non-negative [scalar], or [MoneyRejection.OVERFLOW] if
     * the exact product exceeds [Long.MAX_VALUE]. The overflowing product is never
     * returned in wrapped form; the failure is the result.
     */
    public operator fun times(scalar: Long): Msat {
        if (scalar < 0L) {
            throw MoneyException(MoneyRejection.NEGATIVE, "a millisatoshi amount cannot be scaled by a negative multiplier")
        }
        if (scalar == 0L || millisatoshis == 0L) return ZERO
        // Checked before multiplying rather than after: after is too late, the value has
        // already wrapped and the evidence is gone.
        if (millisatoshis > Long.MAX_VALUE / scalar) {
            throw MoneyException(
                MoneyRejection.OVERFLOW,
                "this millisatoshi amount scaled by that multiplier exceeds the 64-bit range",
            )
        }
        return Msat(millisatoshis * scalar)
    }

    /**
     * This amount as whole satoshis, for a satoshi-denominated field such as the public
     * `["price", "<sats>", "SAT"]` tag.
     *
     * §4.4, "Never lossy": an amount that is not a multiple of 1000 msat is refused with
     * [MoneyRejection.NOT_WHOLE_SATOSHIS]. It is never rounded and never truncated.
     *
     * §4.4's supply bound is checked here too, and not only on the read paths. This is the
     * export door — the one place an amount leaves for a satoshi-denominated field — and no
     * such field can legitimately hold more than the bitcoin supply. An above-cap amount can
     * only have arrived by arithmetic, and letting one out here would put a price on the
     * wire that §4.4 says every implementation MUST reject on the way back in.
     *
     * An amount that is both above supply and not whole satoshis reports
     * [MoneyRejection.ABOVE_SUPPLY]: the bound is the more fundamental refusal, and an
     * amount that may not exist at all is not worth reporting a precision problem about.
     */
    public fun toSatoshis(): Long {
        requireWithinSupply()
        if (millisatoshis % MSAT_PER_SAT != 0L) {
            throw MoneyException(
                MoneyRejection.NOT_WHOLE_SATOSHIS,
                "this millisatoshi amount is not a whole number of satoshis and a satoshi-denominated " +
                    "field is required; §4.4 forbids rounding it",
            )
        }
        return millisatoshis / MSAT_PER_SAT
    }

    /** Whether this amount is at or below the §4.4 bitcoin supply cap. */
    public fun isWithinSupply(): Boolean = millisatoshis <= SUPPLY_CAP_MSAT

    /**
     * This amount, having checked §4.4's supply bound, or [MoneyRejection.ABOVE_SUPPLY].
     * The read paths apply this already; it is public for amounts that arrive by
     * arithmetic.
     */
    public fun requireWithinSupply(): Msat {
        if (!isWithinSupply()) throw aboveSupply()
        return this
    }

    override fun compareTo(other: Msat): Int = millisatoshis.compareTo(other.millisatoshis)

    override fun toString(): String = "$millisatoshis msat"

    public companion object {

        /** `1 SAT = 1000 msat` (§4.4). */
        public const val MSAT_PER_SAT: Long = 1_000L

        /** `1 BTC = 100 000 000 SAT = 100 000 000 000 msat` (§4.4), so 10^11. */
        public const val MSAT_PER_BTC: Long = 100_000_000_000L

        /** The total bitcoin supply in millisatoshis — §4.4's upper bound on a read amount. */
        public const val SUPPLY_CAP_MSAT: Long = 2_100_000_000_000_000_000L

        /**
         * 1 BTC is 10^11 msat, so eleven fractional digits is exactly millisatoshi
         * precision and a twelfth is finer than the unit Nenya can express.
         */
        public const val MAX_BTC_FRACTIONAL_DIGITS: Int = 11

        /** 21 000 000 — the largest whole-BTC part that can be within supply. */
        private const val MAX_BTC_WHOLE: Long = SUPPLY_CAP_MSAT / MSAT_PER_BTC

        /** Guards `toLong()` on the whole part; 21 000 000 has eight digits. */
        private const val MAX_BTC_WHOLE_DIGITS: Int = 8

        /** The largest satoshi amount within supply. */
        private const val MAX_SAT: Long = SUPPLY_CAP_MSAT / MSAT_PER_SAT

        /**
         * §4.3 requires a bound on the size of anything parsed, and requires rejecting
         * rather than truncating when it is exceeded. The longest legitimate BTC amount is
         * `21000000.00000000001` at twenty characters; thirty-two leaves room for the
         * leading zeros a permissive read accepts, and stops a relay handing this parser a
         * hundred megabytes of digits to scan and copy.
         */
        private const val MAX_BTC_TEXT_LENGTH: Int = 32

        public val ZERO: Msat = Msat(0L)

        /** The §4.4 supply cap as an amount. */
        public val SUPPLY_CAP: Msat = Msat(SUPPLY_CAP_MSAT)

        /**
         * An amount read as millisatoshis. Refuses a negative value and, per §4.4,
         * anything above the supply cap.
         */
        public fun ofMsat(millisatoshis: Long): Msat {
            if (millisatoshis < 0L) throw negative()
            if (millisatoshis > SUPPLY_CAP_MSAT) throw aboveSupply()
            return Msat(millisatoshis)
        }

        /**
         * An amount read as whole satoshis, converted to millisatoshis. Refuses a
         * negative value and, per §4.4, anything above the supply cap.
         */
        public fun ofSat(satoshis: Long): Msat {
            if (satoshis < 0L) throw negative()
            // Bounded before the multiply, so the multiply cannot overflow.
            if (satoshis > MAX_SAT) throw aboveSupply()
            return Msat(satoshis * MSAT_PER_SAT)
        }

        /**
         * An amount read as a decimal `BTC` string, per §4.4's permissive-on-read rule.
         *
         * Accepted: one or more ASCII digits, optionally followed by `.` and one to
         * [MAX_BTC_FRACTIONAL_DIGITS] further ASCII digits. Leading zeros are accepted
         * on read. Everything else — a sign, a space, an exponent, a bare `.5` or `5.`,
         * a non-ASCII digit — is [MoneyRejection.MALFORMED].
         *
         * The input is length-bounded first, per §4.3; an over-long string is
         * [MoneyRejection.MALFORMED] and is refused before it is scanned, never truncated.
         *
         * A twelfth fractional digit is [MoneyRejection.SUB_MILLISATOSHI], not malformed:
         * §4.4 requires that a value finer than a millisatoshi fail loudly rather than be
         * rounded, and the caller needs to know which of the two happened.
         *
         * The whole computation is integer arithmetic on digit substrings. No `Double`
         * and no `BigDecimal` appear anywhere in it — §4.4's "Never floating point".
         */
        public fun parseBtc(decimal: String): Msat {
            if (decimal.isEmpty()) throw malformed("an empty string is not a BTC amount")
            // Bounded before anything scans or copies it (§4.3). Rejected, not truncated.
            if (decimal.length > MAX_BTC_TEXT_LENGTH) {
                throw malformed("a BTC amount is at most $MAX_BTC_TEXT_LENGTH characters; this one is longer")
            }

            val dot = decimal.indexOf('.')
            val wholePart: String
            val fractionPart: String
            if (dot < 0) {
                wholePart = decimal
                fractionPart = ""
            } else {
                if (decimal.indexOf('.', dot + 1) >= 0) {
                    throw malformed("a BTC amount carries at most one decimal point")
                }
                wholePart = decimal.substring(0, dot)
                fractionPart = decimal.substring(dot + 1)
                if (fractionPart.isEmpty()) {
                    throw malformed("a decimal point must be followed by at least one digit")
                }
            }
            if (wholePart.isEmpty()) {
                throw malformed("a BTC amount must carry at least one digit before the decimal point")
            }
            // Deliberately not Char.isDigit(), which accepts Arabic-Indic and other
            // non-ASCII digits that String.toLong() would then happily parse.
            if (!wholePart.isAsciiDigits() || !fractionPart.isAsciiDigits()) {
                throw malformed("a BTC amount is ASCII digits with an optional decimal point, nothing else")
            }

            if (fractionPart.length > MAX_BTC_FRACTIONAL_DIGITS) {
                throw MoneyException(
                    MoneyRejection.SUB_MILLISATOSHI,
                    "a BTC amount carries at most $MAX_BTC_FRACTIONAL_DIGITS fractional digits, which is " +
                        "exactly millisatoshi precision; this one carries ${fractionPart.length} and so names " +
                        "a value finer than one millisatoshi, which §4.4 forbids rounding",
                )
            }

            val whole = wholePart.trimStart('0')
            val wholeBtc = when {
                whole.isEmpty() -> 0L
                // Guarded so the toLong() below cannot throw on a 20-digit run of digits.
                whole.length > MAX_BTC_WHOLE_DIGITS -> throw aboveSupply()
                else -> whole.toLong()
            }
            if (wholeBtc > MAX_BTC_WHOLE) throw aboveSupply()

            // Right-padding to eleven digits turns the fractional part into millisatoshis
            // directly: "00000000001" is 1 msat, "5" is 0.5 BTC is 50 000 000 000 msat.
            val fractionMsat =
                if (fractionPart.isEmpty()) 0L
                else fractionPart.padEnd(MAX_BTC_FRACTIONAL_DIGITS, '0').toLong()

            // wholeBtc <= 21 000 000, so the product is at most the cap and cannot overflow.
            return ofMsat(wholeBtc * MSAT_PER_BTC + fractionMsat)
        }

        private fun String.isAsciiDigits(): Boolean = all { it in '0'..'9' }

        private fun negative(): MoneyException =
            MoneyException(MoneyRejection.NEGATIVE, "a Nenya amount cannot be negative")

        private fun aboveSupply(): MoneyException =
            MoneyException(
                MoneyRejection.ABOVE_SUPPLY,
                "this amount is above the total bitcoin supply of $SUPPLY_CAP_MSAT msat, which §4.4 bounds a read amount by",
            )

        private fun malformed(detail: String): MoneyException =
            MoneyException(MoneyRejection.MALFORMED, detail)
    }
}
