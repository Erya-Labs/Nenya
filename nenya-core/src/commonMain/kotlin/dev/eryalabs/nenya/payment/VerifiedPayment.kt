package dev.eryalabs.nenya.payment

import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.crypto.constantTimeEquals
import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.Msat
import kotlin.jvm.JvmSynthetic

/**
 * The two payee roles §8.6 defines, in the tokens it writes them in.
 *
 * §8.6 is the non-custodial rule: the buyer pays the provider and the fee recipient with two
 * separate payments to two separate invoices, each tagged with its role. There is no third
 * role and no combined invoice; a single invoice covering `total_msat` that some party then
 * splits is custody, and NENYA-1 has no version of that design.
 */
public enum class Payee(

    /** The literal `["payee", ...]` token of §8.6 — `provider` or `fee`. */
    public val token: String,
) {

    /** Receives exactly `price_msat` (§8.2, §8.6). Named by the terms of every order. */
    PROVIDER("provider"),

    /** Receives exactly `fee_msat` (§8.3, §8.6). Named only when the fee term names it. */
    FEE("fee");

    public companion object {

        /**
         * The payees a receipt is actually **required** from before an order may become
         * `paid`, per §9.2's "which payees are required" clause: a payee is required when the
         * terms name it **and** its expected amount is non-zero.
         *
         * Both halves bite, in different places.
         *
         * - The fee half is [FeeSplit.feePayeeRequired], **consumed rather than re-derived**.
         *   §8.3 calls out that a computed fee of `0` is reachable with a non-zero `bps` —
         *   at `bps = 1` and `price_msat = 3000` the fee is `floor(3000 × 1 / 10000) = 0` —
         *   so an order can name a fee payee for whom no invoice may legally exist. §8.3,
         *   §9.2 and §11.1 all say the same thing about an implementation that waits for one:
         *   it deadlocks the order into `expired`. T2 computed that flag from the amount and
         *   not from the term for exactly this caller, and two copies of a deadlock rule is
         *   how the two drift apart.
         * - The provider half is §9.2's generic clause applied to `price_msat`. A price of
         *   `0` is not called out anywhere as its own case, and it falls out of the same
         *   sentence: no invoice may exist for an expected amount of zero (§8.6 requires the
         *   provider's request be for *exactly* `price_msat`, and §9.2 check 4 rejects a
         *   zero-amount invoice), so none may be awaited.
         *
         * The returned set is fixed at call time and iterates in declaration order. An empty
         * set is a legitimate answer — a zero-price, zero-fee order requires no receipt at
         * all — and is not the same as "unknown".
         */
        public fun requiredPayees(split: FeeSplit): Set<Payee> {
            val required = LinkedHashSet<Payee>(2)
            if (split.price > Msat.ZERO) required.add(PROVIDER)
            // `split.fee > Msat.ZERO` is the same answer today and must not be written here.
            // No behavioural test can tell the two apart, so the rule against a second copy
            // of the deadlock clause is kept by reading it from the one place that owns it —
            // see FeeSplit.feePayeeRequired's KDoc on why its two clauses stop being the same
            // question once a fee term carries its recipient.
            if (split.feePayeeRequired) required.add(FEE)
            return required
        }
    }
}

/**
 * One of §9.2's six checks, named so that what this library did and did not do is
 * machine-readable rather than a paragraph in a README.
 *
 * §17 requires an implementation that omits a check to expose a capability surface — a
 * machine-readable statement of what it verifies — and MUST NOT report unverified things as
 * verified. Every [VerifiedPayment] therefore carries both [VerifiedPayment.checksPerformed]
 * and [VerifiedPayment.checksNotPerformedHere], and a caller can branch on either.
 *
 * §9.2 check 6 is three separate obligations that happen to share a numeral, so it is three
 * constants here: a caller that acquires the tag codec closes [FEE_TERM_MATCH] without
 * acquiring gift-wrap machinery, and conflating them would make the record unactionable.
 */
public enum class PaymentCheck {

    /**
     * §9.2 check 1, and **only** check 1 — the BOLT-11 string is byte-identical to the one in
     * the stored `type=2` payment request for the same order and payee. One obligation, one
     * constant.
     *
     * It used to carry a second: the provenance of the payment hash check 3 compares against.
     * That is now [PAYMENT_HASH_PROVENANCE], and the split matters because the two are
     * performed in different places at different times. The comparison needs no parser, and
     * [dev.eryalabs.nenya.settlement.Settlement.verify] performs it against the persisted
     * store of accepted payment requests that §17 item 6 requires — recording it in **that**
     * result's own performed-set. While the two shared a constant, that path subtracted the
     * whole of it, so a result reported check 3's provenance closed when nothing had parsed an
     * invoice at all. A refusal reading such a record would let an order past on a payment hash
     * that was still a caller-supplied parameter.
     *
     * A bare [VerifiedPayment.verify] performs this check no more than it performs the
     * provenance: it holds no store, so this constant stays in
     * `Capabilities.PAYMENT_CHECKS_NOT_PERFORMED` and [VerifiedPayment.CHECKS_PERFORMED_HERE]
     * MUST NOT be widened to claim otherwise.
     */
    INVOICE_IDENTITY,

    /** §9.2 check 2 — the proof is lowercase hex decoding to exactly 32 bytes. */
    PREIMAGE_SHAPE,

    /**
     * §9.2 check 3's **comparison only**: `SHA-256(preimage)` equals the payment hash this
     * library was handed. The name says `COMPARISON` because check 3 as the specification
     * states it also fixes where `payment_hash` comes from, and that provenance is
     * [PAYMENT_HASH_PROVENANCE]'s — a separate constant, in the *not*-performed set. A consumer
     * reading `PREIMAGE_HASH` alone would over-read it, so the constant does not exist under
     * that name.
     */
    PREIMAGE_HASH_COMPARISON,

    /**
     * §9.2 check 3's **operand**: the payment hash compared against was the 256-bit `p` tagged
     * field parsed out of the BOLT-11 invoice (Appendix C), and not a value the caller chose.
     *
     * Nothing in this library performs it. `Bolt11Reference` recognises the *shape* of an
     * invoice and slices no tagged field, so [PaymentHash] arrives at every entry point here as
     * a parameter — which means a caller that hands in `SHA-256(its own preimage)` gets a
     * `VerifiedPayment` whose comparison is true and whose subject is an invoice nobody issued.
     * That is the whole content of this constant, and it is worth one of its own: a record that
     * folded it into [INVOICE_IDENTITY] reported it performed the moment check 1's byte
     * comparison succeeded, which is the over-claim §17 forbids and the one a refusal that
     * reads this record must not inherit.
     *
     * In both [VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER] and
     * [VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE], because check 3 applies to every receipt.
     */
    PAYMENT_HASH_PROVENANCE,

    /**
     * §9.2 check 4 — the amount in the invoice's human-readable part equals the expected
     * amount for that payee, and a zero-amount invoice is rejected. Needs the BOLT-11 parser.
     * This is the check a provider who sends a receipt for ten times `price_msat` is caught
     * by, and by nothing else.
     */
    INVOICE_AMOUNT,

    /**
     * §9.2 check 5 — `timestamp + expiry` was not in the past against the clock reading taken
     * when the invoice was accepted. Needs the BOLT-11 parser *and* the persisted acceptance
     * clock reading of check 1.
     */
    INVOICE_EXPIRY,

    /**
     * §9.2 check 6, first obligation — the fee term matches (§8.4).
     *
     * Performed by `Settlement.verifyFeeReceipt`, over the **raw** tag elements at every point
     * §8.4 names, and recorded in *that* result's own performed-set. Not by a bare [verify], which
     * has no tags to compare and whose result therefore still names this constant as unperformed.
     */
    FEE_TERM_MATCH,

    /**
     * §9.2 check 6, second obligation — the sealing key is the fee recipient's (§8.7).
     *
     * Performed on the same path, and against the seal the stored `type=2` arrived under rather
     * than the receipt's own: a fee receipt is authored by the buyer (§9.2's worked example), so
     * the literal reading would refuse every conformant one. See `Settlement.verifyFeeReceipt`.
     * The equality is checked; no BIP-340 signature behind it is verified by anybody here.
     */
    FEE_SEALING_KEY,

    /**
     * §9.2 check 6, third obligation — the order is already `awaiting_payment` (§8.5).
     *
     * Performed on the same path, read from the order the state machine produced and never from a
     * status token a counterparty sent. It governs the fee **receipt** and deliberately not the
     * fee payment **request**, which §8.5 accepts as part of `committed → awaiting_payment`.
     */
    FEE_STATE_PRECONDITION,
}

/**
 * Evidence that a Lightning payment was made, produced only by [verify] and only from a
 * preimage whose SHA-256 this library computed itself.
 *
 * ### Unforgeable through the published API
 *
 * There is no way for an embedding client to construct one of these except by handing
 * [verify] a preimage that actually hashes to the payment hash. That is the point: §9.1 says
 * a wallet's `isPaid`, a counterparty's status string and a relay's acceptance are not
 * evidence, and a type anyone can instantiate is exactly as good as a boolean.
 *
 * The shape is load-bearing and two near-misses are not good enough:
 *
 * - a class with a `private constructor` and a factory on its companion object still emits a
 *   **public synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which
 *   `Class.getConstructors()` reports and which a Java client can call with a `null` marker;
 * - a `data class` with a private primary constructor is still reachable through the
 *   generated `copy()`.
 *
 * So this is an interface, which has no constructor to synthesise an accessor for, and its
 * single implementation is a `private` class nested inside its companion. `getConstructors()`
 * on the interface is empty by construction and stays empty.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: a
 * `private` nested class compiles to a package-private JVM class with a public constructor,
 * so a client that declares itself into `dev.eryalabs.nenya.payment` on the same classloader
 * can still reach it. Nenya protects its user against counterparties and relays, not against
 * the client embedding it.
 *
 * ### It says what it did not check
 *
 * §9.2 lists six checks and this library performs two of them — check 2 in full and check 3's
 * comparison. §17 forbids reporting unverified things as verified, so every instance carries
 * [checksNotPerformedHere] naming the rest, and a [Payee.FEE] instance names check 6's three
 * obligations too. An instance that recorded only invoice identity, amount and expiry would
 * silently imply the fee-receipt checks had been performed, which is the over-claim this
 * record exists to prevent.
 */
public sealed interface VerifiedPayment {

    /** Which side of §8.6's two invoices this evidence is for. */
    public val payee: Payee

    /** The payment hash the preimage was checked against (§9.2 check 3). */
    public val paymentHash: PaymentHash

    /** The preimage itself. Redacted in every string representation (§12 item 11). */
    public val preimage: Preimage

    /** The §9.2 checks this library performed for itself. Never empty. */
    public val checksPerformed: Set<PaymentCheck>

    /**
     * The §9.2 checks this library did **not** perform, which the caller must perform or
     * account for before treating the payment as fully verified (§17). Never empty in v1.
     */
    public val checksNotPerformedHere: Set<PaymentCheck>

    public companion object {

        /**
         * §9.2 check 2 and check 3's comparison — everything this library verifies for
         * itself, and the same for both payees.
         */
        public val CHECKS_PERFORMED_HERE: Set<PaymentCheck> =
            readOnlySetOf(
                linkedSetOf(PaymentCheck.PREIMAGE_SHAPE, PaymentCheck.PREIMAGE_HASH_COMPARISON),
            )

        /**
         * What a [Payee.PROVIDER] receipt still needs: the four obligations that require a
         * BOLT-11 parser or a persisted payment-request store. Check 6 is not listed because it
         * does not apply to a provider receipt at all — recording an inapplicable obligation as
         * "not performed" would be a different false statement.
         *
         * [PaymentCheck.PAYMENT_HASH_PROVENANCE] is here, and is the one constant no path in
         * this library subtracts: check 1's comparison closes on the store path, and check 3's
         * operand does not close until something parses the invoice's `p` field.
         */
        public val CHECKS_NOT_PERFORMED_FOR_PROVIDER: Set<PaymentCheck> =
            readOnlySetOf(
                linkedSetOf(
                    PaymentCheck.INVOICE_IDENTITY,
                    PaymentCheck.PAYMENT_HASH_PROVENANCE,
                    PaymentCheck.INVOICE_AMOUNT,
                    PaymentCheck.INVOICE_EXPIRY,
                ),
            )

        /** The provider's four, plus check 6's three fee-receipt obligations (§9.2). */
        public val CHECKS_NOT_PERFORMED_FOR_FEE: Set<PaymentCheck> =
            readOnlySetOf(
                linkedSetOf(
                    PaymentCheck.INVOICE_IDENTITY,
                    PaymentCheck.PAYMENT_HASH_PROVENANCE,
                    PaymentCheck.INVOICE_AMOUNT,
                    PaymentCheck.INVOICE_EXPIRY,
                    PaymentCheck.FEE_TERM_MATCH,
                    PaymentCheck.FEE_SEALING_KEY,
                    PaymentCheck.FEE_STATE_PRECONDITION,
                ),
            )

        /**
         * §9.2 check 3, computed here: `SHA-256(preimage)` against [paymentHash], with
         * this library's own FIPS 180-4 SHA-256 and nothing else — no secp256k1, no external
         * library, no network.
         *
         * The comparison is `constantTimeEquals`, which compares every byte with no early
         * exit, as `java.security.MessageDigest.isEqual` has on every JDK since 7u; the two
         * operands are both 32 bytes so the property is free here, but it costs nothing to
         * not have to think about it.
         *
         * ### [payee] is a caller assertion, and the layer above must not leave it that way
         *
         * Which payee this receipt is for selects which capability record the result carries,
         * and this function takes the caller's word for it — a fee receipt verified as
         * [Payee.PROVIDER] would come back without §9.2 check 6's three obligations listed,
         * which reads as "check 6 does not apply" and is a false statement about a fee
         * receipt. There is nothing here to check it against: the payee lives in the
         * receipt's own `["payee", ...]` tag, and this library has no tag codec. So the
         * caller that eventually advances an order MUST read the payee from the receipt and
         * cross-check it against the order's own [dev.eryalabs.nenya.money.FeeSplit], never
         * pass through something a counterparty chose. Recorded here rather than left to be
         * rediscovered, because it is the same §9.1 shape one layer up.
         *
         * @throws PaymentException [PaymentRejection.PREIMAGE_MISMATCH] when the digest is
         *   not the payment hash. That is the only failure mode: [Preimage] and [PaymentHash]
         *   already refused everything malformed on the way in, which is why this function
         *   cannot be handed a status string or a boolean to be talked round by.
         */
        public fun verify(payee: Payee, paymentHash: PaymentHash, preimage: Preimage): VerifiedPayment {
            val computed = sha256(preimage.bytes())
            if (!constantTimeEquals(computed, paymentHash.bytes())) {
                throw PaymentException(
                    PaymentRejection.PREIMAGE_MISMATCH,
                    "the SHA-256 of this preimage is not the payment hash it was checked against " +
                        "(§9.2 check 3); the payment is not evidenced",
                )
            }
            val notPerformed = when (payee) {
                Payee.PROVIDER -> CHECKS_NOT_PERFORMED_FOR_PROVIDER
                Payee.FEE -> CHECKS_NOT_PERFORMED_FOR_FEE
            }
            return PreimageEvidence(payee, paymentHash, preimage, notPerformed)
        }

        /**
         * The single implementation. Private, so the only door in is [verify].
         *
         * Nested in the companion rather than at package level so that "the only door in is
         * [verify]" is enforced by the compiler rather than by convention.
         */
        private class PreimageEvidence(
            override val payee: Payee,
            override val paymentHash: PaymentHash,
            override val preimage: Preimage,
            override val checksNotPerformedHere: Set<PaymentCheck>,
        ) : VerifiedPayment {

            override val checksPerformed: Set<PaymentCheck> get() = CHECKS_PERFORMED_HERE

            /**
             * Names the payee and the two check sets, and no bytes. §12 item 11 and STOP
             * RULE 14: a preimage MUST NOT appear in the string representation of anything
             * this library exposes, and this type transitively holds one.
             */
            override fun toString(): String =
                "VerifiedPayment(payee=${payee.token}, performed=$checksPerformed, " +
                    "notPerformedHere=$checksNotPerformedHere)"
        }
    }
}

/**
 * Every §9.2 check that applies to a receipt for [payee] **at all**, performed or not.
 *
 * The union of what this library verifies for itself and what a receipt for that role still owes,
 * which together are the whole of §9.2 as it bears on one payee: check 6's three obligations are
 * in it for a [Payee.FEE] receipt and absent for a [Payee.PROVIDER] one, because §9.2 states check
 * 6 "for a `payee=fee` receipt" and recording an inapplicable obligation would be a different
 * false statement.
 *
 * ### Why it is here, and what keeps it in step with `Settlement`
 *
 * `OrderMachine.receipts` subtracts a receipt's performed set from this to decide whether an order
 * may become `paid` at all (decision B). `Settlement`'s own private `applicable` composes the same
 * set from the same two constants to build its §17 record. The two are the same rule read for
 * opposite purposes — one says what was done, the other refuses what was not — and they must not
 * drift apart: a refusal demanding a check the record says does not apply would deadlock every
 * order, and one blind to a check the record does name would let an order past it.
 *
 * They are held together by tests rather than by a shared call, because a shared helper is not
 * available: `Settlement`'s is private, nothing outside that file can call it, and publishing it
 * would widen a surface its package sweep pins. `SettlementPropertyTest` does the binding, in two
 * halves — "the applicable check set is §9.2's own, for each payee" pins this function against an
 * independently composed set **and** against §9.2's checks written out one by one, and "every
 * settlement partitions its payee's applicable checks" asserts that every result the settlement
 * path produces divides that same set exactly between its performed and not-performed halves. A
 * change to either composition turns one of them red naming the check that moved.
 *
 * It is deliberately **not** derived from any instance. A record that wrongly subtracted a check
 * must still be refused, and only "what applies, minus what was done" fails closed. The result is
 * fixed at call time and iterates in declaration order.
 *
 * `@JvmSynthetic` because `internal` alone is not enough — Kotlin compiles an internal top-level
 * function to a **public** static method on this file's facade class, and this package's
 * structural sweep reads the JVM surface rather than the Kotlin one (see `encodeLowerHex` in
 * `wire` for the same note).
 */
@JvmSynthetic
internal fun applicableChecks(payee: Payee): Set<PaymentCheck> = readOnlySetOf(
    VerifiedPayment.CHECKS_PERFORMED_HERE + when (payee) {
        Payee.PROVIDER -> VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER
        Payee.FEE -> VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE
    },
)
