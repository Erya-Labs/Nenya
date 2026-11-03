package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.FeeRecipientCopy
import dev.eryalabs.nenya.channel.OrderBinding
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.DeliveryRejection
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderOutcome
import dev.eryalabs.nenya.order.OrderState
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
 * §11.2's first row from the `type=1` itself: the order opened on the terms the wire carried. T72.
 *
 * [openOrder] takes the order id and the terms as values a page types, so a page that has the
 * proposal rumor in hand copies four terms and an id across and can copy one wrong. This takes the
 * rumor. The facade performs **no check of its own**: one call to `OrderProposal.decode` — §7.5's
 * `amount`/`amount_msat` agreement, its deadline rules and §8.1's read rule all run inside it — and
 * one to `OrderMachine.open` over `OrderProposal.asOrderEvent()`, so the order is bound to the
 * `["order", ...]` tag that was on the wire.
 *
 * **It establishes no provider and performs no §7.6 comparison**, exactly as [decodeProposal] does
 * not: the order opens in `proposed`, and only [stepOrder]'s `acceptance_received` moves it on.
 *
 * @param machine as [openOrder]'s: `open` reads none of it, and a machine configured with a
 *   non-positive timeout is refused here rather than at the next call.
 * @param proposalRumor the `kind:16` `type=1` as [open] handed it back. A rumor of another bound
 *   kind reaches the decoder and gets its own `NOT_A_PROPOSAL`, and no order.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun openOrderFromProposal(
    machine: JsOrderMachine,
    proposalRumor: JsRumor,
): JsOrderResult = jsGuarded({ JsOrderResult(null, it) }) {
    val opener = machine.asOrderMachine()
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    JsOrderResult(opener.open(proposal.asOrderEvent()), null)
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
): JsOrderOutcome = jsGuarded({
    // T73: only a `delivery_verified` refusal is a §10.4 step's failure. The digest checks run in
    // that event's translation and nowhere else, so this guard restates the fact rather than
    // narrowing it — and keeps any later door from inheriting a step it never made.
    JsOrderOutcome(null, it, if (event.type == "delivery_verified") it.deliveryFailure() else null)
}) {
    JsOrderOutcome(machine.asOrderMachine().on(order.order, event.asOrderEvent()), null)
}

/**
 * §11.2's status rows stepped from the sealed `type=3` itself, its sender and order read off the
 * messages. T76.
 *
 * [stepOrder]'s `status_update` takes a `statusToken` and a `fromParty` a page read itself, and a
 * page passing `BUYER` for every update lets any key holding the order id cancel. This takes the two
 * rumors and the provider key, and the facade performs **no check of its own**:
 *
 * 1. `OrderProposal.decode` and `OrderStatusMessage.decode`, whose refusals come back as values;
 * 2. `OrderBinding.check` (T82, the call a Kotlin client makes): the proposal's and the update's
 *    `order` ids against [order]'s — either other is
 *    `ChannelRejection.DIFFERENT_ORDER`, and nothing is offered to the order; then the proposal against
 *    the one [order] was opened from — another author, other terms or another fee recipient, or an order
 *    [openOrder] opened, is `ChannelRejection.NOT_THIS_ORDERS_PROPOSAL` (T80, T90), and nothing is
 *    offered; then, once [order] is accepted, [providerPubkey] against the key its acceptance was checked against — another is
 *    `ChannelRejection.PROVIDER_NOT_THIS_ORDERS` (T79), and nothing is offered;
 * 3. a `status=accepted` goes exactly where [stepOrder]'s `acceptance_received` sends it — §7.6's
 *    checked answer through `OrderProposal.accepts` (decision J), so a counter-proposal or an update
 *    from the wrong key is refused as it is there;
 * 4. any other status is `OrderEvent.StatusUpdate` from the party `OrderBinding.sender` — that is,
 *    `OrderProposal.partyOf` — maps the
 *    update's attributed sender to; `null` — a stranger, or a key holding two roles — is
 *    `ChannelRejection.SENDER_NOT_A_PARTY`, and nothing is offered;
 * 5. then `OrderMachine.on`, whose own sender rules decide what that party may do.
 *
 * @param machine as [stepOrder]'s.
 * @param order the order as it stands, from [openOrderFromProposal] or from a previous step — an
 *   [openOrder] order records no buyer and is refused (T80).
 * @param proposalRumor the `kind:16` `type=1` this order was opened on with [openOrderFromProposal], as
 *   [open] handed it back.
 * @param statusRumor the `kind:16` `type=3` received, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of both messages** — see
 *   [JsOrderEvent.providerPubkey].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun stepOrderWithStatusUpdate(
    machine: JsOrderMachine,
    order: JsOrder,
    proposalRumor: JsRumor,
    statusRumor: JsRumor,
    providerPubkey: String,
): JsOrderOutcome = jsGuarded({ JsOrderOutcome(null, it) }) {
    val stepper = machine.asOrderMachine()
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    val update = OrderStatusMessage.decode(statusRumor.asBoundRumor("kind:16 type=3 status update"))
    OrderBinding.check(order.order, proposal, providerPubkey, update.order)
    val event = if (update.status == OrderState.ACCEPTED) {
        OrderEvent.AcceptanceReceived(
            acceptance = crossedAcceptance(
                proposalRumor = proposalRumor,
                acceptanceRumor = statusRumor,
                providerPubkey = providerPubkey,
                field = "stepOrderWithStatusUpdate.statusRumor",
            ),
            createdAt = update.createdAt,
        )
    } else {
        val from = OrderBinding.sender(proposal, update.sender, providerPubkey, ChannelVocabulary.STATUS)
        OrderEvent.StatusUpdate(update.status, from, createdAt = update.createdAt)
    }
    JsOrderOutcome(stepper.on(order.order, event), null)
}

/**
 * §11.2's `accepted → committed` stepped from the sealed `type=5` itself, so the order records the
 * §10.1 check its decoder makes. T77.
 *
 * [stepOrder]'s `delivery_committed` takes four retyped values and a `fromParty` a page read itself,
 * and records no check: nobody read the tags. This takes the two rumors and the provider key, and the
 * facade performs **no check of its own**:
 *
 * 1. `OrderProposal.decode` and `DeliveryCommitmentMessage.decode`, whose refusals come back as
 *    values — among them §10.1's `COMMITMENT_CARRIES_KEY`;
 * 2. `OrderBinding.check` (T82, the call a Kotlin client makes): the proposal's and the commitment's
 *    `order` ids against [order]'s — either other is
 *    `ChannelRejection.DIFFERENT_ORDER`, and nothing is offered to the order; then the proposal against
 *    the one [order] was opened from — another author, other terms or another fee recipient, or an order
 *    [openOrder] opened, is `ChannelRejection.NOT_THIS_ORDERS_PROPOSAL` (T80, T90), and nothing is
 *    offered; then [providerPubkey] against the key the accepted order's acceptance was checked against — another is
 *    `ChannelRejection.PROVIDER_NOT_THIS_ORDERS` (T79), and nothing is offered;
 * 3. the party `OrderBinding.sender` — that is, `OrderProposal.partyOf` — maps the commitment's
 *    attributed sender to; `null` is
 *    `ChannelRejection.SENDER_NOT_A_PARTY`, and nothing is offered;
 * 4. `DeliveryCommitmentMessage.asOrderEvent` from that party, carrying the checks its decode made;
 * 5. then `OrderMachine.on`, whose own sender rules decide what that party may do.
 *
 * @param machine as [stepOrder]'s.
 * @param order the order as it stands, from [openOrderFromProposal] or from a previous step — an
 *   [openOrder] order records no buyer and is refused (T80).
 * @param proposalRumor the `kind:16` `type=1` this order was opened on with [openOrderFromProposal], as
 *   [open] handed it back.
 * @param commitmentRumor the `kind:16` `type=5` received, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of both messages** — see
 *   [JsOrderEvent.providerPubkey].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun stepOrderWithCommitment(
    machine: JsOrderMachine,
    order: JsOrder,
    proposalRumor: JsRumor,
    commitmentRumor: JsRumor,
    providerPubkey: String,
): JsOrderOutcome = jsGuarded({ JsOrderOutcome(null, it) }) {
    val stepper = machine.asOrderMachine()
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    val commitment = DeliveryCommitmentMessage.decode(
        commitmentRumor.asBoundRumor("kind:16 type=5 delivery commitment"),
    )
    OrderBinding.check(order.order, proposal, providerPubkey, commitment.order)
    val from = OrderBinding.sender(proposal, commitment.sender, providerPubkey, tag = null)
    JsOrderOutcome(stepper.on(order.order, commitment.asOrderEvent(from)), null)
}

/**
 * §11.2's `paid → released` stepped from the sealed `kind:15` itself, so the order records §10.3's
 * binding, checked by the call that makes it. T77.
 *
 * [stepOrder]'s `deliverable_released` takes four retyped values and a `fromParty` a page read
 * itself, and records no check. This takes the two rumors and the provider key, and the facade
 * performs **no check of its own**:
 *
 * 1. `OrderProposal.decode` and `DeliverableReleaseMessage.decode`, whose refusals come back as values;
 * 2. `OrderBinding.check` (T82, the call a Kotlin client makes), passed no message id: the proposal's
 *    `order` id against [order]'s — another is `ChannelRejection.DIFFERENT_ORDER`, and
 *    nothing is offered. The release's own id is **not** compared here: that is §10.3's binding, and
 *    `asOrderEvent` makes it below. Then the proposal against the one [order] was opened from — another
 *    author, other terms or another fee recipient, or an order [openOrder] opened, is
 *    `ChannelRejection.NOT_THIS_ORDERS_PROPOSAL` (T80, T90), and nothing is offered. Then [providerPubkey] against the key the accepted order's
 *    acceptance was checked against — another is `ChannelRejection.PROVIDER_NOT_THIS_ORDERS` (T79),
 *    and nothing is offered;
 * 3. the party `OrderBinding.sender` — that is, `OrderProposal.partyOf` — maps the release's
 *    attributed sender to; `null` is
 *    `ChannelRejection.SENDER_NOT_A_PARTY`, and nothing is offered;
 * 4. `DeliverableReleaseMessage.asOrderEvent` against [order] from that party — another order's
 *    release is its `RELEASE_FOR_ANOTHER_ORDER`, an order not in `paid` its `RELEASE_ORDER_NOT_PAID`;
 * 5. then `OrderMachine.on`, whose own sender rules decide what that party may do.
 *
 * §10.3's identity comparison against the commitment is not made here, as `asOrderEvent` does not
 * make it; the order does not record it as performed.
 *
 * @param machine as [stepOrder]'s.
 * @param order the order as it stands, from [openOrderFromProposal] or from a previous step — an
 *   [openOrder] order records no buyer and is refused (T80).
 * @param proposalRumor the `kind:16` `type=1` this order was opened on with [openOrderFromProposal], as
 *   [open] handed it back.
 * @param releaseRumor the `kind:15` received, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of both messages** — see
 *   [JsOrderEvent.providerPubkey].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun stepOrderWithRelease(
    machine: JsOrderMachine,
    order: JsOrder,
    proposalRumor: JsRumor,
    releaseRumor: JsRumor,
    providerPubkey: String,
): JsOrderOutcome = jsGuarded({ JsOrderOutcome(null, it) }) {
    val stepper = machine.asOrderMachine()
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    val release = DeliverableReleaseMessage.decode(releaseRumor.asBoundRumor("kind:15 deliverable release"))
    OrderBinding.check(order.order, proposal, providerPubkey)
    val from = OrderBinding.sender(proposal, release.sender, providerPubkey, tag = null)
    JsOrderOutcome(stepper.on(order.order, release.asOrderEvent(order.order, from)), null)
}

/**
 * §10.4 **step 1 alone**: the served bytes hashed here and compared to the commitment's `x`.
 *
 * ### Why this is its own entry point. T44 gap (3)
 *
 * §10.4 fixes an order — hash the served bytes against `x`, *then* decrypt, then hash the plaintext
 * against `ox` — and says a buyer who checks `ox` first has decrypted a blob it has no reason to
 * trust. On the JVM the ordering is a type: `DeliveryEvidence.verifyPlaintextBytes` takes a
 * `ServedBytesVerified` and nothing else produces one. [stepOrder]'s `delivery_verified` makes both
 * calls in that order, which is correct for a page that already has both byte strings — and is
 * exactly the wrong shape for a page before it decrypts, because it would have to decrypt first to
 * have a second argument to pass. That is the "MUST NOT decrypt it" rule inverted by the crossing's
 * own convenience, and this entry point is what removes the inversion: a page calls this, and only
 * then decrypts.
 *
 * The facade performs **no check of its own**. One call to `ServedBytesVerified.verifyServedBytes`,
 * whose three refusals — the declared size above the bound, a blob whose length disagrees with
 * `size`, and a digest that is not `x` — are returned as values under `DeliveryRejection`.
 *
 * ### Calling this does not change what [stepOrder] does
 *
 * `delivery_verified` still hashes the served bytes itself, because §11.3 invariant 4 says `settled`
 * is reachable only after the buyer's **own** computation and a `Boolean` from a page saying "step 1
 * passed" is the assertion STOP RULE 12 forbids. So this answer is information for the page, never
 * an input to the order: the bytes are hashed twice, and the second hash is the one the order moves
 * on.
 *
 * @param commitment §10.1's four committed values — from [decodeCommitment], or assembled.
 * @param servedBytesHex the ciphertext the provider served, as lowercase hex.
 * @param maxServedBytes §10.4's bound on what this page will hash, as a decimal string, or `null`
 *   for this library's default. See [JsOrderEvent.maxServedBytes] on why this one bound crosses.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun verifyServedBytes(
    commitment: JsCommitment,
    servedBytesHex: String,
    maxServedBytes: String? = null,
): JsServedBytesResult = jsGuarded({ JsServedBytesResult(null, it) }) {
    JsServedBytesResult(
        ServedBytesVerified.verifyServedBytes(
            commitment = commitment.asDeliverableCommitment(),
            servedBytes = crossedBytes("verifyServedBytes.servedBytesHex", servedBytesHex),
            maxServedBytes = crossedNumberOrNull("verifyServedBytes.maxServedBytes", maxServedBytes)
                ?: ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES,
        ),
        null,
    )
}

/**
 * §7.6's three-way answer about a `type=3`, crossed whole. T48.
 *
 * ### Why this is its own entry point
 *
 * [stepOrder]'s `acceptance_received` computes the same answer and keeps only the first of its three
 * cases: a counter-proposal and a message that is not an acceptance both come back as
 * [JsCrossing.UNCONSTRUCTIBLE_VALUE], because neither is a value that row can be offered. That is
 * right for the machine and wrong for a page that wants to *show* what happened — "the maker changed
 * your amount" and "I could not build that" arrived as the same answer. §7.6 gives the three cases
 * three different correct responses, and a counter-proposal's is a new `type=1` with a new order id,
 * which a page cannot offer unless it is told which terms differ.
 *
 * ### It performs no check of its own and advances nothing
 *
 * One call to [acceptanceAnswer] — the same function `acceptance_received` and [acceptPaymentRequest]
 * reach — so every comparison is `OrderProposal.accepts`', and its four refusals come back as values
 * under `ChannelRejection`. No order is read or moved: nothing here returns a `JsOrder`, and an
 * `accepted` answer is information for the page and never an input to [stepOrder], which takes the
 * two rumors again and makes the comparison itself (STOP RULE 12).
 *
 * @param proposalRumor the `kind:16` `type=1`, as [open] handed it back.
 * @param statusRumor the `kind:16` `type=3` the provider sent, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of both messages** —
 *   see [JsOrderEvent.providerPubkey].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun checkAcceptance(
    proposalRumor: JsRumor,
    statusRumor: JsRumor,
    providerPubkey: String,
): JsAcceptanceCheck = jsGuarded({ JsAcceptanceCheck(null, it) }) {
    JsAcceptanceCheck(acceptanceAnswer(proposalRumor, statusRumor, providerPubkey), null)
}

/**
 * §7.2's attributed sender mapped to its §11.2 party by the library, not by the page. T75.
 *
 * [JsOrderEvent.fromParty] is the party a page says sealed a rumor, and a page labelling every update
 * `BUYER` lets any key holding the order id cancel. This answers that label from the proposal and
 * the key §7.2 attributed — the token [JsOrderEvent.fromParty] takes, or `null` for a key that is no
 * party of this proposal or is two of them at once.
 *
 * The facade performs **no check of its own**: one call to `OrderProposal.decode` and one to
 * `OrderProposal.partyOf`, whose two refusals of the provider key are [checkAcceptance]'s first two.
 * Nothing here moves an order; a party answered is still offered to [stepOrder], whose machine decides
 * what that party may do.
 *
 * @param proposalRumor the `kind:16` `type=1`, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of the proposal** — see
 *   [JsOrderEvent.providerPubkey].
 * @param senderPubkey the key [open] attributed the rumor being labelled to — `JsRumor.pubkey`.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun partyOf(
    proposalRumor: JsRumor,
    providerPubkey: String,
    senderPubkey: String,
): JsPartyResult = jsGuarded({ JsPartyResult(null, it) }) {
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    JsPartyResult(proposal.partyOf(senderPubkey, providerPubkey), null)
}

/**
 * §8.7's copy of the order's `type=1` for the fee recipient, and the key to seal it to (decision T).
 * T86.
 *
 * Revision `1.14`: when an order whose fee is not `0` enters `committed`, the buyer MUST send the fee
 * recipient that order's `type=1`, unchanged. This answers the recipient's key, read off the
 * proposal's own fee term, and the proposal as the [JsWireEvent] [seal] and [sealAsync] take, so a
 * page types neither. The facade performs **no check of its own**: one call to
 * `OrderProposal.decode`, then one to `FeeRecipientCopy.of`, which makes three, each refusal a value
 * and nothing built:
 *
 * 1. `OrderBinding.check` — the proposal must be the one [order] was opened from and [providerPubkey]
 *    the key its acceptance was checked against (`DIFFERENT_ORDER`, `NOT_THIS_ORDERS_PROPOSAL`,
 *    `PROVIDER_NOT_THIS_ORDERS`, and the two refusals of a malformed or buyer's provider key);
 * 2. [order] in `committed`, else `ORDER_NOT_COMMITTED`;
 * 3. a fee to request — no fee term, `["fee", "0"]` or a fee that rounds to `0` is
 *    `NO_FEE_TO_REQUEST`.
 *
 * It seals and signs nothing, and the copy is not evidence of anything (§8.7).
 *
 * @param order the buyer's order as it stands, from [openOrderFromProposal] and the steps after it.
 * @param proposalRumor the `kind:16` `type=1` this order was opened on — the buyer's own `toSelf`
 *   copy, as [open] handed it back.
 * @param providerPubkey the provider key the page resolved **independently of every message** — see
 *   [JsOrderEvent.providerPubkey].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun proposalForFeeRecipient(
    order: JsOrder,
    proposalRumor: JsRumor,
    providerPubkey: String,
): JsFeeRecipientCopy = jsGuarded({ JsFeeRecipientCopy(null, it) }) {
    val proposal = OrderProposal.decode(proposalRumor.asBoundRumor("kind:16 type=1 order proposal"))
    JsFeeRecipientCopy(FeeRecipientCopy.of(order.order, proposal, providerPubkey), null)
}

// =================================================================================================
// The boundary types.
// =================================================================================================

/**
 * What [checkAcceptance] answered: one of §7.6's three cases, or the refusal.
 *
 * ### There is no `ok`, on purpose
 *
 * Every other result in this package publishes `ok` as "an answer came back rather than a refusal".
 * Here a counter-proposal **is** an answer, so `ok` would be true for it — and a page writing
 * `if (check.ok)` would carry an order forward on terms §7.6 found divergent. So the discriminator is
 * [kind] alone, and [accepted] is true for exactly one of its four values.
 *
 * ### Names, never values
 *
 * [divergentTerms] carries tag **names** in §7.6's order, as `Acceptance.CounterProposal` does: §12
 * item 11 keeps an order's price, coordinate and deadlines out of anything a host might log.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsAcceptanceCheck internal constructor(
    private val answer: Acceptance?,
    private val refusal: JsRefusal?,
) {

    /** `"accepted"`, `"counter_proposal"`, `"not_an_acceptance"` or `"refused"`. */
    public val kind: String
        get() = when (answer) {
            is Acceptance.Accepted -> "accepted"
            is Acceptance.CounterProposal -> "counter_proposal"
            is Acceptance.NotAnAcceptance -> "not_an_acceptance"
            null -> "refused"
        }

    /** True exactly when [kind] is `"accepted"`: §7.6 satisfied, all four terms byte-identical. */
    public val accepted: Boolean get() = answer is Acceptance.Accepted

    /** §7.4's `order` id both messages name, when [accepted]. */
    public val orderIdHex: String? get() = (answer as? Acceptance.Accepted)?.order?.toHex()

    /** The provider key the acceptance was checked against, lowercase, when [accepted]. */
    public val provider: String? get() = (answer as? Acceptance.Accepted)?.provider

    /**
     * The accepted terms, when [accepted] — the proposal's, which §7.6 found the update repeating.
     *
     * In the shape [openOrder] takes, so a page displaying them reads the same four values the order
     * would be opened on.
     */
    public val terms: JsOrderTerms?
        get() = (answer as? Acceptance.Accepted)?.terms?.let { terms ->
            JsOrderTerms(
                priceMsat = JsDecimal.of(terms.split.price.millisatoshis),
                feeBasisPoints = when (val fee = terms.split.term) {
                    is dev.eryalabs.nenya.money.FeeTerm.Stated -> fee.basisPoints
                    dev.eryalabs.nenya.money.FeeTerm.Absent -> null
                },
                expirationSeconds = JsDecimal.ofOrNull(terms.expiration),
                deliverBySeconds = JsDecimal.ofOrNull(terms.deliverBy),
            )
        }

    /**
     * Which of §7.6's four terms differ, by tag **name**, in §7.6's order, for `"counter_proposal"`.
     * Empty for every other answer. A fresh array on every read.
     */
    public val divergentTerms: Array<String>
        get() = (answer as? Acceptance.CounterProposal)?.divergentTerms?.toTypedArray() ?: emptyArray()

    /** What the update claimed instead, as `OrderState.name`, for `"not_an_acceptance"`. A claim. */
    public val claimedStatus: String? get() = (answer as? Acceptance.NotAnAcceptance)?.status?.name

    /**
     * §11.1's wire token for [claimedStatus], or `null` — for any other answer, and for the unknown
     * sink, which is a treatment and not a token. See [JsOrder.stateToken].
     */
    public val claimedStatusToken: String?
        get() = (answer as? Acceptance.NotAnAcceptance)?.status?.token

    /** The rejection constant's `name`, when [kind] is `"refused"`. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for §7.6's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag or argument the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    /** Names the case and, for a counter-proposal, the tag names — never a value. */
    override fun toString(): String = when (answer) {
        is Acceptance.CounterProposal -> "JsAcceptanceCheck(counter_proposal: ${answer.divergentTerms})"
        null -> "JsAcceptanceCheck(refused: $reason)"
        else -> "JsAcceptanceCheck($kind)"
    }
}

/**
 * What [partyOf] answered: a party, no party, or the refusal.
 *
 * [ok] says an answer came back, and **an answer may be no party**: [party] is `null`, and [kind]
 * `"not_a_party"`, for a key that is none of the proposal's three, or two of them. A page writing
 * `if (result.ok)` and then reading [party] must handle that `null` as "not a party", never as a
 * default.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsPartyResult internal constructor(
    private val answer: Party?,
    private val refusal: JsRefusal?,
) {

    /** `"party"`, `"not_a_party"` or `"refused"`. */
    public val kind: String
        get() = when {
            refusal != null -> "refused"
            answer != null -> "party"
            else -> "not_a_party"
        }

    /** True when [partyOf] answered rather than refused — whether or not the key is a party. */
    public val ok: Boolean get() = refusal == null

    /** `BUYER`, `PROVIDER` or `FEE_RECIPIENT` — the [JsOrderEvent.fromParty] token — or `null`. */
    public val party: String? get() = answer?.name

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for the provider key's refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag or argument the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    /** Names the party or the refusal reason — never a key. */
    override fun toString(): String =
        if (ok) "JsPartyResult(${party ?: kind})" else "JsPartyResult(refused: $reason)"
}

/**
 * What [proposalForFeeRecipient] answered: the copy and its addressee, or the refusal.
 *
 * [event] goes to [seal] or [sealAsync] with [recipientPubkey] as the recipient, sealed with the
 * buyer's own signer. Neither is evidence of anything (§8.7).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsFeeRecipientCopy internal constructor(
    private val copy: FeeRecipientCopy?,
    private val refusal: JsRefusal?,
) {

    /** `"built"` or `"refused"`. */
    public val kind: String get() = if (copy != null) "built" else "refused"

    /** True exactly when [kind] is `"built"`. */
    public val ok: Boolean get() = copy != null

    /** The fee recipient named in the proposal's fee term, 64 lowercase hex characters, when [ok]. */
    public val recipientPubkey: String? get() = copy?.recipient

    /** The order's `type=1`, unchanged and unsigned, when [ok]. */
    public val event: JsWireEvent? get() = copy?.let { JsWireEvent(it.encode()) }

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `ChannelRejection` for every refusal but a crossing's. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The tag the refusal is about, where the rule names one — `fee` for `NO_FEE_TO_REQUEST`. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String? get() = refusal?.detail

    /** Names the kind or the refusal reason — never a key. */
    override fun toString(): String =
        if (ok) "JsFeeRecipientCopy(built)" else "JsFeeRecipientCopy(refused: $reason)"
}

/**
 * What [verifyServedBytes] answered: §10.4 step 1 passed, or the refusal.
 *
 * ### There is no handle here, and the absence is the design
 *
 * `ServedBytesVerified` is the Kotlin type whose *existence* is the permission to decrypt, and
 * nothing in this package could reproduce that: a page is not compelled by a type to call one
 * function before another, and a handle crossing back would read as a ticket rather than as an
 * answer. So what crosses is the two facts §17 needs — how many bytes were hashed, and the bound
 * they were hashed under — and [ok] says whether the digest was `x`.
 *
 * [maxServedBytes] crosses for the reason `ServedBytesVerified` publishes it: evidence produced
 * under a 64 MiB bound and evidence produced under `Long.MAX_VALUE` would otherwise be
 * indistinguishable, and "bounded, by a bound that bounds nothing" is the §17 over-claim.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsServedBytesResult internal constructor(
    private val verified: ServedBytesVerified?,
    private val refusal: JsRefusal?,
) {

    /** `"verified"` or `"refused"`. */
    public val kind: String get() = if (verified != null) "verified" else "refused"

    /**
     * True exactly when §10.4 step 1 passed — the SHA-256 this library computed **is** the
     * commitment's `x`.
     *
     * It says step 1 and no more. §10.4 step 2's GCM authentication is the page's and step 3's `ox`
     * comparison has not been made, so a page that treated this as "the deliverable is good" would
     * be claiming two checks nobody performed (§17).
     */
    public val ok: Boolean get() = verified != null

    /** How many bytes were hashed, as an exact decimal string. Equal to the commitment's `size`. */
    public val servedBytesLength: String? get() = JsDecimal.ofOrNull(verified?.servedBytesLength)

    /** The §4.3 bound this verification was performed under, as an exact decimal string. */
    public val maxServedBytes: String? get() = JsDecimal.ofOrNull(verified?.maxServedBytes)

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String? get() = refusal?.reason

    /** Which rejection enum [reason] came from — `DeliveryRejection` for §10.4's own refusals. */
    public val reasonVocabulary: String? get() = refusal?.vocabulary

    /** The field the refusal is about, where the rule names one. */
    public val tag: String? get() = refusal?.tag

    /** Why, in words. Never echoes the bytes or a digest (§12 item 11, STOP RULE 14). */
    public val detail: String? get() = refusal?.detail

    /**
     * The `DeliveryFailure` token a `delivery_refused` takes for this refusal, or `null`. T73.
     *
     * `SERVED_BYTES_HASH_MISMATCH` for §10.4 step 1's digest that is not `x`, and `null` for every
     * other answer — a verified blob, a size refusal, a crossing refusal — through
     * `DeliveryFailure.of` and nothing else, so a page offers the order the token this library
     * named rather than one it mapped by hand. Information, never an order move: nothing here
     * steps an order, and offering the token to [stepOrder] is the page's decision.
     */
    public val deliveryFailure: String? get() = refusal?.deliveryFailure()?.name

    override fun toString(): String =
        if (ok) "JsServedBytesResult(verified)" else "JsServedBytesResult(refused: $reason)"
}

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
    private val failure: DeliveryFailure? = null,
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

    /**
     * The `DeliveryFailure` token a `delivery_refused` takes, for a `delivery_verified` refused by
     * one of §10.4's two digest checks. `null` for every other answer. T73.
     *
     * `SERVED_BYTES_HASH_MISMATCH` when the served bytes are not `x` (step 1) and
     * `PLAINTEXT_BYTES_HASH_MISMATCH` when the plaintext is not `ox` (step 3), through
     * `DeliveryFailure.of` and nothing else. A size refusal, a crossing refusal, §11.2's own refusal
     * of the event, and every other event's refusal answer `null`. **Nothing steps the order on it:**
     * the order did not move, and offering this token back to [stepOrder] as a `delivery_refused` is
     * the page's decision.
     */
    public val deliveryFailure: String? get() = failure?.name

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
     * not promote a counter-proposal to an acceptance. A page that wants to know *which* of the
     * other two it was, and which terms diverged, asks [checkAcceptance].
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

/** §7.6's checked acceptance for an `acceptance_received`, once its three fields are present. */
private fun JsOrderEvent.crossedAcceptance(): Acceptance.Accepted = crossedAcceptance(
    proposalRumor = crossedField("acceptance_received.proposalRumor", proposalRumor),
    acceptanceRumor = crossedField("acceptance_received.acceptanceRumor", acceptanceRumor),
    providerPubkey = crossedField("acceptance_received.providerPubkey", providerPubkey),
    field = "acceptance_received.acceptanceRumor",
)

/**
 * §7.6's checked acceptance, through `OrderProposal.accepts` and nothing else.
 *
 * Three library calls, in the order a JVM caller makes them: decode the `type=1`, decode the
 * `type=3`, then compare them against the provider key. §7.6's byte-identical comparison of `item`,
 * `amount_msat`, `fee` and `deliver_by` happens inside the third, which is the whole reason
 * `OrderEvent.AcceptanceReceived` takes an `Acceptance.Accepted` and not three loose values:
 * decision J records that a caller assembling one by hand skipped that comparison and §8.6's
 * sender rule at once.
 *
 * `internal` and taking its three operands rather than a `JsOrderEvent`, because T44's
 * [acceptPaymentRequest] needs the same value for the same reason: `AcceptedPaymentRequest.accept`
 * takes an `Acceptance.Accepted` too, and decision J's objection is to *either* caller assembling
 * one by hand. One function, so the two crossings cannot come to mean different things by "the
 * acceptance for this order".
 *
 * @param field which crossed argument an answer that is not an acceptance is reported against.
 */
internal fun crossedAcceptance(
    proposalRumor: JsRumor,
    acceptanceRumor: JsRumor,
    providerPubkey: String,
    field: String,
): Acceptance.Accepted {
    val answer = acceptanceAnswer(proposalRumor, acceptanceRumor, providerPubkey)
    return answer as? Acceptance.Accepted ?: throw JsCrossing(
        JsCrossing.UNCONSTRUCTIBLE_VALUE,
        field,
        "§7.6 answers one of three things about a `status=accepted` update — an acceptance, a " +
            "counter-proposal whose terms diverge, or a message that is not an acceptance at all " +
            "— and this one is not the first; every door that takes a checked acceptance takes " +
            "the first, and promoting either of the other two to one here would carry an order " +
            "forward on terms §7.6 found divergent",
    )
}

/**
 * §7.6's whole three-way answer, through `OrderProposal.accepts` and nothing else.
 *
 * Three library calls, in the order a JVM caller makes them: decode the `type=1`, decode the
 * `type=3`, then compare them against the provider key. Shared by [crossedAcceptance], which keeps
 * only the first of the three answers, and by [checkAcceptance], which publishes all three — so the
 * door that advances an order and the door that only reports cannot come to mean different things
 * by "§7.6's answer for these two messages".
 */
internal fun acceptanceAnswer(
    proposalRumor: JsRumor,
    statusRumor: JsRumor,
    providerPubkey: String,
): Acceptance {
    val proposal = OrderProposal.decode(
        proposalRumor.asBoundRumor("kind:16 type=1 order proposal"),
    )
    val update = OrderStatusMessage.decode(
        statusRumor.asBoundRumor("kind:16 type=3 status update"),
    )
    return proposal.accepts(update, providerPubkey)
}

/**
 * §10.4's step this refusal is the failure of, through `DeliveryFailure.of` alone, or `null`.
 *
 * [jsGuarded] keeps a `DeliveryException`'s reason as its constant's `name` under the
 * `DeliveryRejection` vocabulary, so the constant is found again by that exact name; a refusal from
 * any other vocabulary is no §10.4 step's.
 */
internal fun JsRefusal.deliveryFailure(): DeliveryFailure? =
    if (vocabulary != "DeliveryRejection") {
        null
    } else {
        DeliveryRejection.entries.firstOrNull { it.name == reason }?.let { DeliveryFailure.of(it) }
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
