package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.delivery.DeliveryException
import dev.eryalabs.nenya.delivery.DeliveryRejection
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.readTimestamp
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * §10.1's and §10.3's own tag names, kept **out** of `NenyaTags` for the reason [ChannelTags] is.
 *
 * `NenyaTags` is held *equal* to §5.3's table parsed at test time, and not one of the names below is
 * a row in it — they are §10's, exactly as `order` and `type` are §7.4's and `payee` and `payment`
 * are §8.6's. Adding one to `NenyaTags` would turn `TagVocabularyTest` red for a reason that has
 * nothing to do with the tag.
 *
 * `m` is the exception and is deliberately absent from this object: it **is** a §5.3 row, decoded by
 * T9's Encoding column wherever it appears, and this package reads it through `TagSet.mimeType`
 * rather than re-reading the tag. §10.3 makes the `m`/`file-type` mapping normative precisely
 * because the two are differently named, so [FILE_TYPE] is here and `m` is not.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public object DeliveryTags {

    /** §10.1's `["url", "<https url>"]` — where the encrypted blob is served from. */
    public const val URL: String = "url"

    /** §10.1's and §10.3's `["x", "<sha256 hex>"]` — the SHA-256 of the encrypted bytes as served. */
    public const val SERVED_HASH: String = "x"

    /** §10.1's and §10.3's `["ox", "<sha256 hex>"]` — the SHA-256 of the plaintext before encryption. */
    public const val PLAINTEXT_HASH: String = "ox"

    /** §10.1's and §10.3's `["size", "<decimal>"]` — the length in bytes of the **encrypted** blob. */
    public const val SIZE: String = "size"

    /** §10.2's `["encryption-algorithm", "aes-gcm"]`, which is the only value v1 permits. */
    public const val ENCRYPTION_ALGORITHM: String = "encryption-algorithm"

    /** §10.2's `["decryption-key", …]`. Checked here, published by nothing (§12 item 11). */
    public const val DECRYPTION_KEY: String = "decryption-key"

    /** §10.2's `["decryption-nonce", …]`. Checked here, published by nothing (§12 item 11). */
    public const val DECRYPTION_NONCE: String = "decryption-nonce"

    /** §10.3's `["file-type", "<mime>"]` — the release's name for the commitment's `m` (§10.3). */
    public const val FILE_TYPE: String = "file-type"

    /** §10.2: "`encryption-algorithm` MUST be `aes-gcm` in v1". The only token either message may carry. */
    public const val AES_GCM: String = "aes-gcm"

    /** §5.3's `m` row, which the commitment carries and which §10.3 forbids looking for on a release. */
    internal const val MIME_TYPE: String = "m"

    /** §10.2: a 256-bit key — 64 hex characters, or standard base64 decoding to 32 bytes. */
    internal const val KEY_BYTES: Int = 32

    /** §10.2: a 96-bit nonce — 24 hex characters, or standard base64 decoding to 12 bytes. */
    internal const val NONCE_BYTES: Int = 12
}

/**
 * §10.3's four operands as the **raw tag value elements** each message carried, which is what
 * "byte-identical" means and what only this package holds.
 *
 * ### Why the comparison is here rather than on `DeliverableCommitment`
 *
 * The same division `SignedTerms` draws for §7.6. `DeliverableCommitment.checkReleaseIdentity`
 * compares *parsed values*, and its own KDoc records the gap in so many words: it takes `size` as a
 * `Long` and "never sees the decimal string §10.3 is talking about", so `["size", "018342912"]` and
 * `["size", "18342912"]` are not byte-identical and compare equal there. That KDoc also says where
 * the obligation belongs — "to the codec when it lands, and is recorded here so that it lands with
 * it" — and this is that codec.
 *
 * It cannot be closed by tightening the parse instead. §4.3 fixes a timestamp-shaped value as "a
 * non-negative decimal integer" and `TagValues.readTimestamp` accepts a leading zero on exactly
 * those grounds, because refusing `018342912` would make a conformant peer look broken. So §10.3 is
 * closed where §10.3 wrote it — over the bytes.
 *
 * ### What is held is the **value**, element 1, and not the whole tag array
 *
 * §10.3 states each operand as a value: "the release's `["file-type", "<mime>"]` **value** MUST be
 * byte-identical to the commitment's `["m", "<mime>"]` **value**". Two things follow, and both are
 * why this holds a `String?` rather than the array or its tail:
 *
 * - the **name** cannot be compared, because §10.3's first operand pair is `["m", …]` against
 *   `["file-type", …]` — differently named on purpose, "because each follows its own host kind's
 *   convention" — and a whole-array comparison would report every conformant release as divergent;
 * - a **trailing element** cannot be compared either. nostr permits a tag to carry more elements
 *   than a codec reads, and §10.3 names the value and not the arity. Comparing the tails would make
 *   `["x", h, "<some extension>"]` against `["x", h]` a mismatch, and §10.3 says any mismatch MUST
 *   move the order to `disputed` — so a codec that compared arity would dispute a conformant peer
 *   over an element neither party's rule mentions.
 *
 * Absence is representable, and an absent operand is not identical to a present one: §10.3 forbids
 * *skipping* the check because a tag was absent, which is the opposite of treating absence as a
 * pass. A tag carrying no value at all reads as absent here for the same reason — there is no value
 * to be byte-identical to.
 *
 * Internal because it is the *shape* of a comparison rather than a value a caller reads: what a
 * caller needs from a failed comparison is which operands diverged, which
 * [DeliverableReleaseMessage.divergenceFrom] names.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
internal class DeliverableTags(
    private val mimeType: String?,
    private val servedHash: String?,
    private val plaintextHash: String?,
    private val size: String?,
) {

    /**
     * The §10.3 operand names on which [other] is not byte-identical to this, in §10.3's own order.
     *
     * Empty is the identity case. Operand names only, never values: §12 item 11 keeps an order's
     * correlation handles out of anything a host application might log, and `ox` is one — §10.5 says
     * so itself, which is why publishing it in a public bid is opt-in with an explained trade-off.
     */
    internal fun divergenceFrom(other: DeliverableTags): List<String> {
        val out = mutableListOf<String>()
        if (mimeType != other.mimeType) out += DeliveryTags.FILE_TYPE
        if (servedHash != other.servedHash) out += DeliveryTags.SERVED_HASH
        if (plaintextHash != other.plaintextHash) out += DeliveryTags.PLAINTEXT_HASH
        if (size != other.size) out += DeliveryTags.SIZE
        return out
    }

    /** Names the four presences and no value at all. §12 item 11. */
    override fun toString(): String =
        "DeliverableTags(mime=${mimeType != null}, ${DeliveryTags.SERVED_HASH}=${servedHash != null}, " +
            "${DeliveryTags.PLAINTEXT_HASH}=${plaintextHash != null}, " +
            "${DeliveryTags.SIZE}=${size != null})"
}

/**
 * The tag reads §10.1 and §10.3 share, in one place so the commitment and the release cannot come to
 * read the same tag two different ways.
 *
 * Nothing here is a second copy of §5.3's Encoding column: `m`, `p` and `nenya` are decoded by T9
 * under the context T13 declared, and what is left for this object is §10's own tags — which §5.3's
 * table does not carry — plus §4.3's duplicate rule over them.
 */
internal object DeliveryTerms {

    /** A tag carries a name and a value; anything shorter carries no value at all. */
    private const val VALUE_ELEMENTS: Int = 2

    /** The value sits second, after the name. */
    private const val VALUE_INDEX: Int = 1

    /**
     * The one occurrence of [name] on [rumor], or `null`, refusing a second one.
     *
     * §5.3's table carries none of §10's tags, so §4.3's duplicate rule — stated over "any tag
     * **this document** marks with cardinality `1` or `0–1`" — does not literally reach them and the
     * tag codec does not check them. Its rationale reaches them unchanged: two implementations
     * resolving two `x` tags by first and by last disagree about which bytes a provider committed
     * to, which is the one comparison §10.4 step 1 makes.
     */
    internal fun single(rumor: AttributedRumor.Bound, name: String): List<String>? {
        val found = rumor.tags.occurrences(name)
        if (found.size > 1) {
            throw ChannelException(
                ChannelRejection.DUPLICATE_TAG,
                name,
                "§10 fixes what `$name` carries on a delivery message and this event carries " +
                    "${found.size} of them; §4.3's rationale applies unchanged — resolving it by " +
                    "first, last or smallest would let two implementations disagree about which " +
                    "bytes the provider committed to",
            )
        }
        return found.firstOrNull()
    }

    /**
     * §10.3's operand: the **value** of [name]'s one occurrence, or `null` where the message
     * carried no such tag — or carried one with no value, which has none to compare.
     */
    internal fun operand(rumor: AttributedRumor.Bound, name: String): String? =
        single(rumor, name)?.getOrNull(VALUE_INDEX)

    /** The single value of [name]'s one occurrence, refusing a tag that carries no value at all. */
    internal fun value(rumor: AttributedRumor.Bound, name: String, clause: String): String {
        val tag = single(rumor, name) ?: throw missing(name, clause)
        if (tag.size < VALUE_ELEMENTS) throw missing(name, clause)
        return tag[VALUE_INDEX]
    }

    /**
     * §4.3's 64-character hex, read through T4's own reader rather than a second copy written here.
     *
     * T4 enforces the length and the alphabet in `DeliverableHash`'s `init`, which every
     * construction path runs, and normalises the case §4.3 requires be accepted. Its refusal is
     * mapped onto this package's vocabulary with the original kept on the cause.
     */
    internal fun hash(rumor: AttributedRumor.Bound, name: String, clause: String): DeliverableHash {
        val hex = value(rumor, name, clause)
        return try {
            DeliverableHash.ofHex(hex)
        } catch (refused: DeliveryException) {
            throw ChannelException(
                when (refused.reason) {
                    DeliveryRejection.WRONG_LENGTH -> ChannelRejection.HASH_WRONG_LENGTH
                    // Every other constant belongs to a size or a verification step and cannot be
                    // raised by a hex read. Mapped rather than dropped, for the reason
                    // `AttributedRumor.readOrder` maps `SeamRejection`: a constant added to
                    // `DeliveryRejection` later must not fall through onto a wrong answer.
                    else -> ChannelRejection.HASH_NOT_HEX
                },
                name,
                "§10.1 fixes `$name` as lowercase hex, exactly ${DeliverableHash.HEX_LENGTH} " +
                    "characters, and §4.3 requires rejecting a value of the wrong length or the " +
                    "wrong alphabet rather than padding or truncating it",
                refused,
            )
        }
    }

    /**
     * §10.1's `size` — "the length in bytes of the **encrypted** blob, as a decimal string".
     *
     * Read **permissively**, through the same reader §4.3's timestamps go through, and that is
     * §10.3's rule reaching down into the parse rather than an oversight. §4.3 fixes this shape as
     * "a non-negative decimal integer" and nothing more, so `018342912` is a legal spelling a
     * conformant peer may emit; refusing it here would make that peer look broken. What §10.3 then
     * requires is that the release's spelling be **byte-identical** to the commitment's, which is
     * [DeliverableTags]' comparison and not this one's.
     */
    internal fun size(rumor: AttributedRumor.Bound, clause: String): Long {
        val text = value(rumor, DeliveryTags.SIZE, clause)
        return try {
            readTimestamp(text, "a `${DeliveryTags.SIZE}` value")
        } catch (refused: TagException) {
            throw ChannelException(
                ChannelRejection.MALFORMED_SIZE,
                DeliveryTags.SIZE,
                "§10.1 encodes `${DeliveryTags.SIZE}` as a byte length written as a decimal string " +
                    "and §4.3 fixes that shape as a non-negative decimal integer; §4.3 requires " +
                    "rejecting a malformed value rather than repairing it into the nearest legal one",
                refused,
            )
        }
    }

    /**
     * §10.2: "`encryption-algorithm` MUST be `aes-gcm` in v1."
     *
     * Refused rather than ignored, and this library performs no decryption at all — so what is
     * claimed by the refusal is exactly that the token was read and compared, which is why
     * [DeliveryCheck.ENCRYPTION_PARAMETERS] stays unperformed on every evidence value this library
     * issues. A message naming another algorithm is one this implementation cannot act on, and §10.2
     * gives v1 no second value to fall back to.
     */
    internal fun requireAesGcm(rumor: AttributedRumor.Bound, clause: String) {
        val token = value(rumor, DeliveryTags.ENCRYPTION_ALGORITHM, clause)
        if (token != DeliveryTags.AES_GCM) {
            throw ChannelException(
                ChannelRejection.UNSUPPORTED_ENCRYPTION_ALGORITHM,
                DeliveryTags.ENCRYPTION_ALGORITHM,
                "§10.2 fixes `${DeliveryTags.ENCRYPTION_ALGORITHM}` as " +
                    "`${DeliveryTags.AES_GCM}` in v1 and gives no second value; a message naming " +
                    "another algorithm describes bytes this implementation has no rule for",
            )
        }
    }

    /** §7.4's `order` id, already decoded through T5's codec by T13. */
    internal fun requireOrder(rumor: AttributedRumor.Bound, clause: String): OrderId =
        rumor.order ?: throw missing(ChannelVocabulary.ORDER, clause)

    /** §10's [tag] is REQUIRED on this message and the event carries no readable one. */
    internal fun missing(tag: String, clause: String): ChannelException = ChannelException(
        ChannelRejection.MISSING_REQUIRED_TAG,
        tag,
        "$clause; this event carries no readable `$tag`",
    )

    /**
     * §10.2's key and nonce encodings, checked and **discarded**.
     *
     * §10.2: both "MUST be emitted as lowercase hex (64 and 24 characters respectively).
     * Implementations SHOULD additionally accept standard base64 on read, because NIP-17 does not
     * specify the encoding and other clients may emit it; an implementation MUST reject a value that
     * decodes to the wrong length under both encodings."
     *
     * Hex is tried first and that ordering is load-bearing rather than stylistic: every hex
     * character is also a base64 character, so a 64-character hex key is *also* well-formed base64 —
     * for 48 bytes. Reading base64 first would refuse every conformant key in the encoding §10.2
     * makes mandatory on write.
     *
     * The case rule is §4.3's and not §10.2's, and the two say different things about different
     * directions: §10.2 fixes the **emission** as lowercase, while §4.3's accept-and-normalise rule
     * governs the read and its no-normalisation exception list is exhaustive and names only the
     * BOLT-11 string and the preimage. So an uppercase key is accepted here, exactly as `T4`'s
     * `DeliverableHash` accepts an uppercase `x`, and for the reason it gives.
     *
     * **Nothing is returned.** §12 item 11 and STOP RULE 14 name decryption keys alongside key
     * material and preimages, and this library does not decrypt — §10.4 step 2 is the caller's, and
     * the caller already holds the rumor it decrypted. A value checked and dropped cannot reach a
     * log through a member nobody meant to print.
     */
    internal fun checkKeyMaterial(
        rumor: AttributedRumor.Bound,
        name: String,
        bytes: Int,
        clause: String,
    ) {
        val text = value(rumor, name, clause)
        if (isHexOf(text, bytes)) return
        if (base64ByteLength(text) == bytes) return
        throw ChannelException(
            ChannelRejection.MALFORMED_KEY_MATERIAL,
            name,
            "§10.2 fixes `$name` as $bytes bytes — ${bytes * 2} hex characters on write, or " +
                "standard base64 on read — and requires rejecting a value that decodes to the " +
                "wrong length under both encodings. This one is ${text.length} character(s) and " +
                "is neither",
        )
    }

    /** Whether [text] is exactly [bytes] bytes of hex, in either case (§4.3). */
    private fun isHexOf(text: String, bytes: Int): Boolean {
        if (text.length != bytes * 2) return false
        for (character in text) {
            val hex = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
            if (!hex) return false
        }
        return true
    }

    /**
     * How many bytes [text] decodes to as standard (RFC 4648 §4) base64, or `-1` if it is not
     * standard base64 at all.
     *
     * Padded, because "standard base64" is the padded alphabet and §10.2 names no other: an
     * unpadded value is refused rather than repaired, which is §4.3's rule for every other malformed
     * encoding in this document. The decoded **length** is all §10.2 asks for, so the bytes are
     * counted and never materialised — key material this library has no use for is key material it
     * does not hold (§12 item 11).
     */
    private fun base64ByteLength(text: String): Int {
        if (text.isEmpty() || text.length % BASE64_GROUP != 0) return -1
        var padding = 0
        while (padding < MAX_BASE64_PADDING && text[text.length - 1 - padding] == BASE64_PAD) padding++
        for (index in 0 until text.length - padding) {
            if (BASE64_ALPHABET.indexOf(text[index]) < 0) return -1
        }
        return text.length / BASE64_GROUP * BASE64_GROUP_BYTES - padding
    }

    /** RFC 4648 §4's alphabet, in its own order. */
    private const val BASE64_ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private const val BASE64_PAD: Char = '='

    /** Four base64 characters carry three bytes. */
    private const val BASE64_GROUP: Int = 4

    private const val BASE64_GROUP_BYTES: Int = 3

    /** A standard base64 value carries at most two `=`; a third is not padding. */
    private const val MAX_BASE64_PADDING: Int = 2
}

/**
 * §10.1's `kind:16` `type=5` delivery commitment, decoded off a §7.2-attributed rumor into the
 * values §10.3 and §10.4 compare.
 *
 * ### What this closes
 *
 * T4 built [DeliverableCommitment] from four typed parameters and said in writing why: "There is no
 * tag codec in this library yet, so the caller supplies the four values it read out of the tags",
 * and it recorded the consequence machine-readably as [DeliveryCheck.COMMITMENT_CARRIES_NO_KEY] —
 * §10.1's "the commitment message MUST NOT contain `decryption-key` or `decryption-nonce`. An
 * implementation MUST reject a commitment that does", which nothing could enforce over four typed
 * values. T9's codec has existed since, so the check runs here, and [asOrderEvent] is what carries
 * the fact that it ran into the order.
 *
 * ### Which layer enforces which rule, said out loud
 *
 * - **§4.1 and §7.2** are T8's and T13's: the id was recomputed and the seal's pubkey equals the
 *   rumor's before an [AttributedRumor.Bound] exists at all.
 * - **§7.4's envelope** is T13's: the required `nenya`, `p` and `order` tags, the `type`
 *   discriminator in canonical decimal, §4.3's duplicate rule over `order` and `type`, and — for a
 *   `type=5` specifically — §7.4's collision rule, which refuses a commitment that does not carry a
 *   `nenya` version this implementation implements, "and MUST NOT interpret a foreign one as a
 *   delivery commitment".
 * - **§5.3's Encoding column** is T9's, applied under the `type=5` context T13 declared. `m` is a
 *   §5.3 row and is read through it, never re-read here.
 * - **§10.1's and §10.2's own rules** are here: the key-absence rule, `x` and `ox`, `size`, `url`
 *   and the `aes-gcm` algorithm token.
 *
 * ### Who the provider is: resolved by the caller, unchanged by this type
 *
 * [sender] is §7.2's attributed key and this type makes no claim that it is the provider's. §11.2
 * says the `type=5` is the provider's and [OrderMachine][dev.eryalabs.nenya.order.OrderMachine]
 * refuses one from any other [Party], but the resolution itself is the caller's — the same division
 * [OrderProposal] records, and the residual `Party` on [OrderEvent.DeliveryCommitted] is where it
 * lives.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class DeliveryCommitmentMessage internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this commitment is for. Never `null` on a `type=5`. */
    public val order: OrderId,

    /** §10.1's four compared values, in the shape §10.3 and §10.4 already consume. */
    public val commitment: DeliverableCommitment,

    /**
     * §10.1's `["url", …]` — where the provider says the encrypted blob is served from.
     *
     * Published here and deliberately **not** on [commitment], and the split is T4's rather than
     * this codec's: a URL on the value handed to the verifier "would be a URL something could be
     * tempted to fetch", and §10.4's download is the embedding client's step under its own policy.
     * A caller has to be able to read it — §12 item 10 forbids an implementation *automatically*
     * downloading a counterparty's URL, not a user asking it to — and the alternative to publishing
     * it is every caller parsing the tag itself, which is the decoding this type exists to do.
     *
     * Nothing in this library fetches it, opens a socket, or renders it (STOP RULE 13). It is
     * redacted from [toString] because a blob URL is an order-scoped correlation handle.
     */
    public val url: String,
    internal val deliverableTags: DeliverableTags,
) {

    /**
     * §7.2's attribution: the key the seal carrying this commitment was signed by, lowercase.
     *
     * Whether that key is the provider's is the caller's resolution and not this library's claim.
     */
    public val sender: String get() = rumor.author

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6); no deadline here is evaluated against it. */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /**
     * §11.2's `accepted → committed` trigger, carrying what this decode checked.
     *
     * The checks travel with the event rather than being re-derived by the state machine, for the
     * reason §17 gives: a record of what was verified is only honest where it is produced by the
     * code that did the verifying. [DeliveryCheck.COMMITMENT_CARRIES_NO_KEY] is on it because
     * [decode] refused a commitment carrying `decryption-key` or `decryption-nonce` by name; an
     * [OrderEvent.DeliveryCommitted] built from the published three-parameter constructor carries
     * an empty set, because nobody checked. The set passed here is
     * [OrderEvent.DeliveryCommitted.DECLARABLE_CHECKS] rather than a second copy written in this
     * package: that event's `init` refuses anything outside it, and two copies of a capability
     * claim is how the two drift apart.
     *
     * [DeliveryCheck.ENCRYPTION_PARAMETERS] is **not** on it, although [decode] held
     * `encryption-algorithm` to `aes-gcm`. That constant is §10.2 whole — AES-256-GCM, a
     * per-deliverable 256-bit key never reused across orders, a 96-bit nonce never reused with that
     * key, a 128-bit tag appended to the ciphertext, no AAD — and this library performs no
     * encryption at all, so claiming it on the strength of a matching token is precisely the §17
     * over-claim the constant exists to prevent.
     *
     * @param from whose key sealed it, **as the caller resolved it** (§7.2). §11.2 says the
     *   provider's; nothing in this library can establish that, and `OrderMachine` refuses every
     *   other [Party].
     */
    public fun asOrderEvent(from: Party): OrderEvent.DeliveryCommitted = OrderEvent.DeliveryCommitted(
        commitment = commitment,
        from = from,
        createdAt = createdAt,
        // The `internal` door, which is the only one that takes a capability claim at all: the
        // published three-parameter constructor records that nobody checked, because a caller
        // holding four typed values has no tags for §10.1's rule to be about.
        checksPerformed = OrderEvent.DeliveryCommitted.DECLARABLE_CHECKS,
    )

    /**
     * Names nothing at all.
     *
     * §12 item 11 keeps the order id out of a diagnostic, §10.5 makes `ox` a correlation handle, and
     * §12 item 10's blob URL is one too. This type holds every one of them.
     */
    override fun toString(): String = "DeliveryCommitmentMessage(redacted)"

    public companion object {

        /**
         * §10.1's commitment, decoded.
         *
         * Takes an [AttributedRumor.Bound] rather than a `CheckedEvent`, for the reason
         * [OrderProposal.decode] gives: §4.1 requires the id check before any other processing and
         * §7.2 requires the pubkey-equality check before a term is read, and the way to enforce an
         * ordering is to make the later step take a type only the earlier steps can produce.
         *
         * @throws ChannelException naming which §10.1, §10.2, §7.4 or §4.3 rule refused the
         *   commitment, and the tag it was about. A tag-layer or delivery-layer refusal is preserved
         *   on the cause.
         */
        public fun decode(rumor: AttributedRumor.Bound): DeliveryCommitmentMessage {
            ChannelTerms.requireType(
                rumor,
                OrderMessageKind.DELIVERY_COMMITMENT,
                ChannelRejection.NOT_A_DELIVERY_COMMITMENT,
            )
            // §10.1, and it is checked before a single value is read: a commitment carrying the key
            // is not a commitment with one tag too many, it is the construction collapsed. Reading
            // its hashes first and refusing afterwards would be the same refusal made after this
            // codec had already treated the message as a commitment.
            refuseKeyMaterial(rumor)

            val order = DeliveryTerms.requireOrder(rumor, CLAUSE)
            val url = DeliveryTerms.value(rumor, DeliveryTags.URL, CLAUSE)
            val x = DeliveryTerms.hash(rumor, DeliveryTags.SERVED_HASH, CLAUSE)
            val ox = DeliveryTerms.hash(rumor, DeliveryTags.PLAINTEXT_HASH, CLAUSE)
            val size = DeliveryTerms.size(rumor, CLAUSE)
            DeliveryTerms.requireAesGcm(rumor, CLAUSE)
            // §5.3's row, read through T9 rather than re-read here — and §10.3 forbids looking for
            // `file-type` on a commitment, which is why this is the only name asked for.
            val mimeType = rumor.tags.mimeType
                ?: throw DeliveryTerms.missing(DeliveryTags.MIME_TYPE, CLAUSE)

            // Validated above, never wrapped in a `try` that would swallow it: STOP RULE 1. The one
            // refusal `DeliverableCommitment`'s `init` can still raise here is a negative `size`,
            // which `readTimestamp` cannot produce, and its own exception is the right answer for a
            // value that somehow did.
            val commitment = DeliverableCommitment(x, ox, mimeType, size)
            return DeliveryCommitmentMessage(
                rumor = rumor,
                order = order,
                commitment = commitment,
                url = url,
                deliverableTags = DeliverableTags(
                    mimeType = DeliveryTerms.operand(rumor, DeliveryTags.MIME_TYPE),
                    servedHash = DeliveryTerms.operand(rumor, DeliveryTags.SERVED_HASH),
                    plaintextHash = DeliveryTerms.operand(rumor, DeliveryTags.PLAINTEXT_HASH),
                    size = DeliveryTerms.operand(rumor, DeliveryTags.SIZE),
                ),
            )
        }

        /**
         * The tags REQUIRED on a `type=5`: §7.4's three, plus `type`, plus §10.1's and §10.2's own.
         *
         * Published because `DeliveryMessageCodecTest` walks it, removing each in turn and asserting
         * the refusal names *that* tag — a proof over a list rather than over hand-written controls
         * that could silently stop covering a tenth. Derived from [AttributedRumor.requiredTags],
         * which the tests hold equal to §7.4's fenced block parsed out of the document.
         */
        public fun requiredTags(): List<String> = REQUIRED

        /**
         * Every tag this codec requires or accepts on a `type=5`.
         *
         * Held against §10.1's own worked JSON example, parsed out of `spec/NENYA-1.md` at test
         * time: this set minus `subject` must equal the ten tag names the example prints. That one
         * extra is documented rather than excused — §7.4 makes `subject` display metadata that MAY
         * appear on any rumor and that §4.3 requires round-trip — and it is named in the test with
         * the clause it comes from. A §10.1 revision that adds or drops a tag turns the suite red
         * rather than leaving this codec quietly stale.
         */
        public fun readableTags(): List<String> = READABLE

        /** The clause every missing-tag refusal from this decoder cites. */
        private const val CLAUSE: String =
            "§10.1 prints the commitment with this tag and §10.3 and §10.4 read it"

        /**
         * §10.1: "The commitment message MUST NOT contain `decryption-key` or `decryption-nonce`.
         * An implementation MUST reject a commitment that does — releasing the key at commitment
         * time collapses the whole construction."
         *
         * Named by tag, because the two are different mistakes with the same consequence and a
         * caller told only "forbidden tag" has to go looking for which.
         */
        private fun refuseKeyMaterial(rumor: AttributedRumor.Bound) {
            for (name in FORBIDDEN) {
                if (rumor.tags.occurrences(name).isEmpty()) continue
                throw ChannelException(
                    ChannelRejection.COMMITMENT_CARRIES_KEY,
                    name,
                    "§10.1: a commitment MUST NOT contain `$name`, and an implementation MUST " +
                        "reject one that does. §10.5 is why the rule is absolute: `ox` binds the " +
                        "provider only because the key does not exist in the buyer's hands until " +
                        "after payment, and a commitment that ships the key has already released " +
                        "the deliverable",
                )
            }
        }

        /** §10.1's two forbidden tags, in the order §10.1 names them. */
        private val FORBIDDEN: List<String> =
            listOf(DeliveryTags.DECRYPTION_KEY, DeliveryTags.DECRYPTION_NONCE)

        private val REQUIRED: List<String> = readOnlyListOf(
            AttributedRumor.requiredTags() + listOf(
                ChannelVocabulary.TYPE,
                DeliveryTags.URL,
                DeliveryTags.SERVED_HASH,
                DeliveryTags.PLAINTEXT_HASH,
                DeliveryTags.MIME_TYPE,
                DeliveryTags.SIZE,
                DeliveryTags.ENCRYPTION_ALGORITHM,
            ),
        )

        private val READABLE: List<String> =
            readOnlyListOf(REQUIRED + listOf(ChannelVocabulary.SUBJECT))
    }
}

/**
 * §10.3's `kind:15` file-message release, decoded off a §7.2-attributed rumor.
 *
 * ### What this closes
 *
 * T4 built [DeliverableRelease] from typed parameters for the same reason it built
 * [DeliverableCommitment] that way, and recorded two obligations as unperformed on every
 * [dev.eryalabs.nenya.delivery.DeliveryEvidence] it issued: [DeliveryCheck.RELEASE_ORDER_BINDING] —
 * §10.3's "a release whose `order` tag names no open order of this implementation's own, or names an
 * order not in state `paid`, MUST NOT advance any state" — and the encoding half of §10.2. Both need
 * a tag codec and an order, and both run here.
 *
 * ### The key and the nonce are checked and not kept
 *
 * §10.2 fixes both encodings and requires a value that decodes to the wrong length under both be
 * rejected. [decode] enforces that and then **drops the value**: §12 item 11 and STOP RULE 14 name
 * decryption keys alongside key material and preimages, this library does not decrypt — §10.4 step 2
 * is the caller's — and the caller already holds the rumor it decrypted. There is no member of this
 * type from which a key could reach a log, so there is nothing to remember to redact.
 *
 * ### `file-type` is read and is deliberately not REQUIRED
 *
 * §10.3 says an implementation "MUST NOT skip the check because the tag it looked for was absent",
 * and T4 made that reachable by letting [DeliverableRelease.fileType] be `null` so the absent case
 * arrives at `checkReleaseIdentity` to be *refused* there as
 * [DeliveryRejection.FILE_TYPE_ABSENT]. A decoder that refused the message outright would be a
 * second, earlier answer to a question §10.3 already answers, and would make that constant
 * unreachable from the only path that produces a release. So absence survives the decode and is a
 * divergence in [divergenceFrom] and a refusal in T4's check.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class DeliverableReleaseMessage internal constructor(
    private val rumor: AttributedRumor.Bound,

    /** §7.4's `order` id this release claims to be about. Never `null` on a `kind:15`. */
    public val order: OrderId,

    /** §10.3's compared values, in the shape §10.3's identity check already consumes. */
    public val release: DeliverableRelease,
    internal val deliverableTags: DeliverableTags,
) {

    /**
     * §7.2's attribution: the key the seal carrying this release was signed by, lowercase.
     *
     * Whether that key is the provider's is the caller's resolution and not this library's claim.
     */
    public val sender: String get() = rumor.author

    /** The id T8 recomputed for the rumor this was decoded from (§4.1). */
    public val id: EventId get() = rumor.id

    /** §4.1's `created_at`, verbatim. A **claim** (§4.6); no deadline here is evaluated against it. */
    public val createdAt: Long get() = rumor.createdAt

    /** The rumor this was decoded from, byte for byte — same tags, same order, same id (§4.3). */
    public fun encode(): WireEvent = rumor.encode()

    /**
     * §10.3's identity check over the **bytes**, which is how §10.3 states it.
     *
     * The four operands, in §10.3's order: the release's `file-type` against the commitment's `m`,
     * then `x`, `ox` and `size` against the commitment's values of the same name. An empty answer is
     * the identity case; anything else names which operand diverged, and §10.3 says any mismatch in
     * any of the four MUST move the order to `disputed`.
     *
     * **This is strictly stronger than [DeliverableCommitment.checkReleaseIdentity] and does not
     * replace it.** That one compares parsed values and says so: it takes `size` as a `Long`, so
     * `["size", "018342912"]` and `["size", "18342912"]` compare equal there and diverge here. It
     * remains the check the state machine runs, because it reports which operand diverged as a typed
     * refusal and because an order may hold a commitment it did not decode from a message. A caller
     * holding both messages gets §10.3's own comparison by calling this.
     *
     * A list of operand **names** and never a `Boolean`: §10.3 gives four operands with four
     * different causes, and `ChannelStructureTest` asserts by reflection that nothing in this
     * package answers a question of this shape with a flag.
     */
    public fun divergenceFrom(commitment: DeliveryCommitmentMessage): List<String> =
        readOnlyListOf(deliverableTags.divergenceFrom(commitment.deliverableTags))

    /**
     * §11.2's `paid → released` trigger, with §10.3's binding checked on the way.
     *
     * §10.3: "A `kind:15` release is bound to its order by its `["order", "<order-id-hex>"]` tag,
     * which §7.4 requires. Hash equality is a *check*, not the binding… A release whose `order` tag
     * names no open order of this implementation's own, or names an order not in state `paid`, MUST
     * NOT advance any state."
     *
     * Both halves are made here, and this is the **only** way to build an
     * [OrderEvent.DeliverableReleased] out of a decoded release — so the event cannot exist without
     * the binding having been checked, which is why it may carry
     * [DeliveryCheck.RELEASE_ORDER_BINDING] as performed — and why the id it was checked against
     * travels on the event, so `OrderMachine` can make the comparison again rather than trust this
     * call. An event built from the published three-parameter constructor carries no id and an
     * empty set, because nobody checked.
     *
     * [DeliveryCheck.RELEASE_IDENTITY] is **not** on it, and deliberately: §10.3's comparison is
     * [divergenceFrom], a separate call a caller may not have made, and that constant's own KDoc
     * says evidence produced without it MUST NOT be read as having performed it.
     * [DeliveryCheck.ENCRYPTION_PARAMETERS] is not on it either — see
     * [DeliveryCommitmentMessage.asOrderEvent] for why a matching algorithm token and two correct
     * lengths are not §10.2.
     *
     * "No open order of this implementation's own" is discharged by the parameter's type rather than
     * by a lookup: an [Order] is minted by `OrderMachine.open` and by nothing else, so a caller that
     * has one has an order this implementation opened. Which order it is, is then the id comparison.
     *
     * @param order the open order this release claims to release, held by the caller.
     * @param from whose key sealed it, **as the caller resolved it** (§7.2). §11.2 says the
     *   provider's; nothing in this library can establish that, and `OrderMachine` refuses every
     *   other [Party].
     * @throws ChannelException [ChannelRejection.RELEASE_FOR_ANOTHER_ORDER] when the release's
     *   `order` tag is not [Order.id], or [ChannelRejection.RELEASE_ORDER_NOT_PAID] when the order
     *   is in any state but `paid`.
     */
    public fun asOrderEvent(order: Order, from: Party): OrderEvent.DeliverableReleased {
        if (this.order != order.id) {
            throw ChannelException(
                ChannelRejection.RELEASE_FOR_ANOTHER_ORDER,
                ChannelVocabulary.ORDER,
                "§10.3 binds a release to its order by its `${ChannelVocabulary.ORDER}` tag and not " +
                    "by hash equality, which it calls a check rather than the binding: an " +
                    "implementation handling two concurrent orders with the same provider routes on " +
                    "the tag first. This release names another order and MUST NOT advance this one",
            )
        }
        if (order.state != OrderState.PAID) {
            throw ChannelException(
                ChannelRejection.RELEASE_ORDER_NOT_PAID,
                ChannelVocabulary.ORDER,
                "§10.3: a release naming an order not in state `${OrderState.PAID.token}` MUST NOT " +
                    "advance any state. This order is in `${order.state.name}`, and the state is " +
                    "this implementation's own view (§11) rather than a token the sender asserted",
            )
        }
        return OrderEvent.DeliverableReleased(
            release = release,
            from = from,
            createdAt = createdAt,
            // §10.3's binding was checked against *that* order, so the id travels with the claim:
            // the event is an ordinary value a caller may offer to any order, and `OrderMachine`
            // makes the comparison again rather than taking this call's word for it.
            order = this.order,
            checksPerformed = OrderEvent.DeliverableReleased.DECLARABLE_CHECKS,
        )
    }

    /**
     * Names nothing at all — and in particular neither the decryption key nor the nonce, which this
     * type does not hold at all (§12 item 11, STOP RULE 14).
     *
     * §12 item 11 also keeps the order id out of a diagnostic, and §10.5 makes `ox` a correlation
     * handle. This type holds both.
     */
    override fun toString(): String = "DeliverableReleaseMessage(redacted)"

    public companion object {

        /**
         * §10.3's release, decoded.
         *
         * Takes an [AttributedRumor.Bound] for the reason [DeliveryCommitmentMessage.decode] does.
         *
         * @throws ChannelException naming which §10.2, §10.3, §7.4 or §4.3 rule refused the release,
         *   and the tag it was about. A tag-layer or delivery-layer refusal is preserved on the cause.
         */
        public fun decode(rumor: AttributedRumor.Bound): DeliverableReleaseMessage {
            if (rumor.kind != RumorKind.FILE_MESSAGE) {
                throw ChannelException(
                    ChannelRejection.NOT_A_RELEASE,
                    null,
                    "this decoder reads §10.3's kind:${RumorKind.FILE_MESSAGE.kind} file message; " +
                        "the rumor it was handed is a kind:${rumor.kind.kind}" +
                        (rumor.type?.let { " ${it.name}" } ?: "") +
                        ". A well-formed message of another kind is not a malformed one",
                )
            }
            val order = DeliveryTerms.requireOrder(rumor, CLAUSE)
            val x = DeliveryTerms.hash(rumor, DeliveryTags.SERVED_HASH, CLAUSE)
            val ox = DeliveryTerms.hash(rumor, DeliveryTags.PLAINTEXT_HASH, CLAUSE)
            val size = DeliveryTerms.size(rumor, CLAUSE)
            DeliveryTerms.requireAesGcm(rumor, CLAUSE)
            DeliveryTerms.checkKeyMaterial(
                rumor,
                DeliveryTags.DECRYPTION_KEY,
                DeliveryTags.KEY_BYTES,
                CLAUSE,
            )
            DeliveryTerms.checkKeyMaterial(
                rumor,
                DeliveryTags.DECRYPTION_NONCE,
                DeliveryTags.NONCE_BYTES,
                CLAUSE,
            )
            // §10.3 forbids looking for `m` on a release, so only this name is asked for — and its
            // absence is carried rather than refused. See this class's note.
            val fileTypeTag = DeliveryTerms.single(rumor, DeliveryTags.FILE_TYPE)
            val fileType = fileTypeTag?.getOrNull(VALUE_INDEX)

            // Validated above, never wrapped in a `try`: STOP RULE 1.
            val release = DeliverableRelease(x, ox, fileType, size)
            return DeliverableReleaseMessage(
                rumor = rumor,
                order = order,
                release = release,
                deliverableTags = DeliverableTags(
                    mimeType = fileType,
                    servedHash = DeliveryTerms.operand(rumor, DeliveryTags.SERVED_HASH),
                    plaintextHash = DeliveryTerms.operand(rumor, DeliveryTags.PLAINTEXT_HASH),
                    size = DeliveryTerms.operand(rumor, DeliveryTags.SIZE),
                ),
            )
        }

        /**
         * The tags REQUIRED on a `kind:15`: §7.4's three, plus §10.2's and §10.3's own.
         *
         * No `type`: §7.4 puts the `type` discriminator on a `kind:16` and on nothing else.
         * No `file-type` either — see this class's note on why §10.3 wants its absence to reach the
         * identity check rather than the decoder.
         */
        public fun requiredTags(): List<String> = REQUIRED

        /**
         * Every tag this codec requires or accepts on a `kind:15`.
         *
         * Held against §10.3's own worked JSON example, parsed out of `spec/NENYA-1.md` at test
         * time: this set minus `subject` must equal the ten tag names the example prints, with
         * `subject` documented as §7.4's display-only MAY exactly as it is on a `type=5`.
         */
        public fun readableTags(): List<String> = READABLE

        /** The value sits second, after the name. */
        private const val VALUE_INDEX: Int = 1

        /** The clause every missing-tag refusal from this decoder cites. */
        private const val CLAUSE: String =
            "§10.2 and §10.3 print the release with this tag and §10.3 and §10.4 read it"

        private val REQUIRED: List<String> = readOnlyListOf(
            AttributedRumor.requiredTags() + listOf(
                DeliveryTags.SERVED_HASH,
                DeliveryTags.PLAINTEXT_HASH,
                DeliveryTags.SIZE,
                DeliveryTags.ENCRYPTION_ALGORITHM,
                DeliveryTags.DECRYPTION_KEY,
                DeliveryTags.DECRYPTION_NONCE,
            ),
        )

        private val READABLE: List<String> = readOnlyListOf(
            REQUIRED + listOf(DeliveryTags.FILE_TYPE, ChannelVocabulary.SUBJECT),
        )
    }
}
