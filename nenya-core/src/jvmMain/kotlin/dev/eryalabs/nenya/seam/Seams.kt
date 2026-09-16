package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.money.Msat

/**
 * The marker every one of §3's seams carries.
 *
 * §3 names four things a Nenya implementation obtains from the client that embeds it — signer,
 * relay transport, wallet, clock and randomness — and says all of them MUST be treated as
 * untrusted with respect to state. [Secp256k1Ops] is a fifth, split out of the signer because
 * §17 lets an implementation omit BIP-340 verification while still signing through a NIP-55
 * signer app, and the two therefore fail independently.
 *
 * ### Sealed on purpose
 *
 * §3's list is closed. A client implements [Signer] or [Wallet]; it does not invent a sixth seam
 * and expect this library to consult it. Sealing `Seam` says that in the type system, and gives
 * the test suite something to enumerate: the fail-closed sweep discovers every seam by asking
 * which interfaces in this package extend this one, so a seam added later without a fail-closed
 * default turns the suite red instead of quietly defaulting to something friendlier.
 *
 * ### Two constraints on every method below, and they are what make the sweep possible
 *
 * Both are constraints on this package's own API surface, which is why they are affordable:
 *
 * - **Every seam method returns a value. None returns `Unit`.** A `Unit` method can only fail
 *   closed by throwing, and a reflective sweep cannot tell a deliberate throw from a bug. So
 *   "publish this event" returns the relay's acknowledgement rather than nothing.
 * - **No seam method takes a parameter a test cannot construct from the JDK and this library's
 *   own types.** The sweep synthesises an argument for every declared parameter and invokes the
 *   default, and it **fails** on a parameter it cannot construct rather than skipping the
 *   method — a sweep that quietly skips what it could not build satisfies the letter of
 *   "enumerated by reflection" while defeating the whole point of it.
 */
public sealed interface Seam

/**
 * The signer seam (§3): the user's public key, event signatures, NIP-44 encrypt and decrypt.
 *
 * §3 says signing is deliberately abstract — an in-process key, a NIP-55 Android signer app or a
 * remote signer are all conformant, and nothing in NENYA-1 depends on where the secret lives.
 * That is why no method here takes or returns a secret key: this interface is the boundary
 * secret key material MUST NOT cross (§12 item 11, and the same rule in this repository's stop
 * rules).
 *
 * §3 also fixes what MUST NOT be accepted from it: **any claim about *what* was signed, beyond
 * the bytes returned.** A signature is bytes; that those bytes attest to a particular event is
 * something the caller establishes by re-serialising the event itself (§4.1), never by asking.
 *
 * Nothing in this library performs, or pretends to perform, a signature check, an encryption or
 * a decryption. These are the seams to build up to and stop at until the cryptography dependency
 * a human must sign off arrives; the defaults below answer [SeamAnswer.Unavailable].
 */
public interface Signer : Seam {

    /** The user's own x-only public key as 64 lowercase hex characters (§4.3). */
    public fun publicKey(): SeamAnswer<String>

    /**
     * A BIP-340 signature over [canonicalSerialisation], as 128 lowercase hex characters.
     *
     * @param canonicalSerialisation the NIP-01 canonical serialisation of §4.1 — the bytes
     *   whose SHA-256 is the event id. Passed rather than an event object because this library
     *   has no event type yet, and passing the exact bytes is what makes §3's "no claim about
     *   what was signed" enforceable by the caller.
     */
    public fun signEvent(canonicalSerialisation: String): SeamAnswer<String>

    /**
     * NIP-44 v2 encryption of [plaintext] to [counterpartyPublicKeyHex], returning the payload
     * NIP-44 defines (§7.1's seal and gift wrap both use it).
     */
    public fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String>

    /**
     * NIP-44 v2 decryption of [payload] from [counterpartyPublicKeyHex].
     *
     * A decrypted rumor is structurally valid at best. §7.2 is the rule that makes a sealed term
     * binding — the seal's pubkey and the rumor's pubkey MUST be equal — and this seam cannot
     * perform it, because it has no opinion about who sealed what.
     */
    public fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String>

    public companion object {

        /** The fail-closed default: signs nothing, decrypts nothing, says so. */
        public val FAIL_CLOSED: Signer = FailClosedSigner
    }
}

/**
 * What a relay said about an event it was handed. **Neither constant is evidence of anything.**
 *
 * §3: an implementation MUST NOT accept from the relay transport "any claim that an event is
 * valid, current, or complete". A relay that says `OK` may have dropped the event a millisecond
 * later; a relay that says it rejected the event may have stored it. This enum exists so that a
 * caller reading it has to read the word "claims" while doing so.
 */
public enum class RelayAcknowledgement {

    /** The relay said it accepted the event. It might be lying, or wrong, or gone. */
    CLAIMS_ACCEPTED,

    /** The relay said it refused the event. Also a claim. */
    CLAIMS_REJECTED,
}

/**
 * The relay transport seam (§3): events out, events in.
 *
 * Deals in serialised events rather than parsed ones, because this library has no event codec
 * yet — the encoding round builds it. That is a narrowing stated rather than papered over, and
 * it has one useful side effect: a transport that cannot parse cannot *interpret*, so §3's rule
 * against accepting a relay's claim that an event is valid has nothing to attach itself to.
 *
 * Everything that comes back through this seam is hostile input (§4.3), including events the
 * implementation believes it authored.
 *
 * **Nothing in this repository opens a socket.** The default below answers
 * [SeamAnswer.Unavailable]; a real transport is the embedding client's, and a test's is a fake.
 */
public interface RelayTransport : Seam {

    /**
     * Hand a serialised event to the relays, returning what they claimed about it.
     *
     * @param serialisedEvent the JSON of a signed nostr event.
     */
    public fun publish(serialisedEvent: String): SeamAnswer<RelayAcknowledgement>

    /**
     * Ask for events matching a serialised NIP-01 filter, returning the serialised events.
     *
     * The list is not a claim of completeness, and §4.6 forbids ordering an order thread by the
     * `created_at` of what comes back: an implementation MUST order by its own receipt sequence.
     *
     * @param serialisedFilter the JSON of a NIP-01 filter.
     */
    public fun request(serialisedFilter: String): SeamAnswer<List<String>>

    public companion object {

        /** The fail-closed default: publishes nothing, fetches nothing, opens no socket. */
        public val FAIL_CLOSED: RelayTransport = FailClosedRelayTransport
    }
}

/**
 * What a wallet *claims* about a payment. §9.1: none of it is evidence.
 *
 * Every constant is named `CLAIMS_` on purpose. This is the exact value a careless
 * implementation branches on to advance an order to `paid`, and §9.2 is the rule that says it
 * MUST NOT: the only acceptable proof is a preimage whose SHA-256 this library computes itself
 * and compares to the invoice's payment hash.
 */
public enum class WalletPaymentState {

    /** The wallet says the payment settled. It may be lying, confused, or compromised. */
    CLAIMS_SETTLED,

    /** The wallet says the payment is in flight. */
    CLAIMS_PENDING,

    /** The wallet says the payment failed. Also only a claim — a failed HTLC can still settle. */
    CLAIMS_FAILED,

    /** The wallet has nothing to say about this invoice. */
    UNKNOWN,
}

/**
 * A wallet's account of one payment: what it claims happened, and the preimage it claims to
 * have received.
 *
 * ### The preimage is *claimed*, and that word is the whole design
 *
 * A real Lightning wallet does return the preimage on settlement, and that preimage is genuine
 * evidence — but only once **this library** has hashed it and compared the digest to the
 * invoice's payment hash (§9.2 check 3). Until then it is 32 bytes a component chose. So this
 * type carries hex and stops there: it has no method that turns itself into a
 * `dev.eryalabs.nenya.payment.VerifiedPayment`, and there is no path from this package to that
 * one. `VerifiedPayment.verify` takes a `Preimage` and a `PaymentHash` and nothing else, which
 * is why a wallet that lies produces no evidence however loudly it lies.
 */
public class WalletPaymentClaim(

    /** What the wallet says the payment did. */
    public val state: WalletPaymentState,

    /**
     * The preimage the wallet claims it received, as hex, or `null` if it offered none.
     *
     * §9.2 check 2 requires lowercase hex decoding to exactly 32 bytes, and this field is
     * deliberately **not** validated here: validating it in the seam package would let a caller
     * mistake "the wallet gave me something well-formed" for "the payment is evidenced". The
     * only reader of this value should be `Preimage.ofHex`, whose result is the only thing
     * `VerifiedPayment.verify` accepts.
     */
    public val claimedPreimageHex: String?,
) {

    /**
     * Names the state and whether a preimage was offered, never the preimage itself.
     * §12 item 11 lists preimages alongside key material and order ids.
     */
    override fun toString(): String =
        "WalletPaymentClaim(state=$state, claimedPreimage=${if (claimedPreimageHex == null) "absent" else "redacted"})"
}

/**
 * The wallet seam (§3): pay this invoice, issue invoices for the user's own funds.
 *
 * §3's rule for this seam is the sharpest of the four — **MUST NOT accept any claim that a
 * payment succeeded** — and §9.1 spells out why: a wallet is software, and the party that
 * benefits from a false "settled" is not always the party that wrote it. Every method here
 * therefore returns something with `CLAIMS` in its name, and nothing in this library reads one
 * of them to advance an order.
 *
 * The executable form of that rule is the test suite's `LyingWallet`, which reports every
 * payment settled, every invoice paid and every balance sufficient, and yields no
 * `dev.eryalabs.nenya.payment.VerifiedPayment` at all.
 */
public interface Wallet : Seam {

    /**
     * Pay a BOLT-11 invoice.
     *
     * @param invoice the BOLT-11 string, opaque to this library — there is no parser yet, so it
     *   is neither validated nor interpreted here (§9.2 checks 1, 4 and 5 are the encoding
     *   round's). §12 items 1 and 2 apply to it: an invoice string MUST NOT reach a public
     *   event, and no order id, coordinate or counterparty pubkey may have reached its
     *   description.
     */
    public fun payInvoice(invoice: String): SeamAnswer<WalletPaymentClaim>

    /** What the wallet believes about an invoice it was asked to pay. Never evidence (§9.1). */
    public fun paymentStatus(invoice: String): SeamAnswer<WalletPaymentClaim>

    /** What the wallet believes it can spend. Also never evidence of anything. */
    public fun spendableBalance(): SeamAnswer<Msat>

    /**
     * An invoice for the user's own funds, for exactly [amount].
     *
     * §8.6's non-custodial rule: the provider and the fee recipient issue **two** invoices for
     * their own amounts. A single invoice covering `total_msat` that some party then splits is
     * custody, and NENYA-1 has no version of that design.
     */
    public fun issueInvoice(amount: Msat): SeamAnswer<String>

    public companion object {

        /** The fail-closed default: pays nothing, claims nothing, issues nothing. */
        public val FAIL_CLOSED: Wallet = FailClosedWallet
    }
}

/**
 * The clock seam (§4.6): the only time this library is allowed to know.
 *
 * §4.6 is unambiguous — every deadline in NENYA-1 (a listing's `expiration`, a bid's, an order
 * proposal's, `deliver_by`, a BOLT-11 invoice's expiry) MUST be evaluated against this clock and
 * never against a counterparty's `created_at`, because gift-wrap timestamps are deliberately
 * randomised into the past (§7.1) and a `created_at` is a claim in any case.
 *
 * ### This library has no opinion about what the clock says
 *
 * A clock reporting 1970 and a clock reporting 3000 are both simply *used*. §4.6 makes the
 * injected clock authoritative; a library that second-guessed it would be substituting an
 * ambient time it is not allowed to read. The one tolerance §4.6 does grant — MAY reject a
 * *public* event whose `created_at` is implausibly far in the future — is a rule about an
 * event, not about the clock, and MUST NOT be applied to a gift wrap or a seal.
 *
 * ### Named `NenyaClock` rather than `Clock`
 *
 * The JDK's `Clock` would be the obvious JVM type, and a default built from it — `Clock.systemUTC()`
 * — is a live ambient effect that every injected-fake test would step straight over. The suite
 * sweeps `src/jvmMain/kotlin` for exactly that token, among others, and a distinct name keeps the
 * two from being confused at a call site.
 */
public interface NenyaClock : Seam {

    /**
     * The current time, as the embedding client understands it, in **unix seconds** — the unit
     * of nostr's `created_at` and of every §4.3 timestamp this reading is compared against.
     *
     * Any `Long` is used as given, including a negative one (a reading before 1970) and
     * [Long.MAX_VALUE]; the deadline arithmetic that consumes it is overflow-checked rather than
     * relying on the clock to stay in a plausible range.
     */
    public fun now(): SeamAnswer<Long>

    public companion object {

        /**
         * The fail-closed default: reports no time at all.
         *
         * Deliberately not "the system clock". A library that read the system clock by default
         * would evaluate deadlines against something the client never chose and no test could
         * pin, and §4.6's whole point is that every deadline rule is testable offline.
         */
        public val FAIL_CLOSED: NenyaClock = FailClosedClock
    }
}

/**
 * The randomness seam (§3, §7.4).
 *
 * §7.4 requires the order id be 32 bytes from a **cryptographically secure** random source. This
 * library cannot enforce that property of an injected source — no API can — so it does the two
 * things it can: it takes the source by injection, so a test can pin it and a client can supply
 * a real one; and it says here, in the one place a client implementing this interface will read,
 * that the right implementation wraps `java.security.SecureRandom` and the wrong one wraps
 * `java.util.Random`.
 *
 * The suite sweeps `src/jvmMain/kotlin` for ambient randomness, so no code in this library can
 * quietly construct either.
 */
public interface Randomness : Seam {

    /**
     * Exactly [count] bytes from a cryptographically secure source.
     *
     * A source that returns a different number of bytes is refused by its caller rather than
     * padded or truncated — see [OrderId.mint], and §4.3's general rule about values of the
     * wrong length.
     */
    public fun randomBytes(count: Int): SeamAnswer<ByteArray>

    public companion object {

        /** The fail-closed default: produces no randomness, so no order id can be minted. */
        public val FAIL_CLOSED: Randomness = FailClosedRandomness
    }
}

/**
 * The answer to "does this signature verify?", when the question was actually asked.
 *
 * Two constants, because the third answer — *not checked* — is [SeamAnswer.Unavailable] and not
 * a constant here. That split is the point: §17's last paragraph requires an implementation's UI
 * to distinguish "signature verified" from "decrypted and structurally valid", and it cannot if
 * the type it is handed cannot express the difference.
 */
public enum class SignatureVerdict {

    /** The signature verified against the key and message supplied. */
    VALID,

    /** The signature was checked and does not verify. Not the same as "not checked". */
    INVALID,
}

/**
 * The secp256k1 seam (§4.1, §7.2, §8.7) — BIP-340 Schnorr verification, and nothing else.
 *
 * ### Why this is separate from [Signer], and why it has no signing method
 *
 * Signing goes through [Signer], which may be a remote or a NIP-55 signer app and never exposes
 * a secret key. Verification is a pure function of public data, so it is its own seam: §17 lets
 * an implementation omit BIP-340 verification and remain conformant while still signing
 * perfectly well, so the two capabilities fail independently and must be injectable
 * independently. There is deliberately no `sign(secretKey, ...)` here — a method taking a secret
 * key would put key material on this library's published surface, which §3 and §12 item 11 exist
 * to prevent.
 *
 * ### The default answers `Unavailable`, and the vectors prove it
 *
 * The JDK ships no secp256k1 and this project has no cryptography dependency, so this library
 * verifies no signature at all. The suite feeds the **valid** rows of the vendored BIP-340
 * vectors to the default and asserts it answers [SeamAnswer.Unavailable] for every one — never
 * [SignatureVerdict.VALID], which would be a lie — and feeds it the invalid rows and asserts the
 * same, so the fail-closed answer is uniform rather than accidentally correct on one side.
 */
public interface Secp256k1Ops : Seam {

    /**
     * BIP-340 Schnorr verification.
     *
     * @param publicKeyXOnly the 32-byte x-only public key.
     * @param message the message, of any length — BIP-340 is defined over arbitrary-length
     *   messages and the vendored vectors exercise 0, 1, 17 and 100 bytes alongside the usual 32.
     * @param signature the 64-byte signature.
     */
    public fun verifySchnorr(
        publicKeyXOnly: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): SeamAnswer<SignatureVerdict>

    public companion object {

        /** The fail-closed default: verifies nothing, and never answers [SignatureVerdict]. */
        public val FAIL_CLOSED: Secp256k1Ops = FailClosedSecp256k1Ops
    }
}

// ---------------------------------------------------------------------------------------------
// The fail-closed defaults.
//
// One private object per seam, each answering SeamAnswer.Unavailable for every method it
// declares, with the capability that was asked for. Private rather than public because a client
// that wants "verify nothing" already has it as the default and does not need a name for it;
// the test suite reaches them through each companion's FAIL_CLOSED.
// ---------------------------------------------------------------------------------------------

private object FailClosedSigner : Signer {

    override fun publicKey(): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.SIGNER_PUBLIC_KEY)

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.EVENT_SIGNATURE)

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.NIP44_ENCRYPTION)

    override fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.NIP44_DECRYPTION)

    override fun toString(): String = "Signer.FAIL_CLOSED"
}

private object FailClosedRelayTransport : RelayTransport {

    override fun publish(serialisedEvent: String): SeamAnswer<RelayAcknowledgement> =
        SeamAnswer.unavailable(SeamCapability.RELAY_PUBLISH)

    override fun request(serialisedFilter: String): SeamAnswer<List<String>> =
        SeamAnswer.unavailable(SeamCapability.RELAY_REQUEST)

    override fun toString(): String = "RelayTransport.FAIL_CLOSED"
}

private object FailClosedWallet : Wallet {

    override fun payInvoice(invoice: String): SeamAnswer<WalletPaymentClaim> =
        SeamAnswer.unavailable(SeamCapability.WALLET_PAYMENT)

    override fun paymentStatus(invoice: String): SeamAnswer<WalletPaymentClaim> =
        SeamAnswer.unavailable(SeamCapability.WALLET_PAYMENT_STATUS)

    override fun spendableBalance(): SeamAnswer<Msat> =
        SeamAnswer.unavailable(SeamCapability.WALLET_BALANCE)

    override fun issueInvoice(amount: Msat): SeamAnswer<String> =
        SeamAnswer.unavailable(SeamCapability.WALLET_INVOICE_ISSUANCE)

    override fun toString(): String = "Wallet.FAIL_CLOSED"
}

private object FailClosedClock : NenyaClock {

    override fun now(): SeamAnswer<Long> =
        SeamAnswer.unavailable(SeamCapability.CLOCK_READING)

    override fun toString(): String = "NenyaClock.FAIL_CLOSED"
}

private object FailClosedRandomness : Randomness {

    override fun randomBytes(count: Int): SeamAnswer<ByteArray> =
        SeamAnswer.unavailable(SeamCapability.RANDOM_BYTES)

    override fun toString(): String = "Randomness.FAIL_CLOSED"
}

private object FailClosedSecp256k1Ops : Secp256k1Ops {

    override fun verifySchnorr(
        publicKeyXOnly: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): SeamAnswer<SignatureVerdict> = SeamAnswer.unavailable(SeamCapability.BIP340_VERIFICATION)

    override fun toString(): String = "Secp256k1Ops.FAIL_CLOSED"
}
