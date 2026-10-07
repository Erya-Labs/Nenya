package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStateException
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.tag.asTagRejection
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireException

/**
 * §7.4's envelope — the fields every rumor carries whatever its kind — as the values a client
 * authors, before any rule has been applied to them.
 *
 * ### A carrier, not a check
 *
 * The same division [dev.eryalabs.nenya.listing.AuthoredListing] draws, and for the same reason:
 * [RumorWriter] refuses **by value**, and a carrier that threw would put half the refusals behind a
 * `try` the caller did not ask for. So this type holds whatever it was handed and the writer is the
 * only place a rule lives.
 *
 * ### There is no `subject` parameter, and that is §7.4's rule rather than an omission
 *
 * §7.4: "A `subject` tag (NIP-17) MAY be present on any rumor. It is display metadata only; an
 * implementation MUST NOT derive any term or state from it." A parameter for it would be a member
 * of this package named after a subject, which is the one shape `ChannelStructureTest` forbids
 * outright — the published surface of this package carries no door a term could be read out of a
 * `subject` through, in either direction. A client that wants one puts it in [extraTags], where
 * §4.3's verbatim-preservation rule carries it to the wire and back without this library ever
 * decoding it. That is strictly what §7.4 asks for: the tag round-trips and yields no term.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class RumorEnvelope(

    /**
     * The author's x-only public key, 64 hex characters (§4.3) — the key §7.2 will compare the
     * seal's against once the app has sealed this rumor.
     *
     * **Normalised to lowercase on the way out**, which is §4.3's rule for an event this client
     * authors. That is the opposite of what [AttributedRumor.encode] does to somebody else's rumor,
     * and for the reason stated there: rewriting a stranger's bytes changes their event's id and
     * detaches the seal that carried it.
     */
    public val authorPubkey: String,

    /** §4.1's `created_at`, in Unix seconds. The app's clock, never this library's (§4.6). */
    public val createdAt: Long,

    /**
     * §7.4's `["p", ...]` counterparties, in order.
     *
     * §7.4 marks `p` MUST on a `kind:15`, `kind:16` and `kind:17`, so a rumor of those kinds
     * carrying none is refused rather than emitted. A `kind:14` is outside that rule and may carry
     * none.
     */
    public val counterparties: List<PubkeyRef> = emptyList(),

    /**
     * §4.1's `content`, verbatim and carrying no machine meaning.
     *
     * On a `kind:14` it is the message the user typed; §7.5's, §9.2's and §10.1's worked examples
     * all print it empty, and §10.3's prints the blob URL in it. This library parses it nowhere —
     * a parser that never looks cannot be fooled by what it would have found — so whatever is put
     * here is what [AttributedRumor.content] reports back.
     */
    public val content: String = "",

    /**
     * §4.5's `nenya` version. MUST on a `kind:15`, `kind:16` and `kind:17` (§7.4).
     *
     * Defaulted to the version this build implements rather than left to the caller, because §7.4's
     * collision rule turns on it: a `type=5` or `type=6` carrying a foreign version MUST NOT be
     * interpreted as a delivery commitment or a bid, and [AttributedRumor.attribute] refuses one.
     * A writer whose default was anything else would emit messages its own decoder discards.
     */
    public val nenyaVersion: Int = NenyaProtocol.VERSION,

    /**
     * Tags no rule in this writer spells, emitted verbatim after the message's own rows (§4.3).
     *
     * §4.3 requires that an implementation not drop tags it does not understand, and a client with
     * a `["subject", …]`, a `["client", …]` or a NIP-89 handler tag has nowhere else to put it. A
     * name the message being built **does** spell is refused there rather than emitted, for the
     * reason `AuthoredListing.extraTags` gives: it is either a second occurrence of a row the
     * writer already emits — which §4.3 requires be rejected rather than resolved — or a caller
     * spelling by hand a tag this writer spells from the document's own table.
     */
    public val extraTags: List<List<String>> = emptyList(),
) {

    /**
     * Names two counts and no value the author wrote.
     *
     * §12 item 2 names the counterparty pubkey and §12 item 11 the order's correlation handles;
     * this type holds the first directly and arbitrary author text in [content].
     */
    override fun toString(): String =
        "RumorEnvelope(counterparties=${counterparties.size}, extraTags=${extraTags.size})"
}

/**
 * What [RumorWriter] answers: the unsigned rumor, or the §7.4, §7.5, §10 or §4.3 rule that refused
 * it.
 *
 * A value rather than an exception, and that is decision **P**'s rule for the whole writing half,
 * stated on `ListingBuild` one package over: assembling a message is something a client does from
 * values it holds, where "which field is wrong" is the normal answer rather than the exceptional
 * one.
 *
 * Every refusal below is one the decoder that reads the same kind would also make, which is what
 * the round-trip proof is for: a builder whose own decoder refuses its output is a defect in the
 * builder.
 */
public sealed interface RumorBuild {

    /**
     * The unsigned rumor, for the app to seal.
     *
     * **Nothing is sealed here, nothing is wrapped here and nothing is published here.** §7.1's
     * rumor is an unsigned event with no `sig`, and that is exactly what this carries: the app
     * hands it to `GiftWrap.seal`, which orchestrates rumor → seal → wrap using the injected
     * signer. §4.1's `id` is not carried either — it is recomputed from these five fields by
     * `EventId.of`, which is the next thing a caller does with this value.
     *
     * ### Which of §4.3's bounds this answer has and has not checked
     *
     * The same division `ListingBuild.Built` records. §4.3's four event-level bounds — serialised
     * event size, tag count, tag value size, `content` size — are measured in
     * `WireEvent.canonicalSerialisation`, which `EventId.of` calls, so a [Built] carrying 600
     * extension tags is a real answer and the refusal arrives one step later as a `WireException`.
     * It is the same asymmetry on the reading side: `AttributedRumor.attribute` takes a
     * `CheckedEvent`, so those four are behind it there too.
     */
    public class Built internal constructor(

        /** §4.1's five id-bearing fields, with the message's tags in its own emission order. */
        public val event: WireEvent,
    ) : RumorBuild {

        /**
         * Names the kind and a tag count, and no value at all.
         *
         * §12 item 11 keeps an order id out of a diagnostic and the tags of a rumor hold one.
         */
        override fun toString(): String = "RumorBuild.Built(kind=${event.kind}, tags=${event.tags.size})"
    }

    /**
     * The message was refused, carrying the reason and the tag it is about as **data**.
     *
     * Same convention as [ChannelException], which is this type's thrown equivalent: tests assert
     * on [reason] and [tag] rather than on [detail]'s wording.
     */
    public class Refused internal constructor(

        /** Which §7.4, §7.5, §8.1, §10.1, §10.2, §11.1, §4.3 or §4.5 rule refused the message. */
        public val reason: ChannelRejection,

        /** The tag this refusal is about, or `null` where the rule names no single tag. */
        public val tag: String?,

        /**
         * Why, in words, for a log.
         *
         * **Never echoes the caller's input**, on the same terms as every other message in this
         * library (§12, STOP RULE 14): a tag *name*, a *count* and a *reason* may appear; an order
         * id, a pubkey, a price, an invoice, a decryption key or an arbitrary tag value may not.
         */
        public val detail: String,
    ) : RumorBuild {

        override fun toString(): String = "RumorBuild.Refused(reason=$reason, tag=$tag)"
    }
}

/**
 * The write half of §7.4 — one entry point per message the private channel carries, each returning
 * the unsigned rumor an app then seals.
 *
 * ### Why this exists at all
 *
 * The library decoded every rumor it defines and constructed none, so every client had to assemble
 * tag arrays by hand — which is where cardinality, ordering and completeness go wrong silently.
 * Decision **P** in `loop/VISION.md` makes the builders library work and fixes the proof: build,
 * decode with the decoder that already reads that kind, build again from what the decode reports,
 * and compare the emitted bytes.
 *
 * ### It seals nothing, publishes nothing and invents nothing
 *
 * Sealing stays the app's, through `GiftWrap.seal`, as everywhere else. Every tag is spelled by
 * `TagWriter`, by `ItemRef.toTag`, by `PubkeyRef.toTag` or from [ChannelTags]' and [DeliveryTags]'
 * own constants — no tag name and no value form is written out here — and every rule applied is one
 * the matching decoder already applies. Each message's required set is **derived** from that
 * decoder's own `requiredTags()`, which the tests hold equal to §7.4's fenced block and the
 * worked examples parsed out of the document at test time, so a tag added to a decoder's required
 * list reaches this writer and that decoder together.
 *
 * ### The two messages that cannot be built, and why each is refused rather than absent
 *
 * - **`type=4`**, GammaMarkets' shipping update. §7.4: "Nenya v1 MUST NOT emit `type=4` and MUST
 *   ignore it on read." It is *readable* — `OrderMessageKind.SHIPPING` exists and
 *   `AttributedRumor.attribute` decodes one — so the asymmetry is the rule, and there is simply no
 *   entry point here that produces one. [ChannelTags.type] refuses it as
 *   [ChannelRejection.RESERVED_TYPE] for any caller that reaches it another way.
 * - **An unknown `type` or an unknown `status`.** Both are the required *treatment* of somebody
 *   else's unrecognised value rather than values of their own, so neither has a wire form:
 *   `OrderMessageKind.UNKNOWN` carries a `null` `type` and `OrderState.UNKNOWN` a `null` token.
 *   Emitting either would republish a stranger's number as though this implementation had
 *   understood it.
 *
 * ### What it refuses, by value
 *
 * **In the order the checks are made**, because exactly one refusal is reported and it is the
 * first. The order is part of the API, so it is written here as the code runs:
 *
 * 1. §4.1's event fields — an author pubkey that is not 64 hex characters, a negative `created_at`
 *    ([ChannelRejection.MALFORMED_EVENT_FIELD]).
 * 2. §4.5's `nenya` version: refused unless it is the version this build implements, because
 *    §7.4's collision rule makes a `type=5` or `type=6` carrying any other one its own decoder
 *    discards ([ChannelRejection.UNSUPPORTED_VERSION]).
 * 3. The message's own rows, spelled one at a time, so a refusal from §5.3's Encoding column or
 *    from §8.1's arities names the row it came from ([ChannelRejection.MALFORMED_TAG], or
 *    [ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE] for an unemittable `status`).
 * 4. §10.1's key-absence rule, checked over the extra tags as well as over the rows: a commitment
 *    MUST NOT carry `decryption-key` or `decryption-nonce` and the builders must not be the way
 *    around it ([ChannelRejection.COMMITMENT_CARRIES_KEY]).
 * 5. §7.4's `order` prohibition on a `type=6`, likewise checked over the extra tags, because the
 *    entry point takes no order id and an extension tag is the only way one could arrive
 *    ([ChannelRejection.FORBIDDEN_ORDER_TAG]).
 * 6. A row this writer spells handed in as an extra tag: a second occurrence where the message
 *    really would carry two ([ChannelRejection.DUPLICATE_TAG]), and
 *    [ChannelRejection.ROW_IS_NOT_AN_EXTENSION] where it would carry one.
 * 7. A final audit against the decoder's own required set — every required tag present, and every
 *    tag §4.3's duplicate rule reaches at most once. Derived rather than listed, so a tag added to
 *    a decoder's `requiredTags()` that this writer has no parameter for makes it refuse everything
 *    rather than quietly emit a message that decoder rejects
 *    ([ChannelRejection.MISSING_REQUIRED_TAG], [ChannelRejection.DUPLICATE_TAG]).
 *
 * Pure computation: no clock, no randomness, no I/O. The `created_at` is the caller's (§4.6).
 */
public object RumorWriter {

    // ---------------------------------------------------------------------------------------------
    // The emission orders, each one wire-visible and therefore pinned.
    //
    // §4.1 computes the id over the tags **in order**, so an emission order is part of the event.
    // §7.4 fixes none, and the document's own worked examples disagree with each other about where
    // `nenya` and `p` sit — which is what makes it a choice this writer has to make and a test has
    // to hold. Where a section prints a worked example, the order below is *that example's*, parsed
    // out of `spec/NENYA-1.md` at test time by `RumorWriterTest`. Where it prints none, the order is
    // this writer's own and the comment says what it was modelled on.
    // ---------------------------------------------------------------------------------------------

    /**
     * §7.5's worked proposal, in the order that example prints its nine tags.
     *
     * `RumorWriterTest` holds this list equal to `Section75.exampleTagNames`, so §7.5 is one side of
     * the comparison rather than this file.
     */
    public val PROPOSAL_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.ORDER,
            ChannelVocabulary.TYPE,
            ChannelVocabulary.COUNTERPARTY,
            ChannelVocabulary.ITEM,
            ChannelVocabulary.AMOUNT_MSAT,
            ChannelVocabulary.FEE,
            ChannelVocabulary.DELIVER_BY,
            ChannelVocabulary.EXPIRATION,
            ChannelVocabulary.VERSION,
        ),
    )

    /**
     * §10.1's worked commitment, in the order that example prints its ten tags.
     *
     * Held equal at test time to `Section10.commitmentTagNames`.
     */
    public val COMMITMENT_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.ORDER,
            ChannelVocabulary.TYPE,
            DeliveryTags.URL,
            DeliveryTags.SERVED_HASH,
            DeliveryTags.PLAINTEXT_HASH,
            DeliveryTags.MIME_TYPE,
            DeliveryTags.SIZE,
            DeliveryTags.ENCRYPTION_ALGORITHM,
            ChannelVocabulary.VERSION,
            ChannelVocabulary.COUNTERPARTY,
        ),
    )

    /**
     * §10.3's worked release, in the order that example prints its ten tags.
     *
     * Held equal at test time to `Section10.releaseTagNames`. Note that it puts `p` **first** and
     * `nenya` last, where §10.1 puts both last — the two orders are genuinely different in the
     * document, which is the clearest evidence that §7.4 fixes none and that a writer must choose.
     */
    public val RELEASE_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.COUNTERPARTY,
            ChannelVocabulary.ORDER,
            DeliveryTags.FILE_TYPE,
            DeliveryTags.ENCRYPTION_ALGORITHM,
            DeliveryTags.DECRYPTION_KEY,
            DeliveryTags.DECRYPTION_NONCE,
            DeliveryTags.SERVED_HASH,
            DeliveryTags.PLAINTEXT_HASH,
            DeliveryTags.SIZE,
            ChannelVocabulary.VERSION,
        ),
    )

    /**
     * A `type=3` status update. §7.6's four repeated terms, in §7.5's own order, with `status` last
     * before `nenya`.
     *
     * §11.1 and §7.6 print no worked example, so this order is this writer's. It is
     * [PROPOSAL_TAG_ORDER] with `status` inserted, which is the shape §7.6 makes it: an acceptance
     * is "a status update carrying the same `order` id" whose `item`, `amount_msat`, `fee` and
     * `deliver_by` MUST be byte-identical to the proposal's. A caller repeating those four is
     * repeating them in the position the proposal put them, which is the only choice here that does
     * not make an implementation re-order a message it is about to compare byte for byte.
     */
    public val ORDER_UPDATE_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.ORDER,
            ChannelVocabulary.TYPE,
            ChannelVocabulary.COUNTERPARTY,
            ChannelVocabulary.ITEM,
            ChannelVocabulary.AMOUNT_MSAT,
            ChannelVocabulary.FEE,
            ChannelVocabulary.DELIVER_BY,
            ChannelVocabulary.EXPIRATION,
            ChannelVocabulary.STATUS,
            ChannelVocabulary.VERSION,
        ),
    )

    /**
     * A `type=6` private bid. §6.1's own prose order, which is the nearest thing it has to an
     * example.
     *
     * §6.1: "carrying the same term tags a public bid would carry — `price`, and optionally `fee`,
     * `deliver_by`, `expiration` — plus exactly one `["item", "<coordinate>", "1"]` naming the
     * listing it bids on (§5.3), plus `["nenya", "1"]` and the `["p", …]` every `kind:16` rumor
     * carries". `item` leads because it is the binding, and there is deliberately no `order`: §6.1
     * and §7.4 both say a `type=6` MUST NOT carry one.
     */
    public val PRIVATE_BID_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.TYPE,
            ChannelVocabulary.ITEM,
            ChannelVocabulary.PRICE,
            ChannelVocabulary.FEE,
            ChannelVocabulary.DELIVER_BY,
            ChannelVocabulary.EXPIRATION,
            ChannelVocabulary.VERSION,
            ChannelVocabulary.COUNTERPARTY,
        ),
    )

    /**
     * A `kind:14` chat. §7.4 puts it outside the required-tag rule, so all three of these are
     * optional and the order is this writer's.
     *
     * `order` is emitted only when the caller passes one, which is §7.4's permission — "an
     * implementation MAY carry `order` on a `kind:14` and MUST NOT derive anything from its
     * presence or absence" — and the absence is reachable from the API rather than only from a
     * hostile peer.
     */
    public val CHAT_TAG_ORDER: List<String> = readOnlyListOf(
        listOf(
            ChannelVocabulary.ORDER,
            ChannelVocabulary.COUNTERPARTY,
            ChannelVocabulary.VERSION,
        ),
    )

    // ---------------------------------------------------------------------------------------------
    // The entry points, one per message §7.4 lets Nenya v1 emit.
    // ---------------------------------------------------------------------------------------------

    /**
     * §7.4's `kind:14` free-text chat, which carries no terms and moves no state.
     *
     * @param order §7.4's optional binding, emitted when present and omitted when `null`. Nothing
     *   in this library derives anything from either case, which is what §7.4 requires.
     */
    public fun chat(envelope: RumorEnvelope, order: OrderId? = null): RumorBuild = assemble(
        Message(
            kind = RumorKind.CHAT,
            type = null,
            order = order,
            tagOrder = CHAT_TAG_ORDER,
            // §7.4 states its required set over the other three kinds and puts chat outside it in
            // so many words, so the audit has nothing to require here. An empty list rather than a
            // skipped audit: the duplicate half of it still runs.
            required = emptyList(),
        ),
        envelope,
    )

    /**
     * §7.5's `kind:16` `type=1` order proposal — the message `OrderMachine.open` opens an order on.
     *
     * @param order §7.4's order id. §7.4 requires it be 32 bytes from a cryptographically secure
     *   random source, minted by the buyer, and the parameter's type is how that is held: an
     *   [OrderId] comes from `OrderId.mint` over injected randomness or off the wire, never from a
     *   hex string a caller assembled.
     * @param item §7.5's `["item", "<coordinate>", "1"]`, which "is how the proposal names the
     *   listing it derives from". Exactly one, and `ItemRef` holds the quantity to `"1"`.
     * @param terms §8.3's price and §7.5's two deadlines. The price is emitted as `amount_msat` in
     *   §4.4's strict canonical decimal, because §7.6 compares it byte-identically.
     * @param feeRecipient §8.1's third `fee` element — REQUIRED above zero basis points and
     *   OMITTED at zero. `FeeSplit` carries the basis points and not the recipient, so it arrives
     *   beside the terms rather than inside them.
     * @param amountSat §7.5's GammaMarkets `["amount", "<sats>"]` compatibility tag, a MAY.
     *   Emitted only when present, and the writer refuses a value that disagrees with
     *   `amount_msat` by the same rule §7.5 makes the *decoder* enforce — "the implementation MUST
     *   reject the message. It MUST NOT prefer one and continue."
     */
    public fun proposal(
        envelope: RumorEnvelope,
        order: OrderId,
        item: ItemRef,
        terms: OrderTerms,
        feeRecipient: String? = null,
        amountSat: Long? = null,
    ): RumorBuild = assemble(
        Message(
            kind = RumorKind.ORDER_MESSAGE,
            type = OrderMessageKind.PROPOSAL,
            order = order,
            tagOrder = if (amountSat == null) PROPOSAL_TAG_ORDER
            // §7.5's example does not print the compatibility tag, so it has no position in that
            // example's order. It is emitted directly after the `amount_msat` it must agree with,
            // which is where a reader comparing the two finds them adjacent.
            else PROPOSAL_TAG_ORDER.withAmountAfterAmountMsat(),
            required = OrderProposal.requiredTags(),
            item = item,
            terms = terms,
            feeRecipient = feeRecipient,
            amountSat = amountSat,
        ),
        envelope,
    )

    /**
     * §7.4's `kind:16` `type=3` status update — the message §7.6 makes acceptance out of.
     *
     * @param status §11.1's order vocabulary, never §5.2's listing one. [OrderState.UNKNOWN] is
     *   refused: §11.1 makes it the required treatment of an unrecognised token rather than a
     *   token, and `OrderStatusCodec.write` is what says so.
     * @param terms the four §7.6 terms this update repeats, or `null` to repeat none. §7.6
     *   requires `item`, `amount_msat`, `fee` and `deliver_by` be **byte-identical** to the
     *   proposal's for the update to be an acceptance, and `Acceptance.CounterProposal` is what an
     *   update repeating none is reported as — so `null` is a legal and meaningful choice here,
     *   not a missing one. A `["status", "cancelled"]` from either party repeats no terms.
     */
    public fun statusUpdate(
        envelope: RumorEnvelope,
        order: OrderId,
        status: OrderState,
        item: ItemRef? = null,
        terms: OrderTerms? = null,
        feeRecipient: String? = null,
    ): RumorBuild = assemble(
        Message(
            kind = RumorKind.ORDER_MESSAGE,
            type = OrderMessageKind.STATUS_UPDATE,
            order = order,
            tagOrder = ORDER_UPDATE_TAG_ORDER,
            // §7.4's three plus `type`. §7.6 requires no term be *present* — an update carrying
            // none is a counter-proposal rather than a malformed message — so the terms are not
            // on this list and `OrderStatusMessage.decode` does not require them either.
            required = AttributedRumor.requiredTags() + listOf(ChannelVocabulary.TYPE),
            item = item,
            terms = terms,
            feeRecipient = feeRecipient,
            status = status,
        ),
        envelope,
    )

    /**
     * §10.1's `kind:16` `type=5` delivery commitment, sent by the provider before any payment
     * request.
     *
     * The commitment MUST NOT carry `decryption-key` or `decryption-nonce` — §10.1: "releasing the
     * key at commitment time collapses the whole construction" — and this writer has no parameter
     * for either, so the only way one could arrive is as an extra tag. That is refused by name, as
     * [ChannelRejection.COMMITMENT_CARRIES_KEY], before any value is spelled: the builders must not
     * be the way around a rule the decoder enforces.
     *
     * @param commitment §10.1's `x`, `ox`, `m` and `size`, in the shape §10.3 and §10.4 compare.
     * @param url §10.1's `["url", …]`, where the provider says the encrypted blob is served from.
     *   Nothing in this library fetches it, opens a socket or renders it (§12 item 10, STOP RULE
     *   13); it is a bare string here for the reason `DeliveryCommitmentMessage.url` gives — a
     *   richer type would be a type for "a URL", which would be a thing to be tempted to fetch.
     */
    public fun commitment(
        envelope: RumorEnvelope,
        order: OrderId,
        commitment: DeliverableCommitment,
        url: String,
    ): RumorBuild = assemble(
        Message(
            kind = RumorKind.ORDER_MESSAGE,
            type = OrderMessageKind.DELIVERY_COMMITMENT,
            order = order,
            tagOrder = COMMITMENT_TAG_ORDER,
            required = DeliveryCommitmentMessage.requiredTags(),
            commitment = commitment,
            url = url,
        ),
        envelope,
    )

    /**
     * §10.3's `kind:15` file-message release, sent by the provider after payment evidence verifies.
     *
     * @param release §10.3's `x`, `ox`, `file-type` and `size`. `file-type` is nullable on
     *   [DeliverableRelease] and stays nullable here, because §10.3 requires the identity check
     *   **not** be skipped when the tag is absent: an absent `file-type` is a divergence from the
     *   commitment's `m` rather than a malformed release, and refusing it in the writer would make
     *   that reachable only from a hostile peer.
     * @param decryptionKey §10.2's 256-bit key — 64 lowercase hex characters on write.
     * @param decryptionNonce §10.2's 96-bit nonce — 24 lowercase hex characters on write.
     *
     * ### The key and the nonce are parameters and are held nowhere
     *
     * §10.2 fixes both encodings and this writer applies the write half: "both MUST be emitted as
     * lowercase hex (64 and 24 characters respectively)", so a value that is not is refused as
     * [ChannelRejection.MALFORMED_KEY_MATERIAL] rather than normalised. They go straight into the
     * two tags and onto no field of anything: §12 item 11 and STOP RULE 14 name decryption keys
     * alongside key material and preimages, so there is no member of any value this function
     * returns from which one could reach a log, exactly as `DeliverableReleaseMessage` holds
     * neither after checking them.
     *
     * Note the asymmetry with the read side, and it is §10.2's own. The decoder **accepts**
     * standard base64 and either hex case, because "NIP-17 does not specify the encoding and other
     * clients may emit it"; this writer emits only the form §10.2 makes mandatory on write. A
     * writer that accepted everything its decoder accepts would be emitting a spelling §10.2
     * forbids, which is the one direction the round-trip proof cannot see — so it is asserted
     * directly.
     */
    public fun release(
        envelope: RumorEnvelope,
        order: OrderId,
        release: DeliverableRelease,
        decryptionKey: String,
        decryptionNonce: String,
    ): RumorBuild = assemble(
        Message(
            kind = RumorKind.FILE_MESSAGE,
            type = null,
            order = order,
            tagOrder = RELEASE_TAG_ORDER,
            required = DeliverableReleaseMessage.requiredTags(),
            release = release,
            decryptionKey = decryptionKey,
            decryptionNonce = decryptionNonce,
        ),
        envelope,
    )

    /**
     * §6.1's `kind:16` `type=6` private bid — "the same terms as the public bid, in a §7 sealed
     * message to the listing author".
     *
     * The **only** `type` that carries no `order` tag, because no order exists until the buyer
     * proposes, so this entry point takes no order id at all. An `["order", …]` arriving as an
     * extra tag is refused as [ChannelRejection.FORBIDDEN_ORDER_TAG], which is the same reason
     * `AttributedRumor.attribute` gives for one on the wire.
     *
     * @param item §6.1's exactly one `["item", "<coordinate>", "1"]`, which "carries the binding
     *   that `A`/`a`, `K`/`k` and `P`/`p` carry on a public bid".
     * @param terms the bid's four terms. §6.1 gives a private bid "the same term tags a public bid
     *   would carry — `price`, and optionally `fee`, `deliver_by`, `expiration`", which is exactly
     *   what [OrderTerms] holds, so it is the type here rather than four loose parameters. The
     *   name says *order* and no order exists at bid time, and that is worth reading rather than
     *   explaining away: what the type holds is §8.3's split and §7.5's two deadlines, and §6.1's
     *   whole point is that a bid states the terms an order would later be opened on. Note the one
     *   consequence — `OrderTerms`'s own `init` applies §7.5's strictly-earlier rule to the two
     *   deadlines, so this writer cannot be handed a bid whose `expiration` is at or after its
     *   `deliver_by`. §6.1 states no such rule and `AttributedRumor.attribute` does not enforce one
     *   on a `type=6`, so that is this writer being stricter than the document rather than
     *   enforcing it: refusing a bid that can still be accepted after its own delivery deadline is
     *   the fail-closed direction, and the shape stays readable because nothing here refuses it on
     *   read.
     *
     * The price rides to the wire as §5.3's satoshi-denominated `price` row and **not** as
     * `amount_msat`: §6.1 says "the same term tags a public bid would carry", and `amount_msat` is
     * §7.5's tag for an order that does not exist at bid time. `TagWriter.price` refuses a
     * part-satoshi amount rather than rounding it, which is §4.4's rule for that row.
     */
    public fun privateBid(
        envelope: RumorEnvelope,
        item: ItemRef,
        terms: OrderTerms,
        feeRecipient: String? = null,
    ): RumorBuild = assemble(
        Message(
            kind = RumorKind.ORDER_MESSAGE,
            type = OrderMessageKind.PRIVATE_BID,
            order = null,
            tagOrder = PRIVATE_BID_TAG_ORDER,
            // §7.4's required set minus the `order` tag it forbids here, plus `type`. Derived from
            // the published list rather than restated, so §7.4's own two survivors move with it.
            required = AttributedRumor.requiredTags() - ChannelVocabulary.ORDER +
                listOf(ChannelVocabulary.TYPE, ChannelVocabulary.ITEM, ChannelVocabulary.PRICE),
            item = item,
            terms = terms,
            feeRecipient = feeRecipient,
        ),
        envelope,
    )

    // ---------------------------------------------------------------------------------------------
    // Assembly, shared by every entry point above.
    // ---------------------------------------------------------------------------------------------

    /**
     * The same assembly, for a message whose own rows are spelled by another package's vocabulary.
     *
     * `internal`, and it exists for exactly one caller: `PaymentWriter`, whose §8.6 `payee` and
     * `payment` tags are `SettlementVocabulary`'s rather than §5.3's or §7.4's. Routing it through
     * here is what keeps **one** copy of §7.4's envelope rules, §4.1's event-field checks, §4.5's
     * version rule and §4.3's extra-tag rules: a second writer that re-derived them would be two
     * implementations of the same four rules, which is the divergence these builders exist to stop.
     *
     * It is not a door a rule can be switched off through. [rows] supplies *values* for names the
     * caller put in [tagOrder]; every check [assemble] makes still runs over the assembled result,
     * and each of [rows]' names joins §4.3's duplicate audit — see [singleOccurrence] for why that
     * is sound for every name this caller supplies and would not be for `p`.
     *
     * @param rows one entry per row the caller spells, keyed by tag name. A name in [tagOrder] with
     *   no entry here and no row in [row] is a refusal, not a silent omission.
     */
    internal fun build(
        kind: RumorKind,
        type: OrderMessageKind?,
        envelope: RumorEnvelope,
        order: OrderId?,
        tagOrder: List<String>,
        required: List<String>,
        rows: Map<String, List<List<String>>>,
    ): RumorBuild = assemble(
        Message(
            kind = kind,
            type = type,
            order = order,
            tagOrder = tagOrder,
            required = required,
            rows = rows,
        ),
        envelope,
    )

    /**
     * One message's identity and its values, so [assemble] is written once.
     *
     * Private, and a plain carrier: every field is a value an entry point above supplied, and the
     * rules are all in [assemble] and [row]. A `when` over [tagOrder]'s names in [row] with a
     * refusing `else` is what keeps a name in an emission order from being a tag silently emitted
     * as nothing — the same discipline `ListingWriter.row` states, and for the same reason.
     */
    private class Message(
        val kind: RumorKind,
        val type: OrderMessageKind?,
        val order: OrderId?,
        val tagOrder: List<String>,
        val required: List<String>,
        val item: ItemRef? = null,
        val terms: OrderTerms? = null,
        val feeRecipient: String? = null,
        val amountSat: Long? = null,
        val status: OrderState? = null,
        val commitment: DeliverableCommitment? = null,
        val url: String? = null,
        val release: DeliverableRelease? = null,
        val decryptionKey: String? = null,
        val decryptionNonce: String? = null,

        /** Rows another package's vocabulary spells; see [RumorWriter.build]. */
        val rows: Map<String, List<List<String>>> = emptyMap(),
    )

    private fun assemble(message: Message, envelope: RumorEnvelope): RumorBuild {
        // §4.1's event fields, before a single tag is spelled: a pubkey that is not a pubkey has no
        // business reaching a tag codec, and a negative `created_at` is no §4.3 timestamp. The
        // pubkey goes through the same reader a `p` tag does rather than a second copy of §4.3's
        // rule, so it is checked and lowercased by the code `AttributedRumor` will compare it with.
        val author = try {
            readPubkeyHex(envelope.authorPubkey, "the author's pubkey")
        } catch (refused: TagException) {
            return refusal(
                ChannelRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes an author pubkey as exactly 64 hexadecimal characters and §7.2 " +
                    "compares the seal's against it; this one was refused as ${refused.reason.name}",
            )
        }
        if (envelope.createdAt < 0L) {
            return refusal(
                ChannelRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes a timestamp as a non-negative integer and `created_at` is one",
            )
        }
        // §4.5 and §7.4's collision rule, together, because they are one refusal from a caller's
        // point of view: `AttributedRumor.attribute` discards a `type=5` or a `type=6` carrying a
        // version it does not implement, so emitting one would be emitting a message this
        // library's own decoder throws away.
        //
        // Applied to **every** kind rather than only to those two, and that is deliberately
        // stricter than the read side. §4.5's general "ignore an event whose version I do not
        // implement" is the caller's decision on read, because a conformant peer may legitimately
        // implement a later revision; on write there is no peer to accommodate — this library is
        // the author, and it cannot honestly state that a message implements a version of the
        // document it was not built from.
        if (envelope.nenyaVersion != NenyaProtocol.VERSION) {
            return refusal(
                ChannelRejection.UNSUPPORTED_VERSION,
                ChannelVocabulary.VERSION,
                "§4.5's `${ChannelVocabulary.VERSION}` tag states the major version of the document " +
                    "a message implements, and this build implements ${NenyaProtocol.VERSION}; " +
                    "§7.4 requires an implementation ignore a `${ChannelVocabulary.TYPE}=" +
                    "${OrderMessageKind.DELIVERY_COMMITMENT.type}` or " +
                    "`${ChannelVocabulary.TYPE}=${OrderMessageKind.PRIVATE_BID.type}` carrying any " +
                    "other, so this writer would be emitting a rumor its own decoder discards",
            )
        }

        // §10.1's key-absence rule and §7.4's `order` prohibition, both checked over the extra tags
        // before anything is spelled. Each is a rule about a tag this writer has no parameter for,
        // so an extension is the only way one could arrive — and each is a construction collapsed
        // rather than a message with one tag too many, which is why it is refused first.
        forbiddenExtra(message, envelope.extraTags)?.let { return it }

        // The message's rows, in its emission order, one at a time — so a refusal from the Encoding
        // column names the row it came from as data. A single `try` around the whole assembly would
        // answer "some value was malformed", which is what the `tag` field exists not to be.
        val tags = mutableListOf<List<String>>()
        for (name in message.tagOrder) {
            val rows = try {
                row(name, message, envelope, author)
            } catch (refused: TagException) {
                return refusal(
                    tagFailure(refused.reason, name),
                    name,
                    "the Encoding column refused the value this writer was handed for `$name`, as " +
                        "${refused.reason.name}",
                )
            } catch (refused: OrderStateException) {
                return refusal(
                    ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE,
                    name,
                    "§11.1's `unknown` is the required treatment of an unrecognised `$name` and not " +
                        "a value of its own, so there is nothing to emit for it",
                )
            } catch (refused: ChannelException) {
                // `ChannelTags.type` already refuses §7.4's `type=4` and its unknown sink by name,
                // so those two refusals are reported as that code made them rather than reworded.
                return refusal(refused.reason, refused.tag ?: name, refused.message ?: "")
            }
            tags += rows
        }

        // The extra tags are checked **after** assembly, against the rows that were actually
        // emitted, so the reason a row handed in as an extension gets is the true one: a duplicate
        // when this message really would carry two, and `ROW_IS_NOT_AN_EXTENSION` when it would
        // carry one. `ListingWriter` records why diagnosing it earlier reports the wrong one.
        extraTagProblem(message, envelope.extraTags, tags.map { it[0] }.toSet())?.let { return it }
        // §4.3: emitted verbatim and last, so a client's extension can never displace a row from
        // the position the emission order fixes for it.
        for (tag in envelope.extraTags) tags += tag.toList()

        vocabularyProblem(message, tags)?.let { return it }

        val event = try {
            WireEvent(author, envelope.createdAt, message.kind.kind, tags, envelope.content)
        } catch (refused: WireException) {
            return refusal(
                ChannelRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.1's event structure refused these fields as ${refused.reason.name}",
            )
        }
        return RumorBuild.Built(event)
    }

    /**
     * One row's tags — none, one, or several — spelled by `TagWriter`, by a `toTag` or from §7.4's,
     * §8.6's and §10's own constants, and never by a tag name written out here.
     *
     * The `when` is over the emission orders' names and its `else` is a refusal rather than a
     * fall-through: a name added to one of those lists and not here would otherwise be a row
     * silently emitted as nothing, which is exactly the "completeness goes wrong in silence"
     * failure the builders exist to stop. [vocabularyProblem] catches it a second time if the row
     * is required.
     *
     * @throws TagException from the tag layer, caught by [assemble], which names the row.
     * @throws OrderStateException for §11.1's unemittable `unknown`.
     * @throws ChannelException from [ChannelTags.type] for §7.4's `type=4` and its unknown sink.
     */
    private fun row(
        name: String,
        message: Message,
        envelope: RumorEnvelope,
        author: String,
    ): List<List<String>> = message.rows[name] ?: when (name) {
        ChannelVocabulary.ORDER -> optional(message.order) { ChannelTags.order(it) }
        ChannelVocabulary.TYPE -> optional(message.type) { ChannelTags.type(it) }
        ChannelVocabulary.COUNTERPARTY -> envelope.counterparties.map { it.toTag() }
        ChannelVocabulary.VERSION -> listOf(TagWriter.version(envelope.nenyaVersion))
        ChannelVocabulary.ITEM -> optional(message.item) { it.toTag() }
        // §7.5: "the price in millisatoshis, what the provider receives", in §4.4's strict
        // canonical decimal — which `Long.toString` is, for a non-negative value, and `Msat`
        // cannot hold a negative one. Strict is the rule rather than the default: §7.6 compares
        // this value byte-identically, so a leading zero here would make an acceptance of these
        // terms read as a counter-proposal.
        ChannelVocabulary.AMOUNT_MSAT ->
            optional(message.terms) { listOf(name, it.split.price.millisatoshis.toString()) }
        ChannelVocabulary.AMOUNT -> optional(message.amountSat) { satoshis ->
            // §7.5's own cross-check, applied on write: "if both are present and
            // `amount × 1000 ≠ amount_msat`, the implementation MUST reject the message". The
            // multiply goes through `Msat.ofSat`, which bounds the operand against §4.4's cap
            // before multiplying, so no value is ever multiplied into a silent 64-bit wrap — the
            // whole reason T1 exists, and the same route `ChannelTerms.checkAmountAgreement` takes.
            // Through `asTagRejection`, which is what makes this a refusal by value rather than a
            // crash. `amountSat` is the one bare `Long` in this writer's API — §7.5's compatibility
            // tag is a count of satoshis and not an `Msat` — so it is the one parameter a caller can
            // hand a negative or an above-supply figure straight from a form field. `Msat.ofSat`
            // answers both with a `MoneyException`, which is not a `TagException` and so would sail
            // past every catch in `assemble` and out of a function whose whole contract is that it
            // refuses by value.
            val stated = try {
                Msat.ofSat(satoshis)
            } catch (refused: MoneyException) {
                throw refused.asTagRejection("§7.5's `${ChannelVocabulary.AMOUNT}` tag value")
            }
            val price = message.terms?.split?.price
            if (price != null && stated != price) {
                throw TagException(
                    TagRejection.MALFORMED_NUMBER,
                    "§7.5 requires `${ChannelVocabulary.AMOUNT}` × 1000 equal " +
                        "`${ChannelVocabulary.AMOUNT_MSAT}` when both are present, and requires " +
                        "rejecting the message when they disagree rather than preferring one",
                )
            }
            listOf(name, satoshis.toString())
        }
        // §5.3's satoshi-denominated row, which §6.1 gives a private bid and §7.5 does not give a
        // proposal — the same price, under the tag each section names. Which of the two is emitted
        // is decided by the emission order alone, so there is one source for the value.
        ChannelVocabulary.PRICE -> optional(message.terms) { TagWriter.price(it.split.price) }
        ChannelVocabulary.FEE -> feeRow(message)
        ChannelVocabulary.DELIVER_BY ->
            optional(message.terms?.deliverBy) { TagWriter.timestamp(name, it) }
        ChannelVocabulary.EXPIRATION ->
            optional(message.terms?.expiration) { TagWriter.timestamp(name, it) }
        ChannelVocabulary.STATUS ->
            optional(message.status) { listOf(name, OrderStatusCodec.write(it)) }
        DeliveryTags.URL -> optional(message.url) { listOf(name, it) }
        // §10.1's and §10.3's `x` and `ox`, taken off whichever of the two values this message is.
        // `DeliverableHash.toHex` is §4.3's lowercase hex, computed by the type that holds the
        // bytes rather than re-encoded here.
        DeliveryTags.SERVED_HASH ->
            optional(message.commitment?.x ?: message.release?.x) { listOf(name, it.toHex()) }
        DeliveryTags.PLAINTEXT_HASH ->
            optional(message.commitment?.ox ?: message.release?.ox) { listOf(name, it.toHex()) }
        // §10.3's `file-type` and §10.1's `m` name the same value and are differently named on
        // purpose — "because each follows its own host kind's convention" — so each message spells
        // only its own, which is also what §10.3 requires a reader do.
        DeliveryTags.MIME_TYPE -> optional(message.commitment?.mimeType) { listOf(name, it) }
        DeliveryTags.FILE_TYPE -> optional(message.release?.fileType) { listOf(name, it) }
        // §10.1: "the length in bytes of the **encrypted** blob, as a decimal string". Written
        // canonically, which is the stricter of the two spellings §10.3 compares byte-identically.
        DeliveryTags.SIZE -> optional(message.commitment?.sizeBytes ?: message.release?.sizeBytes) {
            TagWriter.timestamp(name, it)
        }
        // §10.2: "`encryption-algorithm` MUST be `aes-gcm` in v1", and there is no second value for
        // a caller to choose, so there is no parameter for one.
        DeliveryTags.ENCRYPTION_ALGORITHM -> onDelivery(message) { listOf(name, DeliveryTags.AES_GCM) }
        DeliveryTags.DECRYPTION_KEY ->
            optional(message.decryptionKey) { listOf(name, lowercaseHex(name, it, DeliveryTags.KEY_BYTES)) }
        DeliveryTags.DECRYPTION_NONCE ->
            optional(message.decryptionNonce) { listOf(name, lowercaseHex(name, it, DeliveryTags.NONCE_BYTES)) }
        else -> throw TagException(
            TagRejection.MALFORMED_CONTEXT,
            "`$name` is in this writer's emission order for a kind:${message.kind.kind} and it has " +
                "no row here; a tag this writer cannot spell is a message it must not emit",
        )
    }

    /** One tag or none, for a row whose cardinality permits zero. */
    private fun <T> optional(value: T?, tag: (T) -> List<String>): List<List<String>> =
        if (value == null) emptyList() else listOf(tag(value))

    /** A §10 row with no parameter of its own, emitted on exactly the two messages §10 prints it on. */
    private fun onDelivery(message: Message, tag: () -> List<String>): List<List<String>> =
        if (message.commitment == null && message.release == null) emptyList() else listOf(tag())

    /**
     * §8.1's two arities, or no `fee` tag at all for [FeeTerm.Absent] — which has no wire form.
     *
     * The term is read off [OrderTerms] on every message that can carry one — §7.5's proposal,
     * §7.6's status update and §6.1's private bid alike — so the three cannot come to spell §8.1's
     * arities differently. A message with no terms at all carries no `fee` tag, which is
     * [FeeTerm.Absent]'s wire form and §8.1's zero-fee case.
     */
    private fun feeRow(message: Message): List<List<String>> {
        val term = message.terms?.split?.term ?: FeeTerm.Absent
        if (term == FeeTerm.Absent) {
            if (message.feeRecipient != null) {
                // `TagWriter.fee` would refuse this as ABSENT_FEE_TERM, which names the wrong half:
                // the caller did not ask for the absent form, they asked for a recipient without a
                // term. §8.1 makes the recipient a property of the term, so this is an arity error.
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 makes the `${ChannelVocabulary.FEE}` recipient a part of the term; an " +
                        "absent term has no wire form at all, so there is nothing for a recipient " +
                        "to belong to",
                )
            }
            return emptyList()
        }
        return listOf(TagWriter.fee(term, message.feeRecipient))
    }

    /**
     * §10.2's write rule: "both MUST be emitted as lowercase hex (64 and 24 characters
     * respectively)".
     *
     * Lowercase and exactly that length, refused rather than normalised — the asymmetry
     * [release] documents. The value is returned and held nowhere: it goes into the tag the caller
     * asked for and onto no field (§12 item 11, STOP RULE 14), and the refusal names the length it
     * was and never the value.
     */
    private fun lowercaseHex(name: String, value: String, bytes: Int): String {
        val expected = bytes * 2
        val legal = value.length == expected && value.all { it in '0'..'9' || it in 'a'..'f' }
        if (!legal) {
            throw TagException(
                TagRejection.NOT_HEX,
                "§10.2 fixes `$name` as $bytes bytes and requires it be emitted as $expected " +
                    "lowercase hex characters; this value is ${value.length} character(s) and is " +
                    "not that. The read side accepts uppercase and base64 because other clients " +
                    "emit them, which is a rule about a counterparty's bytes and not about ours",
            )
        }
        return value
    }

    // ---------------------------------------------------------------------------------------------
    // The checks, each derived from the decoder's own list rather than restated.
    // ---------------------------------------------------------------------------------------------

    /**
     * The two prohibitions that are about a tag this writer has no parameter for, so an extension
     * tag is the only way one could arrive.
     *
     * Both are checked before any value is spelled, because each is a construction collapsed rather
     * than a message with one tag too many — §10.1 says so in as many words about the first.
     */
    private fun forbiddenExtra(message: Message, extraTags: List<List<String>>): RumorBuild.Refused? {
        val names = extraTags.mapNotNull { it.firstOrNull() }.toSet()
        if (message.type == OrderMessageKind.DELIVERY_COMMITMENT) {
            for (name in FORBIDDEN_ON_A_COMMITMENT) {
                if (name !in names) continue
                return refusal(
                    ChannelRejection.COMMITMENT_CARRIES_KEY,
                    name,
                    "§10.1: a commitment MUST NOT contain `$name`, and an implementation MUST " +
                        "reject one that does. §10.5 is why the rule is absolute: " +
                        "`${DeliveryTags.PLAINTEXT_HASH}` binds the provider only because the key " +
                        "does not exist in the buyer's hands until after payment, and a commitment " +
                        "that ships the key has already released the deliverable. This writer has " +
                        "no parameter for it, so an extension tag is the only way in and it is " +
                        "closed here",
                )
            }
        }
        if (message.type == OrderMessageKind.PRIVATE_BID && ChannelVocabulary.ORDER in names) {
            return refusal(
                ChannelRejection.FORBIDDEN_ORDER_TAG,
                ChannelVocabulary.ORDER,
                "§7.4: a kind:${message.kind.kind} `${ChannelVocabulary.TYPE}=" +
                    "${OrderMessageKind.PRIVATE_BID.type}` is the one message that MUST NOT carry " +
                    "`${ChannelVocabulary.ORDER}`, because no order id exists until the buyer " +
                    "proposes; §6.1 adds that a bid that looks like a proposal invites an " +
                    "implementation to advance state on it",
            )
        }
        return null
    }

    /**
     * A row this writer spells, handed in as an extra tag, refused for the reason that row's rule
     * gives.
     *
     * @param emitted the row names this message's own parameters produced, so a row that really
     *   would appear twice is reported as §4.3's duplicate and a row that would appear once is not.
     */
    private fun extraTagProblem(
        message: Message,
        extraTags: List<List<String>>,
        emitted: Set<String>,
    ): RumorBuild.Refused? {
        for (tag in extraTags) {
            if (tag.isEmpty()) {
                return refusal(
                    ChannelRejection.MALFORMED_TAG,
                    null,
                    "§4.3 says a tag is an array of one or more strings and requires rejecting an " +
                        "event carrying an empty tag array",
                )
            }
            val name = tag[0]
            if (name !in message.tagOrder) continue
            // The question here is the one this writer's KDoc states — would this message really
            // carry **two** of this row? — and it is answered from [emitted], which is what the
            // message's own parameters produced, rather than from [singleOccurrence], which is the
            // set of rows the *decoders* refuse a second occurrence of. The two are different
            // questions and conflating them answered the wrong one twice: a `kind:14` built with an
            // `order` parameter *and* an `order` extension was told `ROW_IS_NOT_AN_EXTENSION`,
            // whose own text says "nothing is duplicated" and "the parameter was left `null`",
            // because §7.4 puts a chat outside the decoders' duplicate rule.
            //
            // A second `p` is the one row where two is not a mistake — §5.3 gives it cardinality
            // `0–n` — so handing one in as an extension is still refused, but as the row it is
            // rather than as a duplicate.
            return if (name in emitted && name !in MULTI_VALUED_ROWS) {
                refusal(
                    ChannelRejection.DUPLICATE_TAG,
                    name,
                    "this message already carries `$name` from the parameter this writer spells " +
                        "that row from, so the extra tag is a second occurrence of it; §4.3 " +
                        "requires rejecting that rather than resolving it by taking the first or " +
                        "the last, and the decoder for this kind does",
                )
            } else {
                refusal(
                    ChannelRejection.ROW_IS_NOT_AN_EXTENSION,
                    name,
                    "`$name` is a row this writer spells for a kind:${message.kind.kind}, not an " +
                        "extension: pass the value to the parameter it is spelled from, so the " +
                        "Encoding column is applied to it and it lands in the position §4.1 " +
                        "hashes it in",
                )
            }
        }
        return null
    }

    /**
     * The final audit: the assembled tags against the decoder's own required set and §4.3's
     * duplicate rule.
     *
     * Unreachable for every refusal above — each of those is caught earlier and more precisely —
     * and here anyway, because it is the one check that is a function of the *decoder's* published
     * list rather than of this file. A tag added to a decoder's `requiredTags()` that no parameter
     * supplies turns every build of that kind red, which is the fail-closed direction: a writer
     * that emitted a message missing a required tag would be publishing rumors its own decoder
     * refuses.
     */
    private fun vocabularyProblem(message: Message, tags: List<List<String>>): RumorBuild.Refused? {
        val counts = mutableMapOf<String, Int>()
        for (tag in tags) counts[tag[0]] = (counts[tag[0]] ?: 0) + 1
        for (name in message.required) {
            if ((counts[name] ?: 0) > 0) continue
            return refusal(
                ChannelRejection.MISSING_REQUIRED_TAG,
                name,
                "the decoder that reads a kind:${message.kind.kind}" +
                    (message.type?.let { " `${ChannelVocabulary.TYPE}=${it.type}`" } ?: "") +
                    " requires `$name` and this message would carry none",
            )
        }
        for (name in singleOccurrence(message)) {
            val count = counts[name] ?: 0
            if (count <= 1) continue
            return refusal(
                ChannelRejection.DUPLICATE_TAG,
                name,
                "§4.3's duplicate rule reaches `$name` on this kind and this message would carry " +
                    "$count; resolving that by first, last or smallest would let two " +
                    "implementations disagree about what the message binds to",
            )
        }
        return null
    }

    /**
     * The tags §4.3's duplicate rule reaches on [message], derived from what each decoder refuses a
     * second occurrence of rather than listed here.
     *
     * §7.4's own two come from [ChannelVocabulary.SINGLE_OCCURRENCE], which
     * `AttributedRumor.refuseDuplicates` walks. The rest are the tags whose decoders call
     * `ChannelTerms.single` or `DeliveryTerms.single`: §7.5's `amount_msat` on an order message,
     * and §10's own tags on the two delivery messages. `p` is deliberately absent — §5.3 gives it
     * cardinality `0–n` and a rumor carrying two counterparties is legal.
     *
     * [Message.rows]' own names join the set, which is sound because §8.6 states the cardinality of
     * both of them itself — "exactly one `payment` tag", and exactly one named payee role — and
     * `SettlementTags.single` refuses a second of either. It would **not** be sound for `p`, which
     * is why `p` is never one of those names: `RumorWriter.build` supplies values for rows its
     * caller's own section fixes at one, and §7.4's two multi-occurrence rows stay here.
     */
    private fun singleOccurrence(message: Message): Set<String> = buildSet {
        // §7.4 puts a kind:14 outside the rule and this writer reads neither tag there, so §4.3's
        // "unknown tags MUST be ignored on read" is what governs — and `AttributedRumor` refuses a
        // duplicate on every kind but that one, for the reason it states.
        if (message.kind != RumorKind.CHAT) addAll(ChannelVocabulary.SINGLE_OCCURRENCE)
        if (message.kind == RumorKind.ORDER_MESSAGE) add(ChannelVocabulary.AMOUNT_MSAT)
        if (message.commitment != null || message.release != null) addAll(DELIVERY_SINGLE_OCCURRENCE)
        addAll(message.rows.keys)
    }

    /**
     * T9's refusals, mapped onto this package's vocabulary.
     *
     * A `when` over the constants this writer can actually raise rather than an `if`, for the
     * reason `TagException.asChannelRejection` gives: a value refused for being above supply or for
     * being malformed key material is not "a malformed tag", and a caller told only the general
     * answer has to go looking for which.
     */
    private fun tagFailure(reason: TagRejection, name: String): ChannelRejection = when {
        reason == TagRejection.ABOVE_SUPPLY -> ChannelRejection.AMOUNT_ABOVE_SUPPLY
        // Scoped to §10.2's two rows, and that scope is the point.
        // [ChannelRejection.MALFORMED_KEY_MATERIAL]'s own KDoc is written about `decryption-key`
        // and `decryption-nonce`, so answering it for a non-hex `fee` recipient or a non-hex
        // `order` id would send a reader to §10.2 for a mistake made somewhere else — and would
        // say this library had been handed malformed *key material* when what it was handed was a
        // malformed pubkey. Those rows get [ChannelRejection.MALFORMED_TAG], whose `cause` carries
        // the precise `TagRejection` and whose `tag` names the row.
        reason == TagRejection.NOT_HEX && name in KEY_MATERIAL_ROWS ->
            ChannelRejection.MALFORMED_KEY_MATERIAL
        else -> ChannelRejection.MALFORMED_TAG
    }

    private fun refusal(
        reason: ChannelRejection,
        tag: String?,
        detail: String,
    ): RumorBuild.Refused = RumorBuild.Refused(reason, tag, detail)

    /** §7.5's compatibility tag, emitted beside the `amount_msat` it must agree with. */
    private fun List<String>.withAmountAfterAmountMsat(): List<String> = buildList {
        for (name in this@withAmountAfterAmountMsat) {
            add(name)
            if (name == ChannelVocabulary.AMOUNT_MSAT) add(ChannelVocabulary.AMOUNT)
        }
    }

    /** §10.1's two forbidden tags, in the order §10.1 names them. */
    private val FORBIDDEN_ON_A_COMMITMENT: List<String> =
        listOf(DeliveryTags.DECRYPTION_KEY, DeliveryTags.DECRYPTION_NONCE)

    /**
     * The rows this writer may legitimately emit more than one of, which is §7.4's `p` and nothing
     * else.
     *
     * Every other row in every emission order is spelled from a scalar parameter and so emits at
     * most one tag — `optional` returns zero or one, and the `rows` map `PaymentWriter` supplies is
     * one tag per §8.6 row — while `["p", …]` is spelled from a list because §5.3 gives it
     * cardinality `0–n`. So this is the exact set for which "the message would carry two" is not a
     * defect, which is what [extraTagProblem] needs and what §4.3's duplicate rule is not.
     */
    private val MULTI_VALUED_ROWS: Set<String> = setOf(ChannelVocabulary.COUNTERPARTY)

    /**
     * The only two rows whose value §10.2 makes key material, and so the only two a `NOT_HEX`
     * refusal may be reported as [ChannelRejection.MALFORMED_KEY_MATERIAL] for.
     *
     * The same two tags as [FORBIDDEN_ON_A_COMMITMENT] and a separate constant on purpose: that one
     * is §10.1's prohibition on a `type=5` and this is §10.2's encoding rule on a `kind:15`, and a
     * single list would read as one rule where the document states two.
     */
    private val KEY_MATERIAL_ROWS: Set<String> =
        setOf(DeliveryTags.DECRYPTION_KEY, DeliveryTags.DECRYPTION_NONCE)

    /** §10's own tags, every one of which `DeliveryTerms.single` refuses a second occurrence of. */
    private val DELIVERY_SINGLE_OCCURRENCE: Set<String> = setOf(
        DeliveryTags.URL,
        DeliveryTags.SERVED_HASH,
        DeliveryTags.PLAINTEXT_HASH,
        DeliveryTags.SIZE,
        DeliveryTags.ENCRYPTION_ALGORITHM,
        DeliveryTags.DECRYPTION_KEY,
        DeliveryTags.DECRYPTION_NONCE,
        DeliveryTags.FILE_TYPE,
        DeliveryTags.MIME_TYPE,
    )
}
