package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.WireEvent

/**
 * Whether §8.4 marks a point REQUIRED or OPTIONAL, in §8.4's own two words.
 *
 * Two named constants rather than a `Boolean`, for the reason this whole file exists: §8.4's
 * OPTIONAL clause is a MUST NOT — "an implementation MUST NOT require a `fee` tag on any of the
 * three" — so "optional here" is an obligation and not the absence of one, and a flag spelled
 * `isRequired == false` reads as nothing having been said.
 */
public enum class FeeTermRequirement {

    /** §8.4's numbered list: the `fee` tag MUST appear at this point, byte-identically. */
    REQUIRED,

    /**
     * §8.4's following sentence: an implementation MUST NOT require a `fee` tag here — "but where
     * one **is** present it MUST match".
     */
    OPTIONAL,
}

/**
 * One of the seven points §8.4 names as a place a `fee` tag appears for an order.
 *
 * ### The set is held equal to §8.4, parsed at test time
 *
 * §8.4 states four REQUIRED points as a numbered list and three OPTIONAL ones in the sentence
 * that follows. [descriptor] is that phrase, normalised — the document's own words with its
 * backticks and its section references removed — and `Section84` parses both out of
 * `spec/NENYA-1.md` and holds this enum equal to them, required and optional kept apart. A codec
 * covering two of the four cannot pass, and a §8.4 revision turns the suite red rather than
 * leaving this vocabulary quietly agreeing with itself.
 *
 * ### Why the bid is a point at all
 *
 * §8.4 makes the `fee` tag OPTIONAL on a bid and then says "a bid that carried a `fee` tag binds
 * the bidder to that term (§6, §6.1)". A vocabulary that modelled only the four REQUIRED points
 * could not express that binding, and an implementation that dropped a bid's fee tag from the
 * comparison would let a bidder quote one fee and settle another.
 *
 * ### Never branch on ordinal
 *
 * Declaration order follows §8.4's — the four required, then the three optional — and carries no
 * meaning beyond readability. It is **not** a message ordering: an order's points arrive in the
 * sequence the caller observed them, which is what [FeeTermAgreement.across] takes.
 */
public enum class FeeTermPoint(

    /** §8.4's own phrase for this point, normalised. Held equal to the parsed document. */
    public val descriptor: String,

    /** Whether §8.4 marks this point REQUIRED or OPTIONAL. */
    public val requirement: FeeTermRequirement,
) {

    /**
     * §8.4 point 1 — the order proposal (`type=1`).
     *
     * Subject to §8.1's read rule, which [FeeTermAgreement.across] consumes rather than restates:
     * a proposal with no `fee` tag states a zero fee, and the pair for that order is `(0, —)` for
     * every later check. So the sighting for such a proposal is a **deliberate absence**, and an
     * order opened that way has a fee term to compare against and not a hole.
     */
    ORDER_PROPOSAL("order proposal type=1", FeeTermRequirement.REQUIRED),

    /** §8.4 point 2 — the acceptance (`type=3`, `status=accepted`), byte-identical to §7.6's terms. */
    ACCEPTANCE("acceptance type=3 status=accepted", FeeTermRequirement.REQUIRED),

    /** §8.4 point 3 — the fee payment request (`type=2`, `payee=fee`). */
    FEE_PAYMENT_REQUEST("fee payment request type=2 payee=fee", FeeTermRequirement.REQUIRED),

    /** §8.4 point 4 — the fee receipt (`kind:17`, `payee=fee`), which is §9.2 check 6. */
    FEE_RECEIPT("fee receipt kind:17 payee=fee", FeeTermRequirement.REQUIRED),

    /** §8.4's first OPTIONAL point — the bid (§6). A `fee` tag here binds the bidder to that term. */
    BID("bid", FeeTermRequirement.OPTIONAL),

    /** §8.4's second OPTIONAL point — a `payee=provider` payment request. */
    PROVIDER_PAYMENT_REQUEST("payee=provider payment request", FeeTermRequirement.OPTIONAL),

    /** §8.4's third OPTIONAL point — a `payee=provider` receipt, which §9.2's own example omits. */
    PROVIDER_RECEIPT("payee=provider receipt", FeeTermRequirement.OPTIONAL),
    ;

    public companion object {

        /** §8.4's numbered list, derived from [requirement] rather than listed a second time. */
        public val REQUIRED: Set<FeeTermPoint> =
            readOnlySetOf(entries.filter { it.requirement == FeeTermRequirement.REQUIRED })

        /** §8.4's following sentence, derived the same way. */
        public val OPTIONAL: Set<FeeTermPoint> =
            readOnlySetOf(entries.filter { it.requirement == FeeTermRequirement.OPTIONAL })
    }
}

/**
 * Which half of §8.1's `(bps, recipient)` pair diverged — the *side* of the comparison, beside the
 * point it diverged at.
 *
 * §8.4's divergence is a fact about a pair and not about a tag, and the two halves send a reader
 * somewhere different: a different [BASIS_POINTS] is a counterparty quoting another price, a
 * different [RECIPIENT] is somebody substituting themselves for the fee recipient, and a
 * [PRESENCE] mismatch is a message that states a fee where the signed terms state none. Collapsing
 * them loses the one distinction §8.4's "surfaced to the user as a terms mismatch" is about.
 */
public enum class FeeTermElement {

    /**
     * One point carried a `fee` tag and another deliberately carried none.
     *
     * §8.1's read rule is what makes this a divergence rather than a shrug: an absent tag on a
     * proposal *states* `(0, —)`, so a later point carrying `["fee", "0"]` is not the same signed
     * statement — and a later point carrying `["fee", "250", X]` is a fee invented after the fact.
     */
    PRESENCE,

    /** The `<basis-points>` element differs, byte for byte (§8.1, §4.4's strict decimal form). */
    BASIS_POINTS,

    /**
     * The `<recipient-pubkey-hex>` element differs, byte for byte — including one point naming a
     * recipient where the other omits it, which §8.1's two arities make the same divergence.
     */
    RECIPIENT,
}

/**
 * One `(point, fee tag or deliberately absent)` pair — what §8.4 compares, at one of the places it
 * compares them.
 *
 * ### The tag is held raw, and that is what "byte-identical" means
 *
 * §8.4: "'Byte-identical' here means what it says: `["fee","250",X]` and `["fee","0250",X]`
 * diverge, and §4.4's strict decimal form is what keeps that from happening on write." So the
 * elements are the strings the event carried, never a parsed `FeeTerm` and never a normalised
 * pubkey — `FeeTerm.Stated(250)` compares equal to itself however it was spelled, which is exactly
 * the comparison §8.4 says is not the one to make.
 *
 * The parse still does the work that keeps the byte comparison honest, one layer down and before
 * this type exists: T9's tag codec reads `<basis-points>` through §4.4's strict decimal form, so
 * `["fee", "0250", X]` is refused as a malformed number at decode and never reaches a comparison
 * at all. That is why the factories below take **decoded** messages rather than a `WireEvent`: a
 * sighting read off an event nobody parsed would be comparing bytes nobody validated.
 *
 * [term] is internal for the reason `PaymentReceipt.reference` is: it is an operand of a
 * comparison rather than a term a caller reads. What a caller needs from a failed comparison is
 * [FeeTermAgreement.Diverged], and what it needs from a successful one is
 * [FeeTermAgreement.Agreed.recipient].
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class FeeTermSighting internal constructor(

    /** Where in §8.4's list this sighting was taken. */
    public val point: FeeTermPoint,

    /** §8.1's `fee` tag verbatim, or `null` where the message deliberately carried none. */
    internal val term: List<String>?,
) {

    /** Names the point and whether a tag was there — never the pair (§12 item 2, §12 item 11). */
    override fun toString(): String =
        "FeeTermSighting(point=${point.name}, fee=${if (term == null) "absent" else "present"})"

    public companion object {

        /** §8.1's tag name, read off this package's vocabulary rather than spelled again. */
        internal const val FEE: String = SettlementVocabulary.FEE

        /** `["fee", "<bps>"]` — §8.1's zero-basis-point arity. */
        private const val ELEMENTS_ZERO: Int = 2

        /** `["fee", "<bps>", "<recipient-pubkey-hex>"]` — §8.1's arity above zero. */
        private const val ELEMENTS_NONZERO: Int = 3

        /** `["fee", "<bps>", …]` — the basis points sit second. */
        internal const val BASIS_POINTS_INDEX: Int = 1

        /** `["fee", …, "<recipient-pubkey-hex>"]` — the recipient sits third. */
        internal const val RECIPIENT_INDEX: Int = 2

        /**
         * §8.4 point 1, off a `type=1` T14 decoded.
         *
         * A proposal carrying no `fee` tag produces a sighting whose [term] is `null`, which is
         * §8.1's read rule and **not** a missing operand: the pair for that order is `(0, —)` and
         * every later point is compared against it.
         */
        public fun onProposal(proposal: OrderProposal): FeeTermSighting =
            FeeTermSighting(FeeTermPoint.ORDER_PROPOSAL, feeTag(proposal.encode()))

        /**
         * §8.4 point 2, off the `type=3` §7.6 makes an acceptance out of.
         *
         * @throws SettlementException [SettlementRejection.NOT_AN_ACCEPTANCE] when the update
         *   announces anything else. §8.4 names the point as "the acceptance (`type=3`,
         *   `status=accepted`)", and a `cancelled` update is a message about this order that is
         *   not that point — comparing its terms would invent a divergence out of §7.6's own
         *   rule that only an acceptance repeats them.
         */
        public fun onAcceptance(update: OrderStatusMessage): FeeTermSighting {
            if (update.status != OrderState.ACCEPTED) {
                throw SettlementException(
                    SettlementRejection.NOT_AN_ACCEPTANCE,
                    SettlementVocabulary.STATUS,
                    "§8.4's second point is the acceptance — a `type=3` carrying " +
                        "`[\"${SettlementVocabulary.STATUS}\", " +
                        "\"${OrderState.ACCEPTED.token}\"]` — and this update announces something " +
                        "else. §7.6 requires the four terms be repeated on an acceptance and on " +
                        "nothing else, so reading another status update as this point would " +
                        "compare terms §7.6 never asked for",
                )
            }
            return FeeTermSighting(FeeTermPoint.ACCEPTANCE, feeTag(update.encode()))
        }

        /**
         * §8.4 point 3 or its `payee=provider` OPTIONAL counterpart, off a `type=2` T15 decoded.
         *
         * The point is derived from the request's own `payee` tag rather than taken from the
         * caller: §8.4 gives the two payees different requirements — REQUIRED on the fee request,
         * OPTIONAL on the provider's — and a caller that mislabelled one would move a message
         * between a MUST and a MUST NOT.
         */
        public fun onPaymentRequest(request: PaymentRequest): FeeTermSighting = FeeTermSighting(
            when (request.payee) {
                Payee.FEE -> FeeTermPoint.FEE_PAYMENT_REQUEST
                Payee.PROVIDER -> FeeTermPoint.PROVIDER_PAYMENT_REQUEST
            },
            feeTag(request.encode()),
        )

        /** §8.4 point 4 or its `payee=provider` counterpart, off a `kind:17`. See [onPaymentRequest]. */
        public fun onReceipt(receipt: PaymentReceipt): FeeTermSighting = FeeTermSighting(
            when (receipt.payee) {
                Payee.FEE -> FeeTermPoint.FEE_RECEIPT
                Payee.PROVIDER -> FeeTermPoint.PROVIDER_RECEIPT
            },
            feeTag(receipt.encode()),
        )

        /**
         * A sighting at [point] from a `fee` tag the caller read itself, or `null` for a
         * deliberate absence.
         *
         * The door for §8.4's [FeeTermPoint.BID], which this library has no order-thread codec
         * for — a public `kind:1111` bid is §6's, and the `fee` tag on one is read through the tag
         * layer rather than through this package. A caller using this door owns the parse: §4.4's
         * strict decimal form is what makes the byte comparison below honest, and a tag that never
         * went through it can carry `["fee", "0250", …]` into a comparison T9 would have refused.
         *
         * @throws SettlementException [SettlementRejection.MALFORMED_FEE_TERM] when [feeTag] is
         *   not a `fee` tag at one of §8.1's two legal arities. A comparison over something else
         *   would report a divergence in an element §8.1 does not have.
         */
        public fun of(point: FeeTermPoint, feeTag: List<String>?): FeeTermSighting {
            if (feeTag != null) {
                if (feeTag.firstOrNull() != FEE) {
                    throw SettlementException(
                        SettlementRejection.MALFORMED_FEE_TERM,
                        FEE,
                        "§8.4 compares `$FEE` tags and this sighting was handed a tag of another " +
                            "name; the comparison below is positional, so a tag with another " +
                            "shape would report a divergence in an element §8.1 does not have",
                    )
                }
                if (feeTag.size != ELEMENTS_ZERO && feeTag.size != ELEMENTS_NONZERO) {
                    throw SettlementException(
                        SettlementRejection.MALFORMED_FEE_TERM,
                        FEE,
                        "§8.1 gives `$FEE` exactly two legal arities — $ELEMENTS_ZERO elements at " +
                            "zero basis points, $ELEMENTS_NONZERO above — and this one carries " +
                            "${feeTag.size}",
                    )
                }
            }
            return FeeTermSighting(point, feeTag)
        }

        /**
         * The one `fee` tag [event] carries, or `null`.
         *
         * A second one cannot reach here — §5.3 marks `fee` cardinality `0–1` and §4.3's duplicate
         * rule refuses a second occurrence in T9's codec, which every message these factories take
         * has already been through — and is refused rather than resolved anyway, for §4.3's own
         * reason: taking the first, the last or the smallest lets two implementations disagree
         * about the fee term of the same signed order.
         */
        private fun feeTag(event: WireEvent): List<String>? {
            val found = event.tags.filter { it.firstOrNull() == FEE }
            if (found.size > 1) {
                throw SettlementException(
                    SettlementRejection.DUPLICATE_TAG,
                    FEE,
                    "§5.3 marks `$FEE` cardinality 0–1 and this event carries ${found.size}; §4.3 " +
                        "requires rejecting that rather than resolving it by taking the first, " +
                        "the last or the smallest",
                )
            }
            return found.firstOrNull()
        }
    }
}

/**
 * §8.4's answer for one order: the same `(bps, recipient)` pair everywhere it appeared, or the
 * **first** point at which it did not.
 *
 * ### Why this is a type and never a `Boolean`
 *
 * §8.4: "Any divergence at any point MUST abort the order and MUST be surfaced to the user as a
 * terms mismatch, not as a transient error. An implementation MUST NOT 'take the newest', 'take
 * the smaller', or renegotiate silently." A caller told `false` cannot tell the user *which* point
 * diverged and *which* half of the pair did, which is the difference between "your counterparty
 * re-quoted the fee" and "somebody substituted themselves for the fee recipient" — and a caller
 * told `false` with no point to name is exactly the caller that reaches for one of the three wrong
 * answers §8.4 forbids. `SettlementStructureTest` asserts by reflection that no published member
 * of this package answers the question in a bare `Boolean`.
 *
 * ### First divergence, against the first sighting
 *
 * [across] compares every later sighting against the **first** one in the sequence and stops at
 * the first that differs. The first sighting is the reference because §8.4's own list starts at
 * the order proposal and §8.1's read rule fixes the pair there — "a proposal with no `fee` tag
 * states a zero fee, and the pair for that order is then `(0, —)` for every later check". Nothing
 * here takes the newest, and nothing takes a majority: two points agreeing against a third do not
 * outvote the signed proposal.
 *
 * A consequence worth stating rather than leaving to be discovered: if the caller's sequence does
 * not start at the proposal, the reference is whatever it does start at. That is the caller's
 * decision and not this function's, and it is why [Agreed.points] and [Diverged.points] publish
 * the sequence that was actually compared.
 *
 * ### Unforgeable through the published API
 *
 * The shape `VerifiedPayment` specifies, for the reason it gives: a public `sealed interface`
 * whose implementations are `private` classes nested inside its companion. [Agreed] and [Diverged]
 * are public because a caller has to be able to name what it received, and they are themselves
 * `sealed`, so the JVM's `PermittedSubclasses` attribute is what stops another module minting an
 * [Agreed] for terms nobody compared.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public sealed interface FeeTermAgreement {

    /**
     * The points that were actually **compared**, in the order they were given. Never empty.
     *
     * Not necessarily every point the caller handed in: §8.4 says an implementation "MUST NOT
     * require a `fee` tag" on its three OPTIONAL points, so an optional point carrying none is
     * dropped rather than compared. See [across] for why that is the whole difference between a
     * conformant checker and an over-strict one.
     *
     * Points and never values: §12 item 11 keeps an order's terms out of anything a host
     * application might log, and a diagnostic is the likeliest place for one to end up.
     */
    public val points: List<FeeTermPoint>

    /** §8.4 satisfied: the same pair, byte-identically, at every point compared. */
    public sealed interface Agreed : FeeTermAgreement {

        /**
         * §8.1's `<recipient-pubkey-hex>` from the agreed pair, in §4.3's canonical lowercase, or
         * `null` where the pair is `(0, —)` — a stated `["fee", "0"]` or §8.1's absent tag.
         *
         * Normalised here although the comparison above is over raw bytes, and the two are
         * different questions on purpose. §8.4 asks whether the *spelling* is the same everywhere,
         * and §4.3 requires a pubkey be accepted and normalised; §8.7 then asks whether a seal's
         * key **is** this key, which is a question about the key and not about its spelling. So
         * the comparison is byte-identical and this accessor is canonical, and neither is the
         * other's operand.
         *
         * `null` is a fee term that names nobody, which §8.3 and §8.6 make the case where no fee
         * invoice may exist at all.
         */
        public val recipient: String?
    }

    /**
     * §8.4 violated at [point]: the order MUST be aborted and the user told it is a **terms
     * mismatch** rather than a transient error.
     */
    public sealed interface Diverged : FeeTermAgreement {

        /** The first point whose pair differed from the reference. Never the reference itself. */
        public val point: FeeTermPoint

        /** Which half of §8.1's pair differed there. */
        public val element: FeeTermElement
    }

    public companion object {

        /** §8.4 compares a pair across **several** points, and two is the fewest several can be. */
        private const val COMPARABLE_MINIMUM: Int = 2

        /**
         * §8.4 over an ordered sequence of sightings, reporting the first divergence.
         *
         * ### An OPTIONAL point carrying no `fee` tag is not compared, and that is the rule
         *
         * §8.4 marks three points OPTIONAL — the bid, a `payee=provider` payment request and a
         * `payee=provider` receipt — and states the consequence as a MUST NOT: "An implementation
         * MUST NOT require a `fee` tag on any of the three — but where one **is** present it MUST
         * match." So a sighting at an optional point whose tag is absent is **dropped** rather
         * than treated as a divergence from a proposal that carried one, and §9.2's own worked
         * `kind:17` is the example: it is a provider receipt and "correctly carries no `fee` tag",
         * so a checker that compared it would abort an order over the document's own example.
         *
         * That is the over-strict direction, and it is the one that looks correct: it fails
         * *closed*, it breaks only conformant peers, and every test written from the REQUIRED
         * list alone stays green through it.
         *
         * A REQUIRED point carrying no tag is a different fact and **is** compared. §8.1's read
         * rule is why: a proposal with no `fee` tag states a zero fee and the pair for that order
         * is `(0, —)`, §7.6 makes the acceptance omit `fee` exactly when the proposal omitted it,
         * and a fee `type=2` or fee `kind:17` for such an order may not exist at all. So absence
         * at a required point is a signed statement, and a later point stating something else
         * diverges from it — [FeeTermElement.PRESENCE].
         *
         * @param sightings every point at which a `fee` tag appears — or, at a REQUIRED point,
         *   deliberately does not — for one order, in the order the caller observed them. The
         *   first **compared** one is the reference; see this interface's note.
         * ### Fewer than two points is refused, not answered
         *
         * §8.4 is a statement about a pair appearing at **several** places. A sequence that
         * reduces to one point has nothing to compare against, and the answer an implementation
         * reaches for there is "consistent" — which reports a fee term as agreed on the strength
         * of having seen it once, and lets a message be its own reference. That is the vacuous
         * `true` this whole file exists to make unrepresentable, so it is a refusal.
         *
         * That refusal is **not** on its own enough for a caller performing §9.2 check 6: two
         * points can be the wrong two. [Settlement.verifyFeeReceipt] and
         * [Settlement.checkFeePaymentRequest] additionally require the specific §8.4 points the
         * message under test cannot have existed without, and a client doing check 6 through this
         * function alone has to make that requirement itself.
         *
         * @throws SettlementException [SettlementRejection.FEE_TERM_NOT_COMPARABLE] when fewer
         *   than two points are left to compare. Reachable three ways: an empty sequence; a
         *   sequence of optional points that all carried no tag; and a sequence of one.
         */
        public fun across(sightings: List<FeeTermSighting>): FeeTermAgreement {
            val compared = sightings.filter {
                it.point.requirement == FeeTermRequirement.REQUIRED || it.term != null
            }
            if (compared.size < COMPARABLE_MINIMUM) {
                throw SettlementException(
                    SettlementRejection.FEE_TERM_NOT_COMPARABLE,
                    FeeTermSighting.FEE,
                    "§8.4 requires the same `(bps, recipient)` pair wherever a `fee` tag appears " +
                        "for an order, and this comparison has ${compared.size} point(s) — fewer " +
                        "than the $COMPARABLE_MINIMUM it takes to compare anything. Answering " +
                        "`agreed` here would report a fee term as consistent on the strength of " +
                        "having seen it once, which is a term compared against itself. Note that " +
                        "an OPTIONAL point carrying no `fee` tag is dropped rather than compared, " +
                        "so a sequence of only those arrives here too",
                )
            }
            val points = readOnlyListOf(compared.map { it.point })
            val reference = compared.first()
            for (index in 1 until compared.size) {
                val later = compared[index]
                val element = divergence(reference.term, later.term) ?: continue
                return FirstDivergence(points, later.point, element)
            }
            return SamePairEverywhere(points, recipientOf(reference.term))
        }

        /**
         * Which element of §8.1's pair [reference] and [later] differ in, or `null` if they do not.
         *
         * The comparison is positional over the raw elements, which is what §8.4's
         * "byte-identical" means. Element `0` is the tag name and cannot differ: every sighting
         * either carries a `fee` tag or carries none, which [FeeTermSighting.of] enforces and the
         * decoded factories get for free.
         *
         * The length case is the one worth naming. Two tags whose common prefix is equal and whose
         * lengths differ are §8.1's two arities — `["fee", "0"]` against `["fee", "0", X]` — and
         * that difference *is* the recipient, one side naming nobody. Reporting it as an arity
         * error would tell the user their peer is malformed when what happened is that somebody
         * added a fee recipient to a signed zero-fee term.
         */
        private fun divergence(reference: List<String>?, later: List<String>?): FeeTermElement? = when {
            reference == null && later == null -> null
            reference == null || later == null -> FeeTermElement.PRESENCE
            else -> {
                var found: FeeTermElement? = null
                var index = 0
                val shared = if (reference.size < later.size) reference.size else later.size
                while (index < shared && found == null) {
                    if (reference[index] != later[index]) found = elementAt(index)
                    index++
                }
                found ?: if (reference.size == later.size) null else FeeTermElement.RECIPIENT
            }
        }

        private fun elementAt(index: Int): FeeTermElement =
            if (index == FeeTermSighting.BASIS_POINTS_INDEX) FeeTermElement.BASIS_POINTS
            else FeeTermElement.RECIPIENT

        /**
         * §4.3's canonical form of the agreed pair's recipient, or `null` where it names none.
         *
         * Validated rather than lowercased: `readPubkeyHex` refuses a value that is not exactly 64
         * hex characters, so a term that reached agreement on a recipient this library cannot read
         * as a pubkey is refused here rather than handed to §8.7 as an operand no seal can equal.
         *
         * @throws SettlementException [SettlementRejection.MALFORMED_FEE_TERM] with §4.3's reason
         *   on the cause.
         */
        private fun recipientOf(term: List<String>?): String? {
            if (term == null || term.size <= FeeTermSighting.RECIPIENT_INDEX) return null
            return try {
                readPubkeyHex(term[FeeTermSighting.RECIPIENT_INDEX], "a `fee` recipient")
            } catch (refused: TagException) {
                throw SettlementException(
                    SettlementRejection.MALFORMED_FEE_TERM,
                    FeeTermSighting.FEE,
                    "§8.1's fee recipient is a 64-character lowercase hex x-only public key and " +
                        "§4.3 requires rejecting a value of the wrong length rather than padding " +
                        "or truncating it; §8.7 then compares a seal's pubkey against it, and an " +
                        "operand no key can equal would refuse every fee invoice for this order",
                    refused,
                )
            }
        }

        /** §8.4 satisfied. Private, so the only door in is [across]. */
        private class SamePairEverywhere(
            override val points: List<FeeTermPoint>,
            override val recipient: String?,
        ) : Agreed {

            /** Names the points and never the pair: this value holds a counterparty pubkey. */
            override fun toString(): String =
                "FeeTermAgreement.Agreed(points=${points.map { it.name }}, " +
                    "recipient=${if (recipient == null) "none" else "redacted"})"
        }

        /** §8.4's terms mismatch. Private, for the same reason. */
        private class FirstDivergence(
            override val points: List<FeeTermPoint>,
            override val point: FeeTermPoint,
            override val element: FeeTermElement,
        ) : Diverged {

            /** Two vocabulary tokens and a list of points; no value a counterparty chose. */
            override fun toString(): String =
                "FeeTermAgreement.Diverged(point=${point.name}, element=${element.name}, " +
                    "points=${points.map { it.name }})"
        }
    }
}
