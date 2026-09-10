package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.VerifiedPayment
import java.time.Instant

/**
 * How §10.4's three steps failed, when they failed in the caller.
 *
 * §11.2's `released → disputed` row states two triggers on one line — "either hash mismatches, or
 * the blob does not decrypt" — and §10.4 makes three steps out of them. This library performs two
 * of the three itself (steps 1 and 3, in the delivery package) and never performs step 2, so the
 * failure that reaches the state machine is reported rather than recomputed here: the buyer's own
 * verification already ran, and what the transition function consumes is its outcome.
 *
 * A *success* is not on this enum. It is a `DeliveryEvidence`, which cannot be constructed except
 * by the two hash computations succeeding — §11.3 invariant 4 made structural rather than
 * enumerated.
 */
public enum class DeliveryFailure {

    /**
     * §10.4 step 1 — the SHA-256 of the served bytes is not `x`. The buyer has the wrong or a
     * tampered blob, MUST NOT decrypt it and MUST NOT settle.
     */
    SERVED_BYTES_HASH_MISMATCH,

    /**
     * §10.4 step 2 — AES-256-GCM authentication did not pass. Reported rather than computed:
     * this library performs no decryption at all, which every `DeliveryEvidence` records as
     * `DeliveryCheck.GCM_AUTHENTICATION`.
     */
    BLOB_DID_NOT_DECRYPT,

    /** §10.4 step 3 — the SHA-256 of the plaintext is not `ox`. §10.4: the order MUST dispute. */
    PLAINTEXT_BYTES_HASH_MISMATCH,
}

/**
 * Something that arrived, or happened, to an order.
 *
 * ### Message-shaped, never edge-shaped — and that is what makes the proofs mean anything
 *
 * A vocabulary of `CommittedToDisputed`, `ProposedToAccepted` and so on would turn the whole
 * `(state × event)` cross-product into a lookup of the transcribed §11.2 table and every proof
 * over it into a tautology: the function would be asserting the table against itself. So every
 * constant below names something that *arrives or happens* — a rumor kind and `type` (§7.4), a
 * clock crossing (§4.6), or a piece of evidence this library verified for itself (§9.2, §10.4) —
 * and no event names a pair of states. Several events can produce the same transition, and one
 * event produces different transitions from different states; both are facts about §11.2 rather
 * than accidents of this modelling.
 *
 * ### §11.2 does not fully supply this vocabulary, and the gap is recorded rather than papered
 *
 * Eleven of §11.2's fourteen rows name a trigger. Three say only "as above":
 * `accepted → cancelled / expired`, `committed → cancelled / expired / disputed` and
 * `awaiting_payment → cancelled / expired / disputed`. For `cancelled` and `expired` "above"
 * resolves — the `proposed` rows name both triggers. **For `disputed` it does not resolve at
 * all**, because the `committed` row is that token's first appearance in the table.
 *
 * So a trigger for the two pre-`paid` `→ disputed` edges is modelled here, and the obvious model
 * is forbidden: a `type=3` `status=disputed` would be a counterparty's assertion driving an order
 * to a **terminal** state, which is exactly what §11.3 invariant 5 refuses — only `cancelled` is
 * accepted from an assertion alone. It is modelled instead as [LocallyDisputed], a locally raised
 * non-wire event: this client's own user, or its own policy, declaring the order disputed. No new
 * wire message is invented either. §11.2 listing the edge as legal is a statement about the state
 * machine and not about what goes on the wire, so nothing here touches `spec/NENYA-1.md`.
 */
public sealed interface OrderEvent {

    /**
     * An event that arrived over the private channel (§7) — a rumor inside a gift wrap.
     *
     * Everything on one of these is hostile input, including [createdAt]. Nothing in
     * [OrderMachine] reads that field, and a test asserts the consequence: a rumor claiming a
     * `created_at` far past `deliver_by` fires no deadline (§4.6).
     */
    public sealed interface Rumor : OrderEvent {

        /**
         * The `created_at` the rumor carried, or `null` if the caller did not resolve one.
         *
         * **Carried so that §4.6's rule has a subject, and read by nothing.** §7.1 randomises
         * gift-wrap timestamps up to two days into the past on purpose, and §4.6 says every
         * deadline MUST be evaluated against the injected clock and never against a
         * counterparty's `created_at`. A field nothing reads is the only way to write a test
         * that would fail the day something starts reading it — the same shape as the seam
         * package's `LyingWallet`.
         */
        public val createdAt: Instant?
    }

    /**
     * An event this implementation raised itself: a clock crossing, or evidence it verified.
     *
     * Nothing here arrives from a counterparty, which is why these are the only events that may
     * move an order into `paid`, `settled` or a pre-`paid` `disputed` (§9.1, §11.3).
     */
    public sealed interface Local : OrderEvent

    /**
     * `kind:16` `type=1` — the buyer's order proposal (§7.5).
     *
     * The genesis event, and the one event that opens an order rather than advancing one: §11.2's
     * first row has no from-state. [OrderMachine.open] consumes it; [OrderMachine.on] refuses it
     * from every state, because an order that already exists cannot be proposed again — §7.6 says
     * a counter-proposal is a **new** `type=1` with a **new** order id.
     *
     * It carries no [Party]: §7.4's table fixes the sender as the buyer, and there is nothing for
     * a sender field to discriminate.
     */
    public class Proposal(

        /** The terms the proposal states. A `FeeTerm.Absent` term is §8.1's zero-fee proposal. */
        public val terms: OrderTerms,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:16` `type=3` — a status update (§7.4), carrying a `["status", ...]` token from §11.1.
     *
     * **Announces; does not decide** (§11.3 invariant 5). Exactly two statuses can move an order,
     * and both are named in §11.2's own trigger column: `accepted` from the provider's key with
     * byte-identical terms (§7.6), and `cancelled` from either party before `paid`. Every other
     * status — `paid`, `settled`, `disputed`, `released`, an unrecognised token that read as
     * [OrderState.UNKNOWN] — changes nothing, from any key, in any state.
     *
     * [status] is an [OrderState] and not a `String` because the token was already read by
     * [OrderStatusCodec], which is §11.1's codec and no other: a status-shaped `String` reaching
     * a transition function is the shape STOP RULE 12 exists to prevent, and the structural sweep
     * over this package asserts it cannot.
     */
    public class StatusUpdate(

        /** The state the update *claims*, as [OrderStatusCodec] read it. A claim, never a fact. */
        public val status: OrderState,

        /** Whose key sealed it (§7.2), as the layer above resolved it. */
        public val from: Party,

        /**
         * The terms the update asserts, for the `status=accepted` case (§7.6), or `null` when it
         * asserted none.
         *
         * §7.6: an acceptance MUST carry terms byte-identical to the proposal's, and one carrying
         * different terms is a counter-proposal. An acceptance carrying **no** terms is therefore
         * not an acceptance either, and is refused rather than assumed to agree.
         */
        public val assertedTerms: OrderTerms? = null,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:16` `type=5` — the provider's delivery commitment (§10.1).
     *
     * §11.2's trigger for `accepted → committed`: a sealed `type=5` from the provider with
     * well-formed `x` and `ox`. Well-formedness is `DeliverableCommitment`'s own `init` — a
     * malformed one cannot be constructed — so what is left for the transition to check is the
     * sender.
     */
    public class DeliveryCommitted(

        /** §10.1's four values, already read out of the tags by the caller. */
        public val commitment: DeliverableCommitment,

        /** Whose key sealed it. §11.2: the provider's, and nobody else's. */
        public val from: Party,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:16` `type=2` — the payment requests received for this order (§8.6), as a set of the
     * payee roles that sent a valid one.
     *
     * Plural because §11.2's trigger is plural: "one valid `type=2` per **required** payee". The
     * per-invoice checks §11.2 lists on that row — amount `== price_msat`, fee amount
     * `== fee_msat`, sealed by the fee recipient (§8.7), fee term matching (§8.4) — need the
     * BOLT-11 parser, the tag codec and gift-wrap machinery, none of which exists yet, so what
     * reaches here is which payees the caller accepted a request from. That narrowing is not
     * silently absorbed: it is the same set of unperformed checks every `VerifiedPayment` already
     * publishes, and the order carries them forward (§17).
     *
     * §8.5 is why this event exists as its own thing rather than folding into the receipt: the
     * fee **payment request** is accepted as part of `committed → awaiting_payment` and nowhere
     * earlier, while the fee **payment** may not happen before `awaiting_payment` at all. A rule
     * that rejected the fee `type=2` while the order was still `committed` would make
     * `awaiting_payment` unreachable for every fee-bearing order — §8.5 says so in those words,
     * and §11.3 invariant 2 says a test that forbids the request rather than the payment is
     * testing the wrong thing.
     */
    public class PaymentRequestsReceived(

        /**
         * The payee roles a valid `type=2` was received from.
         *
         * MUST equal the required set (§9.2, §11.2): a missing one leaves the order `committed`,
         * and a surplus one is refused — §8.6 and §8.3 both say a fee request for an expected
         * amount of `0` MUST be rejected, and §8.1 says every fee invoice for an order proposed
         * with no `fee` tag MUST be refused.
         */
        public val payees: Set<Payee>,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:15` — the provider's release of the deliverable (§10.3).
     *
     * §11.2's trigger for `paid → released`: a `kind:15` from the provider whose `order` tag names
     * **this** order, carrying key and nonce, with `x`, `ox`, `size` and `file-type`
     * byte-identical to the commitment. The `order`-tag routing is §10.3's *binding* and belongs
     * to whatever holds the open orders; the four-operand identity check is
     * `DeliverableCommitment.checkReleaseIdentity`, and §10.3 says any mismatch in any of the
     * four MUST move the order to `disputed` — which is a legal `paid → disputed` pair of §11.2
     * reached by a trigger §10.3 states rather than §11.2's own row.
     *
     * The key and nonce are deliberately not here: this library does not decrypt, and holding
     * key material in a type with no use for it would put it one careless `toString` from a log
     * (§12 item 11).
     */
    public class DeliverableReleased(

        /** §10.3's four values, already read out of the tags by the caller. */
        public val release: DeliverableRelease,

        /** Whose key sealed it. §11.2: the provider's. */
        public val from: Party,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:16` `type=6` — a private bid (§6.1). **Advances nothing, in any state, from any key.**
     *
     * §11.2 names it as one of two message classes that appear in no row of the table: it
     * precedes the existence of an order id, so there is no order for it to advance, and §7.4
     * says it is the only `type` that carries no `order` tag at all. An implementation MUST NOT
     * treat it as an acceptance, a cancellation, or evidence of anything.
     *
     * It is modelled here *because* it advances nothing. A vocabulary containing only events that
     * move an order cannot express the rule that these two do not.
     */
    public class PrivateBid(

        /** Whose key sealed it. §11.2: from any key, and it changes nothing. */
        public val from: Party,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:14` — free-text chat inside the order thread (§7.4).
     * **Advances nothing, in any state, from any key** (§11.2).
     *
     * §7.6 names it specifically: an implementation MUST NOT treat a chat message as acceptance.
     * It carries no terms and moves no state, which is also why §7.4 exempts it from the `order`
     * tag requirement and forbids deriving anything from that tag's presence or absence.
     */
    public class ChatMessage(

        /** Whose key sealed it. §11.2: from any key, and it changes nothing. */
        public val from: Party,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * `kind:16` `type=4` — GammaMarkets' shipping update. **Reserved; advances nothing.**
     *
     * §7.4: Nenya v1 MUST NOT emit `type=4` and MUST ignore it on read, and it advances no state
     * (§11.2). Modelled for the same reason as [PrivateBid]: a rule that a message class does
     * nothing needs that class to exist in order to be executable.
     */
    public class ShippingUpdate(

        /** Whose key sealed it. It changes nothing whoever sent it. */
        public val from: Party,

        override val createdAt: Instant? = null,
    ) : Rumor

    /**
     * The receipts (`kind:17`) this library has **verified for itself**, per §9.2.
     *
     * Local rather than a [Rumor] on purpose. A `kind:17` arrives over the wire, but what this
     * event carries is not the rumor: it is the result of `VerifiedPayment.verify`, which cannot
     * be constructed except from a preimage whose SHA-256 this library computed and compared to
     * the payment hash (§9.1, §9.2 checks 2 and 3). A wallet's `CLAIMS_SETTLED`, a counterparty's
     * `status=paid` and a relay's acceptance are none of them constructible into one, which is
     * what makes `awaiting_payment → paid` unreachable from an assertion.
     *
     * §17's honesty rule crosses this layer with the evidence: every `VerifiedPayment` says that
     * the invoice's **amount** was never checked (§9.2 check 4), and [Order] carries that record
     * forward, so an order that reached `paid` on check-2/3-only evidence reports itself
     * amount-unverified. Honouring §17 one layer down and dropping it one layer up is the same
     * lie with an extra step.
     */
    public class ReceiptsVerified(

        /**
         * One verified receipt per required payee (§9.2, §11.2).
         *
         * Must cover exactly the required set: a missing one leaves the order `awaiting_payment`
         * — more receipts may still arrive — and a surplus one is refused, since §8.6 says a fee
         * receipt whose expected amount is `0` MUST be rejected and §8.1 says the same of every
         * fee receipt for an order proposed with no `fee` tag.
         */
        public val receipts: Set<VerifiedPayment>,
    ) : Local

    /**
     * The buyer's own §10.4 verification **succeeded** — both hashes computed here.
     *
     * §11.3 invariant 4: `settled` is reachable only after the buyer's own hash computation,
     * never on the provider's assertion. `DeliveryEvidence` is unforgeable through the published
     * API and cannot be obtained except by passing §10.4 step 1 and then step 3, in that order,
     * so this event is the invariant rather than a claim about it.
     */
    public class DeliveryVerified(

        /** The evidence, whose commitment is checked against the order's own (§10.3, §10.4). */
        public val evidence: DeliveryEvidence,
    ) : Local

    /**
     * The buyer's own §10.4 verification **failed**, at the step [failure] names.
     *
     * §11.2's `released → disputed` row. Local, because §10.4 is the buyer's own computation:
     * nothing a counterparty says can produce this event, and nothing a counterparty says can
     * prevent it.
     */
    public class DeliveryRefused(

        /** Which of §10.4's three steps failed. */
        public val failure: DeliveryFailure,
    ) : Local

    /**
     * This implementation re-read its injected clock (§4.6).
     *
     * The one event behind every deadline row of §11.2, and it carries **no time**: the time comes
     * from the `NenyaClock` injected into [OrderMachine], never from a parameter and never from
     * an ambient source. That is the whole of §4.6 made structural — a counterparty's `created_at`
     * cannot reach a deadline comparison because there is no parameter for it to arrive on.
     *
     * Which transition it produces depends on the state and on which deadline the terms carry:
     * `→ expired` before `paid` when the proposal's `expiration` has passed, and `paid → disputed`
     * when `deliver_by` has. §11.2 is explicit that these are two distinct terms and that §7.5
     * keeps them from inverting.
     */
    public data object ClockChecked : Local

    /**
     * This client's own user or its own policy declares the order disputed — a **non-wire** event.
     *
     * §11.2 states `committed → disputed` and `awaiting_payment → disputed` as legal and names no
     * trigger for either: both rows say "as above", and `disputed` makes its first appearance in
     * the `committed` row, so there is nothing above for it to resolve to. That gap is recorded
     * here rather than filled with a `type=3` `status=disputed`, which §11.3 invariant 5 forbids —
     * a counterparty-asserted dispute driving an order to a terminal state is precisely the bug
     * that reading would ship, and it would ship green under an invariant-5 test exercising only
     * `paid` and `settled`.
     *
     * Legal from `committed` and `awaiting_payment` only. The two later `→ disputed` edges have
     * triggers the specification does name — the release deadline (§11.2) and §10.3's and §10.4's
     * failures — so they are reached by [ClockChecked], [DeliverableReleased] and
     * [DeliveryRefused], not by this.
     */
    public data object LocallyDisputed : Local
}
