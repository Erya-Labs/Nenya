package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.settlement.AcceptedPaymentRequest
import dev.eryalabs.nenya.settlement.FeeTermPoint
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.Settlement
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// §9.2's two doors, crossed. These are the two entry points where a translation that drifted would
// loosen evidence, which is why T43 gives them their own task and their own proof.
//
// Neither takes a payment hash, a preimage or an amount as an argument, and that is not an economy:
// §9.2 check 3 says *which* payment hash — the `p` field of the BOLT-11 invoice — and `Settlement`
// reads it off the stored invoice it has already parsed for checks 4 and 5. A boundary that offered
// a page the chance to supply one would have re-opened the exact hole decision I closed, where
// check 1 and check 3 could be about two different invoices. So the arguments below are the receipt
// as it was opened, the client's store, and the terms the order was accepted on — and nothing else.
// =================================================================================================

/**
 * §9.2's evidence over one receipt: checks 1, 4 and 5 against the stored request, check 3's payment
 * hash read out of that same stored invoice, and check 2 with check 3's comparison.
 *
 * The facade performs **no check of its own**. It decodes the receipt through
 * `PaymentReceipt.decode` — whose constructor is `internal`, so there is no other door — and makes
 * one call to `Settlement.verify`. Every refusal reported is that call's own `SettlementRejection`,
 * or §9.2 check 3's `PaymentRejection.PREIMAGE_MISMATCH`, which reaches a page under its own name
 * for the reason `Settlement` gives for not re-badging it: §9 calls check 3 the load-bearing rule
 * of the entire document and one failure gets one name.
 *
 * ### `ok` is not evidence, and a page reading it as evidence has read §9.1 backwards
 *
 * [JsSettlementResult.ok] says the call produced a `Settlement`, which includes §9.4's
 * `Unverified` — a `bitcoin` or `ecash` receipt is a conformant message that evidences **nothing**.
 * [JsSettlement.evidenced] is the field that says whether §9.2's evidence exists, and
 * [JsSettlement.checksNotPerformedHere] is what names the gap. Both are the library's answers.
 *
 * @param receipt the `kind:17` rumor as [open] handed it back. A `kind:14` chat is the one kind
 *   `PaymentReceipt.decode`'s parameter type excludes; see [JsCrossing.NOT_A_BOUND_RUMOR]. A
 *   well-formed rumor of another bound kind reaches the decoder and gets its own `NOT_A_RECEIPT`.
 * @param store the page's persistence, holding the `type=2` requests it accepted. Reading it does
 *   not make it trustworthy: a store is the embedding client's own and §13 says Nenya does not
 *   defend its user against the client embedding it.
 * @param terms the **accepted** terms of this order, which is where check 4's expected amount comes
 *   from — `split.price` for a provider receipt and `split.fee` for a fee one. Whether these are
 *   [receipt]'s own order's terms is **not** checked here, exactly as it is not on the JVM: see
 *   `Settlement.verify`'s own note, and `TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER` one layer
 *   up, which is what stops a receipt from another thread moving an order.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun verifySettlement(
    receipt: JsRumor,
    store: JsPaymentRequestStore,
    terms: JsOrderTerms,
): JsSettlementResult = jsGuarded({ JsSettlementResult(null, it) }) {
    JsSettlementResult(
        Settlement.verify(
            receipt = PaymentReceipt.decode(receipt.asBoundRumor("kind:17 payment receipt")),
            store = store.asPaymentRequestStore(),
            split = terms.asOrderTerms().split,
        ),
        null,
    )
}

/**
 * §9.2 check 6, whole, on top of everything [verifySettlement] does — the `payee=fee` path.
 *
 * Check 6 is the one §9.2 obligation with three sub-checks — §8.4's fee term byte-identical across
 * every point, §8.7's sealing key, and §8.5's `awaiting_payment` precondition read from the order
 * rather than from a `status` token a counterparty chose — and all three are performed by
 * `Settlement.verifyFeeReceipt` and none by this function. A provider receipt handed here is
 * refused `PAYEE_IS_NOT_FEE` by that call, not by this one.
 *
 * @param receipt the `kind:17` rumor as [open] handed it back.
 * @param store the page's persistence. Read twice — check 1's invoice comparison and §8.7's sealing
 *   key — and neither read makes it trustworthy.
 * @param order **this library's own** order, from [openOrder] or [stepOrder], which is the only
 *   thing that can produce one. Whether it is the order this receipt names is not checked here;
 *   `TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER` one layer up is what refuses that.
 * @param earlierPoints every point at which a `fee` tag appears — or deliberately does not — for
 *   this order **before** this receipt, in the order observed. §8.4's proposal, acceptance and fee
 *   `type=2` are REQUIRED to be among them and their absence is `FEE_TERM_POINT_MISSING` rather
 *   than a smaller comparison: this receipt's own point is appended by the library and not taken
 *   from here, so without that requirement an empty array would have the receipt compared against
 *   itself and agree.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun verifyFeeReceipt(
    receipt: JsRumor,
    store: JsPaymentRequestStore,
    order: JsOrder,
    earlierPoints: Array<JsFeeTermSighting> = emptyArray(),
): JsSettlementResult = jsGuarded({ JsSettlementResult(null, it) }) {
    JsSettlementResult(
        Settlement.verifyFeeReceipt(
            receipt = PaymentReceipt.decode(receipt.asBoundRumor("kind:17 payment receipt")),
            store = store.asPaymentRequestStore(),
            order = order.order,
            earlierPoints = earlierPoints.map { it.asFeeTermSighting() },
        ),
        null,
    )
}

/**
 * §8.6's acceptance door: a `type=2` decoded, judged and persisted — or the refusal as a value.
 *
 * ### What this closes. T44 gap (1)
 *
 * Nothing T42 or T43 exported constructs an `AcceptedPaymentRequest`, and §9.2 check 1 compares a
 * receipt against *the stored request* — so a page could build and seal a bill, and could verify a
 * receipt, and had no way at all to accept one. An order could not leave `committed`. This is the
 * missing call, and it is the one the operator's page had to write by hand on a local branch.
 *
 * ### The facade performs no check of its own, and this is where that matters most
 *
 * `AcceptedPaymentRequest.accept` is a **seven-refusal** call sitting on the evidence path, and
 * every one of those refusals is reached here by the call a JVM caller makes: §8.6's provider
 * sender rule, §8.7's fee sealing key through `Settlement.checkFeePaymentRequest`, Appendix C's
 * parser, §9.2 check 4's amount, check 5's expiry against the clock reading taken at this moment,
 * and §8.6's two uniqueness rules. The clock is read inside that call and an unavailable one
 * refuses `CLOCK_UNAVAILABLE` rather than storing a request against a fabricated time. This
 * function decides none of it.
 *
 * ### Why the acceptance is three arguments and not a `JsOrder`
 *
 * `accept` takes §7.6's **checked** `Acceptance.Accepted`, and decision J records why: a caller
 * handed the order, the provider key and the terms separately can assemble three halves of three
 * different orders, and would have §9.2 check 4's expected amount computed against terms nobody
 * accepted. So the acceptance is rebuilt here from the two rumors and the resolved provider key, by
 * the same `crossedAcceptance` [stepOrder]'s `acceptance_received` uses — one function, so the two
 * crossings cannot come to mean different things by "the acceptance for this order". §8.3's split
 * the decode needs comes off that acceptance's own terms for the same reason, rather than crossing
 * as a fourth value a page could state differently.
 *
 * @param request the `kind:16` `type=2` as [open] handed it back.
 * @param proposalRumor §7.5's `type=1` for this order, as [open] handed it back.
 * @param acceptanceRumor §7.6's `status=accepted` `type=3`, as [open] handed it back.
 * @param providerPubkey the provider's key **as the page resolved it**, independently of both
 *   messages (§7.6). See [JsOrderEvent.providerPubkey].
 * @param store the page's persistence (§17 item 6). Written through, and the record handed back is
 *   the one this library minted — never whatever the store returned (§13).
 * @param environment read for [JsEnvironment.nowSeconds] only. An absent clock is the fail-closed
 *   default and refuses the request outright, because §9.2 check 5 is unperformable without that
 *   reading and there is no shape here that holds an invoice and no time.
 * @param earlierPoints §8.4's points observed **before** this request, for a `payee=fee` one;
 *   ignored for a provider request, which §8.4 forbids requiring a `fee` tag on. Empty is a
 *   refusal rather than a convenience: `checkFeePaymentRequest` requires the proposal and the
 *   acceptance to be among them, so a page naming none has its fee request rejected
 *   `FEE_TERM_POINT_MISSING` rather than stored unchecked.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun acceptPaymentRequest(
    request: JsRumor,
    proposalRumor: JsRumor,
    acceptanceRumor: JsRumor,
    providerPubkey: String,
    store: JsPaymentRequestStore,
    environment: JsEnvironment = JsEnvironment(),
    earlierPoints: Array<JsFeeTermSighting> = emptyArray(),
): JsAcceptedRequestResult = jsGuarded({ JsAcceptedRequestResult(null, it) }) {
    val acceptance = crossedAcceptance(
        proposalRumor = proposalRumor,
        acceptanceRumor = acceptanceRumor,
        providerPubkey = providerPubkey,
        field = "acceptPaymentRequest.acceptanceRumor",
    )
    JsAcceptedRequestResult(
        AcceptedPaymentRequest.accept(
            request = PaymentRequest.decode(
                rumor = request.asBoundRumor("kind:16 type=2 payment request"),
                split = acceptance.terms.split,
            ),
            acceptance = acceptance,
            into = store.asPaymentRequestStore(),
            clock = environment.asClock(),
            feeTermPoints = earlierPoints.map { it.asFeeTermSighting() },
        ),
        null,
    )
}

// =================================================================================================
// The boundary types.
// =================================================================================================

/**
 * §9's answer about one receipt: what was checked, what was not, and whether there is evidence.
 *
 * ### The preimage does not cross, and the narrowing is deliberate
 *
 * `VerifiedPayment` publishes the `Preimage` this library hashed. It is not published here. Two
 * reasons, and the first is sufficient: the preimage is already in the receipt the page handed in,
 * so crossing it back adds nothing a page does not hold. The second is §9.1's — the evidence is the
 * *verification*, not a value, and a facade that handed back a preimage-shaped field invites a page
 * to treat possession of the value as the fact. [paymentHashHex] crosses because it is check 3's
 * operand and came off the **stored** invoice, which is the thing a page cannot otherwise see this
 * library read.
 *
 * This claims strictly less than the Kotlin value does, which is the direction STOP RULE 5 permits.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsSettlement internal constructor(internal val settlement: Settlement) {

    /** §7.4's `order` id the receipt named. */
    public val orderIdHex: String get() = settlement.order.toHex()

    /** §8.6's payee role the receipt named, as its token — `provider` or `fee`. Exactly one. */
    public val payee: String get() = settlement.payee.token

    /** Which rail the receipt named, as `PaymentMedium.name` (§9.2, §9.4). */
    public val medium: String get() = settlement.medium.name

    /**
     * True for a `Settlement.Evidenced`, false for §9.4's `Unverified`.
     *
     * The field that says whether §9.2's evidence exists. False is a conformant answer and not an
     * error: §9.4 says v1 defines no verification rule for `bitcoin` or `ecash`, so a receipt on
     * one of those rails MAY be parsed and MUST be treated as unverified — which is what an
     * `Unverified` carrying no payment **is**, made structural.
     */
    public val evidenced: Boolean get() = settlement is Settlement.Evidenced

    /**
     * §9.2 check 3's operand: the 256-bit `p` field of the **stored** BOLT-11 invoice, as 64
     * lowercase hex characters — or `null` on an `Unverified`, which parsed no invoice.
     */
    public val paymentHashHex: String?
        get() = (settlement as? Settlement.Evidenced)?.payment?.paymentHash?.toHex()

    /** The §9.2 checks performed to produce this value, as `PaymentCheck.name`s. */
    public val checksPerformed: Array<String>
        get() = settlement.checksPerformed.map { it.name }.toTypedArray()

    /**
     * The §9.2 checks this library did **not** perform, which the page must perform or account for
     * before treating the payment as fully verified (§17).
     *
     * **Empty is a reachable answer and it is the point of the whole settlement path** — and a page
     * reading emptiness as a warrant that a payment settled has still read §9.1 backwards: the
     * evidence is the verification this library performed, not this record of it. An `Unverified`
     * names everything applicable, because §9.4 says a rail v1 defines no rule for evidences
     * nothing at all.
     */
    public val checksNotPerformedHere: Array<String>
        get() = settlement.checksNotPerformedHere.map { it.name }.toTypedArray()

    /** Names the payee and the rail. §12 item 2 keeps the order id and the hash out of a log. */
    override fun toString(): String = "JsSettlement(payee=$payee, medium=$medium)"
}

/**
 * What [verifySettlement] and [verifyFeeReceipt] answered, with [kind] as the discriminator.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsSettlementResult internal constructor(
    private val answer: Settlement?,
    private val refusal: JsRefusal?,
) {

    /** `"settlement"` or `"refused"`. */
    public val kind: String get() = if (answer != null) "settlement" else "refused"

    /**
     * True exactly when [kind] is `"settlement"`.
     *
     * **Not evidence of payment.** It says the call produced an answer, and §9.4's `Unverified` is
     * one. [JsSettlement.evidenced] is the field about evidence.
     */
    public val ok: Boolean get() = answer != null

    /** The answer, when [ok]. */
    public val settlement: JsSettlement? get() = answer?.let { JsSettlement(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /**
     * Which rejection enum [reason] came from.
     *
     * `SettlementRejection` for §8.4's, §8.7's, §9.2's and Appendix C's own refusals, and
     * `PaymentRejection` for check 3's `PREIMAGE_MISMATCH` — two vocabularies, because the library
     * raises two and which one says which rule broke.
     */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an invoice, a preimage or a key (§12 item 11, STOP RULE 14). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsSettlementResult(settlement)" else "JsSettlementResult(refused: $reason)"
}

/**
 * One `type=2` payment request this library accepted and the page persisted, as an opaque handle.
 *
 * ### Why it is opaque, and what a page can do with one
 *
 * `AcceptedPaymentRequest`'s constructor is `internal` and its only door is
 * `AcceptedPaymentRequest.accept`, which performs §8.6's, §8.7's, Appendix C's and §9.2 checks 4
 * and 5's rules *before* anything is stored. That is the whole value of the type: a record in a
 * store is one this library judged. So this crossing carries the Kotlin record rather than its
 * fields, and a page hands it straight back through [JsPaymentRequestStore.find] — which is exactly
 * what a store does. The four fields below are published for display and are the record's own.
 *
 * **The door that mints one is [acceptPaymentRequest], and it is its own entry point.** `accept`
 * takes a checked §7.6 acceptance and writes to the store, and a write is not a thing [stepOrder]
 * or [verifySettlement] may do as a side effect of being asked a question — which is why T43 left
 * this type crossing *out* only, through the store seam, and why T44 opened the door as a call of
 * its own rather than as a parameter on an existing one. `JsOrderEvent.requests` takes an array of
 * these, so a page hands the records it accepted straight to §11.2's `committed → awaiting_payment`
 * row without unpacking one.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsAcceptedRequest internal constructor(internal val accepted: AcceptedPaymentRequest) {

    /** §7.4's `order` id the request named. */
    public val orderIdHex: String get() = accepted.order.toHex()

    /** §8.6's payee role, as its token — `provider` or `fee`. */
    public val payee: String get() = accepted.payee.token

    /** The BOLT-11 invoice as it was stored, verbatim: check 1 compares this string (§9.2). */
    public val invoice: String get() = accepted.invoice.text

    /** The injected clock's reading at the moment of acceptance, as a decimal string (§9.2 check 5). */
    public val acceptedAtSeconds: String get() = JsDecimal.of(accepted.acceptedAt)

    /** §8.7's operand: the `pubkey` of the seal the `type=2` arrived in. */
    public val sealedBy: String get() = accepted.sealedBy

    /** Names the payee only: an invoice and an order id are §12 item 11 values. */
    override fun toString(): String = "JsAcceptedRequest(payee=$payee)"
}

/**
 * What [acceptPaymentRequest] answered: the record this library minted and stored, or the refusal.
 *
 * ### `ok` says the request was accepted, and that is not a statement about payment
 *
 * An accepted `type=2` is an invoice this library judged and the page persisted — §9.2 check 1's
 * operand, and nothing more. No payment has happened, no preimage exists, and the only payment
 * evidence in this library is still a preimage [verifySettlement] hashed itself.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsAcceptedRequestResult internal constructor(
    private val accepted: AcceptedPaymentRequest?,
    private val refusal: JsRefusal?,
) {

    /** `"accepted"` or `"refused"`. */
    public val kind: String get() = if (accepted != null) "accepted" else "refused"

    /** True exactly when [kind] is `"accepted"`. See this class's note on what that does not mean. */
    public val ok: Boolean get() = accepted != null

    /**
     * The record, when [ok] — the one `accept` minted, never the value the page's store returned.
     *
     * A page hands it back through [JsPaymentRequestStore.find] and to
     * [JsOrderEvent.requests]; it has no reason to take one apart, which is why it is opaque.
     */
    public val request: JsAcceptedRequest? get() = accepted?.let { JsAcceptedRequest(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /**
     * Which rejection enum [reason] came from.
     *
     * `SettlementRejection` for §8.6's, §8.7's, §9.2's and Appendix C's own refusals;
     * `ChannelRejection` where §7.6's two rumors did not decode or the acceptance was refused;
     * `JsCrossing` where §7.6 answered a counter-proposal, which is no acceptance to accept against.
     */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an invoice or a key (§12 item 11, STOP RULE 14). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String =
        if (ok) "JsAcceptedRequestResult(accepted)" else "JsAcceptedRequestResult(refused: $reason)"
}

/**
 * The page's `type=2` persistence, supplied as two functions.
 *
 * ### Functions and not an interface, on T42's terms
 *
 * A JavaScript object cannot implement a Kotlin interface — Kotlin/JS dispatches interface calls
 * through machinery a plain object does not carry — so this seam crosses as its methods, exactly as
 * [JsSigner] does.
 *
 * ### Failing closed, and what that means for a store
 *
 * `PaymentRequestStore` has no `FAIL_CLOSED` constant to stand in for, because a store that
 * refuses to answer is not a different thing from a store with nothing in it: both mean §9.2 check
 * 1 has no stored invoice to compare against, and the library's own answer for that is
 * `NO_STORED_REQUEST`. So an absent or `null`-answering [find] produces that refusal and never an
 * accepted receipt, which is the closed direction.
 *
 * Nothing a store returns is evidence of anything (§9.1, §13). It is the embedding client's own
 * persistence, and Nenya protects its user against counterparties and relays — not against the
 * client embedding it.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsPaymentRequestStore public constructor(

    /**
     * The record stored for `(orderIdHex, payeeToken)`, or `null` for none.
     *
     * `null` is §9.2 check 1 having nothing to compare against, and the library reports it as
     * `NO_STORED_REQUEST`. An absent function answers `null` for every lookup, which is the same
     * fact about a page that has not finished wiring itself up.
     */
    public val find: ((String, String) -> JsAcceptedRequest?)? = null,

    /**
     * Persist the record this library minted (§17 item 6).
     *
     * Answers nothing, and that is the Kotlin contract rather than a simplification:
     * `AcceptedPaymentRequest.accept` returns "the record this function minted and handed to
     * `into` — **never** the value `into` returned, which a client store chooses and which §13 does
     * not make trustworthy". So there is no answer for a page to give and none is taken.
     *
     * Not reached by either of T43's two entry points: §9.2's checks read a store and do not write
     * one. It is here so the seam is whole, on the terms [JsAcceptedRequest] records.
     */
    public val store: ((JsAcceptedRequest) -> Unit)? = null,
) {

    /** Names no function, no invoice and no key (§12 item 11, STOP RULE 14). */
    override fun toString(): String = "JsPaymentRequestStore(redacted)"
}

/**
 * §8.4's observation of a `fee` tag at one point in an order's history — or of its deliberate
 * absence.
 *
 * §8.4 compares the raw tag **elements**, which is what "byte-identical" means, so this carries the
 * row as it appeared and not a basis-point count. `null` is a point at which no `fee` tag appeared,
 * which §8.4 makes a legitimate sighting rather than a missing one: on the three REQUIRED points it
 * is a divergence, and on the OPTIONAL ones it is what §8.4 permits.
 *
 * Built through `FeeTermSighting.of`, so §8.1's two legal arities are that function's rule and not
 * this boundary's.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsFeeTermSighting public constructor(

    /**
     * Which of §8.4's points this is, as `FeeTermPoint.name` — `ORDER_PROPOSAL`, `ACCEPTANCE`,
     * `FEE_PAYMENT_REQUEST`, `FEE_RECEIPT`, `BID`, `PROVIDER_PAYMENT_REQUEST`, `PROVIDER_RECEIPT`.
     *
     * A name no constant matches has no `FeeTermPoint` to be and is
     * [JsCrossing.UNCONSTRUCTIBLE_VALUE]: §8.4's points are a closed set this library enumerates,
     * and inventing an eighth would put a comparison on the wire against a point §8.4 does not have.
     */
    public val point: String,

    /** The `fee` row as it appeared — `["fee", "0"]` or `["fee", "<bps>", "<recipient>"]` — or `null`. */
    public val feeTag: Array<String>? = null,
) {

    /** Names the point only: a fee recipient is a §12 item 2 value. */
    override fun toString(): String = "JsFeeTermSighting(point=$point)"
}

// =================================================================================================
// Translation.
// =================================================================================================

/** This seam as the `PaymentRequestStore` §9.2's checks take. */
internal fun JsPaymentRequestStore.asPaymentRequestStore(): PaymentRequestStore =
    CrossedPaymentRequestStore(this)

private class CrossedPaymentRequestStore(private val js: JsPaymentRequestStore) : PaymentRequestStore {

    /**
     * Hands [accepted] to the page and answers the record this library minted.
     *
     * The page's answer is deliberately discarded: see [JsPaymentRequestStore.store]. A page that
     * supplied no function gets no persistence and no pretence of it — §17 item 6's obligation is
     * about what an implementation has kept, and this layer cannot keep anything.
     */
    override fun store(accepted: AcceptedPaymentRequest): AcceptedPaymentRequest {
        js.store?.invoke(JsAcceptedRequest(accepted))
        return accepted
    }

    /** The record the page holds for this order and payee, or `null` — §9.2 check 1's operand. */
    override fun find(order: OrderId, payee: Payee): AcceptedPaymentRequest? {
        val find = js.find ?: return null
        return find(order.toHex(), payee.token)?.accepted
    }

    /** Names no record (§12 item 11). */
    override fun toString(): String = "CrossedPaymentRequestStore(redacted)"
}

/** This sighting as the `FeeTermSighting` §8.4's comparison takes, through that type's own factory. */
internal fun JsFeeTermSighting.asFeeTermSighting(): FeeTermSighting = FeeTermSighting.of(
    point = crossedConstant("fee term point", point, FeeTermPoint.entries) { it.name },
    feeTag = feeTag?.toList(),
)

/**
 * The constant in [candidates] whose [nameOf] is [crossed], or the crossing's refusal.
 *
 * The one place this package reads an enum constant by name, so the refusal is written once. It is
 * **not** used for a vocabulary the library has an `UNKNOWN` constant for: §11.1's status token,
 * §7.4's `type`, §9.2's `medium` and §5's `status` all cross through the library's own codec, which
 * answers `UNKNOWN` and whose caller's refusal is what reports it. This is for the closed sets that
 * have no such constant — §8.4's points and §10.4's three failures — where there is genuinely no
 * Kotlin value to hand the call.
 */
internal fun <T> crossedConstant(
    what: String,
    crossed: String,
    candidates: List<T>,
    nameOf: (T) -> String,
): T = candidates.firstOrNull { nameOf(it) == crossed } ?: throw JsCrossing(
    JsCrossing.UNCONSTRUCTIBLE_VALUE,
    what,
    "this library enumerates a closed set of $what values and the one that crossed names none of " +
        "them, so there is no value to hand the call; the set is " +
        "${candidates.map { nameOf(it) }} and this boundary does not choose one on the caller's behalf",
)
