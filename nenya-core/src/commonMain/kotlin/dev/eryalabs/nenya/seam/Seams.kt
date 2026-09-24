package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.money.Msat

/**
 * The marker every one of §3's seams carries.
 *
 * §3 names five things a Nenya implementation obtains from the client that embeds it — signer,
 * one-time signer, relay transport, wallet, clock and randomness — and says all of them MUST be
 * treated as untrusted with respect to state. Two of §3's rows become two interfaces each here:
 * "clock and randomness" is [NenyaClock] and [Randomness], because a client can have an
 * authoritative clock without a cryptographically secure source; and [Secp256k1Ops] is split out
 * of the signer because §17 lets an implementation omit BIP-340 verification while still signing
 * through a NIP-55 signer app, so the two fail independently. [EphemeralSigners] is §3's own
 * second row rather than a split of the first — see its KDoc.
 *
 * ### Sealed on purpose
 *
 * §3's list is closed. A client implements [Signer] or [Wallet]; it does not invent an eighth seam
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
 * What a signer did with a NIP-44 payload it was handed: it decrypted it, or it **tried and
 * refused**.
 *
 * ### Why this is not folded into [SeamAnswer]
 *
 * [SeamAnswer] has two shapes, and before this type existed both of a decryption's two failures
 * came back as the same one. A signer with no NIP-44 capability at all and a signer that computed
 * the MAC over a forged payload and found it wrong both answered
 * `Unavailable(`[SeamCapability.NIP44_DECRYPTION]`)` — "not performed". §17 forbids reporting an
 * unverified thing as verified, and it forbids the mirror image just as plainly: **a check that ran
 * and failed is not a check that did not run**. An implementation that reports a refused forgery as
 * *not performed* understates what it did, and a caller reading its capability surface cannot tell
 * a client that has not finished wiring up its signer from a counterparty sending rubbish.
 *
 * So the two are split along the line §17 draws. [SeamAnswer.Unavailable] keeps meaning **not
 * attempted**; [Refused] means attempted and refused; [Decrypted] means attempted and succeeded.
 * Nothing that was refused before is accepted now — the evidence demanded is unchanged and only its
 * *reporting* is more exact.
 */
public sealed interface Nip44Decryption {

    /**
     * The payload authenticated and here is what was inside it.
     *
     * ### The plaintext is never printed
     *
     * [toString] says the case and nothing else. A decrypted NIP-44 payload inside a gift wrap is a
     * private message — a seal, or a rumor carrying an order id, a coordinate and a counterparty's
     * key — and §12 items 2 and 11 keep every one of those out of a log, a crash report and the
     * string form of anything this library exposes. [SeamAnswer.Provided] redacts its value for the
     * same reason; this redacts again one layer in, so unwrapping the answer does not unwrap the
     * rule.
     */
    public class Decrypted(

        /** What the payload carried. A private message — see this class's note before logging it. */
        public val plaintext: String,
    ) : Nip44Decryption {

        override fun toString(): String = "Nip44Decryption.Decrypted(redacted)"
    }

    /**
     * The signer performed the decryption and the payload did not authenticate.
     *
     * NIP-44 v2 is authenticated encryption: a payload whose MAC does not match under the
     * conversation key is refused rather than decoded, and that refusal is a **result**. It carries
     * no detail on purpose. A reason authored by an injected signer is text this library did not
     * write and cannot promise is free of the ciphertext it was handed (§12 item 11), and there is
     * nothing a caller could do differently for one flavour of MAC failure over another: the only
     * two facts worth reporting are that the check ran and that it failed.
     */
    public object Refused : Nip44Decryption {

        override fun toString(): String = "Nip44Decryption.Refused"
    }
}

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
     * A BIP-340 signature over the **SHA-256 of [canonicalSerialisation]**, as 128 lowercase hex
     * characters.
     *
     * §4.1 is explicit: "a signature on a nostr event is over the 32-byte event id and nothing
     * else" — the raw 32 bytes the `id` field spells in hex, not the serialisation, and not a
     * digest of it taken a second time. An implementer who read this KDoc's earlier wording, "a
     * BIP-340 signature over [canonicalSerialisation]", and signed the serialisation itself would
     * produce events every relay on the network considers forged.
     *
     * ### Why the parameter is still the serialisation and not the id
     *
     * A signer app is the party that shows the user what they are about to sign, and 32 bytes of
     * hex are not something a human can consent to. Handing over the exact bytes also keeps §3's
     * "no claim about *what* was signed" enforceable: the caller recomputes the id from the same
     * serialisation it passed (§4.1 requires it to, before anything else), so the seam is never
     * the source of the thing the signature is checked against.
     *
     * @param canonicalSerialisation the NIP-01 canonical serialisation of §4.1. The implementation
     *   hashes it with SHA-256 and signs the resulting 32 bytes.
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
     *
     * ### Three answers, because an implementation that conflates two of them over-claims
     *
     * An implementation MUST answer [SeamAnswer.Unavailable] when it did not attempt the decryption
     * at all — no NIP-44 capability, no key for that counterparty — and
     * `Provided(`[Nip44Decryption.Refused]`)` when it **did** attempt it and the payload did not
     * authenticate. §17 requires the capability surface say what was actually performed, and
     * reporting a refused forgery as not-performed is that rule broken in the direction nobody
     * checks. See [Nip44Decryption].
     */
    public fun nip44Decrypt(counterpartyPublicKeyHex: String, payload: String): SeamAnswer<Nip44Decryption>

    public companion object {

        /** The fail-closed default: signs nothing, decrypts nothing, says so. */
        public val FAIL_CLOSED: Signer = FailClosedSigner
    }
}

/**
 * The one-time signer seam (§3, §7.1): a source of throwaway keypairs, one per gift wrap.
 *
 * §7.1 step 3 requires every `kind:1059` gift wrap be signed by "a freshly generated keypair, new
 * for every single wrap", and step 4 requires the two copies of a message — one to the recipient,
 * one to the sender's own key — use **different** throwaway keypairs, neither of them a key either
 * party uses elsewhere. A wrap signed by a key that appears twice links the two wraps, which is
 * the correlation the whole construction exists to remove.
 *
 * ### Why this is a seam of its own and not a method on [Signer]
 *
 * §3 gives it its own row for a reason this library feels directly: a NIP-55 signer app holds one
 * user key and cannot mint throwaway keys at all, so the two abilities fail **independently**. A
 * client with a perfectly good remote signer may have no way to satisfy this seam, and must be
 * able to say so by leaving it at [FAIL_CLOSED] without also losing its ability to sign. This is
 * the same reason [Secp256k1Ops] is split out of [Signer], and the split is what lets §17's
 * capability surface report exactly which of the three is missing.
 *
 * ### Why a whole [Signer] and not a signing function
 *
 * A gift wrap is both **signed** and **NIP-44-encrypted** with the throwaway key (§3, §7.1): the
 * same key must produce a BIP-340 signature over the wrap and a NIP-44 conversation key with the
 * addressee. A seam returning only a signature could not do the second. The obvious alternative —
 * this library draws 32 random bytes from [Randomness] and asks the client to sign and encrypt
 * *with them* — is exactly the design §3 rules out in as many words ("no secret key crosses this
 * boundary in either direction"), and §12 item 11 forbids besides; this package's own published
 * surface is pinned against it, so a `fresh(secretKey: ByteArray)` would turn the suite red.
 *
 * ### Freshness is the client's contract, and this library does not claim to check it
 *
 * §3's table is blunt about what MUST NOT be accepted from this seam: **any claim that the keypair
 * is fresh.** Freshness cannot be observed from outside — a seam handing out the same key every
 * time is indistinguishable, call by call, from one that does not, and the only way to catch it
 * would be to keep a store of every wrap key ever used, which is the sort of index §7.2 exists to
 * discourage. So this library keeps no such store and reports no such check. What §7.1 does have
 * it verify is narrower and purely local: that the key equals neither party's key, and that it
 * differs between the two copies of one message. A client whose implementation returns a
 * long-lived key has broken the contract stated here, and §13 says this library does not defend
 * its user against the client embedding it.
 */
public interface EphemeralSigners : Seam {

    /**
     * A [Signer] over a keypair generated for this call alone, never handed out before and never
     * again.
     *
     * The returned signer is used for exactly one gift wrap and then dropped. Its
     * [Signer.publicKey] is the wrap's `pubkey`, its [Signer.signEvent] produces the wrap's `sig`,
     * and its [Signer.nip44Encrypt] encrypts the seal to the addressee — all three with the same
     * throwaway key, which is why this returns a whole signer.
     *
     * Every call MUST produce a new keypair. That is a promise the client makes and this library
     * cannot verify; see this interface's note.
     */
    public fun fresh(): SeamAnswer<Signer>

    public companion object {

        /**
         * The fail-closed default: mints no keypair, so no gift wrap can be built.
         *
         * Deliberately not "derive one from [Randomness]". A default that quietly produced a
         * keypair here would be this library performing key generation — cryptography it has no
         * dependency for and no human sign-off to add — and would report a capability it does not
         * have.
         */
        public val FAIL_CLOSED: EphemeralSigners = FailClosedEphemeralSigners
    }
}

/**
 * What one relay said about one event, or about one subscription. **No constant is evidence of
 * anything.**
 *
 * §3: an implementation MUST NOT accept from the relay transport "any claim that an event is
 * valid, current, or complete". A relay that says `OK` may have dropped the event a millisecond
 * later; a relay that says it rejected the event may have stored it; a relay that said nothing at
 * all may have stored it too. This enum exists so that a caller reading it has to read the word
 * "claims" while doing so.
 *
 * ### Why silence is a constant here and not an absence
 *
 * The first three shapes below are ones a transport cannot express as "accepted" or "rejected" and
 * which §5.5 wants apart from both. A relay that timed out, one that closed the subscription and
 * one that sent a bare `NOTICE` have each failed to answer the question, and each fails it
 * differently: the first may still be reachable, the second gave a reason class ([RelayCloseReason])
 * the caller can act on, and the third is a human-readable aside NIP-01 attaches to no request at
 * all. Collapsing the three into [CLAIMS_REJECTED] would tell a caller the relay refused the event
 * when nothing of the sort was said.
 */
public enum class RelayAcknowledgement {

    /** The relay said it accepted the event. It might be lying, or wrong, or gone. */
    CLAIMS_ACCEPTED,

    /** The relay said it refused the event. Also a claim. */
    CLAIMS_REJECTED,

    /** No answer arrived inside the transport's own deadline. Not a refusal, and not an acceptance. */
    TIMED_OUT,

    /** The relay closed the subscription, or the connection under it. See [RelayClaim.closeReason]. */
    CLOSED,

    /** The relay sent a `NOTICE` and nothing that answers the request. Human-readable, and ignored. */
    NOTICE,
}

/**
 * NIP-01's machine-readable prefix on an `OK: false` or a `CLOSED`, as a **closed** set.
 *
 * NIP-01 fixes the prefixes a relay may put in front of its human-readable text, and a closed set is
 * what makes them actionable: a client can retry a `RATE_LIMITED`, must authenticate for an
 * `AUTH_REQUIRED`, and should not retry an `INVALID` at all. The free text after the prefix is
 * deliberately **not** carried anywhere on this surface — it is a string a relay chose, and §12
 * items 2 and 11 keep values a stranger controls out of anything this library exposes.
 *
 * Like every other constant here, none of this is evidence: a relay claiming `DUPLICATE` may never
 * have seen the event.
 */
public enum class RelayCloseReason {

    /** No reason class applies: the relay accepted, timed out, or offered no known prefix. */
    NONE,

    /** NIP-01 `duplicate`. */
    DUPLICATE,

    /** NIP-01 `pow`. */
    POW,

    /** NIP-01 `blocked`. */
    BLOCKED,

    /** NIP-01 `rate-limited`. */
    RATE_LIMITED,

    /** NIP-01 `invalid`. */
    INVALID,

    /** NIP-01 `restricted`. */
    RESTRICTED,

    /** NIP-01 `mute`. */
    MUTE,

    /** NIP-01 `error`. */
    ERROR,

    /** NIP-01 `auth-required`. */
    AUTH_REQUIRED,

    /** A prefix NIP-01 does not define. Reported as unknown rather than mapped onto a neighbour. */
    UNRECOGNISED,
}

/**
 * Whether a relay claims it finished sending what it had. **A claim, and never completeness.**
 *
 * An enum rather than a `Boolean` for the reason this whole package is built on: §9.1 and §3 are
 * about a component being *believed*, and a bare `true` is the shape a caller branches on without
 * reading a word of documentation. The seam package's published surface refuses a `Boolean`
 * parameter outright, and the same argument applies to what it hands back.
 */
public enum class SubscriptionEnd {

    /** The relay sent end-of-stored-events. It claims it has sent everything it holds. */
    CLAIMS_ENDED,

    /** No end-of-stored-events arrived: the relay closed, timed out, or is still sending. */
    CLAIMS_INCOMPLETE,
}

/**
 * One relay's answer, per relay.
 *
 * §5.5 wants "this relay did not accept the request" surfaced apart from "accepted but not readable
 * back", and a single acknowledgement for the whole fan-out cannot say either: a client publishing
 * to five relays and hearing `OK` from one, `rate-limited` from two and nothing from the rest is
 * told, by one constant, something that is true of none of them. So a transport answers a list of
 * these and the caller decides what a quorum is — this library takes no position on that, because
 * §3 gives it no grounds to: none of these is evidence.
 *
 * @param relay the relay this claim came from, as the embedding client names it. Carried so two
 *   relays disagreeing is expressible; never parsed, never validated and never contacted here.
 * @param acknowledgement what that relay said, or the way in which it said nothing.
 * @param closeReason NIP-01's machine-readable prefix when there was one, [RelayCloseReason.NONE]
 *   otherwise.
 */
public class RelayClaim(
    public val relay: String,
    public val acknowledgement: RelayAcknowledgement,
    public val closeReason: RelayCloseReason = RelayCloseReason.NONE,
) {

    /** Names the relay and what it claimed. There is no event and no filter in here to leak. */
    override fun toString(): String =
        "RelayClaim(relay=$relay, claims=$acknowledgement, closeReason=$closeReason)"
}

/**
 * One relay's answer to a subscription: its [claim], what it sent, and whether it says it finished.
 *
 * The events are that relay's alone, which is the point. §4.6 forbids ordering an order thread by
 * the `created_at` of what comes back and requires an implementation order by its own receipt
 * sequence; keeping the fan-out's answers apart is what lets a caller notice that one relay served
 * an event another did not, which is the disagreement §5.5 is about and which a merged list erases.
 *
 * Everything in [events] is hostile input (§4.3), including events the implementation believes it
 * authored.
 */
public class RelayDelivery(
    public val claim: RelayClaim,
    public val events: List<String>,
    public val subscription: SubscriptionEnd,
) {

    /** Names the relay, the claim and **how many** events — never one of them. */
    override fun toString(): String =
        "RelayDelivery(claim=$claim, events=${events.size}, subscription=$subscription)"
}

/**
 * The relay transport seam (§3): events out, events in.
 *
 * Deals in **serialised** events rather than parsed ones, and that is a boundary this library keeps
 * on purpose rather than a gap waiting to be filled. `dev.eryalabs.nenya.wire.EventJson` reads and
 * writes §7.1's object form and `dev.eryalabs.nenya.envelope.GiftWrap` opens a gift wrap, so a codec
 * does exist — but it is the *caller's* to apply, after the bytes have crossed this seam. A
 * transport that cannot parse cannot *interpret*, so §3's rule against accepting a relay's claim
 * that an event is valid has nothing here to attach itself to, and no relay's framing decisions
 * reach the reader that recomputes an event id.
 *
 * Everything that comes back through this seam is hostile input (§4.3), including events the
 * implementation believes it authored.
 *
 * **Nothing in this repository opens a socket.** The default below answers
 * [SeamAnswer.Unavailable]; a real transport is the embedding client's, and a test's is a fake.
 */
public interface RelayTransport : Seam {

    /**
     * Hand a serialised event to the relays, returning what **each** of them claimed about it.
     *
     * One [RelayClaim] per relay the transport tried, in whatever order it chooses. An empty list is
     * a transport that tried no relay at all and is not an acceptance; [SeamAnswer.Unavailable] is a
     * transport that was never wired up. Neither a full list nor an empty one is evidence (§3).
     *
     * @param serialisedEvent the JSON of a signed nostr event.
     */
    public fun publish(serialisedEvent: String): SeamAnswer<List<RelayClaim>>

    /**
     * Ask for events matching a serialised NIP-01 filter, returning what **each** relay sent.
     *
     * One [RelayDelivery] per relay. No list is a claim of completeness — [SubscriptionEnd] carries
     * only what the relay *said* about that — and §4.6 forbids ordering an order thread by the
     * `created_at` of what comes back: an implementation MUST order by its own receipt sequence.
     *
     * @param serialisedFilter the JSON of a NIP-01 filter.
     */
    public fun request(serialisedFilter: String): SeamAnswer<List<RelayDelivery>>

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
     * @param invoice the BOLT-11 string, opaque **at this seam**. `dev.eryalabs.nenya.settlement`
     *   does parse BOLT-11 — Appendix C's reader, which §9.2 checks 3, 4 and 5 are run through —
     *   and this seam deliberately does not: what the wallet is handed is the exact string the
     *   payee sent, so the invoice this library checked and the invoice the wallet pays cannot
     *   differ by a re-encoding. §12 items 1 and 2 apply to it: an invoice string MUST NOT reach a
     *   public event, and no order id, coordinate or counterparty pubkey may have reached its
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
 * ### This library has one opinion about what the clock says: it is not before 1970
 *
 * A clock reporting 1970 and a clock reporting 3000 are both simply *used*. §4.6 makes the
 * injected clock authoritative; a library that second-guessed a plausible reading would be
 * substituting an ambient time it is not allowed to read. The one tolerance §4.6 does grant —
 * MAY reject a *public* event whose `created_at` is implausibly far in the future — is a rule
 * about an event, not about the clock, and MUST NOT be applied to a gift wrap or a seal.
 *
 * A **negative** reading is different in kind. §4.3 fixes every timestamp as a non-negative
 * integer, so a clock answering before 1970 is not reporting an unusual time — it is broken, and
 * no deadline can honestly be evaluated against it. This library treats it exactly as it treats
 * every other injected part that answers something it cannot use (a randomness source returning
 * the wrong number of bytes, a wallet's claim, an unavailable seam): it **fails closed**. No
 * deadline is evaluated against such a reading and no time is recorded from it, and the refusal
 * names the reason — `TransitionRejection.CLOCK_READING_BEFORE_EPOCH` from the order machine, and
 * `ListingActivity.CANNOT_SAY` from a listing, never `ACTIVE` and never `EXPIRED`. A transition
 * that does not depend on the time still happens: an order whose receipts this library verified
 * still becomes `paid`, it simply records no `paidAt`.
 *
 * ### Named `NenyaClock` rather than `Clock`
 *
 * The JDK's `Clock` would be the obvious JVM type, and a default built from it — `Clock.systemUTC()`
 * — is a live ambient effect that every injected-fake test would step straight over. The suite
 * sweeps the main source roots for exactly that token, among others, and a distinct name keeps the
 * two from being confused at a call site.
 */
public interface NenyaClock : Seam {

    /**
     * The current time, as the embedding client understands it, in **unix seconds** — the unit
     * of nostr's `created_at` and of every §4.3 timestamp this reading is compared against.
     *
     * Any non-negative `Long` is used as given, up to and including [Long.MAX_VALUE]; the
     * deadline arithmetic that consumes it is overflow-checked rather than relying on the clock
     * to stay in a plausible range. A **negative** reading (before 1970) is refused where it is
     * consumed: the clock is broken, so no deadline is judged against it and no time is recorded
     * from it — see this interface's note.
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
 * The suite sweeps the main source roots for ambient randomness, so no code in this library can
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
     * ### The contract this library relies on is a 32-byte event id, and only that
     *
     * BIP-340 itself is defined over arbitrary-length messages, and the vendored vectors exercise
     * 0, 1, 17 and 100 bytes alongside the usual 32 — but every call this library will ever make
     * passes an event id, because §4.1 says a nostr signature is over the 32 bytes the `id` field
     * spells and nothing else. So an adapter MAY answer [SeamAnswer.Unavailable] for a [message]
     * of any other length, and is conformant in doing so; it MUST NOT answer
     * [SignatureVerdict.VALID] for one it did not actually verify. The earlier wording promised
     * "a message of any length", which asked adapters for a generality no caller here needs and
     * which a wrapper around an id-only signing API cannot honestly provide.
     *
     * @param publicKeyXOnly the 32-byte x-only public key.
     * @param message the message: a 32-byte event id on every path this library takes. An
     *   implementation MAY answer [SeamAnswer.Unavailable] for any other length.
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

    override fun nip44Decrypt(
        counterpartyPublicKeyHex: String,
        payload: String,
    ): SeamAnswer<Nip44Decryption> = SeamAnswer.unavailable(SeamCapability.NIP44_DECRYPTION)

    override fun toString(): String = "Signer.FAIL_CLOSED"
}

private object FailClosedEphemeralSigners : EphemeralSigners {

    override fun fresh(): SeamAnswer<Signer> =
        SeamAnswer.unavailable(SeamCapability.EPHEMERAL_SIGNER)

    override fun toString(): String = "EphemeralSigners.FAIL_CLOSED"
}

private object FailClosedRelayTransport : RelayTransport {

    override fun publish(serialisedEvent: String): SeamAnswer<List<RelayClaim>> =
        SeamAnswer.unavailable(SeamCapability.RELAY_PUBLISH)

    override fun request(serialisedFilter: String): SeamAnswer<List<RelayDelivery>> =
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
