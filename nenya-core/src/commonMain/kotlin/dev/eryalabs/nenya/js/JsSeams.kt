package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Nip44Decryption
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.seam.SignatureVerdict
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * What a JavaScript NIP-44 decryption answered: the plaintext, or the refusal.
 *
 * Three answers, not two, because `Nip44Decryption` has three and §17 is the reason: a payload the
 * signer *checked* and refused is not the same fact as a signer that never ran. A `null` returned
 * from [JsSigner.nip44Decrypt] is the third — the seam did not perform the operation at all — and
 * this object is the other two.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsDecryption public constructor(

    /** True when the payload authenticated; false when it was checked and did not. */
    public val ok: Boolean,

    /** What the payload carried, when [ok]. A private message: see `Nip44Decryption.Decrypted`. */
    public val plaintext: String? = null,
) {

    /** Says the case and nothing else — §12 items 2 and 11, the same rule one layer down. */
    override fun toString(): String = if (ok) "JsDecryption(ok, redacted)" else "JsDecryption(refused)"
}

/**
 * The §3 signer seam, supplied from JavaScript as four functions.
 *
 * ### Why functions and not an interface a page implements
 *
 * `Signer` is a Kotlin interface, and a JavaScript object cannot implement one: Kotlin/JS dispatches
 * interface calls through machinery a plain object does not carry. Kotlin **function types** do
 * cross — they are ordinary JavaScript functions in both directions — so the seam crosses as its
 * methods rather than as its type. The page writes `new JsSigner(() => myPubkey, …)` and the
 * adapter below is what makes that a `Signer`.
 *
 * ### `null` means unavailable, and that is the whole of the convention
 *
 * Decision **P** says `SeamAnswer` becomes `{ ok, value, capability, detail }` on the way **out**.
 * On the way **in** there is no answer to translate — the page is being asked rather than
 * answering — so the convention is the narrowest one that cannot be mistaken: a function returning
 * `null` becomes `SeamAnswer.Unavailable` for that
 * capability, with the detail this library authors. There is deliberately **no** way for a page to
 * supply its own `Unavailable` detail — §17 requires the capability statement be this library's, and
 * text an injected seam wrote is text this library cannot promise is free of the ciphertext it was
 * handed (§12 item 11).
 *
 * Nothing any of these functions returns is evidence of anything (§3, §9.1). A page that returns a
 * signature from `signEvent` has asserted a signature, and `Secp256k1Ops` is still the only thing
 * that can verify one.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsSigner public constructor(

    /** §4.1's own x-only public key, 64 hex characters, or `null` for unavailable. */
    public val publicKeyHex: () -> String?,

    /** A BIP-340 signature over the canonical serialisation handed in, 128 hex characters, or `null`. */
    public val signEvent: (String) -> String?,

    /** NIP-44 v2 encryption to the counterparty key handed in, or `null` for unavailable. */
    public val nip44Encrypt: (String, String) -> String?,

    /** NIP-44 v2 decryption, answering [JsDecryption], or `null` for unavailable. */
    public val nip44Decrypt: (String, String) -> JsDecryption?,
) {

    /** Names no function and no key (§12 item 11, STOP RULE 14). */
    override fun toString(): String = "JsSigner(redacted)"
}

/**
 * The §3 clock, randomness and secp256k1 seams, supplied from JavaScript as three functions.
 *
 * Grouped into one object because `GiftWrap.seal` takes all three and a page that supplies one
 * almost always supplies the others; each stays independently absent, because each field is
 * independently nullable and an absent one is `FAIL_CLOSED` for that seam alone.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsEnvironment public constructor(

    /** §4.6's clock: the current time in unix seconds as a decimal string, or `null`. */
    public val nowSeconds: (() -> String?)? = null,

    /** §7.4's randomness: [count] cryptographically secure bytes as lowercase hex, or `null`. */
    public val randomBytesHex: ((Int) -> String?)? = null,

    /**
     * §4.1's BIP-340 verification over `(publicKeyXOnlyHex, messageHex, signatureHex)`.
     *
     * Answers `"VALID"`, `"INVALID"`, or `null` for not performed. **`null` is not `"INVALID"`** —
     * §17 requires an implementation that omits verification to say so rather than report a negative
     * result, and `SignatureCheck` has no `INVALID` constant for exactly that reason.
     */
    public val verifySchnorr: ((String, String, String) -> String?)? = null,

    /**
     * §7.1's one-time signer: a fresh throwaway keypair per gift wrap, as its own [JsSigner].
     *
     * The capability is **minting the keypair**, never any claim that it is fresh: §3 says an
     * implementation MUST NOT accept such a claim from this seam, and nothing here reports one.
     */
    public val freshSigner: (() -> JsSigner?)? = null,
) {

    override fun toString(): String = "JsEnvironment(redacted)"
}

// =================================================================================================
// The adapters. Each wraps the crossed functions in the Kotlin seam interface, answering
// `SeamAnswer.Unavailable` with this library's own detail wherever a function is absent or returns
// null. Not exported: these are Kotlin types by construction.
// =================================================================================================

/** §4.3's lowercase hex, through the library's own encoder rather than a second one. */
private fun hex(bytes: ByteArray): String = dev.eryalabs.nenya.wire.encodeLowerHex(bytes)

/** The detail every `Unavailable` this file produces carries. Authored here; echoes no input. */
private fun crossedUnavailable(capability: SeamCapability): SeamAnswer.Unavailable =
    SeamAnswer.Unavailable(
        capability,
        "the JavaScript function supplied for this seam answered nothing, so ${capability.what} " +
            "was not performed; §17 requires this be reported as not performed rather than as a " +
            "negative result",
    )

/** A nullable crossed answer as a `SeamAnswer`, with no third case to get wrong. */
private fun <T> crossed(capability: SeamCapability, value: T?): SeamAnswer<T> =
    if (value == null) crossedUnavailable(capability) else SeamAnswer.Provided(value)

/** This [JsSigner] as the `Signer` every entry point takes. */
internal fun JsSigner.asSigner(): Signer = CrossedSigner(this)

private class CrossedSigner(private val js: JsSigner) : Signer {

    override fun publicKey(): SeamAnswer<String> =
        crossed(SeamCapability.SIGNER_PUBLIC_KEY, js.publicKeyHex())

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> =
        crossed(SeamCapability.EVENT_SIGNATURE, js.signEvent(canonicalSerialisation))

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        crossed(SeamCapability.NIP44_ENCRYPTION, js.nip44Encrypt(counterpartyPublicKeyHex, plaintext))

    override fun nip44Decrypt(
        counterpartyPublicKeyHex: String,
        payload: String,
    ): SeamAnswer<Nip44Decryption> {
        val answer = js.nip44Decrypt(counterpartyPublicKeyHex, payload)
            ?: return crossedUnavailable(SeamCapability.NIP44_DECRYPTION)
        val plaintext = answer.plaintext
        // `ok` with no plaintext is not a third case to invent a meaning for: there is nothing to
        // hand `Decrypted`, so it is the refusal NIP-44's authenticated encryption produces.
        return if (answer.ok && plaintext != null) {
            SeamAnswer.Provided(Nip44Decryption.Decrypted(plaintext))
        } else {
            SeamAnswer.Provided(Nip44Decryption.Refused)
        }
    }

    /** Never names a key or a function (§12 item 11). */
    override fun toString(): String = "CrossedSigner(redacted)"
}

/** This environment's clock, or `FAIL_CLOSED` when the page supplied none. */
internal fun JsEnvironment.asClock(): NenyaClock {
    val now = nowSeconds ?: return NenyaClock.FAIL_CLOSED
    return object : NenyaClock {
        override fun now(): SeamAnswer<Long> {
            val text = now() ?: return crossedUnavailable(SeamCapability.CLOCK_READING)
            // A clock reading that is not a decimal string is not a time. Reported as unavailable
            // rather than guessed at: §4.6 makes this clock authoritative for every deadline, and a
            // deadline computed from a guess is worse than one that refuses to be computed.
            val seconds = JsDecimal.toLongOrNull(text)
                ?: return crossedUnavailable(SeamCapability.CLOCK_READING)
            return SeamAnswer.Provided(seconds)
        }
    }
}

/** This environment's randomness, or `FAIL_CLOSED`. */
internal fun JsEnvironment.asRandomness(): Randomness {
    val bytes = randomBytesHex ?: return Randomness.FAIL_CLOSED
    return object : Randomness {
        override fun randomBytes(count: Int): SeamAnswer<ByteArray> {
            val hex = bytes(count) ?: return crossedUnavailable(SeamCapability.RANDOM_BYTES)
            val decoded = JsHex.toBytesOrNull(hex)
                ?: return crossedUnavailable(SeamCapability.RANDOM_BYTES)
            // A short answer is not padded and a long one is not truncated: §7.4's order id needs
            // 32 bytes of entropy and silently supplying fewer is the failure the seam exists to
            // make visible.
            if (decoded.size != count) return crossedUnavailable(SeamCapability.RANDOM_BYTES)
            return SeamAnswer.Provided(decoded)
        }
    }
}

/** This environment's secp256k1 seam, or `FAIL_CLOSED`. */
internal fun JsEnvironment.asSecp(): Secp256k1Ops {
    val verify = verifySchnorr ?: return Secp256k1Ops.FAIL_CLOSED
    return object : Secp256k1Ops {
        override fun verifySchnorr(
            publicKeyXOnly: ByteArray,
            message: ByteArray,
            signature: ByteArray,
        ): SeamAnswer<SignatureVerdict> {
            val answer = verify(
                hex(publicKeyXOnly),
                hex(message),
                hex(signature),
            ) ?: return crossedUnavailable(SeamCapability.BIP340_VERIFICATION)
            // Only the two constants `SignatureVerdict` names. Anything else is a seam this library
            // does not understand, and §17 forbids turning that into a verdict — in either
            // direction: reading an unknown string as INVALID would report a signature as refused
            // that nobody checked.
            val verdict = SignatureVerdict.entries.firstOrNull { it.name == answer }
                ?: return crossedUnavailable(SeamCapability.BIP340_VERIFICATION)
            return SeamAnswer.Provided(verdict)
        }
    }
}

/** This environment's one-time signer, or `FAIL_CLOSED`. */
internal fun JsEnvironment.asEphemeralSigners(): EphemeralSigners {
    val fresh = freshSigner ?: return EphemeralSigners.FAIL_CLOSED
    return object : EphemeralSigners {
        override fun fresh(): SeamAnswer<Signer> {
            val js = fresh() ?: return crossedUnavailable(SeamCapability.EPHEMERAL_SIGNER)
            return SeamAnswer.Provided(js.asSigner())
        }
    }
}
