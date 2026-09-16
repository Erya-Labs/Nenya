package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.money.Msat

/**
 * The fakes behind every seam test in this package, and the generator behind every value they
 * hand out.
 *
 * These are not scratch files. §3 says a Nenya implementation obtains the signer, the relay
 * transport, the wallet, the clock and the randomness from the client embedding it and MUST
 * treat all of them as untrusted with respect to state — and a rule about untrusted components
 * is only testable if there is a component here to be untrusted by. [LyingWallet] in particular
 * is the executable form of §9.1 and of this repository's stop rule against believing an
 * injected interface's assertion; without it that rule is a paragraph.
 *
 * Every fixture is **computed**, never typed. The queue's Definition of done forbids a hash, a
 * key, a signature or any other encoded value appearing in a test as a literal somebody wrote
 * out, so the bytes here come from `java.util.Random`'s algorithm ([JdkRandom]) pinned to
 * [SeamFixtures.SEED] and the
 * hex from [SeamFixtures.lowerHex]. A reviewer can change the seed, re-run the suite, and every
 * property must still hold.
 */
internal object SeamFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom]) rather
     * than `kotlin.random` because it is specified by the JDK, so this file produces the same runs
     * on any JVM a reviewer re-runs it on, and on JavaScript. It lives in the **test** tree; the ambient-effects sweep
     * forbids either of them under every production source root.
     */
    const val SEED: Long = 20260910L

    /**
     * A deliberately non-BOLT-11 placeholder, used wherever a seam takes an invoice.
     *
     * The queue forbids a hand-typed bech32 or BOLT-11 string anywhere in this repository —
     * three reviews of an earlier draft caught fabricated ones — and nothing in this task parses
     * an invoice, so the honest placeholder is a string that could not be mistaken for one.
     */
    const val NOT_AN_INVOICE: String = "not-a-bolt11-invoice: no encoded value may be typed here"

    /** §4.3's canonical form: lowercase, unpadded. */
    fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    /** [count] bytes from the pinned generator, offset by [stream] so two runs differ. */
    fun bytes(count: Int, stream: Long = 0L): ByteArray {
        val out = ByteArray(count)
        JdkRandom(SEED + stream).nextBytes(out)
        return out
    }
}

/**
 * The value a seam provided, or a loud failure.
 *
 * A test that quietly treated [SeamAnswer.Unavailable] as "no value here" would turn the
 * fail-closed default into a silent pass, which is the one thing this package's tests must never
 * do.
 */
internal fun <T> SeamAnswer<T>.provided(): T = when (this) {
    is SeamAnswer.Provided -> value
    is SeamAnswer.Unavailable -> kotlin.test.fail("expected a provided answer, got $this")
}

/** The refusal a seam answered with, or a loud failure. */
internal fun SeamAnswer<*>.unavailable(): SeamAnswer.Unavailable = when (this) {
    is SeamAnswer.Unavailable -> this
    is SeamAnswer.Provided -> kotlin.test.fail(
        "expected an explicit unavailable — the fail-closed answer §17 requires — but the seam " +
            "answered $this",
    )
}

/**
 * A wallet that reports every payment settled, every invoice paid and every balance sufficient
 * — and yields no `dev.eryalabs.nenya.payment.VerifiedPayment` at all.
 *
 * §9.1 and this repository's stop rule 12 say the same thing in different words: a `Boolean
 * isPaid` from a wallet, a status string from a counterparty and a relay's acceptance of an
 * event are none of them evidence, and the only acceptable proof is a preimage whose SHA-256
 * this library computes itself. This class is that rule with a subject.
 *
 * It lies in the most dangerous way available to it: it does not merely *say* the payment
 * settled, it hands over 32 well-formed bytes and calls them the preimage. That is the shape a
 * compromised or merely buggy wallet actually takes — the naive integration reads the claim,
 * sees a preimage-shaped value, and advances the order. `VerifiedPayment.verify` hashes the
 * bytes and refuses.
 *
 * @param fabricatedPreimageHex 32 bytes in lowercase hex that are **not** the preimage of the
 *   payment hash under test. Passed in rather than generated here so the test controls the
 *   relationship it is asserting about.
 */
internal class LyingWallet(private val fabricatedPreimageHex: String) : Wallet {

    /** How many times the library asked it anything. Nothing here should move an order. */
    var calls: Int = 0
        private set

    private fun claim(): SeamAnswer<WalletPaymentClaim> {
        calls++
        return SeamAnswer.Provided(
            WalletPaymentClaim(WalletPaymentState.CLAIMS_SETTLED, fabricatedPreimageHex),
        )
    }

    override fun payInvoice(invoice: String): SeamAnswer<WalletPaymentClaim> = claim()

    override fun paymentStatus(invoice: String): SeamAnswer<WalletPaymentClaim> = claim()

    /** Every balance is sufficient, up to §4.4's entire supply cap. */
    override fun spendableBalance(): SeamAnswer<Msat> {
        calls++
        return SeamAnswer.Provided(Msat.SUPPLY_CAP)
    }

    override fun issueInvoice(amount: Msat): SeamAnswer<String> {
        calls++
        return SeamAnswer.Provided(SeamFixtures.NOT_AN_INVOICE)
    }
}

/**
 * A clock that reports exactly what it was built with, however implausible.
 *
 * §4.6 makes the injected clock authoritative for every deadline, so this library has no opinion
 * about what it says: a fake reporting 1970 and one reporting the year 3000 are both simply
 * used. The controls in `OrderIdTest` assert precisely that.
 */
internal class FakeClock(private val unixSeconds: Long) : NenyaClock {

    override fun now(): SeamAnswer<Long> = SeamAnswer.Provided(unixSeconds)
}

/**
 * A randomness source that draws from the pinned generator and records what it was asked for.
 *
 * The recording is the point: §7.4 says the order id is 32 bytes from a cryptographically secure
 * source, and [requested] is how a test proves the minting function drew exactly that many, in
 * one call, rather than stretching or re-drawing.
 */
internal class RecordingRandomness(seed: Long = SeamFixtures.SEED) : Randomness {

    private val random = JdkRandom(seed)
    private val counts = mutableListOf<Int>()

    /** One entry per call, holding the byte count asked for. */
    val requested: List<Int> get() = counts.toList()

    override fun randomBytes(count: Int): SeamAnswer<ByteArray> {
        counts += count
        val out = ByteArray(count)
        random.nextBytes(out)
        return SeamAnswer.Provided(out)
    }
}

/**
 * A randomness source that answers with the wrong number of bytes.
 *
 * §7.4's 32 bytes are not negotiable and §4.3 forbids padding or truncating a value of the wrong
 * length, so both directions are refused. A source returning 31 bytes is the interesting one: an
 * implementation that quietly padded would mint an order id with a known zero byte in it.
 */
internal class WrongLengthRandomness(private val size: Int) : Randomness {

    override fun randomBytes(count: Int): SeamAnswer<ByteArray> = SeamAnswer.Provided(ByteArray(size))
}

/**
 * A randomness source that hands back exactly the array it was built with — **without copying**.
 *
 * The absence of a copy is the point. A real source hands over an array it may still hold a
 * reference to, so a value type that did not copy on the way in could be rewritten after it was
 * read. `OrderIdTest` mutates the array afterwards and asserts the minted id did not move.
 */
internal class FixedRandomness(private val value: ByteArray) : Randomness {

    override fun randomBytes(count: Int): SeamAnswer<ByteArray> = SeamAnswer.Provided(value)
}

/**
 * A signer that answers everything, so the fail-closed sweep is measured against a seam that
 * *could* have answered. Performs no cryptography: the strings are generated bytes in hex and
 * the "encryption" is the identity, which is why nothing in the suite asserts anything about
 * them beyond their presence.
 */
internal class FakeSigner : Signer {

    override fun publicKey(): SeamAnswer<String> =
        SeamAnswer.Provided(SeamFixtures.lowerHex(SeamFixtures.bytes(32, stream = 7L)))

    override fun signEvent(canonicalSerialisation: String): SeamAnswer<String> =
        SeamAnswer.Provided(SeamFixtures.lowerHex(SeamFixtures.bytes(64, stream = 8L)))

    override fun nip44Encrypt(counterpartyPublicKeyHex: String, plaintext: String): SeamAnswer<String> =
        SeamAnswer.Provided(plaintext)

    override fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<String> =
        SeamAnswer.Provided(payload)
}

/**
 * A relay transport that accepts everything and hands back what it was given. Opens no socket —
 * nothing in this repository may, and a test that would is wrong even when it passes.
 */
internal class FakeRelayTransport : RelayTransport {

    private val published = mutableListOf<String>()

    override fun publish(serialisedEvent: String): SeamAnswer<RelayAcknowledgement> {
        published += serialisedEvent
        return SeamAnswer.Provided(RelayAcknowledgement.CLAIMS_ACCEPTED)
    }

    override fun request(serialisedFilter: String): SeamAnswer<List<String>> =
        SeamAnswer.Provided(published.toList())
}

/**
 * A secp256k1 seam that claims every signature valid.
 *
 * It performs no cryptography and verifies nothing — it exists to prove the *type* can express
 * [SignatureVerdict.VALID]. Without it, "the default never answers VALID for the BIP-340 valid
 * rows" would be satisfied by a return type that could not say VALID at all, which is the
 * vacuous version of that assertion.
 */
internal class OptimisticSecp256k1Ops : Secp256k1Ops {

    override fun verifySchnorr(
        publicKeyXOnly: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): SeamAnswer<SignatureVerdict> = SeamAnswer.Provided(SignatureVerdict.VALID)
}
