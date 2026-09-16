package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.OrderMessageKind
import dev.eryalabs.nenya.channel.RumorKind
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §8.6's two tags — `payment` and `payee` — read once, so a request and a receipt cannot come to
 * read the same tag two different ways.
 *
 * Nothing here is a second copy of §5.3's Encoding column: neither tag is a row in §5.3's table, so
 * the tag codec does not reach them and this object must. The one value it does decode through T9
 * is the fee recipient's pubkey, read with [readPubkeyHex] rather than a second 64-hex reader
 * written here.
 */
internal object SettlementTags {

    /**
     * The one occurrence of [name] on [rumor], or `null`, refusing a second one.
     *
     * §5.3's table carries neither `payment` nor `payee`, so §4.3's duplicate rule — stated over
     * "any tag **this document** marks with cardinality `1` or `0–1`" — does not literally reach
     * them and the tag codec does not check them. §8.6 states the cardinality itself: a `type=2`
     * "MUST carry exactly one `["payment", "lightning", "<bolt11>"]` tag", and it names exactly one
     * payee role. §4.3's rationale reaches them unchanged and is at its sharpest here — two
     * implementations resolving two `payment` tags by first and by last disagree about which
     * invoice an order was settled against, which is the one comparison §9.2 check 1 makes.
     */
    fun single(rumor: AttributedRumor.Bound, name: String): List<String>? {
        val found = rumor.tags.occurrences(name)
        if (found.size > 1) {
            throw SettlementException(
                SettlementRejection.DUPLICATE_TAG,
                name,
                "§8.6 fixes what `$name` carries on a payment message and this event carries " +
                    "${found.size} of them; §4.3's rationale applies unchanged — resolving it by " +
                    "first, last or smallest would let two implementations disagree about which " +
                    "invoice an order was settled against",
            )
        }
        return found.firstOrNull()
    }

    /**
     * §7.4's `order` id, which T13 already decoded through T5's codec.
     *
     * The elvis cannot fire on a `type=2` or a `kind:17` — §7.4 requires the tag on both and its
     * single exception is the `type=6` private bid — and is written rather than asserted away
     * because a `!!` here would turn a future loosening of §7.4's rule into a crash in a decoder
     * whose whole job is to refuse hostile input by name.
     */
    fun requireOrder(rumor: AttributedRumor.Bound): OrderId = rumor.order ?: throw missing(
        SettlementVocabulary.ORDER,
        "§7.4 requires an `${SettlementVocabulary.ORDER}` tag on every `kind:16` but a " +
            "`type=${OrderMessageKind.PRIVATE_BID.type}`, and on every `kind:17`",
    )

    /**
     * §8.6's and §9.2's `payment` tag, at the arity the message it is on prints.
     *
     * The arity is the caller's rather than this object's, because it is the one thing the two
     * messages genuinely differ on: §8.6 prints `["payment", "lightning", "<bolt11>"]` for a
     * request, which carries no proof because nothing has been paid yet, and §9.2 prints
     * `["payment", "<medium>", "<medium-reference>", "<proof>"]` for a receipt, which is nothing
     * without one.
     */
    fun payment(rumor: AttributedRumor.Bound, elements: Int): List<String> {
        val tag = single(rumor, SettlementVocabulary.PAYMENT) ?: throw missing(
            SettlementVocabulary.PAYMENT,
            "§8.6 requires exactly one `${SettlementVocabulary.PAYMENT}` tag on a payment request " +
                "and §9.2 prints one on every receipt",
        )
        if (tag.size != elements) {
            throw SettlementException(
                SettlementRejection.WRONG_ARITY,
                SettlementVocabulary.PAYMENT,
                "this message's `${SettlementVocabulary.PAYMENT}` tag carries ${tag.size} " +
                    "element(s) where $elements are required; §9.2 fixes the GammaMarkets shape as " +
                    "name, medium, reference and — on a receipt — proof",
            )
        }
        return tag
    }

    /**
     * §8.6's `["payee", "provider"]` or `["payee", "fee", "<fee-recipient-pubkey-hex>"]`.
     *
     * The two arities are checked **against the role** rather than accepted in either form, exactly
     * as T9 reads §8.1's `fee` tag: §8.6 prints one shape per role, and a three-element `provider`
     * tag is a message naming a recipient for a payee that has none.
     */
    fun payee(rumor: AttributedRumor.Bound): DecodedPayee {
        val tag = single(rumor, SettlementVocabulary.PAYEE) ?: throw missing(
            SettlementVocabulary.PAYEE,
            "§8.6: \"Payment requests MUST be tagged with their payee role\", and §9.2 check 1 " +
                "compares a receipt against the request stored for the same order **and payee**",
        )
        if (tag.size <= SettlementVocabulary.ROLE_INDEX) {
            throw SettlementException(
                SettlementRejection.WRONG_ARITY,
                SettlementVocabulary.PAYEE,
                "a `${SettlementVocabulary.PAYEE}` tag carries a name and a role; this one carries " +
                    "${tag.size} element(s)",
            )
        }
        val token = tag[SettlementVocabulary.ROLE_INDEX]
        val role = Payee.entries.firstOrNull { it.token == token } ?: throw SettlementException(
            SettlementRejection.UNKNOWN_PAYEE_ROLE,
            SettlementVocabulary.PAYEE,
            "§8.6 gives exactly two payee roles and this message names neither; an unrecognised " +
                "role MUST NOT be mapped onto the nearer of the two, for the reason §7.4 gives " +
                "about an unknown `type` and §11.1 about an unknown `status`",
        )
        val elements = when (role) {
            Payee.PROVIDER -> SettlementVocabulary.PAYEE_ELEMENTS_PROVIDER
            Payee.FEE -> SettlementVocabulary.PAYEE_ELEMENTS_FEE
        }
        if (tag.size != elements) {
            throw SettlementException(
                SettlementRejection.WRONG_ARITY,
                SettlementVocabulary.PAYEE,
                "§8.6 prints `${SettlementVocabulary.PAYEE}` with $elements element(s) for the " +
                    "`$token` role and this one carries ${tag.size}",
            )
        }
        val recipient = if (role == Payee.FEE) {
            try {
                readPubkeyHex(tag[SettlementVocabulary.RECIPIENT_INDEX], "a fee recipient pubkey")
            } catch (refused: TagException) {
                throw SettlementException(
                    SettlementRejection.MALFORMED_PAYEE_RECIPIENT,
                    SettlementVocabulary.PAYEE,
                    "§8.6's fee payee names the recipient's x-only pubkey and §4.3 fixes one as " +
                        "exactly 64 hex characters, rejecting a value of the wrong length rather " +
                        "than padding or truncating it",
                    refused,
                )
            }
        } else {
            null
        }
        return DecodedPayee(role, recipient)
    }

    /** §8.6's or §9.2's [tag] is REQUIRED on this message and the event carries no readable one. */
    fun missing(tag: String, clause: String): SettlementException = SettlementException(
        SettlementRejection.MISSING_REQUIRED_TAG,
        tag,
        "$clause; this event carries no readable `$tag`",
    )

    /**
     * §8.6's payee role and, for a fee payee, the recipient key it names.
     *
     * A pair of values rather than two reads, so the arity rule and the role can never be decided
     * by two different call sites. Internal: what a caller reads is [PaymentRequest.payee] and
     * [PaymentRequest.feeRecipient].
     */
    class DecodedPayee(val role: Payee, val recipient: String?) {

        /** Names the role, which is a vocabulary token, and never the recipient key (§12 item 2). */
        override fun toString(): String =
            "DecodedPayee(role=${role.token}, recipient=${if (recipient == null) "absent" else "redacted"})"
    }
}

/**
 * §8.6's `kind:16` `type=2` payment request, decoded off a §7.2-attributed rumor.
 *
 * ### What this closes, and what it deliberately does not
 *
 * §17 item 6 is the one conformance item that names a **persistence** obligation — "having
 * persisted the payment requests **and their acceptance timestamps** that evidence is checked
 * against" — and T3 deferred §9.2 check 1 in writing because nothing then had anywhere to keep
 * them. This type is the message; [AcceptedPaymentRequest] is the record; [PaymentRequestStore] is
 * the place. Together they are what check 1 compares against.
 *
 * §8.6's **non-custodial rule is structural here rather than tested**: a request names exactly one
 * payee, the store is keyed by payee, and there is no shape in this package that could express a
 * single invoice covering `total_msat` for two of them. `SettlementStructureTest` asserts that by
 * reflection over the *generic* signatures — `List<Payee>` erases to `List`, and a sweep reading
 * the erased form would pass over exactly the shape §8.6 forbids.
 *
 * ### Which layer enforces which rule, said out loud
 *
 * - **§4.1 and §7.2** are T8's and T13's: the id was recomputed and the seal's pubkey equals the
 *   rumor's before an `AttributedRumor.Bound` exists at all.
 * - **§7.4's envelope** is T13's: the required `nenya`, `p` and `order` tags, the `type`
 *   discriminator in canonical decimal, and §4.3's duplicate rule over `order` and `type`.
 * - **§5.3's Encoding column** is T9's, applied under the `type=2` context T13 declared.
 * - **§8.6's own rules** are here: one `payment` tag, one `payee` tag, the `lightning` medium, the
 *   BOLT-11 recogniser, and the payee-must-be-required rule §8.3 states about a zero amount.
 *
 * ### Two narrowings of this **decode**, and where each is discharged
 *
 * - **§8.7 is not performed here.** A `payee=fee` request MUST arrive in a gift wrap whose seal
 *   pubkey equals the fee recipient named in the signed fee term. This type publishes [sender] —
 *   §7.2's attributed key — and [feeRecipient] — §8.6's third element — and compares neither,
 *   because the operand §8.7 actually names is the recipient in the **signed fee term**, which is
 *   a sequence of raw tags across §8.4's points and not something [decode]'s [FeeSplit] holds:
 *   `FeeSplit` carries the basis points and not the recipient. `Settlement.checkFeePaymentRequest`
 *   is where that comparison is made, and it takes both.
 * - **§8.5 is not performed here either, and MUST NOT be.** "Fees settle only at settlement" makes
 *   the state a precondition of a fee **payment**, not of a fee payment **request**: §8.5 says in
 *   as many words that a rule refusing this message while the order is still `committed` would make
 *   `awaiting_payment` unreachable for every fee-bearing order. The precondition bites on the
 *   `kind:17`, in `Settlement.verifyFeeReceipt`.
 *
 * Pure computation: no clock, no randomness, no I/O. The clock is read one step later, by
 * [AcceptedPaymentRequest.accept], which is where §17 item 6's second value comes from.
 */
public class PaymentRequest internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this request is for. Never `null` on a `type=2`. */
    public val order: OrderId,

    /** Which side of §8.6's two invoices this request is from. Exactly one, never a collection. */
    public val payee: Payee,

    /**
     * §8.6's third `payee` element — the fee recipient's x-only pubkey — or `null` on a provider
     * request, which names none.
     *
     * Published because §8.7's binding is a comparison between this and the fee recipient in the
     * **signed fee term**, and a caller that must eventually make it needs both operands. This
     * library makes neither half of that comparison: see this class's note.
     */
    public val feeRecipient: String?,

    /** §8.6's BOLT-11 reference, held verbatim — the operand of §9.2 check 1. */
    public val invoice: Bolt11Reference,
) {

    /**
     * §7.2's attribution: the key the seal carrying this request was signed by, lowercase.
     *
     * Whether that key is the provider's, or the fee recipient's, is the caller's resolution and
     * never this library's claim — the same division `OrderProposal` records one package over.
     */
    public val sender: String get() = rumor.author

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6); no deadline here is evaluated against it. */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /**
     * Names the payee role and nothing else.
     *
     * §12 items 1, 2 and 11 and STOP RULE 14: this value holds an order id, an invoice string and,
     * on a fee request, a counterparty pubkey. A role is a vocabulary token and none of those.
     */
    override fun toString(): String = "PaymentRequest(payee=${payee.token})"

    public companion object {

        /**
         * §8.6's payment request, decoded and checked against the order's own terms.
         *
         * Takes an [AttributedRumor.Bound] rather than a `CheckedEvent`, for the reason
         * `OrderProposal.decode` gives: §4.1 requires the id check before any other processing and
         * §7.2 requires the pubkey-equality check before a term is read, and the way to enforce an
         * ordering is to make the later step take a type only the earlier steps can produce.
         *
         * @param split §8.3's arithmetic for the **accepted** terms of [PaymentRequest.order]. Not
         *   optional, and not a convenience: §8.6 says a payee whose expected amount is `0` has no
         *   payment request at all and that "any that arrives MUST be rejected", so there is no
         *   version of this decode that can be performed without the terms. The answer is read out
         *   of `Payee.requiredPayees`, which owns that rule, rather than re-derived here.
         * @throws SettlementException naming which §8.6, §9.3, §7.4 or §4.3 rule refused the
         *   request, and the tag it was about.
         */
        public fun decode(rumor: AttributedRumor.Bound, split: FeeSplit): PaymentRequest {
            if (rumor.type != OrderMessageKind.PAYMENT_REQUEST) {
                throw SettlementException(
                    SettlementRejection.NOT_A_PAYMENT_REQUEST,
                    ChannelTags.TYPE,
                    "this decoder reads §7.4's `type=${OrderMessageKind.PAYMENT_REQUEST.type}` on a " +
                        "kind:${RumorKind.ORDER_MESSAGE.kind}; the rumor it was handed is a " +
                        "kind:${rumor.kind.kind} ${rumor.type?.name ?: "rumor carrying no type"}. A " +
                        "well-formed message of another kind is not a malformed one",
                )
            }
            val order = SettlementTags.requireOrder(rumor)
            val payee = SettlementTags.payee(rumor)
            // §8.3 and §8.6, consumed rather than re-derived: T2 computed `feePayeeRequired` from
            // the amount and T3 folded it into `requiredPayees` for exactly this caller, and two
            // copies of a deadlock rule is how the two drift apart. The provider half falls out of
            // the same clause — §9.2's "expected amount is non-zero" — and is not a second rule.
            if (payee.role !in Payee.requiredPayees(split)) {
                throw SettlementException(
                    SettlementRejection.PAYEE_NOT_REQUIRED,
                    SettlementVocabulary.PAYEE,
                    "§8.6: when a payee's expected amount is `0`, no payment request exists at all " +
                        "for it and any that arrives MUST be rejected (§8.3). Accepting one strands " +
                        "the order: the implementation would hold an invoice for a payee no receipt " +
                        "may ever be required from, and §11.1 says such an order expires",
                )
            }
            val tag = SettlementTags.payment(rumor, SettlementVocabulary.PAYMENT_ELEMENTS_REQUEST)
            val medium = PaymentMedium.of(tag[SettlementVocabulary.MEDIUM_INDEX])
            if (medium != PaymentMedium.LIGHTNING) {
                throw SettlementException(
                    SettlementRejection.MEDIUM_NOT_LIGHTNING,
                    SettlementVocabulary.PAYMENT,
                    "§8.6: a `type=${OrderMessageKind.PAYMENT_REQUEST.type}` MUST carry exactly one " +
                        "`[\"${SettlementVocabulary.PAYMENT}\", " +
                        "\"${PaymentMedium.LIGHTNING.token}\", \"<bolt11>\"]` tag. §9.4's other " +
                        "rails are a **receipt** rule — one MAY be parsed there and evidences " +
                        "nothing — and reading that permission onto a request would store an " +
                        "invoice this library can never verify",
                )
            }
            // Validated, never wrapped in a `try` that would swallow it: STOP RULE 1.
            val invoice = Bolt11Reference.recognise(tag[SettlementVocabulary.REFERENCE_INDEX])
            return PaymentRequest(rumor, order, payee.role, payee.recipient, invoice)
        }
    }
}
