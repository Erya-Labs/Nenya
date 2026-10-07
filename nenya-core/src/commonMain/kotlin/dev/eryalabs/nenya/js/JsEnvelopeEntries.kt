package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.envelope.GiftWrap
import dev.eryalabs.nenya.envelope.OpenedMessage
import dev.eryalabs.nenya.envelope.OutgoingWrap
import dev.eryalabs.nenya.envelope.SealedMessage
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// §7.1's two procedures, crossed. The half of decision P's facade that turns the page from a board
// into a trade: everything below here speaks in private messages, and a private message is the one
// thing a client cannot assemble out of the thirteen entry points T42 added.
//
// Both entry points take their seams through T42's `JsSigner` and `JsEnvironment` adapters and add
// no plug-in of their own. Neither takes a `WireLimits` or a `TagLimits`, which is T42's choice
// restated rather than a new one: `decodeListing` and `decodeBid` take none either, so every entry
// point in this package makes the call a JVM caller makes with §4.3's defaults. §4.3 says those
// bounds SHOULD be configurable and this library does make them so — through the Kotlin API, which
// is where a client that needs other bounds reaches for them. A crossing that offered them would be
// offering a page the means to widen what it parses, and the page is the one party §13 says Nenya
// does not defend its user against.
// =================================================================================================

/**
 * §7.1's write procedure: one rumor sealed and gift-wrapped twice, or the refusal as a value.
 *
 * The facade performs **no check of its own**. Every refusal reported is `GiftWrap.seal`'s own
 * `EnvelopeRejection` with side `SEAL`, reached by the same call a JVM caller makes, over the same
 * rumor — which is the Kotlin `WireEvent` one of T42's eleven builders produced, held on
 * [JsWireEvent] and never re-parsed. §7.1 step 1's "the **true** `created_at`" is therefore the one
 * the builder was given, and this crossing cannot have changed it: it never reads it.
 *
 * ### Nothing here is published, and nothing here is signed by this library
 *
 * The two copies come back as JSON ready to hand to a relay transport, and [JsSealResult] is
 * all-or-nothing exactly as `SealedMessage` is: a page cannot publish a recipient copy whose
 * matching self copy failed, because a failure produces no copies at all (§7.1 step 4).
 *
 * @param rumor §7.1 step 1's unsigned rumor, as [JsWireEvent] — the output of `buildChat`,
 *   `buildProposal`, `buildCommitment`, `buildRelease`, `buildPrivateBid`, `buildStatusUpdate`,
 *   `buildPaymentRequest` or `buildReceipt`. Its `pubkey` MUST be the sender's own (§7.2).
 * @param recipientPubkey the recipient's x-only key, 64 lowercase hex characters (§4.3).
 * @param sender the sender's real signer. It seals (§7.1 step 2) and nothing else.
 * @param environment §7.1 step 5's clock and randomness, §7.1 step 3's one-time signer, and the
 *   BIP-340 verifier. Each is independently absent and an absent one fails closed for that seam
 *   alone, exactly as the library default it stands in for does — so a page that supplies no
 *   `freshSigner` gets `EPHEMERAL_SIGNER_UNAVAILABLE` and not a wrap signed by somebody's identity.
 * @param recipientRelayHint §7.1 step 3's optional relay URL, emitted verbatim or omitted entirely.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun seal(
    rumor: JsWireEvent,
    recipientPubkey: String,
    sender: JsSigner,
    environment: JsEnvironment = JsEnvironment(),
    recipientRelayHint: String? = null,
): JsSealResult = jsGuarded({ JsSealResult(null, it) }) {
    JsSealResult(
        GiftWrap.seal(
            rumor = rumor.event,
            recipientPubkey = recipientPubkey,
            sender = sender.asSigner(),
            ephemeral = environment.asEphemeralSigners(),
            clock = environment.asClock(),
            randomness = environment.asRandomness(),
            secp = environment.asSecp(),
            recipientRelayHint = recipientRelayHint,
        ),
        null,
    )
}

/**
 * §7.1's read procedure, in §7.1's order, ending in §7.2's rule — or the refusal as a value.
 *
 * Every byte of [wrapJson] is hostile: it came off a relay, from a stranger. Nothing about that
 * changes here, and nothing about the order of §7.1's checks is decided here either — this function
 * makes one call, and the order, the "a rejection at any step stops the read" rule and the two
 * conditional signature steps are all `GiftWrap.open`'s. In particular the `p` tag is still checked
 * before the first decrypt call, so a wrap addressed to somebody else costs the page's signer
 * nothing at all.
 *
 * ### No timestamp is checked, here least of all
 *
 * §4.6 and §7.1 both forbid it, and [open] takes **no clock** — the `environment` below is read for
 * its verifier alone. A page that wanted a freshness rule would have to write one, and §7.1 says
 * why it must not: write step 5 deliberately produces timestamps up to two days old.
 *
 * @param wrapJson the `kind:1059` gift wrap as §7.1's JSON object form.
 * @param me the reader's own signer. Asked for its public key and for the two NIP-44 decryptions,
 *   never for a signature.
 * @param environment read for [JsEnvironment.verifySchnorr] only. An absent verifier is §17's
 *   not-performed answer and opens the message with both signature checks recorded
 *   `NOT_CHECKED` — never as a negative result, and never as grounds to discard it.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun open(
    wrapJson: String,
    me: JsSigner,
    environment: JsEnvironment = JsEnvironment(),
): JsOpenResult = jsGuarded({ JsOpenResult(null, it) }) {
    JsOpenResult(GiftWrap.open(wrapJson = wrapJson, me = me.asSigner(), secp = environment.asSecp()), null)
}

// =================================================================================================
// The boundary types. Each holds the Kotlin value and publishes strings, ints and arrays of
// strings — the same shape `JsListing` and `JsBid` take, and for the same reason.
// =================================================================================================

/**
 * One outgoing `kind:1059` gift wrap: its JSON, its recomputed id, and the key it is addressed to.
 *
 * **There is no accessor for the throwaway key**, and that is `OutgoingWrap`'s own design rather
 * than an economy here: §7.2 says the gift wrap's `pubkey` is random, carries no identity, and
 * implementations MUST NOT display it, index by it, or use it in any comparison. A rule expressed
 * as a prohibition needs a shape that cannot break it, so the key that signed this wrap is
 * reachable only by parsing [json] — which is a thing a page would have to do on purpose, and which
 * this library does nowhere.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOutgoingWrap internal constructor(private val wrap: OutgoingWrap) {

    /** §7.1's object form of the signed `kind:1059`, ready to hand to a relay transport. */
    public val json: String get() = wrap.json

    /** The wrap's id, recomputed by this library from its own five fields (§4.1), not claimed. */
    public val idHex: String get() = wrap.id.toHex()

    /** The key this copy is encrypted and addressed to, in §4.3's lowercase hex. */
    public val addressee: String get() = wrap.addressee

    /** Names neither the addressee nor the id: both are §12 item 2 values. */
    override fun toString(): String = "JsOutgoingWrap(redacted)"
}

/**
 * What [seal] answered: §7.1 step 4's two copies, or the refusal, with [kind] as the discriminator.
 *
 * All or nothing. [toRecipient] and [toSelf] are both present or both absent, because
 * `SealedMessage` is: §7.1 step 4 produces two copies or none, so a page cannot publish one half of
 * a message whose other half failed.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsSealResult internal constructor(
    private val sealed: SealedMessage?,
    private val refusal: JsRefusal?,
) {

    /** `"sealed"` or `"refused"`. */
    public val kind: String get() = if (sealed != null) "sealed" else "refused"

    /** True exactly when [kind] is `"sealed"`. */
    public val ok: Boolean get() = sealed != null

    /** The copy addressed to the recipient, when [ok]. */
    public val toRecipient: JsOutgoingWrap? get() = sealed?.let { JsOutgoingWrap(it.toRecipient) }

    /** The copy addressed to the sender's own key, so the sender can reconstruct the thread. */
    public val toSelf: JsOutgoingWrap? get() = sealed?.let { JsOutgoingWrap(it.toSelf) }

    /**
     * §17's statement of what was **not** checked while producing this message, as
     * `SeamCapability.name`s.
     *
     * Empty when the injected verifier answered a verdict for all four signatures this library
     * emitted; `["BIP340_VERIFICATION"]` when it answered unavailable for any of them. §17 permits
     * omitting BIP-340 verification and forbids reporting an unverified thing as verified, and
     * requires the statement be machine-readable rather than a paragraph in a README — so a page
     * that must know which of the two it is holding reads this rather than inferring it from the
     * existence of the value. A fresh array on every read.
     */
    public val notPerformedHere: Array<String>
        get() = sealed?.notPerformedHere?.map { it.name }?.toTypedArray() ?: emptyArray()

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `EnvelopeRejection` for §7.1's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The field the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§7.2, §12 item 11, STOP RULE 14). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsSealResult(sealed)" else "JsSealResult(refused: $reason)"
}

/**
 * §7.4's rumor, attributed by §7.2's rule and decoded by §7.4's — what [open] actually hands back.
 *
 * Holds the Kotlin `AttributedRumor` internally, which is what lets [verifySettlement],
 * [verifyFeeReceipt] and `JsOrderEvent`'s §7.5 and §8.6 variants take an opened message **directly**
 * rather than re-reading this package's own output. A second parse is a second chance to disagree
 * with the first, and §9.2's evidence rules are the last place in this library where two readings of
 * one message could be allowed to diverge.
 *
 * ### `event` is the rumor as it arrived, and it is not a rumor this library will seal
 *
 * [event] is `AttributedRumor.encode()`'s answer: §7.4's own re-emission of what was decoded. It is
 * published so a page can display the message and so the equality proof has §4.1's canonical form
 * to compare, and §7.1's write path would refuse it from this reader anyway — its `pubkey` is the
 * counterparty's, not the reader's (`RUMOR_NOT_SENDERS`).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsRumor internal constructor(internal val rumor: AttributedRumor) {

    /**
     * §7.2's author: the key the **seal** claimed, which is what binds every term below to a key.
     *
     * Never the gift wrap's `pubkey`, which §7.2 says carries no identity at all.
     */
    public val author: String get() = rumor.author

    /** §4.1's `id`, recomputed by this library from the rumor's own five fields. */
    public val idHex: String get() = rumor.id.toHex()

    /** §4.1's `created_at`, unix seconds as a decimal string. A counterparty's claim (§4.6). */
    public val createdAtSeconds: String get() = JsDecimal.of(rumor.createdAt)

    /** §4.1's `content`. */
    public val content: String get() = rumor.content

    /** §7.4's row this rumor is, as `RumorKind.name` — `CHAT`, `FILE_MESSAGE`, … */
    public val kind: String get() = rumor.kind.name

    /** The nostr `kind` number §7.4's table gives that row: 14, 15, 16 or 17. */
    public val kindNumber: Int get() = rumor.kind.kind

    /**
     * §7.2's attribution, as `Attribution.name`.
     *
     * `SIGNATURE_VERIFIED` exactly when the seal's signature was checked and verified;
     * `AUTHENTICATED_BY_DECRYPTION` otherwise — never on the strength of the **wrap's** signature,
     * whatever [JsOpenResult.wrapSignature] says. §17 and §7.2 both require a UI distinguish the
     * two, "because a single label covering both claims the stronger property for the weaker case",
     * which is why this crosses as its own value and not as a boolean called `verified`.
     */
    public val attribution: String get() = rumor.attribution.name

    /** §17's statement of what the channel codec itself did not do, as `SeamCapability.name`s. */
    public val notPerformedHere: Array<String>
        get() = rumor.notPerformedHere.map { it.name }.toTypedArray()

    /** §4.5's `nenya` major version, or `null` where the rumor carries no `nenya` tag. */
    public val nenyaVersion: Int? get() = rumor.nenyaVersion

    /** §7.4's `p` rows, each `[pubkey]` or `[pubkey, relay]`. A fresh array on every read. */
    public val counterparties: Array<Array<String>>
        get() = rumor.counterparties.map { ref ->
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }.toTypedArray()

    /** The tags §7.4 gives no row for, as they appeared. */
    public val unknownTags: Array<Array<String>> get() = rumor.unknownTags.asTagArray()

    /**
     * §7.4's `type` on a bound rumor, as `OrderMessageKind.name`, or `null` for a `kind:14` chat.
     *
     * `null` for a chat rather than a token of its own: §7.4 puts `kind:14` outside the
     * required-tag rule and says an implementation "MUST NOT derive anything from its presence or
     * absence", so there is no `type` for one to carry.
     */
    public val messageType: String?
        get() = (rumor as? AttributedRumor.Bound)?.type?.name

    /** §7.4's `order` id on a bound rumor, or `null` — a `type=6` private bid carries none (§6.1). */
    public val orderIdHex: String?
        get() = (rumor as? AttributedRumor.Bound)?.order?.toHex()

    /** §7.4's re-emission of the decoded rumor. See this class's note. */
    public val event: JsWireEvent get() = JsWireEvent(rumor.encode())

    /** Names the kind and the attribution. §12 item 2 keeps the author and the order id out. */
    override fun toString(): String = "JsRumor(kind=$kind, attribution=$attribution)"
}

/**
 * What [open] answered: the opened message, or the refusal, with [kind] as the discriminator.
 *
 * ### There is no accessor for the wrap's `pubkey`, and that is the point
 *
 * §7.2: "The gift wrap's `pubkey` is random and carries **no** identity. Implementations MUST NOT
 * display it, index by it, or use it in any comparison." So it is not on this type, exactly as it
 * is not on `OpenedMessage`. `GiftWrap.open` uses it for two things — a NIP-44 counterparty and the
 * key the wrap's own signature is checked against — and then drops it.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOpenResult internal constructor(
    private val opened: OpenedMessage?,
    private val refusal: JsRefusal?,
) {

    /** `"opened"` or `"refused"`. */
    public val kind: String get() = if (opened != null) "opened" else "refused"

    /** True exactly when [kind] is `"opened"`. */
    public val ok: Boolean get() = opened != null

    /** The rumor, attributed by §7.2's rule and decoded by §7.4's, when [ok]. */
    public val rumor: JsRumor? get() = opened?.let { JsRumor(it.rumor) }

    /**
     * §7.1 read step 6's verdict on the `kind:13` seal's signature: `VERIFIED` or `NOT_CHECKED`.
     *
     * **There is no third value, and that absence is the design.** §7.1 says an implementation
     * that verifies MUST reject on a bad signature, so an invalid verdict produces no opened
     * message at all — it is the `SEAL_SIGNATURE_INVALID` refusal, and a page cannot be handed a
     * message carrying a signature this library checked and refused.
     */
    public val sealSignature: String? get() = opened?.sealSignature?.name

    /**
     * §7.1 read step 4's verdict on the `kind:1059` wrap's signature.
     *
     * Published, and deliberately not folded into [sealSignature] or into the attribution: §7.2
     * says a valid one "says only that the wrap reached the reader unaltered. It attributes
     * nothing, it does not corroborate the seal, and an implementation MUST NOT report a message
     * as signature-verified on the strength of it."
     */
    public val wrapSignature: String? get() = opened?.wrapSignature?.name

    /**
     * The wrap's `id`, recomputed here from its own five fields (§4.1).
     *
     * Published for **de-duplication**, which a page needs because NIP-17 publishes one wrap to
     * several relays and §4.6 forbids ordering the thread by anything the wrap claims about time.
     * The wrap's id and not the rumor's: two wraps of one rumor are two messages to discard one of.
     */
    public val wrapIdHex: String? get() = opened?.wrapId?.toHex()

    /** §17's statement of what was not checked while opening this message. See [JsSealResult]. */
    public val notPerformedHere: Array<String>
        get() = opened?.notPerformedHere?.map { it.name }?.toTypedArray() ?: emptyArray()

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `EnvelopeRejection` for §7.1's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The field the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input, a ciphertext or a key (§7.2, §12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsOpenResult(opened)" else "JsOpenResult(refused: $reason)"
}

/**
 * This rumor as the §7.4 **bound** half every decoder below it takes, or the crossing's refusal.
 *
 * `AttributedRumor` is sealed over `Chat` and `Bound` and `PaymentReceipt.decode`,
 * `PaymentRequest.decode`, `OrderProposal.decode` and `OrderStatusMessage.decode` all take `Bound`.
 * So this is the one place a `kind:14` can be turned away, and [JsCrossing.NOT_A_BOUND_RUMOR]
 * records why that is the crossing's answer rather than a §9.2 or §7.5 refusal of this layer's own
 * invention.
 */
internal fun JsRumor.asBoundRumor(what: String): AttributedRumor.Bound =
    rumor as? AttributedRumor.Bound ?: throw JsCrossing(
        JsCrossing.NOT_A_BOUND_RUMOR,
        what,
        "§7.4 divides a rumor into the bound kinds — 15, 16 and 17, each carrying the required " +
            "tags that bind it to an order — and `kind:14` chat, which it places outside that rule " +
            "in so many words; this library's decoders take the bound half, so a chat rumor is not " +
            "a value any of them can be handed, and this boundary does not decide on its behalf " +
            "what a chat would have meant as a $what",
    )
