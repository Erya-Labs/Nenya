package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.delivery.DeliveryException
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.applicableChecks
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer

/**
 * Why a `(state, event)` pair was refused.
 *
 * §11.2: "Every transition not listed is **illegal** and MUST be refused", and the transition
 * function SHOULD be total — "every (state, event) pair produces either a new state or an
 * explicit rejection — so that 'we forgot a case' cannot become 'we fell through to the happy
 * path'". These constants are that explicitness. The reason is part of the API and not merely
 * diagnostic text, exactly as `PaymentRejection` and `DeliveryRejection` are: a client showing
 * its user why an order did not move needs to distinguish "that key may not do that" from "that
 * message decides nothing" from "the deadline has not passed yet".
 */
public enum class TransitionRejection {

    /**
     * The order is in one of §11.1's four terminal states. §11.3 invariant 3: there is no path
     * from any terminal state to any other state — including back to itself, and including on
     * evidence this library verified.
     */
    STATE_IS_TERMINAL,

    /**
     * The order is the [OrderState.UNKNOWN] sink (§11.1), which appears in neither column of
     * §11.2 and therefore originates no transition.
     *
     * Distinct from [STATE_IS_TERMINAL] on purpose. Being unreachable *as* a state is a different
     * fact from being an agreed terminal outcome, and reporting the two the same way would tell a
     * user that a garbled status update had settled or cancelled their order.
     */
    STATE_IS_UNKNOWN,

    /**
     * §11.2's two message classes that "MUST advance no state, in any state, from any key" — a
     * `kind:16` `type=6` private bid and a `kind:14` chat message — plus §7.4's reserved
     * `type=4`, which v1 MUST ignore on read.
     */
    EVENT_ADVANCES_NO_STATE,

    /**
     * §11.3 invariant 5 — a `type=3` status update announces; it does not decide. Only
     * `cancelled` can move an order through one, and everything else, from any key, in any state,
     * changes nothing.
     *
     * `accepted` is refused by [ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE] instead, and the two are
     * kept apart on purpose: this one says the status decides nothing anywhere, and that one says
     * the status decides something *and this is not the message that carries the decision*.
     */
    STATUS_UPDATE_DECIDES_NOTHING,

    /**
     * A `type=3` `status=accepted` was offered as an acceptance. It is not one, in any state, from
     * any key, carrying any terms.
     *
     * §7.6 fixes acceptance as two comparisons over things a caller-assembled
     * [OrderEvent.StatusUpdate] does not carry: the **seal's** pubkey against the provider key the
     * caller resolved independently of both messages, and §7.6's four terms **byte-identically**
     * against the proposal's — the `fee` pair including its recipient, which
     * [OrderTerms.namesTheSameDealAs] does not compare. An order advanced on that event had
     * therefore had neither made, which is gap G3: a `type=3` naming the same basis points and a
     * different fee recipient was a counter-proposal in `OrderProposal.accepts` and an acceptance
     * here.
     *
     * Both comparisons live in `OrderProposal.accepts`, and its answer reaches §11.2 as
     * [OrderEvent.AcceptanceReceived] (decision J). This refusal is what makes the old door shut
     * rather than merely narrow: no shape of a `StatusUpdate` advances an order to `accepted`.
     */
    ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE,

    /**
     * An [OrderEvent.AcceptanceReceived] whose checked acceptance names a **different** order.
     *
     * The one question `OrderProposal.accepts` cannot answer, for the reason
     * [RECEIPT_FOR_ANOTHER_ORDER] and [RELEASE_FOR_ANOTHER_ORDER] exist two and three rows later:
     * `accepts` compared the `type=3`'s `order` tag against **the proposal it was called on**, and
     * the `Acceptance.Accepted` it returns is then an ordinary value a caller may offer to any
     * order. Two orders between the same two parties differ in nothing else a rule here reads.
     */
    ACCEPTANCE_FOR_ANOTHER_ORDER,

    /**
     * A stored `type=2` record in an [OrderEvent.PaymentRequestsReceived] names a **different**
     * order.
     *
     * §9.2 check 1 keys the store by `(order, payee)` and §7.4 makes the order id the handle every
     * message in a thread carries, so a record carries the id its `type=2` named. Without this
     * comparison a request accepted for another order at the same price satisfies every other rule
     * on this row, because every other rule is about the payee and the sealing key and neither is
     * order-specific — and the order would enter `awaiting_payment` against an invoice §9.2 check 1
     * will never find for it.
     */
    PAYMENT_REQUEST_FOR_ANOTHER_ORDER,

    /**
     * A `payee=provider` record was sealed by a key that is not the one this order's acceptance was
     * checked against (§8.6 revision `1.5`, decision I).
     *
     * `AcceptedPaymentRequest.accept` makes this comparison too — against
     * `Acceptance.Accepted.provider`, the key **that acceptance** was checked against. That is the
     * same rule about a different subject, and the gap between the two is the whole of this
     * constant: a caller that resolved a stranger as the provider, was refused at `accepts`, and
     * then resolved that stranger for a *second* `accepts` call over a `type=3` the stranger really
     * did seal, holds a well-formed `Acceptance.Accepted` for this order id and a well-formed
     * provider record under it. Every check T24 makes passes on it. What does not pass is the
     * comparison against the key **this order** recorded when it advanced to `accepted`.
     *
     * An order holding no provider key at all is refused here as well, and that is the fail-closed
     * direction rather than an oversight: §11.2 reaches `committed` only through
     * `proposed → accepted`, so a `committed` order with no recorded provider is a state this
     * library cannot produce, and comparing a real key against its absence must not read as a match.
     */
    PROVIDER_REQUEST_NOT_FROM_PROVIDER,

    /**
     * A `cancelled` status update arrived at or after `paid`. §11.3 invariant 5: cancellation is
     * accepted from a counterparty's assertion alone, and **only before `paid`** — after money
     * has moved, the terminal an abandoned order reaches is `disputed` (§11.2, §11.4), which
     * carries the fact that a payment happened.
     */
    CANCELLATION_TOO_LATE,

    /**
     * The event is legal somewhere in §11.2 and not from this state — a `type=5` at `proposed`,
     * a receipt at `committed` (§8.5, §11.3 invariant 2), a release before `paid`.
     */
    WRONG_STATE_FOR_EVENT,

    /**
     * The key it came from may not send this. §7.4 fixes the commitment and the release to the
     * provider, and §11.2 accepts cancellation from "either party" — which §8.5 makes clear the
     * fee recipient is not.
     *
     * §7.6's own sender rule for an acceptance is **not** here: it is made over a real seal, one
     * layer down, by `OrderProposal.accepts`, and the events that reach this file carry a [Party]
     * the caller labelled rather than a key anything compared.
     */
    WRONG_SENDER,

    /**
     * Two stored `type=2` records in one set for the same payee (§8.6 revision `1.5`).
     *
     * §8.6 is one invoice per payee, so a second record for a role is not a duplicate to be
     * collapsed: one of the two bills for something else, and §9.2 check 1 compares a receipt
     * against *the* stored request. It is the same rule [RECEIPT_PAYEE_DUPLICATED] makes one row
     * later, and it is needed here for the first time because the trigger became a set of records
     * rather than a set of payee roles — a `Set<Payee>` could not express the shape at all.
     *
     * `AcceptedPaymentRequest.accept` refuses it within **one** store
     * ([dev.eryalabs.nenya.settlement.SettlementRejection.REQUEST_ALREADY_STORED]), and that is not
     * this rule: the event is a set the caller assembles, and a caller holding records from two
     * stores — or one record it kept after the store refused its replacement — assembles this
     * without either door noticing. Deduplicating to the payee here, which is what the required-set
     * arithmetic below would otherwise do on its own, would let it through silently.
     */
    PAYMENT_REQUEST_PAYEE_DUPLICATED,

    /** §11.2 — a required payee (§9.2) sent no valid `type=2`. The order stays `committed`. */
    PAYMENT_REQUESTS_INCOMPLETE,

    /**
     * A `type=2` arrived from a payee no receipt is required from. §8.6: when the expected fee is
     * `0`, "**no** fee payment request exists at all and any that arrives MUST be rejected", and
     * §8.1 says every fee invoice for an order proposed with no `fee` tag MUST be refused.
     */
    PAYMENT_REQUEST_NOT_REQUIRED,

    /** §11.2 — not every required receipt has verified yet. The order stays `awaiting_payment`. */
    RECEIPTS_INCOMPLETE,

    /** A verified receipt for a payee that is not required (§8.1, §8.3, §8.6, §9.2). */
    RECEIPT_NOT_REQUIRED,

    /**
     * A verified receipt whose `["order", ...]` tag names a **different** order from this one.
     *
     * §9.2 check 1 keys the stored payment request by `(order, payee)` and §7.4 makes the order id
     * the handle every message in a thread carries, so a settlement result carries the id the
     * `kind:17` named. Without this comparison a receipt verified for another order at the same
     * price is indistinguishable from this one's — every other check here passes on it, because
     * every other check is about the payee and the payment hash and neither is order-specific.
     *
     * Checked **first**, before the duplicate, surplus and completeness rules, for the reason §9.2
     * gives check 1 first place: a caller whose evidence belongs to another thread is told that,
     * rather than being told its receipt set is incomplete — which is true of the wrong order and
     * useless for a misrouted one.
     */
    RECEIPT_FOR_ANOTHER_ORDER,

    /**
     * A `kind:15` release whose `["order", ...]` tag names a **different** order from this one.
     *
     * §10.3's binding, re-made here for the reason [RECEIPT_FOR_ANOTHER_ORDER] exists one row
     * earlier: "Hash equality is a *check*, not the binding: an implementation handling two
     * concurrent orders with the same provider routes on the `order` tag and then verifies the
     * hashes." `DeliverableReleaseMessage.asOrderEvent` makes that comparison against the order it
     * was handed — but the event it returns is an ordinary value a caller may then offer to any
     * order, and two orders with the same provider can carry the same commitment, so every other
     * check on this row would pass on a misrouted release. Without this one the order would record
     * `DeliveryCheck.RELEASE_ORDER_BINDING` for a binding checked against a different thread.
     *
     * An event built from a bare `DeliverableRelease` carries no id, and is not refused here: it
     * makes no claim about which order it is for, and it carries no performed check either.
     */
    RELEASE_FOR_ANOTHER_ORDER,

    /**
     * Two receipts for the same payee. §8.6 is one invoice per payee, so a second receipt for a
     * role is not a duplicate to be collapsed: one of the two is for something else.
     */
    RECEIPT_PAYEE_DUPLICATED,

    /**
     * Two receipts in one set proving the **same** payment. §8.6's non-custodial rule: "The buyer
     * MUST pay the provider and the fee recipient with two separate payments to two separate
     * invoices", and an implementation MUST NOT honour a single invoice covering `total_msat`
     * that some party then splits.
     *
     * Two distinct invoices cannot share a payment hash — a preimage is drawn per invoice — so a
     * receipt set in which two payees present the same hash is one payment claimed twice. §9.2
     * check 1 is performed one layer down now, against the stored `type=2` for each payee, and it
     * does **not** subsume this: check 1 compares each receipt against its own stored invoice and
     * asks nothing about the other receipt in the set, so two genuinely distinct invoices
     * presented with one shared payment hash pass check 1 twice. Catching that needs check 3's
     * provenance — the hash parsed out of each invoice — which nothing here does yet. This check
     * needs only arithmetic already done here.
     */
    RECEIPT_PAYMENT_DUPLICATED,

    /**
     * Every required receipt verified, and at least one §9.2 check that applies to one of them was
     * never performed. The order stays `awaiting_payment`.
     *
     * §9.2 says an implementation "MUST perform **all**" of its checks before treating a payment as
     * made; §11.1 and §11.2's row define `paid` as receipts "verified per §9.2"; §17 item 6 says
     * the same. Partial evidence is therefore not weak evidence for `paid` — it is not the thing
     * §11.2 names — and this library fails closed on it rather than advancing with a record of what
     * it skipped. No honest priced trade can reach `paid` until the missing checks are implemented,
     * which is the intended cost: an order wrongly marked `paid` releases a deliverable against a
     * payment nobody checked the amount of.
     *
     * Raised **after** every other refusal in `awaiting_payment → paid`, so a caller whose receipt
     * set is incomplete, misrouted or duplicated is still told that first. The refusal carries
     * [OrderOutcome.Refused.ChecksNotPerformed.missing], which names the checks by rule rather than
     * by identifier (§12 item 11).
     */
    PAYMENT_CHECKS_NOT_PERFORMED,

    /**
     * A release or a delivery-evidence event reached an order holding no commitment. Unreachable
     * through §11.2 — `paid` and `released` are downstream of `accepted → committed` — and named
     * rather than asserted, because §11.2's whole point is that a forgotten case must not fall
     * through to the happy path.
     */
    NO_COMMITMENT_TO_CHECK_AGAINST,

    /**
     * §10.4's evidence was produced against a different commitment from this order's. Hash
     * equality is a *check* and not the binding (§10.3): an implementation handling two
     * concurrent orders with the same provider routes on the `order` tag first, and this is the
     * refusal for evidence that arrived on the wrong thread.
     */
    EVIDENCE_IS_FOR_ANOTHER_COMMITMENT,

    /** The injected clock was read and the deadline has not been reached (§4.6, §11.2). */
    DEADLINE_NOT_PASSED,

    /**
     * There is no deadline to compare against: terms carrying no `expiration` before `paid`; or
     * — at `paid` — no `deliver_by` and no clock reading taken when the order became `paid`, so
     * §11.2's release timeout has nothing to run from; or — at `released` — no clock reading taken
     * at release, none taken at `paid` **and** no `deliver_by`, so §11.2's verification deadline
     * has nothing to run from either.
     *
     * §11.2 requires that last case to be surfaced to the user as *having no deadline* rather than
     * as pending, which is why it is this constant and not [DEADLINE_NOT_PASSED]: the two look
     * alike from the outside — the order did not move — and they are opposite facts. One says "not
     * yet"; this one says "never, on the readings this library has".
     */
    NO_DEADLINE_TO_CHECK,

    /**
     * The injected [NenyaClock] answered [SeamAnswer.Unavailable] — which is what the fail-closed
     * default does. §4.6 makes the injected clock authoritative and there is no fallback: a
     * library that reached for an ambient clock here would evaluate a deadline against something
     * the client never chose.
     */
    CLOCK_UNAVAILABLE,

    /**
     * The injected [NenyaClock] answered a **negative** reading — a time before 1970. §4.3 fixes
     * every timestamp as a non-negative integer, so such a clock is broken rather than merely
     * unusual, and this library fails closed on it exactly as it does on an unavailable one:
     * nothing expires, nothing is disputed and no `paidAt` is recorded against a time no deadline
     * can honestly be compared with. Distinct from [CLOCK_UNAVAILABLE] because the remedy
     * differs — that one means "inject a clock", this one means "the clock injected is wrong".
     */
    CLOCK_READING_BEFORE_EPOCH,

    /**
     * A `type=1` reached an order that already exists. §11.2's genesis row is the only one with
     * no from-state, and §7.6 says a counter-proposal is a **new** proposal with a **new** order
     * id — never a second set of terms on this one.
     */
    ORDER_ALREADY_OPEN,
}

/**
 * Why an order is `disputed` — recorded because §14 item 3 is explicit that `disputed` resolves
 * nothing, and what it does instead is stop the order pretending to be live.
 *
 * A user looking at a disputed order needs to know which of five quite different things happened,
 * and only one of them is even about the deliverable. Typed rather than a string for the reason
 * every other capability record in this library is typed: a caller branches on it.
 */
public enum class DisputeGround {

    /**
     * §10.3 — the release's `file-type`, `x`, `ox` or `size` was not byte-identical to the
     * commitment's. "Any mismatch in any of the four MUST move the order to `disputed`."
     */
    RELEASE_DOES_NOT_MATCH_COMMITMENT,

    /**
     * §11.2 — the injected clock passed `deliver_by` from the accepted terms (or, where the terms
     * carry none, this implementation's own release timeout) with no release received. The one
     * mechanism v1 has against §11.4's residual risk.
     */
    RELEASE_DEADLINE_PASSED,

    /**
     * §11.2 (revision `1.6`) — the injected clock passed the **verification deadline** with the
     * buyer's own §10.4 verification neither succeeded nor failed.
     *
     * The deadline runs from the clock reading taken when the order entered `released`, or — where
     * none was taken — from the reading taken at `paid`, or from `deliver_by`; plus this
     * implementation's verification window. §11.2 makes that window local rather than a wire term,
     * because at `released` the only outstanding act is the buyer's own computation.
     *
     * Distinct from [RELEASE_DEADLINE_PASSED], and the distinction is the point rather than
     * bookkeeping: that one says the provider never released a key, and is the one mechanism v1
     * has against §11.4's residual risk. This one says the key was released and the bytes never
     * verified — because the blob was served at the wrong length or over the download bound (§10.4
     * says neither is a hash mismatch), because the URL was dead, or because the buyer never
     * looked. A client that showed a user the first message for the second case would be telling
     * them their provider took the money and ran.
     */
    VERIFICATION_DEADLINE_PASSED,

    /** §10.4 step 1 — the served bytes did not hash to `x`. */
    SERVED_BYTES_HASH_MISMATCH,

    /** §10.4 step 2 — GCM authentication did not pass in the caller. */
    BLOB_DID_NOT_DECRYPT,

    /** §10.4 step 3 — the plaintext did not hash to `ox`. */
    PLAINTEXT_BYTES_HASH_MISMATCH,

    /**
     * `OrderEvent.LocallyDisputed` — this client's own user or policy, before `paid`. §11.2
     * states the `committed → disputed` and `awaiting_payment → disputed` edges and names no
     * trigger for either; see that event's note on why the missing trigger is modelled locally
     * rather than as a counterparty's assertion.
     */
    RAISED_LOCALLY,
}

/**
 * The result of offering an event to an order: a new order, or a named refusal.
 *
 * §11.2 asks for exactly these two outcomes and no third. There is no "unchanged, no comment"
 * and no `null`: [Refused] always carries a [Refused.reason], which is what stops a forgotten
 * case becoming a fall-through to the happy path.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `VerifiedPayment` and `DeliveryEvidence`: an interface has no
 * constructor to synthesise an accessor for, so `Class.getConstructors()` on it is empty by
 * construction. A class with a `private constructor` and a companion factory emits a **public
 * synthetic** constructor carrying a trailing `DefaultConstructorMarker` that a Java client can
 * call with a `null` marker; a `data class` with a private primary constructor is still reachable
 * through the generated `copy()`. Neither is good enough for a type whose whole job is to say
 * that an order advanced.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: the
 * implementing classes compile to package-private classes with public constructors, so a client
 * that declares itself into `dev.eryalabs.nenya.order` on the same classloader can still reach
 * them. Nenya protects its user against counterparties and relays, not against the client
 * embedding it.
 */
public sealed interface OrderOutcome {

    /**
     * The order as it stands after the event — the new order for [Advanced], and the **unchanged**
     * one for [Refused].
     */
    public val order: Order

    /** The event was legal from this state and the order moved (§11.2). */
    public sealed interface Advanced : OrderOutcome

    /** The event was refused. [order] is unchanged; [reason] says why. */
    public sealed interface Refused : OrderOutcome {

        /** Which of §11.2's refusals this is. Branch on this, never on [detail]. */
        public val reason: TransitionRejection

        /**
         * A human-readable explanation naming the rule, authored in this library.
         *
         * Never echoes an input: §12 item 11 keeps order ids, preimages and key material out of
         * logs and crash reports, and a rejection message is a log line waiting to happen. It may
         * name a *rule* and a *role*; it may never name a byte, an amount or a deadline.
         */
        public val detail: String

        /**
         * [TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED], with the checks that were owed and
         * not done (decision B).
         *
         * A sub-interface rather than a field on every refusal, because [missing] is meaningful
         * for this one refusal and empty-and-misleading on the other nineteen. A caller branches
         * on the type — `is OrderOutcome.Refused.ChecksNotPerformed` — and gets the set, or
         * branches on [reason] and gets the constant; neither reading can be had by accident.
         *
         * What a client does with it is the point of publishing it: the set names which of §9.2's
         * checks this library cannot yet perform, so a client can say "the invoice amount was
         * never checked" rather than "the order did not move".
         */
        public sealed interface ChecksNotPerformed : Refused {

            /**
             * The §9.2 checks that apply to the receipts offered and were performed by nobody —
             * the union, over every receipt in the set, of what applies to that payee minus what
             * that receipt recorded as performed.
             *
             * Never empty: an empty [missing] is exactly the case that advances instead.
             *
             * **Computed from what *applies*, never from a receipt's own
             * `checksNotPerformedHere`.** The two agree today and are different questions. A
             * record that wrongly subtracted a check — a settlement path that came to claim
             * §9.2 check 4 without a parser behind it — would make its own omission invisible to a
             * refusal that trusted it, which is §9.1 one layer up: what a value *asserts* about
             * itself is not evidence. Only "applicable minus performed" fails closed.
             *
             * The names are §9.2's rules and not identifiers, so §12 item 11 permits them here and
             * in [detail].
             */
            public val missing: Set<PaymentCheck>
        }
    }
}

/**
 * One order, as this implementation understands it: a §11.1 state plus everything §11.2 needs to
 * decide the next one.
 *
 * ### It cannot be constructed with a state
 *
 * There is no way for an embedding client to make an order that is already `paid`. The only doors
 * in are [OrderMachine.open], which produces `proposed` and nothing else, and [OrderMachine.on],
 * which produces a new order only from a transition §11.2 lists. That is the same reason
 * `VerifiedPayment` is a sealed interface with a private implementation, one layer up: a state
 * anyone can assert is exactly as good as a `Boolean isPaid`.
 *
 * ### It carries §17's capability record forward, and that is not decoration
 *
 * Every settlement result says that §9.2 check 3's *provenance* was not checked here: the payment
 * hash the preimage was compared against is a parameter every entry point takes, so a caller that
 * hands in the SHA-256 of a preimage it chose gets a true comparison about an invoice nobody
 * issued. §17 says an implementation MUST NOT report unverified things as verified, and honouring
 * that one layer down while dropping it one layer up is the same lie with an extra step — so
 * [paymentChecksNotPerformedHere] carries it, and [deliveryChecksNotPerformedHere] does the same
 * for §10.
 *
 * **No order reaches `paid` on partial payment evidence any more.** It used to: a provider who
 * sent a `type=2` for ten times `price_msat` is caught by §9.2 check 4, `Settlement.verify`
 * performs check 4 now and did not then, and the order advanced anyway with the omission written
 * into its record. The human's decision B closed that — `awaiting_payment → paid` now refuses
 * ([TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED]) while any applicable §9.2 check is
 * unperformed — so the record's job here is narrower than it was and its **emptiness** is now the
 * invariant rather than its contents. See [paymentChecksNotPerformedHere].
 *
 * The delivery record is unchanged and is not subject to that rule: §10's obligations are not
 * §9.2's, and decision B is about what counts as evidence of payment.
 *
 * The record is the **settlement results'** and not their `VerifiedPayment`s'. That distinction
 * is the whole of the fix: check 1 and check 6 are performed on the settlement path, a
 * `VerifiedPayment` knows of neither, and an order that read through to one would under-report
 * every check the evidence behind it actually passed. Under-reporting is the safe direction of a
 * §17 error and it is still a false statement about what this library did.
 *
 * ### Never branch on ordinal
 *
 * §11.4: escrow would enter as a `held` state between `accepted` and `committed`, and an
 * implementation MUST NOT write transition logic enumerating "everything after `accepted`" in a
 * way a new state would silently join. Nothing in this file compares states by ordinal or by
 * range; every decision is an explicit `when` over named constants, and the four terminals are
 * named individually rather than taken as a suffix of the enum.
 */
public sealed interface Order {

    /**
     * §7.4's 32-byte order id this order was opened under — the handle every `kind:15`, `kind:16`
     * and `kind:17` in its thread carries.
     *
     * Recorded because the evidence that moves an order carries one too: a `Settlement.Evidenced`
     * names the order its `kind:17` claimed to settle, and without an id here there is nothing to
     * compare it against. A receipt verified for another order at the same price would otherwise
     * satisfy every check `OrderMachine.receipts` makes.
     *
     * **It is never printed.** §12 item 11 names order ids in one sentence with key material and
     * preimages as values that MUST NOT appear in a log, a crash report, or the string
     * representation of anything this library exposes, and [OrderId.toString] redacts itself —
     * but [toString] here names no id at all, and a test asserts the hex is absent.
     */
    public val id: OrderId

    /** Where the order is (§11.1). Never [OrderState.UNKNOWN] for an order this library opened. */
    public val state: OrderState

    /** The terms it was opened with (§7.5). Immutable: §7.6 makes altered terms a new order. */
    public val terms: OrderTerms

    /**
     * The provider's key this order's acceptance was **checked against** (§7.6), in §4.3's
     * canonical lowercase — or `null` at `proposed`, in the `unknown` sink, and on the
     * `proposed → cancelled` and `proposed → expired` rows, where no acceptance ever arrived.
     *
     * Recorded, rather than left with the caller, because the next message in the order depends on
     * it: §8.6 (revision `1.5`) requires a `payee=provider` `type=2` to arrive under "the same key
     * the acceptance for that order was checked against", and that comparison is made here, at
     * `committed → awaiting_payment`, against **this** field
     * ([TransitionRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER], decision I). A caller re-supplying
     * the key at that point could supply a different one, and the two checks would be about two
     * different providers while looking like one rule.
     *
     * It is the key the **caller resolved** and this library compared a seal against — never one
     * this library discovered. §7.6 names the two places the answer really comes from, the author
     * of the offer or the bidder the buyer chose, and §13 leaves the resolution where only the
     * caller can do it. See `Acceptance.Accepted.provider`.
     *
     * **It is never printed.** §12 item 2 keeps a counterparty pubkey out of anything public and
     * §12 item 11 out of the string representation of anything this library exposes, so [toString]
     * on the implementation names no key at all and a test asserts the hex is absent.
     */
    public val provider: String?

    /** §10.1's commitment, once the provider has sent one; `null` before `committed`. */
    public val commitment: DeliverableCommitment?

    /**
     * The injected clock's reading at the moment the order became `paid`, in unix seconds, or
     * `null` if it had none to give — including when it answered a negative reading, which this
     * library refuses as a broken clock rather than recording (see `NenyaClock`). Never negative.
     *
     * Recorded for exactly one purpose: §11.2 says that where the accepted terms carry no
     * `deliver_by`, an implementation MUST apply a release timeout of its own and MUST NOT leave
     * the order in `paid` indefinitely. A timeout needs something to run from, and the only
     * instant §4.6 permits this library to know is one the injected clock gave it.
     */
    public val paidAt: Long?

    /**
     * The injected clock's reading at the moment the order entered `released`, in unix seconds, or
     * `null` if it had none to give — including a negative reading, refused as a broken clock
     * exactly as [paidAt] refuses one. Never negative.
     *
     * Recorded for the same single purpose [paidAt] is, one state later: §11.2's verification
     * deadline (revision `1.6`) runs from it, and a timeout needs something to run from. It is the
     * **first** anchor that deadline tries, ahead of [paidAt] and ahead of `deliver_by`, because it
     * is the only one of the three that measures what the deadline is actually about — how long
     * the buyer has had the key. A release sent one second before `deliver_by` would otherwise be
     * disputed one second later.
     *
     * **It is never printed** (§12 item 11), for the reason [paidAt] is not: a clock reading taken
     * at a moment of this order's life is a correlation handle for anyone who later reads the log.
     */
    public val releasedAt: Long?

    /** Why the order is `disputed`, and `null` in every other state (§14 item 3). */
    public val disputeGround: DisputeGround?

    /** The §9.2 checks this library performed on the receipts behind `paid`. Empty before it. */
    public val paymentChecksPerformed: Set<PaymentCheck>

    /**
     * The §9.2 checks behind `paid` that this library did **not** perform (§17).
     *
     * ### Invariant: empty on every order in `paid`, `released` or `settled`
     *
     * Decision B made it so. `awaiting_payment → paid` refuses while any applicable §9.2 check is
     * unperformed ([TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED]), and `paid` is the only
     * gateway to `released` and `settled`, so an order past that gate carries nothing here by
     * construction. It used to be non-empty for every order that reached `paid` on a receipt —
     * `INVOICE_AMOUNT` above all, the check a provider inflating their invoice is caught by — and
     * that is precisely the state of affairs the decision ended.
     *
     * The field is **kept**, and not because it is now always empty. It is the shape §17 item 6
     * requires of an order's record, it is what the refusal above is computed against one layer
     * out, and it goes back to carrying content the moment a §9.2 check is performed on some paths
     * and not others. A caller that deleted its handling would have to write it again.
     *
     * **Empty was always a third answer, and still is: a caller MUST NOT read it as "everything
     * was checked".** An order whose price and fee both compute to zero requires no receipt at all
     * (§9.2's non-zero clause), reaches `paid` on an empty receipt set, and carries an empty record
     * in both directions — today it is the only order that can. The question this set answers is
     * "of the checks the receipts behind this order needed, which were skipped", and where there
     * were no receipts there were no checks. [paymentChecksPerformed] being empty is the flag that
     * distinguishes the two, which is why both sets are published rather than only this one.
     */
    public val paymentChecksNotPerformedHere: Set<PaymentCheck>

    /**
     * The §10 obligations this library performed for this order, accumulated as it moved.
     *
     * Three transitions contribute and each contributes only what its own input could establish:
     * `accepted → committed` adds what the `type=5` decoder checked over the commitment's tags
     * (§10.1's key-absence rule), `paid → released` adds §10.3's binding where the `kind:15`
     * decoder checked it, and `released → settled` adds the two hashes a `DeliveryEvidence` carries.
     * An order whose commitment and release arrived as bare typed values adds nothing at the first
     * two, because nobody checked — the events built that way have no parameter to say otherwise.
     */
    public val deliveryChecksPerformed: Set<DeliveryCheck>

    /**
     * The §10 obligations this library did **not** perform for this order (§17 item 7).
     *
     * Populated at `released → settled` from the evidence's own record, **minus** what
     * [deliveryChecksPerformed] already holds: `DeliveryEvidence` reports what the verification
     * chain did, and that chain has no tags and no order, so it honestly records §10.1's and
     * §10.3's obligations as unperformed even where the decoders performed them. Leaving them in
     * both sets would be one order saying two things at once.
     */
    public val deliveryChecksNotPerformedHere: Set<DeliveryCheck>
}

/**
 * §11.2's transition function, total over `(state, event)`, consuming only evidence this library
 * verified for itself.
 *
 * ### What may move an order, and what may not
 *
 * `awaiting_payment → paid` consumes a `Settlement.Evidenced` for every payee
 * `Payee.requiredPayees` names, and **nothing else**; `released → settled` consumes a
 * `DeliveryEvidence`, and nothing else. Neither type can be constructed from a wallet's claim, a
 * counterparty's status string or a relay's acceptance, so §9.1 and STOP RULE 12 are enforced by
 * the type system rather than by a check somebody has to remember to write. The seam package's
 * `LyingWallet` is the executable form of that: it reports every payment settled and moves an
 * order out of `awaiting_payment` not at all.
 *
 * The evidence is bound to *this* order as well as verified: a `Settlement.Evidenced` names the
 * order its `kind:17` claimed to settle, and one naming another is refused
 * ([TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER]) before any other rule runs. Verified evidence
 * for somebody else's order is still evidence of something; it is not evidence of this.
 *
 * ### The two transitions the codecs feed, and the two comparisons left here
 *
 * The same argument reaches two rows further up the table (decision J). `proposed → accepted`
 * consumes an `Acceptance.Accepted`, which exists only where `OrderProposal.accepts` compared the
 * seal against the provider key the caller resolved and then §7.6's four terms over the raw signed
 * tags; `committed → awaiting_payment` consumes `AcceptedPaymentRequest`s, which exist only where
 * T24 held a real `type=2` to §8.4, §8.6, §8.7, Appendix C and §9.2 checks 4 and 5 and then stored
 * it. A `type=3` `status=accepted` a caller assembles advances nothing, from any state
 * ([TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE]), and there is no shape of payment
 * request that is a payee label.
 *
 * What is left here is what a checked value cannot know, because both are ordinary values a caller
 * may offer to any order: whether it is *this* order's ([TransitionRejection.ACCEPTANCE_FOR_ANOTHER_ORDER],
 * [TransitionRejection.PAYMENT_REQUEST_FOR_ANOTHER_ORDER]), and whether a `payee=provider` request
 * came under the key **this** order's acceptance was checked against
 * ([TransitionRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER], decision I).
 *
 * ### The seams are injected, both of them
 *
 * §4.6 makes the injected clock the only time this library may know — every deadline in NENYA-1
 * is evaluated against it and never against a counterparty's `created_at`, because gift-wrap
 * timestamps are deliberately randomised into the past (§7.1). Both parameters take defaults so a
 * test can pin them, and the clock's default fails closed: an order under it simply never expires,
 * and says `CLOCK_UNAVAILABLE` when asked, rather than expiring against an ambient clock. A clock
 * that answers a negative reading fails closed the same way, and says `CLOCK_READING_BEFORE_EPOCH`.
 *
 * @param clock §4.6's authoritative clock.
 * @param releaseTimeoutSeconds §11.2's own-release timeout, as a number of seconds, applied only
 *   where the accepted terms carry no `deliver_by`. **Local policy, not a wire value** — §11.2
 *   requires an implementation to apply and display one and fixes no number, which is why this
 *   is a parameter with a default rather than a constant. Seven days is long enough not to fire
 *   on a provider who is merely slow and short enough that an abandoned order reaches a terminal
 *   state while the buyer still remembers it. Must be strictly positive.
 * @param verificationTimeoutSeconds §11.2's verification window out of `released`, as a number of
 *   seconds, applied to whichever anchor that row names first — the reading taken at release, then
 *   [Order.paidAt], then `deliver_by`. **Local policy, not a wire value**, and §11.2 says so in
 *   those words: at `released` the only outstanding act is the buyer's own computation of `x` and
 *   `ox` (§10.4), so there is nothing for the two parties to agree in advance about and a fourth
 *   wire term would reopen §7.5's and §7.6's byte-identical terms for no gain. Seven days, for the
 *   reason [releaseTimeoutSeconds] is. Must be strictly positive.
 */
public class OrderMachine(
    private val clock: NenyaClock = NenyaClock.FAIL_CLOSED,
    private val releaseTimeoutSeconds: Long = DEFAULT_RELEASE_TIMEOUT_SECONDS,
    private val verificationTimeoutSeconds: Long = DEFAULT_VERIFICATION_TIMEOUT_SECONDS,
) {

    init {
        if (releaseTimeoutSeconds <= 0L) {
            throw OrderStateException(
                OrderStateRejection.RELEASE_TIMEOUT_NOT_POSITIVE,
                "§11.2 requires an implementation with no `deliver_by` to apply and display a " +
                    "release timeout of its own; a zero or negative one is not a timeout, it " +
                    "disputes an order the moment it is paid",
            )
        }
        if (verificationTimeoutSeconds <= 0L) {
            throw OrderStateException(
                OrderStateRejection.VERIFICATION_TIMEOUT_NOT_POSITIVE,
                "§11.2 requires an implementation to apply and display a verification window of " +
                    "its own out of `released`; a zero or negative one is not a window, it " +
                    "disputes an order the moment the key reaches the buyer",
            )
        }
    }

    /**
     * §11.2's genesis row — `— → proposed`. The buyer sends a `type=1` with complete terms.
     *
     * The only row of §11.2 with no from-state, and therefore the one edge [on] cannot express:
     * there is no order to hand it. Terms carrying no fee term are **complete** (§8.1) and open at
     * zero fee; §7.5's rule that `expiration` falls strictly before `deliver_by` was already
     * enforced when the terms were built, so nothing here can fail.
     */
    public fun open(proposal: OrderEvent.Proposal): Order = OpenOrder(
        id = proposal.order,
        state = OrderState.PROPOSED,
        terms = proposal.terms,
        // §11.2's genesis row is the buyer's own act: no acceptance has arrived, so there is no
        // checked provider key to record and nothing is invented to stand in for one.
        provider = null,
        commitment = null,
        paidAt = null,
        releasedAt = null,
        disputeGround = null,
        paymentChecksPerformed = emptySet(),
        paymentChecksNotPerformedHere = emptySet(),
        deliveryChecksPerformed = emptySet(),
        deliveryChecksNotPerformedHere = emptySet(),
    )

    /**
     * §11.1's sink as a value: an order whose status this implementation did not recognise.
     *
     * §11.1 requires an unrecognised status be treated as `unknown` and MUST NOT be mapped onto
     * the nearest known state — "in particular an unknown status MUST NOT be treated as `paid` or
     * `settled`". That makes `unknown` a **state**, and a state nothing can ever be in is not a
     * state: a caller reconstructing an order thread from what a relay returned needs somewhere
     * to put the one whose `["status", ...]` token `OrderStatusCodec` read as
     * [OrderState.UNKNOWN].
     *
     * This is the only door to it, and it is a door to nothing else. It takes no state, no token
     * and no `Boolean`: it produces [OrderState.UNKNOWN] and can produce nothing else, so it
     * cannot be used to assert an order into `paid`. [on] then refuses every event from it —
     * §11.1's sink appears in neither column of §11.2, so it originates no transition and
     * receives none.
     *
     * It takes the order id for the same reason [open] does: the caller reconstructing a thread
     * read the `["order", ...]` tag off the messages it is putting here, and an order in the sink
     * that had forgotten which thread it was is one a client cannot even list.
     */
    public fun unrecognised(id: OrderId, terms: OrderTerms): Order = OpenOrder(
        id = id,
        state = OrderState.UNKNOWN,
        terms = terms,
        // The sink is a thread a caller could not read, not one it took part in: §7.6 was never
        // reached, so there is no key an acceptance was compared against.
        provider = null,
        commitment = null,
        paidAt = null,
        releasedAt = null,
        disputeGround = null,
        paymentChecksPerformed = emptySet(),
        paymentChecksNotPerformedHere = emptySet(),
        deliveryChecksPerformed = emptySet(),
        deliveryChecksNotPerformedHere = emptySet(),
    )

    /**
     * Offer [event] to [order]: a new order, or a named refusal. Total over every pair.
     *
     * The three rules that hold in **every** state are applied before the state is even looked
     * at, so that they are uniform rather than repeated eleven times and forgotten once:
     *
     * 1. §11.2's message classes that advance nothing — `kind:14`, `type=6`, and §7.4's reserved
     *    `type=4` — "in any state, from any key";
     * 2. §11.3 invariant 5 — a `type=3` announcing anything other than `accepted` or `cancelled`
     *    changes nothing, from any key, in any state. `paid`, `settled` and `disputed` are the
     *    ones that matter, and `disputed` is the one an implementer reaches for as a trigger.
     *    `accepted` changes nothing either, and is deliberately **not** refused here: it earns its
     *    own reason a moment later ([TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE]),
     *    because "that status decides nothing" and "that status decides something and this is not
     *    the message carrying the decision" send a client to do different things;
     * 3. a `type=1` cannot reach an order that already exists (§7.6).
     */
    public fun on(order: Order, event: OrderEvent): OrderOutcome {
        advancesNothingEver(event)?.let { return refuse(order, it.first, it.second) }

        return when (order.state) {
            OrderState.UNKNOWN -> refuse(
                order,
                TransitionRejection.STATE_IS_UNKNOWN,
                "§11.1's unknown sink appears in neither column of §11.2: it originates no " +
                    "transition and receives none, and MUST NOT be mapped onto the nearest known " +
                    "state",
            )

            // Named one by one rather than taken as `isTerminal` or as a suffix of the enum.
            // §11.4 warns against transition logic a future `held` state would silently join, and
            // the four terminals are separately checked against §11.1's own marks by the suite.
            OrderState.SETTLED,
            OrderState.CANCELLED,
            OrderState.EXPIRED,
            OrderState.DISPUTED,
            -> refuse(
                order,
                TransitionRejection.STATE_IS_TERMINAL,
                "§11.3 invariant 3: there is no path from a terminal state to any other state, " +
                    "and evidence arriving afterwards does not reopen one",
            )

            OrderState.PROPOSED -> fromProposed(order, event)
            OrderState.ACCEPTED -> fromAccepted(order, event)
            OrderState.COMMITTED -> fromCommitted(order, event)
            OrderState.AWAITING_PAYMENT -> fromAwaitingPayment(order, event)
            OrderState.PAID -> fromPaid(order, event)
            OrderState.RELEASED -> fromReleased(order, event)
        }
    }

    // ------------------------------------------------------------------ state-independent rules

    /**
     * The refusal that holds in all eleven states, or `null` if this event might move something.
     *
     * Checked before the state so the answer is uniform: §11.2 says a private bid and a chat
     * message advance nothing "in any state, from any key", and §11.3 invariant 5 says the same
     * of a status update announcing `paid` or `settled`. A terminal-state check running first
     * would answer `STATE_IS_TERMINAL` for four of the eleven and make those rules impossible to
     * state as one assertion.
     *
     * `accepted` is not on this list even though it now advances nothing either. It is refused by
     * [statusUpdate] with its own reason, which means a terminal or `unknown` order answers
     * `STATE_IS_TERMINAL` or `STATE_IS_UNKNOWN` for one — the more specific answer, and the same
     * treatment `cancelled` already gets.
     */
    private fun advancesNothingEver(event: OrderEvent): Pair<TransitionRejection, String>? = when {
        event is OrderEvent.ChatMessage || event is OrderEvent.PrivateBid ->
            TransitionRejection.EVENT_ADVANCES_NO_STATE to
                "§11.2: a kind:16 type=6 private bid and a kind:14 chat message appear in no row " +
                "of the transition table and MUST advance no state, in any state, from any key; " +
                "neither is an acceptance, a cancellation, or evidence of anything (§7.6, §9.1)"

        event is OrderEvent.ShippingUpdate ->
            TransitionRejection.EVENT_ADVANCES_NO_STATE to
                "§7.4: type=4 is GammaMarkets' shipping update, reserved in Nenya v1, which MUST " +
                "NOT emit it and MUST ignore it on read"

        event is OrderEvent.StatusUpdate &&
            event.status != OrderState.ACCEPTED &&
            event.status != OrderState.CANCELLED ->
            TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING to
                "§11.3 invariant 5: a type=3 status update announces; it does not decide. " +
                "`cancelled` is the only status one of these can move an order on, and only " +
                "before `paid`. `accepted` is not a second one: §7.6's comparison is made over a " +
                "seal and the raw signed tags, one layer down, and no type=3 a caller assembles " +
                "advances an order"

        event is OrderEvent.Proposal ->
            TransitionRejection.ORDER_ALREADY_OPEN to
                "§11.2's genesis row is the only one without a from-state: a type=1 opens an " +
                "order and never advances one. §7.6 makes a counter-proposal a new type=1 with a " +
                "new order id"

        else -> null
    }

    // ------------------------------------------------------------------ per-state dispatch

    private fun fromProposed(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.AcceptanceReceived -> acceptance(order, event)
        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        is OrderEvent.ClockChecked -> acceptanceDeadline(order)
        else -> wrongState(
            order,
            "proposed",
            "§7.6's checked acceptance, a type=3 cancellation, or the clock passing `expiration`",
        )
    }

    private fun fromAccepted(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.DeliveryCommitted ->
            if (event.from != Party.PROVIDER) {
                wrongSender(order, "a type=5 delivery commitment is the provider's (§7.4, §10.1)")
            } else {
                advance(
                    order.with(
                        state = OrderState.COMMITTED,
                        commitment = event.commitment,
                        // §17: what the `type=5` decoder checked over the tags travels with the
                        // event, because a record of what was verified is honest only where it is
                        // produced by the code that did the verifying. Empty for an event built
                        // from a bare `DeliverableCommitment`, which is four values and no tags.
                        deliveryChecksPerformed = order.deliveryChecksPerformed + event.checksPerformed,
                    ),
                )
            }

        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        is OrderEvent.ClockChecked -> acceptanceDeadline(order)
        else -> wrongState(order, "accepted", "a type=5 commitment, a cancellation, or the clock passing `expiration`")
    }

    private fun fromCommitted(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.PaymentRequestsReceived -> paymentRequests(order, event)
        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        is OrderEvent.ClockChecked -> acceptanceDeadline(order)
        is OrderEvent.LocallyDisputed -> advance(
            order.with(state = OrderState.DISPUTED, disputeGround = DisputeGround.RAISED_LOCALLY),
        )

        else -> wrongState(
            order,
            "committed",
            "one valid type=2 per required payee, a cancellation, the clock passing `expiration`, " +
                "or a locally raised dispute",
        )
    }

    private fun fromAwaitingPayment(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.ReceiptsVerified -> receipts(order, event)
        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        is OrderEvent.ClockChecked -> acceptanceDeadline(order)
        is OrderEvent.LocallyDisputed -> advance(
            order.with(state = OrderState.DISPUTED, disputeGround = DisputeGround.RAISED_LOCALLY),
        )

        else -> wrongState(
            order,
            "awaiting_payment",
            "every required receipt verified per §9.2, a cancellation, the clock passing " +
                "`expiration`, or a locally raised dispute",
        )
    }

    private fun fromPaid(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.DeliverableReleased -> release(order, event)
        is OrderEvent.ClockChecked -> releaseDeadline(order)
        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        else -> wrongState(order, "paid", "a kind:15 release, or the clock passing `deliver_by`")
    }

    private fun fromReleased(order: Order, event: OrderEvent): OrderOutcome = when (event) {
        is OrderEvent.DeliveryVerified -> settle(order, event)
        is OrderEvent.DeliveryRefused -> advance(
            order.with(state = OrderState.DISPUTED, disputeGround = ground(event.failure)),
        )

        is OrderEvent.ClockChecked -> verificationDeadline(order)
        is OrderEvent.StatusUpdate -> statusUpdate(order, event)
        else -> wrongState(
            order,
            "released",
            "the buyer's own §10.4 verification succeeding or failing, or the clock passing the " +
                "verification deadline",
        )
    }

    // ------------------------------------------------------------------ the individual triggers

    /**
     * §11.2's cancellation — the one status that still moves anything — and the flat refusal of a
     * `status=accepted`.
     *
     * The `accepted` branch takes no operand at all, and that is the change decision J asks for:
     * there is no terms parameter to compare against and no sender to be right, because neither
     * operand §7.6 names is on this event. A `Party` is a label the caller applied and
     * [OrderTerms] are parsed values, so the two comparisons that would have run here were a
     * strictly weaker imitation of the ones `OrderProposal.accepts` makes over the seal and the raw
     * tags. It is refused from every state, including `proposed`, so the old door is shut rather
     * than narrowed.
     */
    private fun statusUpdate(order: Order, event: OrderEvent.StatusUpdate): OrderOutcome =
        when (event.status) {
            OrderState.ACCEPTED -> refuse(
                order,
                TransitionRejection.ACCEPTANCE_NOT_DECIDED_BY_STATUS_UPDATE,
                "§7.6 decides acceptance by comparing the seal's pubkey against the provider key " +
                    "the caller resolved, and then the four terms **byte-identically** against the " +
                    "proposal's raw tags — the `fee` pair, its recipient included. Neither operand " +
                    "is on a type=3 assembled by a caller: the sender here is a label and the terms " +
                    "are parsed values. That comparison is `OrderProposal.accepts`', and its " +
                    "checked answer is the only thing that reaches `accepted`",
            )

            OrderState.CANCELLED -> when {
                order.state == OrderState.PAID || order.state == OrderState.RELEASED -> refuse(
                    order,
                    TransitionRejection.CANCELLATION_TOO_LATE,
                    "§11.3 invariant 5: only `cancelled` is accepted from a counterparty's " +
                        "assertion alone, and only before `paid`",
                )

                event.from == Party.FEE_RECIPIENT -> wrongSender(
                    order,
                    "§11.2 accepts a cancellation from either **party**; §8.5 is explicit that " +
                        "the fee recipient is not a party to the delivery and has no refund " +
                        "obligation",
                )

                else -> advance(order.with(state = OrderState.CANCELLED))
            }

            // Unreachable: `advancesNothingEver` already refused every other status, in every
            // state. Named rather than left to fall through — §11.2's totality rule is precisely
            // that a forgotten case must not become the happy path.
            else -> refuse(
                order,
                TransitionRejection.STATUS_UPDATE_DECIDES_NOTHING,
                "§11.3 invariant 5: a status update announces; it does not decide",
            )
        }

    /**
     * §11.2's `proposed → accepted` row: §7.6's acceptance, as `OrderProposal.accepts` checked it.
     *
     * One comparison, because the codec already made the other two and made them over operands
     * this file does not hold. What is left is the binding — is this acceptance *this* order's —
     * for the reason §10.3 gives about its own release: routing is on the `order` id, and equality
     * of anything else is a check rather than the binding. Two orders between the same buyer and
     * provider carry the same provider key and can carry identical terms, so every other rule on
     * this row would pass on a misrouted acceptance.
     *
     * The provider key travels onto the order as it advances, and is the operand §8.6's revision
     * `1.5` sender rule is checked against one state later (decision I, decision H).
     */
    private fun acceptance(order: Order, event: OrderEvent.AcceptanceReceived): OrderOutcome {
        if (event.acceptance.order != order.id) {
            return refuse(
                order,
                TransitionRejection.ACCEPTANCE_FOR_ANOTHER_ORDER,
                "§7.6 compares an acceptance against the proposal it answers, and this one was " +
                    "checked against another order's. §7.4 makes the order id the handle every " +
                    "message in a thread carries: the acceptance may well be genuine, and it is " +
                    "not this order's",
            )
        }
        return advance(
            order.with(
                state = OrderState.ACCEPTED,
                // §7.6's checked key, recorded so that §8.6's `payee=provider` rule has the same
                // operand at `committed → awaiting_payment` that T24 had at acceptance. Never a
                // key this library discovered: it is the one the caller resolved and `accepts`
                // compared the seal against.
                provider = event.acceptance.provider,
            ),
        )
    }

    /** §11.2's `→ expired` rows: the injected clock passing the proposal's `expiration` (§4.6). */
    private fun acceptanceDeadline(order: Order): OrderOutcome {
        val deadline = order.terms.expiration ?: return refuse(
            order,
            TransitionRejection.NO_DEADLINE_TO_CHECK,
            "these terms carry no `expiration`, so §11.2's `→ expired` rows have no deadline to " +
                "fire on; §7.5 does not make the tag REQUIRED and this library does not invent one",
        )
        val now = when (val clockNow = reading()) {
            is ClockReading.At -> clockNow.unixSeconds
            is ClockReading.Refused -> return clockRefusal(order, clockNow)
        }
        return if (now < deadline) {
            refuse(
                order,
                TransitionRejection.DEADLINE_NOT_PASSED,
                "the injected clock has not reached the proposal's `expiration` (§4.6, §11.2)",
            )
        } else {
            advance(order.with(state = OrderState.EXPIRED))
        }
    }

    /**
     * §11.2's `paid → disputed` row: the clock passing `deliver_by` with no release received.
     *
     * Where the accepted terms carry no `deliver_by`, §11.2 requires an implementation to apply a
     * release timeout of its own and MUST NOT leave the order in `paid` indefinitely — so
     * [releaseTimeoutSeconds] runs from [Order.paidAt], the clock reading taken when the order
     * became `paid`. If the clock gave none then, there is nothing to run from and the refusal
     * says so rather than substituting a time from somewhere §4.6 does not permit.
     */
    private fun releaseDeadline(order: Order): OrderOutcome {
        val now = when (val clockNow = reading()) {
            is ClockReading.At -> clockNow.unixSeconds
            is ClockReading.Refused -> return clockRefusal(order, clockNow)
        }
        val paidAt = order.paidAt
        val deadline = order.terms.deliverBy
            ?: paidAt?.let { at ->
                // The injected clock is the embedding client's, so `paidAt` can be any
                // non-negative `Long` it chose — including one so late that adding the timeout
                // overflows. Plain `+` would wrap silently to a large negative deadline that every clock reading has already
                // passed, disputing the order on the spot; that silent wrap is the bug class
                // `Msat` refuses too. `on` is documented total over every pair (§11.2), so the
                // overflow is not thrown either: a deadline beyond `Long.MAX_VALUE` is a deadline
                // no clock reading can reach, and the honest answer is the refusal below.
                // `releaseTimeoutSeconds` is positive (checked in `init`), so only the top of the
                // range can be crossed.
                checkedDeadline(at, releaseTimeoutSeconds) ?: return refuse(
                    order,
                    TransitionRejection.DEADLINE_NOT_PASSED,
                    "§11.2's own-release timeout lands beyond the last representable second, so " +
                        "no clock reading can reach it",
                )
            }
            ?: return refuse(
                order,
                TransitionRejection.NO_DEADLINE_TO_CHECK,
                "these terms carry no `deliver_by`, and the injected clock gave no reading when " +
                    "the order became `paid`, so §11.2's own-release timeout has nothing to run " +
                    "from",
            )
        return if (now < deadline) {
            refuse(
                order,
                TransitionRejection.DEADLINE_NOT_PASSED,
                "the injected clock has not reached `deliver_by` (§4.6, §11.2); a counterparty's " +
                    "`created_at` is not consulted and never will be",
            )
        } else {
            advance(
                order.with(
                    state = OrderState.DISPUTED,
                    disputeGround = DisputeGround.RELEASE_DEADLINE_PASSED,
                ),
            )
        }
    }

    /**
     * §11.2's second `released → disputed` row (revision `1.6`): the clock passing the
     * **verification deadline** with the buyer's own §10.4 verification neither done nor failed.
     *
     * The anchor order is §11.2's own, and each step of it is a fact this library either has or
     * does not: [Order.releasedAt] — how long the buyer has actually had the key — then
     * [Order.paidAt], then `deliver_by` from the accepted terms. Where none of the three exists
     * there is no deadline, and §11.2 requires that to be reported as *having none* rather than as
     * pending, which is [TransitionRejection.NO_DEADLINE_TO_CHECK] and not
     * [TransitionRejection.DEADLINE_NOT_PASSED]. Nothing is invented to fill the gap: §4.6 permits
     * this library no time but the injected clock's.
     *
     * A `DeliveryVerified` arriving after the deadline but before any [OrderEvent.ClockChecked]
     * still settles the order, exactly as a `kind:15` arriving after `deliver_by` still releases
     * one. The deadline is not a state the order is in; it is a question this event asks, and the
     * answer to "did the bytes verify" does not become "no" because nobody asked in time.
     */
    private fun verificationDeadline(order: Order): OrderOutcome {
        val now = when (val clockNow = reading()) {
            is ClockReading.At -> clockNow.unixSeconds
            is ClockReading.Refused -> return clockRefusal(order, clockNow)
        }
        val anchor = order.releasedAt
            ?: order.paidAt
            ?: order.terms.deliverBy
            ?: return refuse(
                order,
                TransitionRejection.NO_DEADLINE_TO_CHECK,
                "the injected clock gave no reading when the order was released and none when it " +
                    "became `paid`, and these terms carry no `deliver_by`, so §11.2's " +
                    "verification deadline has nothing to run from. §11.2 requires this be shown " +
                    "as having no deadline rather than as still pending",
            )
        // The same overflow the own-release timeout has, reached the same way: every anchor here
        // is a `Long` the embedding client's clock or the counterparty's terms supplied, so a
        // deadline beyond the last representable second is constructible. Plain `+` would wrap to
        // a negative deadline every reading has already passed and dispute the order on the spot.
        // `on` is total over every pair (§11.2), so this refuses rather than throwing.
        val deadline = checkedDeadline(anchor, verificationTimeoutSeconds) ?: return refuse(
            order,
            TransitionRejection.DEADLINE_NOT_PASSED,
            "§11.2's verification deadline lands beyond the last representable second, so no " +
                "clock reading can reach it",
        )
        return if (now < deadline) {
            refuse(
                order,
                TransitionRejection.DEADLINE_NOT_PASSED,
                "the injected clock has not reached the verification deadline (§4.6, §11.2); a " +
                    "counterparty's `created_at` is not consulted and never will be",
            )
        } else {
            advance(
                order.with(
                    state = OrderState.DISPUTED,
                    disputeGround = DisputeGround.VERIFICATION_DEADLINE_PASSED,
                ),
            )
        }
    }

    /**
     * §11.2's `committed → awaiting_payment`: one valid `type=2` per **required** payee.
     *
     * "Required" is §9.2's — named by the terms **and** carrying a non-zero expected amount — and
     * it is read from `Payee.requiredPayees`, never re-derived. §8.3 is explicit about what
     * happens to an implementation that requires an invoice per *named* payee instead: at
     * `bps = 1` and `price_msat = 3000` the fee is `floor(3000 × 1 / 10000) = 0`, no fee invoice
     * may legally exist, and every such order deadlocks into `expired`.
     *
     * ### The two comparisons that come first, and why a stored record does not already make them
     *
     * Every operand below is read off an [dev.eryalabs.nenya.settlement.AcceptedPaymentRequest],
     * which `AcceptedPaymentRequest.accept` minted after §8.4, §8.6, §8.7, Appendix C and §9.2
     * checks 4 and 5. Two things that record cannot know are checked here, both against **this**
     * order:
     *
     * 1. the order id, for the reason [TransitionRejection.PAYMENT_REQUEST_FOR_ANOTHER_ORDER]
     *    gives — a record is keyed by `(order, payee)` and is otherwise an ordinary value;
     * 2. the sealing key of a `payee=provider` record against [Order.provider], the key **this
     *    order's** acceptance was checked against (decision I). T24 compared it against the key
     *    *the acceptance it was handed* carried, which is the same rule about a different subject:
     *    a caller holding an `Acceptance.Accepted` resolved against a stranger mints a record that
     *    passes every check T24 makes.
     *
     * The payee of a `payee=fee` record is not held to a key here: §8.7 binds a fee invoice to the
     * recipient named in the **signed fee term**, which is a sequence of raw tags no [Order] holds,
     * and `Settlement.checkFeePaymentRequest` made that comparison before the record existed.
     */
    private fun paymentRequests(order: Order, event: OrderEvent.PaymentRequestsReceived): OrderOutcome {
        if (event.requests.any { it.order != order.id }) {
            return refuse(
                order,
                TransitionRejection.PAYMENT_REQUEST_FOR_ANOTHER_ORDER,
                "a `type=2` accepted for another order was offered as this one's. §7.4 makes the " +
                    "order id the handle every message in a thread carries and §9.2 check 1 keys " +
                    "the store by (order, payee); the invoice may well be valid, and it is not " +
                    "this order's",
            )
        }
        // `order.provider` is null only where no acceptance ever arrived, which §11.2 makes
        // unreachable at `committed` — and a null compares unequal to every real key, so the
        // shape this library cannot produce fails closed rather than matching.
        if (event.requests.any { it.payee == Payee.PROVIDER && it.sealedBy != order.provider }) {
            return refuse(
                order,
                TransitionRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER,
                "§8.6: a `type=2` with `[\"payee\", \"provider\"]` MUST arrive in a gift wrap " +
                    "whose seal is the provider's key — the same key the acceptance for **this** " +
                    "order was checked against (§7.6, §11.2) — and any other MUST be rejected. A " +
                    "provider invoice from another key is somebody else's bill under the " +
                    "provider's name, and §9.2 check 1 would anchor this order's whole payment " +
                    "evidence to it",
            )
        }
        val required = Payee.requiredPayees(order.terms.split)
        val offered = event.requests.mapTo(LinkedHashSet()) { it.payee }
        // Before the set arithmetic, because the set arithmetic is what would hide it: reducing
        // records to roles collapses two provider invoices into one `Payee.PROVIDER` and the order
        // advances with §9.2 check 1 pointed at whichever of them the store happens to hold.
        if (offered.size != event.requests.size) {
            return refuse(
                order,
                TransitionRejection.PAYMENT_REQUEST_PAYEE_DUPLICATED,
                "§8.6 is one invoice per payee, so two accepted `type=2`s for one role are not a " +
                    "duplicate to be collapsed: one of them bills for something else, and §9.2 " +
                    "check 1 compares a receipt against *the* stored request for that payee",
            )
        }
        val surplus = offered - required
        if (surplus.isNotEmpty()) {
            return refuse(
                order,
                TransitionRejection.PAYMENT_REQUEST_NOT_REQUIRED,
                "a type=2 arrived from a payee no payment is owed to. §8.6: when the expected " +
                    "amount is 0 no payment request exists at all and any that arrives MUST be " +
                    "rejected; §8.1 says the same of every fee invoice for an order proposed with " +
                    "no `fee` tag",
            )
        }
        val missing = required - offered
        if (missing.isNotEmpty()) {
            return refuse(
                order,
                TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE,
                "§11.2 enters `awaiting_payment` on one valid type=2 per required payee (§9.2); " +
                    "at least one is still outstanding",
            )
        }
        return advance(order.with(state = OrderState.AWAITING_PAYMENT))
    }

    /**
     * §11.2's `awaiting_payment → paid`: **all** required receipts verified per §9.2.
     *
     * The only input is a set of `Settlement.Evidenced`, which exists only where this library
     * hashed a preimage itself **and** matched the invoice against the `type=2` it stored for the
     * same order and payee. Every operand below is read off that evidence and none off a caller's
     * parameter: the order id, the payee and the payment hash are the ones the `kind:17` carried
     * and the store confirmed.
     *
     * §17's record travels with it: the union of what those *settlement results* say was and was
     * not checked becomes the order's own — not the union of their `VerifiedPayment`s', which
     * would drop check 1 and check 6 on the floor and under-report an order that had passed both.
     * So `paid` claims exactly the checks the evidence behind it performed, no more and no less.
     *
     * ### The last question asked is whether §9.2 was actually performed (decision B)
     *
     * §9.2 requires **all** of its checks before a payment may be treated as made, so a receipt set
     * that is complete, correctly routed and undisputed is still not `paid` while some check that
     * applies to it was performed by nobody. That is refused here, and the order does not move. In
     * practice no priced order can reach `paid` until checks 4, 5 and check 3's provenance exist;
     * a free order — price `0`, no fee — owes no receipt, leaves the set empty and still advances.
     * That cost is the decision, not a side effect of it: an order marked `paid` releases a
     * deliverable against a payment whose amount nobody checked.
     *
     * Two details of the placement are load-bearing.
     *
     * - It runs **last**. Ahead of the missing-receipt rule it would answer "some check was not
     *   performed" to a caller who had simply not sent the fee receipt yet — true, useless, and it
     *   would hide §8.5's deadlock probe, which needs `RECEIPTS_INCOMPLETE` to stay reachable.
     * - It asks what **applies** to each payee and subtracts what that receipt says it *performed*.
     *   It never reads `checksNotPerformedHere`. The two are the same set today and are different
     *   questions: a settlement path that came to subtract a check it had not done would make its
     *   own omission invisible to the refusal that exists to catch it, and §9.1's whole point is
     *   that a value's assertion about itself is not evidence. Only "applicable minus performed"
     *   fails closed.
     */
    private fun receipts(order: Order, event: OrderEvent.ReceiptsVerified): OrderOutcome {
        val required = Payee.requiredPayees(order.terms.split)
        if (event.receipts.any { it.order != order.id }) {
            return refuse(
                order,
                TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER,
                "a receipt naming another order was offered as evidence for this one. §7.4 makes " +
                    "the order id the handle every message in a thread carries and §9.2 check 1 " +
                    "keys the stored payment request by (order, payee); the payment may well have " +
                    "happened, and it is not this order's",
            )
        }
        val covered = event.receipts.mapTo(LinkedHashSet()) { it.payee }
        if (covered.size != event.receipts.size) {
            return refuse(
                order,
                TransitionRejection.RECEIPT_PAYEE_DUPLICATED,
                "§8.6 is one invoice per payee, so two receipts for one role are not a duplicate " +
                    "to be collapsed: one of them is evidence of something else",
            )
        }
        if (event.receipts.mapTo(HashSet()) { it.payment.paymentHash }.size != event.receipts.size) {
            return refuse(
                order,
                TransitionRejection.RECEIPT_PAYMENT_DUPLICATED,
                "§8.6: the buyer pays the provider and the fee recipient with two **separate** " +
                    "payments to two separate invoices, and two invoices cannot share a payment " +
                    "hash. One payment is being offered as evidence of two",
            )
        }
        val surplus = covered - required
        if (surplus.isNotEmpty()) {
            return refuse(
                order,
                TransitionRejection.RECEIPT_NOT_REQUIRED,
                "a verified receipt arrived for a payee no payment is owed to (§8.1, §8.3, §8.6, " +
                    "§9.2). The payment may well have happened; it is not this order's",
            )
        }
        val missing = required - covered
        if (missing.isNotEmpty()) {
            return refuse(
                order,
                TransitionRejection.RECEIPTS_INCOMPLETE,
                "§11.2 enters `paid` only when **all** required receipts have verified per §9.2; " +
                    "at least one has not",
            )
        }
        // Decision B, and the last thing asked before the order moves: §9.2's checks must all have
        // been performed, not merely recorded as skipped. `applicableChecks` and never the
        // receipt's own `checksNotPerformedHere` — a record that wrongly subtracted a check would
        // otherwise hide its own omission from the refusal that exists to catch it.
        val unperformed = event.receipts.flatMapTo(LinkedHashSet()) {
            applicableChecks(it.payee) - it.checksPerformed
        }
        if (unperformed.isNotEmpty()) {
            return refuseChecks(order, unperformed)
        }
        return advance(
            order.with(
                state = OrderState.PAID,
                // A broken or silent clock records nothing here rather than a time: the refusal is
                // raised when a deadline is next evaluated, which reads the clock afresh.
                paidAt = (reading() as? ClockReading.At)?.unixSeconds,
                // The settlement result's own sets, never its `payment`'s: check 1 and check 6 are
                // performed on the settlement path and a `VerifiedPayment` knows nothing of
                // either, so reading through to it would under-report an order that passed both.
                paymentChecksPerformed = event.receipts.flatMapTo(LinkedHashSet()) { it.checksPerformed },
                paymentChecksNotPerformedHere =
                    event.receipts.flatMapTo(LinkedHashSet()) { it.checksNotPerformedHere },
            ),
        )
    }

    /**
     * §11.2's `paid → released`, and §10.3's `paid → disputed`.
     *
     * §10.3: "Any mismatch in any of the four MUST move the order to `disputed`." That is a legal
     * §11.2 pair reached by a trigger §10.3 states rather than §11.2's own row, which names the
     * release deadline. The four-operand check is not reimplemented here — it is
     * `DeliverableCommitment.checkReleaseIdentity`, which reports **which** operand diverged, and
     * a check comparing only `x` is the mistake it exists to catch.
     */
    private fun release(order: Order, event: OrderEvent.DeliverableReleased): OrderOutcome {
        if (event.from != Party.PROVIDER) {
            return wrongSender(order, "§11.2: the kind:15 release is the provider's (§7.4, §10.3)")
        }
        // §10.3's binding, before a hash is compared and for the reason §10.3 gives: routing is on
        // the `order` tag, and hash equality is a check rather than the binding. A release decoded
        // from a message carries the tag it read; one assembled from four typed values carries no
        // id and makes no claim, which is why `null` passes here and carries no check either.
        if (event.order != null && event.order != order.id) {
            return refuse(
                order,
                TransitionRejection.RELEASE_FOR_ANOTHER_ORDER,
                "§10.3: a release whose `order` tag names another order MUST NOT advance this one. " +
                    "Two orders with the same provider can carry the same commitment, so every " +
                    "hash on this row would match a misrouted release",
            )
        }
        val commitment = order.commitment ?: return noCommitment(order)
        try {
            commitment.checkReleaseIdentity(event.release)
        } catch (diverged: DeliveryException) {
            // Not a swallowed failure: §10.3 makes this outcome mandatory, and the reason travels
            // as the order's own DisputeGround rather than being discarded.
            return advance(
                order.with(
                    state = OrderState.DISPUTED,
                    disputeGround = DisputeGround.RELEASE_DOES_NOT_MATCH_COMMITMENT,
                ),
            )
        }
        return advance(
            order.with(
                state = OrderState.RELEASED,
                // §11.2's verification deadline runs from here. A broken or silent clock records
                // nothing rather than a time, exactly as `paid` does: the deadline then falls back
                // to `paidAt` and to `deliver_by`, and says it has none if neither exists.
                releasedAt = (reading() as? ClockReading.At)?.unixSeconds,
                // §10.3's binding, where the `kind:15` decoder made it. Accumulated rather than
                // replaced: the commitment's own check was recorded two transitions ago.
                deliveryChecksPerformed = order.deliveryChecksPerformed + event.checksPerformed,
            ),
        )
    }

    /** §11.2's `released → settled`: the buyer's own computation of `x` **and** `ox` both match. */
    private fun settle(order: Order, event: OrderEvent.DeliveryVerified): OrderOutcome {
        val commitment = order.commitment ?: return noCommitment(order)
        if (!sameCommitment(commitment, event.evidence.commitment)) {
            return refuse(
                order,
                TransitionRejection.EVIDENCE_IS_FOR_ANOTHER_COMMITMENT,
                "§10.3: hash equality is a check and not the binding. Evidence produced against " +
                    "another commitment belongs to another order thread and settles nothing here",
            )
        }
        // §17, and the union is the honest shape rather than the generous one. `DeliveryEvidence`
        // records what the **verification chain** did, and that chain genuinely performs neither
        // §10.1's key-absence rule nor §10.3's order binding — it holds a commitment and two byte
        // arrays, and has no tags and no order. Where those checks *were* performed, they were
        // performed by the `type=5` and `kind:15` decoders and recorded on this order as it passed
        // through `committed` and `released`. Replacing the record here would drop them, and
        // leaving them in the not-performed set beside them would have the same order saying both.
        val performed = order.deliveryChecksPerformed + event.evidence.checksPerformed
        return advance(
            order.with(
                state = OrderState.SETTLED,
                deliveryChecksPerformed = performed,
                deliveryChecksNotPerformedHere = event.evidence.checksNotPerformedHere - performed,
            ),
        )
    }

    // ------------------------------------------------------------------ small shared pieces

    /**
     * §4.6's clock reading, or the refusal it earns: [TransitionRejection.CLOCK_UNAVAILABLE] when
     * the seam declined, [TransitionRejection.CLOCK_READING_BEFORE_EPOCH] when it answered a
     * negative number of unix seconds. A negative reading is never compared with a deadline —
     * the clock is broken, and §4.3 has no timestamp before 1970 for it to be right about.
     *
     * The only place in this file a time comes from. There is no parameter on any of
     * [OrderMachine]'s methods that accepts a time — no `Long` of unix seconds at all — which is
     * what makes "a counterparty's `created_at` never drives a deadline" structural rather than a
     * rule to remember.
     */
    private fun reading(): ClockReading = when (val answer = clock.now()) {
        is SeamAnswer.Unavailable -> ClockReading.Unavailable
        is SeamAnswer.Provided ->
            if (answer.value < 0L) ClockReading.BeforeEpoch else ClockReading.At(answer.value)
    }

    /** The refusal a deadline check returns when [reading] found no usable time. */
    private fun clockRefusal(order: Order, refused: ClockReading.Refused): OrderOutcome = when (refused) {
        ClockReading.Unavailable -> refuse(
            order,
            TransitionRejection.CLOCK_UNAVAILABLE,
            "the injected clock answered unavailable, and §4.6 makes it authoritative for every " +
                "deadline; there is no ambient fallback",
        )
        ClockReading.BeforeEpoch -> refuse(
            order,
            TransitionRejection.CLOCK_READING_BEFORE_EPOCH,
            "the injected clock answered a time before 1970; §4.3 timestamps are non-negative, so " +
                "the clock is broken and no deadline is evaluated against it",
        )
    }

    /**
     * What [reading] found: a usable time, or which of the two refusals a deadline check returns
     * instead. Objects and a `Long`, deliberately no `String`: `OrderStructureTest` pins every
     * member of this package that takes one.
     */
    private sealed interface ClockReading {
        class At(val unixSeconds: Long) : ClockReading
        sealed interface Refused : ClockReading
        object Unavailable : Refused
        object BeforeEpoch : Refused
    }

    private fun noCommitment(order: Order): OrderOutcome = refuse(
        order,
        TransitionRejection.NO_COMMITMENT_TO_CHECK_AGAINST,
        "this order holds no §10.1 commitment to check a release or its evidence against",
    )

    private fun wrongSender(order: Order, why: String): OrderOutcome =
        refuse(order, TransitionRejection.WRONG_SENDER, why)

    private fun wrongState(order: Order, state: String, legal: String): OrderOutcome = refuse(
        order,
        TransitionRejection.WRONG_STATE_FOR_EVENT,
        "§11.2 lists no such transition out of `$state`. What is legal there: $legal",
    )

    private fun ground(failure: DeliveryFailure): DisputeGround = when (failure) {
        DeliveryFailure.SERVED_BYTES_HASH_MISMATCH -> DisputeGround.SERVED_BYTES_HASH_MISMATCH
        DeliveryFailure.BLOB_DID_NOT_DECRYPT -> DisputeGround.BLOB_DID_NOT_DECRYPT
        DeliveryFailure.PLAINTEXT_BYTES_HASH_MISMATCH -> DisputeGround.PLAINTEXT_BYTES_HASH_MISMATCH
    }

    public companion object {

        /**
         * §11.2's release timeout, for accepted terms carrying no `deliver_by`.
         *
         * **Local policy, not a wire value.** §11.2 requires an implementation to apply and
         * display one and deliberately fixes no number — revision `1.1`'s unnamed "release
         * deadline" is exactly what §11.2 says left the transition "untestable and invented per
         * implementation". A constant so it can be named in a UI; a parameter with a default so a
         * client and a test can both pin it. Seven days, as a number of seconds.
         */
        public const val DEFAULT_RELEASE_TIMEOUT_SECONDS: Long = 7L * 24L * 60L * 60L

        /**
         * §11.2's verification window, applied to whichever anchor the `released → disputed`
         * deadline row names first.
         *
         * **Local policy, not a wire value**, exactly as [DEFAULT_RELEASE_TIMEOUT_SECONDS] is, and
         * for a stronger reason: §11.2 states that this deadline is local *by design*, because the
         * only act outstanding at `released` is the buyer's own. §11.2 fixes no number. A constant
         * so it can be named in a UI — the row requires an implementation to **display** the
         * window, not merely to apply it — and a parameter with a default so a client and a test
         * can both pin it. Seven days, as a number of seconds: long enough for a buyer who was
         * away from the client when the key arrived, short enough that an order whose blob never
         * verified reaches a terminal state while both parties still remember it.
         */
        public const val DEFAULT_VERIFICATION_TIMEOUT_SECONDS: Long = 7L * 24L * 60L * 60L
    }
}

/**
 * [atSeconds] plus [durationSeconds], or `null` if the sum does not fit in a `Long`.
 *
 * The check is written as a comparison before the addition, so no wrapped value is ever
 * computed. Internal so the boundary can be pinned by a test at [Long.MAX_VALUE] and
 * [Long.MIN_VALUE] directly, rather than only through a clock.
 */
internal fun checkedDeadline(atSeconds: Long, durationSeconds: Long): Long? = when {
    durationSeconds > 0L && atSeconds > Long.MAX_VALUE - durationSeconds -> null
    durationSeconds < 0L && atSeconds < Long.MIN_VALUE - durationSeconds -> null
    else -> atSeconds + durationSeconds
}

/**
 * §10.3's four operands, compared by value.
 *
 * `DeliverableCommitment` is a plain class with no `equals`, so `==` on two of them is JVM
 * identity — which would refuse a caller that rebuilt an equal commitment from the same tags, and
 * would be a real bug for anything that persists an order and reloads it. Comparing the four
 * values it holds is both correct and the same comparison §10.3 makes; `DeliverableHash` has
 * proper value equality of its own.
 */
private fun sameCommitment(one: DeliverableCommitment, other: DeliverableCommitment): Boolean =
    one.x == other.x &&
        one.ox == other.ox &&
        one.mimeType == other.mimeType &&
        one.sizeBytes == other.sizeBytes

/**
 * The order after one transition, built by reading every field through [Order]'s own interface.
 *
 * File-private, so the only way to reach it is [OrderMachine]. Reading through the interface
 * rather than casting to the implementation is not style: it means no unchecked cast exists here
 * to be wrong later.
 */
private fun Order.with(
    state: OrderState = this.state,
    provider: String? = this.provider,
    commitment: DeliverableCommitment? = this.commitment,
    paidAt: Long? = this.paidAt,
    releasedAt: Long? = this.releasedAt,
    disputeGround: DisputeGround? = this.disputeGround,
    paymentChecksPerformed: Set<PaymentCheck> = this.paymentChecksPerformed,
    paymentChecksNotPerformedHere: Set<PaymentCheck> = this.paymentChecksNotPerformedHere,
    deliveryChecksPerformed: Set<DeliveryCheck> = this.deliveryChecksPerformed,
    deliveryChecksNotPerformedHere: Set<DeliveryCheck> = this.deliveryChecksNotPerformedHere,
): Order = OpenOrder(
    // Not a parameter: §7.6 makes a counter-proposal a new `type=1` with a **new** order id, so
    // there is no transition in §11.2 that moves an order to another id, and a door for one here
    // is a door for a receipt to be re-pointed at the evidence it did not match.
    id = id,
    state = state,
    terms = terms,
    provider = provider,
    commitment = commitment,
    paidAt = paidAt,
    releasedAt = releasedAt,
    disputeGround = disputeGround,
    paymentChecksPerformed = paymentChecksPerformed,
    paymentChecksNotPerformedHere = paymentChecksNotPerformedHere,
    deliveryChecksPerformed = deliveryChecksPerformed,
    deliveryChecksNotPerformedHere = deliveryChecksNotPerformedHere,
)

/** The single implementation of [Order]. Private, so the only doors in are on [OrderMachine]. */
private class OpenOrder(
    override val id: OrderId,
    override val state: OrderState,
    override val terms: OrderTerms,
    override val provider: String?,
    override val commitment: DeliverableCommitment?,
    override val paidAt: Long?,
    override val releasedAt: Long?,
    override val disputeGround: DisputeGround?,
    override val paymentChecksPerformed: Set<PaymentCheck>,
    override val paymentChecksNotPerformedHere: Set<PaymentCheck>,
    override val deliveryChecksPerformed: Set<DeliveryCheck>,
    override val deliveryChecksNotPerformedHere: Set<DeliveryCheck>,
) : Order {

    /**
     * Names the state, the dispute ground and the two capability records — and no order id, no
     * counterparty pubkey, no amount, no deadline, no hash and no clock reading, [Order.paidAt] and
     * [Order.releasedAt] included (§12 item 11, and see [OrderTerms.toString]).
     *
     * The order id is the addition worth naming: §12 item 11 lists it beside key material and
     * preimages, and it is a correlation handle for anyone who later learns it, so it is absent
     * here rather than delegated to `OrderId.toString` — a redaction two levels deep is one a
     * later `id.toHex()` in a debugging line undoes without anybody noticing. [Order.provider] is
     * absent for the same reason under §12 item 2: it is a plain 64-character hex `String` with no
     * `toString` of its own to redact, so this is the only place that can keep it out of a log.
     */
    override fun toString(): String =
        "Order(state=${state.token ?: "unknown"}, disputeGround=$disputeGround, " +
            "paymentChecksNotPerformedHere=$paymentChecksNotPerformedHere, " +
            "deliveryChecksNotPerformedHere=$deliveryChecksNotPerformedHere)"
}

/** The single implementation of [OrderOutcome.Advanced]. */
private class Moved(override val order: Order) : OrderOutcome.Advanced {

    override fun toString(): String = "Advanced($order)"
}

/** The single implementation of [OrderOutcome.Refused]. */
private class NotMoved(
    override val order: Order,
    override val reason: TransitionRejection,
    override val detail: String,
) : OrderOutcome.Refused {

    override fun toString(): String = "Refused($reason: $detail)"
}

/**
 * The single implementation of [OrderOutcome.Refused.ChecksNotPerformed].
 *
 * Its own class rather than a field on [NotMoved], so the only way to obtain a `missing` set is
 * the one refusal it is true of, and so [NotMoved] cannot come to carry an empty one that reads as
 * "nothing was skipped".
 */
private class ChecksMissing(
    override val order: Order,
    override val missing: Set<PaymentCheck>,
    override val detail: String,
) : OrderOutcome.Refused.ChecksNotPerformed {

    override val reason: TransitionRejection get() = TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED

    /** The check names are §9.2's rules, not identifiers, so §12 item 11 permits them. */
    override fun toString(): String = "Refused($reason: missing=$missing: $detail)"
}

private fun advance(order: Order): OrderOutcome.Advanced = Moved(order)

private fun refuse(order: Order, reason: TransitionRejection, detail: String): OrderOutcome.Refused =
    NotMoved(order, reason, detail)

private fun refuseChecks(
    order: Order,
    missing: Set<PaymentCheck>,
): OrderOutcome.Refused.ChecksNotPerformed = ChecksMissing(
    order,
    readOnlySetOf(missing),
    "§9.2 requires an implementation to perform **all** of its checks before treating a payment " +
        "as made, and §11.1, §11.2 and §17 item 6 all define `paid` as receipts verified per " +
        "§9.2. At least one check that applies to these receipts was performed by nobody, so this " +
        "order fails closed rather than advancing on partial evidence",
)
