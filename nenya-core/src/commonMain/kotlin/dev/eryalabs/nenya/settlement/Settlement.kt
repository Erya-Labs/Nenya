package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.RumorKind
import dev.eryalabs.nenya.collections.readOnlySetOf
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
 * byte-identically against the one stored for the same `order` and `payee`. That fact is recorded
 * in this value's own [checksPerformed], and `VerifiedPayment.CHECKS_PERFORMED_HERE` is **not**
 * widened to say so — a bare `VerifiedPayment.verify` still performs neither check 1 nor anything
 * else this package adds, and a global claim would be the §17 over-claim one layer below where the
 * conformance surface is watching for it. `Capabilities.PAYMENT_CHECKS_NOT_PERFORMED` is derived
 * from that constant and is unchanged, and `PaymentCheck.INVOICE_IDENTITY` therefore stays in §17
 * item 6's not-performed set, which is correct for a second reason: that constant carries the
 * provenance of check 3's payment hash as well as check 1's comparison — the 256-bit `p` field
 * parsed out of the invoice — and nothing in this library parses one.
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
            return PreimageAndInvoice(receipt.order, receipt.payee, receipt.medium, payment)
        }

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
        private fun checkInvoiceIdentity(receipt: PaymentReceipt, store: PaymentRequestStore) {
            val stored = store.find(receipt.order, receipt.payee) ?: throw SettlementException(
                SettlementRejection.NO_STORED_REQUEST,
                SettlementVocabulary.PAYMENT,
                "§9.2 check 1: \"If no such payment request was received and stored, the receipt " +
                    "MUST be rejected.\" No `type=2` is stored for this order and payee, so there " +
                    "is nothing to compare against — this is not a mismatch, and a caller told one " +
                    "would go looking for a wrong invoice that does not exist",
            )
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
        ) : Evidenced {

            /** T3's performed set, composed with check 1 — never redefined. See the note above. */
            override val checksPerformed: Set<PaymentCheck> =
                readOnlySetOf(payment.checksPerformed + PaymentCheck.INVOICE_IDENTITY)

            /** T3's not-performed set, minus the one this path closed. Composed, not listed. */
            override val checksNotPerformedHere: Set<PaymentCheck> =
                readOnlySetOf(payment.checksNotPerformedHere - PaymentCheck.INVOICE_IDENTITY)

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
