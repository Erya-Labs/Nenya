package dev.eryalabs.nenya.money

/**
 * The fee term of an order — §8.1's `["fee", ...]` tag reduced to the part §8.3's
 * arithmetic uses, plus the distinction §8.1's read rule turns on.
 *
 * ### Why "absent" is a case and not a zero
 *
 * §8.1 says a missing `fee` tag on an order proposal MUST be read as zero fee, and that an
 * implementation reading one that way MUST subsequently refuse **every** fee invoice for
 * that order. §8.4 then says the pair for such an order is `(0, —)` for every later check.
 * So [Absent] and `Stated(0)` agree on the number and disagree on the wire: the first
 * carries no tag, the second carries the two-element `["fee", "0"]` that §8.1 says a
 * proposal SHOULD carry so the absence of a fee is itself a signed statement. Collapsing
 * them into a bare `0` would throw that away, and it is the pair that has to be reproduced
 * byte-identically at four separate points (§8.4).
 *
 * ### What this type deliberately does not hold
 *
 * The recipient pubkey. §8.1 makes it REQUIRED when basis points are greater than zero and
 * OMITTED when they are zero, and validating a 64-character lowercase x-only hex key —
 * along with the reachability check §8.1 requires before a fee term is proposed — belongs
 * to the tag codec, which does not exist yet. [namesRecipient] records *whether* §8.1
 * requires one, which is all §8.3 and §9.2 need in order to say which payees are required.
 *
 * ### No local policy limit lives here
 *
 * §8.1 fixes the legal range at `0..10000` inclusive and forbids refusing an in-range value
 * on the ground that it is too high. A client MAY hold its own lower limit, but §8.1
 * requires that refusal to surface as its own distinct, named condition — *above this
 * client's local policy limit* — and never as a malformed or unsupported term. That is a
 * decision about whether a client will transact, taken above this library with a limit this
 * library does not know; conflating it with the wire-validity check here is exactly what
 * §8.1 forbids, because it would make a legal peer look broken. So [of] refuses `10001` and
 * accepts `10000`, and nothing in between is a policy question.
 *
 * Pure arithmetic: no clock, no randomness, no I/O.
 */
public sealed interface FeeTerm {

    /** Basis points, always within `0..`[MAX_BASIS_POINTS]. 1 bps = 0.01%; `250` is 2.5%. */
    public val basisPoints: Int

    /**
     * Whether §8.1 requires this term to name a fee recipient, i.e. whether its basis
     * points are greater than zero. Note that this is about the *term*, not the amount: a
     * term can name a recipient and still compute a fee of zero (§8.3), which is why
     * [FeeSplit.feePayeeRequired] is a separate question with a separate answer.
     */
    public val namesRecipient: Boolean

    /**
     * The fee this term levies on [price], as §8.3 defines it:
     * `fee_msat = floor(price_msat × bps / 10000)`.
     *
     * Computed by the exact 64-bit decomposition §8.3 publishes as its second conformant
     * route:
     *
     * ```
     * fee_msat = (price_msat / 10000) * bps + ((price_msat % 10000) * bps) / 10000
     * ```
     *
     * §8.3 offers arbitrary precision as the other route and mandates neither. This one is
     * implemented because it is what a reference implementation should demonstrate: every
     * language has 64-bit integers and not every language has `BigInteger`, so the split is
     * the route a re-implementer in a smaller language actually needs to see written down.
     *
     * The naive `price_msat * bps` is what this exists to avoid. At the supply cap and
     * `10000` bps the true product is 2.1 × 10^22, which exceeds 2^63 — and the wrapped
     * value is *positive*, so it survives a sanity check that only asks whether the answer
     * went negative. Neither term below can overflow: the first is `(price / 10000) * bps`
     * with `bps ≤ 10000`, so it is at most `price`; the second's numerator is at most
     * `9999 × 10000`, under 10^8.
     *
     * Both operands are non-negative, so the integer division truncates toward zero and
     * truncation is therefore floor — never ceiling (§8.3). §8.3's satoshi rounding is not
     * applied here and is dormant in v1: BOLT-11 expresses millisatoshis, and Lightning is
     * the only rail v1 defines an evidence rule for, so no v1 payment request is rounded.
     *
     * @throws MoneyException [MoneyRejection.ABOVE_SUPPLY] if [price] is above §4.4's
     *   supply cap. §4.4 bounds `price_msat` there precisely so that this arithmetic is
     *   expressible without overflow, and an above-cap amount can only have reached here by
     *   arithmetic on a total (§8.3's `total_msat`), which is not a price.
     */
    public fun feeOn(price: Msat): Msat {
        price.requireWithinSupply()
        val priceMsat = price.millisatoshis
        val bps = basisPoints.toLong()
        val whole = (priceMsat / BASIS_POINT_DIVISOR) * bps
        val remainder = ((priceMsat % BASIS_POINT_DIVISOR) * bps) / BASIS_POINT_DIVISOR
        return Msat.ofMsat(whole + remainder)
    }

    /**
     * The whole §8.3 arithmetic for [price] under this term: the price, the fee and the
     * total the buyer pays, together with whether a fee payee is required at all.
     */
    public fun splitOn(price: Msat): FeeSplit = FeeSplit(price, this)

    /**
     * No `fee` tag at all. §8.1's read rule: this MUST be read as zero fee, MUST NOT be
     * refused as incomplete terms, and every fee invoice for the order MUST subsequently
     * be refused.
     */
    public data object Absent : FeeTerm {
        override val basisPoints: Int get() = 0
        override val namesRecipient: Boolean get() = false
    }

    /**
     * An explicit `["fee", ...]` term stating [basisPoints]. Construct with [of].
     *
     * §8.1's range is enforced in the `init` block rather than only in the factory, so no
     * construction path can bypass it. A `Stated` above [MAX_BASIS_POINTS] would levy a fee
     * larger than the price, and "the factory is the only caller" is a property of today's
     * code rather than of this type.
     */
    public class Stated internal constructor(override val basisPoints: Int) : FeeTerm {

        init {
            if (basisPoints < 0) {
                throw MoneyException(MoneyRejection.NEGATIVE, "a fee cannot be a negative number of basis points")
            }
            if (basisPoints > MAX_BASIS_POINTS) {
                throw MoneyException(
                    MoneyRejection.BPS_ABOVE_MAXIMUM,
                    "a fee term is at most $MAX_BASIS_POINTS basis points, which is 100%; §8.1 rejects more",
                )
            }
        }

        override val namesRecipient: Boolean get() = basisPoints > 0

        override fun equals(other: Any?): Boolean =
            other is Stated && other.basisPoints == basisPoints

        override fun hashCode(): Int = basisPoints

        override fun toString(): String = "$basisPoints bps"
    }

    public companion object {

        /**
         * §8.3's `10000` in its role as the divisor: 1 bps = 1/10000. A `Long` because it
         * divides a millisatoshi amount, and mixing the widths is how the intermediate
         * this decomposition exists to avoid gets reintroduced.
         */
        public const val BASIS_POINT_DIVISOR: Long = 10_000L

        /**
         * §8.1's `10000` in its role as the ceiling: 100%, inclusive, and NENYA-1 imposes
         * no ceiling below it. Numerically the same as [BASIS_POINT_DIVISOR] and a
         * different fact — one is what a basis point *is*, the other is how many of them
         * may be charged.
         */
        public const val MAX_BASIS_POINTS: Int = 10_000

        /**
         * A stated fee term of [basisPoints].
         *
         * @throws MoneyException [MoneyRejection.NEGATIVE] below zero, or
         *   [MoneyRejection.BPS_ABOVE_MAXIMUM] above [MAX_BASIS_POINTS]. Never for being
         *   merely large: see this type's note on local policy limits.
         */
        public fun of(basisPoints: Int): Stated = Stated(basisPoints)
    }
}

/**
 * §8.3's three amounts for one order, computed together.
 *
 * ```
 * price_msat = <amount_msat from the accepted terms>   // what the PROVIDER RECEIVES
 * fee_msat   = floor(price_msat × bps / 10000)         // an added line item on top
 * total_msat = price_msat + fee_msat                   // what the BUYER PAYS
 * ```
 *
 * §8.2 is the reason these are three numbers and not two: the fee is added on top of the
 * price, never subtracted from it, so the provider receives exactly the price they quoted
 * and the fee is visible to both sides.
 *
 * Obtain one from [FeeTerm.splitOn].
 */
public class FeeSplit internal constructor(

    /** What the provider receives (§8.2). At or below §4.4's supply cap. */
    public val price: Msat,

    /** The term the fee was computed under, including whether there was a term at all. */
    public val term: FeeTerm,
) {

    /**
     * `floor(price_msat × bps / 10000)` (§8.3). Never greater than [price].
     *
     * Derived from [price] and [term] rather than passed in. A split whose fee disagreed
     * with its own signed terms is the exact shape §8.4 aborts an order over, and deriving
     * it here makes that disagreement unrepresentable rather than merely unreachable.
     */
    public val fee: Msat = term.feeOn(price)

    /**
     * What the buyer pays: `price_msat + fee_msat` (§8.3).
     *
     * This can legitimately exceed §4.4's supply cap — at the cap and `10000` bps it is
     * 4.2 × 10^18, which §8.3 pins as the boundary example and which is still below 2^63.
     * That is why [Msat] itself is not capped: a type that refused this could not represent
     * a conformant §8.3 total. It cannot overflow, because both addends are at or below the
     * cap.
     */
    public val total: Msat = price + fee

    /**
     * Whether a fee payee is required for this order, and therefore whether a fee invoice
     * may exist at all.
     *
     * §9.2: "a payee is required when the terms name it **and** its expected amount is
     * non-zero". Both halves matter, and the second is the one that bites. §8.3 calls out
     * that a computed fee of zero is reachable with a *non-zero* `bps` — at `bps = 1` and
     * `price_msat = 3000` the fee is `floor(3000 × 1 / 10000) = 0` — leaving an order with
     * a fee payee named by its terms and no fee invoice that may legally exist. §8.3, §9.2
     * and §11.1 all say the same thing about it: an implementation that requires an invoice
     * per *named* payee deadlocks every such order into `expired`.
     *
     * So this is computed from the amount, not from the term. `false` means no fee invoice
     * may be sent or accepted and none may be awaited.
     *
     * The left conjunct is redundant today and kept deliberately. `namesRecipient` is true
     * exactly when the basis points are above zero, and zero basis points always compute to
     * a zero fee, so no input can make the two clauses disagree and no test can kill it —
     * that is a fact about §8.1's arity rule, not a coverage gap. It is written out because
     * §9.2 states the rule as two clauses and because the redundancy is a coincidence of
     * this stage: once a fee term carries its recipient, "the terms name a payee" and "the
     * basis points are non-zero" stop being the same question.
     */
    public val feePayeeRequired: Boolean = term.namesRecipient && fee > Msat.ZERO

    override fun equals(other: Any?): Boolean =
        other is FeeSplit && other.price == price && other.term == term && other.fee == fee

    override fun hashCode(): Int = (price.hashCode() * 31 + term.hashCode()) * 31 + fee.hashCode()

    override fun toString(): String = "FeeSplit(price=$price, term=$term, fee=$fee, total=$total)"
}
