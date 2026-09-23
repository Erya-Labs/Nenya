package dev.eryalabs.nenya.envelope

/**
 * §4.3's bounds on a `kind:13` seal and a `kind:1059` gift wrap, which are NIP-44's bounds and
 * not the ordinary event defaults.
 *
 * ### Why these two kinds have their own numbers
 *
 * §4.3's ordinary defaults are not merely conservative for a gift wrap, they are unusable. A
 * wrap's `content` is base64 over a NIP-44 payload whose plaintext is the seal's JSON, and the
 * seal's `content` is base64 over a NIP-44 payload whose plaintext is the rumor's JSON. Each
 * NIP-44 layer prefixes a version byte, a 32-byte nonce and a 2-byte length, pads the plaintext
 * to a power-of-two-derived size, and appends a 32-byte MAC; base64 then costs a further third.
 * A rumor of 7 169 bytes of JSON therefore produces a wrap whose `content` exceeds §4.3's 16 KiB
 * default — so an implementation obeying that default had to refuse to open a 10 KB chat message,
 * which every deployed NIP-17 client emits.
 *
 * The **rumor** keeps §4.3's ordinary defaults. It is an ordinary event, and nothing here relaxes
 * a bound on it; [MAX_RUMOR_JSON_BYTES] below is an additional refusal on the write path, not a
 * relaxation of any existing one.
 *
 * ### Where the numbers come from
 *
 * All three are **derived**, and `Section71Test` in `src/commonTest` re-derives them from the
 * vendored NIP-44 vectors rather than trusting this file: it implements NIP-44's `calc_padded_len`
 * against every `v2.valid.calc_padded_len` vector, reads the plaintext ceiling out of
 * `v2.invalid.encrypt_msg_lengths`, and computes the other two from the payload layout and the
 * seal JSON's fixed fields. It then holds its results equal both to the numbers parsed out of
 * §4.3 and to the constants here, so a specification revision, a vendored-vector change and a
 * hand edit of this file each turn the suite red instead of leaving three copies drifting apart.
 *
 * ### Constants, not a configurable limits object
 *
 * Unlike `WireLimits`, which §4.3 says SHOULD be configurable, these are not a client's choice:
 * they are what NIP-44 can represent. A client that lowered [MAX_NIP44_PLAINTEXT_BYTES] would
 * refuse messages conformant senders emit, and one that raised it would produce payloads no
 * deployed implementation can decrypt. [MAX_WRAP_JSON_BYTES] is the one number here that *is* a
 * local default, and it is a whole-event bound of the same kind `WireLimits` carries.
 *
 * Pure values: no clock, no randomness, no I/O, and nothing here encrypts anything.
 */
public object EnvelopeLimits {

    /**
     * 65 535 — the largest NIP-44 plaintext, in bytes, and therefore the largest seal JSON.
     *
     * Decision **F**: NIP-44's vendored text now describes a six-byte extended-length prefix that
     * would permit 65 536 and above, but its own published vectors list `65536` among
     * `v2.invalid.encrypt_msg_lengths` — encryption MUST refuse it — and no deployed application
     * emits the extended form. §4.3 follows the vectors, on the interoperability precedent §4.1
     * sets for canonical JSON escaping.
     *
     * The floor is 1: NIP-44 refuses an empty plaintext, and the vectors list `0` alongside
     * `65536`.
     */
    public const val MAX_NIP44_PLAINTEXT_BYTES: Int = 65_535

    /**
     * 87 472 — the largest `content` of a `kind:1059` wrap, in base64 characters.
     *
     * Exactly the base64 encoding of the largest payload [MAX_NIP44_PLAINTEXT_BYTES] permits:
     * 65 535 bytes pad to 65 536, plus 1 version byte, 32 nonce bytes, a 2-byte length prefix and
     * a 32-byte MAC is 65 603 raw bytes, which base64 carries in 87 472 characters. NIP-44's own
     * commentary names that 65 603 in the same words.
     */
    public const val MAX_WRAP_CONTENT_CHARS: Int = 87_472

    /**
     * 40 960 — the largest rumor JSON, in bytes, that may be **emitted**.
     *
     * A bound on writing and not on reading. Anything from 40 961 bytes upwards pads to 49 152,
     * whose base64 payload plus the seal JSON's fixed fields exceeds [MAX_NIP44_PLAINTEXT_BYTES],
     * so the seal cannot be wrapped at all: a sender that exceeded this would produce a message
     * nobody can open. §7.1 step 1 states it as a refusal at the sender for that reason.
     */
    public const val MAX_RUMOR_JSON_BYTES: Int = 40_960

    /**
     * 88 KiB — the default bound on a whole serialised `kind:1059` wrap, in bytes.
     *
     * A local default in the shape of §4.3's other whole-event bounds, and the only number here
     * an implementation may reasonably vary. It has to clear [MAX_WRAP_CONTENT_CHARS] plus the
     * wrap's own fields: the seven NIP-01 fields around an empty `content` come to 345 characters
     * at `kind:1059`, and the single `["p", "<64 hex>", "<relay>"]` tag §7.1 requires adds 1 099
     * more once §4.3's 1024-byte tag-value bound is applied to the relay hint. The widest
     * conformant wrap is therefore 88 916 bytes, which 88 KiB (90 112) clears with 1 196 to
     * spare; 86 KiB (88 064) would refuse it. `Section71Test` asserts both halves — that the
     * bound clears the widest conformant wrap, and that it clears it by less than two KiB, so it
     * is bounding the thing it names rather than being a large round number.
     */
    public const val DEFAULT_MAX_WRAP_JSON_BYTES: Int = 88 * 1024
}
