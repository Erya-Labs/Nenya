package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeKeyRanges
import dev.eryalabs.nenya.seam.Nip44PayloadSigner
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.seam.provided
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireLimits
import dev.eryalabs.nenya.wire.appendCanonicalString

/**
 * The envelope builder §7.1's **read** path is measured against: every layer written by hand, so a
 * control can corrupt exactly one of them and leave the others honest.
 *
 * ### Why a builder and not `GiftWrap.seal`
 *
 * `GiftWrap.seal` refuses to emit anything malformed, which is its job — so it cannot produce a wrap
 * whose `id` disagrees with its fields, a seal claiming somebody else's key, or a rumor carrying a
 * `sig`. Every negative control the read path needs is exactly one of those. So this file writes
 * §7.1's object form itself, through **the library's own escaping routine**
 * ([appendCanonicalString], which §7.1 requires be the only one), with the `id` and `sig` taken as
 * parameters rather than computed.
 *
 * That freedom is also the risk: a writer that disagreed with `EventJson` about a byte would produce
 * controls that fail for the wrong reason, and a reviewer would have no way to tell. So
 * [GiftWrapOpenTest] holds [rawJson] byte-equal to `EventJson.writeSigned` and `writeUnsigned` over
 * an honest event before it uses it for anything, and every control below builds its layer honestly
 * and then changes **one** thing.
 *
 * ### One corrupt layer at a time, and the outer ones re-done honestly
 *
 * Changing a seal means re-encrypting it into its wrap, re-computing that wrap's `id` and re-signing
 * it — otherwise the read stops at the wrap and the control proves nothing about the seal. Every
 * composer here does that by construction: [wrapAround] takes a seal's JSON and produces an honest
 * wrap of it, whatever the seal is.
 *
 * ### Nothing here forges under a conversation key
 *
 * `FakeCrypto`'s header forbids it in as many words: calling `FakeCrypto.encrypt` to manufacture a
 * payload a seam is then expected to reject would be a test of that file rather than of Nenya. Every
 * payload below is produced by a [Nip44PayloadSigner] holding a key of its own, and the one control
 * that needs a payload the reader cannot open uses [disturbed] — an honest payload with a byte
 * changed, which is the failure mode that file does model.
 */
internal class Envelope(

    /** The honest sender: seals with its own key, and is the key §7.2 attributes terms to. */
    val sender: Nip44PayloadSigner = EnvelopeFixtures.senderSigner(),

    /** The throwaway key §7.1 step 3 signs the wrap with. One per envelope, as step 3 requires. */
    val throwaway: Nip44PayloadSigner = Nip44PayloadSigner(FakeKey(FakeKeyRanges.EPHEMERAL)),
) {

    // -----------------------------------------------------------------------------------------
    // §7.1 step 1: the rumor.
    // -----------------------------------------------------------------------------------------

    /** The rumor's JSON as §7.1 step 1 emits it: `id` present, and no `sig` key at all. */
    fun rumorJson(rumor: WireEvent = EnvelopeFixtures.rumor(), limits: WireLimits = WireLimits.DEFAULT): String =
        rawJson(idHexOf(rumor, limits), rumor, null)

    /** The same rumor carrying a `sig` — the one thing §7.1 step 8 rejects "with any value at all". */
    fun signedRumorJson(
        rumor: WireEvent = EnvelopeFixtures.rumor(),
        signer: Signer = sender,
        limits: WireLimits = WireLimits.DEFAULT,
    ): String = rawJson(idHexOf(rumor, limits), rumor, signatureOf(rumor, signer, limits))

    /** The same rumor carrying `"sig":""`, which §7.1 step 8 names by itself. */
    fun emptySigRumorJson(
        rumor: WireEvent = EnvelopeFixtures.rumor(),
        limits: WireLimits = WireLimits.DEFAULT,
    ): String = rawJson(idHexOf(rumor, limits), rumor, "")

    /** A rumor whose `content` changed after its `id` was taken — §4.1's central refusal. */
    fun rumorWithStaleId(
        rumor: WireEvent = EnvelopeFixtures.rumor(),
        limits: WireLimits = WireLimits.DEFAULT,
    ): String = rawJson(idHexOf(rumor, limits), changed(rumor), null)

    // -----------------------------------------------------------------------------------------
    // §7.1 step 2: the seal.
    // -----------------------------------------------------------------------------------------

    /**
     * §7.1 step 2's `kind:13`, carrying [plaintext] NIP-44-encrypted to [addressee].
     *
     * @param by the key that actually encrypts and signs. Honestly the sender's.
     * @param claiming the `pubkey` the seal **says** it is. Honestly [by]'s own key; a control that
     *   passes somebody else's builds the seal §7.2 exists to refuse — one that claims a key it does
     *   not hold, whose content therefore does not decrypt under that key's conversation.
     * @param signed `false` writes the seal with no `sig` key at all.
     * @param staleId keeps the `id` of the seal as it was **before** its content was changed.
     */
    fun sealJson(
        plaintext: String = rumorJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
        by: Nip44PayloadSigner = sender,
        claiming: String = by.key.hex,
        kind: Int = NenyaKind.SEAL,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = SEAL_CREATED_AT,
        signed: Boolean = true,
        staleId: Boolean = false,
    ): String {
        val content = by.nip44Encrypt(addressee, plaintext).provided()
        val seal = WireEvent(claiming, createdAt, kind, tags, content)
        val idHex = if (staleId) idHexOf(changed(seal), SEAL_LIMITS) else idHexOf(seal, SEAL_LIMITS)
        val sig = if (signed) signatureOf(seal, by, SEAL_LIMITS) else null
        return rawJson(idHex, seal, sig)
    }

    // -----------------------------------------------------------------------------------------
    // §7.1 step 3: the wrap.
    // -----------------------------------------------------------------------------------------

    /**
     * §7.1 step 3's `kind:1059` around [sealJson], honest in every respect but what a caller varies.
     *
     * @param tags the wrap's tags, defaulting to step 3's single `["p", "<addressee>"]`. `null` is
     *   not permitted here: a control that wants no `p` tag passes `emptyList()`, which says so.
     */
    fun wrapAround(
        sealJson: String = sealJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
        by: Nip44PayloadSigner = throwaway,
        kind: Int = NenyaKind.GIFT_WRAP,
        tags: List<List<String>> = listOf(listOf(RECIPIENT_TAG, addressee)),
        createdAt: Long = WRAP_CREATED_AT,
        signed: Boolean = true,
        content: String? = null,
    ): String {
        val wrap = wrapEvent(sealJson, addressee, by, kind, tags, createdAt, content)
        val sig = if (signed) signatureOf(wrap, by, EnvelopeFixtures.WRAP_LIMITS) else null
        return rawJson(idHexOf(wrap, EnvelopeFixtures.WRAP_LIMITS), wrap, sig)
    }

    /**
     * A wrap whose `content` was changed **after** its `id` and `sig` were taken over the honest one.
     *
     * §4.1's central rule, in the shape a relay or a middlebox produces it: every other field still
     * matches, and only re-hashing the event catches it. Nothing below the id check runs, which is
     * what [GiftWrapOpenTest] asserts by counting the reader's decryptions.
     */
    fun wrapWithStaleId(
        sealJson: String = sealJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
    ): String {
        val honest = wrapEvent(sealJson, addressee)
        val tampered = changedContent(honest, disturbed(honest.content))
        return rawJson(
            idHexOf(honest, EnvelopeFixtures.WRAP_LIMITS),
            tampered,
            signatureOf(honest, throwaway, EnvelopeFixtures.WRAP_LIMITS),
        )
    }

    /**
     * A wrap whose `content` changed, whose `id` was **recomputed** for the change, and which still
     * carries the signature over the old id.
     *
     * The upgrade of [wrapWithStaleId] that an attacker who has read §4.1 produces: the id check
     * passes, so §7.1 step 4 is the only thing left that can catch it — and a reader with no verifier
     * gets as far as the decryption and is stopped there instead.
     */
    fun wrapWithRecomputedIdAndStaleSignature(
        sealJson: String = sealJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
    ): String {
        val honest = wrapEvent(sealJson, addressee)
        val tampered = changedContent(honest, disturbed(honest.content))
        return rawJson(
            idHexOf(tampered, EnvelopeFixtures.WRAP_LIMITS),
            tampered,
            signatureOf(honest, throwaway, EnvelopeFixtures.WRAP_LIMITS),
        )
    }

    /**
     * An honest wrap with **only** its signature replaced: a genuine signature by another key, over
     * the same id.
     *
     * The control that pins how the two verifier modes differ on one message. With a verifier it is
     * `WRAP_SIGNATURE_INVALID`; with none the wrap **opens**, because nothing else about it changed
     * and §7.1 requires the read continue past step 4 — and [OpenedMessage.wrapSignature] then says
     * `NOT_CHECKED` rather than implying the signature was fine.
     */
    fun wrapWithForeignSignature(
        sealJson: String = sealJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
        signer: Signer = sender,
    ): String {
        val honest = wrapEvent(sealJson, addressee)
        return rawJson(
            idHexOf(honest, EnvelopeFixtures.WRAP_LIMITS),
            honest,
            signatureOf(honest, signer, EnvelopeFixtures.WRAP_LIMITS),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Primitives.
    // -----------------------------------------------------------------------------------------

    /** The wrap event §7.1 step 3 builds, before an `id` or a `sig` is decided. */
    fun wrapEvent(
        sealJson: String = sealJson(),
        addressee: String = EnvelopeFixtures.recipientKey(),
        by: Nip44PayloadSigner = throwaway,
        kind: Int = NenyaKind.GIFT_WRAP,
        tags: List<List<String>> = listOf(listOf(RECIPIENT_TAG, addressee)),
        createdAt: Long = WRAP_CREATED_AT,
        content: String? = null,
    ): WireEvent = WireEvent(
        by.key.hex,
        createdAt,
        kind,
        tags,
        content ?: by.nip44Encrypt(addressee, sealJson).provided(),
    )

    private companion object {

        /** §7.1 step 3's single tag name. */
        const val RECIPIENT_TAG: String = "p"

        /**
         * The seal's and the wrap's `created_at`, pinned inside §7.1 step 5's window below
         * `EnvelopeFixtures.NOW` and different from each other, because step 5 draws them
         * independently and a fixture that shared one would hide a reader that confused the layers.
         *
         * Neither is ever compared against a clock: §4.6 and §7.1 both forbid it, and `open` takes
         * no clock at all.
         */
        const val SEAL_CREATED_AT: Long = EnvelopeFixtures.NOW - 3_600L

        const val WRAP_CREATED_AT: Long = EnvelopeFixtures.NOW - 7_200L

        /** §4.3's bounds for writing a `kind:13` seal, as `GiftWrap` uses them. */
        val SEAL_LIMITS: WireLimits = EnvelopeFixtures.SEAL_LIMITS
    }
}

/**
 * §7.1's object form with the `id` and the `sig` **verbatim**, in `EventJson`'s key order.
 *
 * Every string goes through [appendCanonicalString], which §7.1 requires be the one escaping routine
 * in the implementation, so this writer and `EventJson` cannot disagree about a byte — and
 * `GiftWrapOpenTest` holds them equal over an honest event rather than taking that on trust.
 *
 * It bounds nothing, deliberately: emitting events `EventJson` would refuse is its whole purpose.
 */
internal fun rawJson(idHex: String, event: WireEvent, sigHex: String?): String {
    val out = StringBuilder()
    out.append("{\"id\":")
    out.appendCanonicalString(idHex)
    out.append(",\"pubkey\":")
    out.appendCanonicalString(event.pubkey)
    out.append(",\"created_at\":").append(event.createdAt)
    out.append(",\"kind\":").append(event.kind)
    out.append(",\"tags\":[")
    for ((index, tag) in event.tags.withIndex()) {
        if (index > 0) out.append(',')
        out.append('[')
        for ((position, value) in tag.withIndex()) {
            if (position > 0) out.append(',')
            out.appendCanonicalString(value)
        }
        out.append(']')
    }
    out.append("],\"content\":")
    out.appendCanonicalString(event.content)
    if (sigHex != null) {
        out.append(",\"sig\":")
        out.appendCanonicalString(sigHex)
    }
    out.append('}')
    return out.toString()
}

/** §4.1's id over [event], in lowercase hex. */
internal fun idHexOf(event: WireEvent, limits: WireLimits = WireLimits.DEFAULT): String =
    EventId.of(event, limits).toHex()

/** [signer]'s signature over §4.1's id of [event] — the contract `Signer.signEvent` states. */
internal fun signatureOf(event: WireEvent, signer: Signer, limits: WireLimits = WireLimits.DEFAULT): String =
    signer.signEvent(event.canonicalSerialisation(limits)).provided()

/** [event] with one distinctive tag added, so its id changes and nothing else about it is unusual. */
internal fun changed(event: WireEvent): WireEvent =
    WireEvent(event.pubkey, event.createdAt, event.kind, event.tags + listOf(listOf("x", "changed")), event.content)

/** [event] with [content] in place of its own. */
internal fun changedContent(event: WireEvent, content: String): WireEvent =
    WireEvent(event.pubkey, event.createdAt, event.kind, event.tags, content)

/**
 * An honest NIP-44 payload with **one byte changed**, so it no longer authenticates.
 *
 * The one way this test tree is allowed to produce a payload a reader cannot open: `FakeCrypto`'s
 * header says a disturbed payload getting `Unavailable` "is a real result about the code under test",
 * and forbids the alternative — manufacturing one under a conversation key, which anything at all can
 * compute and which would pass against a library with no confidentiality property whatsoever.
 *
 * The replacement character is chosen from base64's own alphabet and is never the one already there,
 * so the payload stays base64 and printable ASCII and is refused for its **MAC** rather than for its
 * shape.
 */
internal fun disturbed(payload: String): String {
    require(payload.isNotEmpty()) { "there is no byte to disturb in an empty payload" }
    val last = payload.length - 1
    val replacement = if (payload[last] == 'A') 'B' else 'A'
    return payload.substring(0, last) + replacement
}
