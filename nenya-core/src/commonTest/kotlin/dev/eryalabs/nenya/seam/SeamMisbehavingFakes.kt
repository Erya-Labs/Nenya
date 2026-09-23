package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.platform.runtimeSimpleName
import dev.eryalabs.nenya.utf8Bytes

/**
 * The seam fakes §7.1's write path has to be proved against, beside T30's honest ones.
 *
 * T30 built the fakes that behave: a signer that signs and encrypts, a verifier that agrees with
 * it, a source of throwaway keypairs. Those reach the refusals that are about **structure** — a
 * reused key, an identity key, a signature by the wrong key. They cannot reach the refusals that
 * are about a seam answering something unusable, because an honest fake never does; and a rejection
 * constant no control produces is a constant nobody has shown the code can raise.
 *
 * So each fake here exists to reach exactly one `EnvelopeRejection`, its header says which, and it
 * **misbehaves only in the one answer that constant is about**. A fake that got two answers wrong
 * would prove only that the first check fired.
 *
 * Nothing here is typed. Every value is derived from this library's own SHA-256 over a pinned label
 * (`FakeCrypto`, `FakeKey`), or from an honest fake's answer with one property changed, so a
 * reviewer can change a seed, re-run the suite, and every property still holds.
 */
internal object TestBase64 {

    /**
     * Standard base64 with padding — RFC 4648's alphabet, which is NIP-44's payload encoding.
     *
     * Written here rather than reached for, because STOP RULE 11's budget has no encoder in it and
     * the JDK's is not available to common code. Twenty lines for one fixed alphabet is not a JSON
     * parser; `EventJson`'s header makes the same distinction.
     */
    const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(4 * ((bytes.size + 2) / 3))
        var index = 0
        while (index + 2 < bytes.size) {
            val triple = (byteAt(bytes, index) shl 16) or (byteAt(bytes, index + 1) shl 8) or
                byteAt(bytes, index + 2)
            out.append(ALPHABET[(triple ushr 18) and 0x3f])
            out.append(ALPHABET[(triple ushr 12) and 0x3f])
            out.append(ALPHABET[(triple ushr 6) and 0x3f])
            out.append(ALPHABET[triple and 0x3f])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val triple = byteAt(bytes, index) shl 16
                out.append(ALPHABET[(triple ushr 18) and 0x3f])
                out.append(ALPHABET[(triple ushr 12) and 0x3f])
                out.append("==")
            }
            2 -> {
                val triple = (byteAt(bytes, index) shl 16) or (byteAt(bytes, index + 1) shl 8)
                out.append(ALPHABET[(triple ushr 18) and 0x3f])
                out.append(ALPHABET[(triple ushr 12) and 0x3f])
                out.append(ALPHABET[(triple ushr 6) and 0x3f])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** The bytes, or `null` for anything that is not standard padded base64. */
    fun decode(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        if (text.isEmpty()) return ByteArray(0)
        var padding = 0
        while (padding < 2 && text[text.length - 1 - padding] == '=') padding++
        val out = ByteArray(text.length / 4 * 3 - padding)
        var written = 0
        var index = 0
        while (index < text.length) {
            var quad = 0
            for (offset in 0 until 4) {
                val character = text[index + offset]
                val value = if (character == '=') 0 else ALPHABET.indexOf(character)
                if (value < 0) return null
                quad = (quad shl 6) or value
            }
            for (shift in intArrayOf(16, 8, 0)) {
                if (written < out.size) out[written++] = ((quad ushr shift) and 0xff).toByte()
            }
            index += 4
        }
        return out
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff
}

/**
 * T30's honest signer with its NIP-44 payload carried as **base64** rather than hex.
 *
 * The envelope's three size bounds are derived from what NIP-44 can represent, and the derivation
 * (`Section71`) is base64 throughout: 65 535 bytes of plaintext pad and frame to 65 603 raw bytes,
 * which base64 carries in 87 472 characters. `FakeCrypto`'s hex is twice the plaintext instead of
 * four thirds of it, so a rumor at §7.1 step 1's ceiling produces a "seal" no conformant NIP-44
 * implementation would ever produce — 81 952 characters where the real one is 54 636. A test that
 * measured the write path's bounds against that would be measuring the fake's encoding.
 *
 * So this subclass re-encodes the same payload, leaving every other property of `FakeCryptoSigner`
 * exactly as it was: the same conversation key, the same MAC, the same counters, the same
 * signatures. It re-encodes through `FakeCrypto.hexToBytes` and back rather than reimplementing the
 * cipher, so there is one encryption in the test tree and not two.
 */
internal open class Nip44PayloadSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        when (val answer = super.nip44Encrypt(counterpartyPublicKeyHex, plaintext)) {
            is SeamAnswer.Provided -> SeamAnswer.Provided(
                TestBase64.encode(FakeCrypto.hexToBytes(answer.value) ?: ByteArray(0)),
            )
            is SeamAnswer.Unavailable -> answer
        }

    override fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String> {
        // A payload that is not base64 is still handed to the delegate, as a string it will refuse,
        // so the call is counted and the answer is the seam's own Unavailable rather than one
        // manufactured here.
        val bytes = TestBase64.decode(payload)
        val asHex = if (bytes == null) NOT_A_PAYLOAD else SeamFixtures.lowerHex(bytes)
        return super.nip44Decrypt(counterpartyPublicKeyHex, asHex)
    }

    override fun toString(): String = "Nip44PayloadSigner(stream=${key.stream})"

    private companion object {

        /** Not hex, so `FakeCrypto.decrypt` refuses it exactly as it refuses any other rubbish. */
        const val NOT_A_PAYLOAD: String = "not a payload"
    }
}

/**
 * [FakeEphemeralSigners] handing out [Nip44PayloadSigner]s, for the same reason that class exists.
 *
 * @param firstStream the seed of the first key; see [FakeKeyRanges] for why the ranges are a
 *   million apart.
 */
internal class Nip44PayloadEphemeralSigners(
    private val firstStream: Long = FakeKeyRanges.EPHEMERAL,
) : EphemeralSigners {

    private val issued = mutableListOf<Nip44PayloadSigner>()

    /** Every signer handed out, oldest first. */
    val handedOut: List<Nip44PayloadSigner> get() = issued.toList()

    /** The public keys of every signer handed out, in the order they were. */
    val keysHandedOut: List<String> get() = issued.map { it.key.hex }

    override fun fresh(): SeamAnswer<Signer> {
        val signer = Nip44PayloadSigner(FakeKey(firstStream + issued.size))
        issued += signer
        return SeamAnswer.Provided(signer)
    }
}

/**
 * A one-time signer that hands out the signers it was given, in order, and then nothing.
 *
 * The way a misbehaving signer becomes a *throwaway* one: every fake below is a [Signer], and
 * §7.1's write path reaches the throwaway seam's answers only through [EphemeralSigners.fresh].
 * Exhaustion answers [SeamAnswer.Unavailable] rather than repeating the last signer, because
 * repeating would silently turn a test about one refusal into a test about
 * `EPHEMERAL_KEY_REUSED`.
 */
internal class ScriptedEphemeralSigners(private val script: List<Signer>) : EphemeralSigners {

    /** How many times it was asked. */
    var calls: Int = 0
        private set

    override fun fresh(): SeamAnswer<Signer> {
        val index = calls
        calls++
        if (index >= script.size) return SeamAnswer.unavailable(SeamCapability.EPHEMERAL_SIGNER)
        return SeamAnswer.Provided(script[index])
    }
}

/**
 * Reaches `SIGNER_PUBLIC_KEY_MALFORMED`: a signer whose public key is 63 characters.
 *
 * §4.3 requires rejecting a value of the wrong length rather than padding or truncating it, and 63
 * is the length a naive implementation repairs. Everything else this signer does is honest.
 */
internal class ShortPublicKeySigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun publicKey(): SeamAnswer<String> =
        SeamAnswer.Provided(super.publicKey().provided().dropLast(1))
}

/** Reaches `ENCRYPTION_UNAVAILABLE`: a signer that signs perfectly well and encrypts nothing. */
internal class NoEncryptionSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.NIP44_ENCRYPTION)
}

/**
 * Reaches `CIPHERTEXT_MALFORMED`: a signer whose NIP-44 payload is the empty string.
 *
 * NIP-44 refuses an empty plaintext and every payload it produces carries a version byte, a nonce
 * and a MAC, so an empty payload is a seam answering something that cannot be a payload at all —
 * and it is what an adapter returns when it swallows its own error.
 */
internal class EmptyCiphertextSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        SeamAnswer.Provided("")
}

/**
 * Reaches `CIPHERTEXT_MALFORMED`'s other half: a signer whose NIP-44 payload is not printable
 * ASCII.
 *
 * The payload is an honest one with an **unpaired high surrogate** appended — the one shape that
 * has no UTF-8 encoding at all, and therefore the shape that would otherwise reach the event
 * writer as `WireRejection.UNPAIRED_SURROGATE` instead of as a named envelope refusal (§4.1). The
 * surrogate is written as a code point rather than as a literal character, so it cannot arrive in
 * this file as an invisible edit.
 */
internal class NonAsciiCiphertextSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        SeamAnswer.Provided(
            super.nip44Encrypt(counterpartyPublicKeyHex, plaintext).provided() + LONE_HIGH_SURROGATE,
        )

    private companion object {

        /** U+D800, half of a pair; not a character, and it has no UTF-8 encoding. */
        val LONE_HIGH_SURROGATE: Char = 0xD800.toChar()
    }
}

/**
 * Reaches `SEAL_TOO_LARGE_TO_EMIT` and `WRAP_TOO_LARGE_TO_EMIT`: a signer whose NIP-44 payload is
 * larger than NIP-44 can produce.
 *
 * Neither constant is reachable through an honest encryptor — §7.1 step 1's 40 960-byte rumor bound
 * is derived precisely so that it is not — so the only way to prove the code raises them is a seam
 * that returns more than it should. The payload is base64 of a SHA-256 chain from a pinned seed:
 * derived, never typed, and printable ASCII, so it is refused for its **size** rather than for its
 * shape.
 *
 * @param chars how many characters to return. The default clears
 *   `EnvelopeLimits.MAX_WRAP_CONTENT_CHARS`, and therefore both bounds.
 */
internal class OversizedCiphertextSigner(
    key: FakeKey,
    private val chars: Int = DEFAULT_CHARS,
) : FakeCryptoSigner(key) {

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> {
        // Counted through the honest path first, so a test can still assert how many encryptions
        // happened before the refusal.
        super.nip44Encrypt(counterpartyPublicKeyHex, plaintext)
        val out = StringBuilder(chars + PAYLOAD_BLOCK)
        var block = sha256("nenya-oversized-payload:${key.stream}".utf8Bytes())
        while (out.length < chars) {
            out.append(TestBase64.encode(block))
            block = sha256(block)
        }
        return SeamAnswer.Provided(out.substring(0, chars))
    }

    private companion object {

        /** Comfortably past 87 472, so one fake reaches both bounds. */
        const val DEFAULT_CHARS: Int = 90_000

        /** One SHA-256 digest in base64. */
        const val PAYLOAD_BLOCK: Int = 44
    }
}

/**
 * Reaches `RUMOR_TOO_LARGE_TO_OPEN`: a signer whose **decryption** returns a plaintext longer than
 * NIP-44 can carry.
 *
 * The read path's innermost size bound cannot be reached by any conformant envelope, and that is a
 * fact about the bounds rather than a gap in the tests: a seal is at most 65 535 bytes, which leaves
 * room for a rumor of roughly 48 KB, so no wrap a conformant sender emits carries a rumor above the
 * 65 535 the read path bounds it at. The only way a reader sees one is a seam returning more than
 * NIP-44 ever produces — which is exactly what `OversizedCiphertextSigner` is on the write side, and
 * this is the same fake pointed the other way.
 *
 * It misbehaves on **one** decryption and not on every one, because §7.1's read procedure decrypts
 * twice: the wrap's `content` to the seal, then the seal's to the rumor. A fake that oversized both
 * would be refused at the seal (`SEAL_TOO_LARGE`) and would never prove the rumor's bound exists.
 *
 * @param honestDecryptions how many decryptions to answer honestly before oversizing the next.
 *   `1` reaches the rumor's bound; `0` reaches the seal's, which also has an honest control.
 */
internal class OversizedPlaintextSigner(
    key: FakeKey,
    private val honestDecryptions: Int = 1,
    private val chars: Int = DEFAULT_CHARS,
) : Nip44PayloadSigner(key) {

    private var answered = 0

    override fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String> {
        // Through the honest path first, so a payload that genuinely does not authenticate is still
        // refused as Unavailable and the call is still counted.
        val answer = super.nip44Decrypt(counterpartyPublicKeyHex, payload)
        if (answer is SeamAnswer.Unavailable) return answer
        answered++
        if (answered <= honestDecryptions) return answer
        val out = StringBuilder(chars + PLAINTEXT_BLOCK)
        var block = sha256("nenya-oversized-plaintext:${key.stream}".utf8Bytes())
        while (out.length < chars) {
            out.append(TestBase64.encode(block))
            block = sha256(block)
        }
        return SeamAnswer.Provided(out.substring(0, chars))
    }

    private companion object {

        /** One byte past `EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES`, so the bound is probed exactly. */
        const val DEFAULT_CHARS: Int = 65_536

        /** One SHA-256 digest in base64. */
        const val PLAINTEXT_BLOCK: Int = 44
    }
}

/** Reaches `SIGNATURE_UNAVAILABLE`: a signer that encrypts perfectly well and signs nothing. */
internal class NoSignatureSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.EVENT_SIGNATURE)
}

/**
 * Reaches `SIGNATURE_MALFORMED`: a signer whose signature is 127 hex characters.
 *
 * One short of NIP-01's 128, which is the length an implementation that trimmed a leading zero
 * produces — and exactly the value §4.3 forbids padding back out.
 */
internal class ShortSignatureSigner(key: FakeKey) : FakeCryptoSigner(key) {

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> =
        SeamAnswer.Provided(super.signEvent(canonicalSerialisation).provided().dropLast(1))
}

/**
 * A signer whose payload encoding is base64 and whose signatures are by another key — T30's
 * [LyingCryptoSigner] with [Nip44PayloadSigner]'s encoding, so the envelope tests can use one
 * encoding throughout.
 *
 * Reaches `SIGNATURE_DOES_NOT_VERIFY` when a [FakeSecp256k1Ops] is injected: everything it returns
 * is well-formed, and every signature is a genuine signature by the wrong key.
 */
internal class LyingNip44PayloadSigner(
    reported: FakeKey,
    private val actual: FakeKey,
) : Nip44PayloadSigner(reported) {

    override val signingKey: FakeKey get() = actual

    override fun toString(): String =
        "LyingNip44PayloadSigner(reports=${key.stream}, signs=${actual.stream})"
}

/** [LyingEphemeralSigners] with [Nip44PayloadSigner]'s encoding; see [LyingNip44PayloadSigner]. */
internal class LyingNip44PayloadEphemeralSigners(
    private val firstStream: Long = FakeKeyRanges.LYING,
) : EphemeralSigners {

    private val issued = mutableListOf<LyingNip44PayloadSigner>()

    /** Every signer handed out, oldest first. */
    val handedOut: List<LyingNip44PayloadSigner> get() = issued.toList()

    override fun fresh(): SeamAnswer<Signer> {
        val reported = FakeKey(firstStream + issued.size * 2)
        val actual = FakeKey(firstStream + issued.size * 2 + 1)
        val signer = LyingNip44PayloadSigner(reported, actual)
        issued += signer
        return SeamAnswer.Provided(signer)
    }
}

/**
 * Calls every fake in this file at least once and returns the names it actually called.
 *
 * The same floor [exerciseEveryCryptoFake] carries for `SeamCryptoFakes.kt`, and folded into it, so
 * there is one coverage floor over both files rather than two that can drift. The names come from
 * [runtimeSimpleName] over the **instances** and are recorded after the call returns, never at
 * construction: recording at construction would let the call be deleted while the floor stayed
 * green, which is the one failure mode a coverage floor must not have.
 *
 * `SeamFakeCoverageTest` anchors the union to the compiled test tree, so a misbehaving fake added
 * here without a call turns the suite red rather than waiting for the task that first needs it.
 */
internal fun exerciseEveryMisbehavingFake(): Set<String> {
    val serialisation = "[0,\"a\",1,1059,[],\"coverage\"]"
    val exercised = mutableSetOf<String>()

    fun <T : Any> exercise(fake: T, use: (T) -> Unit): T {
        use(fake)
        exercised += runtimeSimpleName(fake)
        return fake
    }

    val key = FakeKey(stream = 92L)
    val counterparty = FakeKey(stream = 93L)

    // [TestBase64] is exercised here but deliberately **not** named: the floor this set feeds is
    // `SeamFakeCoverageTest`'s, which reflects over classes in the compiled test tree, and a Kotlin
    // `object`'s runtime simple name is the one entry the JVM run could not vouch for on the
    // JavaScript build. Its round trip is checked all the same, and a break in it fails here.
    val digest = sha256(COVERAGE.utf8Bytes())
    check(TestBase64.decode(TestBase64.encode(digest))?.contentEquals(digest) == true) {
        "base64 must round-trip, or every size this file models is measured wrong"
    }
    val payloadSigner = exercise(Nip44PayloadSigner(key)) {
        val payload = it.nip44Encrypt(counterparty.hex, COVERAGE).provided()
        check(Nip44PayloadSigner(counterparty).nip44Decrypt(key.hex, payload).provided() == COVERAGE)
        it.nip44Decrypt(key.hex, "not base64 at all").unavailable()
    }
    exercise(Nip44PayloadEphemeralSigners(firstStream = COVERAGE_EPHEMERAL)) { it.fresh().provided() }
    exercise(ScriptedEphemeralSigners(listOf(payloadSigner))) {
        it.fresh().provided()
        it.fresh().unavailable()
    }
    exercise(ShortPublicKeySigner(key)) { check(it.publicKey().provided().length == SHORT_KEY_LENGTH) }
    exercise(NoEncryptionSigner(key)) { it.nip44Encrypt(counterparty.hex, COVERAGE).unavailable() }
    exercise(EmptyCiphertextSigner(key)) {
        check(it.nip44Encrypt(counterparty.hex, COVERAGE).provided().isEmpty())
    }
    exercise(NonAsciiCiphertextSigner(key)) {
        check(it.nip44Encrypt(counterparty.hex, COVERAGE).provided().last().isHighSurrogate())
    }
    exercise(OversizedCiphertextSigner(key)) {
        check(it.nip44Encrypt(counterparty.hex, COVERAGE).provided().length > OVERSIZED_FLOOR)
    }
    exercise(OversizedPlaintextSigner(key, honestDecryptions = 1)) {
        val payload = Nip44PayloadSigner(counterparty).nip44Encrypt(key.hex, COVERAGE).provided()
        check(it.nip44Decrypt(counterparty.hex, payload).provided() == COVERAGE) {
            "the first decryption must be honest, or the rumor bound could never be reached"
        }
        check(it.nip44Decrypt(counterparty.hex, payload).provided().length > PLAINTEXT_FLOOR)
        it.nip44Decrypt(counterparty.hex, "not base64 at all").unavailable()
    }
    exercise(NoSignatureSigner(key)) { it.signEvent(serialisation).unavailable() }
    exercise(ShortSignatureSigner(key)) {
        check(it.signEvent(serialisation).provided().length == SHORT_SIGNATURE_LENGTH)
    }
    exercise(LyingNip44PayloadSigner(key, counterparty)) { it.signEvent(serialisation).provided() }
    exercise(LyingNip44PayloadEphemeralSigners(firstStream = COVERAGE_LYING)) { it.fresh().provided() }

    return exercised
}

/** Any distinctive plaintext; none of these fakes parses it. */
private const val COVERAGE: String = "coverage"

/** Key ranges of their own, so this coverage pass cannot collide with a test's own fixtures. */
private const val COVERAGE_EPHEMERAL: Long = 4_000_000L

private const val COVERAGE_LYING: Long = 5_000_000L

/** One short of §4.3's 64, which is what [ShortPublicKeySigner] exists to return. */
private const val SHORT_KEY_LENGTH: Int = 63

/** One short of NIP-01's 128. */
private const val SHORT_SIGNATURE_LENGTH: Int = 127

/** Past `EnvelopeLimits.MAX_WRAP_CONTENT_CHARS`, which is what makes the oversized fake oversized. */
private const val OVERSIZED_FLOOR: Int = 87_472

/** `EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES`, which the oversized plaintext must exceed. */
private const val PLAINTEXT_FLOOR: Int = 65_535
