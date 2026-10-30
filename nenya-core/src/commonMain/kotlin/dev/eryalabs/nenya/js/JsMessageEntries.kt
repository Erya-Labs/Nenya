package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.PrivateBid
import dev.eryalabs.nenya.money.FeeTerm
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// The three §7 decoders a page could not reach, crossed. T44 gap (2).
//
// `open` hands back a `JsRumor` — §7.4's envelope, decoded — and T42 and T43 exported a decoder for
// exactly one thing below it: `PaymentReceipt`, inside the two settlement checks. Everything else a
// counterparty says arrives as `JsRumor.unknownTags` and the page reads the terms out itself, which
// is the hand-parsing the whole facade exists to remove: §7.5's `amount_msat`/`amount` agreement,
// §7.4's duplicate rule, §8.1's absent-versus-stated-zero distinction and §10.1's key-absence rule
// are all decisions this library already makes and a page re-deciding them is a second rulebook.
//
// Each entry point below is **one library call** over the rumor the page already holds. The facade
// performs no check of its own: every refusal reported is the decoder's own `ChannelRejection` with
// the tag it was about, reached by the call a JVM caller makes, over the same `AttributedRumor`.
// The one refusal that is not the library's is `JsCrossing.NOT_A_BOUND_RUMOR` — §7.4 puts `kind:14`
// chat outside the bound half in so many words, so a chat is not a value any of these decoders has
// a parameter for; see that constant's note on why inventing a §7.5 or §10.1 refusal for it here
// would be this layer answering a question the library was never asked.
// =================================================================================================

/**
 * §7.5's `kind:16` `type=1` order proposal, decoded — the terms a page was reading out of raw tags.
 *
 * One call to `OrderProposal.decode`, which is the only door: `OrderProposal`'s constructor is
 * `internal`. §7.5's `amount`/`amount_msat` cross-check, §7.5's two deadline rules and §8.1's read
 * rule all run inside it and none of them is restated here.
 *
 * **It establishes no provider and performs no §7.6 comparison.** Those are `accepts`, which
 * [stepOrder]'s `acceptance_received` event reaches with the provider key the page resolved; see
 * [JsOrderEvent.providerPubkey]. A decoded proposal is the buyer's message read, and nothing more.
 *
 * @param rumor the `kind:16` `type=1` as [open] handed it back. A rumor of another bound kind
 *   reaches the decoder and gets its own `NOT_A_PROPOSAL`.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeProposal(rumor: JsRumor): JsProposalResult =
    jsGuarded({ JsProposalResult(null, it) }) {
        JsProposalResult(
            OrderProposal.decode(rumor.asBoundRumor("kind:16 type=1 order proposal")),
            null,
        )
    }

/**
 * §10.1's `kind:16` `type=5` delivery commitment, decoded — the provider's promise about a file.
 *
 * One call to `DeliveryCommitmentMessage.decode`. §10.1's key-absence rule is the first thing that
 * call makes — a commitment carrying `decryption-key` or `decryption-nonce` is refused by tag name
 * before a single hash is read — and it is the rule a page parsing tags by hand is most likely to
 * skip, because the message looks complete with the key on it. §10.2's `aes-gcm` token, §10.1's
 * `x`, `ox`, `m`, `size` and `url`, and §4.3's bounds are the same call's.
 *
 * @param rumor the `kind:16` `type=5` as [open] handed it back.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeCommitment(rumor: JsRumor): JsCommitmentResult =
    jsGuarded({ JsCommitmentResult(null, it) }) {
        JsCommitmentResult(
            DeliveryCommitmentMessage.decode(rumor.asBoundRumor("kind:16 type=5 delivery commitment")),
            null,
        )
    }

/**
 * §10.3's `kind:15` file message, decoded — the release that carries the decryption key.
 *
 * One call to `DeliverableReleaseMessage.decode`, which checks §10.2's two encodings: a
 * `decryption-key` or `decryption-nonce` that decodes to the wrong length under **both** hex and
 * base64 is refused. That is the check a page reading the tags itself cannot make without
 * reimplementing both decoders, and §10.2 requires it be made.
 *
 * ### The key and the nonce do not cross, and that is the library's shape rather than an economy
 *
 * `DeliverableReleaseMessage` checks both values and then drops them: §12 item 11 and STOP RULE 14
 * name a decryption key alongside key material and preimages, and this library decrypts nothing —
 * §10.4 step 2 is the page's. So there is no member of the Kotlin type from which a key could reach
 * a log, and [JsReleaseMessage] cannot publish one either. The page performing step 2 already holds
 * the rumor it decoded, and reads the two values off [JsRumor.unknownTags] — having been told by
 * this call that they are present and the right length.
 *
 * @param rumor the `kind:15` as [open] handed it back.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeRelease(rumor: JsRumor): JsReleaseResult =
    jsGuarded({ JsReleaseResult(null, it) }) {
        JsReleaseResult(
            DeliverableReleaseMessage.decode(rumor.asBoundRumor("kind:15 deliverable release")),
            null,
        )
    }

/**
 * §7.4's `kind:16` `type=3` status update, decoded — the message §7.6 makes acceptance out of. T48.
 *
 * One call to `OrderStatusMessage.decode`. Its `status` is read by §11.1's order codec and by no
 * other, so a listing token such as `sold` is the unknown sink here and never a listing state, and a
 * `type=3` with no `status` is refused rather than defaulted to anything.
 *
 * **A decoded update is a claim and moves nothing.** Whether it is an acceptance is §7.6's comparison
 * against the proposal and the provider key, which is [checkAcceptance]; whether it moves an order is
 * [stepOrder]'s. This call performs neither.
 *
 * @param rumor the `kind:16` `type=3` as [open] handed it back.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeStatusUpdate(rumor: JsRumor): JsStatusUpdateResult =
    jsGuarded({ JsStatusUpdateResult(null, it) }) {
        JsStatusUpdateResult(
            OrderStatusMessage.decode(rumor.asBoundRumor("kind:16 type=3 status update")),
            null,
        )
    }

/**
 * §6.1's `kind:16` `type=6` private bid, decoded — the terms a listing author was reading out of raw
 * tags. T59.
 *
 * One call to `PrivateBid.decode`. §6.1's `order` prohibition already ran inside [open]'s
 * attribution; §6.1's `price` requirement and §5.3's satoshi-denominated `price` row run in this one,
 * and neither is restated here.
 *
 * **A decoded bid binds nobody and moves nothing** (§6.1). It is not an order event, and no order id
 * crosses with it, because a `type=6` carries none (§7.4).
 *
 * @param rumor the `kind:16` `type=6` as [open] handed it back. A rumor of another bound kind
 *   reaches the decoder and gets its own `NOT_A_PRIVATE_BID`.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodePrivateBid(rumor: JsRumor): JsPrivateBidResult =
    jsGuarded({ JsPrivateBidResult(null, it) }) {
        JsPrivateBidResult(PrivateBid.decode(rumor.asBoundRumor("kind:16 type=6 private bid")), null)
    }

// =================================================================================================
// The boundary types. Each holds the Kotlin value and publishes every value that value publishes,
// as strings, ints and arrays of strings — the same shape `JsListing` and `JsRumor` take.
// =================================================================================================

/**
 * §7.5's proposal as the page reads it: §8.3's split, §7.5's deadlines and §7.4's envelope.
 *
 * The fee crosses as all three of [feeMsat], [feeBasisPoints] and [feePayeeRequired], exactly as it
 * does on [JsOrder], because the three are different facts: the amount is §8.3's arithmetic, the
 * basis points are §8.1's signed term, and whether a fee payee is owed anything at all is §8.3's
 * deadlock rule. A page shown only the first cannot tell §8.1's absent term from a stated zero.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderProposal internal constructor(private val proposal: OrderProposal) {

    /** §7.2's author: the key the seal carrying this proposal was signed by, lowercase. */
    public val buyer: String get() = proposal.buyer

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = proposal.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(proposal.createdAt)

    /** §7.4's `order` id this proposal mints the order under. */
    public val orderIdHex: String get() = proposal.order.toHex()

    /** §7.5's `item` coordinate — the listing this proposal derives from (§4.2). */
    public val itemCoordinate: String get() = proposal.item.coordinate.toTagValue()

    /** §5.3's `item` quantity. Exactly `"1"` in Nenya v1; `ItemRef` refuses any other. */
    public val itemQuantity: String get() = proposal.item.quantity

    /** §8.2's `price_msat` — what the provider receives — as an exact decimal string. */
    public val priceMsat: String get() = JsDecimal.of(proposal.terms.split.price.millisatoshis)

    /** §8.3's fee, computed by this library's own arithmetic, as an exact decimal string. */
    public val feeMsat: String get() = JsDecimal.of(proposal.terms.split.fee.millisatoshis)

    /** §8.3's `total_msat` — price plus fee — as an exact decimal string. */
    public val totalMsat: String get() = JsDecimal.of(proposal.terms.split.total.millisatoshis)

    /** §8.1's fee in basis points, or `null` for an absent fee term. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? get() = when (val fee = proposal.terms.split.term) {
        is FeeTerm.Stated -> fee.basisPoints
        FeeTerm.Absent -> null
    }

    /** True exactly when §8.3's split requires a `payee=fee` invoice and receipt at all. */
    public val feePayeeRequired: Boolean get() = proposal.terms.split.feePayeeRequired

    /** §8.1's fee recipient — the third `fee` element `FeeTerm` does not hold — or `null`. */
    public val feeRecipient: String? get() = proposal.feeRecipient

    /** §5.3's `expiration`, unix seconds as a decimal string, or `null`. */
    public val expirationSeconds: String? get() = JsDecimal.ofOrNull(proposal.terms.expiration)

    /** §7.5's `deliver_by`, unix seconds as a decimal string, or `null`. */
    public val deliverBySeconds: String? get() = JsDecimal.ofOrNull(proposal.terms.deliverBy)

    /** §7.4's `p` rows, each `[pubkey]` or `[pubkey, relay]`. A fresh array on every read. */
    public val counterparties: Array<Array<String>>
        get() = proposal.counterparties.map { ref ->
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }.toTypedArray()

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public val event: JsWireEvent get() = JsWireEvent(proposal.encode())

    /** Names nothing: §12 items 1, 2 and 11 cover every value this type holds. */
    override fun toString(): String = "JsOrderProposal(redacted)"
}

/** What [decodeProposal] answered, with [kind] as the discriminator. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsProposalResult internal constructor(
    private val decoded: OrderProposal?,
    private val refusal: JsRefusal?,
) {

    /** `"proposal"` or `"refused"`. */
    public val kind: String get() = if (decoded != null) "proposal" else "refused"

    /** True exactly when [kind] is `"proposal"`. */
    public val ok: Boolean get() = decoded != null

    /** The proposal, when [ok]. */
    public val proposal: JsOrderProposal? get() = decoded?.let { JsOrderProposal(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §7.5's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsProposalResult(proposal)" else "JsProposalResult(refused: $reason)"
}

/**
 * §6.1's private bid as the page reads it: who bid, on which listing, and on what terms.
 *
 * The fee crosses as [feeMsat], [feeBasisPoints] and [feePayeeRequired], as on [JsOrderProposal] and
 * for the same reason: an absent fee term and a stated zero are different signed statements (§8.1).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsPrivateBid internal constructor(private val bid: PrivateBid) {

    /** §7.2's author: the key the seal carrying this bid was signed by, lowercase. */
    public val bidder: String get() = bid.bidder

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = bid.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(bid.createdAt)

    /** §6.1's `item` coordinate — the listing this bid names (§4.2). */
    public val itemCoordinate: String get() = bid.item.coordinate.toTagValue()

    /** §5.3's `item` quantity. Exactly `"1"` in Nenya v1; `ItemRef` refuses any other. */
    public val itemQuantity: String get() = bid.item.quantity

    /** §5.3's `price` in millisatoshis — what the provider receives (§8.2) — as an exact decimal string. */
    public val priceMsat: String get() = JsDecimal.of(bid.price.millisatoshis)

    /** §8.3's fee on that price, computed by this library's own arithmetic, as an exact decimal string. */
    public val feeMsat: String get() = JsDecimal.of(bid.split.fee.millisatoshis)

    /** §8.3's `total_msat` — price plus fee — as an exact decimal string. */
    public val totalMsat: String get() = JsDecimal.of(bid.split.total.millisatoshis)

    /** §8.1's fee in basis points, or `null` for an absent fee term. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? get() = when (val fee = bid.fee) {
        is FeeTerm.Stated -> fee.basisPoints
        FeeTerm.Absent -> null
    }

    /** True exactly when §8.3's split on these terms would require a `payee=fee` invoice at all. */
    public val feePayeeRequired: Boolean get() = bid.split.feePayeeRequired

    /** §8.1's fee recipient — the third `fee` element `FeeTerm` does not hold — or `null`. */
    public val feeRecipient: String? get() = bid.feeRecipient

    /** §5.3's `expiration` — the bid's own deadline (§4.6) — unix seconds as a decimal string, or `null`. */
    public val expirationSeconds: String? get() = JsDecimal.ofOrNull(bid.expiration)

    /** §5.3's `deliver_by`, unix seconds as a decimal string, or `null`. */
    public val deliverBySeconds: String? get() = JsDecimal.ofOrNull(bid.deliverBy)

    /** §7.4's `p` rows, each `[pubkey]` or `[pubkey, relay]`. A fresh array on every read. */
    public val counterparties: Array<Array<String>>
        get() = bid.counterparties.map { ref ->
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }.toTypedArray()

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public val event: JsWireEvent get() = JsWireEvent(bid.encode())

    /** Names nothing: §12 items 1, 2 and 11 cover every value this type holds. */
    override fun toString(): String = "JsPrivateBid(redacted)"
}

/** What [decodePrivateBid] answered, with [kind] as the discriminator. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsPrivateBidResult internal constructor(
    private val decoded: PrivateBid?,
    private val refusal: JsRefusal?,
) {

    /** `"private_bid"` or `"refused"`. */
    public val kind: String get() = if (decoded != null) "private_bid" else "refused"

    /** True exactly when [kind] is `"private_bid"`. Says it decoded — never that it binds anyone. */
    public val ok: Boolean get() = decoded != null

    /** The bid, when [ok]. */
    public val privateBid: JsPrivateBid? get() = decoded?.let { JsPrivateBid(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §6.1's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsPrivateBidResult(private_bid)" else "JsPrivateBidResult(refused: $reason)"
}

/**
 * §10.1's commitment as the page reads it: the four committed values, and where the blob is served.
 *
 * [url] is published and nothing in this library fetches it (§12 item 10, STOP RULE 13). §10.4's
 * download is the page's step under the page's own policy, and the alternative to publishing the
 * tag is every page parsing it itself — which is the decoding this type exists to do.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsCommitmentMessage internal constructor(
    private val message: DeliveryCommitmentMessage,
) {

    /**
     * §7.2's attribution: the key the seal carrying this commitment was signed by, lowercase.
     *
     * Whether that key is the provider's is the page's resolution and not this library's claim —
     * the same division [JsOrderEvent.fromParty] records one layer up.
     */
    public val sender: String get() = message.sender

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = message.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(message.createdAt)

    /** §7.4's `order` id this commitment is for. */
    public val orderIdHex: String get() = message.order.toHex()

    /** §10.1's `url` — where the provider says the encrypted blob is served from. */
    public val url: String get() = message.url

    /**
     * §10.1's four committed values, in the shape [stepOrder] already takes.
     *
     * The same type a page hands to `delivery_committed` and to `delivery_verified`, so a page that
     * decoded a commitment passes it straight on rather than copying four strings across.
     */
    public val commitment: JsCommitment
        get() = JsCommitment(
            message.commitment.x.toHex(),
            message.commitment.ox.toHex(),
            message.commitment.mimeType,
            JsDecimal.of(message.commitment.sizeBytes),
        )

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public val event: JsWireEvent get() = JsWireEvent(message.encode())

    /** Names nothing: the order id, `ox` and the blob URL are all §12 values. */
    override fun toString(): String = "JsCommitmentMessage(redacted)"
}

/** What [decodeCommitment] answered, with [kind] as the discriminator. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsCommitmentResult internal constructor(
    private val decoded: DeliveryCommitmentMessage?,
    private val refusal: JsRefusal?,
) {

    /** `"commitment"` or `"refused"`. */
    public val kind: String get() = if (decoded != null) "commitment" else "refused"

    /** True exactly when [kind] is `"commitment"`. */
    public val ok: Boolean get() = decoded != null

    /** The commitment, when [ok]. */
    public val commitment: JsCommitmentMessage? get() = decoded?.let { JsCommitmentMessage(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §10.1's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsCommitmentResult(commitment)" else "JsCommitmentResult(refused: $reason)"
}

/**
 * §10.3's release as the page reads it — without the key, for the reason [decodeRelease] records.
 *
 * [release]'s `fileType` stays nullable, because §10.3 requires the identity check **not** be
 * skipped when the tag is absent: an absent `file-type` is a divergence from the commitment's `m`
 * rather than a malformed release, and the decoder carries the absence rather than refusing it.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsReleaseMessage internal constructor(private val message: DeliverableReleaseMessage) {

    /** §7.2's attribution: the key the seal carrying this release was signed by, lowercase. */
    public val sender: String get() = message.sender

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = message.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(message.createdAt)

    /** §7.4's `order` id this release claims to be about. */
    public val orderIdHex: String get() = message.order.toHex()

    /** §10.3's four compared values, in the shape [stepOrder] already takes. */
    public val release: JsRelease
        get() = JsRelease(
            message.release.x.toHex(),
            message.release.ox.toHex(),
            message.release.fileType,
            JsDecimal.of(message.release.sizeBytes),
        )

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public val event: JsWireEvent get() = JsWireEvent(message.encode())

    /** Names nothing — and in particular neither the key nor the nonce, which this type never held. */
    override fun toString(): String = "JsReleaseMessage(redacted)"
}

/** What [decodeRelease] answered, with [kind] as the discriminator. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsReleaseResult internal constructor(
    private val decoded: DeliverableReleaseMessage?,
    private val refusal: JsRefusal?,
) {

    /** `"release"` or `"refused"`. */
    public val kind: String get() = if (decoded != null) "release" else "refused"

    /** True exactly when [kind] is `"release"`. */
    public val ok: Boolean get() = decoded != null

    /** The release, when [ok]. */
    public val release: JsReleaseMessage? get() = decoded?.let { JsReleaseMessage(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §10.3's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input, a key or a nonce (§12 item 11, STOP RULE 14). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsReleaseResult(release)" else "JsReleaseResult(refused: $reason)"
}

/**
 * §7.4's status update as the page reads it: who sealed it, which order, and the state it **claims**.
 *
 * The four terms it repeats are not published one by one. `OrderStatusMessage` holds them only for
 * §7.6's comparison and publishes none, so a getter for one here would be a value the Kotlin type does
 * not have — and a page that compared them itself would be the second rulebook [checkAcceptance]
 * exists to remove. The whole rumor is still on [event].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsStatusUpdate internal constructor(private val message: OrderStatusMessage) {

    /** §7.2's attribution: the key the seal carrying this update was signed by, lowercase. */
    public val sender: String get() = message.sender

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = message.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(message.createdAt)

    /** §7.4's `order` id this update claims to be about. */
    public val orderIdHex: String get() = message.order.toHex()

    /** The state this update **claims**, as `OrderState.name`. A claim, never a fact. */
    public val status: String get() = message.status.name

    /**
     * §11.1's wire token for [status], or `null` for the unknown sink. See [JsOrder.stateToken] on
     * why the sink has no token.
     */
    public val statusToken: String? get() = message.status.token

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public val event: JsWireEvent get() = JsWireEvent(message.encode())

    /** Names the claimed state only, as `OrderStatusMessage` does. */
    override fun toString(): String = "JsStatusUpdate(status=$status)"
}

/** What [decodeStatusUpdate] answered, with [kind] as the discriminator. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsStatusUpdateResult internal constructor(
    private val decoded: OrderStatusMessage?,
    private val refusal: JsRefusal?,
) {

    /** `"status_update"` or `"refused"`. */
    public val kind: String get() = if (decoded != null) "status_update" else "refused"

    /** True exactly when [kind] is `"status_update"`. Says it decoded — never that it was accepted. */
    public val ok: Boolean get() = decoded != null

    /** The update, when [ok]. */
    public val statusUpdate: JsStatusUpdate? get() = decoded?.let { JsStatusUpdate(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §7.4's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsStatusUpdateResult(status_update)" else "JsStatusUpdateResult(refused: $reason)"
}
