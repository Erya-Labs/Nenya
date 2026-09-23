package dev.eryalabs.nenya.wire

/**
 * Why a piece of event JSON was refused (§4.1, §4.3, §7.1).
 *
 * The reason is part of the API and not diagnostic text, for the reason [WireRejection] gives: a
 * caller told only "malformed" cannot tell a relay that serves rubbish from a counterparty whose
 * client emits a trailing comma from a bound it should raise. Tests assert on these constants
 * rather than on message wording.
 *
 * ### These are the JSON-shaped refusals only
 *
 * [EventJson] throws a [WireException] for everything that is a rule about the **event** rather
 * than about its JSON — an unpaired surrogate ([WireRejection.UNPAIRED_SURROGATE]), a `null` tag or
 * tag element ([WireRejection.NULL_TAG_ELEMENT]), §4.3's tag-count bound
 * ([WireRejection.TOO_MANY_TAGS]), a pubkey of the wrong length, a negative `created_at` — because
 * those rules already have constants, and one rule with two names is how two callers come to branch
 * on different ones. A caller that wants a single door catches `IllegalArgumentException`, which
 * both extend.
 *
 * ### No message here echoes its input
 *
 * §12 item 2 names the counterparty pubkey among the values that MUST NOT appear outside an
 * encrypted event, and a rumor's `content` is the order message itself. A message may name a
 * *field*, an *offset*, a *length* and a *bound*; it may never name a key a stranger chose, a tag
 * value or a byte of `content`. The one exception is deliberate and safe: [DUPLICATE_KEY] names the
 * repeated key, because a key is only recognised as a duplicate after it has matched one of the
 * seven names §4.1 fixes, so the name in that message is this library's own and never the caller's
 * text.
 */
public enum class JsonRejection {

    /**
     * The text is longer than the bound ([WireLimits.maxSerialisedEventBytes]) measured in UTF-8
     * bytes. Checked **before** the scanner runs, so a 100 MiB "event" is never walked.
     */
    TOO_LARGE,

    /** No text at all. Not an empty object — nothing. */
    EMPTY_INPUT,

    /**
     * A leading U+FEFF. RFC 8259 says a JSON text MUST NOT begin with one, and a reader that
     * silently skips it reads a different byte sequence from the one whose id it will check.
     */
    BYTE_ORDER_MARK,

    /** A `/` where a value or whitespace belongs — JavaScript-style comments are not JSON. */
    COMMENT,

    /** The text is not one top-level object (§7.1: the payload is the JSON *object* form). */
    NOT_AN_OBJECT,

    /** Something other than whitespace follows the closing `}`. Two events, or an object plus junk. */
    TRAILING_DATA,

    /**
     * A `,` immediately before a `}` or a `]`. Not JSON, and named separately because NIP-59's own
     * worked gift wrap carries one: §18 says in as many words that a parser which accepts it is a
     * parser that accepts malformed input from a relay.
     */
    TRAILING_COMMA,

    /** The text ended in the middle of a string, a number, an array or the object. */
    UNTERMINATED,

    /** A character the grammar has no place for — a missing `:`, a stray `]`, exotic whitespace. */
    UNEXPECTED_CHARACTER,

    /** An object member whose key is not a JSON string. */
    KEY_NOT_A_STRING,

    /**
     * The same top-level key twice. §7.1: "An object carrying the **same key twice** MUST be
     * rejected, at every layer", for §4.3's reason — first-wins and last-wins are both defensible,
     * which is exactly the problem.
     */
    DUPLICATE_KEY,

    /**
     * A top-level key outside the seven NIP-01 fields. §7.1 states this as a MAY and Nenya applies
     * it to itself: an eighth field is either a different protocol or an attempt to smuggle a value
     * past a parser that ignores it. Refusing also means this reader needs no generic value-skipper
     * and therefore has no nesting depth to bound.
     */
    UNKNOWN_KEY,

    /** One of the six required keys — `id`, `pubkey`, `created_at`, `kind`, `tags`, `content` — is absent. */
    MISSING_KEY,

    /** A field §4.1 defines as a string arrived as something else — `"id":123`, `"content":null`. */
    VALUE_NOT_A_STRING,

    /**
     * A bare JSON number was required and something else arrived: `"created_at":"123"`, `null`,
     * `NaN`, `Infinity`. §4.1 emits `created_at` and `kind` as bare numbers and §4.3 forbids
     * reading a timestamp in any other form.
     */
    NUMBER_EXPECTED,

    /** `01`. JSON has no leading zeros, and a reader that accepts them accepts two spellings of one id. */
    LEADING_ZERO,

    /** `-1` or `+1`. §4.3: a timestamp MUST NOT be accepted in a signed form, and a kind is not signed. */
    SIGNED_NUMBER,

    /** `1.5` or `1e3`. §4.3: not floating-point, not exponential. */
    FRACTIONAL_OR_EXPONENTIAL,

    /** A number too long or too large to be a `Long`. Refused rather than saturated or wrapped. */
    NUMBER_TOO_LARGE,

    /**
     * `kind` outside `0..65535`. NIP-01 fixes a kind as a 16-bit number; §4.2 reads its ranges —
     * `30000..39999` for the addressable events §5.1 and §5.2 use — off that same numbering.
     */
    KIND_OUT_OF_RANGE,

    /**
     * A raw character below `0x20` inside a string. RFC 8259 requires it be escaped, which is
     * §4.1's whole reasoned deviation from NIP-01's literal wording: the literal reading emits
     * bytes that are not JSON.
     */
    RAW_CONTROL_CHARACTER,

    /**
     * A `\` followed by something JSON does not define — `\x41`, `\u00zz`, a `\u` with fewer than
     * four hex digits. The eight JSON escapes and `\uXXXX` are the whole vocabulary.
     */
    BAD_ESCAPE,

    /** `tags` is not an array. */
    TAGS_NOT_AN_ARRAY,

    /** An element of `tags` is not an array — §4.3's "a tag is an array of one or more strings". */
    TAG_NOT_AN_ARRAY,

    /** An element of a tag is not a string (§4.3), and is not `null` — a number, an object, `true`. */
    TAG_ELEMENT_NOT_A_STRING,

    /**
     * An array where a tag value belongs. Its own reason rather than [TAG_ELEMENT_NOT_A_STRING],
     * because it is the one shape that would need this reader to recurse: the event grammar is
     * exactly three levels deep, so anything deeper is refused at the character that opens it and
     * no depth counter is needed.
     */
    NESTED_ARRAY,

    /**
     * `sig` is present and is not 128 hexadecimal characters — NIP-01 fixes it as a 64-byte BIP-340
     * signature. Its **shape** only: nothing in this library verifies a signature or claims to
     * (§17, [CheckedEvent]).
     */
    SIGNATURE_MALFORMED,
}

/**
 * Event JSON was refused, carrying the [reason] as data.
 *
 * One exception type for the JSON surface, mirroring [WireException] beside it: a caller has one
 * thing to catch and one field to branch on. The message never echoes the caller's input — see
 * [JsonRejection].
 */
public class JsonException internal constructor(
    public val reason: JsonRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * One event, read out of §7.1's JSON object form: the five id-bearing fields as a [WireEvent], and
 * the two *claims* that travel beside them.
 *
 * The claims are deliberately not folded into the event. [WireEvent] is the five fields §4.1 hashes
 * and nothing else, so an id and a signature — both statements *about* those fields, neither
 * checked here — would be exactly the "the relay says so" this library refuses to hold as data.
 * What this type says is "these bytes parsed, and they claimed this". Checking the claim is
 * [CheckedEvent.checkEventId], which is a separate call and produces a separate type:
 *
 * ```
 * val read = EventJson.read(text, limits)
 * val checked = CheckedEvent.checkEventId(read.claimedIdHex, read.event, limits)
 * ```
 *
 * Nothing in this library verifies [signatureHex] or reports it as verified (§17). It is carried so
 * that an embedding client with secp256k1 can check it against [claimedIdHex] — §4.1: a signature
 * on a nostr event is over the 32 bytes the `id` field spells, and nothing else.
 */
public class ReadEvent internal constructor(

    /** §4.1's five id-bearing fields, structurally valid per §4.3. */
    public val event: WireEvent,

    /**
     * The `id` the text claimed, verbatim and in the case it arrived in, already checked as 64 hex
     * characters (§4.3). **A claim, not a fact** — [CheckedEvent.checkEventId] is what makes it one.
     */
    public val claimedIdHex: String,

    /**
     * The `sig` the text claimed, verbatim, checked only as 128 hex characters — or `null` when the
     * object carried no `sig` key at all.
     *
     * The distinction is load-bearing for §7.1 step 8, which requires rejecting a rumor carrying a
     * `sig` key "with any value at all, including an empty string or `null`". Each of those is
     * refused before a reader ever sees it, so `null` here means the key was absent and nothing
     * else — but they are refused by **two** different names, and a caller mapping them must know
     * which: `"sig":null` and `"sig":7` are [JsonRejection.VALUE_NOT_A_STRING], because the value is
     * not a string at all, while `"sig":""` and any other wrong-shaped string are
     * [JsonRejection.SIGNATURE_MALFORMED].
     */
    public val signatureHex: String?,
) {

    /**
     * Names neither id nor signature nor anything [WireEvent.toString] declines to name.
     *
     * A rumor's id is a correlation handle for a private order (§12 item 11, [EventId.toString]),
     * and whether the payload carried a signature at all is the one thing about the claim a
     * debugging line can safely say.
     */
    override fun toString(): String = "ReadEvent(event=$event, signed=${signatureHex != null})"
}

/**
 * §7.1's payload format: the JSON **object** form of a nostr event, written and read.
 *
 * ### Why this is not §4.1's serialisation
 *
 * §4.1's `[0,…]` array exists only to be hashed. What NIP-59 encrypts at every layer — and what
 * §7.1 states as a rule rather than leaving implied — is the object
 * `{"id":…,"pubkey":…,"created_at":…,"kind":…,"tags":…,"content":…,"sig":…}`, with strings escaped
 * by §4.1's rules so that an implementation has exactly one escaping routine and a rumor's
 * `content` survives the round trip byte for byte. [writeUnsigned] and [writeSigned] emit that
 * object through the same `appendCanonicalString` [WireEvent.canonicalSerialisation] uses, so the
 * two forms can never disagree about an escape.
 *
 * ### The reader, and why writing one here is not a new dependency
 *
 * STOP RULE 11's budget has no JSON library in it, and that forbids adding one — not writing a
 * reader for one fixed shape. This one is deliberately not a JSON parser: it reads **an event
 * object and nothing else**. There is no generic value, no object nesting and no array nesting
 * beyond `tags`' two levels, and therefore **no recursion and no depth bound to get wrong** — the
 * grammar is a fixed three levels deep, and anything deeper is refused at the character that opens
 * it.
 *
 * Every refusal it makes is a rule somebody wrote down:
 *
 * - the byte bound is checked **before** the scan — a character-count guard first, since a UTF-8
 *   encoding is never shorter in bytes than the text is in UTF-16 units, then the exact count;
 * - whitespace is space, tab, LF and CR, which is RFC 8259's whole list;
 * - a **duplicate** top-level key is refused (§7.1, §4.3's no-first-wins reasoning), a **missing**
 *   required one is refused, and an **unknown** one is refused under §7.1's MAY;
 * - `created_at` and `kind` are bare `0|[1-9][0-9]*` integers fitting a `Long`, `kind` at most
 *   [MAX_KIND] — never signed, fractional, exponential or quoted (§4.3);
 * - a string accepts every legal escape, including the non-canonical ones §4.1 forbids on *write*
 *   but which a conformant peer may still send (`\/`, `A`, uppercase hex, an escaped surrogate
 *   pair — `nak` re-escapes `<`), joins a surrogate pair, and refuses a lone surrogate (§4.1), a
 *   raw character below `0x20`, and any escape JSON does not define;
 * - `tags` is an array of arrays of strings, the tag count bounded **during** the scan so a
 *   million-tag input is refused rather than materialised;
 * - a trailing comma, trailing data, a byte-order mark, a comment, `NaN`, `Infinity` and empty
 *   input are each refused by name.
 *
 * ### What it does not do
 *
 * It does not check the id. §4.1 requires that before any other processing, and this library
 * enforces the ordering with a type: [read] hands back a [ReadEvent] carrying the *claim*, and only
 * [CheckedEvent.checkEventId] turns it into the [CheckedEvent] every decoder here takes. It does
 * not verify the signature, and does not claim to (§17).
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public object EventJson {

    /** 128 characters — NIP-01's `sig` is a 64-byte BIP-340 signature in hex. */
    public const val SIGNATURE_HEX_LENGTH: Int = 128

    /** NIP-01 fixes a kind as a 16-bit number; §4.2 reads its addressable range off that numbering. */
    public const val MAX_KIND: Int = 65_535

    /**
     * §7.1 step 1's unsigned rumor: the object form with `id` present and **no `sig` key at all**.
     *
     * Keys in the fixed order `id, pubkey, created_at, kind, tags, content`. The order is this
     * library's own choice — JSON objects are unordered and [read] accepts any order, as it must,
     * since NIP-59's and NIP-17's own worked examples disagree with each other about it — but a
     * fixed one makes two emissions of the same event byte-identical, which is what a round-trip
     * test can assert.
     *
     * **[id] is checked, not trusted.** The id is recomputed from [event] and the emission refused
     * if it disagrees, because an object whose `id` field is not the SHA-256 of its own five fields
     * is precisely the event §4.1 requires every reader to reject — emitting one would put this
     * library on the wrong end of its own central rule. Recomputing also runs §4.3's four bounds and
     * §4.1's unpaired-surrogate check over the event on the way past, so nothing this function emits
     * can carry text that has no UTF-8 encoding.
     *
     * @throws WireException [WireRejection.ID_MISMATCH] when [id] is not this event's id, or
     *   whichever bound [WireEvent.canonicalSerialisation] refused.
     * @throws JsonException [JsonRejection.KIND_OUT_OF_RANGE] for a kind outside `0..`[MAX_KIND].
     *   [WireEvent] leaves `kind` unvalidated because it knows no kind vocabulary; this function
     *   will not emit an object its own reader would refuse.
     */
    public fun writeUnsigned(
        event: WireEvent,
        id: EventId,
        limits: WireLimits = WireLimits.DEFAULT,
    ): String = write(event, id, null, limits)

    /**
     * The same object with `sig` last: a `kind:13` seal or a `kind:1059` wrap as §7.1 steps 2 and 3
     * emit them, and any ordinary signed event.
     *
     * [sig] is checked for **shape** only — [SIGNATURE_HEX_LENGTH] hexadecimal characters — and is
     * emitted verbatim. Nothing here verifies it, produces it, or claims it was verified (§17): the
     * signature comes from the injected signer, and §7.2 is explicit that a verdict on one
     * establishes no identity by itself.
     *
     * @throws WireException as [writeUnsigned].
     * @throws JsonException [JsonRejection.SIGNATURE_MALFORMED] or [JsonRejection.KIND_OUT_OF_RANGE].
     */
    public fun writeSigned(
        event: WireEvent,
        id: EventId,
        sig: String,
        limits: WireLimits = WireLimits.DEFAULT,
    ): String = write(event, id, checkedEventSignature(sig), limits)

    /**
     * One event object, read strictly.
     *
     * [limits] is §4.3's bounds, injected: [WireLimits.maxSerialisedEventBytes] bounds **this text**
     * in UTF-8 bytes — which is the object form, so it is the true serialised size rather than the
     * canonical serialisation's under-measurement — and [WireLimits.maxTagsPerEvent] bounds the tag
     * count during the scan. A `kind:1059` wrap is read under
     * `WireLimits(maxSerialisedEventBytes = EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES, …)`, for the
     * reason §4.3 gives.
     *
     * @throws JsonException naming the JSON rule that was broken.
     * @throws WireException naming the event rule that was broken —
     *   [WireRejection.UNPAIRED_SURROGATE], [WireRejection.NULL_TAG_ELEMENT],
     *   [WireRejection.TOO_MANY_TAGS], [WireRejection.EMPTY_TAG], [WireRejection.WRONG_LENGTH] or
     *   [WireRejection.NOT_HEX]. [WireRejection.NEGATIVE_TIMESTAMP] is deliberately **not** on that
     *   list although [WireEvent] can raise it: a sign is refused earlier and more precisely here,
     *   as [JsonRejection.SIGNED_NUMBER], so no text reaching this function can produce it and a
     *   caller writing a control for it would be writing one that can never fire.
     */
    public fun read(text: String, limits: WireLimits = WireLimits.DEFAULT): ReadEvent {
        if (text.isEmpty()) {
            throw JsonException(
                JsonRejection.EMPTY_INPUT,
                "there is no text to read; §7.1's payload is one JSON object",
            )
        }
        // The cheap guard first: a UTF-8 encoding spends at least one byte per UTF-16 unit, so more
        // units than the bound is more bytes than the bound, and a hostile 100 MiB string against a
        // 64 KiB bound is refused after one comparison. The exact count below is NOT free — it
        // walks and allocates the text — so what the guard bounds is the work this function will do
        // before refusing, against the bound THIS CALLER injected. A caller that raises the bound to
        // hold a 6 MB text has asked for a 6 MB encoding, and gets one.
        if (text.length > limits.maxSerialisedEventBytes) {
            throw JsonException(
                JsonRejection.TOO_LARGE,
                "the text is ${text.length} characters, which is already past the " +
                    "${limits.maxSerialisedEventBytes}-byte bound; §4.3 requires rejecting rather " +
                    "than truncating",
            )
        }
        val bytes = wireUtf8(text) { "the event JSON" }.size
        if (bytes > limits.maxSerialisedEventBytes) {
            throw JsonException(
                JsonRejection.TOO_LARGE,
                "the text is $bytes UTF-8 bytes and the bound is ${limits.maxSerialisedEventBytes}; " +
                    "§4.3 measures this one in bytes, not characters, and requires rejecting rather " +
                    "than truncating",
            )
        }
        if (text[0].code == BYTE_ORDER_MARK) {
            throw JsonException(
                JsonRejection.BYTE_ORDER_MARK,
                "the text opens with U+FEFF; RFC 8259 forbids it, and skipping it silently would " +
                    "read different bytes from the ones whose id is about to be checked",
            )
        }
        return Reader(text, limits).readEvent()
    }

    private fun write(event: WireEvent, id: EventId, sig: String?, limits: WireLimits): String {
        if (event.kind < 0 || event.kind > MAX_KIND) {
            throw JsonException(
                JsonRejection.KIND_OUT_OF_RANGE,
                "kind is ${event.kind}; NIP-01 fixes a kind at 0..$MAX_KIND, and this writer will " +
                    "not emit an object its own reader would refuse",
            )
        }
        val recomputed = EventId.of(event, limits)
        if (recomputed != id) {
            throw WireException(
                WireRejection.ID_MISMATCH,
                "the id given is not the SHA-256 of this event's canonical serialisation (§4.1); " +
                    "emitting it would produce an object every conformant reader must reject",
            )
        }

        val out = StringBuilder()
        out.append("{\"").append(KEY_ID).append("\":")
        out.appendCanonicalString(recomputed.toHex())
        out.append(",\"").append(KEY_PUBKEY).append("\":")
        out.appendCanonicalString(event.pubkey)
        out.append(",\"").append(KEY_CREATED_AT).append("\":").append(event.createdAt)
        out.append(",\"").append(KEY_KIND).append("\":").append(event.kind)
        out.append(",\"").append(KEY_TAGS).append("\":[")
        for ((index, tag) in event.tags.withIndex()) {
            if (index > 0) out.append(',')
            out.append('[')
            for ((position, value) in tag.withIndex()) {
                if (position > 0) out.append(',')
                out.appendCanonicalString(value)
            }
            out.append(']')
        }
        out.append("],\"").append(KEY_CONTENT).append("\":")
        out.appendCanonicalString(event.content)
        if (sig != null) {
            out.append(",\"").append(KEY_SIG).append("\":")
            out.appendCanonicalString(sig)
        }
        out.append('}')
        return out.toString()
    }

    /**
     * The scanner: one pass over [text], an index, and no recursion at all.
     *
     * Every method here leaves [at] on the first character it has not consumed, which is the
     * invariant each of its callers depends on.
     */
    private class Reader(private val text: String, private val limits: WireLimits) {

        private var at: Int = 0

        fun readEvent(): ReadEvent {
            skipWhitespace()
            if (!has() || text[at] != '{') {
                throw JsonException(
                    JsonRejection.NOT_AN_OBJECT,
                    "§7.1's payload is one top-level JSON object; this text does not open one " +
                        "(at offset $at)",
                )
            }
            at++

            var id: String? = null
            var pubkey: String? = null
            var createdAt: Long? = null
            var kind: Int? = null
            var tags: List<List<String>>? = null
            var content: String? = null
            var sig: String? = null
            var afterComma = false

            while (true) {
                skipWhitespace()
                if (!has()) throw unterminated("the object")
                if (text[at] == '}') {
                    if (afterComma) throw trailingComma("the object")
                    at++
                    break
                }
                if (text[at] != '"') {
                    throw JsonException(
                        JsonRejection.KEY_NOT_A_STRING,
                        "an object key is a JSON string; offset $at opens with something else",
                    )
                }
                at++
                val key = readStringBody()
                skipWhitespace()
                if (!has() || text[at] != ':') {
                    throw JsonException(
                        JsonRejection.UNEXPECTED_CHARACTER,
                        "a `:` must follow an object key; offset $at carries something else",
                    )
                }
                at++
                skipWhitespace()
                when (key) {
                    KEY_ID -> {
                        refuseRepeat(id, KEY_ID)
                        id = readStringValue(KEY_ID)
                    }
                    KEY_PUBKEY -> {
                        refuseRepeat(pubkey, KEY_PUBKEY)
                        pubkey = readStringValue(KEY_PUBKEY)
                    }
                    KEY_CREATED_AT -> {
                        refuseRepeat(createdAt, KEY_CREATED_AT)
                        createdAt = readNumber(KEY_CREATED_AT)
                    }
                    KEY_KIND -> {
                        refuseRepeat(kind, KEY_KIND)
                        val value = readNumber(KEY_KIND)
                        if (value > MAX_KIND) {
                            throw JsonException(
                                JsonRejection.KIND_OUT_OF_RANGE,
                                "kind is $value; NIP-01 fixes a kind at 0..$MAX_KIND",
                            )
                        }
                        kind = value.toInt()
                    }
                    KEY_TAGS -> {
                        refuseRepeat(tags, KEY_TAGS)
                        tags = readTags()
                    }
                    KEY_CONTENT -> {
                        refuseRepeat(content, KEY_CONTENT)
                        content = readStringValue(KEY_CONTENT)
                    }
                    KEY_SIG -> {
                        refuseRepeat(sig, KEY_SIG)
                        sig = checkedEventSignature(readStringValue(KEY_SIG))
                    }
                    else -> throw JsonException(
                        JsonRejection.UNKNOWN_KEY,
                        "an unknown top-level key before offset $at. §7.1 permits refusing an " +
                            "eighth field: it is either a different protocol or a value being " +
                            "smuggled past a parser that ignores it. The key itself is not quoted " +
                            "here, because a stranger chose it (§12).",
                    )
                }
                afterComma = false
                skipWhitespace()
                if (!has()) throw unterminated("the object")
                when (text[at]) {
                    ',' -> {
                        at++
                        afterComma = true
                    }
                    // Left for the top of the loop, which is the one place the object closes.
                    '}' -> Unit
                    else -> throw JsonException(
                        JsonRejection.UNEXPECTED_CHARACTER,
                        "a `,` or a `}` must follow an object member; offset $at carries something else",
                    )
                }
            }

            skipWhitespace()
            if (has()) {
                throw JsonException(
                    JsonRejection.TRAILING_DATA,
                    "${text.length - at} character(s) follow the closing `}`; one event object is " +
                        "the whole payload (§7.1)",
                )
            }

            return ReadEvent(
                event = WireEvent(
                    pubkey = required(pubkey, KEY_PUBKEY),
                    createdAt = required(createdAt, KEY_CREATED_AT),
                    kind = required(kind, KEY_KIND),
                    tags = required(tags, KEY_TAGS),
                    content = required(content, KEY_CONTENT),
                ),
                // Checked as §4.3 hex and the result discarded: the claim is carried verbatim, and
                // `CheckedEvent.checkEventId` is the only thing that turns it into a fact.
                claimedIdHex = required(id, KEY_ID).also { EventId.ofHex(it) },
                signatureHex = sig,
            )
        }

        // -------------------------------------------------------------------------------------
        // Values.
        // -------------------------------------------------------------------------------------

        private fun readStringValue(field: String): String {
            if (!has() || text[at] != '"') {
                throw JsonException(
                    JsonRejection.VALUE_NOT_A_STRING,
                    "§4.1 defines `$field` as a JSON string; offset $at opens with something else",
                )
            }
            at++
            return readStringBody()
        }

        /** [at] is one past the opening quote; returns with [at] one past the closing one. */
        private fun readStringBody(): String {
            val out = StringBuilder()
            while (true) {
                if (!has()) throw unterminated("a string")
                val character = text[at]
                when {
                    character == '"' -> {
                        at++
                        return out.toString()
                    }
                    character == '\\' -> {
                        at++
                        readEscape(out)
                    }
                    character.code < 0x20 -> throw JsonException(
                        JsonRejection.RAW_CONTROL_CHARACTER,
                        "a raw character below 0x20 inside a string at offset $at; RFC 8259 " +
                            "requires it be escaped, which is what §4.1's rule 2 emits",
                    )
                    else -> {
                        out.append(character)
                        at++
                    }
                }
            }
        }

        /**
         * [at] is one past the backslash.
         *
         * All eight JSON escapes are accepted, including the two §4.1 forbids this library from
         * *emitting*: `\/`, and the six-character form for a character rule 1 names. §4.1 binds what
         * Nenya writes, not what a conformant peer may send — `nak` re-escapes `<` in the
         * six-character form, and an event refused for that would be an event this library called
         * forged.
         */
        private fun readEscape(out: StringBuilder) {
            if (!has()) throw unterminated("an escape")
            val marker = text[at]
            at++
            when (marker) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append(BACKSPACE.toChar())
                // 0x0C has no escape of its own in Kotlin, and a raw form feed in a source file is
                // an invisible character; `appendCanonicalString` matches on code points for the
                // same reason, and 0x08 is spelled the same way here so the two read alike.
                'f' -> out.append(FORM_FEED.toChar())
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> readUnicodeEscape(out)
                else -> throw JsonException(
                    JsonRejection.BAD_ESCAPE,
                    "an escape JSON does not define, at offset ${at - 1}",
                )
            }
        }

        /** [at] is one past the `u`. */
        private fun readUnicodeEscape(out: StringBuilder) {
            val unit = readFourHexDigits()
            when (unit) {
                in HIGH_SURROGATE_RANGE -> {
                    // §4.1: a pair spells one astral character and is legal; half of one is not a
                    // character at all and has no UTF-8 encoding. Only an *escaped* low surrogate
                    // can follow, because a raw one would have made the whole text ill-formed
                    // UTF-16 and `read` refused it before this scanner ever started.
                    if (at + 1 >= text.length || text[at] != '\\' || text[at + 1] != 'u') {
                        throw unpairedSurrogate()
                    }
                    at += 2
                    val low = readFourHexDigits()
                    if (low !in LOW_SURROGATE_RANGE) throw unpairedSurrogate()
                    out.append(unit.toChar()).append(low.toChar())
                }
                in LOW_SURROGATE_RANGE -> throw unpairedSurrogate()
                else -> out.append(unit.toChar())
            }
        }

        private fun readFourHexDigits(): Int {
            if (at + HEX_DIGITS_PER_ESCAPE > text.length) throw unterminated("a `\\u` escape")
            var value = 0
            for (offset in 0 until HEX_DIGITS_PER_ESCAPE) {
                val digit = nibbleOrMinusOne(text[at + offset])
                if (digit < 0) {
                    throw JsonException(
                        JsonRejection.BAD_ESCAPE,
                        "a `\\u` escape is four hexadecimal digits; offset ${at + offset} is not one",
                    )
                }
                value = (value shl 4) or digit
            }
            at += HEX_DIGITS_PER_ESCAPE
            return value
        }

        /**
         * §4.1's `created_at` and `kind`: a bare, unsigned decimal integer matching `0|[1-9][0-9]*`
         * and fitting a `Long`, and nothing else (§4.3).
         */
        private fun readNumber(field: String): Long {
            if (!has()) throw unterminated("a number")
            val first = text[at]
            if (first == '-' || first == '+') {
                throw JsonException(
                    JsonRejection.SIGNED_NUMBER,
                    "`$field` is a non-negative integer and §4.3 forbids accepting a signed form",
                )
            }
            if (first !in '0'..'9') {
                throw JsonException(
                    JsonRejection.NUMBER_EXPECTED,
                    "§4.1 emits `$field` as a bare JSON number; offset $at opens with something " +
                        "else — a quoted number, `null`, `NaN` and `Infinity` all land here",
                )
            }
            val start = at
            while (has() && text[at] in '0'..'9') at++
            val digits = at - start
            if (text[start] == '0' && digits > 1) {
                throw JsonException(
                    JsonRejection.LEADING_ZERO,
                    "`$field` carries a leading zero; JSON has no such number, and two spellings " +
                        "of one timestamp are two event ids",
                )
            }
            if (has() && (text[at] == '.' || text[at] == 'e' || text[at] == 'E')) {
                throw JsonException(
                    JsonRejection.FRACTIONAL_OR_EXPONENTIAL,
                    "§4.3 forbids accepting `$field` in a floating-point or exponential form",
                )
            }
            if (digits > MAX_LONG_DIGITS) {
                throw JsonException(
                    JsonRejection.NUMBER_TOO_LARGE,
                    "`$field` carries $digits digits and no Long holds more than $MAX_LONG_DIGITS",
                )
            }
            var value = 0L
            for (index in start until at) {
                val digit = text[index] - '0'
                if (value > (Long.MAX_VALUE - digit) / 10L) {
                    throw JsonException(
                        JsonRejection.NUMBER_TOO_LARGE,
                        "`$field` does not fit a Long; §4.3 requires rejecting rather than " +
                            "truncating, and a wrapped timestamp is a negative one",
                    )
                }
                value = value * 10L + digit
            }
            return value
        }

        // -------------------------------------------------------------------------------------
        // Tags — the only nesting in the grammar, and it is exactly two levels.
        // -------------------------------------------------------------------------------------

        private fun readTags(): List<List<String>> {
            if (!has() || text[at] != '[') {
                throw JsonException(
                    JsonRejection.TAGS_NOT_AN_ARRAY,
                    "§4.1 defines `tags` as an array; offset $at opens with something else",
                )
            }
            at++
            val tags = ArrayList<List<String>>()
            var afterComma = false
            while (true) {
                skipWhitespace()
                if (!has()) throw unterminated("`tags`")
                if (text[at] == ']') {
                    if (afterComma) throw trailingComma("`tags`")
                    at++
                    return tags
                }
                if (text[at] != '[') throw tagIsNotAnArray()
                // Bounded HERE, before the tag's own characters are walked, so a million-tag input
                // costs one comparison rather than a million allocations. §4.3: reject, never
                // truncate, and an unbounded parser is a denial of service in the buyer's client.
                if (tags.size >= limits.maxTagsPerEvent) {
                    throw WireException(
                        WireRejection.TOO_MANY_TAGS,
                        "the event carries more than ${limits.maxTagsPerEvent} tags; §4.3 requires " +
                            "rejecting rather than truncating",
                    )
                }
                at++
                tags.add(readTag())
                afterComma = false
                skipWhitespace()
                if (!has()) throw unterminated("`tags`")
                when (text[at]) {
                    ',' -> {
                        at++
                        afterComma = true
                    }
                    ']' -> Unit
                    else -> throw JsonException(
                        JsonRejection.UNEXPECTED_CHARACTER,
                        "a `,` or a `]` must follow a tag; offset $at carries something else",
                    )
                }
            }
        }

        /** [at] is one past the tag's opening `[`. */
        private fun readTag(): List<String> {
            val values = ArrayList<String>()
            var afterComma = false
            while (true) {
                skipWhitespace()
                if (!has()) throw unterminated("a tag")
                if (text[at] == ']') {
                    if (afterComma) throw trailingComma("a tag")
                    at++
                    // An empty tag is refused by WireEvent, where §4.3's rule already lives;
                    // nothing is gained by refusing it twice under two names.
                    return values
                }
                if (text[at] != '"') throw tagElementIsNotAString()
                at++
                values.add(readStringBody())
                afterComma = false
                skipWhitespace()
                if (!has()) throw unterminated("a tag")
                when (text[at]) {
                    ',' -> {
                        at++
                        afterComma = true
                    }
                    ']' -> Unit
                    else -> throw JsonException(
                        JsonRejection.UNEXPECTED_CHARACTER,
                        "a `,` or a `]` must follow a tag value; offset $at carries something else",
                    )
                }
            }
        }

        private fun tagIsNotAnArray(): Nothing {
            if (text.startsWith(NULL_LITERAL, at)) throw nullTagElement()
            throw JsonException(
                JsonRejection.TAG_NOT_AN_ARRAY,
                "§4.3: a tag is an array of one or more strings; offset $at opens with something else",
            )
        }

        private fun tagElementIsNotAString(): Nothing {
            if (text[at] == '[') {
                throw JsonException(
                    JsonRejection.NESTED_ARRAY,
                    "an array where a tag value belongs, at offset $at. An event's JSON is exactly " +
                        "three levels deep, so this reader neither recurses nor counts depth: " +
                        "anything deeper is refused at the character that opens it",
                )
            }
            if (text.startsWith(NULL_LITERAL, at)) throw nullTagElement()
            throw JsonException(
                JsonRejection.TAG_ELEMENT_NOT_A_STRING,
                "§4.3 requires rejecting an event containing a non-string tag element; offset $at " +
                    "is not a string",
            )
        }

        // -------------------------------------------------------------------------------------
        // Plumbing.
        // -------------------------------------------------------------------------------------

        private fun has(): Boolean = at < text.length

        /**
         * RFC 8259's four whitespace characters and no others — a vertical tab and a non-breaking
         * space are not whitespace, and land on whichever refusal the caller was about to make.
         *
         * A `/` is refused here rather than falling through, because it is the one character that
         * could only ever open a JavaScript-style comment: JSON has no use for one outside a string,
         * and this function is never called from inside one.
         */
        private fun skipWhitespace() {
            while (at < text.length) {
                when (text[at]) {
                    ' ', '\t', '\n', '\r' -> at++
                    '/' -> throw JsonException(
                        JsonRejection.COMMENT,
                        "a `/` at offset $at, where JSON has no use for one; comments are not JSON",
                    )
                    else -> return
                }
            }
        }

        private fun refuseRepeat(existing: Any?, key: String) {
            if (existing != null) {
                throw JsonException(
                    JsonRejection.DUPLICATE_KEY,
                    "`$key` appears twice. §7.1 requires rejecting an object carrying the same key " +
                        "twice at every layer: first-wins and last-wins are both defensible, which " +
                        "is exactly the problem (§4.3)",
                )
            }
        }

        private fun <T> required(value: T?, key: String): T = value ?: throw JsonException(
            JsonRejection.MISSING_KEY,
            "the object carries no `$key`; §4.1's five id-bearing fields and the claimed `id` are " +
                "all required",
        )

        private fun unterminated(what: String): JsonException = JsonException(
            JsonRejection.UNTERMINATED,
            "the text ended inside $what, at offset $at of ${text.length}",
        )

        private fun trailingComma(what: String): JsonException = JsonException(
            JsonRejection.TRAILING_COMMA,
            "a trailing comma closes $what at offset $at. NIP-59's own worked gift wrap carries " +
                "one; §18 says a parser that accepts it is a parser that accepts malformed input " +
                "from a relay",
        )

        private fun unpairedSurrogate(): WireException = WireException(
            WireRejection.UNPAIRED_SURROGATE,
            "an escaped UTF-16 surrogate before offset $at is not part of a pair, so it is not a " +
                "character and has no UTF-8 encoding; §4.1 requires rejecting it rather than " +
                "substituting a replacement character, which would change the id",
        )

        private fun nullTagElement(): WireException = WireException(
            WireRejection.NULL_TAG_ELEMENT,
            "a null at offset $at where §4.3 requires a tag or a tag value; §4.3 requires " +
                "rejecting an event containing a non-string or null tag element",
        )

        private fun nibbleOrMinusOne(character: Char): Int = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> -1
        }

        private companion object {

            const val HEX_DIGITS_PER_ESCAPE: Int = 4

            /** 19 — the digit count of `Long.MAX_VALUE`. A cheap guard before the exact check. */
            const val MAX_LONG_DIGITS: Int = 19

            const val NULL_LITERAL: String = "null"

            /** 0x08 and 0x0C, JSON's `\b` and `\f`, as code points rather than invisible source. */
            const val BACKSPACE: Int = 0x08

            const val FORM_FEED: Int = 0x0C

            val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF

            val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
        }
    }

    // -----------------------------------------------------------------------------------------
    // The seven NIP-01 field names, and nothing else is a key this reader will accept.
    // -----------------------------------------------------------------------------------------

    private const val KEY_ID: String = "id"

    private const val KEY_PUBKEY: String = "pubkey"

    private const val KEY_CREATED_AT: String = "created_at"

    private const val KEY_KIND: String = "kind"

    private const val KEY_TAGS: String = "tags"

    private const val KEY_CONTENT: String = "content"

    private const val KEY_SIG: String = "sig"

    /** U+FEFF, as a code point so it never appears as an invisible character in this file. */
    private const val BYTE_ORDER_MARK: Int = 0xFEFF
}

/**
 * A `sig` checked for **shape** and nothing more: [EventJson.SIGNATURE_HEX_LENGTH] hexadecimal
 * characters, handed back in the case it arrived in.
 *
 * Top-level and file-private rather than a member of [EventJson], so the object's reader and its
 * writer reach the same one function: a signature that a round trip re-emits must be a signature
 * the reader would have accepted, and two copies of that rule is how the two come to differ.
 *
 * Not normalised. §4.3's accept-and-normalise rule is about hex values that are **compared**, and
 * this one is neither compared nor hashed here — it is handed to a verifier this library does not
 * have (§17). Nothing here verifies it or reports it as verified.
 */
private fun checkedEventSignature(sig: String): String {
    if (sig.length != EventJson.SIGNATURE_HEX_LENGTH) {
        throw JsonException(
            JsonRejection.SIGNATURE_MALFORMED,
            "a sig is exactly ${EventJson.SIGNATURE_HEX_LENGTH} hex characters (a 64-byte BIP-340 " +
                "signature); this one is ${sig.length}",
        )
    }
    for (character in sig) {
        val known = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        if (!known) {
            throw JsonException(
                JsonRejection.SIGNATURE_MALFORMED,
                "a sig is hexadecimal characters only; this one carries something else",
            )
        }
    }
    return sig
}
