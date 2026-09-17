package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.MoneyRejection
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.readStrictDecimal
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §7.6's four terms as the **raw tag elements** the rumor carried, which is what "byte-identical"
 * means and what only this package holds.
 *
 * ### Why the comparison is here rather than on `OrderTerms`
 *
 * §7.6 fixes the terms an acceptance must repeat as `item`, `amount_msat`, `fee` and `deliver_by`,
 * and says they must be **byte-identical**. `OrderTerms.namesTheSameDealAs` compares *parsed
 * values*, and its own KDoc records the gap: `["amount_msat", "090000000"]` and
 * `["amount_msat", "90000000"]` are not byte-identical and would compare equal there. That KDoc
 * also says how two of the four are closed — on parse, by §4.3's canonical-decimal rule — and this
 * type is the other half, for the two that are not and must not become so.
 *
 * `deliver_by` is a timestamp, and `TagValues.readTimestamp` deliberately accepts a leading zero on
 * the stated grounds that §4.3 requires only "a non-negative decimal integer" and that refusing
 * `01757066034` would make a conformant peer look broken. `item` is a coordinate whose `d` value is
 * opaque. Neither may be tightened to close §7.6, so §7.6 is closed where it was written — over the
 * bytes — and `["deliver_by", "1757066034"]` against `["deliver_by", "01757066034"]` is a
 * **counter-proposal** rather than an acceptance.
 *
 * Each field is the whole tag array or `null` where the rumor carried none, so an absent `fee`
 * (§8.1's zero-fee proposal) and a stated `["fee", "0"]` are different values here, exactly as
 * `FeeTerm.Absent` and `FeeTerm.Stated(0)` are one layer down. Duplicates cannot reach this type:
 * §4.3's duplicate rule refuses a second `item`, `fee` or `deliver_by` in the tag codec, and
 * [ChannelTerms.single] refuses a second `amount_msat` here.
 *
 * Internal because it is the *shape* of a comparison rather than a term a caller reads: the terms
 * themselves are published as [OrderProposal.terms] and [OrderProposal.item], and what a caller
 * needs from a failed comparison is which tags diverged, which [Acceptance.CounterProposal] names.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
internal class SignedTerms(
    private val item: List<String>?,
    private val amountMsat: List<String>?,
    private val fee: List<String>?,
    private val deliverBy: List<String>?,
) {

    /**
     * The §7.6 term names on which [other] is not byte-identical to this, in §7.6's own order.
     *
     * Empty is the acceptance case. Tag **names** only, never values: §12 item 11 keeps an order's
     * price, coordinate and deadlines out of anything a host application might log.
     */
    internal fun divergenceFrom(other: SignedTerms): List<String> {
        val out = mutableListOf<String>()
        if (item != other.item) out += ChannelVocabulary.ITEM
        if (amountMsat != other.amountMsat) out += ChannelVocabulary.AMOUNT_MSAT
        if (fee != other.fee) out += ChannelVocabulary.FEE
        if (deliverBy != other.deliverBy) out += ChannelVocabulary.DELIVER_BY
        return out
    }

    /** Names the four presences and no value at all. §12 item 11. */
    override fun toString(): String =
        "SignedTerms(item=${item != null}, amount_msat=${amountMsat != null}, " +
            "fee=${fee != null}, deliver_by=${deliverBy != null})"
}

/**
 * The tag reads §7.5 and §7.6 share, in one place so the proposal and the acceptance cannot come to
 * read the same tag two different ways.
 *
 * Nothing here is a second copy of §5.3's Encoding column: `item`, `fee`, `expiration`,
 * `deliver_by` and `status` are all decoded by T9 under the context T13 declared, and what is left
 * for this object is §7.5's own two tags — `amount_msat` and `amount`, which §5.3's table does not
 * carry — plus §4.3's duplicate rule over them.
 */
internal object ChannelTerms {

    /** A tag carries a name and a value; anything shorter carries no value at all. */
    private const val VALUE_ELEMENTS: Int = 2

    /** The value sits second, after the name. */
    private const val VALUE_INDEX: Int = 1

    /**
     * §4.4's supply cap is 19 digits, so a canonical value longer than that is above supply rather
     * than unreadable — and saying so is the difference between "your peer asked for more bitcoin
     * than exists" and "your peer is broken".
     */
    private val SUPPLY_CAP_DIGITS: Int = Msat.SUPPLY_CAP_MSAT.toString().length

    /**
     * The one occurrence of [name] on [rumor], or `null`, refusing a second one.
     *
     * §5.3's table carries neither `amount_msat` nor `amount`, so §4.3's duplicate rule — stated
     * over "any tag **this document** marks with cardinality `1` or `0–1`" — does not literally
     * reach them and the tag codec does not check them. Its rationale reaches them unchanged and is
     * the sharpest it gets anywhere in this library: two implementations resolving two
     * `amount_msat` tags by first and by last disagree about the price of a signed order.
     */
    internal fun single(rumor: AttributedRumor.Bound, name: String): List<String>? {
        val found = rumor.tags.occurrences(name)
        if (found.size > 1) {
            throw ChannelException(
                ChannelRejection.DUPLICATE_TAG,
                name,
                "§7.5 fixes what `$name` carries on an order message and this event carries " +
                    "${found.size} of them; §4.3's rationale applies unchanged — resolving it by " +
                    "first, last or smallest would let two implementations disagree about the " +
                    "price of the same signed order",
            )
        }
        return found.firstOrNull()
    }

    /** §7.6's four terms, verbatim, off a rumor whose duplicates have already been refused. */
    internal fun signedTerms(rumor: AttributedRumor.Bound): SignedTerms = SignedTerms(
        item = rumor.tags.occurrences(ChannelVocabulary.ITEM).firstOrNull(),
        amountMsat = single(rumor, ChannelVocabulary.AMOUNT_MSAT),
        fee = rumor.tags.occurrences(ChannelVocabulary.FEE).firstOrNull(),
        deliverBy = rumor.tags.occurrences(ChannelVocabulary.DELIVER_BY).firstOrNull(),
    )

    /**
     * §7.5's `amount_msat`, in §4.4's strict canonical decimal form and bounded by §4.4's supply
     * cap.
     *
     * Strict, not permissive, and the reason is §7.6 rather than tidiness: the acceptance
     * comparison is over bytes, so a proposal whose price was read permissively could be
     * "accepted" by a message carrying a different spelling of it. §4.3 requires rejecting a
     * malformed value rather than repairing it into the nearest legal one.
     */
    internal fun amountMsat(rumor: AttributedRumor.Bound): Msat {
        val tag = single(rumor, ChannelVocabulary.AMOUNT_MSAT)
            ?: throw missingProposalTag(ChannelVocabulary.AMOUNT_MSAT)
        if (tag.size < VALUE_ELEMENTS) throw missingProposalTag(ChannelVocabulary.AMOUNT_MSAT)
        val millisatoshis = decimal(tag[VALUE_INDEX], ChannelVocabulary.AMOUNT_MSAT)
        return try {
            Msat.ofMsat(millisatoshis)
        } catch (refused: MoneyException) {
            throw refused.asChannelRejection(ChannelVocabulary.AMOUNT_MSAT)
        }
    }

    /**
     * §7.5's cross-check: "If both are present and `amount × 1000 ≠ amount_msat`, the
     * implementation MUST reject the message. It MUST NOT prefer one and continue."
     *
     * The multiply goes through T1's `Msat.ofSat` and **not** through a bare `Long` multiply, which
     * is the whole reason T1 exists. `amount` is a satoshi count a stranger chose; `× 1000` wraps
     * silently in 64 bits, and because 1000 does not divide 2⁶⁴ evenly a crafted value can be
     * congruent to a legitimate `amount_msat` modulo 2⁶⁴ and compare **equal** — walking through
     * the one clause §7.5 wrote to stop a 1000× disagreement, with the wrapped product positive and
     * plausible. `ofSat` bounds the operand against §4.4's cap *before* multiplying, so no such
     * value is ever multiplied at all.
     *
     * Absent entirely is legal and common: §7.5 makes the tag a MAY, and an implementation that
     * required it would refuse conformant peers.
     */
    internal fun checkAmountAgreement(rumor: AttributedRumor.Bound, price: Msat) {
        val tag = single(rumor, ChannelVocabulary.AMOUNT) ?: return
        if (tag.size < VALUE_ELEMENTS) {
            throw ChannelException(
                ChannelRejection.MALFORMED_AMOUNT,
                ChannelVocabulary.AMOUNT,
                "§7.5 encodes the GammaMarkets compatibility tag as " +
                    "`[\"${ChannelVocabulary.AMOUNT}\", \"<sats>\"]`; this one carries " +
                    "${tag.size} element(s)",
            )
        }
        val satoshis = decimal(tag[VALUE_INDEX], ChannelVocabulary.AMOUNT)
        val stated = try {
            Msat.ofSat(satoshis)
        } catch (refused: MoneyException) {
            throw refused.asChannelRejection(ChannelVocabulary.AMOUNT)
        }
        if (stated != price) {
            throw ChannelException(
                ChannelRejection.AMOUNT_DISAGREEMENT,
                ChannelVocabulary.AMOUNT,
                "§7.5: `${ChannelVocabulary.AMOUNT}` and `${ChannelVocabulary.AMOUNT_MSAT}` are " +
                    "both present and disagree by a factor this implementation MUST NOT resolve — " +
                    "§7.5 says the message MUST be rejected and that an implementation MUST NOT " +
                    "prefer one and continue",
            )
        }
    }

    /**
     * §7.5's ordering rule, checked **before** [OrderTerms] is built.
     *
     * §7.5: when both are present `expiration` MUST be strictly earlier than `deliver_by`, and an
     * implementation MUST reject a proposal that violates it. `OrderTerms`'s own `init` refuses the
     * same shape with an `OrderStateException`; checking here first means the caller's answer is
     * this package's named [ChannelRejection.DEADLINES_INVERTED] and never an exception from a
     * package it did not call. The construction is validated, never wrapped in a `try`.
     *
     * An implementation written from memory writes `<=` where §7.5 says *strictly* earlier, so the
     * equal case is refused here and is its own control.
     */
    internal fun checkDeadlines(expiration: Long?, deliverBy: Long?) {
        if (expiration == null || deliverBy == null) return
        if (expiration >= deliverBy) {
            throw ChannelException(
                ChannelRejection.DEADLINES_INVERTED,
                ChannelVocabulary.EXPIRATION,
                "§7.5 requires a proposal's `${ChannelVocabulary.EXPIRATION}` to fall **strictly** " +
                    "before its `${ChannelVocabulary.DELIVER_BY}` and requires rejecting a " +
                    "proposal that violates it: an order that can still be accepted after its own " +
                    "delivery deadline has passed puts the provider in a state where acceptance " +
                    "and expiry are simultaneously correct",
            )
        }
    }

    /** §7.4's `type` discriminator, as the door this decoder is written for. */
    internal fun requireType(
        rumor: AttributedRumor.Bound,
        expected: OrderMessageKind,
        reason: ChannelRejection,
    ) {
        if (rumor.type == expected) return
        throw ChannelException(
            reason,
            ChannelVocabulary.TYPE,
            "this decoder reads §7.4's `${ChannelVocabulary.TYPE}=${expected.type}` on a " +
                "kind:${RumorKind.ORDER_MESSAGE.kind}; the rumor it was handed is a " +
                "kind:${rumor.kind.kind} ${rumor.type?.name ?: "rumor carrying no type"}. A " +
                "well-formed message of another kind is not a malformed one",
        )
    }

    /** §7.5 marks [tag] REQUIRED on a `type=1` and the event carries no readable one. */
    internal fun missingProposalTag(tag: String): ChannelException = ChannelException(
        ChannelRejection.MISSING_REQUIRED_TAG,
        tag,
        "§7.5 marks `$tag` REQUIRED on a kind:${RumorKind.ORDER_MESSAGE.kind} " +
            "`${ChannelVocabulary.TYPE}=${OrderMessageKind.PROPOSAL.type}` and this event carries " +
            "no readable one",
    )

    private fun decimal(text: String, name: String): Long = try {
        readStrictDecimal(
            text,
            "a `$name` value",
            TagRejection.MALFORMED_NUMBER,
            maxDigits = SUPPLY_CAP_DIGITS,
            tooLargeReason = TagRejection.ABOVE_SUPPLY,
        )
    } catch (refused: TagException) {
        throw ChannelException(
            if (refused.reason == TagRejection.ABOVE_SUPPLY) ChannelRejection.AMOUNT_ABOVE_SUPPLY
            else ChannelRejection.MALFORMED_AMOUNT,
            name,
            "§4.3 and §4.4 fix a `$name` value as canonical decimal — no sign, no separators, no " +
                "leading zeros — and §7.6 compares it byte-identically, so a value read " +
                "permissively here is a counter-proposal an implementation would call an acceptance",
            refused,
        )
    }

    /**
     * T1's refusals, mapped onto this package's vocabulary with the original kept on the cause.
     *
     * A `when` over the enum rather than an `if`, for the reason `TagException.asChannelRejection`
     * gives: a constant added to [MoneyRejection] later must not be able to fall through silently
     * onto "above supply", which is the one answer here that sounds like a fact about bitcoin.
     */
    private fun MoneyException.asChannelRejection(name: String): ChannelException = ChannelException(
        when (reason) {
            MoneyRejection.ABOVE_SUPPLY -> ChannelRejection.AMOUNT_ABOVE_SUPPLY
            MoneyRejection.NEGATIVE,
            MoneyRejection.SUB_MILLISATOSHI,
            MoneyRejection.NOT_WHOLE_SATOSHIS,
            MoneyRejection.BPS_ABOVE_MAXIMUM,
            MoneyRejection.MALFORMED,
            MoneyRejection.OVERFLOW,
            -> ChannelRejection.MALFORMED_AMOUNT
        },
        name,
        "§4.4 bounds every amount read off the wire by the total bitcoin supply and forbids a " +
            "negative or floating-point one; this `$name` is outside that",
        this,
    )
}

/**
 * §7.5's `kind:16` `type=1` order proposal, decoded off a §7.2-attributed rumor into the terms
 * §11.2's transition function already consumes.
 *
 * ### What this closes
 *
 * Before this type, `OrderTerms` was built by hand in tests and by nobody else: the `item`
 * coordinate, `amount_msat`, the `fee` pair and the two deadlines all arrived as typed values a
 * caller was trusted to get right, so the money path's inputs had never been read off an event.
 * [decode] is the parser, and [asOrderEvent] hands `OrderMachine.open` the genesis event §11.2's
 * first row has no from-state for.
 *
 * ### Which layer enforces which rule, said out loud
 *
 * - **§4.1 and §7.2** are T8's and T13's: the id was recomputed and the seal's pubkey equals the
 *   rumor's before an `AttributedRumor.Bound` exists at all.
 * - **§7.4's envelope** is T13's: the required `nenya`, `p` and `order` tags, the `type`
 *   discriminator in canonical decimal, and §4.3's duplicate rule over `order` and `type`.
 * - **§5.3's Encoding column** is T9's, applied under the `type=1` context T13 declared — which is
 *   where §5.3's `item` row's "exactly one on a `kind:16` `type=1`" is enforced
 *   (`TagContext.requiresItem`), and where the quantity is held to the string `"1"`, and where a
 *   second `fee` tag and a `["fee", "0250", …]` are both refused.
 * - **§7.5's own rules** are here: `amount_msat`, the `amount` cross-check, and the two deadlines.
 *
 * Nothing in [dev.eryalabs.nenya.order] is touched. T7's types are consumed as they are.
 *
 * ### One stated narrowing: this type does not know who the provider is
 *
 * §7.6 says an acceptance is a `type=3` "from the provider". §7.2 gives this library the key the
 * message was **sealed** by, and nothing here can establish that that key is the provider's: the
 * proposal's `p` tag is a counterparty pubkey the *buyer* wrote, and §5.3 gives `p` cardinality
 * `0–n`. So [accepts] compares the terms and the order id, and the caller compares
 * [OrderStatusMessage.sender] against the key it knows to be the provider's — the same division
 * `Party` records one package over, where the resolution is the layer above's and never a claim
 * this library verified. The obligation is stated here rather than silently absorbed.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class OrderProposal internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this proposal mints the order under. Never `null` on a `type=1`. */
    public val order: OrderId,

    /**
     * §7.5's `["item", "<coordinate>", "1"]` — the listing this proposal derives from.
     *
     * REQUIRED and not optional, and §7.5 says why: the coordinate "is how the proposal names the
     * listing it derives from". The quantity is the string `"1"` and `ItemRef` refuses any other.
     * §5.6's "the referenced listing MUST NOT be expired at the time of proposal" needs the listing
     * and a clock, and is the caller's: nothing here reads a clock.
     */
    public val item: ItemRef,

    /** §8.3's arithmetic and §7.5's two deadlines, in the shape §11.2's transitions read. */
    public val terms: OrderTerms,
    internal val signedTerms: SignedTerms,
) {

    /** §7.2's attribution: the key the seal carrying this proposal was signed by, lowercase. */
    public val buyer: String get() = rumor.author

    /** §7.5's `amount_msat` — the price, what the **provider receives** (§8.2). */
    public val price: Msat get() = terms.split.price

    /**
     * §8.1's `fee` recipient, the third element `FeeTerm` deliberately does not hold, or `null`
     * when the term states zero basis points or there is no term at all.
     *
     * Published because §8.4 requires the fee **pair** be byte-identical at all four points it
     * appears and `OrderTerms.namesTheSameDealAs` compares only the basis points; a caller holding
     * this can make the comparison §8.4 asks for. [accepts] makes it over the raw tag instead,
     * which reaches both elements at once.
     */
    public val feeRecipient: String? get() = rumor.tags.feeRecipient

    /** §7.4's `["p", ...]` counterparties, as §5.3's Encoding column decoded them. */
    public val counterparties: List<PubkeyRef> get() = rumor.counterparties

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6); no deadline here is evaluated against it. */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /**
     * §11.2's genesis event, for `OrderMachine.open`.
     *
     * The [order] id travels with the terms, so the order the machine opens is bound to the
     * `["order", ...]` tag that was on the wire rather than to one a caller supplied beside the
     * terms — which is what lets §11.2's `awaiting_payment → paid` refuse a receipt that settles
     * somebody else's order.
     *
     * The `created_at` rides along because `OrderEvent.Rumor` carries one so that §4.6 has a
     * subject; `OrderMachine` reads it nowhere, which is the point of the field.
     */
    public fun asOrderEvent(): OrderEvent.Proposal = OrderEvent.Proposal(order, terms, createdAt)

    /**
     * §7.6, whole: is [update] an acceptance of *these* terms, a counter-proposal, or neither?
     *
     * The answer is a sealed type and never a `Boolean` or a status-shaped `String`, and
     * `ChannelStructureTest` asserts that by reflection. §7.6 names three outcomes and they carry
     * three different correct responses — settle in, re-propose with a new order id, or ignore —
     * and a caller told `false` cannot tell the second from the third.
     *
     * "Silence, a `kind:14` chat message, a public event" are the other three things §7.6 says MUST
     * NOT be treated as acceptance, and they are unrepresentable rather than tested: this function
     * takes an [OrderStatusMessage], which only [OrderStatusMessage.decode] mints and only from a
     * `kind:16` `type=3`, so there is no value of any of those shapes to hand it.
     *
     * @throws ChannelException [ChannelRejection.DIFFERENT_ORDER] if [update] names another order.
     *   Not a counter-proposal: see that constant.
     */
    public fun accepts(update: OrderStatusMessage): Acceptance {
        if (update.order != order) {
            throw ChannelException(
                ChannelRejection.DIFFERENT_ORDER,
                ChannelVocabulary.ORDER,
                "§7.6 fixes an acceptance as a status update carrying **the same** " +
                    "`${ChannelVocabulary.ORDER}` id; this one names a different order and is not " +
                    "about this one at all",
            )
        }
        if (update.status != OrderState.ACCEPTED) return Acceptance.NotAnAcceptance(update.status)
        val divergent = signedTerms.divergenceFrom(update.signedTerms)
        return if (divergent.isEmpty()) Acceptance.Accepted(order, terms)
        else Acceptance.CounterProposal(readOnlyListOf(divergent))
    }

    /**
     * Names nothing at all.
     *
     * §12 item 1 keeps an order's price out of anything public, §12 item 2 the counterparty pubkey
     * and §12 item 11 the order id and the listing coordinate. This type holds every one of them.
     */
    override fun toString(): String = "OrderProposal(redacted)"

    public companion object {

        /**
         * §7.5's proposal, decoded.
         *
         * Takes an [AttributedRumor.Bound] rather than a `CheckedEvent`: §4.1 requires the id check
         * before any other processing and §7.2 requires the pubkey-equality check before a term is
         * read, and the way to enforce an ordering is to make the later step take a type only the
         * earlier steps can produce. This is that later step, exactly as T13 is T8's.
         *
         * @throws ChannelException naming which §7.5, §7.4 or §4.3 rule refused the proposal, and
         *   the tag it was about. A tag-layer [dev.eryalabs.nenya.tag.TagException] refusal is
         *   preserved on the cause.
         */
        public fun decode(rumor: AttributedRumor.Bound): OrderProposal {
            ChannelTerms.requireType(rumor, OrderMessageKind.PROPOSAL, ChannelRejection.NOT_A_PROPOSAL)
            // Both of these are already enforced one layer down — §7.4's `order` requirement by
            // T13, §5.3's "exactly one `item` on a type=1" by T9 under the context T13 declared —
            // so neither elvis can fire today. They are written rather than asserted away because
            // a `!!` here would turn a future loosening of either rule into a crash in a decoder
            // whose whole job is to refuse hostile input by name.
            val order = rumor.order ?: throw ChannelTerms.missingProposalTag(ChannelVocabulary.ORDER)
            val item = rumor.tags.item ?: throw ChannelTerms.missingProposalTag(ChannelVocabulary.ITEM)

            val price = ChannelTerms.amountMsat(rumor)
            ChannelTerms.checkAmountAgreement(rumor, price)

            val expiration = rumor.tags.expiration
            val deliverBy = rumor.tags.deliverBy
            ChannelTerms.checkDeadlines(expiration, deliverBy)

            // §8.1's read rule, carried by T9: no `fee` tag is `FeeTerm.Absent`, which is zero fee
            // and MUST NOT be refused as incomplete terms — and is a different value from a stated
            // `["fee", "0"]`, because §8.4 requires the pair `(0, —)` be reproducible as what it
            // was. Validated above, never wrapped in a `try`: STOP RULE 1.
            val terms = OrderTerms.of(price, rumor.tags.fee, expiration, deliverBy)
            return OrderProposal(rumor, order, item, terms, ChannelTerms.signedTerms(rumor))
        }

        /**
         * The tags REQUIRED on a `type=1`: §7.4's three, plus §7.5's own three.
         *
         * Published because `ProposalCodecTest` walks it, removing each in turn and asserting the
         * refusal names *that* tag — a proof over a list rather than over hand-written controls
         * that could silently stop covering a seventh. Derived from [AttributedRumor.requiredTags],
         * which the tests hold equal to §7.4's fenced block parsed out of the document, so the
         * envelope half of this list is anchored to the specification rather than to this file.
         */
        public fun requiredTags(): List<String> = REQUIRED

        /**
         * Every tag this codec requires or accepts as a **term** on a `type=1`.
         *
         * Held against §7.5's own worked JSON example, parsed out of `spec/NENYA-1.md` at test
         * time: this set minus `amount` and `subject` must equal the nine tag names the example
         * prints. Those two are documented extras rather than an excuse — `amount` is §7.5's
         * GammaMarkets MAY, which the example does not print and which this codec must accept, and
         * `subject` is §7.4's display-only MAY that MUST round-trip on any rumor — and each is
         * named in the test with the clause it comes from. A §7.5 revision that adds or drops a
         * term turns the suite red rather than leaving this codec quietly stale.
         *
         * `price`, `title`, `alt` and the rest of §5.3's listing vocabulary are deliberately absent.
         * §5.3's Encoding column still decodes them if a peer sends one and §4.3 still round-trips
         * them, but none is a **term** of an order: §7.5 names the terms and this is that list.
         */
        public fun readableTags(): List<String> = READABLE

        private val REQUIRED: List<String> = readOnlyListOf(
            AttributedRumor.requiredTags() + listOf(
                ChannelVocabulary.TYPE,
                ChannelVocabulary.ITEM,
                ChannelVocabulary.AMOUNT_MSAT,
            ),
        )

        private val READABLE: List<String> = readOnlyListOf(
            REQUIRED + listOf(
                ChannelVocabulary.AMOUNT,
                ChannelVocabulary.FEE,
                ChannelVocabulary.DELIVER_BY,
                ChannelVocabulary.EXPIRATION,
                ChannelVocabulary.SUBJECT,
            ),
        )
    }
}

/**
 * §7.4's `kind:16` `type=3` status update, decoded — the message §7.6 makes acceptance out of.
 *
 * ### §11.1's conflation, from the one direction only this package can reach
 *
 * A `type=3` carries a `["status", ...]` tag whose vocabulary is §11.1's **order** vocabulary,
 * while the identically-named §5.3 tag on a listing carries §5.2's. §11.1 says one codec MUST NOT
 * serve both, and names the reason: they share exactly one token, `cancelled`, with different
 * meanings — a cancelled listing is withdrawn from the board, a cancelled order ended before
 * `paid`. That is the token where the mistake looks correct and passes a careless test.
 *
 * T6 closed the direction it could reach and T10 the listing one; this is the third face, and the
 * one where the tag name, the encoding and the shape are all identical and only the vocabulary
 * differs. [status] is read by [OrderStatusCodec] and by nothing else, so `sold`, `awarded` and
 * `fulfilled` land in [OrderState.UNKNOWN] — not in a listing state, which is not even assignable
 * to this field.
 *
 * ### The status tag is REQUIRED here and is never defaulted
 *
 * §5.2's "a listing with no `status` is active" is a **listing** rule. Applying it to a `type=3`
 * would invent an order transition out of a missing tag, so a status update carrying no readable
 * `status` is [ChannelRejection.MISSING_REQUIRED_TAG] and produces no value at all.
 *
 * ### `amount` is deliberately not read here, and the consequence is stated rather than absorbed
 *
 * §7.5's `amount × 1000 = amount_msat` cross-check is written about a **proposal**, and §7.6's four
 * byte-identical terms are `item`, `amount_msat`, `fee` and `deliver_by` — `amount` is not among
 * them. So a `type=3` carrying a GammaMarkets `["amount", …]` that disagrees with its own
 * `amount_msat` is not refused here: refusing it would be this library inventing a rule the document
 * does not state, and comparing it as a fifth term would make every conformant provider that omits
 * the compatibility tag a counter-proposal.
 *
 * What that costs is worth naming: a peer reading the same signed acceptance through the
 * GammaMarkets tag would see a different price. The tag is not read as a price anywhere in this
 * library, and §8.3's arithmetic runs on the proposal's `amount_msat` alone, so nothing here can be
 * moved by it. Closing it properly is a §7.6 revision and not a codec's decision to make (STOP
 * RULE 10).
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class OrderStatusMessage internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this update claims to be about. Never `null` on a `type=3`. */
    public val order: OrderId,

    /**
     * The state this update **claims**, as §11.1's codec read it. A claim, never a fact.
     *
     * An [OrderState] and not a `String`: a status-shaped string reaching a decision is the shape
     * STOP RULE 12 exists to prevent, and `OrderEvent.StatusUpdate` takes the same type for the
     * same reason.
     */
    public val status: OrderState,
    internal val signedTerms: SignedTerms,
) {

    /**
     * §7.2's attribution: the key the seal carrying this update was signed by, lowercase.
     *
     * Whether that key is the provider's is the caller's resolution and not this library's claim —
     * see [OrderProposal]'s note on the narrowing.
     */
    public val sender: String get() = rumor.author

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6). */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /** Names the claimed state, which is a vocabulary token, and no identifier of any kind. */
    override fun toString(): String = "OrderStatusMessage(status=${status.name})"

    public companion object {

        /**
         * A `type=3`, decoded.
         *
         * @throws ChannelException [ChannelRejection.NOT_A_STATUS_UPDATE] for any other message,
         *   or [ChannelRejection.MISSING_REQUIRED_TAG] naming `status` when it carries none.
         */
        public fun decode(rumor: AttributedRumor.Bound): OrderStatusMessage {
            ChannelTerms.requireType(
                rumor,
                OrderMessageKind.STATUS_UPDATE,
                ChannelRejection.NOT_A_STATUS_UPDATE,
            )
            val order = rumor.order ?: throw ChannelException(
                ChannelRejection.MISSING_REQUIRED_TAG,
                ChannelVocabulary.ORDER,
                "§7.4 requires an `${ChannelVocabulary.ORDER}` tag on every `kind:16` but a " +
                    "`${ChannelVocabulary.TYPE}=${OrderMessageKind.PRIVATE_BID.type}`",
            )
            val token = rumor.tags.status ?: throw ChannelException(
                ChannelRejection.MISSING_REQUIRED_TAG,
                ChannelVocabulary.STATUS,
                "§7.4 says a `${ChannelVocabulary.TYPE}=${OrderMessageKind.STATUS_UPDATE.type}` " +
                    "carries a `${ChannelVocabulary.STATUS}` tag from §11.1 and this event carries " +
                    "none; §5.2's \"absent means active\" is a listing rule, and applying it here " +
                    "would invent an order transition out of a missing tag",
            )
            return OrderStatusMessage(
                rumor,
                order,
                OrderStatusCodec.read(token),
                ChannelTerms.signedTerms(rumor),
            )
        }
    }
}

/**
 * §7.6's answer to "were these terms accepted", as a type rather than as a flag.
 *
 * §7.6 names three outcomes for a `type=3` offered against a proposal and gives each a different
 * correct response:
 *
 * - [Accepted] — the status is `accepted` and all four terms are byte-identical. The order advances.
 * - [CounterProposal] — the status is `accepted` and a term differs. §7.6: "An acceptance carrying
 *   different terms is a **counter-proposal**, and the correct response is a new `type=1` from the
 *   buyer, with a new order id." Reporting it as malformed sends the caller to do the wrong thing.
 * - [NotAnAcceptance] — the status is something else entirely. It may still move the order (§11.2
 *   accepts `cancelled` from either party before `paid`), and that is the state machine's decision
 *   rather than this codec's.
 *
 * A `Boolean` collapses the second and the third, which is exactly the loss §7.6's wording exists to
 * prevent; a status-shaped `String` hands a decision back as text that has already been decoded once
 * (STOP RULE 12, §11.1). `ChannelStructureTest` asserts by reflection that no member of this package
 * answers the question in either shape.
 *
 * A public `sealed interface` whose implementations carry **internal** constructors, so no Kotlin
 * caller can mint one and the only values are the ones [OrderProposal.accepts] produced by comparing
 * bytes. That is one notch weaker than the shape `AttributedRumor` uses and its KDoc argues for —
 * private nested classes — because an `internal` constructor is still reachable from Java on the
 * same classloader, and saying so is the point: the cases are published types a caller pattern-matches
 * on, which private constructors would not allow, so what is claimed here is exactly "unforgeable
 * through the Kotlin API" and not "unforgeable".
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public sealed interface Acceptance {

    /**
     * §7.6 satisfied: `["status", "accepted"]` and `item`, `amount_msat`, `fee` and `deliver_by`
     * byte-identical to the proposal's.
     */
    public class Accepted internal constructor(

        /** The order both messages name. */
        public val order: OrderId,

        /** The proposal's terms, which are now the **accepted** terms §8.4 and §11.2 read. */
        public val terms: OrderTerms,
    ) : Acceptance {

        /** Names the outcome and nothing else: this value holds an order id (§12 item 11). */
        override fun toString(): String = "Acceptance.Accepted(redacted)"
    }

    /**
     * §7.6's counter-proposal: `["status", "accepted"]` carrying at least one different term.
     *
     * A message about this order, and a legitimate one — the correct response is a new `type=1`
     * with a new order id — so it is reported as what it is rather than as malformed.
     *
     * An update carrying **no** terms at all lands here too, and deliberately: §7.6 requires the
     * four terms be byte-identical, an absent term is not identical to a present one, and an
     * acceptance that agrees to nothing is not an acceptance. `OrderEvent.StatusUpdate` records the
     * same reading one package over — "an acceptance carrying no terms is therefore not an
     * acceptance either, and is refused rather than assumed to agree".
     */
    public class CounterProposal internal constructor(

        /**
         * Which of §7.6's four terms differ, by tag **name**, in §7.6's order.
         *
         * Names and never values: §12 item 11 keeps an order's price, coordinate and deadlines out
         * of anything a host application might log, and a diagnostic is the likeliest place for one
         * to end up. Never empty — an empty divergence is [Accepted].
         */
        public val divergentTerms: List<String>,
    ) : Acceptance {

        /** Tag names only, which is what [divergentTerms] holds and why it holds only those. */
        override fun toString(): String = "Acceptance.CounterProposal(differing=$divergentTerms)"
    }

    /**
     * The update's status is not `accepted`, so §7.6 does not reach it at all.
     *
     * Its own case rather than a counter-proposal: a `["status", "cancelled"]` is §11.2's
     * cancellation from either party, and an unrecognised token is [OrderState.UNKNOWN], which
     * §11.1 says MUST NOT be mapped onto the nearest known state. Neither is a counter-offer to
     * re-propose against.
     */
    public class NotAnAcceptance internal constructor(

        /** What the update claimed instead, as §11.1's codec read it. A claim, never a fact. */
        public val status: OrderState,
    ) : Acceptance {

        /** A §11.1 vocabulary token's constant name, which is not a value a stranger chose. */
        override fun toString(): String = "Acceptance.NotAnAcceptance(status=${status.name})"
    }
}
