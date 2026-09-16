package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.text.strictUtf8OrNull

/**
 * Why an event, a bound or an event-id check was refused (§4.1, §4.3).
 *
 * The reason is part of the API and not merely diagnostic text, for the same reason
 * `DeliveryRejection` and `PaymentRejection` are. §4.3 names four separate resource bounds and
 * requires rejection rather than truncation for each, so "too big" is never a useful answer: a
 * caller told only that has no way to know whether to drop the event, raise a bound, or
 * distrust the relay that served it. Tests assert on these constants rather than on message
 * wording.
 */
public enum class WireRejection {

    /**
     * §4.3: a tag is an array of **one or more** strings, and an implementation MUST reject an
     * event containing an empty tag array.
     */
    EMPTY_TAG,

    /**
     * §4.3's companion rule: an event containing a `null` tag element MUST be rejected.
     *
     * Unreachable from Kotlin, where `List<List<String>>` makes it unrepresentable, and entirely
     * reachable from Java, where that type is `List<List<String>>` only by annotation. A relay's
     * `["e", null]` handed to [WireEvent] would otherwise surface as a `NullPointerException`
     * from the serialiser — a denial of service in the buyer's client rather than a rejection.
     *
     * A `null` **tag** — what a relay's `"tags":[null]` parses to — carries this reason too. It is
     * the same door, the same Java caller and the same denial of service, and it reached a bare
     * `NullPointerException` out of the emptiness check until a review pass noticed that the two
     * halves of one sentence were being handled opposite ways.
     *
     * §4.3's other half of that sentence, a **non-string** tag element, has no constant: a Java
     * caller can only reach that through an unchecked heap-polluting cast, and it lands as a
     * `ClassCastException` inside `String` code this package does not own.
     */
    NULL_TAG_ELEMENT,

    /** §4.3's tag-count bound ([WireLimits.maxTagsPerEvent]) exceeded. Never truncated. */
    TOO_MANY_TAGS,

    /**
     * §4.3's per-tag-value bound ([WireLimits.maxTagValueBytes]) exceeded. Measured in **UTF-8
     * bytes**, which is what §4.3 says and is not what a character count says: 1024 characters
     * of anything above `U+07FF` is at least 3072 bytes, so a char-counting implementation
     * passes every other bound test and fails only this one.
     */
    TAG_VALUE_TOO_LONG,

    /** §4.3's `content` bound ([WireLimits.maxContentBytes]) exceeded, in UTF-8 bytes. */
    CONTENT_TOO_LONG,

    /**
     * §4.3's whole-event bound ([WireLimits.maxSerialisedEventBytes]) exceeded, measured over
     * the canonical serialisation's UTF-8 bytes. See [WireEvent.canonicalSerialisation] for
     * what that measurement does and does not include.
     */
    SERIALISED_EVENT_TOO_LARGE,

    /**
     * A hex value whose character count is not the length §4.3 fixes for it — 64 for a pubkey
     * and 64 for an event id. §4.3: an implementation MUST reject a value of the wrong length
     * rather than padding or truncating it.
     */
    WRONG_LENGTH,

    /** A character outside `0-9`, `a-f`, `A-F`. Not a hex string at all. */
    NOT_HEX,

    /**
     * `created_at` is a Unix timestamp in seconds, and §4.3 fixes a timestamp as a
     * **non-negative** integer. A negative one is not a time this protocol can express.
     */
    NEGATIVE_TIMESTAMP,

    /**
     * A [WireLimits] value below one. §4.3 requires an implementation to bound anything it
     * parses; a bound of zero or less is not a smaller bound, it is a decoder that refuses
     * every event, and a caller that reached one did so by arithmetic rather than on purpose.
     */
    NON_POSITIVE_LIMIT,

    /**
     * §4.1: `content` or a tag value carries an **unpaired UTF-16 surrogate** — half of a pair, or
     * a pair in the wrong order — which is not a character and has no UTF-8 encoding, so the event
     * has no id. §4.1 requires rejecting it rather than substituting a replacement character.
     *
     * Substitution is not a safe default because platforms substitute differently: the JVM's
     * encoder writes `?` and JavaScript's writes U+FFFD, so the same event would hash to two
     * different ids and one client would call the other's events forged. A JSON parser produces
     * such a string readily from a `\uD800`-style escape.
     */
    UNPAIRED_SURROGATE,

    /**
     * §4.1's central rule: the claimed `id` is not the recomputed one. The event MUST be
     * rejected **before any other processing**, which this library enforces by producing no
     * [CheckedEvent] — the type every later decoder in this library takes as its input.
     */
    ID_MISMATCH,
}

/**
 * An event or an event-id check was refused, carrying the [reason] as data.
 *
 * One exception type for the whole wire surface, mirroring `DeliveryException` in the delivery
 * package and `PaymentException` in the payment package: a caller has one thing to catch and
 * one field to branch on.
 *
 * **The message never echoes the caller's input.** §12 item 2 names the counterparty pubkey
 * among the values that MUST NOT appear outside an encrypted event, and `content` is the
 * message body itself. A rejection message may name a *length*, a *bound* and a *reason*; it
 * may never name a pubkey, a tag value or a byte of `content`.
 */
public class WireException internal constructor(
    public val reason: WireRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * §4.3's resource bounds, injected so a client can choose its own (§4.3 says they SHOULD be
 * configurable) and so a test can pin them.
 *
 * §4.3 publishes defaults "no larger than" the four values below and requires an implementation
 * to **reject rather than truncate** when one is exceeded — a relay that returns a 100 MiB
 * "event" is not a hypothetical, and an unbounded parser is a denial of service in the buyer's
 * client.
 *
 * §4.3's fifth bound, 64 `image` tags, is deliberately **not** here: it names a tag from §5.3's
 * vocabulary, and this package knows nothing about any tag's meaning. It belongs with the tag
 * codec.
 *
 * A bound below one is refused at construction ([WireRejection.NON_POSITIVE_LIMIT]): a decoder
 * that refuses every event is not a stricter decoder.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class WireLimits(

    /** §4.3's 64 KiB serialised-event bound, in bytes. */
    public val maxSerialisedEventBytes: Int = DEFAULT_MAX_SERIALISED_EVENT_BYTES,

    /** §4.3's 512-tags-per-event bound. */
    public val maxTagsPerEvent: Int = DEFAULT_MAX_TAGS_PER_EVENT,

    /** §4.3's 1024-**bytes**-per-tag-value bound, in UTF-8 bytes. */
    public val maxTagValueBytes: Int = DEFAULT_MAX_TAG_VALUE_BYTES,

    /** §4.3's 16 KiB `content` bound, in UTF-8 bytes. */
    public val maxContentBytes: Int = DEFAULT_MAX_CONTENT_BYTES,
) {

    init {
        requirePositive(maxSerialisedEventBytes, "maxSerialisedEventBytes")
        requirePositive(maxTagsPerEvent, "maxTagsPerEvent")
        requirePositive(maxTagValueBytes, "maxTagValueBytes")
        requirePositive(maxContentBytes, "maxContentBytes")
    }

    override fun toString(): String =
        "WireLimits(maxSerialisedEventBytes=$maxSerialisedEventBytes, " +
            "maxTagsPerEvent=$maxTagsPerEvent, maxTagValueBytes=$maxTagValueBytes, " +
            "maxContentBytes=$maxContentBytes)"

    public companion object {

        /** 64 KiB — §4.3's default bound on a serialised event. */
        public const val DEFAULT_MAX_SERIALISED_EVENT_BYTES: Int = 64 * 1024

        /** 512 — §4.3's default bound on the number of tags in one event. */
        public const val DEFAULT_MAX_TAGS_PER_EVENT: Int = 512

        /** 1024 bytes — §4.3's default bound on one tag value. */
        public const val DEFAULT_MAX_TAG_VALUE_BYTES: Int = 1024

        /** 16 KiB — §4.3's default bound on `content`. */
        public const val DEFAULT_MAX_CONTENT_BYTES: Int = 16 * 1024

        /** §4.3's published defaults, and what every entry point here uses when given none. */
        public val DEFAULT: WireLimits = WireLimits()

        private fun requirePositive(value: Int, what: String) {
            if (value < 1) {
                throw WireException(
                    WireRejection.NON_POSITIVE_LIMIT,
                    "$what is $value; §4.3 requires a bound, and a bound below one refuses every " +
                        "event rather than bounding anything",
                )
            }
        }
    }
}

/**
 * The five id-bearing fields of a nostr event — everything §4.1's canonical serialisation reads,
 * and nothing else.
 *
 * `id` and `sig` are deliberately absent. They are *claims about* this value rather than part of
 * it: the id is recomputed from these five fields by [EventId.of] and compared against the claim
 * by [CheckedEvent.checkEventId], and no signature is checked, produced or claimed anywhere in
 * this library (`Secp256k1Ops` answers `Unavailable`, and §17 permits that provided nothing is
 * reported as verified when it was not).
 *
 * ### There is no JSON parser here, and that is a stated narrowing
 *
 * This library **emits** the canonical form and **recomputes** an id over a structure the caller
 * supplies. It does not turn a relay's bytes into that structure: there is no JSON library
 * available to it, and a hostile-input-hardened JSON parser is a piece of work in its own right
 * rather than a subclause of this one. So §17 item 1's write half — computing the id per §4.1 —
 * is closed here, and its read half is the embedding client's, which is also why `RelayTransport`
 * deals in `String`.
 *
 * The consequence is worth stating plainly: an id recomputed from a structure the client parsed
 * proves that *this* structure hashes to that id. If the client's parser and the relay's
 * serialiser disagree about the bytes, the recomputation is faithful to the client's reading and
 * the mismatch surfaces as [WireRejection.ID_MISMATCH] — which is the safe direction, and is
 * exactly what §4.1's "reject before any other processing" asks for.
 *
 * ### What is validated here
 *
 * The structural rules of §4.3 that are properties of an event rather than of a vocabulary: the
 * pubkey is exactly 64 hex characters, no tag array is empty, no tag element is `null`, and
 * `created_at` is non-negative. §4.3's resource bounds are enforced one step later, in
 * [canonicalSerialisation], because the largest of the four can only be measured there; see that
 * function.
 *
 * The `null` check has no Kotlin caller and is not dead code. `List<List<String>>` makes a `null`
 * element unrepresentable in Kotlin and says nothing at all about the JVM surface this library
 * publishes: a Java or Android client that handed a relay's `["e", null]` straight to this
 * constructor would otherwise reach a `NullPointerException` two layers down, and §4.3 requires a
 * rejection.
 *
 * `kind` is deliberately unvalidated. §4.1 makes it a bare JSON number and this package knows no
 * kind vocabulary at all — which kinds Nenya defines is §5's business and the tag codec's.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class WireEvent(
    pubkey: String,

    /** §4.1's `created_at`, in Unix seconds. Emitted as a bare JSON number, never quoted. */
    public val createdAt: Long,

    /** §4.1's `kind`. Emitted as a bare JSON number, never quoted. */
    public val kind: Int,

    tags: List<List<String>>,

    /** §4.1's `content`, verbatim. Escaped on the way out by rules 1–3 and never normalised. */
    public val content: String,
) {

    /**
     * The author's x-only public key, exactly 64 hex characters (§4.3), **in the case it
     * arrived in**.
     *
     * Checked and not normalised, and that is a correctness rule rather than laziness. This value
     * goes into the id preimage verbatim, so lowercasing it here would change the bytes whose
     * SHA-256 §4.1 compares against the relay's claim: an event whose author serialised an
     * uppercase pubkey would be recomputed as a *different* event and refused as
     * [WireRejection.ID_MISMATCH] — this library calling a genuine event forged, which is the one
     * outcome §4.1 exists to prevent.
     *
     * §4.3's "accept uppercase on read and normalise it" still holds, one layer up: normalisation
     * belongs where two hex values are *compared* — a `p` tag against a pubkey, a coordinate
     * against a listing — which is the tag codec's job and not the id's. And §4.3's "MUST emit
     * lowercase hex" binds whoever authors an event: pass a lowercase pubkey for anything this
     * client signs. Nothing here rewrites somebody else's bytes.
     */
    public val pubkey: String

    /**
     * The tags, in the order given. §4.1 says an implementation MUST NOT reorder or normalise
     * them, so this is a copy of the caller's order and never a sorted or de-duplicated one.
     * Deeply unmodifiable, so the event whose id was computed is the event that is still here.
     */
    public val tags: List<List<String>>

    init {
        this.pubkey = checkedHex(pubkey, PUBKEY_HEX_LENGTH, "a pubkey")
        if (createdAt < 0L) {
            throw WireException(
                WireRejection.NEGATIVE_TIMESTAMP,
                "created_at is $createdAt; §4.3 fixes a timestamp as a non-negative integer",
            )
        }
        this.tags = readOnlyListOf(
            tags.mapIndexed { index, tag ->
                // Kotlin is right that this cannot be null and wrong that it therefore cannot
                // happen, for the reason the element check below gives: `"tags":[null]` is what a
                // relay can serve, and reaching the emptiness check with it is an NPE.
                @Suppress("SENSELESS_COMPARISON")
                if (tag == null) {
                    throw WireException(
                        WireRejection.NULL_TAG_ELEMENT,
                        "tag $index is null; §4.3 says a tag is an array of one or more strings and " +
                            "requires rejecting an event carrying a non-string or null tag",
                    )
                }
                if (tag.isEmpty()) {
                    throw WireException(
                        WireRejection.EMPTY_TAG,
                        "tag $index is empty; §4.3 says a tag is an array of one or more strings and " +
                            "requires rejecting an event carrying an empty tag array",
                    )
                }
                val values = tag.toList()
                for ((position, value) in values.withIndex()) {
                    // Kotlin is right that this cannot be null and wrong that it therefore cannot
                    // happen: the JVM signature this class publishes accepts one, and a Java client
                    // parsing `["e", null]` off a relay is the caller §4.3 wrote this rule about.
                    @Suppress("SENSELESS_COMPARISON")
                    if (value == null) {
                        throw WireException(
                            WireRejection.NULL_TAG_ELEMENT,
                            "element $position of tag $index is null; §4.3 requires rejecting an event " +
                                "containing a non-string or null tag element",
                        )
                    }
                }
                readOnlyListOf(values)
            },
        )
    }

    /**
     * §4.1's NIP-01 canonical serialisation, exactly:
     *
     * ```
     * [0,<pubkey hex>,<created_at number>,<kind number>,<tags>,<content>]
     * ```
     *
     * with no insignificant whitespace anywhere, and inside every string value — `content` and
     * every tag value alike — exactly these three rules and nothing else:
     *
     * 1. the seven shortcut escapes and only these seven, for the characters they name;
     * 2. every **other** character below `0x20` as `\u00XX`, two **lowercase** hex digits;
     * 3. every character at or above `0x20` verbatim, as UTF-8 bytes.
     *
     * So `/` is never escaped, `0x7f` (DEL) is emitted verbatim because it is above `0x20`, a
     * non-BMP character is emitted as UTF-8 rather than as an escaped surrogate pair, and none
     * of the seven ever appears in the six-character `u`-escape form §4.1 forbids by name for
     * exactly those seven. Rule 1 takes precedence over rule 2 wherever both could apply.
     *
     * §4.1 is explicit that this is a deliberate, reasoned deviation from NIP-01's literal
     * wording, and the only one in the document: read literally, NIP-01 places a raw `0x01`
     * inside a JSON string, which RFC 8259 forbids and which computes a different id from every
     * deployed implementation — meaning events the network considers forged, in both directions.
     * The authority here is `nostr-tools` and `go-nostr`, which agree with each other byte for
     * byte, and rules 1–3 are a precise statement of what they do.
     *
     * ### The bounds are checked here, and one of them under-rejects
     *
     * §4.3's four bounds are enforced before anything is handed back, in the order tag count,
     * tag value, `content`, whole event, each rejecting rather than truncating. This is the
     * earliest point all four can be measured, and it is upstream of everything: the only way to
     * a [CheckedEvent] runs through here.
     *
     * The whole-event bound is measured over **this** serialisation, which omits the `id` and
     * `sig` fields and the JSON object's field names — roughly 200 further bytes on the wire. So
     * it is a **lower** bound on the true serialised size and under-rejects slightly. Said out
     * loud rather than implied: an event this function accepts at 65 000 bytes is over §4.3's
     * 64 KiB once a relay sees it.
     *
     * ### Text with no UTF-8 encoding is refused, not substituted
     *
     * A `String` may hold an unpaired surrogate, which is not a character and has no UTF-8
     * encoding. The JVM's encoder emits `?` for one and JavaScript's emits U+FFFD, so encoding
     * leniently would give this event a different id on each platform. §4.1 requires rejecting
     * it: every tag value and `content` is checked as it is measured, before its byte bound, and
     * the refusal is [WireRejection.UNPAIRED_SURROGATE]. Such a value cannot come from decoding
     * valid UTF-8, only from a `\uD800`-style escape a JSON parser expanded, which a web client's
     * `JSON.parse` does without complaint.
     *
     * @throws WireException [WireRejection.TOO_MANY_TAGS], [WireRejection.UNPAIRED_SURROGATE],
     *   [WireRejection.TAG_VALUE_TOO_LONG], [WireRejection.CONTENT_TOO_LONG] or
     *   [WireRejection.SERIALISED_EVENT_TOO_LARGE], naming which rule was broken.
     */
    public fun canonicalSerialisation(limits: WireLimits = WireLimits.DEFAULT): String {
        if (tags.size > limits.maxTagsPerEvent) {
            throw WireException(
                WireRejection.TOO_MANY_TAGS,
                "the event carries ${tags.size} tags and the bound is ${limits.maxTagsPerEvent}; " +
                    "§4.3 requires rejecting rather than truncating",
            )
        }
        for ((index, tag) in tags.withIndex()) {
            for (value in tag) {
                val bytes = wireUtf8(value) { "a value of tag $index" }.size
                if (bytes > limits.maxTagValueBytes) {
                    throw WireException(
                        WireRejection.TAG_VALUE_TOO_LONG,
                        "a value of tag $index is $bytes UTF-8 bytes and the bound is " +
                            "${limits.maxTagValueBytes}; §4.3 measures this one in bytes, not characters",
                    )
                }
            }
        }
        val contentBytes = wireUtf8(content) { "content" }.size
        if (contentBytes > limits.maxContentBytes) {
            throw WireException(
                WireRejection.CONTENT_TOO_LONG,
                "content is $contentBytes UTF-8 bytes and the bound is ${limits.maxContentBytes}; " +
                    "§4.3 requires rejecting rather than truncating",
            )
        }

        val out = StringBuilder()
        out.append("[0,")
        out.appendCanonicalString(pubkey)
        out.append(',').append(createdAt)
        out.append(',').append(kind)
        out.append(",[")
        for ((index, tag) in tags.withIndex()) {
            if (index > 0) out.append(',')
            out.append('[')
            for ((position, value) in tag.withIndex()) {
                if (position > 0) out.append(',')
                out.appendCanonicalString(value)
            }
            out.append(']')
        }
        out.append("],")
        out.appendCanonicalString(content)
        out.append(']')
        val serialisation = out.toString()

        val serialisedBytes = wireUtf8(serialisation) { "the canonical serialisation" }.size
        if (serialisedBytes > limits.maxSerialisedEventBytes) {
            throw WireException(
                WireRejection.SERIALISED_EVENT_TOO_LARGE,
                "the canonical serialisation is $serialisedBytes UTF-8 bytes and the bound is " +
                    "${limits.maxSerialisedEventBytes}; §4.3 requires rejecting rather than truncating",
            )
        }
        return serialisation
    }

    /**
     * Names the kind, the timestamp and two counts — and neither the pubkey nor a byte of
     * `content` or of any tag value.
     *
     * §12 item 2 names the counterparty pubkey among the values that MUST NOT leave an encrypted
     * event, and §12 item 11 forbids secrets in "the string representation of any value the
     * implementation exposes". A `kind:16` rumor's `content` is the order message itself. A
     * default Kotlin `toString` would carry both; the debugging `toString` somebody adds later
     * is what this override is for.
     */
    override fun toString(): String =
        "WireEvent(kind=$kind, createdAt=$createdAt, tags=${tags.size}, contentChars=${content.length})"

    public companion object {

        /** 64 characters — §4.3 fixes a pubkey as exactly that, a 32-byte x-only key. */
        public const val PUBKEY_HEX_LENGTH: Int = 64
    }
}

/**
 * §4.1's three rules, and nothing else, over one string value.
 *
 * The seven shortcuts are matched on their **code points** rather than on character literals, in
 * the order §4.1 lists them, so this `when` reads against the specification's own list — 0x0A,
 * 0x22, 0x5C, 0x0D, 0x09, 0x08, 0x0C — and so that 0x0C, for which Kotlin has no escape of its
 * own, need not appear here as a raw control character in the source.
 */
private fun StringBuilder.appendCanonicalString(value: String) {
    append('"')
    for (character in value) {
        when (val code = character.code) {
            0x0A -> append("\\n")
            0x22 -> append("\\\"")
            0x5C -> append("\\\\")
            0x0D -> append("\\r")
            0x09 -> append("\\t")
            0x08 -> append("\\b")
            0x0C -> append("\\f")
            else ->
                if (code < 0x20) {
                    // Rule 2: the 27 control characters rule 1 does not name, in the four-hex-digit
                    // form with lowercase digits. Rule 3 is the branch below — everything at or
                    // above 0x20 verbatim, including `/`, 0x7f and non-BMP surrogate pairs.
                    append("\\u00")
                    append(HEX_DIGITS[code ushr 4])
                    append(HEX_DIGITS[code and 0x0f])
                } else {
                    append(character)
                }
        }
    }
    append('"')
}

/**
 * §4.3's fixed-length hex check, kept file-private so it never becomes API. Uppercase is
 * **accepted** — §4.3's no-normalisation exception list is exhaustive and names only the BOLT-11
 * invoice string and the Lightning preimage, and a pubkey and an event id are on neither — and
 * the value is handed back unchanged.
 *
 * Whether to normalise afterwards is the caller's decision and the two callers here differ, which
 * is why this function does not make it: an event id is normalised ([normalisedHex]) because it
 * is compared and never hashed, and a pubkey is not, because it goes into the id preimage. See
 * [WireEvent.pubkey].
 *
 * The delivery and payment packages each read their own hex, for the reason `DeliverableHash`
 * gives: the preimage's rule is the *opposite* of this one, and one shared reader would put the
 * two one `when` branch apart. That reasoning applies here unchanged.
 */
private fun checkedHex(text: String, length: Int, what: String): String {
    if (text.length != length) {
        throw WireException(
            WireRejection.WRONG_LENGTH,
            "$what is exactly $length hex characters; this one is ${text.length}, and §4.3 requires " +
                "rejecting a value of the wrong length rather than padding or truncating it",
        )
    }
    for (character in text) {
        val known = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        if (!known) {
            throw WireException(
                WireRejection.NOT_HEX,
                "$what is hexadecimal characters only; this one carries something else",
            )
        }
    }
    return text
}

/** [checkedHex], then §4.3's canonical lowercase form. For values that are compared, not hashed. */
private fun normalisedHex(text: String, length: Int, what: String): String {
    val checked = checkedHex(text, length, what)
    val out = StringBuilder(length)
    for (character in checked) {
        out.append(if (character in 'A'..'F') character + ('a' - 'A') else character)
    }
    return out.toString()
}

/**
 * §4.3's canonical hex form: lowercase, unpadded. Shared by the reader above and [EventId].
 *
 * `@JvmSynthetic` because `internal` alone is not enough. Kotlin compiles an internal top-level
 * function to a **public** static method on this file's facade class, so without it a Java client
 * could call `WireEventKt.encodeLowerHex(...)` — and the package's structural sweep, which reads
 * the JVM surface rather than the Kotlin one, would rightly report it as published API. It is a
 * two-line helper, not API.
 */
@JvmSynthetic
internal fun encodeLowerHex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}

/**
 * The UTF-8 bytes of [text] — the only way this package turns a `String` into bytes it measures
 * or hashes — or [WireRejection.UNPAIRED_SURROGATE] when [text] has no UTF-8 encoding (§4.1).
 *
 * Delegates to the common `strictUtf8OrNull`, which never substitutes, so the JVM and JavaScript
 * builds refuse the same strings and hash the rest to the same bytes. [what] names the field for
 * the message and is built only on refusal; the message never carries the text itself.
 * `@JvmSynthetic` for the reason [encodeLowerHex] gives.
 */
@JvmSynthetic
internal inline fun wireUtf8(text: String, what: () -> String): ByteArray =
    strictUtf8OrNull(text) ?: throw WireException(
        WireRejection.UNPAIRED_SURROGATE,
        "${what()} carries an unpaired UTF-16 surrogate, which has no UTF-8 encoding; §4.1 requires " +
            "rejecting it rather than substituting a replacement character, which would change the id",
    )

/** Lowercase, per §4.1 rule 2 and §4.3. An uppercase digit here is a wire-visible defect. */
private const val HEX_DIGITS: String = "0123456789abcdef"

/**
 * §4.3's hex reader, reachable inside this package for [EventId]'s own 64-character values.
 * `@JvmSynthetic` for the reason [encodeLowerHex] gives.
 */
@JvmSynthetic
internal fun readEventIdHex(text: String): String =
    normalisedHex(text, EventId.HEX_LENGTH, "an event id")
