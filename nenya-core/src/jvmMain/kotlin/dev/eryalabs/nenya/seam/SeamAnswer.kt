package dev.eryalabs.nenya.seam

/**
 * Which of §3's capabilities an answer is about, named so that "this library did not do that"
 * is machine-readable rather than a paragraph in a README.
 *
 * §17 is explicit that an implementation MAY omit BIP-340 signature verification and BOLT-11
 * node-key recovery and still be conformant, **provided it does not report unverified things as
 * verified**, and that such an implementation MUST expose a capability surface. This enum is one
 * half of that surface; `PaymentCheck` and `DeliveryCheck` are the other two, and all three are
 * read by the same caller.
 *
 * A constant here says what the *capability* is, never what the answer was. "Unavailable" plus a
 * capability is a complete, actionable statement; a bare `false` is not, which is the whole
 * reason [SeamAnswer] has three shapes rather than two.
 */
public enum class SeamCapability(

    /** A one-line description of the capability, for a diagnostic that carries no secret. */
    public val what: String,
) {

    /** The user's own x-only public key, 32 bytes (§3, §4.1). */
    SIGNER_PUBLIC_KEY("the user's own x-only public key"),

    /** A BIP-340 signature over a NIP-01 canonical serialisation (§3, §4.1). */
    EVENT_SIGNATURE("a signature over a canonical event serialisation"),

    /** NIP-44 v2 encryption to a counterparty, for the seal and the gift wrap (§3, §7.1). */
    NIP44_ENCRYPTION("NIP-44 v2 encryption to a counterparty"),

    /** NIP-44 v2 decryption of a seal or a gift wrap (§3, §7.1). */
    NIP44_DECRYPTION("NIP-44 v2 decryption of a seal or a gift wrap"),

    /** Handing an event to a relay (§3). The relay's answer is a claim, never a fact. */
    RELAY_PUBLISH("publishing an event to a relay"),

    /** Asking a relay for events (§3). What comes back is hostile input. */
    RELAY_REQUEST("asking a relay for events"),

    /** Paying a BOLT-11 invoice from the user's own wallet (§3, §8.6). */
    WALLET_PAYMENT("paying a BOLT-11 invoice"),

    /** What the wallet *believes* about an invoice. §9.1: not evidence of anything. */
    WALLET_PAYMENT_STATUS("what the wallet believes about an invoice"),

    /** What the wallet *believes* it can spend. Also not evidence of anything. */
    WALLET_BALANCE("what the wallet believes it can spend"),

    /** An invoice for the user's own funds, as a payee (§3, §8.6). */
    WALLET_INVOICE_ISSUANCE("an invoice for the user's own funds"),

    /** The current time. §4.6 makes this clock authoritative for every deadline. */
    CLOCK_READING("the current time"),

    /** Cryptographically secure random bytes — §7.4's requirement for the order id. */
    RANDOM_BYTES("cryptographically secure random bytes"),

    /**
     * BIP-340 Schnorr verification over secp256k1 (§4.1, §7.2, §8.7).
     *
     * This is the capability §17's escape clause is written for, and the one this library does
     * not have: the JDK ships no secp256k1 and no dependency may be added. Every default answer
     * for it is [SeamAnswer.Unavailable] — never `false`, which a caller cannot tell apart from
     * "checked, and the signature is invalid".
     */
    BIP340_VERIFICATION("BIP-340 Schnorr signature verification over secp256k1"),
}

/**
 * What a seam answered: a value, or an explicit **unavailable** — never a bare `false`.
 *
 * ### Why three answers and not two
 *
 * VISION requires that an operation this library cannot perform "returns false or an explicit
 * *unavailable*, never true", and permits either. This package takes the stricter of the two
 * uniformly, and that is **this library's own design choice rather than a rule imposed on it**:
 * §17 requires an implementation that omits BIP-340 verification to publish a machine-readable
 * statement of what it actually verifies, and `false` cannot be that statement, because it is
 * indistinguishable from "checked, and invalid". A UI told `false` says "invalid signature"; a
 * UI told [Unavailable] says "not checked", which is the distinction §17's last paragraph
 * requires it to draw.
 *
 * So a verification seam has three answers — verified, refuted, not performed — and the third
 * is this type rather than a magic value inside the second.
 *
 * ### Nothing in here is evidence
 *
 * A [Provided] carries whatever the embedding client's implementation said. §3 says all four
 * seams are untrusted with respect to state and §9.1 says a wallet's claim of settlement is not
 * evidence of payment. This type deliberately makes that *visible* rather than preventing it:
 * the seams model the assertions a real wallet, signer and relay genuinely make, so that the
 * rule against believing them has a subject. The test suite's `LyingWallet` is what makes the
 * rule executable.
 */
public sealed interface SeamAnswer<out T> {

    /**
     * The seam answered, and here is what it said.
     *
     * ### The value is never printed
     *
     * [toString] names the *type* of the value and never the value. §12 item 11 forbids key
     * material, decryption keys, preimages and order ids from appearing in a log, a crash
     * report or the string representation of anything this library exposes, and a generic
     * wrapper cannot know which of those it is holding — a `Provided` returned by
     * [Signer.nip44Decrypt] is a decrypted private message and a `Provided` returned by
     * [Wallet.payInvoice] carries a claimed preimage. Redacting unconditionally is the only
     * form of that rule a generic type can enforce. A caller that genuinely needs the value
     * reads [value], which is a thing it had to ask for.
     */
    public class Provided<out T>(

        /** What the seam said. A claim, not a fact — see this file's note. */
        public val value: T,
    ) : SeamAnswer<T> {

        override fun toString(): String =
            "SeamAnswer.Provided(${value?.let { it::class.java.simpleName } ?: "null"}, redacted)"
    }

    /**
     * The seam did not perform the operation. **Not `false`, and not an error.**
     *
     * Every default implementation in this package answers this, for every method it declares.
     * A client that injects nothing therefore gets a library that verifies nothing and *says*
     * so, which is §17's capability surface at its narrowest rather than a library that
     * silently reports plausible answers.
     */
    public class Unavailable(

        /** Which of §3's capabilities was asked for. */
        public val capability: SeamCapability,

        /**
         * Why it was not available, in text authored in this library. Never echoes an input,
         * so it cannot carry a key, a preimage, an order id or an invoice into a log
         * (§12 item 11).
         */
        public val detail: String,
    ) : SeamAnswer<Nothing> {

        override fun toString(): String = "SeamAnswer.Unavailable(${capability.name}: $detail)"
    }

    public companion object {

        /**
         * The fail-closed answer every default in this package returns, phrased once.
         *
         * @param capability which of §3's capabilities the caller asked for.
         */
        internal fun unavailable(capability: SeamCapability): Unavailable = Unavailable(
            capability,
            "no implementation of this seam was injected, so ${capability.what} was not attempted; " +
                "§17 requires this be reported as not performed rather than as a negative result",
        )
    }
}
