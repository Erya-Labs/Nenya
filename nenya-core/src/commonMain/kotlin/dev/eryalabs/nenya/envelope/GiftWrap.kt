package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.SignatureVerdict
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.text.strictUtf8OrNull
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireLimits

/**
 * Which half of §7.1 a rejection belongs to: the write procedure or the read procedure.
 *
 * Declared on **every** [EnvelopeRejection] constant with no default, so a constant cannot be
 * added without choosing a side. That is not bookkeeping: it is what lets the read half's
 * non-vacuity floor enumerate its own constants from the enum —
 * `EnvelopeRejection.entries.filter { it.side == OPEN }` — instead of from a list somebody kept in
 * step by hand, and the same for the write half here. A hand-written list is exactly the shape
 * that goes stale the first time a constant is added without its control.
 */
public enum class EnvelopeSide {

    /** §7.1's write procedure: rumor → seal → wrap ([GiftWrap.seal]). */
    SEAL,

    /** §7.1's read procedure: wrap → seal → rumor. */
    OPEN,
}

/**
 * Why §7.1's envelope refused, carrying the side it refused on.
 *
 * The reason is part of the API and not diagnostic text, for the reason `WireRejection` and
 * `SeamRejection` give: "this message could not be sealed" is not an answer a caller can act on,
 * and the fixes differ completely — an unavailable seam is a client that has not finished wiring
 * itself up, a reused throwaway key is a client whose one-time signer is not one, and an oversized
 * rumor is a message the user must shorten. Tests assert on these constants rather than on message
 * wording.
 *
 * ### No message built from any of these echoes an input
 *
 * §12 items 2 and 11 name the counterparty pubkey, the order id and key material among the values
 * that MUST NOT appear in a log, a crash report or the string representation of anything this
 * library exposes, and §7.2 adds the gift wrap's throwaway pubkey — which MUST NOT be displayed,
 * indexed by, or used in any comparison outside this file. A message here may name a *field*, a
 * *length*, a *bound* and a *reason*; it may never name a key, a rumor's `content`, a ciphertext or
 * an order id. `EnvelopeRedactionTest` sweeps every refusal this write path can produce and asserts
 * exactly that.
 */
public enum class EnvelopeRejection(

    /** Which of §7.1's two procedures raised this. */
    public val side: EnvelopeSide,
) {

    // -------------------------------------------------------------------- §7.1's write procedure

    /**
     * A [Signer] answered [SeamAnswer.Unavailable] for its own public key.
     *
     * Covers the sender's signer and a throwaway one alike: the refusal names the capability that
     * was missing, never which party was asking, because naming the party is one step from naming
     * the key (§12 item 2).
     */
    SIGNER_PUBLIC_KEY_UNAVAILABLE(EnvelopeSide.SEAL),

    /**
     * A [Signer]'s public key is not 64 **lowercase** hex characters (§4.3).
     *
     * Lowercase rather than §4.3's accept-and-normalise rule, because this is the write path:
     * §4.3 requires an implementation to *emit* lowercase hex, and the key goes verbatim into the
     * `pubkey` field whose bytes the event id hashes. Normalising it here would emit an event
     * whose id the signer never signed.
     */
    SIGNER_PUBLIC_KEY_MALFORMED(EnvelopeSide.SEAL),

    /** The recipient key the caller supplied is not 64 lowercase hex characters (§4.3). */
    RECIPIENT_KEY_MALFORMED(EnvelopeSide.SEAL),

    /**
     * The recipient is the sender. §7.1's last paragraph makes this Nenya's own refusal, stated as
     * a MAY: a message whose recipient equals its sender is refused "other than as the sender's own
     * copy from step 4", which [GiftWrap.seal] produces itself and never takes as an argument.
     */
    RECIPIENT_IS_SENDER(EnvelopeSide.SEAL),

    /**
     * The rumor's `pubkey` is not the sender's own key — §7.2's rule mirrored onto the write path.
     *
     * §7.2 is a rule every reader MUST apply, and a sender that emits a rumor claiming somebody
     * else's key has produced a message every conformant reader will discard as an impersonation.
     * Refusing here, **before anything is encrypted or signed**, is the cheapest place to catch it.
     *
     * Compared byte for byte against the sender's own lowercase key rather than through §4.3's
     * accept-and-normalise rule: §4.3 requires this implementation to emit lowercase hex, so a
     * rumor spelling the sender's key in uppercase is one this library will not seal either.
     */
    RUMOR_NOT_SENDERS(EnvelopeSide.SEAL),

    /**
     * The rumor cannot be written as §7.1's object form at all — a kind outside NIP-01's `0..65535`,
     * a §4.3 bound exceeded, or text with no UTF-8 encoding (§4.1). The wire layer's own reason is
     * kept as the cause.
     */
    RUMOR_MALFORMED(EnvelopeSide.SEAL),

    /**
     * The rumor's JSON is longer than [EnvelopeLimits.MAX_RUMOR_JSON_BYTES] (§4.3, §7.1 step 1).
     *
     * A bound on writing and not on reading: anything above it produces a seal that cannot be
     * wrapped, so the sender would emit a message nobody can open.
     */
    RUMOR_TOO_LARGE(EnvelopeSide.SEAL),

    /** The relay hint exceeds §4.3's per-tag-value bound, measured in UTF-8 bytes. */
    RELAY_HINT_TOO_LONG(EnvelopeSide.SEAL),

    /**
     * The relay hint has no UTF-8 encoding at all — an unpaired UTF-16 surrogate (§4.1).
     *
     * Its own reason rather than [RELAY_HINT_TOO_LONG], because the two have opposite fixes: one
     * says shorten the hint and the other says the hint is not text. A single-character hint
     * reported as "too long" is a caller sent chasing the wrong thing.
     */
    RELAY_HINT_MALFORMED(EnvelopeSide.SEAL),

    /** The injected [NenyaClock] answered [SeamAnswer.Unavailable]; §7.1 step 5 has no fallback. */
    CLOCK_UNAVAILABLE(EnvelopeSide.SEAL),

    /**
     * The injected [NenyaClock] reported a time before 1970.
     *
     * §4.3 fixes every timestamp as a non-negative integer, so this is not an unusual time — it is
     * a broken clock, and §7.1 step 5's window cannot honestly be drawn from it. The same reading
     * the order machine refuses as `CLOCK_READING_BEFORE_EPOCH`.
     */
    CLOCK_READING_BEFORE_EPOCH(EnvelopeSide.SEAL),

    /** The injected [Randomness] answered [SeamAnswer.Unavailable]. §7.1 step 5 has no fallback. */
    RANDOMNESS_UNAVAILABLE(EnvelopeSide.SEAL),

    /**
     * The injected [Randomness] answered, and what it answered cannot be used: the wrong number of
     * bytes, or a bounded run of rejection-sampling draws that never landed inside the window.
     *
     * **Never a fallback to `now`.** §7.1 step 5 says a seal carrying the true time reintroduces
     * exactly the timing correlation the construction exists to remove, so a source this library
     * cannot draw a uniform value from produces no message at all.
     */
    RANDOMNESS_UNUSABLE(EnvelopeSide.SEAL),

    /** A [Signer] answered [SeamAnswer.Unavailable] for NIP-44 encryption (§3, §7.1 steps 2 and 3). */
    ENCRYPTION_UNAVAILABLE(EnvelopeSide.SEAL),

    /**
     * A [Signer] returned something that is not a NIP-44 payload: empty, or carrying a character
     * outside printable ASCII.
     *
     * NIP-44 v2's payload is base64, and NIP-44 reserves a leading `#` for future versions — both
     * printable ASCII. Checking it here is what makes every byte count below a character count,
     * and it refuses at the seam the one shape that would otherwise reach the event writer as an
     * unpaired surrogate (§4.1) rather than as a named rejection.
     */
    CIPHERTEXT_MALFORMED(EnvelopeSide.SEAL),

    /** A [Signer] answered [SeamAnswer.Unavailable] for an event signature (§3, §4.1). */
    SIGNATURE_UNAVAILABLE(EnvelopeSide.SEAL),

    /** A [Signer] returned something that is not 128 lowercase hex characters (NIP-01, §4.3). */
    SIGNATURE_MALFORMED(EnvelopeSide.SEAL),

    /**
     * The injected [Secp256k1Ops] checked a signature this library was about to emit and answered
     * [SignatureVerdict.INVALID].
     *
     * Verify-on-emit: §3 says nothing a signer *claims* about what it signed may be accepted
     * beyond the bytes returned, so a client with a verifier gets its own outgoing events checked
     * against the id this library recomputed. A verifier answering [SeamAnswer.Unavailable] is a
     * different thing entirely and never lands here — the message is emitted and
     * [SealedMessage.notPerformedHere] records that the check was not performed (§17).
     */
    SIGNATURE_DOES_NOT_VERIFY(EnvelopeSide.SEAL),

    /**
     * The `kind:13` seal's JSON exceeds [EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES], so it is not a
     * NIP-44 plaintext and cannot be wrapped. Reachable only from a signer whose `nip44Encrypt`
     * returns more than NIP-44 can produce, since [RUMOR_TOO_LARGE] bounds the honest path.
     */
    SEAL_TOO_LARGE_TO_EMIT(EnvelopeSide.SEAL),

    /**
     * The `kind:1059` wrap's `content` exceeds [EnvelopeLimits.MAX_WRAP_CONTENT_CHARS], or the
     * whole wrap exceeds [EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES] (§4.3). Same shape and same
     * cause as [SEAL_TOO_LARGE_TO_EMIT], one layer out.
     */
    WRAP_TOO_LARGE_TO_EMIT(EnvelopeSide.SEAL),

    /** The injected [EphemeralSigners] answered [SeamAnswer.Unavailable]; no wrap can be signed. */
    EPHEMERAL_SIGNER_UNAVAILABLE(EnvelopeSide.SEAL),

    /**
     * A throwaway keypair is one of the two parties' own keys. §7.1 step 4: it MUST equal neither
     * party's key — a wrap signed by the sender's key unmasks the sender to anyone reading the
     * relay, and one signed by the recipient's is the mistake a naive implementation makes because
     * the recipient's key is already in hand.
     */
    EPHEMERAL_KEY_IS_IDENTITY(EnvelopeSide.SEAL),

    /**
     * The two copies of one message were handed the same throwaway key. §7.1 step 4: it MUST differ
     * between them, because a key appearing on two wraps links them, which is the correlation the
     * whole construction exists to remove.
     */
    EPHEMERAL_KEY_REUSED(EnvelopeSide.SEAL),

    /**
     * A throwaway signer was asked for a second signature, a second encryption, or a decryption.
     *
     * This one is about **this library's own code** rather than about an injected part: §7.1 step 3
     * gives every wrap its own keypair, so a throwaway signer is used exactly once for one
     * encryption and one signature. The guard that enforces it is internal and its refusal is
     * unreachable from a correct call path; it exists so that a later edit which reuses a key
     * inside this file fails loudly instead of quietly linking two wraps.
     */
    THROWAWAY_SIGNER_REUSED(EnvelopeSide.SEAL),
}

/**
 * §7.1's envelope refused, carrying the [reason] as data.
 *
 * Extends [IllegalStateException] rather than [IllegalArgumentException], and for the reason
 * `SeamException` gives: most of what can go wrong here is an injected component behaving other
 * than its contract — a signer that will not sign, a randomness source that answers the wrong
 * number of bytes, a one-time signer that is not one — rather than a bad argument. The handful of
 * argument failures ([EnvelopeRejection.RECIPIENT_IS_SENDER],
 * [EnvelopeRejection.RUMOR_NOT_SENDERS]) travel in the same type because a caller wants one thing
 * to catch and one field to branch on.
 *
 * **The message never echoes an input.** See [EnvelopeRejection].
 */
public class EnvelopeException internal constructor(
    public val reason: EnvelopeRejection,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * One outgoing `kind:1059` gift wrap: its JSON, its recomputed id, and the key it is addressed to.
 *
 * ### There is no accessor for the throwaway key, and that is the point
 *
 * §7.2: "The gift wrap's `pubkey` is random and carries **no** identity. Implementations MUST NOT
 * display it, index by it, or use it in any comparison." A rule expressed as a prohibition needs a
 * shape that cannot break it, so the key that signed this wrap is reachable only by parsing [json]
 * — which is a thing a caller would have to do on purpose, and which this library does nowhere.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `CheckedEvent`, `VerifiedPayment` and `AttributedRumor`: a public
 * `sealed interface` whose single implementation is a `private` class. An interface has no
 * constructor to synthesise an accessor for, so `Class.getConstructors()` on it is empty by
 * construction; a `private constructor` plus a companion factory emits a public synthetic
 * constructor a Java client can call with a `null` marker. The claim is "unforgeable **through the
 * published API**", not unforgeable full stop.
 */
public sealed interface OutgoingWrap {

    /** §7.1's object form of the signed `kind:1059`, ready to hand to a relay transport. */
    public val json: String

    /** The wrap's id, recomputed here from its own five fields (§4.1) and not merely claimed. */
    public val id: EventId

    /**
     * The key this copy is encrypted and addressed to, in §4.3's lowercase hex: the recipient on
     * [SealedMessage.toRecipient] and the sender's own key on [SealedMessage.toSelf].
     */
    public val addressee: String
}

/**
 * §7.1 step 4's **two** copies of one message: one for the recipient, one for the sender's own key.
 *
 * Each carries its own seal, encrypted to that copy's addressee, and its own throwaway keypair —
 * a seal is encrypted to exactly one reader, so the two copies cannot share one, and a throwaway
 * key appearing on both would link them.
 *
 * All or nothing: [GiftWrap.seal] produces one of these or throws. There is no partial output, so
 * a client cannot publish a recipient copy whose matching self copy failed.
 */
public sealed interface SealedMessage {

    /** The copy addressed to the recipient. */
    public val toRecipient: OutgoingWrap

    /** The copy addressed to the sender's own key, so the sender can reconstruct the thread. */
    public val toSelf: OutgoingWrap

    /**
     * What was **not** checked while producing this message (§17).
     *
     * Empty when the injected [Secp256k1Ops] answered a verdict for all four signatures this
     * library emitted; `{`[SeamCapability.BIP340_VERIFICATION]`}` when it answered
     * [SeamAnswer.Unavailable] for any of them. §17 permits omitting BIP-340 verification and
     * forbids reporting an unverified thing as verified, and requires the statement be
     * machine-readable rather than a paragraph in a README — so a caller that must know which of
     * the two it is holding reads this set rather than inferring it from the existence of the value.
     *
     * It says nothing about NIP-44 encryption or about the signatures themselves being *made*:
     * both are the injected signer's work, and `Capabilities.NOT_PERFORMED_HERE` is where the
     * library-wide statement about them lives.
     */
    public val notPerformedHere: Set<SeamCapability>
}

/**
 * §7.1's write procedure: a rumor, sealed and gift-wrapped twice, using only what the embedding
 * client plugs in.
 *
 * ### The first production code in this library to call a seam
 *
 * Until now nothing did: `OrderId.mint` draws bytes and every other module is pure computation. So
 * this is where §3's contracts stop being descriptions and start being load-bearing, and it is
 * written on the assumption that every one of them may be broken. Nothing an injected part
 * *asserts* is evidence of anything:
 *
 * - a signer's public key is checked for shape before it becomes a `pubkey` field;
 * - a signature is checked for shape, and — when a verifier is injected — **verified against the
 *   id this library recomputed** before the event is emitted;
 * - a NIP-44 payload is checked for shape and for size before it becomes `content`;
 * - a one-time signer's keys are checked against both parties' keys and against each other, which
 *   is the whole of what §3 says may be checked about that seam (freshness cannot be observed from
 *   outside, and this library claims no such check);
 * - a clock reading before 1970 and a randomness source that cannot produce a uniform draw each
 *   stop the message rather than being repaired.
 *
 * ### The four timestamps
 *
 * §7.1 step 5: each seal and each wrap — four values, drawn independently — is randomised
 * uniformly into `[now − 172 800, now]`, and none may lie in the future. The draw is rejection
 * sampling over 32-bit values with a **bounded** number of attempts, so a source that never lands
 * in range ends as [EnvelopeRejection.RANDOMNESS_UNUSABLE] rather than looping forever or falling
 * back to `now`. `max(0, now − 172 800)` is the floor, because §4.3 has no negative timestamp.
 *
 * ### What is injected, and what is fixed
 *
 * §4.3's bounds on the **rumor** are the caller's ([limits] on [seal]): a rumor is an ordinary
 * event and §4.3 says its bounds SHOULD be configurable. The seal's and the wrap's are not — they
 * are what NIP-44 can represent, and [EnvelopeLimits] states the reasoning once. That asymmetry is
 * why [seal] takes a `WireLimits` rather than an envelope-limits object: [EnvelopeLimits] is
 * deliberately a set of constants, and the one number in it that is a local default bounds the
 * wrap, which no conformant sender can reach anyway.
 *
 * Pure but for the seams: no clock, no randomness, no I/O of its own, and nothing here opens a
 * socket or performs any cryptography beyond this library's own SHA-256 (§4.1's event id).
 */
public object GiftWrap {

    /**
     * 172 800 seconds — two days, §7.1 step 5's randomisation window.
     *
     * Published because it is a wire-visible property of what this library emits, and pinned
     * against §7.1's own `[now − N, now]` interval parsed out of the specification at test time.
     */
    public const val RANDOMISATION_WINDOW_SECONDS: Long = 172_800L

    /**
     * §7.1's write procedure, producing both copies or nothing.
     *
     * The order of the refusals below is §7.1's own and is load-bearing: everything that can be
     * decided from the arguments alone is decided **before** the first byte is encrypted or signed,
     * so a rumor claiming somebody else's key costs no seam call at all.
     *
     * @param rumor §7.1 step 1's unsigned rumor, with the **true** `created_at` — the one timestamp
     *   in the envelope that is not randomised. Its `pubkey` MUST be the sender's own.
     * @param recipientPubkey the recipient's x-only key, 64 lowercase hex characters (§4.3).
     * @param sender the sender's real signer: it seals (§7.1 step 2) and nothing else.
     * @param ephemeral §3's one-time signer, asked for exactly two keypairs — one per copy.
     * @param clock §4.6's injected clock. Read once; both copies' windows are drawn against the
     *   same reading, so the two wraps do not disagree about what "now" was.
     * @param randomness §7.1 step 5's source. Asked for four independent draws, one per timestamp.
     * @param secp the BIP-340 verifier, defaulting to [Secp256k1Ops.FAIL_CLOSED]. An
     *   [SignatureVerdict.INVALID] verdict refuses; [SeamAnswer.Unavailable] emits and is recorded
     *   in [SealedMessage.notPerformedHere] as not checked (§17).
     * @param recipientRelayHint §7.1 step 3's optional relay URL, the third element of the `p` tag.
     *   Emitted verbatim when given and omitted entirely when not; this library neither parses nor
     *   validates a relay URL beyond §4.3's tag-value bound.
     * @param limits §4.3's bounds on the rumor, injected because §4.3 says they SHOULD be
     *   configurable. The seal's and the wrap's are [EnvelopeLimits]' and are not a client's choice.
     * @throws EnvelopeException naming which of §7.1's rules refused, and never echoing an input.
     */
    public fun seal(
        rumor: WireEvent,
        recipientPubkey: String,
        sender: Signer,
        ephemeral: EphemeralSigners = EphemeralSigners.FAIL_CLOSED,
        clock: NenyaClock = NenyaClock.FAIL_CLOSED,
        randomness: Randomness = Randomness.FAIL_CLOSED,
        secp: Secp256k1Ops = Secp256k1Ops.FAIL_CLOSED,
        recipientRelayHint: String? = null,
        limits: WireLimits = WireLimits.DEFAULT,
    ): SealedMessage {
        val senderKey = publicKeyOf(sender)
        val recipient = checkedKey(recipientPubkey, EnvelopeRejection.RECIPIENT_KEY_MALFORMED)
        if (recipient == senderKey) {
            throw refuse(
                EnvelopeRejection.RECIPIENT_IS_SENDER,
                "§7.1 permits refusing a message whose recipient equals its sender, and Nenya " +
                    "applies that to itself; the sender's own copy is step 4's second wrap, which " +
                    "this function produces rather than takes",
            )
        }
        // §7.2's rule mirrored onto the write path, before a single byte is encrypted or signed:
        // a rumor claiming somebody else's key is one every conformant reader MUST discard.
        if (rumor.pubkey != senderKey) {
            throw refuse(
                EnvelopeRejection.RUMOR_NOT_SENDERS,
                "§7.2: a rumor is unsigned, so its `pubkey` is a claim, and the seal's signature is " +
                    "what binds it; a rumor whose `pubkey` is not the sealing key's would be " +
                    "discarded as an impersonation by every conformant reader",
            )
        }

        val rumorJson = rumorJson(rumor, limits)
        val rumorBytes = utf8Size(rumorJson, EnvelopeRejection.RUMOR_MALFORMED)
        if (rumorBytes > EnvelopeLimits.MAX_RUMOR_JSON_BYTES) {
            throw refuse(
                EnvelopeRejection.RUMOR_TOO_LARGE,
                "the rumor's JSON is $rumorBytes bytes and §7.1 step 1 bounds it at " +
                    "${EnvelopeLimits.MAX_RUMOR_JSON_BYTES}; a larger one produces a seal that " +
                    "cannot be wrapped, so the sender would emit a message nobody can open",
            )
        }
        val hint = checkedRelayHint(recipientRelayHint)
        val now = clockReading(clock)

        val state = Emission(
            rumorJson = rumorJson,
            senderKey = senderKey,
            recipientKey = recipient,
            sender = sender,
            ephemeral = ephemeral,
            randomness = randomness,
            secp = secp,
            relayHint = hint,
            now = now,
        )
        // Recipient first, so the throwaway key the self copy is refused for reusing is always the
        // recipient copy's. Both are built before either is published, which is what makes step 4
        // all-or-nothing rather than a recipient copy with no matching self copy.
        val toRecipient = state.copyFor(recipient)
        val toSelf = state.copyFor(senderKey)
        return Sealed(toRecipient, toSelf, state.notPerformed())
    }

    // -----------------------------------------------------------------------------------------
    // One message being built: the values both copies share, and the two things that accumulate
    // across them — the throwaway keys already handed out, and whether every signature was
    // actually verified.
    // -----------------------------------------------------------------------------------------

    private class Emission(
        val rumorJson: String,
        val senderKey: String,
        val recipientKey: String,
        val sender: Signer,
        val ephemeral: EphemeralSigners,
        val randomness: Randomness,
        val secp: Secp256k1Ops,
        val relayHint: String?,
        val now: Long,
    ) {

        /** Every throwaway key handed out so far — §7.1 step 4's "MUST differ between the two". */
        private val throwawayKeys = mutableListOf<String>()

        /** False as soon as one verdict was [SeamAnswer.Unavailable]; §17's over-claim guard. */
        private var everySignatureVerified: Boolean = true

        fun notPerformed(): Set<SeamCapability> =
            if (everySignatureVerified) NOTHING_UNPERFORMED else BIP340_NOT_PERFORMED

        /** §7.1 steps 2 to 5 for one addressee. */
        fun copyFor(addressee: String): OutgoingWrap {
            val sealJson = sealFor(addressee)

            val throwaway = OneShotSigner(freshSigner())
            val throwawayKey = publicKeyOf(throwaway)
            if (throwawayKey == senderKey || throwawayKey == recipientKey) {
                throw refuse(
                    EnvelopeRejection.EPHEMERAL_KEY_IS_IDENTITY,
                    "§7.1 step 4: the throwaway keypair MUST equal neither party's key; a wrap " +
                        "signed by a key either party uses elsewhere links the two, which is the " +
                        "correlation the construction exists to remove",
                )
            }
            if (throwawayKey in throwawayKeys) {
                throw refuse(
                    EnvelopeRejection.EPHEMERAL_KEY_REUSED,
                    "§7.1 steps 3 and 4: a freshly generated keypair, new for every single wrap, " +
                        "and different between the two copies of one message; this one-time signer " +
                        "handed out a key it had already handed out",
                )
            }
            throwawayKeys += throwawayKey

            val createdAt = randomisedCreatedAt()
            val content = ciphertext(throwaway, addressee, sealJson)
            if (content.length > EnvelopeLimits.MAX_WRAP_CONTENT_CHARS) {
                throw refuse(
                    EnvelopeRejection.WRAP_TOO_LARGE_TO_EMIT,
                    "the wrap's content is ${content.length} characters and §4.3 bounds a " +
                        "kind:${NenyaKind.GIFT_WRAP} content at " +
                        "${EnvelopeLimits.MAX_WRAP_CONTENT_CHARS}, which is the base64 of the " +
                        "largest payload NIP-44 can produce",
                )
            }
            val tag = if (relayHint == null) {
                listOf(RECIPIENT_TAG, addressee)
            } else {
                listOf(RECIPIENT_TAG, addressee, relayHint)
            }
            val wrap = WireEvent(throwawayKey, createdAt, NenyaKind.GIFT_WRAP, listOf(tag), content)
            val written = write(wrap, throwaway, WRAP_LIMITS, EnvelopeRejection.WRAP_TOO_LARGE_TO_EMIT)
            return Wrap(written.json, written.id, addressee)
        }

        /** §7.1 step 2: one seal per copy, NIP-44-encrypted to that copy's addressee. */
        private fun sealFor(addressee: String): String {
            val createdAt = randomisedCreatedAt()
            val content = ciphertext(sender, addressee, rumorJson)
            if (content.length > EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES) {
                throw sealTooLarge(content.length, null)
            }
            // §7.1 step 2: tags empty. Not "no tags Nenya writes" — empty, and the read procedure
            // rejects a seal whose tags are not.
            val seal = WireEvent(senderKey, createdAt, NenyaKind.SEAL, emptyList(), content)
            val written = write(seal, sender, SEAL_LIMITS, EnvelopeRejection.SEAL_TOO_LARGE_TO_EMIT)
            // Characters, and they are bytes: every field of a seal is ASCII — hex for the id, the
            // pubkey and the signature, digits for `created_at` and `kind`, `[]` for the tags §7.1
            // step 2 requires be empty, and a NIP-44 payload [ciphertext] has already held to
            // printable ASCII. The serialisation the writer measured is the shorter array form, so
            // this is the measurement that decides whether the seal is a NIP-44 plaintext.
            val bytes = written.json.length
            if (bytes > EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES) throw sealTooLarge(bytes, null)
            return written.json
        }

        /**
         * Recompute the id, sign it, verify it when a verifier is injected, and write §7.1's
         * object form — in that order, which is §4.1's.
         *
         * The signature goes over the **id this library recomputed**, never over anything the
         * signer was in a position to choose: [Signer.signEvent] takes the canonical serialisation
         * and the verifier is handed [EventId.of]'s bytes, so a signer that signed something else
         * produces a verdict of [SignatureVerdict.INVALID] rather than an event that passes.
         */
        private fun write(
            event: WireEvent,
            signer: Signer,
            limits: WireLimits,
            tooLarge: EnvelopeRejection,
        ): Written {
            val serialisation = try {
                event.canonicalSerialisation(limits)
            } catch (refused: IllegalArgumentException) {
                throw refuse(tooLarge, boundsMessage(tooLarge), refused)
            }
            val id = EventId.of(event, limits)
            val signature = signature(signer, serialisation)
            verifyOnEmit(event.pubkey, id, signature)
            val json = try {
                EventJson.writeSigned(event, id, signature, limits)
            } catch (refused: IllegalArgumentException) {
                throw refuse(tooLarge, boundsMessage(tooLarge), refused)
            }
            return Written(json, id)
        }

        /**
         * §3's rule with teeth: a signature this library is about to emit is checked against the
         * id this library computed, by a verifier the client injected.
         *
         * Three answers and three outcomes, which is the whole reason [SeamAnswer] has three
         * shapes: a verdict of [SignatureVerdict.VALID] emits, [SignatureVerdict.INVALID] refuses,
         * and [SeamAnswer.Unavailable] emits and is recorded as not performed. Reporting the third
         * as either of the first two is the §17 over-claim, in the one direction — outgoing events
         * — where nobody else is watching.
         */
        private fun verifyOnEmit(publicKeyHex: String, id: EventId, signature: String) {
            val verdict = secp.verifySchnorr(decodeHex(publicKeyHex), id.bytes(), decodeHex(signature))
            when (verdict) {
                is SeamAnswer.Unavailable -> everySignatureVerified = false
                is SeamAnswer.Provided -> if (verdict.value != SignatureVerdict.VALID) {
                    throw refuse(
                        EnvelopeRejection.SIGNATURE_DOES_NOT_VERIFY,
                        "the injected BIP-340 verifier checked a signature this library was about " +
                            "to emit, against the id this library recomputed (§4.1), and refused " +
                            "it; §3 forbids accepting a signer's claim about what it signed beyond " +
                            "the bytes it returned",
                    )
                }
            }
        }

        private fun freshSigner(): Signer {
            val answer = ephemeral.fresh()
            return when (answer) {
                is SeamAnswer.Provided -> answer.value
                is SeamAnswer.Unavailable -> throw refuse(
                    EnvelopeRejection.EPHEMERAL_SIGNER_UNAVAILABLE,
                    "§7.1 step 3 requires a freshly generated keypair for every single wrap and the " +
                        "injected one-time signer answered unavailable (${answer.capability.name}); " +
                        "a library that minted one itself would be performing key generation it has " +
                        "no dependency for",
                )
            }
        }

        private fun ciphertext(signer: Signer, addressee: String, plaintext: String): String {
            val answer = signer.nip44Encrypt(addressee, plaintext)
            val payload = when (answer) {
                is SeamAnswer.Provided -> answer.value
                is SeamAnswer.Unavailable -> throw refuse(
                    EnvelopeRejection.ENCRYPTION_UNAVAILABLE,
                    "§7.1 encrypts at both layers through the injected signer, and it answered " +
                        "unavailable (${answer.capability.name})",
                )
            }
            if (payload.isEmpty()) {
                throw refuse(
                    EnvelopeRejection.CIPHERTEXT_MALFORMED,
                    "the injected signer returned an empty NIP-44 payload; NIP-44 refuses an empty " +
                        "plaintext and every payload it produces carries a version byte, a nonce " +
                        "and a MAC",
                )
            }
            for (character in payload) {
                if (character.code < PRINTABLE_ASCII_FIRST || character.code > PRINTABLE_ASCII_LAST) {
                    throw refuse(
                        EnvelopeRejection.CIPHERTEXT_MALFORMED,
                        "the injected signer returned a NIP-44 payload carrying a character outside " +
                            "printable ASCII; NIP-44 v2's payload is base64, and a reserved future " +
                            "version is base64 behind a `#`",
                    )
                }
            }
            return payload
        }

        private fun signature(signer: Signer, serialisation: String): String {
            val answer = signer.signEvent(serialisation)
            val signature = when (answer) {
                is SeamAnswer.Provided -> answer.value
                is SeamAnswer.Unavailable -> throw refuse(
                    EnvelopeRejection.SIGNATURE_UNAVAILABLE,
                    "§7.1 signs the seal with the sender's key and the wrap with the throwaway one, " +
                        "and the injected signer answered unavailable (${answer.capability.name})",
                )
            }
            if (signature.length != EventJson.SIGNATURE_HEX_LENGTH || !isLowerHex(signature)) {
                throw refuse(
                    EnvelopeRejection.SIGNATURE_MALFORMED,
                    "a nostr signature is exactly ${EventJson.SIGNATURE_HEX_LENGTH} lowercase hex " +
                        "characters — a 64-byte BIP-340 signature — and this one is " +
                        "${signature.length} character(s) in some other shape; §4.3 requires " +
                        "rejecting a value of the wrong length rather than padding or truncating it",
                )
            }
            return signature
        }

        /**
         * §7.1 step 5's uniform draw from `[max(0, now − 172 800), now]`.
         *
         * Rejection sampling rather than a bare modulo, because a modulo over a 32-bit draw is
         * biased towards the low end of any window that is not a power of two — and this window is
         * 172 801 values wide. The number of attempts is **bounded**: a source that never lands in
         * range is refused as [EnvelopeRejection.RANDOMNESS_UNUSABLE], never unwound into a loop
         * that does not terminate and never resolved by falling back to `now`, which §7.1 step 5
         * forbids in as many words.
         */
        private fun randomisedCreatedAt(): Long {
            val floor = if (now > RANDOMISATION_WINDOW_SECONDS) now - RANDOMISATION_WINDOW_SECONDS else 0L
            val width = now - floor + 1L
            // The largest multiple of the window that fits in 2^32; a draw at or above it is the
            // biased tail and is thrown away rather than folded in.
            val ceiling = (DRAW_RANGE / width) * width
            var attempt = 0
            while (attempt < MAX_DRAWS) {
                attempt++
                val drawn = unsignedDraw()
                if (drawn < ceiling) return floor + drawn % width
            }
            throw refuse(
                EnvelopeRejection.RANDOMNESS_UNUSABLE,
                "the injected randomness source produced $MAX_DRAWS draws in a row outside the " +
                    "range a uniform value can be taken from; §7.1 step 5 requires a uniform draw " +
                    "into the past and forbids substituting the true time for one",
            )
        }

        /** One draw: [DRAW_BYTES] bytes read big-endian as an unsigned 32-bit value. */
        private fun unsignedDraw(): Long {
            val answer = randomness.randomBytes(DRAW_BYTES)
            val bytes = when (answer) {
                is SeamAnswer.Provided -> answer.value
                is SeamAnswer.Unavailable -> throw refuse(
                    EnvelopeRejection.RANDOMNESS_UNAVAILABLE,
                    "§7.1 step 5 randomises every seal's and every wrap's created_at from the " +
                        "injected source and the source answered unavailable " +
                        "(${answer.capability.name}); there is no fallback",
                )
            }
            if (bytes.size != DRAW_BYTES) {
                throw refuse(
                    EnvelopeRejection.RANDOMNESS_UNUSABLE,
                    "the injected randomness source was asked for $DRAW_BYTES bytes and returned " +
                        "${bytes.size}; §4.3 requires rejecting a value of the wrong length rather " +
                        "than padding or truncating it",
                )
            }
            var value = 0L
            for (byte in bytes) value = (value shl BITS_PER_BYTE) or (byte.toLong() and BYTE_MASK)
            return value
        }

        private fun sealTooLarge(size: Int, cause: Throwable?): EnvelopeException = refuse(
            EnvelopeRejection.SEAL_TOO_LARGE_TO_EMIT,
            "the kind:${NenyaKind.SEAL} seal came to $size bytes and NIP-44's largest plaintext is " +
                "${EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES}, so it cannot be wrapped at all",
            cause,
        )

        private fun boundsMessage(reason: EnvelopeRejection): String = when (reason) {
            EnvelopeRejection.SEAL_TOO_LARGE_TO_EMIT ->
                "the kind:${NenyaKind.SEAL} seal exceeds what NIP-44 can carry as a plaintext " +
                    "(${EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES} bytes)"
            else ->
                "the kind:${NenyaKind.GIFT_WRAP} wrap exceeds §4.3's envelope bounds " +
                    "(${EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES} bytes of JSON over a content of " +
                    "at most ${EnvelopeLimits.MAX_WRAP_CONTENT_CHARS})"
        }
    }

    /** An event written: its §7.1 object form and the id this library recomputed for it. */
    private class Written(val json: String, val id: EventId)

    /**
     * A throwaway signer, usable once.
     *
     * §7.1 step 3 gives every wrap its own keypair, which means one encryption and one signature
     * per key and no decryption at all. The guard is internal to this file rather than a rule in a
     * comment because the failure it prevents — a second wrap encrypted or signed under a key
     * already used — is invisible in the output: two wraps sharing a key are linked to anyone
     * reading the relay, and every test of the *contents* still passes.
     *
     * [Signer.publicKey] is deliberately unguarded: it returns what the seam already decided and
     * asking twice reveals nothing.
     */
    internal class OneShotSigner(private val delegate: Signer) : Signer {

        private var encrypted = false

        private var signed = false

        override fun publicKey(): SeamAnswer<String> = delegate.publicKey()

        override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> {
            if (signed) throw reused("sign a second event")
            signed = true
            return delegate.signEvent(canonicalSerialisation)
        }

        override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> {
            if (encrypted) throw reused("encrypt a second payload")
            encrypted = true
            return delegate.nip44Encrypt(counterpartyPublicKeyHex, plaintext)
        }

        override fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String> =
            throw reused("decrypt")

        /** Names neither the key nor the delegate's own string form. */
        override fun toString(): String = "GiftWrap.OneShotSigner(used=$signed)"

        private fun reused(what: String): EnvelopeException = refuse(
            EnvelopeRejection.THROWAWAY_SIGNER_REUSED,
            "a throwaway signer was asked to $what; §7.1 step 3 gives every gift wrap its own " +
                "keypair, so each is used for exactly one encryption and one signature and never " +
                "for a decryption",
        )
    }

    /** The single implementation of [OutgoingWrap]; see that interface for why it is private. */
    private class Wrap(
        override val json: String,
        override val id: EventId,
        override val addressee: String,
    ) : OutgoingWrap {

        /**
         * Names neither the addressee, the id, the throwaway key nor a byte of the wrap.
         *
         * §12 item 2 puts the counterparty pubkey among the values that MUST NOT appear outside an
         * encrypted event, §12 item 11 covers the id of a private message, and §7.2 forbids the
         * wrap's own key being displayed at all. The size is the one thing a debugging line can
         * safely carry.
         */
        override fun toString(): String = "OutgoingWrap(jsonChars=${json.length})"
    }

    /** The single implementation of [SealedMessage]. */
    private class Sealed(
        override val toRecipient: OutgoingWrap,
        override val toSelf: OutgoingWrap,
        override val notPerformedHere: Set<SeamCapability>,
    ) : SealedMessage {

        override fun toString(): String =
            "SealedMessage(toRecipient=$toRecipient, toSelf=$toSelf, " +
                "notPerformedHere=${notPerformedHere.map { it.name }})"
    }

    // -----------------------------------------------------------------------------------------
    // Argument checks, and the two limit sets §4.3 fixes for the two envelope kinds.
    // -----------------------------------------------------------------------------------------

    private fun publicKeyOf(signer: Signer): String {
        val answer = signer.publicKey()
        val key = when (answer) {
            is SeamAnswer.Provided -> answer.value
            is SeamAnswer.Unavailable -> throw refuse(
                EnvelopeRejection.SIGNER_PUBLIC_KEY_UNAVAILABLE,
                "§7.1 needs a signer's own public key for the event it is about to sign, and the " +
                    "signer answered unavailable (${answer.capability.name})",
            )
        }
        return checkedKey(key, EnvelopeRejection.SIGNER_PUBLIC_KEY_MALFORMED)
    }

    /**
     * §4.3's pubkey, in the form this library is required to **emit**: exactly 64 lowercase hex
     * characters.
     *
     * §4.3's accept-and-normalise rule is for values read off the wire; this is the write path, and
     * the value goes verbatim into a `pubkey` field whose bytes §4.1 hashes into the id the signer
     * signs. Normalising would emit an event whose id nobody signed.
     */
    private fun checkedKey(key: String, reason: EnvelopeRejection): String {
        if (key.length != WireEvent.PUBKEY_HEX_LENGTH || !isLowerHex(key)) {
            throw refuse(
                reason,
                "§4.3 fixes an x-only pubkey as exactly ${WireEvent.PUBKEY_HEX_LENGTH} hex " +
                    "characters and requires an implementation to emit lowercase hex; this one is " +
                    "${key.length} character(s) in some other shape",
            )
        }
        return key
    }

    private fun rumorJson(rumor: WireEvent, limits: WireLimits): String = try {
        EventJson.writeUnsigned(rumor, EventId.of(rumor, limits), limits)
    } catch (refused: IllegalArgumentException) {
        throw refuse(
            EnvelopeRejection.RUMOR_MALFORMED,
            "the rumor cannot be written as §7.1's object form; the wire layer's own reason is " +
                "kept as the cause of this exception",
            refused,
        )
    }

    private fun checkedRelayHint(hint: String?): String? {
        if (hint == null) return null
        val bytes = utf8Size(hint, EnvelopeRejection.RELAY_HINT_MALFORMED)
        if (bytes > WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES) {
            throw refuse(
                EnvelopeRejection.RELAY_HINT_TOO_LONG,
                "the relay hint is $bytes UTF-8 bytes and §4.3 bounds a tag value at " +
                    "${WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES}; §4.3 measures this one in bytes, " +
                    "not characters",
            )
        }
        return hint
    }

    private fun clockReading(clock: NenyaClock): Long {
        val answer = clock.now()
        val now = when (answer) {
            is SeamAnswer.Provided -> answer.value
            is SeamAnswer.Unavailable -> throw refuse(
                EnvelopeRejection.CLOCK_UNAVAILABLE,
                "§7.1 step 5 randomises every timestamp in the envelope relative to the injected " +
                    "clock and it answered unavailable (${answer.capability.name}); §4.6 makes that " +
                    "clock the only time this library is allowed to know",
            )
        }
        if (now < 0L) {
            throw refuse(
                EnvelopeRejection.CLOCK_READING_BEFORE_EPOCH,
                "the injected clock reported a time before 1970; §4.3 fixes every timestamp as a " +
                    "non-negative integer, so no window can honestly be drawn from this reading",
            )
        }
        return now
    }

    /**
     * The UTF-8 byte count of [text], or a refusal when it has none.
     *
     * Text with no UTF-8 encoding is refused rather than measured with a substitute character, for
     * §4.1's reason: the JVM's encoder writes `?` and JavaScript's writes U+FFFD, so a lenient
     * measurement is two different sizes and, one layer down, two different event ids.
     */
    private fun utf8Size(text: String, reason: EnvelopeRejection): Int =
        strictUtf8OrNull(text)?.size ?: throw refuse(
            reason,
            "the text carries an unpaired UTF-16 surrogate, which has no UTF-8 encoding; §4.1 " +
                "requires rejecting it rather than substituting a replacement character",
        )

    /** §4.3's canonical hex alphabet: digits and lowercase letters, and nothing else. */
    private fun isLowerHex(text: String): Boolean {
        for (character in text) {
            val known = character in '0'..'9' || character in 'a'..'f'
            if (!known) return false
        }
        return true
    }

    /** Only ever reached with a value [isLowerHex] and a fixed length have already accepted. */
    private fun decodeHex(lowercaseHex: String): ByteArray {
        val out = ByteArray(lowercaseHex.length / 2)
        for (index in out.indices) {
            val high = nibble(lowercaseHex[2 * index])
            val low = nibble(lowercaseHex[2 * index + 1])
            out[index] = ((high shl NIBBLE_BITS) or low).toByte()
        }
        return out
    }

    private fun nibble(character: Char): Int =
        if (character in '0'..'9') character - '0' else character - 'a' + DECIMAL_RADIX

    private fun refuse(
        reason: EnvelopeRejection,
        message: String,
        cause: Throwable? = null,
    ): EnvelopeException = EnvelopeException(reason, message, cause)

    /** `["p", "<addressee>", "<relay hint>"]` — §7.1 step 3's single tag. */
    private const val RECIPIENT_TAG: String = "p"

    /**
     * §4.3's bounds for a `kind:13` seal: NIP-44's plaintext ceiling, and one tag's worth of room
     * that a conformant seal never uses, because §7.1 step 2 requires its `tags` be empty.
     */
    private val SEAL_LIMITS: WireLimits = WireLimits(
        maxSerialisedEventBytes = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES,
        maxTagsPerEvent = 1,
        maxTagValueBytes = WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES,
        maxContentBytes = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES,
    )

    /** §4.3's bounds for a `kind:1059` wrap: the one `p` tag §7.1 requires, and nothing else. */
    private val WRAP_LIMITS: WireLimits = WireLimits(
        maxSerialisedEventBytes = EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES,
        maxTagsPerEvent = 1,
        maxTagValueBytes = WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES,
        maxContentBytes = EnvelopeLimits.MAX_WRAP_CONTENT_CHARS,
    )

    private val NOTHING_UNPERFORMED: Set<SeamCapability> =
        readOnlySetOf(emptyList<SeamCapability>())

    private val BIP340_NOT_PERFORMED: Set<SeamCapability> =
        readOnlySetOf(listOf(SeamCapability.BIP340_VERIFICATION))

    /** 4 bytes per draw: 2^32 values, which is 24 853 whole windows with room to reject the tail. */
    private const val DRAW_BYTES: Int = 4

    /** 2^32, the number of values [DRAW_BYTES] bytes spell, as the `Long` they are read into. */
    private const val DRAW_RANGE: Long = 4_294_967_296L

    /**
     * 64 attempts. §7.1 step 5's window is 172 801 values wide, so an unbiased source rejects a
     * draw with probability below 1 in 12 000 and 64 consecutive rejections is not a run of bad
     * luck — it is a source that cannot produce one, and the bound is what turns that into a
     * refusal rather than a hang.
     */
    internal const val MAX_DRAWS: Int = 64

    private const val BITS_PER_BYTE: Int = 8

    private const val BYTE_MASK: Long = 0xffL

    private const val NIBBLE_BITS: Int = 4

    private const val DECIMAL_RADIX: Int = 10

    private const val PRINTABLE_ASCII_FIRST: Int = 0x21

    private const val PRINTABLE_ASCII_LAST: Int = 0x7e
}
