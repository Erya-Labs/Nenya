package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.crypto.constantTimeEquals
import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.utf8Bytes

/**
 * The crypto-free fakes the gift-wrap tasks stand on: a keypair, a signer that signs and
 * NIP-44-encrypts, a BIP-340 verifier that agrees with it, a source of throwaway signers, and the
 * misbehaving variants §7.1's refusals have to be proved against.
 *
 * Nothing here performs any cryptography beyond this library's own FIPS 180-4 SHA-256. There is no
 * secp256k1 and no NIP-44 — STOP RULE 11 forbids the dependency either would need — so these are a
 * **model** of the two, built so that the structural properties the envelope code depends on hold:
 * a signature that only the holder of a key produces and that a verifier accepts for that key and
 * no other, and an encryption only the two parties to a conversation can undo.
 *
 * ### The honest limit of what these prove, stated here so no later task has to guess
 *
 * They model "only the two key holders can decrypt" **against an adversary that goes through the
 * same fakes**: a test that hands [FakeCryptoSigner.nip44Decrypt] the wrong counterparty key, or a
 * payload whose bytes have been disturbed, gets [Nip44Decryption.Refused] and that is a real
 * result about the code under test. They prove nothing against an adversary with the conversation
 * key itself, because [FakeCrypto.conversationKey] is a pure function of two public keys and
 * anything at all can compute it. **A test MUST NOT forge under a conversation key directly** —
 * calling [FakeCrypto.encrypt] to manufacture a payload that a seam is then expected to reject is
 * a test of this file, not of Nenya, and it would pass against a library that had no
 * confidentiality property whatsoever. Adversaries in the envelope tests are other
 * [FakeCryptoSigner]s, holding keys of their own.
 *
 * Two further limits, both deliberate:
 *
 * - **The encryption is deterministic.** Real NIP-44 draws a random nonce, so the same plaintext
 *   encrypts differently every time; here it does not. That makes a failing test reproducible,
 *   and it is safe for the envelope tasks because §7.1 already gives the two copies of a message
 *   different throwaway keys and different addressees, so no two payloads in one wrap share a
 *   conversation key. A test that needs two distinct ciphertexts under **one** key must vary the
 *   plaintext.
 * - **Freshness is modelled, not enforced.** [FakeEphemeralSigners] does hand out a new key per
 *   call, but §3 says no implementation may accept a *claim* of freshness from that seam, and
 *   nothing here gives one. [FixedEphemeralSigners] is the counterexample the refusals are aimed
 *   at.
 *
 * Every value is derived from [sha256] over a label and a pinned seed. Nothing is typed: the
 * queue's Definition of done forbids a key, a hash or a signature appearing in a test as a literal
 * somebody wrote out, and a reviewer can change a seed here, re-run the suite, and watch every
 * property still hold.
 */
internal object FakeCrypto {

    /** The MAC this fake appends, in bytes. NIP-44's is 32; 16 is enough to model a mismatch. */
    const val MAC_LENGTH: Int = 16

    /**
     * The 32 bytes a signature is actually over: §4.1's event id, the SHA-256 of the canonical
     * serialisation.
     *
     * This is the fake's half of the contract [Signer.signEvent] states. A signer that signed the
     * serialisation itself would produce a signature [FakeSecp256k1Ops] rejects, which is the
     * property that makes the reworded KDoc testable rather than decorative.
     */
    fun eventId(canonicalSerialisation: String): ByteArray = sha256(canonicalSerialisation.utf8Bytes())

    /**
     * The 64 bytes this fake calls a BIP-340 signature by [publicKeyXOnlyHex] over [eventId].
     *
     * A pure function of the key and the message, in two SHA-256 halves. It is not a signature
     * scheme — anything holding the **public** key can compute it — and that is exactly why the
     * header above forbids forging under a conversation key: the same is true there. What it does
     * model faithfully is the only property the envelope code reads off a verdict, that a
     * signature made under one key does not verify under another.
     */
    fun signature(publicKeyXOnlyHex: String, eventId: ByteArray): ByteArray {
        require(eventId.size == 32) { "a nostr signature is over a 32-byte event id (§4.1)" }
        return sha256("nenya-fake-sig-0:$publicKeyXOnlyHex:".utf8Bytes() + eventId) +
            sha256("nenya-fake-sig-1:$publicKeyXOnlyHex:".utf8Bytes() + eventId)
    }

    /**
     * The conversation key two parties share, **symmetric** in its two arguments.
     *
     * The keys are sorted before hashing, so either party derives the same value from its own key
     * and the other's — which is the one structural property of ECDH the envelope code depends on.
     * §7.1 needs it in both directions: the sender encrypts the seal to the addressee, and the
     * addressee decrypts it back, and neither holds the other's secret.
     */
    fun conversationKey(oneHex: String, otherHex: String): ByteArray {
        val low = minOf(oneHex, otherHex)
        val high = maxOf(oneHex, otherHex)
        return sha256("nenya-fake-nip44-ck:$low:$high".utf8Bytes())
    }

    /** [length] bytes of SHA-256 in counter mode under [conversationKey]. */
    fun keystream(conversationKey: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        var counter = 0
        while (filled < length) {
            val block = sha256(
                "nenya-fake-nip44-ks:".utf8Bytes() + conversationKey + byteArrayOf(
                    (counter ushr 24).toByte(),
                    (counter ushr 16).toByte(),
                    (counter ushr 8).toByte(),
                    counter.toByte(),
                ),
            )
            val take = minOf(block.size, length - filled)
            block.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    /** [MAC_LENGTH] bytes over the ciphertext, so a disturbed payload is refused rather than decoded. */
    fun mac(conversationKey: ByteArray, ciphertext: ByteArray): ByteArray =
        sha256("nenya-fake-nip44-mac:".utf8Bytes() + conversationKey + ciphertext).copyOf(MAC_LENGTH)

    /** The payload: the MAC then the ciphertext, in lowercase hex. */
    fun encrypt(conversationKey: ByteArray, plaintext: String): String {
        val bytes = plaintext.utf8Bytes()
        val stream = keystream(conversationKey, bytes.size)
        val ciphertext = ByteArray(bytes.size) { (bytes[it].toInt() xor stream[it].toInt()).toByte() }
        return SeamFixtures.lowerHex(mac(conversationKey, ciphertext) + ciphertext)
    }

    /**
     * The plaintext, or `null` when the payload is malformed or its MAC does not match.
     *
     * `null` rather than a throw, and never a partially-decrypted string: the caller turns it into
     * [Nip44Decryption.Refused] — a decryption **performed** and refused, which is what a real
     * NIP-44 implementation answers when authentication fails and what §17 requires be
     * distinguishable both from "decrypted, and the contents were rubbish" and from "there is no
     * decryption capability here at all".
     */
    fun decrypt(conversationKey: ByteArray, payload: String): String? {
        val raw = hexToBytes(payload) ?: return null
        if (raw.size < MAC_LENGTH) return null
        val offered = raw.copyOfRange(0, MAC_LENGTH)
        val ciphertext = raw.copyOfRange(MAC_LENGTH, raw.size)
        if (!constantTimeEquals(mac(conversationKey, ciphertext), offered)) return null
        val stream = keystream(conversationKey, ciphertext.size)
        return ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor stream[it].toInt()).toByte() }
            .decodeToString()
    }

    /** Lowercase or uppercase hex to bytes, or `null` for anything that is not hex. */
    fun hexToBytes(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = text[2 * index].digitToIntOrNull(16) ?: return null
            val low = text[2 * index + 1].digitToIntOrNull(16) ?: return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}

/**
 * Where each source of throwaway keys starts, kept a **million** apart.
 *
 * [FakeKey] is a pure function of its stream, so two sources whose ranges overlap hand out the
 * same key and a test asserting "these two wraps used different keys" fails for a reason that has
 * nothing to do with the code under test. The first draft spaced these a thousand apart, which is
 * wide enough for this file's own thousand-call test and **not** wide enough for the two-thousand
 * wrap keys §7.1's two-copy rule will need: at 2 000 calls [FakeEphemeralSigners] would have
 * landed exactly on the reused-key fixture. A million leaves no plausible run able to reach the
 * next range.
 *
 * Small streams — single and double digits — are free for tests to use for named parties.
 */
internal object FakeKeyRanges {

    /** [FakeEphemeralSigners]: one key per `fresh()` call, counting up from here. */
    const val EPHEMERAL: Long = 1_000_000L

    /** The single key [FixedEphemeralSigners.reusing] hands out on every call. */
    const val REUSED: Long = 2_000_000L

    /** [LyingEphemeralSigners]: two keys per call, a reported one and the one it signs with. */
    const val LYING: Long = 3_000_000L
}

/**
 * A keypair for the fakes: a public key and nothing else.
 *
 * There is no secret-key field, and that absence is the point rather than an economy. §3 says no
 * secret key crosses the seam boundary in either direction, and a fixture type holding one would
 * make it easy for a later test to pass it across without noticing. Everything the fakes need —
 * signatures, conversation keys — is derived from the **public** key, which is only sound because
 * none of it is real cryptography (see [FakeCrypto]'s header).
 *
 * @param stream the pinned seed. Two [FakeKey]s with different streams have different keys; two
 *   with the same stream are the same key, which is how a test says "this is the same party".
 */
internal class FakeKey(val stream: Long) {

    /** The x-only public key as raw bytes — 32 of them, because it is a SHA-256 digest. */
    val bytes: ByteArray = sha256("nenya-fake-key:$stream".utf8Bytes())

    /** §4.3's form: 64 lowercase hex characters. */
    val hex: String = SeamFixtures.lowerHex(bytes)

    /** Names the stream, never the key. §12 item 11 covers key material of every kind. */
    override fun toString(): String = "FakeKey(stream=$stream, key=redacted)"
}

/**
 * A signer that really signs and really encrypts — under [FakeCrypto]'s model — and counts what it
 * was asked to do.
 *
 * The counts are what let a test assert that a wrap was built with **one** call to each seam
 * method rather than several, which is how §7.1 step 4's "its own seal per copy" is proved rather
 * than assumed.
 *
 * Its [signEvent] honours the contract [Signer.signEvent] states: the parameter is the canonical
 * serialisation, and the signature returned is over that serialisation's SHA-256.
 */
internal open class FakeCryptoSigner(val key: FakeKey) : Signer {

    /**
     * The key this signer actually signs and encrypts with, which for an honest signer is the key
     * it reports. [LyingCryptoSigner] is the one that overrides it.
     */
    protected open val signingKey: FakeKey get() = key

    /** One counter per method, so a test can say which seam operation happened and how often. */
    var publicKeyCalls: Int = 0
        private set
    var signEventCalls: Int = 0
        private set
    var encryptCalls: Int = 0
        private set
    var decryptCalls: Int = 0
        private set

    override fun publicKey(): SeamAnswer<String> {
        publicKeyCalls++
        return SeamAnswer.Provided(key.hex)
    }

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> {
        signEventCalls++
        return SeamAnswer.Provided(
            SeamFixtures.lowerHex(
                FakeCrypto.signature(signingKey.hex, FakeCrypto.eventId(canonicalSerialisation)),
            ),
        )
    }

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> {
        encryptCalls++
        return SeamAnswer.Provided(
            FakeCrypto.encrypt(FakeCrypto.conversationKey(signingKey.hex, counterpartyPublicKeyHex), plaintext),
        )
    }

    /**
     * Decrypts, or reports a decryption it **performed and refused**.
     *
     * Never [SeamAnswer.Unavailable], and that is T35's correction rather than a detail: this fake
     * holds a key and does the work, so every failure it can have is a payload it authenticated and
     * rejected. Answering `Unavailable` — which it did until T35 — reported a check that ran as a
     * check that did not run, which is §17's rule broken in the direction nobody watches. A signer
     * that genuinely cannot decrypt is [DecryptUnavailableSigner], and that is a different fake.
     */
    override fun nip44Decrypt(
        counterpartyPublicKeyHex: String,
        payload: String,
    ): SeamAnswer<Nip44Decryption> {
        decryptCalls++
        val plaintext = FakeCrypto.decrypt(
            FakeCrypto.conversationKey(signingKey.hex, counterpartyPublicKeyHex),
            payload,
        ) ?: return SeamAnswer.Provided(Nip44Decryption.Refused)
        return SeamAnswer.Provided(Nip44Decryption.Decrypted(plaintext))
    }

    /** Names the stream of the key it reports, never the key. */
    override fun toString(): String = "FakeCryptoSigner(stream=${key.stream})"
}

/**
 * A signer that **reports one public key and signs and encrypts with another**.
 *
 * §3's rule for the signer seam is that nothing it *claims* about what was signed may be accepted
 * beyond the bytes returned, and this is that rule with a subject: everything this signer returns
 * is well-formed, and every one of its signatures is a genuine signature — by the wrong key. Code
 * that takes [publicKey] as the author of what [signEvent] produced believes it; code that puts
 * the two through [FakeSecp256k1Ops] does not.
 *
 * For §7.1 it is the shape a compromised one-time signer takes. A wrap whose `pubkey` is not the
 * key that signed it is a wrap whose signature check must fail, and a reader that skipped the
 * check cannot tell.
 */
internal class LyingCryptoSigner(
    reported: FakeKey,
    private val actual: FakeKey,
) : FakeCryptoSigner(reported) {

    override val signingKey: FakeKey get() = actual

    override fun toString(): String = "LyingCryptoSigner(reports=${key.stream}, signs=${actual.stream})"
}

/**
 * The BIP-340 seam that agrees with [FakeCryptoSigner], and disagrees with everything else.
 *
 * Answers [SignatureVerdict.VALID] for exactly one thing: a 32-byte message whose [FakeCrypto]
 * signature under the key offered equals the signature offered. Everything else is
 * [SignatureVerdict.INVALID] — a flipped bit in either operand, the wrong key, a signature or key
 * of the wrong length, and a message that is not 32 bytes.
 *
 * The message-length rule is the contract [Secp256k1Ops.verifySchnorr] now states: what this
 * library ever passes is a §4.1 event id, and this fake's scheme is defined over nothing else, so
 * it reports a 33-byte message as not verifying rather than pretending to check it. That is a
 * verdict and not an [SeamAnswer.Unavailable] because it is a real answer: no signature this fake
 * would ever produce covers that message.
 *
 * Distinct from [OptimisticSecp256k1Ops], which says VALID to everything and exists only to prove
 * the return type can express a verdict at all.
 */
internal class FakeSecp256k1Ops : Secp256k1Ops {

    /** How many verdicts were asked for, so a test can prove the check actually ran. */
    var calls: Int = 0
        private set

    override fun verifySchnorr(
        publicKeyXOnly: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): SeamAnswer<SignatureVerdict> {
        calls++
        if (publicKeyXOnly.size != 32 || message.size != 32 || signature.size != 64) {
            return SeamAnswer.Provided(SignatureVerdict.INVALID)
        }
        val expected = FakeCrypto.signature(SeamFixtures.lowerHex(publicKeyXOnly), message)
        return SeamAnswer.Provided(
            if (constantTimeEquals(expected, signature)) SignatureVerdict.VALID else SignatureVerdict.INVALID,
        )
    }
}

/**
 * The well-behaved one-time signer: a brand-new [FakeCryptoSigner] for every [fresh] call, and a
 * record of every one it handed out.
 *
 * The record is what makes §7.1's rules checkable. Step 3 wants a new keypair per wrap and step 4
 * wants the two copies of one message to differ, and both are assertions about the *set* of keys
 * a run drew — which no test can make unless the source remembers.
 *
 * @param firstStream the seed of the first key. Two sources built with different streams hand out
 *   disjoint keys, so a test can have a sender and a recipient without their throwaway keys
 *   colliding. The default is [FakeKeyRanges.EPHEMERAL]; see there for why the ranges are a
 *   million apart.
 */
internal class FakeEphemeralSigners(
    private val firstStream: Long = FakeKeyRanges.EPHEMERAL,
) : EphemeralSigners {

    private val issued = mutableListOf<FakeCryptoSigner>()

    /** Every signer handed out, oldest first. */
    val handedOut: List<FakeCryptoSigner> get() = issued.toList()

    /** The public keys of every signer handed out, in the order they were. */
    val keysHandedOut: List<String> get() = issued.map { it.key.hex }

    override fun fresh(): SeamAnswer<Signer> {
        val signer = FakeCryptoSigner(FakeKey(firstStream + issued.size))
        issued += signer
        return SeamAnswer.Provided(signer)
    }
}

/**
 * A one-time signer that is not one: it hands out **the same signer every time**.
 *
 * The three misbehaviours §7.1 names are one mechanism under three meanings, so this is one class
 * with three factories rather than three copies of four lines. What differs is which key the fixed
 * signer holds, and that is what each refusal is about:
 *
 * - [reusing] — a throwaway key that is not thrown away. §7.1 step 4: the keypair MUST differ
 *   between the two copies of a message, because a key appearing on two wraps links them.
 * - [identity] — the sender's own key. §7.1 step 4: it MUST equal neither party's key, and this is
 *   the failure that unmasks the sender to anyone reading the relay.
 * - [recipientKey] — the recipient's key. The same rule, the other party, and the one a naive
 *   implementation reaches for because the recipient's key is already in hand.
 */
internal class FixedEphemeralSigners private constructor(private val fixed: Signer) : EphemeralSigners {

    /** How many times it was asked, so a test can prove the call happened more than once. */
    var calls: Int = 0
        private set

    override fun fresh(): SeamAnswer<Signer> {
        calls++
        return SeamAnswer.Provided(fixed)
    }

    companion object {

        /** Hands out one throwaway signer over and over — the same key on every wrap. */
        fun reusing(
            signer: FakeCryptoSigner = FakeCryptoSigner(FakeKey(FakeKeyRanges.REUSED)),
        ): FixedEphemeralSigners = FixedEphemeralSigners(signer)

        /** Hands out the sender's own signer, so the wrap is signed by the key it hides. */
        fun identity(user: Signer): FixedEphemeralSigners = FixedEphemeralSigners(user)

        /** Hands out a signer over the recipient's key. */
        fun recipientKey(recipient: Signer): FixedEphemeralSigners = FixedEphemeralSigners(recipient)
    }
}

/**
 * A one-time signer whose signers lie: each reports one public key and signs and encrypts with
 * another (see [LyingCryptoSigner]).
 *
 * A fresh pair per call, so the keys are genuinely new — the misbehaviour is the mismatch, not
 * reuse, and a test that conflated the two would not know which refusal it had proved.
 */
internal class LyingEphemeralSigners(
    private val firstStream: Long = FakeKeyRanges.LYING,
) : EphemeralSigners {

    private val issued = mutableListOf<LyingCryptoSigner>()

    /** Every signer handed out, oldest first. */
    val handedOut: List<LyingCryptoSigner> get() = issued.toList()

    override fun fresh(): SeamAnswer<Signer> {
        val reported = FakeKey(firstStream + issued.size * 2)
        val actual = FakeKey(firstStream + issued.size * 2 + 1)
        val signer = LyingCryptoSigner(reported, actual)
        issued += signer
        return SeamAnswer.Provided(signer)
    }
}

/**
 * A randomness source that returns only `0xFF` bytes.
 *
 * Not a lazy stand-in for [RecordingRandomness]: it is the boundary control for §7.1 step 5, which
 * randomises each seal's and each wrap's `created_at` uniformly into `[now − 172 800, now]` from
 * the injected source. A source that is all ones drives that arithmetic to the far end of its
 * range, which is where an off-by-one puts a timestamp in the future — the one outcome step 5
 * forbids outright.
 */
internal class AllOnesRandomness : Randomness {

    override fun randomBytes(count: Int): SeamAnswer<ByteArray> =
        SeamAnswer.Provided(ByteArray(count) { 0xFF.toByte() })
}
