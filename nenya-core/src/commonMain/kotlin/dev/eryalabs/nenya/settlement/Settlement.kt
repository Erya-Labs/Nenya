package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.RumorKind
import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §9.2's `kind:17` payment receipt, decoded off a §7.2-attributed rumor.
 *
 * ### The BOLT-11 reference is **not** recognised here, and that is §9.2 check 1's own shape
 *
 * [PaymentRequest] runs the [Bolt11Reference] recogniser, because §8.6 states its MUST about a
 * payment request: "an implementation MUST reject a `payment` tag whose reference is not a BOLT-11
 * invoice string", and §9.3 forbids paying a static address. A *receipt* is judged differently, and
 * §9.2 check 1 says how: the string in the receipt "MUST be **byte-identical** to the BOLT-11
 * string in the corresponding `type=2` payment request stored for the same `order` and `payee`. No
 * normalisation, no case folding, no re-encoding, no bech32 round-trip."
 *
 * So the reference arrives here as an opaque string and [Settlement.verify] compares it. That is
 * not a weakening: an address, an LNURL or a malformed string can never equal a stored reference,
 * because the recogniser refused to store one. What it buys is that check 1's three outcomes stay
 * distinguishable — *no stored request*, *not byte-identical*, and *uppercase, which §4.3 forbids
 * normalising* — each of which sends a reader somewhere different.
 *
 * ### What is checked here
 *
 * The envelope (T13's), one `payment` tag and one `payee` tag (§8.6, §4.3's rationale), §9.2's
 * four-element GammaMarkets shape, and — for a `lightning` receipt and only for one — §9.2 check 2
 * in full, through T3's `Preimage.ofHex`: lowercase hex decoding to exactly 32 bytes, with
 * uppercase **rejected** rather than normalised.
 *
 * ### §9.4's other rails are decoded and evidence nothing
 *
 * §9.4: `bitcoin` and `ecash` "are recognised as GammaMarkets vocabulary and MAY be parsed, but
 * **NENYA-1 v1 defines no verification rule for either**, so neither constitutes evidence and
 * neither may advance state. An implementation encountering one MUST treat the payment as
 * unverified." So a receipt naming one decodes, carries its [medium], has no [preimage], and
 * yields [Settlement.Unverified]. The opposite answer from the one a `type=2` gets for the same
 * token, and deliberately: conflating the two is how a codec either refuses a conformant receipt or
 * stores a request it can never verify.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class PaymentReceipt internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this receipt claims to settle. Never `null` on a `kind:17`. */
    public val order: OrderId,

    /** §8.6's payee role this receipt is for. Half of §9.2 check 1's key. */
    public val payee: Payee,

    /** §9.2's `<medium>`. [PaymentMedium.LIGHTNING] is the only one v1 evidences (§9.4). */
    public val medium: PaymentMedium,

    /**
     * §9.2's `<medium-reference>`, verbatim — the BOLT-11 string on a `lightning` receipt, and
     * whatever the rail uses on another.
     *
     * Internal, not published: it is an operand of check 1's comparison rather than a term, and the
     * only honest thing a caller could do with it is compare it against the store, which is
     * [Settlement.verify]'s job. The whole event is still available byte for byte through [encode].
     */
    internal val reference: String,

    /**
     * §9.2's `<proof>` read as check 2's preimage, or `null` on a rail v1 defines no rule for.
     *
     * Published because T3's `VerifiedPayment.verify` takes one and a caller may want to hand it a
     * payment hash it obtained elsewhere. It redacts itself in every string representation (§12
     * item 11).
     */
    public val preimage: Preimage?,
) {

    /**
     * §7.2's attribution: the key the seal carrying this receipt was signed by, lowercase.
     *
     * §9.2's own note on the asymmetry: "it is the *payer* who sends it and the *payee's*
     * verification that matters". Whether this key is the buyer's is the caller's resolution.
     */
    public val sender: String get() = rumor.author

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6). */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /**
     * Names the payee role and the rail, both vocabulary tokens, and no identifier of any kind.
     *
     * §12 items 1, 2 and 11 and STOP RULE 14: this value holds an order id, an invoice string and a
     * preimage — three of the values named in one sentence as forbidden in "the string
     * representation of anything the implementation exposes".
     */
    override fun toString(): String =
        "PaymentReceipt(payee=${payee.token}, medium=${medium.name})"

    public companion object {

        /**
         * §9.2's receipt, decoded.
         *
         * Takes an [AttributedRumor.Bound] rather than a `CheckedEvent`, for the reason
         * `OrderProposal.decode` gives: §4.1's id check and §7.2's pubkey-equality check both come
         * first, and the way to enforce an ordering is to make the later step take a type only the
         * earlier steps can produce.
         *
         * @throws SettlementException naming which §9.2, §8.6, §7.4 or §4.3 rule refused the
         *   receipt, and the tag it was about. A `PaymentException` from §9.2 check 2 is preserved
         *   on the cause.
         */
        public fun decode(rumor: AttributedRumor.Bound): PaymentReceipt {
            if (rumor.kind != RumorKind.RECEIPT) {
                throw SettlementException(
                    SettlementRejection.NOT_A_RECEIPT,
                    null,
                    "this decoder reads §9.2's kind:${RumorKind.RECEIPT.kind} receipt; the rumor it " +
                        "was handed is a kind:${rumor.kind.kind}. A well-formed message of another " +
                        "kind is not a malformed one",
                )
            }
            val order = SettlementTags.requireOrder(rumor)
            val payee = SettlementTags.payee(rumor)
            val tag = SettlementTags.payment(rumor, SettlementVocabulary.PAYMENT_ELEMENTS_RECEIPT)
            val medium = PaymentMedium.of(tag[SettlementVocabulary.MEDIUM_INDEX])
            val reference = tag[SettlementVocabulary.REFERENCE_INDEX]
            val proof = tag[SettlementVocabulary.PROOF_INDEX]
            // §9.4: v1 defines no verification rule for the other rails, so their proof is not read
            // as a preimage at all. A txid is not 32 bytes of lowercase hex by coincidence, and
            // reading one through check 2's reader would refuse a receipt §9.4 says MAY be parsed.
            val preimage = if (medium.hasVerificationRule) readPreimage(proof) else null
            return PaymentReceipt(rumor, order, payee.role, medium, reference, preimage)
        }

        /** §9.2 check 2, in full, through T3's reader rather than a second hex decoder. */
        private fun readPreimage(proof: String): Preimage = try {
            Preimage.ofHex(proof)
        } catch (refused: PaymentException) {
            throw SettlementException(
                SettlementRejection.PREIMAGE_MALFORMED,
                SettlementVocabulary.PAYMENT,
                "§9.2 check 2: the proof MUST be **lowercase** hex and MUST decode to exactly 32 " +
                    "bytes. Uppercase and mixed case are rejected rather than normalised — §4.3 " +
                    "names the preimage as one of exactly two values where no normalisation of any " +
                    "kind is permitted. The precise reason is on the cause",
                refused,
            )
        }
    }
}

/**
 * What §9.2 concluded about one `kind:17` receipt checked against the store — evidence, or
 * explicitly none.
 *
 * ### Compose and record; do not redefine
 *
 * §9.2 check 1 is performed **on this path**: the BOLT-11 string in the receipt was compared
 * byte-identically against the one stored for the same `order` and `payee`. So is check 6, on
 * [Settlement.verifyFeeReceipt]'s path — §8.4's byte-identical fee term, §8.7's sealing key and
 * §8.5's state precondition. Both facts are recorded in this value's own [checksPerformed], and
 * `VerifiedPayment.CHECKS_PERFORMED_HERE` is **not** widened to say so — a bare
 * `VerifiedPayment.verify` still performs neither check 1 nor check 6 nor anything else this
 * package adds, and a global claim would be the §17 over-claim one layer below where the
 * conformance surface is watching for it. `Capabilities.PAYMENT_CHECKS_NOT_PERFORMED` is derived
 * from that constant and is unchanged, and `PaymentCheck.INVOICE_IDENTITY` therefore stays in §17
 * item 6's not-performed set, which is correct for a second reason: that constant carries the
 * provenance of check 3's payment hash as well as check 1's comparison — the 256-bit `p` field
 * parsed out of the invoice — and nothing in this library parses one.
 *
 * The three check-6 constants are a different case and are treated differently, which is the point
 * of publishing per-result sets at all: they are not global claims about `VerifiedPayment.verify`
 * either, and a [verify] result for a fee receipt still names all three as **not** performed. What
 * changed with them is §17 item 5, which cited §8.4 and named `PaymentCheck.FEE_TERM_MATCH` as
 * missing for want of the receipt codec that now exists.
 *
 * ### Three answers, because §9.2 and §9.4 give three
 *
 * [Evidenced] is a preimage this library hashed itself, against an invoice it stored itself.
 * [Unverified] is §9.4's required treatment of a rail v1 defines no rule for. Everything else is a
 * [SettlementException] naming which check refused it — and never a `Boolean`, which collapses the
 * first two, nor a status-shaped `String`, which hands a decision back as text (STOP RULE 12,
 * §9.1). `SettlementStructureTest` asserts both by reflection.
 *
 * ### Unforgeable through the published API
 *
 * The shape `VerifiedPayment` specifies, for the reason it gives: a public `sealed interface` whose
 * implementations are `private` classes nested inside its companion. [Evidenced] and [Unverified]
 * are public because a caller has to be able to name what it received, and they are themselves
 * `sealed`, so the JVM's `PermittedSubclasses` attribute is what stops another module implementing
 * one.
 */
public sealed interface Settlement {

    /** §7.4's `order` id the receipt named. */
    public val order: OrderId

    /** §8.6's payee role the receipt named. Exactly one — never a collection (§8.6). */
    public val payee: Payee

    /** Which rail the receipt named (§9.2, §9.4). */
    public val medium: PaymentMedium

    /**
     * The §9.2 checks performed to produce this value, on this path.
     *
     * Empty on an [Unverified]: §9.4 says the payment MUST be treated as unverified, and a set
     * naming a check that was performed against a rail with no rule would be the over-claim in
     * miniature.
     */
    public val checksPerformed: Set<PaymentCheck>

    /**
     * The §9.2 checks this library did **not** perform, which the caller must perform or account
     * for before treating the payment as fully verified (§17). Never empty in v1.
     */
    public val checksNotPerformedHere: Set<PaymentCheck>

    /**
     * §9.2's evidence: check 1 against the stored request, and checks 2 and 3's comparison through
     * T3.
     *
     * Still not the whole of §9.2 — checks 4, 5 and 6 need a BOLT-11 parser, a clock comparison and
     * gift-wrap machinery — and [checksNotPerformedHere] names what is missing rather than leaving
     * a caller to infer it from the existence of this value.
     */
    public sealed interface Evidenced : Settlement {

        /** T3's evidence, over a preimage this library hashed itself (§9.2 checks 2 and 3). */
        public val payment: VerifiedPayment
    }

    /**
     * §9.4's required treatment of a rail NENYA-1 v1 defines no verification rule for.
     *
     * Its own case rather than an exception, because §9.4 says the message MAY be parsed: a
     * `bitcoin` or `ecash` receipt is a conformant message, and refusing it would make a conformant
     * peer look broken. It carries no [Evidenced.payment], so there is nothing here that could
     * advance an order — which is §9.4's other half, made structural.
     */
    public sealed interface Unverified : Settlement

    public companion object {

        /**
         * §9.2's checks this path performs — check 1, plus T3's two — as a set a caller can branch
         * on.
         *
         * Derived from `VerifiedPayment.CHECKS_PERFORMED_HERE` rather than transcribed, so a check
         * T3 adds later lands here the moment it is declared. It is deliberately **not** the same
         * value as that constant and deliberately does not replace it: see this interface's note.
         */
        public val CHECKS_PERFORMED_ON_THE_STORE_PATH: Set<PaymentCheck> = readOnlySetOf(
            VerifiedPayment.CHECKS_PERFORMED_HERE + PaymentCheck.INVOICE_IDENTITY,
        )

        /**
         * §9.2 check 6's three obligations, **derived** rather than listed.
         *
         * They are exactly what a fee receipt carries and a provider receipt does not — T3 states
         * that in the two constants it publishes, and the difference between them is the set §9.2
         * numbers 6. Deriving it means a fourth obligation added to check 6 later lands here the
         * moment `CHECKS_NOT_PERFORMED_FOR_FEE` names it, rather than being silently left out of
         * [verifyFeeReceipt]'s claim.
         */
        public val CHECK_SIX: Set<PaymentCheck> = readOnlySetOf(
            VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE -
                VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER,
        )

        /**
         * What [verifyFeeReceipt] performs: the store path's three, plus check 6's three.
         *
         * Composed from [CHECKS_PERFORMED_ON_THE_STORE_PATH] and [CHECK_SIX], and — like that
         * constant — deliberately **not** `VerifiedPayment.CHECKS_PERFORMED_HERE`, which stays
         * exactly as T3 wrote it. A bare `VerifiedPayment.verify` still performs neither check 1
         * nor check 6, and widening its global claim to say otherwise would be the §17 over-claim
         * one layer below where the conformance surface is watching for it.
         */
        public val CHECKS_PERFORMED_ON_THE_FEE_RECEIPT_PATH: Set<PaymentCheck> = readOnlySetOf(
            CHECKS_PERFORMED_ON_THE_STORE_PATH + CHECK_SIX,
        )

        /**
         * §9.2 checked against the store, whole.
         *
         * The order of the checks is check 1 first, and that is §9.2's own: "Before an
         * implementation may treat a payment as made, it MUST perform **all** of the following
         * itself", numbered, with invoice identity at 1. A caller whose receipt names an invoice
         * nobody asked it to pay is told that, rather than being told its preimage does not hash —
         * which would be true of a fabricated receipt and useless for a misrouted one.
         *
         * @param paymentHash §9.2 check 3's operand. **Still a caller-supplied parameter**, and the
         *   narrowing T3 stated is unchanged: check 3 defines it as the 256-bit `p` tagged field
         *   parsed out of the BOLT-11 invoice, and [Bolt11Reference] parses no field at all. Unused
         *   for an [Unverified] result — a caller that wants to avoid computing one reads
         *   [PaymentReceipt.medium] first, and putting the branch here rather than in the caller is
         *   what keeps §9.4's decision out of the caller's hands.
         * @param store the client's persistence, holding the `type=2` requests it accepted.
         * @throws SettlementException [SettlementRejection.NO_STORED_REQUEST] when no request was
         *   stored for this order and payee, [SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED]
         *   when the two differ only in case, [SettlementRejection.INVOICE_NOT_IDENTICAL] when they
         *   differ otherwise. T3's own `PaymentException` — `PREIMAGE_MISMATCH` — is **not** wrapped
         *   and reaches the caller as it was thrown: §9.2 check 3 is T3's rule, and re-badging its
         *   refusal in this package's vocabulary would put two names on the one failure §9 calls
         *   the load-bearing rule of the entire document.
         */
        public fun verify(
            receipt: PaymentReceipt,
            paymentHash: PaymentHash,
            store: PaymentRequestStore,
        ): Settlement {
            // §9.4: the two halves of this condition are the same fact — `decode` reads a proof as
            // a preimage exactly when the rail has a verification rule — and both are written
            // because the second is what the compiler needs and the first is what the rule is.
            val preimage = receipt.preimage
            if (!receipt.medium.hasVerificationRule || preimage == null) {
                return NoRule(receipt.order, receipt.payee, receipt.medium, applicable(receipt.payee))
            }
            checkInvoiceIdentity(receipt, store)
            val payment = VerifiedPayment.verify(receipt.payee, paymentHash, preimage)
            return PreimageAndInvoice(
                receipt.order,
                receipt.payee,
                receipt.medium,
                payment,
                alsoPerformed = emptySet(),
            )
        }

        /**
         * §9.2 check 6, whole, on top of everything [verify] does — the `payee=fee` path.
         *
         * Check 6 is the one §9.2 obligation with three separate sub-checks, and each is performed
         * here against something this library holds rather than against something a counterparty
         * said:
         *
         * 1. **§8.4 — the fee term MUST match.** [FeeTermAgreement.across] over [earlierPoints]
         *    with this receipt's own `fee` tag appended, comparing the raw tag elements, which is
         *    what "byte-identical" means. The first divergence aborts, named as a **terms
         *    mismatch** with the point and the side on
         *    [SettlementException.feeTermDivergence] — §8.4 requires it be surfaced that way and
         *    forbids "take the newest", "take the smaller" and silent renegotiation.
         * 2. **§8.7 — the sealing key MUST be the fee recipient's.** See the note below on which
         *    seal.
         * 3. **§8.5 — the state MUST already be `awaiting_payment`**, read from T7's [Order] and
         *    never from a `["status", ...]` token a counterparty chose (§9.1, §11.1, STOP RULE 12).
         *
         * ### Which seal §8.7 is about, stated rather than absorbed
         *
         * §9.2 check 6 says "the sealing key MUST be the fee recipient's (§8.7)" about a
         * `payee=fee` **receipt**, and §8.7 itself states the rule about the `type=2` payment
         * request. Those cannot both be read literally of the receipt's own seal: §9.2's worked
         * `kind:17` carries `"pubkey": "<buyer-pubkey-hex>"` and its own closing paragraph says
         * "it is the *payer* who sends it", so a conformant fee receipt is sealed by the **buyer**.
         * A check that required the receipt's own seal to be the fee recipient's would refuse
         * every conformant fee receipt and deadlock every fee-bearing order — the same failure
         * §8.5's revision `1.2` exists to undo, one message later.
         *
         * So the operand is the seal of the fee **invoice** this receipt settles, which is
         * [AcceptedPaymentRequest.sealedBy] on the stored `type=2` check 1 already looked up. That
         * is the reading under which §8.7, §9.2's example and check 6 all hold at once, and it is
         * the rule §8.7 actually writes down: a fee invoice forwarded by the provider is rejected.
         * [checkFeePaymentRequest] performs the same comparison at the moment the invoice arrives,
         * which is where §8.7 puts it; this is the half that survives to settlement.
         *
         * ### What the equality does and does not establish
         *
         * It establishes that the `pubkey` of the seal the caller decrypted equals the fee
         * recipient named in the signed fee term. It establishes **nothing about a signature**:
         * this library verifies no BIP-340 signature anywhere (`SeamCapability.BIP340_VERIFICATION`
         * is not-performed and stays so), and neither does §7.2 require one. So the binding is only
         * as strong as the decryption the caller performed — §7.2's authenticated-by-decryption
         * wording, one layer up — and a caller that got its rumor from somewhere other than a
         * NIP-59 gift wrap it opened itself has an equality between two strings and no evidence.
         *
         * @param paymentHash §9.2 check 3's operand, with the narrowing [verify] states unchanged.
         * @param store the client's persistence, holding the `type=2` requests it accepted. It is
         *   read twice here — for check 1's invoice comparison and for §8.7's sealing key — and
         *   neither read makes it trustworthy: a store is the embedding client's own persistence
         *   and §13 says Nenya does not defend its user against the client embedding it.
         * @param order **this implementation's own** order, from `OrderMachine`, which is the only
         *   thing that can produce one. Whether it is the order this receipt names is the caller's
         *   binding and not a check made here: `Order` carries no id, for the reason `OrderTerms`
         *   gives — an identifier held where nothing reads it is a correlation handle (§12). The
         *   same obligation reaches [earlierPoints]: sightings taken from another order's messages
         *   would agree with each other, and only the caller knows which order they came from.
         * @param earlierPoints every point at which a `fee` tag appears — or deliberately does not
         *   — for this order **before** this receipt, in the order they were observed. §8.4's
         *   proposal, acceptance and fee `type=2` are **required** to be among them and their
         *   absence is a refusal rather than a smaller comparison: this receipt's own point is
         *   appended here rather than taken from the caller, so without that requirement an empty
         *   list would have the receipt compared against itself and agree. A comparison against
         *   the proposal alone passes every single-comparison test and is exactly what §8.4's four
         *   points exist to prevent, which is why this is a sequence and not a pair.
         * @throws SettlementException [SettlementRejection.PAYEE_IS_NOT_FEE] for a provider
         *   receipt — check 6 does not apply to one at all;
         *   [SettlementRejection.FEE_TERM_POINT_MISSING] when a §8.4 point this receipt cannot
         *   have existed without is not among [earlierPoints];
         *   [SettlementRejection.PAYEE_NOT_REQUIRED] when the order's expected fee is `0`, because
         *   §8.3 and §8.6 say no fee invoice may exist for it and therefore no receipt may;
         *   [SettlementRejection.FEE_TERM_MISMATCH], [SettlementRejection.FEE_SEAL_NOT_RECIPIENT]
         *   and [SettlementRejection.FEE_RECEIPT_STATE_NOT_AWAITING_PAYMENT] for check 6's own three;
         *   and check 1's three, unchanged. T3's `PaymentException` for check 3 reaches the caller
         *   as it was thrown, for the reason [verify] gives.
         */
        public fun verifyFeeReceipt(
            receipt: PaymentReceipt,
            paymentHash: PaymentHash,
            store: PaymentRequestStore,
            order: Order,
            earlierPoints: List<FeeTermSighting>,
        ): Settlement {
            if (receipt.payee != Payee.FEE) {
                throw SettlementException(
                    SettlementRejection.PAYEE_IS_NOT_FEE,
                    SettlementVocabulary.PAYEE,
                    "§9.2 check 6 is stated \"for a `payee=fee` receipt\" and this one names the " +
                        "provider. Check 6 does not apply to a provider receipt at all, and " +
                        "running §8.4 over one as though it did would refuse the conformant " +
                        "provider receipt §9.2's own worked example prints — which carries no " +
                        "`fee` tag, exactly as §8.4 permits",
                )
            }
            // §9.4 first, exactly as `verify` does it and for the same reason: a rail NENYA-1 v1
            // defines no verification rule for evidences nothing, and a result that claimed check
            // 6 had been performed against one would be work recorded against a payment this
            // library reports as unverified. Nothing is accepted by this branch — §8.5 forbids
            // *accepting* a fee receipt before `awaiting_payment`, and an `Unverified` is the
            // statement that this receipt evidences nothing, which is not an acceptance.
            val preimage = receipt.preimage
            if (!receipt.medium.hasVerificationRule || preimage == null) {
                return NoRule(receipt.order, receipt.payee, receipt.medium, applicable(receipt.payee))
            }
            // §8.4's points are a message order, and the ones that must already exist are required
            // here rather than trusted. Without this a caller handing in nothing would have the
            // receipt compared against **itself**, which always agrees — and the result would claim
            // FEE_TERM_MATCH and FEE_SEALING_KEY over a term nobody signed.
            requireEarlierPoints(earlierPoints, REQUIRED_BEFORE_FEE_RECEIPT)
            // §8.3 and §8.6, the receipt-side mirror of `PaymentRequest.decode`'s rule, and read
            // out of `Payee.requiredPayees` rather than re-derived: a fee whose computed amount is
            // 0 has no invoice that may legally exist, so nothing can have been paid against one.
            if (Payee.FEE !in Payee.requiredPayees(order.terms.split)) {
                throw SettlementException(
                    SettlementRejection.PAYEE_NOT_REQUIRED,
                    SettlementVocabulary.PAYEE,
                    "§8.3: a computed `fee_msat` of zero means no fee invoice may be sent or " +
                        "accepted, even though the term named a recipient — and a receipt is " +
                        "evidence of paying an invoice that may not exist. §8.1's read rule " +
                        "reaches the same answer for an order proposed with no `fee` tag at all",
                )
            }
            // §9.2 check 6, in §9.2's own order: the fee term, the sealing key, then the state.
            val agreed = agreementOrMismatch(earlierPoints + FeeTermSighting.onReceipt(receipt))
            val stored = requireStored(receipt, store)
            checkFeeSeal(stored.sealedBy, agreed, forwarded = true)
            if (order.state != OrderState.AWAITING_PAYMENT) {
                throw SettlementException(
                    SettlementRejection.FEE_RECEIPT_STATE_NOT_AWAITING_PAYMENT,
                    null,
                    "§9.2 check 6: for a `payee=fee` receipt \"the state MUST already be " +
                        "`${OrderState.AWAITING_PAYMENT.token}`\" (§8.5), and this order's is " +
                        "`${order.state.token ?: "unknown"}`. The state was read from this " +
                        "implementation's own order and not from a status token a " +
                        "counterparty sent. Note what this does **not** refuse: the fee payment " +
                        "**request** is accepted as part of `${OrderState.COMMITTED.token} → " +
                        "${OrderState.AWAITING_PAYMENT.token}` and never earlier — §8.5 says a " +
                        "rule that refused it while the order was still " +
                        "`${OrderState.COMMITTED.token}` would make " +
                        "`${OrderState.AWAITING_PAYMENT.token}` unreachable for every fee-bearing " +
                        "order",
                )
            }
            checkInvoiceIdentity(receipt, store)
            val payment = VerifiedPayment.verify(receipt.payee, paymentHash, preimage)
            return PreimageAndInvoice(
                receipt.order,
                receipt.payee,
                receipt.medium,
                payment,
                alsoPerformed = CHECK_SIX,
            )
        }

        /**
         * §8.4 and §8.7 over a fee `type=2`, at the moment §8.7 puts them: when the invoice arrives.
         *
         * This is the half of §9.2 check 6 that bites before settlement, and it is the one §8.7
         * states in its own words: "A `type=2` payment request with `["payee", "fee", ...]` MUST
         * arrive in a gift wrap whose seal (`kind:13`) `pubkey` equals the fee-recipient pubkey
         * named in the signed fee term. A fee invoice forwarded by the provider, or arriving from
         * any other key, MUST be rejected." The forwarding shape is the one that makes it matter,
         * and it is the control this function exists for.
         *
         * Not folded into `PaymentRequest.decode`, deliberately: that decoder takes the order's
         * `FeeSplit`, which holds the basis points and **not** the recipient, so it has one operand
         * of §8.7's comparison and not the other. The signed fee term is a sequence of raw tags
         * across §8.4's points, and asking `decode` for it would make every request decode depend
         * on the caller having assembled the whole order's history first.
         *
         * See [verifyFeeReceipt] for what this equality does and does not establish; the same
         * narrowing applies, because it is the same comparison.
         *
         * @param earlierPoints §8.4's points before this request. The proposal and the acceptance
         *   are **required** to be among them: this request's own point is appended here, so
         *   without that requirement an empty list would have the request compared against itself
         *   and agree — and §8.7 would then be satisfied out of the recipient the request itself
         *   named, which is the substitution §8.7 exists to refuse.
         * @return the agreed term, so the caller holds the fee recipient §8.2 requires it display
         *   and §8.6 requires it tag, without re-deriving it from a tag it has already compared.
         * @throws SettlementException [SettlementRejection.PAYEE_IS_NOT_FEE] for a provider
         *   request — §8.4 makes the `fee` tag OPTIONAL there and MUST NOT require it, and §8.7 is
         *   not about it; [SettlementRejection.FEE_TERM_POINT_MISSING] when the proposal or the
         *   acceptance is not among [earlierPoints];
         *   [SettlementRejection.FEE_TERM_MISMATCH] on divergence; or
         *   [SettlementRejection.FEE_SEAL_NOT_RECIPIENT] on §8.7.
         */
        public fun checkFeePaymentRequest(
            request: PaymentRequest,
            earlierPoints: List<FeeTermSighting>,
        ): FeeTermAgreement.Agreed {
            if (request.payee != Payee.FEE) {
                throw SettlementException(
                    SettlementRejection.PAYEE_IS_NOT_FEE,
                    SettlementVocabulary.PAYEE,
                    "§8.7 is a rule about a `type=2` carrying `[\"${SettlementVocabulary.PAYEE}\", " +
                        "\"${Payee.FEE.token}\", …]` and this one names " +
                        "`${Payee.PROVIDER.token}`. §8.4 makes the `fee` tag OPTIONAL on a " +
                        "provider request and says an implementation MUST NOT require one there",
                )
            }
            requireEarlierPoints(earlierPoints, REQUIRED_BEFORE_FEE_PAYMENT_REQUEST)
            val agreed = agreementOrMismatch(earlierPoints + FeeTermSighting.onPaymentRequest(request))
            checkFeeSeal(request.sender, agreed, forwarded = false)
            return agreed
        }

        /**
         * §8.4's REQUIRED points that MUST already exist before a fee `type=2` can be judged.
         *
         * §8.4 is a message order as well as a list: the fee payment request charges a term the
         * proposal stated and the acceptance repeated byte-identically (§7.6), so those two are
         * what "the **signed** fee term" §8.7 compares a seal against means. A `type=2` judged
         * without them is judged against itself.
         */
        private val REQUIRED_BEFORE_FEE_PAYMENT_REQUEST: List<FeeTermPoint> =
            readOnlyListOf(listOf(FeeTermPoint.ORDER_PROPOSAL, FeeTermPoint.ACCEPTANCE))

        /** Those two, plus the fee `type=2` a fee receipt settles. §8.4 point 3 precedes point 4. */
        private val REQUIRED_BEFORE_FEE_RECEIPT: List<FeeTermPoint> =
            readOnlyListOf(REQUIRED_BEFORE_FEE_PAYMENT_REQUEST + FeeTermPoint.FEE_PAYMENT_REQUEST)

        /**
         * Refuse a sequence missing a §8.4 point the message under test cannot have existed
         * without.
         *
         * This is the guard that stops the vacuous agreement, and it is not the same guard as
         * [FeeTermAgreement.across]'s empty-sequence refusal: that one catches a comparison with
         * nothing, this one catches a comparison with everything except the signed term. Without
         * it a caller handing in an empty list gets `Agreed` over a single point — a message
         * compared against itself, which always agrees — and a stranger who names themselves in
         * both their own `["payee", "fee", …]` tag and their own `["fee", …]` tag satisfies §8.7
         * out of a term nobody signed. That is precisely the substitution §8.7 exists to refuse.
         *
         * A **presence** check and not an ordering one: §8.4 fixes no order for the sightings a
         * caller observed, and §8.4's own OPTIONAL bid legitimately precedes the proposal. What it
         * fixes is that these points exist.
         */
        private fun requireEarlierPoints(
            earlierPoints: List<FeeTermSighting>,
            needed: List<FeeTermPoint>,
        ) {
            val seen = earlierPoints.mapTo(HashSet()) { it.point }
            val missing = needed.firstOrNull { it !in seen } ?: return
            throw SettlementException(
                SettlementRejection.FEE_TERM_POINT_MISSING,
                SettlementVocabulary.FEE,
                "§8.4 marks the `fee` tag REQUIRED at ${needed.map { it.name }} before this " +
                    "message can exist at all, and ${missing.name} is not among the points this " +
                    "check was handed. Comparing what is left would compare this message against " +
                    "itself, which always agrees — and would let a stranger naming themselves in " +
                    "their own `payee` and `fee` tags satisfy §8.7 out of a term nobody signed",
            )
        }

        /**
         * §8.4's answer, with a divergence turned into the refusal §8.4 requires.
         *
         * Two shapes for one rule, and both are needed: [FeeTermAgreement.across] **reports** —
         * which is what a caller assembling an order's history wants, and what lets a test assert
         * on the point and the side without catching anything — while this **refuses**, because
         * §8.4 says a divergence "MUST abort the order". The structured answer survives the throw
         * on [SettlementException.feeTermDivergence] rather than being flattened into a message.
         */
        private fun agreementOrMismatch(sightings: List<FeeTermSighting>): FeeTermAgreement.Agreed {
            return when (val agreement = FeeTermAgreement.across(sightings)) {
                is FeeTermAgreement.Agreed -> agreement
                is FeeTermAgreement.Diverged -> throw SettlementException(
                    SettlementRejection.FEE_TERM_MISMATCH,
                    SettlementVocabulary.FEE,
                    "§8.4: the same `(bps, recipient)` pair MUST appear byte-identically wherever " +
                        "a `fee` tag appears for an order, and it does not. Any divergence MUST " +
                        "abort the order and MUST be surfaced to the user as a **terms mismatch**, " +
                        "not as a transient error; an implementation MUST NOT take the newest, " +
                        "take the smaller, or renegotiate silently. The point and the side are on " +
                        "`feeTermDivergence`",
                    feeTermDivergence = agreement,
                )
            }
        }

        /**
         * §8.7's equality: the seal a fee invoice arrived under is the fee recipient's own key.
         *
         * @param forwarded whether the refusal should name the *stored invoice's* seal rather than
         *   the message's own. Both are §8.7 and the reason is one constant; what differs is where
         *   a reader should look, and that is worth a sentence rather than a shrug.
         */
        private fun checkFeeSeal(
            sealPubkey: String,
            agreed: FeeTermAgreement.Agreed,
            forwarded: Boolean,
        ) {
            val recipient = agreed.recipient
            if (recipient != null && recipient == sealPubkey) return
            throw SettlementException(
                SettlementRejection.FEE_SEAL_NOT_RECIPIENT,
                SettlementVocabulary.PAYEE,
                "§8.7: a `type=2` payment request with `[\"${SettlementVocabulary.PAYEE}\", " +
                    "\"${Payee.FEE.token}\", …]` MUST arrive in a gift wrap whose seal (kind:13) " +
                    "`pubkey` equals the fee-recipient pubkey named in the signed fee term, and a " +
                    "fee invoice forwarded by the provider — or arriving from any other key — " +
                    "MUST be rejected. " +
                    (
                        if (forwarded) "The seal compared is the one the **stored** `type=2` " +
                            "arrived under: a fee receipt is authored by the buyer (§9.2), so its " +
                            "own seal is never the fee recipient's."
                        else "The seal compared is this request's own."
                        ) +
                    (
                        if (recipient == null) " The signed fee term names no recipient at all, " +
                            "so there is no key a fee invoice could legally arrive from."
                        else ""
                        ) +
                    " This is not a missing or a malformed `${SettlementVocabulary.PAYEE}` tag: " +
                    "the tag is present and well formed, and what is wrong is who sealed the " +
                    "message",
            )
        }

        /** §9.2 check 1's lookup, shared so the fee path and [verify] cannot come to differ. */
        private fun requireStored(
            receipt: PaymentReceipt,
            store: PaymentRequestStore,
        ): AcceptedPaymentRequest = store.find(receipt.order, receipt.payee) ?: throw noStoredRequest()

        /**
         * §9.2 check 1, whole, with its three outcomes kept apart.
         *
         * The decision is the byte comparison and nothing else. The uppercase test below is a
         * **diagnosis** run only after that decision has already refused the receipt: §4.3 permits
         * no case folding here, so telling a caller that its reference carries uppercase is a fact
         * about its own encoder, not a second chance for the receipt. Folding the two into one
         * case-insensitive comparison is exactly the mutation this rule exists to catch.
         *
         * The diagnosis asks whether the reference carries an **ASCII uppercase character**, which
         * is what Appendix C and §4.3 actually forbid, rather than whether the two strings are equal
         * ignoring case. The looser form would be wrong in a way that only ever misreports: the
         * bech32 alphabet carries `s` and `k`, and `String.equals(ignoreCase = true)` folds U+017F
         * onto `s` and U+212A onto `k`, so a reference differing from the stored one by a homoglyph
         * would be reported as an encoder emitting uppercase. The verdict would be right and the
         * reason would send a reader to the wrong place, which is the defect this whole enum exists
         * to prevent.
         *
         * A consequence worth stating rather than leaving to be discovered: the uppercase reason
         * therefore **takes precedence** over a substantive difference. A reference that carries
         * uppercase *and* names a different invoice is reported as uppercase, because §4.3 makes
         * case a property of the string on its own — "an uppercase or mixed-case value MUST be
         * rejected" — independently of what it is being compared against, and a caller whose
         * encoder has a case bug should be told about the case bug first.
         */
        private fun noStoredRequest(): SettlementException = SettlementException(
            SettlementRejection.NO_STORED_REQUEST,
            SettlementVocabulary.PAYMENT,
            "§9.2 check 1: \"If no such payment request was received and stored, the receipt " +
                "MUST be rejected.\" No `type=2` is stored for this order and payee, so there " +
                "is nothing to compare against — this is not a mismatch, and a caller told one " +
                "would go looking for a wrong invoice that does not exist",
        )

        private fun checkInvoiceIdentity(receipt: PaymentReceipt, store: PaymentRequestStore) {
            val stored = requireStored(receipt, store)
            if (receipt.reference == stored.invoice.text) return
            if (receipt.reference.any { it in 'A'..'Z' }) {
                throw SettlementException(
                    SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED,
                    SettlementVocabulary.PAYMENT,
                    "§9.2 check 1 compares the BOLT-11 string byte-identically and names case " +
                        "folding among the things it forbids; §4.3 lists the invoice as one of " +
                        "exactly two values where an uppercase or mixed-case form is rejected " +
                        "outright. The contrast is `PaymentHash`, which is **not** on that list and " +
                        "is accepted and normalised",
                )
            }
            throw SettlementException(
                SettlementRejection.INVOICE_NOT_IDENTICAL,
                SettlementVocabulary.PAYMENT,
                "§9.2 check 1: the BOLT-11 string in this receipt is not byte-identical to the one " +
                    "in the stored `type=2` for the same order and payee. No normalisation, no case " +
                    "folding, no re-encoding, no bech32 round-trip",
            )
        }

        /**
         * Every §9.2 check that applies to a receipt for [payee] at all, performed or not.
         *
         * Composed out of the two sets T3 publishes rather than listed here, so check 6's three
         * obligations attach to a fee receipt and to nothing else — recording an inapplicable
         * obligation as "not performed" would be a different false statement, which is the reading
         * `VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER` already carries.
         */
        private fun applicable(payee: Payee): Set<PaymentCheck> = readOnlySetOf(
            VerifiedPayment.CHECKS_PERFORMED_HERE + when (payee) {
                Payee.PROVIDER -> VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER
                Payee.FEE -> VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE
            },
        )

        /** §9.2's evidence. Private, so the only door in is [verify]. */
        private class PreimageAndInvoice(
            override val order: OrderId,
            override val payee: Payee,
            override val medium: PaymentMedium,
            override val payment: VerifiedPayment,

            /**
             * What this path closed **beyond** check 1: empty from [verify], and check 6's three
             * from [verifyFeeReceipt].
             *
             * A parameter rather than a second class, so the two paths cannot come to compose the
             * record two different ways — the whole point of composing it from T3's sets rather
             * than listing it is that one place decides.
             */
            alsoPerformed: Set<PaymentCheck>,
        ) : Evidenced {

            /** T3's performed set, composed with check 1 — never redefined. See the note above. */
            override val checksPerformed: Set<PaymentCheck> = readOnlySetOf(
                payment.checksPerformed + PaymentCheck.INVOICE_IDENTITY + alsoPerformed,
            )

            /** T3's not-performed set, minus the ones this path closed. Composed, not listed. */
            override val checksNotPerformedHere: Set<PaymentCheck> = readOnlySetOf(
                payment.checksNotPerformedHere - PaymentCheck.INVOICE_IDENTITY - alsoPerformed,
            )

            /** Names the role, the rail and the two check counts, and no identifier (§12 item 11). */
            override fun toString(): String =
                "Settlement.Evidenced(payee=${payee.token}, medium=${medium.name}, " +
                    "performed=$checksPerformed, notPerformedHere=$checksNotPerformedHere)"
        }

        /** §9.4's answer. Private, for the same reason. */
        private class NoRule(
            override val order: OrderId,
            override val payee: Payee,
            override val medium: PaymentMedium,
            override val checksNotPerformedHere: Set<PaymentCheck>,
        ) : Unverified {

            override val checksPerformed: Set<PaymentCheck> get() = emptySet()

            override fun toString(): String =
                "Settlement.Unverified(payee=${payee.token}, medium=${medium.name}, " +
                    "notPerformedHere=$checksNotPerformedHere)"
        }
    }
}
