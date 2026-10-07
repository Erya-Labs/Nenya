package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderMessageKind
import dev.eryalabs.nenya.channel.RumorBuild
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorKind
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.WireEvent

/**
 * What [PaymentWriter] answers: the unsigned rumor, or the §8.6, §9.2 or §9.4 rule that refused it.
 *
 * A value rather than an exception, for the reason [RumorBuild] and `ListingBuild` give. Its own
 * type rather than [RumorBuild] because the reasons are a different vocabulary: a §8.6 refusal is a
 * [SettlementRejection] and a §7.4 one is a [ChannelRejection], and flattening the two would make
 * a caller read the §8.6 answer out of a channel constant. Both appear here, each on its own field,
 * because both layers really do refuse these two messages — §7.4's envelope is checked by the same
 * code that checks it for every other rumor, and saying so is better than re-deriving it.
 */
public sealed interface PaymentBuild {

    /**
     * The unsigned rumor, for the app to seal with `GiftWrap.seal`.
     *
     * Nothing is sealed, signed or published here, and §4.1's `id` is not carried: the same
     * division [RumorBuild.Built] records, which is where §4.3's four event-level bounds are
     * measured too.
     */
    public class Built internal constructor(

        /** §4.1's five id-bearing fields, with §8.6's or §9.2's tags in their emission order. */
        public val event: WireEvent,
    ) : PaymentBuild {

        /** Names the kind and a tag count. §12 items 1, 2 and 11 keep the rest out of a log. */
        override fun toString(): String = "PaymentBuild.Built(kind=${event.kind}, tags=${event.tags.size})"
    }

    /**
     * The message was refused, carrying the reason and the tag it is about as **data**.
     *
     * Exactly one of [reason] and [channelReason] is non-`null`, and which one says which layer
     * refused: §8.6's and §9.2's own rules are a [SettlementRejection], while §7.4's envelope and
     * §4.1's event fields come back from [RumorWriter] as a [ChannelRejection]. A caller that does
     * not care reads [tag] and [detail]; one that switches on a vocabulary gets the right one
     * rather than a translation of it.
     */
    public class Refused internal constructor(

        /** Which §8.6, §9.2 or §9.4 rule refused the message, or `null` if §7.4's envelope did. */
        public val reason: SettlementRejection?,

        /** Which §7.4, §4.1 or §4.5 rule refused it, or `null` if one of §8.6's own did. */
        public val channelReason: ChannelRejection?,

        /** The tag this refusal is about, or `null` where the rule names no single tag. */
        public val tag: String?,

        /**
         * Why, in words, for a log.
         *
         * **Never echoes the caller's input** (§12, STOP RULE 14): a tag name, a count, a payee
         * role and a reason may appear; an order id, an invoice, a preimage, a pubkey or an amount
         * may not.
         */
        public val detail: String,
    ) : PaymentBuild {

        override fun toString(): String =
            "PaymentBuild.Refused(reason=$reason, channelReason=$channelReason, tag=$tag)"
    }
}

/**
 * The write half of §8.6 and §9.2: the `type=2` payment request and the `kind:17` receipt, each
 * returning the unsigned rumor an app then seals.
 *
 * ### Why these two are here and not in [RumorWriter]
 *
 * §8.6's `payee` and `payment` tags are `SettlementVocabulary`'s, which is this package's and not
 * §5.3's or §7.4's — exactly as [RumorWriter]'s KDoc records for `order` and `type`. A writer for
 * them in the channel package would either duplicate those constants or make the channel package
 * depend on this one's vocabulary. So the §7.4 **envelope** is built by [RumorWriter], through the
 * `kind:14` entry point it already has for a rumor with no required tags, and the §8.6 and §9.2
 * rows are added here. That is a deliberate inversion of the obvious structure, and it is what
 * keeps one copy of §7.4's envelope rules rather than two.
 *
 * ### What it adds and what it must not
 *
 * **It adds no rule about evidence and relaxes none** (STOP RULE 5). Nothing here accepts, stores
 * or verifies anything: [paymentRequest] produces an unsigned event, and whether that event is a
 * request this implementation will act on is decided where it already was — by
 * `PaymentRequest.decode` against §8.3's split, and then by `AcceptedPaymentRequest.accept`
 * against the acceptance, the clock and the store. T41 says so in as many words, and the proof
 * follows it: the round trip for a `type=2` runs the built event through **that acceptance path**
 * rather than through a rule of this writer's own, so a builder that emitted something §8.6 would
 * refuse is caught by §8.6's existing refusal and not by a second copy of it living here.
 *
 * The one place this writer is stricter than the decoder is §9.2 check 2's **case**, and that is
 * the document's own asymmetry rather than a new rule: check 2 requires the proof be lowercase hex
 * and §4.3 names the preimage as one of exactly two values where no normalisation is permitted, so
 * a writer that emitted an uppercase proof would be emitting a receipt its own decoder rejects.
 * Refusing it at the writer is the same refusal one step earlier.
 *
 * ### What it refuses, by value
 *
 * **In the order the checks are made**, because exactly one refusal is reported and it is the
 * first:
 *
 * 1. §8.6's payee arity — a `provider` request naming a recipient, or a `fee` request naming none
 *    ([SettlementRejection.WRONG_ARITY]) — and a recipient that is not 64 hex characters
 *    ([SettlementRejection.MALFORMED_PAYEE_RECIPIENT]).
 * 2. §8.6's medium: a `type=2` MUST carry `["payment", "lightning", "<bolt11>"]` and no other rail
 *    ([SettlementRejection.MEDIUM_NOT_LIGHTNING]). §9.4's other rails are a *receipt* permission,
 *    and reading it onto a request would store an invoice this library can never verify.
 * 3. §9.4's unknown sink, on either message: [PaymentMedium.UNKNOWN] is the required treatment of
 *    somebody else's token rather than a token, so it has no wire form
 *    ([SettlementRejection.UNKNOWN_MEDIUM_IS_NOT_EMITTABLE]).
 * 4. §9.2 check 2's shape and case on a Lightning receipt's proof
 *    ([SettlementRejection.PREIMAGE_MALFORMED]).
 * 5. §7.4's envelope, §4.1's event fields and §4.3's extra-tag rules, all through [RumorWriter] —
 *    reported on [PaymentBuild.Refused.channelReason].
 *
 * Pure computation: no clock, no randomness, no I/O. The clock is read one step later, by
 * `AcceptedPaymentRequest.accept`.
 */
public object PaymentWriter {

    /**
     * A `type=2` payment request. §8.6 prints no whole worked event, so the order is this
     * writer's, modelled on §9.2's receipt — the one message in §8 or §9 that *is* printed whole.
     *
     * §9.2's example prints `order`, `payee`, `payment`, `amount_msat`, `nenya`, `p`; this is that
     * order with `type` in the position §7.5's example puts it (directly after `order`) and
     * without the `amount_msat` §8.6 does not ask a request to carry — the invoice is the amount
     * on a request, and §8.6's refusals are all stated about it.
     */
    public val REQUEST_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            SettlementVocabulary.ORDER,
            TYPE,
            SettlementVocabulary.PAYEE,
            // §8.4 point 3: the fee term is REQUIRED on a `payee=fee` request and OPTIONAL on a
            // `payee=provider` one. It sits beside the payee it qualifies rather than at either
            // end, which is also where a reader comparing the pair against §8.4's other points
            // finds it.
            FEE,
            SettlementVocabulary.PAYMENT,
            VERSION,
            COUNTERPARTY,
        ),
    )

    /**
     * A `kind:17` receipt, in the order §9.2's own worked example prints its six tags — **plus**
     * §8.4's `fee`, which that example does not print and which point 4 makes REQUIRED on one of
     * the two payees.
     *
     * `PaymentWriterTest` holds this list **minus `fee`** equal to the tag names parsed out of the
     * example at test time, so §9.2 is one side of the comparison rather than this file, and the one
     * extra is documented rather than excused: §9.2's worked receipt is a `payee=provider` one, and
     * §8.4 says so itself — "the `kind:17` example in §9.2 correctly carries no `fee` tag" — while
     * making it REQUIRED on a `payee=fee` receipt (§9.2 check 6). Same relationship
     * `DeliveryCommitmentMessage.readableTags` has to §10.1's example and its one `subject`.
     */
    public val RECEIPT_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            SettlementVocabulary.ORDER,
            SettlementVocabulary.PAYEE,
            FEE,
            SettlementVocabulary.PAYMENT,
            AMOUNT_MSAT,
            VERSION,
            COUNTERPARTY,
        ),
    )

    /**
     * §8.6's `kind:16` `type=2` payment request — "exactly one `["payment", "lightning",
     * "<bolt11>"]` tag", for exactly one payee.
     *
     * §8.6's non-custodial rule is **structural** here, as it is on the read side: this function
     * names one [Payee] and there is no shape in this package that could express a single invoice
     * covering `total_msat` for two of them. `SettlementStructureTest` asserts that by reflection
     * over the generic signatures.
     *
     * @param payee which side of §8.6's two invoices this request is from. Exactly one, never a
     *   collection.
     * @param feeRecipient §8.6's third `payee` element, REQUIRED on a `fee` request and refused on
     *   a `provider` one — §8.6 prints one shape per role, and a three-element `provider` tag is a
     *   message naming a recipient for a payee that has none.
     * @param invoice §8.6's BOLT-11 reference, already recognised by `Bolt11Reference.recognise`.
     *   A `Bolt11Reference` and never a string, so the one door an unrecognised reference could
     *   enter by is the one §8.6's own recogniser guards: "an implementation MUST reject a
     *   `payment` tag whose reference is not a BOLT-11 invoice string".
     * @param medium defaulted to [PaymentMedium.LIGHTNING] and refused for anything else, so the
     *   parameter exists only to make §8.6's refusal reachable from the API rather than only from a
     *   hostile peer. §9.4's `bitcoin` and `ecash` are a receipt permission and not a request one.
     * @param terms the accepted terms of [order], which §8.4 point 3 makes **REQUIRED** on a
     *   `payee=fee` request: "the same `(bps, recipient)` pair MUST appear, byte-identically,
     *   wherever a `fee` tag appears for an order", and point 3 is the fee payment request. A fee
     *   request built without them is refused as [SettlementRejection.FEE_TERM_POINT_MISSING]
     *   rather than emitted, because `Settlement.checkFeePaymentRequest` — which
     *   `AcceptedPaymentRequest.accept` runs on every fee request — refuses a message carrying no
     *   point 3 at all, so a writer that omitted it would emit a request this library cannot
     *   accept.
     *
     *   §8.4 makes it **OPTIONAL** on a `payee=provider` request, and adds that an implementation
     *   MUST NOT require one there — so `null` is legal on that side and the tag is simply absent.
     *   Where terms *are* supplied the tag is emitted for either payee, which is the other half of
     *   §8.4: "where one **is** present it MUST match". The tag is spelled by the same `TagWriter.fee`
     *   the proposal and the acceptance go through, which is what makes the three byte-identical.
     */
    public fun paymentRequest(
        envelope: RumorEnvelope,
        order: OrderId,
        payee: Payee,
        invoice: Bolt11Reference,
        feeRecipient: String? = null,
        medium: PaymentMedium = PaymentMedium.LIGHTNING,
        terms: OrderTerms? = null,
    ): PaymentBuild {
        val payeeTag = payeeTag(payee, feeRecipient) ?: return refused(
            SettlementRejection.WRONG_ARITY,
            SettlementVocabulary.PAYEE,
            "§8.6 prints `${SettlementVocabulary.PAYEE}` with " +
                "${SettlementVocabulary.PAYEE_ELEMENTS_PROVIDER} element(s) for the " +
                "`${Payee.PROVIDER.token}` role and ${SettlementVocabulary.PAYEE_ELEMENTS_FEE} for " +
                "`${Payee.FEE.token}`: the recipient is REQUIRED on a fee request and has nothing " +
                "to name on a provider one",
        )
        val normalisedPayee = normalised(payeeTag) ?: return refused(
            SettlementRejection.MALFORMED_PAYEE_RECIPIENT,
            SettlementVocabulary.PAYEE,
            "§8.6's fee payee names the recipient's x-only pubkey and §4.3 fixes one as " +
                "exactly 64 hex characters, rejecting a value of the wrong length rather " +
                "than padding or truncating it",
        )
        if (medium == PaymentMedium.UNKNOWN) return unknownMedium()
        if (medium != PaymentMedium.LIGHTNING) {
            return refused(
                SettlementRejection.MEDIUM_NOT_LIGHTNING,
                SettlementVocabulary.PAYMENT,
                "§8.6: a `type=${OrderMessageKind.PAYMENT_REQUEST.type}` MUST carry exactly one " +
                    "`[\"${SettlementVocabulary.PAYMENT}\", \"${PaymentMedium.LIGHTNING.token}\", " +
                    "\"<bolt11>\"]` tag. §9.4's other rails are a **receipt** rule — one MAY be " +
                    "parsed there and evidences nothing — and emitting one here would put an " +
                    "invoice on the wire this library can never verify",
            )
        }
        // `medium.token` is non-null for every constant but UNKNOWN, refused above. The elvis is
        // written rather than asserted away because a `!!` in a writer whose whole job is to refuse
        // by value would be the one line that could still crash a caller.
        val token = medium.token ?: return unknownMedium()
        return assemble(
            kind = RumorKind.ORDER_MESSAGE,
            type = OrderMessageKind.PAYMENT_REQUEST,
            envelope = envelope,
            order = order,
            tagOrder = REQUEST_TAG_ORDER,
            required = requestRequiredTags(),
            payee = payee,
            payeeTag = normalisedPayee,
            paymentTag = listOf(SettlementVocabulary.PAYMENT, token, invoice.text),
            terms = terms,
            feeRecipient = feeRecipient,
            point = FeeTermPoint.FEE_PAYMENT_REQUEST,
            pointClause = "§8.4 point 3",
            // §8.6 does not ask a request to carry one, and its own refusals are all stated about
            // the invoice: the amount on a request *is* the invoice, and §9.2 check 4 reads it from
            // the human-readable part rather than from a tag beside it.
            amountMsat = null,
        )
    }

    /**
     * §9.2's `kind:17` payment receipt, in the GammaMarkets shape §9.2 fixes:
     * `["payment", "<medium>", "<medium-reference>", "<proof>"]`.
     *
     * **Nothing here is evidence.** This function writes the four elements a receipt carries; §9.2's
     * five checks are performed by `Settlement.verify` against the store, and none of them is made
     * or claimed here. In particular the `reference` is not compared to anything — check 1's
     * byte-identical comparison is against the *stored* request, which this writer has no access
     * to and must not — and the proof is not hashed against a payment hash, which is check 3's and
     * is `Settlement`'s.
     *
     * @param medium §9.4's rail. [PaymentMedium.LIGHTNING] is the only one §9.2 defines a
     *   verification rule for; `bitcoin` and `ecash` are emittable here because §9.4 says a receipt
     *   naming one MAY be parsed and MUST be treated as unverified, which is a shape a conformant
     *   peer may send and therefore one this library must be able to write for a test to exercise.
     * @param reference the `<medium-reference>` — the BOLT-11 string on a Lightning receipt, an
     *   address on `bitcoin`, a mint on `ecash`. A bare `String` and not a `Bolt11Reference`,
     *   precisely because §9.4's other two rails carry something that is not an invoice at all;
     *   check 1 compares it byte-identically against the stored request, so holding anything that
     *   normalised it would be holding the wrong value.
     * @param proof §9.2's `<proof>`. On Lightning it is the 32-byte preimage as 64 **lowercase**
     *   hex characters and this writer holds it to exactly that (check 2, §4.3's no-normalisation
     *   exception list). On §9.4's other rails it is a txid or a mint proof and v1 defines no
     *   verification rule for either, so it is emitted verbatim and this library reads it as
     *   nothing — the same asymmetry `PaymentReceipt.decode` has, where the preimage reader runs
     *   only when `medium.hasVerificationRule`.
     * @param terms the accepted terms of [order], or `null` to emit no `["amount_msat", …]` tag.
     *   §9.2's example prints one on a receipt, and the figure it can honestly carry is **the
     *   expected amount for this payee** — `price_msat` for `provider` and §8.3's `fee_msat` for
     *   `fee` — which is check 4's own operand. So it is computed by `Settlement.expectedAmount`,
     *   the one function in this library that reads §9.2's sentence, rather than taken as a number
     *   a caller chose: a receipt advertising an amount other than the one check 4 is about would
     *   contradict itself, and a second reading of that sentence beside the existing one is how a
     *   request comes to be stored against a figure the receipt is then refused against.
     *
     *   §9.2 states no rule for this tag and no check reads it — check 4's amount is the one
     *   encoded in the **invoice** — so a receipt carrying none is one `Settlement.verify` treats
     *   identically, and `null` is a real choice rather than a missing one.
     */
    public fun receipt(
        envelope: RumorEnvelope,
        order: OrderId,
        payee: Payee,
        reference: String,
        proof: String,
        feeRecipient: String? = null,
        medium: PaymentMedium = PaymentMedium.LIGHTNING,
        terms: OrderTerms? = null,
    ): PaymentBuild {
        val payeeTag = payeeTag(payee, feeRecipient) ?: return refused(
            SettlementRejection.WRONG_ARITY,
            SettlementVocabulary.PAYEE,
            "§9.2 check 1 compares a receipt against the request stored for the same order **and " +
                "payee**, and §8.6 prints one `${SettlementVocabulary.PAYEE}` arity per role: the " +
                "recipient is REQUIRED on a fee receipt and has nothing to name on a provider one",
        )
        val normalisedPayee = normalised(payeeTag) ?: return refused(
            SettlementRejection.MALFORMED_PAYEE_RECIPIENT,
            SettlementVocabulary.PAYEE,
            "§8.6's fee payee names the recipient's x-only pubkey and §4.3 fixes one as " +
                "exactly 64 hex characters",
        )
        if (medium == PaymentMedium.UNKNOWN) return unknownMedium()
        val token = medium.token ?: return unknownMedium()
        // §9.2 check 2, applied on write and only where §9.2 states it: the proof is a preimage on
        // Lightning and is a txid or a mint proof on §9.4's other rails, for which v1 defines no
        // rule at all. Holding a txid to 64 lowercase hex characters would refuse a receipt §9.4
        // says MAY be parsed, which is the over-strict direction `PaymentReceipt.decode` avoids by
        // the same test.
        if (medium.hasVerificationRule && !isLowercasePreimageHex(proof)) {
            return refused(
                SettlementRejection.PREIMAGE_MALFORMED,
                SettlementVocabulary.PAYMENT,
                "§9.2 check 2: the proof MUST be **lowercase** hex and MUST decode to exactly " +
                    "$PREIMAGE_BYTES bytes. Uppercase and mixed case are rejected rather than " +
                    "normalised — §4.3 names the preimage as one of exactly two values where no " +
                    "normalisation of any kind is permitted — so a writer that emitted one would " +
                    "emit a receipt this library's own decoder refuses. This proof is " +
                    "${proof.length} character(s)",
            )
        }
        return assemble(
            kind = RumorKind.RECEIPT,
            type = null,
            envelope = envelope,
            order = order,
            tagOrder = RECEIPT_TAG_ORDER,
            required = receiptRequiredTags(),
            payee = payee,
            payeeTag = normalisedPayee,
            paymentTag = listOf(SettlementVocabulary.PAYMENT, token, reference, proof),
            terms = terms,
            feeRecipient = feeRecipient,
            // §8.4 point 4 — "the fee receipt (`kind:17`, `payee=fee`) — §9.2 check 6" — and
            // OPTIONAL on a `payee=provider` receipt, which §8.4 notes §9.2's own worked example
            // correctly omits.
            point = FeeTermPoint.FEE_RECEIPT,
            pointClause = "§8.4 point 4 and §9.2 check 6",
            // §9.2 check 4's own operand, from the one function that computes it. See [receipt].
            amountMsat = terms?.let { Settlement.expectedAmount(payee, it.split) },
        )
    }

    /**
     * The tags `PaymentRequest.decode` requires on a `type=2`: §7.4's three, plus `type`, plus
     * §8.6's own two.
     *
     * Published because `PaymentWriterTest` walks it, removing each in turn and asserting that
     * **the decoder** refuses the result naming that tag — a proof over a list rather than over
     * hand-written controls that could silently stop covering a sixth. The envelope half is
     * derived from `AttributedRumor.requiredTags()`, which the tests hold equal to §7.4's fenced
     * block parsed out of the document.
     */
    public fun requestRequiredTags(): List<String> = REQUEST_REQUIRED

    /**
     * The tags `PaymentReceipt.decode` requires on a `kind:17`: §7.4's three plus §8.6's two.
     *
     * No `type`: §7.4 puts the discriminator on a `kind:16` and on nothing else. Walked by the
     * same removal loop, for the same reason.
     */
    public fun receiptRequiredTags(): List<String> = RECEIPT_REQUIRED

    // ---------------------------------------------------------------------------------------------
    // Assembly: §7.4's envelope from `RumorWriter`, §8.6's and §9.2's rows here.
    // ---------------------------------------------------------------------------------------------

    /**
     * §8.6's two `payee` arities, or `null` when the role and the recipient disagree.
     *
     * `null` rather than a refusal value, so the two entry points can each cite the clause that is
     * about *them* — §8.6 for a request and §9.2 check 1 for a receipt — while the arity rule
     * itself is written once.
     */
    private fun payeeTag(payee: Payee, feeRecipient: String?): List<String>? = when (payee) {
        // §8.6 prints the provider payee with two elements and no third. The order's fee recipient
        // is not one of them even when this message carries §8.4's optional pair naming it: that
        // pair is on the `fee` tag, and this tag names the payee being billed.
        Payee.PROVIDER -> listOf(SettlementVocabulary.PAYEE, payee.token)
        Payee.FEE ->
            if (feeRecipient == null) null
            else listOf(SettlementVocabulary.PAYEE, payee.token, feeRecipient)
    }

    /**
     * [payeeTag] with its recipient as §4.3 requires it be **emitted** — 64 lowercase hex
     * characters — or `null` where it is not a pubkey at all.
     *
     * ### Why the normalised value is taken rather than the caller's
     *
     * `readPubkeyHex` both checks and lowercases, and the value that reaches the tag is the one it
     * **returns**. Calling it for its side effect and then emitting the caller's spelling is the
     * mistake this function exists to have fixed, and it is wrong three ways at once:
     *
     * 1. §4.3 and §8.6 fix that element as a 64-character *lowercase* hex x-only pubkey, so an
     *    uppercase one is a spelling §4.3 forbids this library to emit;
     * 2. `TagWriter.fee` lowercases the recipient **it** writes, so §8.6's `payee` and §8.4's `fee`
     *    would name one key under two spellings inside a single event this library authored — and
     *    §8.4 requires that pair appear byte-identically wherever a `fee` tag appears for an order;
     * 3. `PaymentRequest.decode` normalises on read, so build → decode → rebuild would emit the
     *    lowercase form and the two events would **not** be byte-identical. T41's headline property
     *    would be false, and it held only because the corpus's generated pubkeys are lowercase by
     *    construction and so could never draw the case. `PaymentWriterTest` now draws it directly.
     *
     * A two-element `provider` tag has no recipient to normalise and is returned unchanged.
     */
    private fun normalised(payeeTag: List<String>): List<String>? {
        if (payeeTag.size <= SettlementVocabulary.RECIPIENT_INDEX) return payeeTag
        val recipient = try {
            readPubkeyHex(payeeTag[SettlementVocabulary.RECIPIENT_INDEX], "a fee recipient pubkey")
        } catch (refusedHex: TagException) {
            return null
        }
        return payeeTag.subList(0, SettlementVocabulary.RECIPIENT_INDEX) + recipient
    }

    /** §8.4 names the point a `fee` tag is REQUIRED at and this message carries none. */
    private fun missingFeeTerm(point: FeeTermPoint, clause: String): PaymentBuild.Refused = refused(
        SettlementRejection.FEE_TERM_POINT_MISSING,
        FEE,
        "$clause makes the `$FEE` term REQUIRED on a `${Payee.FEE.token}` message, and this one " +
            "would carry none. §8.4: the same `(bps, recipient)` pair MUST appear byte-identically " +
            "wherever a `$FEE` tag appears for an order, and a point absent entirely is not a pair " +
            "that matches — `${point.name}` is the point this message is. Pass the order's accepted " +
            "terms, which is where the pair comes from",
    )

    /**
     * The whole message: §8.6's and §9.2's own rows, handed to [RumorWriter.build], which applies
     * §7.4's envelope, §4.1's event fields, §4.5's version rule, §4.3's extra-tag rules and the
     * required-tag audit.
     *
     * **Nothing about §7.4 is re-derived here**, and that is the point of the delegation: `nenya`
     * and `p` spelled out in two files is how two writers come to disagree about §4.5's version,
     * and §4.3's "a row handed in as an extension" rule written twice is how they come to disagree
     * about which refusal that is. So this function spells exactly the three rows
     * `SettlementVocabulary` owns and nothing else, and [RumorWriter]'s refusals are reported
     * verbatim on [PaymentBuild.Refused.channelReason] rather than translated — a §4.3 refusal
     * about an author pubkey is the same refusal whichever message it was building.
     */
    private fun assemble(
        kind: RumorKind,
        type: OrderMessageKind?,
        envelope: RumorEnvelope,
        order: OrderId,
        tagOrder: List<String>,
        required: List<String>,
        payee: Payee,
        payeeTag: List<String>,
        paymentTag: List<String>,
        terms: OrderTerms?,
        feeRecipient: String?,
        point: FeeTermPoint,
        pointClause: String,
        amountMsat: Msat?,
    ): PaymentBuild {
        // §8.4's `(bps, recipient)` pair, which is REQUIRED on a `payee=fee` message (points 3 and
        // 4) and OPTIONAL on a `payee=provider` one — where §8.4 adds that an implementation MUST
        // NOT require one.
        //
        // The recipient is the **order's** fee recipient at both points, and that is the thing
        // worth reading twice: §8.6's `payee` tag names it only on a `payee=fee` message, while
        // §8.4's `fee` tag names it wherever the pair appears. So one parameter serves both and the
        // two tags take different arities from it — a `payee=provider` request carrying §8.4's
        // optional pair is a conformant message, not a provider naming a recipient it has none of.
        val term = terms?.split?.term
        val stated = term != null && term != FeeTerm.Absent
        if (!stated) {
            if (payee == Payee.FEE) return missingFeeTerm(point, pointClause)
            if (feeRecipient != null) {
                return refused(
                    SettlementRejection.MALFORMED_FEE_TERM,
                    FEE,
                    "§8.1 makes the `$FEE` recipient a part of the term, and these terms state no " +
                        "term at all: an absent term has no wire form, so there is nothing for a " +
                        "recipient to belong to. §8.4 makes the pair OPTIONAL on a " +
                        "`${Payee.PROVIDER.token}` message — a recipient without one is not that " +
                        "permission, it is half a pair",
                )
            }
        }
        val feeRows = if (!stated) {
            emptyList()
        } else {
            // Spelled by the same `TagWriter.fee` §7.5's proposal and §7.6's acceptance go through,
            // which is what makes §8.4's points byte-identical rather than identical-looking — and
            // it is what applies §8.1's two arities, refusing a recipient at zero basis points and
            // the absence of one above zero.
            try {
                listOf(TagWriter.fee(requireNotNull(term), feeRecipient))
            } catch (refusedArity: TagException) {
                return refused(
                    SettlementRejection.MALFORMED_FEE_TERM,
                    FEE,
                    "§8.1 fixes the `$FEE` term's two arities — the recipient is REQUIRED above " +
                        "zero basis points and OMITTED at zero — and this pair is neither, as " +
                        "${refusedArity.reason.name}",
                )
            }
        }

        val rows = buildMap<String, List<List<String>>> {
            put(SettlementVocabulary.PAYEE, listOf(payeeTag))
            put(SettlementVocabulary.PAYMENT, listOf(paymentTag))
            // §8.4's pair, or no row at all where §8.4 makes it OPTIONAL and no terms were given.
            put(FEE, feeRows)
            // §9.2's example prints `amount_msat` on a receipt and no check in §9.2 reads it, so it
            // is emitted when the caller supplies one and omitted otherwise. §4.4's strict
            // canonical decimal, which `Long.toString` is for a non-negative value and `Msat`
            // cannot hold a negative one.
            put(
                AMOUNT_MSAT,
                if (amountMsat == null) emptyList()
                else listOf(listOf(AMOUNT_MSAT, amountMsat.millisatoshis.toString())),
            )
        }
        val build = RumorWriter.build(
            kind = kind,
            type = type,
            envelope = envelope,
            order = order,
            tagOrder = tagOrder,
            required = required,
            rows = rows,
        )
        return when (build) {
            is RumorBuild.Built -> PaymentBuild.Built(build.event)
            is RumorBuild.Refused ->
                PaymentBuild.Refused(null, build.reason, build.tag, build.detail)
        }
    }

    /** §9.4's unknown sink has no wire form, on either message, for the reason §9.4 gives. */
    private fun unknownMedium(): PaymentBuild.Refused = refused(
        SettlementRejection.UNKNOWN_MEDIUM_IS_NOT_EMITTABLE,
        SettlementVocabulary.PAYMENT,
        "§9.4's unrecognised `<medium>` is the required *treatment* of a token this implementation " +
            "has never heard of — parsed, and evidencing nothing — and not a value of its own: " +
            "`PaymentMedium.UNKNOWN` carries no token, so there is nothing to emit for it. " +
            "Emitting a sentinel would republish an invented rail as though this implementation " +
            "had defined one",
    )

    /** §9.2 check 2, whole: exactly 64 lowercase hex characters, refused rather than normalised. */
    private fun isLowercasePreimageHex(proof: String): Boolean =
        proof.length == PREIMAGE_BYTES * 2 && proof.all { it in '0'..'9' || it in 'a'..'f' }

    private fun refused(
        reason: SettlementRejection,
        tag: String?,
        detail: String,
    ): PaymentBuild.Refused = PaymentBuild.Refused(reason, null, tag, detail)

    /**
     * §9.2 check 2's length: "the proof … MUST decode to exactly 32 bytes".
     *
     * `Preimage` holds the same rule in its own `init` and publishes no length constant, so this is
     * not a second source of truth for a value that has one — `PaymentWriterTest` asserts that a
     * proof of this length is exactly what `Preimage.ofHex` accepts, which is the comparison that
     * would catch the two drifting apart.
     */
    private const val PREIMAGE_BYTES: Int = 32

    /** §7.4's `type`, whose constant lives in the channel package's vocabulary. */
    private const val TYPE: String = ChannelTags.TYPE

    /** §4.5's `nenya`, likewise. */
    private const val VERSION: String = ChannelVocabulary.VERSION

    /** §7.4's `p`, likewise. */
    private const val COUNTERPARTY: String = ChannelVocabulary.COUNTERPARTY

    /** §7.5's `amount_msat`, which §9.2's receipt example also prints. */
    private const val AMOUNT_MSAT: String = ChannelTags.AMOUNT_MSAT

    /** §8.1's and §8.4's `fee`, which is a §5.3 row and so has its constant in that vocabulary. */
    private const val FEE: String = ChannelVocabulary.FEE

    private val REQUEST_REQUIRED: List<String> = readOnlyListOf(
        AttributedRumor.requiredTags() + listOf(
            TYPE,
            SettlementVocabulary.PAYEE,
            SettlementVocabulary.PAYMENT,
        ),
    )

    private val RECEIPT_REQUIRED: List<String> = readOnlyListOf(
        AttributedRumor.requiredTags() + listOf(
            SettlementVocabulary.PAYEE,
            SettlementVocabulary.PAYMENT,
        ),
    )
}
