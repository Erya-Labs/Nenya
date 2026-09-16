package dev.eryalabs.nenya.delivery

import dev.eryalabs.nenya.crypto.constantTimeEquals

/**
 * Why a deliverable value, a release identity check (§10.3) or a buyer verification step
 * (§10.4) was refused.
 *
 * The reason is part of the API and not merely diagnostic text, for the same reason
 * `PaymentRejection` is. §10.3 makes the identity check a **four-operand** comparison and
 * §10.4 makes the two hash checks ordered, so "it did not verify" is never a useful answer: a
 * `file-type` divergence and an `x` divergence have different causes, and an implementation
 * that reports the same thing for both cannot tell its user which one happened. Tests assert
 * on these constants rather than on message wording.
 */
public enum class DeliveryRejection {

    /**
     * A hex value whose character count is not exactly [DeliverableHash.HEX_LENGTH]. §4.3: `x`
     * and `ox` are exactly 64 characters each and an implementation MUST reject a value of the
     * wrong length rather than padding or truncating it.
     */
    WRONG_LENGTH,

    /** A character outside `0-9`, `a-f`, `A-F`. Not a hex string at all. */
    NOT_HEX,

    /**
     * `size` is the length in bytes of the encrypted blob as a decimal string (§10.1), so a
     * negative value is not a size at all. §4.3 requires rejection rather than coercion.
     */
    NEGATIVE_SIZE,

    /**
     * A commitment carrying no `["m", ...]` value (§10.1). §10.3 forbids skipping the identity
     * check because a tag it looked for was absent, and a commitment with no MIME type has no
     * operand for the `file-type` half of that check — so it is refused where it is built,
     * rather than becoming an identity check that quietly compares three operands.
     */
    MIME_TYPE_ABSENT,

    /**
     * A release carrying no `["file-type", ...]` tag (§10.3). Named separately from
     * [FILE_TYPE_MISMATCH] because §10.3 says in so many words that an implementation MUST NOT
     * skip the check because the tag it looked for was absent: absence is a *refusal*, not a
     * pass, and a caller told "mismatch" would go looking for a value that was never there.
     */
    FILE_TYPE_ABSENT,

    /** §10.3 operand 1 — the release's `file-type` is not byte-identical to the commitment's `m`. */
    FILE_TYPE_MISMATCH,

    /** §10.3 operand 2 — the release's `x` is not byte-identical to the commitment's `x`. */
    SERVED_HASH_MISMATCH,

    /** §10.3 operand 3 — the release's `ox` is not byte-identical to the commitment's `ox`. */
    PLAINTEXT_HASH_MISMATCH,

    /** §10.3 operand 4 — the release's `size` is not byte-identical to the commitment's `size`. */
    SIZE_MISMATCH,

    /**
     * The commitment's declared `size` is above the bound the caller injected (§4.3, §10.4).
     * §4.3 requires rejecting rather than truncating when a bound is exceeded, and §10.4
     * requires the download be bounded at all — a relay or a blob host that announces a
     * 100 MiB deliverable is not a hypothetical.
     */
    SIZE_LIMIT_EXCEEDED,

    /**
     * The bytes handed to [ServedBytesVerified.verifyServedBytes] are not as long as the
     * commitment says they are. §10.4: an implementation MUST refuse a blob whose length
     * disagrees with `size`. Distinct from [SIZE_MISMATCH], which is a disagreement between
     * two *signed statements*; this one is a disagreement between a statement and the bytes.
     */
    SIZE_DISAGREEMENT,

    /**
     * §10.4 step 1 — the SHA-256 of the served bytes is not `x`. The buyer has the wrong or a
     * tampered blob, MUST NOT decrypt it and MUST NOT settle.
     */
    SERVED_BYTES_MISMATCH,

    /**
     * §10.4 step 3 — the SHA-256 of the plaintext bytes is not `ox`. §11.3 invariant 4 makes
     * this the only way `settled` is reachable, and §10.5 is why: `ox` was published before the
     * buyer paid and before the key existed in the buyer's hands, so the only file whose
     * plaintext hashes to it is the one the provider committed to.
     */
    PLAINTEXT_BYTES_MISMATCH,
}

/**
 * A deliverable value or verification step was refused, carrying the [reason] as data.
 *
 * One exception type for the whole delivery surface, so a caller has one thing to catch and
 * one field to branch on, mirroring `PaymentException` in the payment package and
 * `MoneyException` in the money package.
 *
 * **The message never echoes the caller's input.** A deliverable's plaintext is the thing the
 * buyer paid for and the served blob is its ciphertext; neither belongs in a crash report. A
 * rejection message may name a *length* and a *reason*, and may name a MIME type, which §10.3
 * compares as a value and which the release publishes in a tag either way. It may never name a
 * byte of either blob.
 */
public class DeliveryException internal constructor(
    public val reason: DeliveryRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * One of §10's `x` / `ox` SHA-256 commitments — 32 bytes, read from 64 hex characters (§4.3).
 *
 * `x` is the SHA-256 of the **encrypted** file exactly as served; `ox` is the SHA-256 of the
 * **plaintext** before encryption (§10.1, NIP-94 / NIP-17 semantics). Both are the same shape
 * and the same rules, so they are the same type; which one a value is, is a property of the
 * field it sits in rather than of the value.
 *
 * ### Case
 *
 * Uppercase and mixed-case input is **accepted and normalised**. §4.3's exception list — where
 * no normalisation of any kind is permitted — has exactly two entries, the BOLT-11 invoice
 * string and the Lightning preimage, and states that it is exhaustive. §4.3 then names `x` and
 * `ox` explicitly among the values that follow the general accept-and-normalise rule, so an
 * uppercase `x` is a conformant peer being permissive on the wire and MUST NOT be refused.
 *
 * ### The length is checked in `init`, not only in the factory
 *
 * A Kotlin class with a `private constructor` and a factory on its companion still emits a
 * **JVM-public synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which a
 * Java client reaches as `new DeliverableHash(bytes, null)`. So "the factory is the only way
 * in" is a property of today's Kotlin call sites and not of this type. The invariant lives in
 * `init`, which every construction path runs, and the array is copied on the way in as well as
 * on the way out — the caller of that synthetic constructor still holds its own reference.
 *
 * ### Why this package reads its own hex
 *
 * The payment package has a hex reader too, and it is deliberately file-private there: it
 * raises `PaymentRejection` values and enforces §4.3's *no-normalisation* rule for the
 * preimage, which is the opposite of the rule that applies here. Sharing one reader would mean
 * one rejection vocabulary spanning two unrelated failure surfaces, and would put the
 * preimage's uppercase rule one `when` branch away from `x`'s — which is precisely the pair
 * §4.3 keeps one line apart and which an implementation gets wrong in one direction or the
 * other. Twenty lines of pure function, covered by controls in both packages, is the cheaper
 * mistake.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class DeliverableHash private constructor(value: ByteArray) {

    private val value: ByteArray

    init {
        if (value.size != BYTE_LENGTH) {
            throw DeliveryException(
                DeliveryRejection.WRONG_LENGTH,
                "an x or ox commitment is exactly $BYTE_LENGTH bytes; this one is ${value.size}",
            )
        }
        this.value = value.copyOf()
    }

    /** A fresh copy of the 32 bytes. Copied, so a caller cannot mutate this commitment. */
    public fun bytes(): ByteArray = value.copyOf()

    /** The canonical lowercase hex form (§4.3), whatever case it was read in. */
    public fun toHex(): String = encodeLowerHex(value)

    override fun equals(other: Any?): Boolean =
        other is DeliverableHash && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /**
     * Deliberately carries no hex.
     *
     * §12 item 11 names key material, decryption keys, preimages and order ids; `x` and `ox`
     * are not on that list, and this type redacts anyway, for the reason §10.5 gives: `ox` is a
     * correlation handle. If the plaintext ever becomes public, its hash links that file to
     * this order, which is why §10.5 makes publishing `ox` in a public bid opt-in with an
     * explained trade-off rather than the default. A hash that reaches a host application's log
     * has been published in the least deliberate way available. [toHex] is there for the caller
     * who genuinely needs the value; a `toString` is never that caller.
     */
    override fun toString(): String = "DeliverableHash(redacted)"

    public companion object {

        /** 32 bytes — a SHA-256 digest. */
        public const val BYTE_LENGTH: Int = 32

        /** 64 characters — [BYTE_LENGTH] bytes of hex, the length §4.3 fixes for `x` and `ox`. */
        public const val HEX_LENGTH: Int = BYTE_LENGTH * 2

        /**
         * An `x` or `ox` commitment read from [HEX_LENGTH] hex characters, in either case
         * (§4.3), normalised to lowercase.
         *
         * @throws DeliveryException [DeliveryRejection.WRONG_LENGTH] if the character count is
         *   not [HEX_LENGTH] — §4.3 requires rejection rather than padding or truncation — or
         *   [DeliveryRejection.NOT_HEX] for a non-hex character.
         */
        public fun ofHex(hex: String): DeliverableHash = DeliverableHash(decodeThirtyTwoBytes(hex))
    }
}

/**
 * §10.1's `kind:16` `type=5` commitment, reduced to the four values §10.3 and §10.4 compare.
 *
 * The provider sends this **before** any payment request, which is the whole construction:
 * because `ox` is published before the buyer pays and before the decryption key exists in the
 * buyer's hands, the provider cannot substitute a different file afterwards (§10.5).
 *
 * ### These are typed parameters, not a parse
 *
 * There is no tag codec in this library yet, so the caller supplies the four values it read
 * out of the `["x", ...]`, `["ox", ...]`, `["m", ...]` and `["size", ...]` tags. Building that
 * codec here is out of scope; the narrowing is recorded machine-readably on every
 * [DeliveryEvidence] this package issues, as [DeliveryCheck.COMMITMENT_CARRIES_NO_KEY] and
 * [DeliveryCheck.ENCRYPTION_PARAMETERS] — both §10 obligations that live in tags this type
 * does not carry.
 *
 * ### What it does not hold
 *
 * The blob URL, and that is deliberate rather than an omission. §12 item 10 forbids an
 * implementation automatically fetching a URL from an unknown counterparty, and this library
 * has no I/O at all (STOP RULE 13): the served bytes arrive as a parameter, fetched by the
 * embedding client under its own policy. A URL held here would be a URL something could be
 * tempted to fetch. It also does not hold `decryption-key` or `decryption-nonce` — §10.1 says
 * a commitment carrying either MUST be rejected, since releasing the key at commitment time
 * collapses the construction.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class DeliverableCommitment(

    /** §10.1's `["x", ...]` — the SHA-256 of the encrypted bytes exactly as served. */
    public val x: DeliverableHash,

    /** §10.1's `["ox", ...]` — the SHA-256 of the plaintext before encryption. */
    public val ox: DeliverableHash,

    /**
     * §10.1's `["m", ...]` value, compared byte-identically against the release's `file-type`
     * (§10.3). Never normalised, case-folded or parsed as a media type: §10.3 says
     * byte-identical, and two implementations that each "helpfully" normalise differently
     * disagree about the same signed event.
     */
    public val mimeType: String,

    /** §10.1's `["size", ...]` — the length in bytes of the **encrypted** blob. */
    public val sizeBytes: Long,
) {

    init {
        requireNonNegativeSize(sizeBytes, "a commitment's size")
        if (mimeType.isEmpty()) {
            throw DeliveryException(
                DeliveryRejection.MIME_TYPE_ABSENT,
                "§10.1 requires a commitment to carry an [\"m\", ...] value, and §10.3 forbids " +
                    "skipping the identity check because a tag it looked for was absent",
            )
        }
    }

    /**
     * §10.3's identity check, as the **four-operand** comparison the specification makes
     * normative — and it is four on purpose. A check that compares only `x` passes a careless
     * test, because `x` is the operand the rest of §10.4 exercises anyway; the ones that catch
     * a real substitution are the other three.
     *
     * - the release's `file-type` **value** MUST be byte-identical to this commitment's `m`
     *   **value**. They are differently named because each follows its own host kind's
     *   convention, so §10.3 states the mapping normatively rather than leaving it implied.
     *   §10.3 also forbids looking for `file-type` on the commitment or `m` on the release,
     *   which is why the two types name their fields differently and neither carries both;
     * - the release's `x`, `ox` and `size` MUST each be byte-identical to this commitment's
     *   value of the same name.
     *
     * **Where "byte-identical" is narrowed here, stated rather than papered over.** For `x` and
     * `ox` it is exact: §4.3 licenses normalising the case on read, so two values that differ
     * only in case are the same value and everything else is a divergence. For `size` this type
     * compares the two **parsed** integers, because it takes a `Long` and never sees the decimal
     * string §10.3 is talking about. `["size", "018342912"]` and `["size", "18342912"]` are not
     * byte-identical and would compare equal here. That is not a gap this check can close: §4.3
     * requires a timestamp-shaped tag value be rejected unless it is a canonical non-negative
     * decimal integer, which makes the leading-zero form refusable **on parse**, and the parse is
     * the tag codec's — which does not exist yet. So the obligation belongs to the codec when it
     * lands, and is recorded here so that it lands with it.
     *
     * Any mismatch in any of the four MUST move the order to `disputed` (§10.3). That is the
     * caller's transition to make: this function reports *which* operand diverged and has no
     * opinion about state, because the release is bound to its order by its `["order", ...]`
     * tag and not by hash equality — an implementation handling two concurrent orders with the
     * same provider routes on that tag first and verifies the hashes second.
     *
     * @throws DeliveryException [DeliveryRejection.FILE_TYPE_ABSENT] when the release carries
     *   no `file-type` at all — §10.3 forbids skipping the check in that case rather than
     *   refusing it; otherwise [DeliveryRejection.FILE_TYPE_MISMATCH],
     *   [DeliveryRejection.SERVED_HASH_MISMATCH], [DeliveryRejection.PLAINTEXT_HASH_MISMATCH]
     *   or [DeliveryRejection.SIZE_MISMATCH], naming the operand that diverged.
     */
    public fun checkReleaseIdentity(release: DeliverableRelease) {
        val fileType = release.fileType
            ?: throw DeliveryException(
                DeliveryRejection.FILE_TYPE_ABSENT,
                "the release carries no [\"file-type\", ...] tag, and §10.3 says an implementation " +
                    "MUST NOT skip the check because the tag it looked for was absent",
            )
        if (fileType != mimeType) {
            throw DeliveryException(
                DeliveryRejection.FILE_TYPE_MISMATCH,
                "the release's file-type is not byte-identical to the commitment's m (§10.3)",
            )
        }
        if (release.x != x) {
            throw DeliveryException(
                DeliveryRejection.SERVED_HASH_MISMATCH,
                "the release's x is not byte-identical to the commitment's x (§10.3)",
            )
        }
        if (release.ox != ox) {
            throw DeliveryException(
                DeliveryRejection.PLAINTEXT_HASH_MISMATCH,
                "the release's ox is not byte-identical to the commitment's ox (§10.3)",
            )
        }
        if (release.sizeBytes != sizeBytes) {
            throw DeliveryException(
                DeliveryRejection.SIZE_MISMATCH,
                "the release's size is not byte-identical to the commitment's size (§10.3)",
            )
        }
    }

    /** Names no hash and no MIME type. See [DeliverableHash.toString]. */
    override fun toString(): String = "DeliverableCommitment(sizeBytes=$sizeBytes)"
}

/**
 * §10.3's `kind:15` file-message release, reduced to the four values §10.3 compares.
 *
 * Sent by the provider after payment evidence verifies. It is an ordinary NIP-17 file message
 * so a generic NIP-17 client can render it, which is why its MIME type travels as `file-type`
 * rather than as the commitment's `m`.
 *
 * ### These are typed parameters, not a parse
 *
 * As with [DeliverableCommitment]: no tag codec exists yet, so the caller supplies what it read
 * out of the tags. This type deliberately does **not** hold `decryption-key` or
 * `decryption-nonce`, because this library does not decrypt — §10.4 step 2 is the caller's, and
 * every [DeliveryEvidence] says so as [DeliveryCheck.GCM_AUTHENTICATION]. Holding a decryption
 * key in a type that has no use for one would put key material one careless `toString` away
 * from a log (§12 item 11).
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class DeliverableRelease(

    /** §10.3's `["x", ...]` — MUST be the same value as the commitment's. */
    public val x: DeliverableHash,

    /** §10.3's `["ox", ...]` — MUST be the same value as the commitment's. */
    public val ox: DeliverableHash,

    /**
     * §10.3's `["file-type", ...]` value, or `null` when the release carried no such tag.
     *
     * Absence is representable on purpose. §10.3 says an implementation MUST NOT skip the
     * identity check because the tag it looked for was absent, so the absent case has to reach
     * [DeliverableCommitment.checkReleaseIdentity] to be *refused* there
     * ([DeliveryRejection.FILE_TYPE_ABSENT]). A type that made absence unrepresentable would
     * push the decision back into whatever code builds this value, where "the tag was missing
     * so I will not check" is exactly the shortcut §10.3 names.
     */
    public val fileType: String?,

    /** §10.3's `["size", ...]` — MUST be the same value as the commitment's. */
    public val sizeBytes: Long,
) {

    init {
        requireNonNegativeSize(sizeBytes, "a release's size")
    }

    /** Names no hash and no MIME type. See [DeliverableHash.toString]. */
    override fun toString(): String = "DeliverableRelease(sizeBytes=$sizeBytes)"
}

/**
 * §10.1: `size` is a byte length written as a decimal string, so a negative value is not a size
 * at all. Enforced in `init` on both types, which every construction path runs — including the
 * JVM-public synthetic constructor Kotlin emits for a class with a default-carrying or
 * inline-class parameter.
 */
private fun requireNonNegativeSize(sizeBytes: Long, what: String) {
    if (sizeBytes < 0L) {
        throw DeliveryException(
            DeliveryRejection.NEGATIVE_SIZE,
            "$what is a byte length and cannot be negative; this one is $sizeBytes",
        )
    }
}

/**
 * §4.3's 64-character hex reader for `x` and `ox`, kept file-private so it never becomes API.
 * Uppercase is accepted and normalised: §4.3's no-normalisation exception list is exhaustive
 * and names only the BOLT-11 string and the preimage.
 */
private fun decodeThirtyTwoBytes(text: String): ByteArray {
    if (text.length != DeliverableHash.HEX_LENGTH) {
        throw DeliveryException(
            DeliveryRejection.WRONG_LENGTH,
            "an x or ox commitment is exactly ${DeliverableHash.HEX_LENGTH} hex characters, which " +
                "is ${DeliverableHash.BYTE_LENGTH} bytes; this one is ${text.length} characters, and " +
                "§4.3 requires rejecting a value of the wrong length rather than padding or " +
                "truncating it",
        )
    }
    val bytes = ByteArray(DeliverableHash.BYTE_LENGTH)
    for (i in 0 until DeliverableHash.BYTE_LENGTH) {
        bytes[i] = ((nibble(text[2 * i]) shl 4) or nibble(text[2 * i + 1])).toByte()
    }
    return bytes
}

private fun nibble(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> throw DeliveryException(
        DeliveryRejection.NOT_HEX,
        "an x or ox commitment is hexadecimal characters only; this one carries something else",
    )
}

/** §4.3's canonical hex form: lowercase, unpadded. */
private fun encodeLowerHex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS: String = "0123456789abcdef"
