package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.RumorBuild
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.delivery.DeliveryException
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamException
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.PaymentBuild
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * §7.4's envelope, as strings and arrays: the five fields every private-channel rumor carries
 * whatever its kind.
 *
 * One field per `RumorEnvelope` field, with the same defaults. `counterparties` crosses as §5.3's
 * `p` rows rather than as `PubkeyRef`s, on the same terms as [JsAuthoredListing.pubkeyRefs].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsRumorEnvelope public constructor(

    /** §4.1's `pubkey`: the sender's x-only key, 64 hex characters. */
    public val authorPubkey: String,

    /** §4.1's `created_at`, unix seconds as a decimal string (§4.6: the caller's clock). */
    public val createdAtSeconds: String,

    /** §7.4's `p` rows, each `[pubkey]` or `[pubkey, relay]`. */
    public val counterparties: Array<Array<String>> = emptyArray(),

    /** §4.1's `content`. */
    public val content: String = "",

    /** §4.5's `nenya` major version. */
    public val nenyaVersion: Int = dev.eryalabs.nenya.NenyaProtocol.VERSION,

    /** Rows §7.4 gives no vocabulary for, passed through as extensions. */
    public val extraTags: Array<Array<String>> = emptyArray(),
) {

    /** Names nothing: §12 item 2 keeps a counterparty pubkey out of a diagnostic. */
    override fun toString(): String = "JsRumorEnvelope(redacted)"
}

/**
 * §8.3's split and §7.5's two deadlines, as strings.
 *
 * `OrderTerms` holds a `FeeSplit`, whose constructor is `internal` so §8.3's arithmetic is the only
 * way to one. This object therefore carries the *inputs* to that arithmetic — a price and a fee term
 * — and `OrderTerms.of` computes the split, which is the same call the JVM side makes. A caller
 * cannot hand in a price, a fee and a total that disagree, because it never hands in a total.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderTerms public constructor(

    /** §8.2's `price_msat`, in millisatoshis, as an exact decimal string. */
    public val priceMsat: String,

    /** §8.1's fee in basis points, or `null` for `FeeTerm.Absent`. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? = null,

    /** §5.3's `expiration`, unix seconds as a decimal string. */
    public val expirationSeconds: String? = null,

    /** §7.5's `deliver_by`, unix seconds as a decimal string. */
    public val deliverBySeconds: String? = null,
) {

    /** Names nothing: a price is a §12 item 11 value. */
    override fun toString(): String = "JsOrderTerms(redacted)"
}

/** §10.1's commitment to a deliverable, as strings. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsCommitment public constructor(

    /** §10.1's `x`: the SHA-256 of the encrypted bytes, 64 hex characters. */
    public val xHex: String,

    /** §10.1's `ox`: the SHA-256 of the plaintext bytes, 64 hex characters. */
    public val oxHex: String,

    /** §10.1's `m`: the deliverable's media type. */
    public val mimeType: String,

    /** §10.1's `size`, in bytes, as an exact decimal string — a file exceeds 2^53 bytes in principle. */
    public val sizeBytes: String,
) {

    override fun toString(): String = "JsCommitment(redacted)"
}

/**
 * §10.3's release of a deliverable, as strings.
 *
 * One field per `DeliverableRelease` field, in that order, and — like that class — **no field has a
 * default**. That is deliberate rather than an omission. `DeliverableRelease.sizeBytes` is a required
 * `Long`, so a JVM caller cannot omit a size; a default here would let a page omit one and put
 * `["size", "0"]` on the wire as a positive claim about a file the caller never measured. It is the
 * same objection [crossedPayee] records against defaulting an unknown `payee` token to `provider`:
 * this boundary does not choose a wire value on the caller's behalf. §10.3's identity check would
 * catch the mismatch at the far end, but a release that lies about its own size should not be
 * reachable by forgetting an argument.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsRelease public constructor(

    /** §10.3's `x`, 64 hex characters. */
    public val xHex: String,

    /** §10.3's `ox`, 64 hex characters. */
    public val oxHex: String,

    /**
     * §10.3's `file-type`, or `null`.
     *
     * Stays nullable, because §10.3 requires the identity check **not** be skipped when the tag is
     * absent: an absent `file-type` is a divergence from the commitment's `m` rather than a
     * malformed release, and refusing it here would make that case reachable only from a hostile
     * peer. `DeliverableRelease` says the same thing and this field is that field. Nullable but
     * **not** defaulted: absence is a thing a caller states, not a thing it falls into.
     */
    public val fileType: String?,

    /** §10.3's `size`, in bytes, as an exact decimal string. */
    public val sizeBytes: String,
) {

    override fun toString(): String = "JsRelease(redacted)"
}

// =================================================================================================
// The six §7.4 messages T41 added. Each one call to `RumorWriter`, over translated arguments.
// =================================================================================================

/** §7.4's `kind:14` chat message, through `RumorWriter.chat`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildChat(envelope: JsRumorEnvelope, orderIdHex: String? = null): JsBuildResult = jsBuild {
    RumorWriter.chat(envelope.asRumorEnvelope(), orderIdHex?.let { OrderId.ofHex(it) }).asJsBuildResult()
}

/** §7.5's `kind:16` `type=1` order proposal, through `RumorWriter.proposal`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildProposal(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    itemCoordinate: String,
    terms: JsOrderTerms,
    feeRecipient: String? = null,
    amountSat: String? = null,
): JsBuildResult = jsBuild {
    RumorWriter.proposal(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        item = ItemRef(Coordinate.parse(itemCoordinate)),
        terms = terms.asOrderTerms(),
        feeRecipient = feeRecipient,
        amountSat = crossedNumberOrNull("amount", amountSat),
    ).asJsBuildResult()
}

/** §11.1's `kind:16` `type=3` status update, through `RumorWriter.statusUpdate`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildStatusUpdate(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    statusToken: String,
    itemCoordinate: String? = null,
    terms: JsOrderTerms? = null,
    feeRecipient: String? = null,
): JsBuildResult = jsBuild {
    RumorWriter.statusUpdate(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        // `OrderStatusCodec.read` answers UNKNOWN for a token §11.1 has no rule for, and the
        // writer's own refusal is what reports it. No vocabulary is restated here.
        status = OrderStatusCodec.read(statusToken),
        item = itemCoordinate?.let { ItemRef(Coordinate.parse(it)) },
        terms = terms?.asOrderTerms(),
        feeRecipient = feeRecipient,
    ).asJsBuildResult()
}

/** §10.1's `kind:16` `type=5` delivery commitment, through `RumorWriter.commitment`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildCommitment(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    commitment: JsCommitment,
    url: String,
): JsBuildResult = jsBuild {
    RumorWriter.commitment(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        commitment = commitment.asDeliverableCommitment(),
        url = url,
    ).asJsBuildResult()
}

/** §10.3's `kind:15` file-message release, through `RumorWriter.release`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildRelease(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    release: JsRelease,
    decryptionKeyHex: String,
    decryptionNonceHex: String,
): JsBuildResult = jsBuild {
    RumorWriter.release(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        release = release.asDeliverableRelease(),
        decryptionKey = decryptionKeyHex,
        decryptionNonce = decryptionNonceHex,
    ).asJsBuildResult()
}

/**
 * §6.1's `kind:16` `type=6` private bid, through `RumorWriter.privateBid`.
 *
 * Takes no order id, and that is §6.1's rule rather than an omission: no order exists until the
 * buyer proposes, and an `["order", …]` crossed as an extra tag is refused as
 * `ChannelRejection.FORBIDDEN_ORDER_TAG` by the writer.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildPrivateBid(
    envelope: JsRumorEnvelope,
    itemCoordinate: String,
    terms: JsOrderTerms,
    feeRecipient: String? = null,
): JsBuildResult = jsBuild {
    RumorWriter.privateBid(
        envelope = envelope.asRumorEnvelope(),
        item = ItemRef(Coordinate.parse(itemCoordinate)),
        terms = terms.asOrderTerms(),
        feeRecipient = feeRecipient,
    ).asJsBuildResult()
}

// =================================================================================================
// The two settlement messages T41 added.
// =================================================================================================

/** §8.6's `kind:16` `type=2` payment request, through `PaymentWriter.paymentRequest`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildPaymentRequest(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    payeeToken: String,
    invoice: String,
    feeRecipient: String? = null,
    mediumToken: String = PaymentMedium.LIGHTNING.token!!,
    terms: JsOrderTerms? = null,
): JsBuildResult = jsBuild {
    PaymentWriter.paymentRequest(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        payee = crossedPayee(payeeToken),
        invoice = Bolt11Reference.recognise(invoice),
        feeRecipient = feeRecipient,
        medium = PaymentMedium.of(mediumToken),
        terms = terms?.asOrderTerms(),
    ).asJsBuildResult()
}

/** §9.2's `kind:17` payment receipt, through `PaymentWriter.receipt`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildReceipt(
    envelope: JsRumorEnvelope,
    orderIdHex: String,
    payeeToken: String,
    reference: String,
    proof: String,
    feeRecipient: String? = null,
    mediumToken: String = PaymentMedium.LIGHTNING.token!!,
    terms: JsOrderTerms? = null,
): JsBuildResult = jsBuild {
    PaymentWriter.receipt(
        envelope = envelope.asRumorEnvelope(),
        order = OrderId.ofHex(orderIdHex),
        payee = crossedPayee(payeeToken),
        reference = reference,
        proof = proof,
        feeRecipient = feeRecipient,
        medium = PaymentMedium.of(mediumToken),
        terms = terms?.asOrderTerms(),
    ).asJsBuildResult()
}

// =================================================================================================
// Translation. Every nested value is built by this library's own constructor, so each refusal is
// the one a JVM caller gets for the same input.
// =================================================================================================

/** `RumorBuild`'s two cases, flattened to [JsBuildResult]'s fields. */
internal fun RumorBuild.asJsBuildResult(): JsBuildResult = when (this) {
    is RumorBuild.Built -> jsBuilt(event)
    is RumorBuild.Refused -> jsRefused(reason.name, "ChannelRejection", tag, null, detail)
}

/**
 * `PaymentBuild`'s two cases.
 *
 * `Refused` publishes exactly one of a `SettlementRejection` and a `ChannelRejection`, and which
 * one says which layer refused — §8.6's and §9.2's own rules, or §7.4's envelope coming back from
 * `RumorWriter`. [JsBuildResult.reasonVocabulary] is how that survives the crossing instead of
 * being flattened into one string a caller cannot branch on.
 */
internal fun PaymentBuild.asJsBuildResult(): JsBuildResult = when (this) {
    is PaymentBuild.Built -> jsBuilt(event)
    is PaymentBuild.Refused -> when {
        reason != null -> jsRefused(reason!!.name, "SettlementRejection", tag, null, detail)
        else -> jsRefused(channelReason?.name, "ChannelRejection", tag, null, detail)
    }
}

/** This envelope as the `RumorEnvelope` the writers take. */
internal fun JsRumorEnvelope.asRumorEnvelope(): RumorEnvelope = RumorEnvelope(
    authorPubkey = authorPubkey,
    createdAt = crossedSeconds("created_at", createdAtSeconds),
    counterparties = counterparties.map { it.asCrossedPubkeyRef() },
    content = content,
    nenyaVersion = nenyaVersion,
    extraTags = extraTags.asTagRows(),
)

/** These terms as the `OrderTerms` the writers take, through §8.3's own arithmetic. */
internal fun JsOrderTerms.asOrderTerms(): OrderTerms = OrderTerms.of(
    price = Msat.ofMsat(crossedAmount("price", priceMsat)),
    fee = feeBasisPoints?.let { FeeTerm.of(it) } ?: FeeTerm.Absent,
    expiration = crossedSecondsOrNull("expiration", expirationSeconds),
    deliverBy = crossedSecondsOrNull("deliver_by", deliverBySeconds),
)

/** This commitment as §10.1's `DeliverableCommitment`, through `DeliverableHash.ofHex`. */
internal fun JsCommitment.asDeliverableCommitment(): DeliverableCommitment = DeliverableCommitment(
    x = DeliverableHash.ofHex(xHex),
    ox = DeliverableHash.ofHex(oxHex),
    mimeType = mimeType,
    sizeBytes = crossedNumber("size", sizeBytes),
)

/** This release as §10.3's `DeliverableRelease`. */
internal fun JsRelease.asDeliverableRelease(): DeliverableRelease = DeliverableRelease(
    x = DeliverableHash.ofHex(xHex),
    ox = DeliverableHash.ofHex(oxHex),
    fileType = fileType,
    sizeBytes = crossedNumber("size", sizeBytes),
)

/** §5.3's `p` row through `PubkeyRef`'s own constructor. */
internal fun Array<String>.asCrossedPubkeyRef(): PubkeyRef = when (size) {
    1 -> PubkeyRef(this[0])
    2 -> PubkeyRef(this[0], this[1])
    else -> throw JsCrossing(
        JsCrossing.WRONG_ROW_ARITY,
        "p",
        "§5.3 encodes a `p` row as [pubkey] or [pubkey, relay]; this one crossed with $size element(s)",
    )
}

/**
 * §8.6's `payee` token as the `Payee` it names.
 *
 * `Payee` is an enum of exactly two constants and §8.6 prints both tokens, so an unrecognised token
 * has no `Payee` to be. It is a [JsCrossing] rather than a §8.6 refusal for the reason that class
 * records: there is no value to hand `PaymentWriter`, and inventing one — defaulting to `provider`,
 * say — would put a payee on the wire the caller never named.
 */
internal fun crossedPayee(token: String): Payee =
    Payee.entries.firstOrNull { it.token == token } ?: throw JsCrossing(
        JsCrossing.UNKNOWN_PAYEE_TOKEN,
        "payee",
        "§8.6 prints exactly two `payee` tokens, `${Payee.PROVIDER.token}` and `${Payee.FEE.token}`; " +
            "the token crossed is neither, and this boundary does not choose one on the caller's behalf",
    )
