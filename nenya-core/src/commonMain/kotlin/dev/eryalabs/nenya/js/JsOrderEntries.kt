package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderOutcome
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.order.DeliveryFailure
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.settlement.Settlement
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// §11.2's state machine, crossed. The last two of T43's six entry points, and the pair that makes
// the rest of this facade a trade rather than a message log.
//
// The machine is stateless: `OrderMachine` holds a clock and two local timeout policies and nothing
// else, and every transition is a pure function of (order, event). So the machine crosses as its
// configuration — `JsOrderMachine` — and is constructed per call, while the *order* crosses as an
// opaque `JsOrder` holding the value only this library can produce. A page therefore cannot hand
// `stepOrder` an order it assembled, which is §11.3's invariants made structural one layer out:
// `Order`'s single implementation is private and its only doors are `open` and a prior transition.
// =================================================================================================

/**
 * §11.2's first row: a `type=1` proposal opens an order in `proposed`.
 *
 * The one event that opens an order rather than advancing one, and the only door to a `JsOrder`.
 * The facade performs no check of its own: the order id goes through `OrderId.ofHex`, the terms
 * through `OrderTerms.of` — which is §8.3's own arithmetic and §7.5's own deadline rules — and the
 * open through `OrderMachine.open`.
 *
 * @param machine §4.6's clock and the two local timeout policies. `open` reads none of them; they
 *   are taken here so that one `JsOrderMachine` configures a whole thread, and so that a machine a
 *   page configured with a non-positive timeout is refused at the first call rather than the second.
 * @param orderIdHex §7.4's `order` id — 32 bytes of randomness the buyer drew, as 64 hex
 *   characters. Here because an order that does not know its own id cannot tell its own evidence
 *   from somebody else's.
 * @param terms the terms the proposal states. An absent fee term is §8.1's zero-fee proposal.
 * @param createdAtSeconds the `created_at` the proposal carried, or `null`. **Read by nothing**:
 *   §4.6 says every deadline MUST be evaluated against the injected clock and never against a
 *   counterparty's `created_at`, and this field is carried so that rule has a subject.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun openOrder(
    machine: JsOrderMachine,
    orderIdHex: String,
    terms: JsOrderTerms,
    createdAtSeconds: String? = null,
): JsOrderResult = jsGuarded({ JsOrderResult(null, it) }) {
    JsOrderResult(
        machine.asOrderMachine().open(
            OrderEvent.Proposal(
                order = OrderId.ofHex(orderIdHex),
                terms = terms.asOrderTerms(),
                createdAt = crossedSecondsOrNull("created_at", createdAtSeconds),
            ),
        ),
        null,
    )
}

/**
 * §11.2's fourteen rows: one event offered to one order, which moves or does not.
 *
 * ### This facade cannot become a second opinion about an order
 *
 * Every answer here comes from `OrderMachine.on`, and [JsOrderOutcome] publishes the order that
 * call returned — the **new** one when it advanced and the **unchanged** one when it refused.
 * There is no path through this function that constructs an order, edits a state, or reports a
 * state the machine did not reach. That is the point of T43's named mutation: make `stepOrder`
 * report `paid` from a refused outcome and the equality proof turns red, because the expected side
 * reads the state off the Kotlin `OrderOutcome` and the crossed side must agree field for field.
 *
 * ### §9.1 and STOP RULE 12 are one layer down, and nothing here weakens them
 *
 * The only events that may move an order into `paid`, `settled` or a pre-`paid` `disputed` are
 * `OrderEvent.Local`s — `receipts_verified`, `delivery_verified`, `clock_checked`,
 * `locally_disputed` — and the first two take values a page cannot forge: a `JsSettlement` comes
 * from [verifySettlement] or [verifyFeeReceipt], and `delivery_verified`'s evidence is built here
 * by this library's own two digest checks over bytes the page supplies. A `status_update` carrying
 * `paid` moves nothing from any state, which is §11.3 invariant 5 and is the machine's answer, not
 * this layer's.
 *
 * @param machine §4.6's clock and the two local timeout policies. Read for every deadline.
 * @param order the order as it stands, from [openOrder] or from a previous [stepOrder].
 * @param event what happened. See [JsOrderEvent] for the fourteen `type` tokens and the fields each
 *   takes; a token no variant answers to, or a missing required field, is
 *   [JsCrossing.UNCONSTRUCTIBLE_VALUE] and reaches the machine not at all.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun stepOrder(
    machine: JsOrderMachine,
    order: JsOrder,
    event: JsOrderEvent,
): JsOrderOutcome = jsGuarded({ JsOrderOutcome(null, it) }) {
    JsOrderOutcome(machine.asOrderMachine().on(order.order, event.asOrderEvent()), null)
}

// =================================================================================================
// The boundary types.
// =================================================================================================

/**
 * §11.2's machine as its configuration: §4.6's clock, and the two timeouts that are local policy.
 *
 * Both timeouts are **local policy and not wire values**, which is why they cross as the page's
 * choice rather than being fixed: §11.2 requires an implementation with no `deliver_by` to apply
 * and display a release timeout of its own, and to apply a verification window out of `released`.
 * `null` takes this library's default, which is what a JVM caller gets for omitting the argument.
 * A non-positive one is refused by `OrderMachine`'s own `init` — a zero timeout "is not a timeout,
 * it disputes an order the moment it is paid" — and that refusal crosses as a value like any other.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderMachine public constructor(

    /**
     * §4.6's clock, through [JsEnvironment.nowSeconds].
     *
     * An absent clock is `FAIL_CLOSED`, and §11.2's deadline rows then refuse
     * `CLOCK_UNAVAILABLE` rather than treating a deadline as unpassed — which is the closed
     * direction, because an order whose deadline cannot be read must not be allowed to drift past
     * it in silence. §4.6 makes this reading authoritative and nothing here substitutes for it.
     */
    public val environment: JsEnvironment = JsEnvironment(),

    /** §11.2's release timeout in seconds, as a decimal string, or `null` for this library's. */
    public val releaseTimeoutSeconds: String? = null,

    /** §11.2's verification window in seconds, as a decimal string, or `null` for this library's. */
    public val verificationTimeoutSeconds: String? = null,
) {

    override fun toString(): String = "JsOrderMachine(redacted)"
}

/**
 * One order as this library holds it — the handle every later transition is made against.
 *
 * Opaque by construction, on the terms [JsAcceptedRequest] records and for a stronger reason:
 * `Order`'s single implementation is `private` and its only doors are `OrderMachine.open` and a
 * transition that advanced. §11.3's invariants are properties of the values the machine produces,
 * so an order a page could assemble out of a state token and a price would be an order none of
 * them held. The fields below are what it publishes; there is no way to set one.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrder internal constructor(internal val order: Order) {

    /** §7.4's `order` id, 64 lowercase hex characters. */
    public val idHex: String get() = order.id.toHex()

    /** §11.1's state, as `OrderState.name`. */
    public val state: String get() = order.state.name

    /**
     * §11.1's wire token for the state, or `null` for `UNKNOWN`.
     *
     * `null` rather than `"unknown"`, because §11.1's unknown sink is a **treatment** and not a
     * token: it appears in neither column of §11.2, and a page emitting it would be putting a
     * status on the wire §11.1 does not define.
     */
    public val stateToken: String? get() = order.state.token

    /** True for §11.2's terminal states — `settled`, `cancelled`, `expired`, `disputed`. */
    public val terminal: Boolean get() = order.state.isTerminal

    /** True when §11.1 has a rule for this state at all; false for the unknown sink. */
    public val recognised: Boolean get() = order.state.isRecognised

    /** §8.2's `price_msat` of the accepted terms, as an exact decimal string. */
    public val priceMsat: String get() = JsDecimal.of(order.terms.split.price.millisatoshis)

    /** §8.3's fee, computed by this library's own arithmetic, as an exact decimal string. */
    public val feeMsat: String get() = JsDecimal.of(order.terms.split.fee.millisatoshis)

    /** §8.3's `total_msat` — price plus fee — as an exact decimal string. */
    public val totalMsat: String get() = JsDecimal.of(order.terms.split.total.millisatoshis)

    /** §8.1's fee in basis points, or `null` for an absent fee term. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? get() = when (val fee = order.terms.split.term) {
        is dev.eryalabs.nenya.money.FeeTerm.Stated -> fee.basisPoints
        dev.eryalabs.nenya.money.FeeTerm.Absent -> null
    }

    /** True exactly when §8.3's split requires a `payee=fee` invoice and receipt at all. */
    public val feePayeeRequired: Boolean get() = order.terms.split.feePayeeRequired

    /** §5.3's `expiration` of the accepted terms, unix seconds as a decimal string, or `null`. */
    public val expirationSeconds: String? get() = JsDecimal.ofOrNull(order.terms.expiration)

    /** §7.5's `deliver_by` of the accepted terms, unix seconds as a decimal string, or `null`. */
    public val deliverBySeconds: String? get() = JsDecimal.ofOrNull(order.terms.deliverBy)

    /** §7.6's provider key, once an acceptance established one. `null` before that. */
    public val provider: String? get() = order.provider

    /** §10.1's commitment, once the provider made one. `null` before that. */
    public val commitment: JsCommitment?
        get() = order.commitment?.let {
            JsCommitment(it.x.toHex(), it.ox.toHex(), it.mimeType, JsDecimal.of(it.sizeBytes))
        }

    /**
     * The **injected clock's** reading when this order reached `paid`, as a decimal string, or
     * `null`.
     *
     * The clock's and never a counterparty's `created_at` (§4.6). `null` where the clock was
     * unavailable at that moment: §11.2's release deadline then falls back to `deliver_by`, and
     * says it has none if that is absent too, rather than being computed from a fabricated time.
     */
    public val paidAtSeconds: String? get() = JsDecimal.ofOrNull(order.paidAt)

    /** The same for `released`, from which §11.2's verification window runs. */
    public val releasedAtSeconds: String? get() = JsDecimal.ofOrNull(order.releasedAt)

    /** Why the order is `disputed`, as `DisputeGround.name`, or `null` if it is not. */
    public val disputeGround: String? get() = order.disputeGround?.name

    /** The §9.2 checks performed on the receipts that moved this order, as `PaymentCheck.name`s. */
    public val paymentChecksPerformed: Array<String>
        get() = order.paymentChecksPerformed.map { it.name }.toTypedArray()

    /** The §9.2 checks nobody performed on them (§17, decision B). */
    public val paymentChecksNotPerformedHere: Array<String>
        get() = order.paymentChecksNotPerformedHere.map { it.name }.toTypedArray()

    /** §10's checks performed on the commitment and the release, as `DeliveryCheck.name`s. */
    public val deliveryChecksPerformed: Array<String>
        get() = order.deliveryChecksPerformed.map { it.name }.toTypedArray()

    /** §10's checks nobody performed on them (§17). */
    public val deliveryChecksNotPerformedHere: Array<String>
        get() = order.deliveryChecksNotPerformedHere.map { it.name }.toTypedArray()

    /** Names the state only. §12 item 2 keeps the order id and every amount out of a log. */
    override fun toString(): String = "JsOrder(state=$state)"
}

/** What [openOrder] answered: the order in `proposed`, or the refusal. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderResult internal constructor(
    private val opened: Order?,
    private val refusal: JsRefusal?,
) {

    /** `"order"` or `"refused"`. */
    public val kind: String get() = if (opened != null) "order" else "refused"

    /** True exactly when [kind] is `"order"`. */
    public val ok: Boolean get() = opened != null

    /** The order, when [ok]. */
    public val order: JsOrder? get() = opened?.let { JsOrder(it) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `SeamRejection`, `MoneyRejection`, … */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The field the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    override fun toString(): String = if (ok) "JsOrderResult(order)" else "JsOrderResult(refused: $reason)"
}

/**
 * What [stepOrder] answered: §11.2's outcome, or a crossing that never reached the machine.
 *
 * ### Three cases and not two, which is why [order] is nullable
 *
 * `OrderOutcome.order` is non-null for both of its cases — the new order when it advanced and the
 * **unchanged** one when it refused — and that is deliberate on the Kotlin side: a caller holding a
 * refusal still holds its order. A crossing refusal is the third case, and it carries no order
 * because no call was made: the `JsOrderEvent` could not be turned into an `OrderEvent` at all.
 * [reasonVocabulary] is how a page tells the two refusals apart — `TransitionRejection` is §11.2's
 * answer about this order, `JsCrossing` is this boundary's answer about the argument.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderOutcome internal constructor(
    private val outcome: OrderOutcome?,
    private val refusal: JsRefusal?,
) {

    /** `"advanced"` or `"refused"`. */
    public val kind: String get() = if (outcome is OrderOutcome.Advanced) "advanced" else "refused"

    /** True exactly when the event was legal from this state and the order moved (§11.2). */
    public val ok: Boolean get() = outcome is OrderOutcome.Advanced

    /**
     * The order after the event: the new one when [ok], the **unchanged** one when §11.2 refused,
     * and `null` when the crossing refused before the machine was called.
     */
    public val order: JsOrder? get() = outcome?.let { JsOrder(it.order) }

    /**
     * Which of §11.2's refusals this is, as `TransitionRejection.name` — or the crossing's reason.
     *
     * Branch on this and never on [detail], which is prose authored for a human.
     */
    public val reason: String?
        get() = (outcome as? OrderOutcome.Refused)?.reason?.name ?: refusal?.reason

    /** `TransitionRejection` for §11.2's own refusals, `JsCrossing` for the argument's. */
    public val reasonVocabulary: String?
        get() = if (outcome is OrderOutcome.Refused) "TransitionRejection" else refusal?.vocabulary

    /** The field a crossing refusal is about. Always `null` for a §11.2 refusal, which names a rule. */
    public val tag: String? get() = if (outcome != null) null else refusal?.tag

    /** Why, in words, authored in this library. Never names a byte, an amount or a deadline (§12). */
    public val detail: String?
        get() = (outcome as? OrderOutcome.Refused)?.detail ?: refusal?.detail

    /**
     * §9.2's checks that were owed and done by nobody, for
     * `PAYMENT_CHECKS_NOT_PERFORMED` alone (decision B). Empty for every other answer.
     *
     * Published as its own field rather than folded into [detail] because what a page does with it
     * is the point: the set names which of §9.2's checks nobody performed, so a page can say "the
     * invoice amount was never checked" rather than "the order did not move". Computed from what
     * *applies* minus what was performed, never from a receipt's own record of its own omissions —
     * which is §9.1 one layer up, and only that direction fails closed.
     */
    public val missingChecks: Array<String>
        get() = (outcome as? OrderOutcome.Refused.ChecksNotPerformed)
            ?.missing?.map { it.name }?.toTypedArray() ?: emptyArray()

    override fun toString(): String =
        if (ok) "JsOrderOutcome(advanced)" else "JsOrderOutcome(refused: $reason)"
}

/**
 * One §11.2 event, as a plain object with a [type] discriminator and the union of the fields the
 * fourteen `OrderEvent` variants take.
 *
 * ### Why a union on the way *in*
 *
 * Decision **P** says a sealed result becomes a plain object with a `kind` string on the way out.
 * This is the same rule on the way in, and it has to be: `OrderEvent` is sealed over fourteen
 * classes, no sealed type crosses, and a page cannot implement a Kotlin interface. Fourteen entry
 * points would be fourteen more exports with fourteen more equality proofs owed; one object with a
 * `type` is the shape a page already writes.
 *
 * **Every field is translated by this library's own constructor** — `OrderId.ofHex`,
 * `OrderTerms.of`, `OrderStatusCodec.read`, `DeliverableHash.ofHex`, `ServedBytesVerified.
 * verifyServedBytes` — so each refusal is the one a JVM caller gets for the same input. A `type`
 * naming no variant, or a field that variant has no default for crossing as `null`, is
 * [JsCrossing.UNCONSTRUCTIBLE_VALUE] and reaches the machine not at all.
 *
 * ### The fourteen tokens
 *
 * **Nine §7 rumors**, each carrying whose key sealed it: `proposal`, `status_update`,
 * `acceptance_received`, `delivery_committed`, `payment_requests_received`, `deliverable_released`,
 * `private_bid`, `chat_message`, `shipping_update`. Then **five §9.1 locals** — the only events that
 * may move an order into `paid`, `settled` or a pre-`paid` `disputed`, because nothing in them
 * arrives from a counterparty: `receipts_verified`, `delivery_verified`, `delivery_refused`,
 * `clock_checked`, `locally_disputed`. Nine and five, which is `OrderEvent`'s own fourteen.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsOrderEvent public constructor(

    /** Which of the fourteen. See this class's note for the tokens. */
    public val type: String,

    /**
     * The `created_at` the rumor carried, or `null`. **Read by nothing** (§4.6).
     *
     * Ignored entirely for the four local events, which carry no `created_at` because nothing in
     * them came off a relay.
     */
    public val createdAtSeconds: String? = null,

    /**
     * §7.4's `order` id, for `proposal` and `deliverable_released`.
     *
     * On a `deliverable_released` it is **optional**, and the asymmetry is §10.3's: a release
     * decoded from a message carries the `order` tag it read and §11.2 compares it, while one
     * assembled from typed values carries no id and makes no claim. Supplying it buys §10.3's
     * binding check (`RELEASE_FOR_ANOTHER_ORDER`); omitting it buys nothing and claims nothing.
     * Neither shape declares the binding as a *check performed* — see this class's note on why no
     * field here does.
     */
    public val orderIdHex: String? = null,

    /**
     * §8.3's and §7.5's terms, for `proposal`, and the terms a `status_update` **asserts**.
     *
     * On a `status_update` these are a counterparty's claim and §11.2 reads nothing from them: the
     * only door to `accepted` is `acceptance_received`, which carries §7.6's checked answer.
     */
    public val terms: JsOrderTerms? = null,

    /**
     * §11.1's `status` token a `status_update` carries — `active` is not one; §11.1's are
     * `proposed`, `accepted`, `awaiting_payment`, `paid`, `released`, `settled`, `cancelled`,
     * `expired`, `disputed`.
     *
     * Read through `OrderStatusCodec.read`, which is §11.1's codec and no other. A token §11.1 has
     * no rule for becomes `OrderState.UNKNOWN` and the machine's own
     * `STATUS_UPDATE_DECIDES_NOTHING` is what reports it — so an unrecognised status is the
     * library's answer and not this boundary's.
     */
    public val statusToken: String? = null,

    /**
     * Whose key sealed the rumor (§7.2), as resolved by the layer above — `BUYER`, `PROVIDER` or
     * `FEE_RECIPIENT`.
     *
     * Required by every rumor variant but `proposal`, which carries none: §7.4's table fixes a
     * `type=1`'s sender as the buyer and there is nothing for a sender field to discriminate.
     */
    public val fromParty: String? = null,

    /** §10.1's four values, for `delivery_committed` and for `delivery_verified`'s commitment. */
    public val commitment: JsCommitment? = null,

    /** §10.3's four values, for `deliverable_released`. */
    public val release: JsRelease? = null,

    /** §7.5's proposal rumor, for `acceptance_received`. See [providerPubkey]. */
    public val proposalRumor: JsRumor? = null,

    /** §7.6's `status=accepted` rumor, for `acceptance_received`. */
    public val acceptanceRumor: JsRumor? = null,

    /**
     * The provider key §7.6's acceptance is **checked against**, for `acceptance_received`.
     *
     * The three arguments above are `OrderProposal.accepts`'s, and that call is what performs
     * §7.6's byte-identical comparison of `item`, `amount_msat`, `fee` and `deliver_by` and §8.6's
     * sender rule. An answer that is §7.6's counter-proposal, or not an acceptance at all, has no
     * `Acceptance.Accepted` to be and is [JsCrossing.UNCONSTRUCTIBLE_VALUE] — this boundary does
     * not promote a counter-proposal to an acceptance.
     */
    public val providerPubkey: String? = null,

    /** The `type=2` records the page's store holds, for `payment_requests_received` (§8.6). */
    public val requests: Array<JsAcceptedRequest> = emptyArray(),

    /**
     * §9.2's verified receipts, for `receipts_verified` — from [verifySettlement] or
     * [verifyFeeReceipt].
     *
     * Each must be `evidenced`: `OrderEvent.ReceiptsVerified` takes §9.2 **evidence**, and §9.4's
     * `Unverified` is the statement that a receipt evidences nothing. One that crossed unverified
     * is [JsCrossing.UNCONSTRUCTIBLE_VALUE] rather than dropped — dropping would turn "this rail
     * has no verification rule" into "this receipt was not offered", which is §17's rule broken in
     * the direction nobody checks.
     */
    public val settlements: Array<JsSettlement> = emptyArray(),

    /**
     * §10.4's served bytes, as lowercase hex, for `delivery_verified`.
     *
     * The ciphertext the provider served. This library hashes it itself and compares the digest to
     * the commitment's `x` — §10.4 step 1 — so what crosses is the bytes and never a verdict about
     * them (§9.1, STOP RULE 12).
     */
    public val servedBytesHex: String? = null,

    /** §10.4's decrypted bytes, as lowercase hex. Hashed here and compared to the commitment's `ox`. */
    public val plaintextBytesHex: String? = null,

    /**
     * §10.4's bound on what this page will hash, as a decimal string, or `null` for the default.
     *
     * The one bound this package does let a page choose, and the asymmetry with [seal] and [open] —
     * which take no `WireLimits` precisely so a page cannot widen what the library parses — is
     * deliberate rather than an oversight. This bound buys a page nothing: it is `verifyServedBytes`'
     * own parameter, with the same default a JVM caller gets, and the digest comparison against the
     * commitment's `x` is unconditional whatever it is set to. A generous one costs the page its own
     * CPU and changes no answer; §4.3's event bounds change what is *accepted*, which is why those
     * stay this library's.
     */
    public val maxServedBytes: String? = null,

    /**
     * Which of §10.4's three steps failed, for `delivery_refused` —
     * `SERVED_BYTES_HASH_MISMATCH`, `BLOB_DID_NOT_DECRYPT` or `PLAINTEXT_BYTES_HASH_MISMATCH`.
     */
    public val deliveryFailure: String? = null,
) {

    /** Names the type only: every other field is a §12 item 2 or item 11 value. */
    override fun toString(): String = "JsOrderEvent(type=$type)"
}

// =================================================================================================
// Translation. Every nested value is built by this library's own constructor, so each refusal is
// the one a JVM caller gets for the same input.
// =================================================================================================

/** This configuration as the `OrderMachine` both entry points call, through its own `init`. */
internal fun JsOrderMachine.asOrderMachine(): OrderMachine = OrderMachine(
    clock = environment.asClock(),
    releaseTimeoutSeconds = crossedSecondsOrNull("release_timeout", releaseTimeoutSeconds)
        ?: OrderMachine.DEFAULT_RELEASE_TIMEOUT_SECONDS,
    verificationTimeoutSeconds = crossedSecondsOrNull("verification_timeout", verificationTimeoutSeconds)
        ?: OrderMachine.DEFAULT_VERIFICATION_TIMEOUT_SECONDS,
)

/**
 * This object as the one `OrderEvent` its [JsOrderEvent.type] names.
 *
 * The `when` is over the token and every arm is one constructor call. Nothing is defaulted on a
 * caller's behalf that the Kotlin variant does not default itself, which is what [crossedField]
 * enforces: a variant whose parameter has no default gets a required crossing, so a page cannot
 * reach the machine by omitting the thing the event is about.
 */
internal fun JsOrderEvent.asOrderEvent(): OrderEvent {
    val createdAt = crossedSecondsOrNull("created_at", createdAtSeconds)
    return when (type) {
        "proposal" -> OrderEvent.Proposal(
            order = OrderId.ofHex(crossedField("proposal.orderIdHex", orderIdHex)),
            terms = crossedField("proposal.terms", terms).asOrderTerms(),
            createdAt = createdAt,
        )

        "status_update" -> OrderEvent.StatusUpdate(
            // §11.1's codec and no other. An unrecognised token is `UNKNOWN` and the machine's
            // own refusal reports it; this boundary restates no vocabulary.
            status = OrderStatusCodec.read(crossedField("status_update.statusToken", statusToken)),
            from = crossedParty("status_update.fromParty"),
            createdAt = createdAt,
            assertedTerms = terms?.asOrderTerms(),
        )

        "acceptance_received" -> OrderEvent.AcceptanceReceived(
            acceptance = crossedAcceptance(),
            createdAt = createdAt,
        )

        // The public three-argument constructor, which declares no `DeliveryCheck` at all. §17
        // forbids reporting an unverified thing as verified and a page's word for a check is
        // exactly what STOP RULE 12 says is not evidence, so this crossing has no field a page
        // could declare one through — `DECLARABLE_CHECKS` is for the decoder that read the tags.
        "delivery_committed" -> OrderEvent.DeliveryCommitted(
            commitment = crossedField("delivery_committed.commitment", commitment)
                .asDeliverableCommitment(),
            from = crossedParty("delivery_committed.fromParty"),
            createdAt = createdAt,
        )

        "payment_requests_received" -> OrderEvent.PaymentRequestsReceived(
            requests = requests.map { it.accepted }.toSet(),
            createdAt = createdAt,
        )

        // The internal five-argument constructor, for the one field the public one cannot carry:
        // §10.3's `order` tag. `checksPerformed` is empty, so this claims nothing — see
        // `JsOrderEvent.orderIdHex` on why the id and the check are separate facts.
        "deliverable_released" -> OrderEvent.DeliverableReleased(
            release = crossedField("deliverable_released.release", release).asDeliverableRelease(),
            from = crossedParty("deliverable_released.fromParty"),
            createdAt = createdAt,
            order = orderIdHex?.let { OrderId.ofHex(it) },
            checksPerformed = emptySet(),
        )

        "private_bid" -> OrderEvent.PrivateBid(crossedParty("private_bid.fromParty"), createdAt)
        "chat_message" -> OrderEvent.ChatMessage(crossedParty("chat_message.fromParty"), createdAt)
        "shipping_update" -> OrderEvent.ShippingUpdate(
            crossedParty("shipping_update.fromParty"),
            createdAt,
        )

        "receipts_verified" -> OrderEvent.ReceiptsVerified(
            receipts = settlements.map { crossedPaymentEvidence(it) }.toSet(),
        )

        "delivery_verified" -> OrderEvent.DeliveryVerified(evidence = crossedDeliveryEvidence())

        "delivery_refused" -> OrderEvent.DeliveryRefused(
            failure = crossedConstant(
                "delivery failure",
                crossedField("delivery_refused.deliveryFailure", deliveryFailure),
                DeliveryFailure.entries,
            ) { it.name },
        )

        "clock_checked" -> OrderEvent.ClockChecked
        "locally_disputed" -> OrderEvent.LocallyDisputed

        else -> throw JsCrossing(
            JsCrossing.UNCONSTRUCTIBLE_VALUE,
            "order event type",
            "§11.2's trigger column names fourteen events and `type` must be one of them; the " +
                "token that crossed is none, so there is no event to offer the machine and this " +
                "boundary does not choose one on the caller's behalf",
        )
    }
}

/** §7.2's sender, as the `Party` §11.2's sender rules compare against. */
private fun JsOrderEvent.crossedParty(field: String): Party =
    crossedConstant(field, crossedField(field, fromParty), Party.entries) { it.name }

/**
 * §7.6's checked acceptance, through `OrderProposal.accepts` and nothing else.
 *
 * Three library calls, in the order a JVM caller makes them: decode the `type=1`, decode the
 * `type=3`, then compare them against the provider key. §7.6's byte-identical comparison of `item`,
 * `amount_msat`, `fee` and `deliver_by` happens inside the third, which is the whole reason
 * `OrderEvent.AcceptanceReceived` takes an `Acceptance.Accepted` and not three loose values:
 * decision J records that a caller assembling one by hand skipped that comparison and §8.6's
 * sender rule at once.
 */
private fun JsOrderEvent.crossedAcceptance(): Acceptance.Accepted {
    val proposal = OrderProposal.decode(
        crossedField("acceptance_received.proposalRumor", proposalRumor)
            .asBoundRumor("kind:16 type=1 order proposal"),
    )
    val update = OrderStatusMessage.decode(
        crossedField("acceptance_received.acceptanceRumor", acceptanceRumor)
            .asBoundRumor("kind:16 type=3 status update"),
    )
    val answer = proposal.accepts(
        update,
        crossedField("acceptance_received.providerPubkey", providerPubkey),
    )
    return answer as? Acceptance.Accepted ?: throw JsCrossing(
        JsCrossing.UNCONSTRUCTIBLE_VALUE,
        "acceptance_received.acceptanceRumor",
        "§7.6 answers one of three things about a `status=accepted` update — an acceptance, a " +
            "counter-proposal whose terms diverge, or a message that is not an acceptance at all " +
            "— and this one is not the first; §11.2's only door to `accepted` takes the checked " +
            "acceptance, and promoting either of the other two to one here would advance an order " +
            "on terms §7.6 found divergent",
    )
}

/** §9.2's evidence from a crossed settlement, which must be `Evidenced` and not §9.4's `Unverified`. */
private fun crossedPaymentEvidence(crossed: JsSettlement): Settlement.Evidenced =
    crossed.settlement as? Settlement.Evidenced ?: throw JsCrossing(
        JsCrossing.UNCONSTRUCTIBLE_VALUE,
        "receipts_verified.settlements",
        "§11.2's `awaiting_payment → paid` row takes §9.2 evidence, and §9.4 says a receipt on a " +
            "rail NENYA-1 v1 defines no verification rule for MUST be treated as unverified — so " +
            "an unverified settlement is not a value that row can be offered; it is reported here " +
            "rather than dropped, because dropping it would turn \"this rail has no rule\" into " +
            "\"this receipt was never offered\"",
    )

/**
 * §10.4's evidence, computed here by this library's own two digest checks over the page's bytes.
 *
 * Two calls, in §10.4's order: `verifyServedBytes` hashes the served ciphertext and compares it to
 * the commitment's `x` (step 1), and `verifyPlaintextBytes` hashes the decrypted bytes and compares
 * them to `ox` (step 3). Either mismatch is a `DeliveryRejection` returned as a value. Nothing a
 * page asserts about either digest is read — §10.4's steps are comparisons this library makes, and
 * a boolean from a page would be the assertion STOP RULE 12 forbids.
 */
private fun JsOrderEvent.crossedDeliveryEvidence(): DeliveryEvidence {
    val served = ServedBytesVerified.verifyServedBytes(
        commitment = crossedField("delivery_verified.commitment", commitment)
            .asDeliverableCommitment(),
        servedBytes = crossedBytes("delivery_verified.servedBytesHex", servedBytesHex),
        maxServedBytes = crossedNumberOrNull("delivery_verified.maxServedBytes", maxServedBytes)
            ?: ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES,
    )
    return DeliveryEvidence.verifyPlaintextBytes(
        servedBytes = served,
        plaintextBytes = crossedBytes("delivery_verified.plaintextBytesHex", plaintextBytesHex),
    )
}

/**
 * A field the Kotlin variant has no default for, or the crossing's refusal.
 *
 * The alternative is to default it, and `JsRelease`'s own note records what that costs: a page
 * omitting a size would have put `["size", "0"]` on the wire as a positive claim about a file it
 * never measured. The same objection applies to every field below — a `status_update` with no
 * token, a `deliverable_released` with no release — so there is one helper and no defaults.
 */
private fun <T : Any> crossedField(field: String, value: T?): T = value ?: throw JsCrossing(
    JsCrossing.UNCONSTRUCTIBLE_VALUE,
    field,
    "the event this `type` names has no default for `$field`, so there is nothing to construct " +
        "it from; this boundary does not choose a value on the caller's behalf",
)

/**
 * A required hex field as the bytes it spells, through [JsHex]'s strict reader.
 *
 * [JsCrossing.UNCONSTRUCTIBLE_VALUE] and not a §10.4 refusal: a string that is not hex has no
 * `ByteArray` to be, so there is nothing to hash and nothing for §10.4's comparison to be made
 * against. §10.4's own two refusals — the digest that is not `x`, the digest that is not `ox` —
 * come back as `DeliveryRejection`s from the calls above, which is where they belong.
 */
private fun crossedBytes(field: String, text: String?): ByteArray =
    JsHex.toBytesOrNull(crossedField(field, text)) ?: throw JsCrossing(
        JsCrossing.UNCONSTRUCTIBLE_VALUE,
        field,
        "this boundary carries a byte string as §4.3's own lowercase hex, an even number of " +
            "characters from `0`-`9` and `a`-`f`; the value crossed for `$field` is not that form",
    )
