package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.collections.readOnlyListOf
import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.platform.declaringEnumSimpleName
import dev.eryalabs.nenya.seam.SeamCapability

/**
 * What this library did with one of §17's ten conformance items.
 *
 * Three answers rather than two, for the same reason
 * [dev.eryalabs.nenya.seam.SeamAnswer] has three: §17's last paragraph requires an
 * implementation that omits a check to publish "a machine-readable statement of what it
 * verifies", and a two-valued answer cannot draw the distinction that statement exists to
 * draw. An item half-done reported as done is the §17 over-claim; an item half-done reported
 * as absent throws away a fact the caller needs.
 *
 * §17 itself is all-or-nothing — an implementation is conformant only if it does all ten — so
 * [PARTIAL] is **not** a claim of conformance on that item. It is a claim about which half.
 */
public enum class ConformanceStatus {

    /**
     * Performed by this library, for itself, with tests. Carries evidence classes and **no**
     * not-performed constants.
     */
    PERFORMED_HERE,

    /**
     * Partly performed here. Carries evidence classes for the half that is done **and**
     * not-performed constants naming the half that is not.
     *
     * ### A structural limit of that rule, recorded for whoever extends this
     *
     * Requiring a constant is what makes [PARTIAL] falsifiable, and it also means an item can
     * only take [PARTIAL] for a gap one of the three enums has a constant for. Two real gaps do
     * not: "turning a relay's bytes into a `WireEvent` is the client's step" (item 1) and
     * "§12's obligation is negative, so there is nothing to not-perform" (item 9). Both items
     * are therefore [PERFORMED_HERE] with the gap spelled out in their note rather than
     * [PARTIAL] with nothing to point at. Adding a constant to one of those enums to fix that
     * is a change to an established evidence surface and belongs in a task of its own.
     */
    PARTIAL,

    /**
     * Not performed here at all. Carries not-performed constants and **no** evidence classes:
     * an item with a class to point at is not an item nothing was done for.
     */
    NOT_PERFORMED_HERE,
}

/**
 * One of §17's ten numbered conformance items, with a status this library can defend and the
 * machine-checkable evidence for it.
 *
 * ### Why every field but [note] is evidence rather than prose
 *
 * A hand-written item-to-status map is a transcription, and a test written by the same hand
 * that wrote the map proves only that the two agree. So each field is anchored to something
 * outside this file:
 *
 * - [specSections] is held **equal** to the `§x.y` references parsed out of §17 at test time,
 *   so a revision that moved an item's obligations turns the suite red rather than leaving a
 *   stale reading here;
 * - [evidenceClasses] must be found by reflection in `build/classes/kotlin/jvm/main`, so an item
 *   cannot claim work that does not exist;
 * - [notPerformed] must be a subset of [Capabilities.NOT_PERFORMED_HERE], which is itself
 *   derived from the three enums rather than listed.
 *
 * Flipping a status without doing the work therefore fails on one side or the other: a
 * [ConformanceStatus.NOT_PERFORMED_HERE] item raised to [ConformanceStatus.PERFORMED_HERE]
 * has no class to name and still names constants, and a
 * [ConformanceStatus.PERFORMED_HERE] item has no constants to point at.
 *
 * ### Not an evidence type, and deliberately not shaped like one
 *
 * `VerifiedPayment`, `DeliveryEvidence` and `CheckedEvent` are unforgeable through the published
 * API because a client that could mint one could mint a payment. This is the opposite kind of
 * value: it publishes what was **not** checked, so the constructor is `internal` for tidiness
 * rather than for safety, and Kotlin publishing it to the JVM costs nothing. A client that builds
 * its own [ConformanceItem] has built its own object; [Capabilities.ITEMS] is unchanged, and a
 * fabricated "everything is fine" item is a statement about nothing.
 *
 * A caller that reads this as a warrant has read it backwards: nothing here is evidence of a
 * payment, a signature or a delivery, and no field of it is a status string standing in for one.
 */
public class ConformanceItem internal constructor(

    /** §17's own numbering, `1`–`10`. The item set is held equal to the parsed one. */
    public val number: Int,

    /** What this library did with it. */
    public val status: ConformanceStatus,

    specSections: Set<String>,
    evidenceClasses: Set<String>,
    notPerformed: Set<Enum<*>>,

    /** One paragraph in this library's own words. Never parsed, never asserted against. */
    public val note: String,
) {

    /**
     * The sections the item cites, as bare `x.y` strings without the `§`.
     *
     * Held equal to the references parsed out of §17's text at test time. This is the anchor
     * that makes the item set more than a count: two lists of ten numbers agree by arithmetic
     * coincidence, and two lists of section references do not.
     */
    public val specSections: Set<String> = unmodifiable(specSections)

    /**
     * Fully-qualified names of classes in the main source tree that do this item's work, or empty when
     * [status] is [ConformanceStatus.NOT_PERFORMED_HERE].
     *
     * Not exhaustive, and not meant to be: one class that must exist is enough to make the
     * claim falsifiable, which is the whole job of this field.
     */
    public val evidenceClasses: Set<String> = unmodifiable(evidenceClasses)

    /**
     * The checks this item is missing, drawn from [Capabilities.NOT_PERFORMED_HERE], or empty
     * when [status] is [ConformanceStatus.PERFORMED_HERE].
     *
     * A subset rather than the whole union: these are the constants that bear on *this* item.
     */
    public val notPerformed: Set<Enum<*>> = unmodifiable(notPerformed)

    /** Names the number, the status and the counts. Carries no identifier of any kind. */
    override fun toString(): String =
        "ConformanceItem(#$number, $status, sections=${specSections.sorted()}, " +
            "classes=${evidenceClasses.size}, notPerformed=${notPerformed.size})"

    private companion object {

        /**
         * Copied and wrapped, not stored by reference.
         *
         * §13 says this library does not defend its user against the client embedding it, so this
         * is hygiene rather than a boundary — but a surface whose caller can empty
         * [notPerformed] process-wide is a surface that can be made to say "nothing is
         * unverified", which is the one sentence §17 forbids it from saying. The three enum sets
         * in [Capabilities] are wrapped for the same reason, and `WireEvent.tags` before them.
         */
        fun <T> unmodifiable(values: Set<T>): Set<T> =
            readOnlySetOf(values)
    }
}

/**
 * §17's capability surface: a machine-readable statement of what this library verifies, and —
 * the half that matters — what it does not.
 *
 * ### Why this exists at all
 *
 * §17's last paragraph: "An implementation MAY omit BIP-340 signature verification and BOLT-11
 * node-key recovery and is still conformant, provided it does not report unverified things as
 * verified. Such an implementation MUST expose a capability surface — a machine-readable
 * statement of what it verifies." This library omits both, deliberately and permanently until
 * a human signs off a cryptography dependency, so that MUST is binding on it. Until now the
 * omission was stated only in KDoc, which is not machine-readable and which a client embedding
 * this library cannot branch on.
 *
 * ### The union is derived, never listed
 *
 * [NOT_PERFORMED_HERE] is computed from the enum constants of
 * [PaymentCheck], [DeliveryCheck] and [SeamCapability] minus the two sets those packages
 * already publish as performed. Nothing is transcribed, so a constant added to any of the three
 * later cannot go unpublished here — it lands in the union the moment it is declared, and the
 * suite's union equality is what says so.
 *
 * Every [SeamCapability] is in the union without subtraction, and that is a statement rather
 * than an oversight: this library performs **none** of §3's seam operations itself. Each is
 * delegated to an interface the embedding client supplies, and every default answers
 * [dev.eryalabs.nenya.seam.SeamAnswer.Unavailable]. A client that injects a real wallet has a
 * wallet; this library still has not paid anything.
 *
 * ### What this surface is not
 *
 * It is not evidence, and it is not a warrant. It publishes absences. §9.1's rule stands one
 * layer above it: a caller that reads "PERFORMED_HERE" on item 6 and concludes a particular
 * payment settled has skipped the only thing that would establish that, which is
 * [VerifiedPayment] over a preimage this library hashed itself.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public object Capabilities {

    /** The document this surface is about. */
    public const val SPECIFICATION: String = "NENYA-1"

    /** §17's item count, derived from [ITEMS] rather than written down twice. */
    public val itemCount: Int get() = ITEMS.size

    /**
     * Every §9.2 check this library does not perform, derived from [PaymentCheck]'s constants
     * minus [VerifiedPayment.CHECKS_PERFORMED_HERE].
     */
    public val PAYMENT_CHECKS_NOT_PERFORMED: Set<PaymentCheck> =
        unmodifiable(PaymentCheck.entries.filterNot { it in VerifiedPayment.CHECKS_PERFORMED_HERE })

    /**
     * Every §10 obligation this library does not perform in the verification chain, derived
     * from [DeliveryCheck]'s constants minus [DeliveryEvidence.CHECKS_PERFORMED_HERE].
     *
     * [DeliveryCheck.RELEASE_IDENTITY] is in here although `delivery` implements it, because
     * that is a separate call and evidence produced without it MUST NOT be read as having
     * performed it. The constant's own KDoc is the authority on that, and this set consumes it
     * rather than second-guessing it.
     */
    public val DELIVERY_CHECKS_NOT_PERFORMED: Set<DeliveryCheck> =
        unmodifiable(DeliveryCheck.entries.filterNot { it in DeliveryEvidence.CHECKS_PERFORMED_HERE })

    /**
     * Every §3 seam capability, all of them, because this library performs none of them itself.
     * See this object's note on why there is no subtraction here.
     */
    public val SEAM_CAPABILITIES_NOT_PERFORMED: Set<SeamCapability> =
        unmodifiable(SeamCapability.entries.toList())

    /**
     * The union of the three, in declaration order, as the constants themselves.
     *
     * The constants rather than their names: a caller branching on
     * `PaymentCheck.INVOICE_AMOUNT in Capabilities.NOT_PERFORMED_HERE` is doing the thing this
     * surface is for, and a set of strings would make that a spelling contest.
     */
    public val NOT_PERFORMED_HERE: Set<Enum<*>> = unmodifiable(
        PAYMENT_CHECKS_NOT_PERFORMED.toList<Enum<*>>() +
            DELIVERY_CHECKS_NOT_PERFORMED.toList<Enum<*>>() +
            SEAM_CAPABILITIES_NOT_PERFORMED.toList<Enum<*>>(),
    )

    /**
     * §17's ten items, in §17's order.
     *
     * The statuses are this library's own claims about itself and are the one thing here a
     * human wrote. Everything that would make a wrong claim cheap — the section references, the
     * evidence classes, the not-performed constants — is checked against something else.
     */
    public val ITEMS: List<ConformanceItem> = readOnlyListOf(listOf(
        ConformanceItem(
            number = 1,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("4.1", "4.3"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.wire.WireEvent",
                "dev.eryalabs.nenya.wire.EventId",
                "dev.eryalabs.nenya.wire.CheckedEvent",
                "dev.eryalabs.nenya.wire.WireLimits",
                "dev.eryalabs.nenya.tag.TagLimits",
            ),
            notPerformed = emptySet(),
            note = "§4.1's canonical serialisation is emitted byte for byte and the id is " +
                "recomputed with this library's own FIPS 180-4 SHA-256; nothing downstream " +
                "reads an event that did not pass that check, because every decoder takes a CheckedEvent. §4.3's hex, " +
                "timestamp, tag-shape, duplicate, unknown-tag and resource rules are all " +
                "enforced, the fifth bound in the tag layer where the `image` vocabulary lives. " +
                "One narrowing, stated rather than papered over: there is no JSON parser here " +
                "and STOP RULE 11 forbids adding one, so turning a relay's bytes into a " +
                "WireEvent is the embedding client's step. §4.1's rules are about a structure, " +
                "and this library holds itself to all of them over the structure it is handed. " +
                "That narrowing is visible in one behaviour and is stated rather than implied: " +
                "the 64 KiB bound is measured over the canonical serialisation, which omits " +
                "`id`, `sig` and the JSON object's field names, so it is a lower bound on the " +
                "true serialised size and under-rejects by of the order of two hundred bytes. " +
                "§4.3 requires a bound and requires reject-never-truncate; both hold. A client " +
                "that needs the bound measured over the exact bytes a relay sent it should " +
                "inject a correspondingly smaller WireLimits.",
        ),
        ConformanceItem(
            number = 2,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("5.3", "4.4"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.tag.TagSet",
                "dev.eryalabs.nenya.tag.NenyaTags",
                "dev.eryalabs.nenya.tag.TagWriter",
                "dev.eryalabs.nenya.tag.TagSpec",
                "dev.eryalabs.nenya.money.Msat",
            ),
            notPerformed = emptySet(),
            note = "§5.3's table is not transcribed: NenyaTags is held equal to the table " +
                "parsed out of the document at test time. Both `fee` arities are read and " +
                "written. The Card. and Requirement columns are applied to listings only and " +
                "the kind is taken as an input everywhere else, which is the half §5.3 says " +
                "rejects this document's own bid example when it is got wrong. Money is strict " +
                "on write and permissive on read across §4.4's eight unit tokens, and there is " +
                "no floating-point amount anywhere: Msat is a value class over a Long. One " +
                "judgement call, recorded here rather than left to be discovered: §5.3's " +
                "Encoding cell for `image` prints three elements while §5.1 calls a Nenya offer " +
                "an unmodified NIP-99 listing, where the dimensions element is optional. This " +
                "library reads and writes both arities, because refusing the two-element form " +
                "would refuse a conformant NIP-99 offer. That is a disagreement inside the " +
                "document and a human has been asked to settle it.",
        ),
        ConformanceItem(
            number = 3,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("6", "6.1"),
            evidenceClasses = setOf("dev.eryalabs.nenya.bid.Bid"),
            notPerformed = emptySet(),
            note = "Public bidding is implemented and is the only bidding there is, which is " +
                "what §17 asks for: `A`/`a`, `K`/`k` and `P`/`p` all REQUIRED, all cross-checked " +
                "against the coordinate they scope to, and `E`/`e` scoping refused by name. " +
                "§6.1's private bidding is OPTIONAL and is not implemented; §17 says in as many " +
                "words that only the reverse omission is non-conformant, so this item is not " +
                "PARTIAL on account of it.",
        ),
        ConformanceItem(
            number = 4,
            status = ConformanceStatus.PARTIAL,
            specSections = setOf("7.2", "7.3"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.channel.AttributedRumor",
                "dev.eryalabs.nenya.channel.Attribution",
                "dev.eryalabs.nenya.channel.RumorKind",
                "dev.eryalabs.nenya.channel.OrderMessageKind",
            ),
            notPerformed = setOf(
                SeamCapability.NIP44_ENCRYPTION,
                SeamCapability.NIP44_DECRYPTION,
                SeamCapability.RELAY_PUBLISH,
            ),
            note = "Done: §7.2's pubkey-equality check, which §7.2 requires even of an " +
                "implementation that cannot verify the seal's signature — a rumor whose claimed " +
                "`pubkey` is not the seal's is discarded, no value is produced for it, and every " +
                "message that survives is reported as authenticated-by-decryption only. " +
                "Attribution has exactly one constant, so there is none for signature-verified. " +
                "§7.4's envelope with it: the four rumor kinds and the six `type` values are held " +
                "equal to §7.4's own tables parsed at test time, an unimplemented `type` lands in " +
                "a sink rather than on the nearest known value, `type=4` is readable and " +
                "unemittable, and the required-tag rule and its one `type=6` exception are both " +
                "enforced. The gift wrap's ephemeral pubkey is unrepresentable: the entry point " +
                "takes the seal's key and the rumor, and there is no parameter it could arrive " +
                "in. Not done, and all three constants stay: §7.1's encryption is forbidden " +
                "here, the **decryption was the caller's** — this library was handed a rumor and " +
                "a pubkey and takes no position on where they came from — and §7.3's " +
                "`kind:10050` publication needs a relay, which nothing here opens a socket to.",
        ),
        ConformanceItem(
            number = 5,
            status = ConformanceStatus.PARTIAL,
            specSections = setOf("8.1", "8.3", "8.4", "8.5", "8.6"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.money.FeeSplit",
                "dev.eryalabs.nenya.money.FeeTerm",
                "dev.eryalabs.nenya.order.OrderMachine",
                "dev.eryalabs.nenya.settlement.FeeTermAgreement",
                "dev.eryalabs.nenya.settlement.FeeTermPoint",
            ),
            notPerformed = setOf(
                SeamCapability.BIP340_VERIFICATION,
                SeamCapability.RELAY_REQUEST,
            ),
            note = "Done: the fee is computed by §8.3's published 64-bit decomposition and " +
                "checked against BigInteger over a sampled corpus, so it does not overflow; a " +
                "fee payment is refused before `awaiting_payment` while the fee payment " +
                "*request* is accepted as part of `committed → awaiting_payment` and never " +
                "earlier (§8.5); a payee whose computed amount is 0 requires no invoice (§8.3), " +
                "which is the clause that deadlocks an order when it is missed; §8.6's two " +
                "invoices are two payees and there is no combined-invoice shape to refuse " +
                "because there is none to build. §8.4 is done too, and that is what changed " +
                "here: the `(bps, recipient)` pair is compared over the **raw tag elements**, " +
                "which is what byte-identical means, across all four points §8.4 marks REQUIRED " +
                "and the three it marks OPTIONAL — with the MUST NOT on those three honoured, so " +
                "a `payee=provider` message carrying no `fee` tag is accepted and one carrying a " +
                "disagreeing tag is not. The answer names which point diverged and which half of " +
                "the pair, because §8.4 requires a divergence be surfaced as a terms mismatch, " +
                "and the point set is held equal to §8.4 parsed out of the document at test " +
                "time. §8.7's sealing key is compared too, against the seal the `type=2` " +
                "arrived under. Not done, and both constants stay: `requires a signed fee term` " +
                "needs BIP-340 verification, which this library does not have — so the §8.7 " +
                "binding is only as strong as the decryption the caller performed, and no " +
                "signature behind any of these terms is verified by anybody here; and §8.1's " +
                "reachability check on a named fee recipient needs a relay query, which nothing " +
                "here opens a socket for.",
        ),
        ConformanceItem(
            number = 6,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("9.2"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.payment.VerifiedPayment",
                "dev.eryalabs.nenya.payment.Payee",
                "dev.eryalabs.nenya.order.OrderMachine",
                "dev.eryalabs.nenya.settlement.PaymentRequestStore",
                "dev.eryalabs.nenya.settlement.Settlement",
                "dev.eryalabs.nenya.settlement.Bolt11Invoice",
            ),
            notPerformed = emptySet(),
            note = "All six of §9.2's checks are now performed on the path OrderMachine consumes, " +
                "which is why this item moved off PARTIAL. `awaiting_payment → paid` takes a " +
                "Settlement.Evidenced per required payee — a bare VerifiedPayment is not " +
                "expressible there — and Settlement.verify performs check 1 (the receipt's " +
                "BOLT-11 string compared byte-identically against the stored one, with a receipt " +
                "nothing was stored for rejected outright), check 2 (lowercase hex decoding to " +
                "exactly 32 bytes), check 3 in full, check 4 (the amount against `price_msat` for " +
                "a provider receipt and §8.3's `fee_msat` for a fee one, in either direction, with " +
                "an any-amount invoice rejected) and check 5 (`timestamp + expiry` against the " +
                "clock reading persisted at acceptance, never against the clock at verification, " +
                "because §9.2 says an invoice that was live when the buyer paid it does not " +
                "become unpaid because it has since expired). Settlement.verifyFeeReceipt " +
                "performs check 6's three besides: §8.4's byte-identical fee term across every " +
                "point it marks REQUIRED, §8.7's sealing key against the seal the stored `type=2` " +
                "arrived under, and §8.5's state precondition read off this implementation's own " +
                "order rather than off a status token a counterparty sent. What closed last was " +
                "check 3's PROVENANCE, and it closed by SUBTRACTION of a parameter: neither entry " +
                "point takes a payment hash any more, so check 3's operand is the 256-bit `p` " +
                "field of the stored invoice, read off the same parse checks 4 and 5 are made " +
                "against. There is no argument left through which a caller could hand in the " +
                "SHA-256 of a preimage it chose and get a true comparison about an invoice nobody " +
                "issued. Parsing the invoice for its AMOUNT never established that and was never " +
                "claimed to. Two narrowings, stated plainly rather than implied, because this " +
                "item is the one that governs reaching `paid`. FIRST, DURABLE PERSISTENCE IS THE " +
                "EMBEDDING CLIENT'S: §17 item 6 is about what an implementation has KEPT, and " +
                "PaymentRequestStore is an injected interface — keyed by (order, payee), holding " +
                "the BOLT-11 string verbatim alongside the value the injected clock held when the " +
                "request was accepted, and refusing to accept at all rather than storing against a " +
                "fabricated time when the clock is fail-closed — whose one shipped implementation, " +
                "PaymentRequestStore.inMemory, survives no restart. A client that needs an order " +
                "to be checkable after a process dies MUST implement that interface over something " +
                "durable; nothing here will tell it that it has not, because a store is the " +
                "client's own persistence and §13 says this library does not defend its user " +
                "against the client embedding it. There is no enum constant for that gap, which " +
                "is why this item is PERFORMED_HERE with the narrowing written out rather than " +
                "PARTIAL with nothing to point at — the structural limit ConformanceStatus.PARTIAL " +
                "records, and the same shape items 1 and 9 take. SECOND, NO SIGNATURE IS VERIFIED " +
                "ANYWHERE: SeamCapability.BIP340_VERIFICATION stays in the union, so check 6's " +
                "sealing-key equality and §7.2's attribution are only as strong as the decryption " +
                "the caller performed. PaymentCheck.INVOICE_IDENTITY, PAYMENT_HASH_PROVENANCE, " +
                "INVOICE_AMOUNT and INVOICE_EXPIRY all remain in Capabilities.NOT_PERFORMED_HERE, " +
                "and that is deliberate and is not a contradiction of this item's status: that " +
                "union is derived from VerifiedPayment.CHECKS_PERFORMED_HERE and is a claim about " +
                "a BARE VerifiedPayment.verify, which holds no store, parses no invoice and takes " +
                "its payment hash as a parameter. The machine-readable record of what the store " +
                "path did is each result's own Settlement.checksPerformed, which composes checks " +
                "1, 3, 4 and 5 with T3's two rather than widening VerifiedPayment's global claim. " +
                "Decision B's gate is unchanged and still bites: `awaiting_payment → paid` " +
                "REFUSES with TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED while any check " +
                "that applies to the receipts offered was performed by nobody, computed from what " +
                "applies to each payee minus what that receipt recorded as PERFORMED and never " +
                "from its checksNotPerformedHere, so a record that wrongly subtracted a check " +
                "cannot hide its own omission from it. What changed is that a priced order can " +
                "now pass that gate rather than only a free one: a fee receipt put through plain " +
                "Settlement.verify is still refused, missing exactly check 6's three. Every order " +
                "in `paid`, `released` or `settled` carries an empty " +
                "paymentChecksNotPerformedHere, and that emptiness is an invariant of the gate — " +
                "now one that six performed checks satisfy rather than one only an order owing no " +
                "receipt could reach. It is still not a warrant: §9.1 stands one layer above this " +
                "surface, and the thing that establishes a particular payment is VerifiedPayment " +
                "over a preimage this library hashed itself, not a status read off this item.",
        ),
        ConformanceItem(
            number = 7,
            status = ConformanceStatus.PARTIAL,
            specSections = setOf("10", "10.2"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.delivery.DeliverableCommitment",
                "dev.eryalabs.nenya.delivery.ServedBytesVerified",
                "dev.eryalabs.nenya.delivery.DeliveryEvidence",
                "dev.eryalabs.nenya.channel.DeliveryCommitmentMessage",
                "dev.eryalabs.nenya.channel.DeliverableReleaseMessage",
                "dev.eryalabs.nenya.channel.DeliveryTags",
            ),
            notPerformed = setOf(
                DeliveryCheck.GCM_AUTHENTICATION,
                DeliveryCheck.ENCRYPTION_PARAMETERS,
                DeliveryCheck.COMMITMENT_CARRIES_NO_KEY,
                DeliveryCheck.SERVED_BYTES_PROVENANCE,
            ),
            note = "`x` and `ox` are both computed here with this library's own FIPS 180-4 " +
                "SHA-256, in §10.4's order, " +
                "enforced by the type system rather than by a comment — there is no way to " +
                "reach the `ox` check without having passed the `x` check. §10.3's " +
                "four-operand identity check is implemented as its own call, now in both of the " +
                "shapes §10.3 and §10.4 need: over parsed values, which is what the state machine " +
                "runs, and over the raw tag value elements, which is what §10.3 actually says — " +
                "`[\"size\", \"018342912\"]` against `[\"size\", \"18342912\"]` is a divergence " +
                "there and cannot be one in a numeric comparison. What changed here is that the " +
                "two delivery messages are now decoded rather than assembled from typed " +
                "parameters: a `kind:16` `type=5` is refused by name if it carries " +
                "`decryption-key` or `decryption-nonce` (§10.1), a `kind:15` has both values held " +
                "to §10.2's encodings — lowercase hex of 64 and 24 characters, or standard base64 " +
                "decoding to 32 and 12 bytes, refused when it is the wrong length under both — and " +
                "both messages are refused for an `encryption-algorithm` other than `aes-gcm`. " +
                "§10.3's binding is enforced where §10.3 puts it, on the `order` tag and not on " +
                "hash equality: building the `paid → released` event takes the open `Order`, " +
                "refuses a release naming another order, and refuses one naming an order this " +
                "implementation's own view does not hold in `paid`. Neither key nor nonce is " +
                "published by any member or any `toString`: they are checked and dropped, because " +
                "this library does not decrypt and the caller already holds the rumor it " +
                "decrypted (§12 item 11). " +
                "THE FOUR CONSTANTS BELOW ALL STAY, AND THE TWO THAT LOOK LIKE CONTRADICTIONS ARE " +
                "NOT ONE. `Capabilities.NOT_PERFORMED_HERE` is derived from " +
                "`DeliveryEvidence.CHECKS_PERFORMED_HERE`, so it is a claim about the " +
                "VERIFICATION CHAIN — a commitment and two byte arrays — which holds no tags and " +
                "no order and genuinely performs neither COMMITMENT_CARRIES_NO_KEY nor " +
                "RELEASE_ORDER_BINDING. The machine-readable record of what the DECODED path did " +
                "is the order's own `deliveryChecksPerformed`, which the two message types fill " +
                "through `asOrderEvent` and which an order built from a bare " +
                "`DeliverableCommitment` or `DeliverableRelease` leaves empty, because nobody " +
                "checked. The same shape §17 item 6 takes for the payment store, and for the same " +
                "reason: widening the global claim on the strength of a path a caller may not have " +
                "taken is the over-claim this surface exists to prevent. ENCRYPTION_PARAMETERS " +
                "stays for a second reason too — the algorithm token matches and the key and nonce " +
                "are the right lengths, but §10.2 is AES-256-GCM whole, including a nonce never " +
                "reused with the same key, a 128-bit tag appended to the ciphertext and no AAD, " +
                "and this library performs no encryption at all. GCM_AUTHENTICATION and " +
                "SERVED_BYTES_PROVENANCE are unchanged and unreachable from here: §10.4 step 2 is " +
                "the caller's decryption, and nothing in this library opens a socket.",
        ),
        ConformanceItem(
            number = 8,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("11"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.order.OrderMachine",
                "dev.eryalabs.nenya.order.OrderState",
                "dev.eryalabs.nenya.order.OrderEvent",
            ),
            notPerformed = emptySet(),
            note = "The transition function is total over the whole state-by-event " +
                "cross-product and every unlisted pair is a named rejection rather than a " +
                "fall-through. The legal set is held **equal** to §11.2's table, transcribed " +
                "line by line with spec line numbers and itself parsed back out of the " +
                "document at test time, so neither a truncated transcription nor an invented " +
                "edge passes. §11.1's eleventh case — an unrecognised status reads as `unknown` " +
                "and transitions nowhere — is implemented as §11.1 requires.",
        ),
        ConformanceItem(
            number = 9,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("12"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.seam.OrderId",
                "dev.eryalabs.nenya.payment.VerifiedPayment",
                "dev.eryalabs.nenya.tag.TagSet",
            ),
            notPerformed = emptySet(),
            note = "The clause is read as being about order-scoped identifiers, and that " +
                "reading is stated rather than assumed, because the literal one is " +
                "unsatisfiable: §12 item 2 names the listing coordinate, the `d` value, the " +
                "title and the counterparty pubkey, and §5.3 REQUIRES `d` and `title` on a " +
                "public listing while §6 REQUIRES `a` and `p` on a public bid. A specification " +
                "cannot forbid in one section what it mandates in another, so the values the " +
                "clause closes a channel for are the ones a *public* event has no reason to " +
                "carry. On that reading: this library writes nothing into a payment layer — it " +
                "builds no BOLT-11 description and no wallet memo — logs nothing at all, and " +
                "emits only listings and public bids, neither of which has a field an order " +
                "id, an invoice or a preimage could reach. §12 item 5's `g` and `location` are " +
                "refused on read and unemittable on write. Every type holding one of §12 item " +
                "11's values redacts " +
                "it from its string representation, with a control per type asserting the full " +
                "hex is absent. The negative obligation is what is claimed here; the positive " +
                "one — that no future caller writes one somewhere — is not something a library " +
                "can assert about its embedder.",
        ),
        ConformanceItem(
            number = 10,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = setOf("14"),
            evidenceClasses = setOf(
                "dev.eryalabs.nenya.money.Msat",
                "dev.eryalabs.nenya.order.OrderState",
                "dev.eryalabs.nenya.seam.Wallet",
            ),
            notPerformed = emptySet(),
            note = "§14 is a list of things not to do, and this library does none of them: no " +
                "custody, because it never holds a balance and the wallet is the client's; no " +
                "escrow, because §11.4's seam is reserved and OrderState declares no `held`; no " +
                "arbitration role; no index server, no relay, no blob server and no mint, " +
                "because nothing here opens a socket; no hardcoded fee recipient, because the " +
                "recipient is a field of the signed term and nothing here supplies a default " +
                "for it; no identity field to write a real name into; no NIP-04, no NIP-90 " +
                "and no NIP-15 anywhere on the dependency budget; no zap receipt read as " +
                "evidence, because the only evidence is a hashed preimage; and no " +
                "floating-point money — Msat is a Long and no published signature in this " +
                "library mentions a Double or a Float.",
        ),
    ))

    /**
     * §17's item [number], or `null` for a number §17 does not have.
     *
     * `null` rather than a throw: a caller asking about item 11 of a ten-item list is asking a
     * question this surface has no answer to, and inventing one is the failure mode the whole
     * package exists to prevent.
     */
    public fun item(number: Int): ConformanceItem? = ITEMS.firstOrNull { it.number == number }

    /**
     * The whole surface as lines a machine can read and a human can paste into an issue.
     *
     * One line per §17 item — `NENYA-1 §17.<n> <status> sections=<…> classes=<…>
     * notPerformed=<…>` — then one line per constant in [NOT_PERFORMED_HERE], qualified by its
     * enum so `INVOICE_AMOUNT` and a future constant of the same name in another enum stay
     * distinguishable.
     *
     * Carries no identifier, no amount and no key: it is a statement about this library, not
     * about any order (§12 item 11).
     */
    public fun report(): String {
        val out = StringBuilder()
        for (item in ITEMS) {
            out.append(SPECIFICATION).append(" §17.").append(item.number)
                .append(' ').append(item.status.name)
                .append(" sections=").append(item.specSections.sorted().joinToString(",") { "§$it" })
                .append(" classes=").append(item.evidenceClasses.sorted().joinToString(","))
                .append(" notPerformed=").append(item.notPerformed.map(::qualify).sorted().joinToString(","))
                .append('\n')
        }
        for (constant in NOT_PERFORMED_HERE) {
            out.append(SPECIFICATION).append(" not-performed ").append(qualify(constant)).append('\n')
        }
        return out.toString()
    }

    /**
     * `PaymentCheck.INVOICE_AMOUNT` — the constant's own enum, then its name.
     *
     * The declaring class rather than `javaClass`: an enum constant with a body is an anonymous
     * subclass, and none of the three enums has one today. Reading the declaring class means
     * adding a body to one later does not silently change every qualified name this surface
     * publishes. Common Kotlin cannot read a declaring class, so the lookup is a platform
     * `expect` with a real `actual` on each target ([declaringEnumSimpleName]).
     */
    public fun qualify(constant: Enum<*>): String =
        "${declaringEnumSimpleName(constant)}.${constant.name}"

    private fun <T> unmodifiable(values: List<T>): Set<T> =
        readOnlySetOf(values)
}
