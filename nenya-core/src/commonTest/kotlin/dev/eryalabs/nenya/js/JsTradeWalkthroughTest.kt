package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.TransitionRejection
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T67: one whole trade, walked the way `docs/INTEGRATING.md` walks it, through the exported entry
 * points only — and the two cheats a provider or a buyer can try on the way.
 *
 * Buyer, provider and fee recipient are three [FakeCryptoSigner]s, each crossed into the [JsSigner] a
 * page supplies by [JsFlowFixtures.crossed], and every party shares one [JsEnvironment] built from the
 * same fakes with a pinned clock. Every private message is built by its sender, [seal]ed, and
 * [open]ed by its recipient; a sender that needs its own message later opens its `toSelf` copy, as
 * the guide's provider section says. The steps follow the guide's `Calls:` lines in order, and
 * between two steps nothing reaches past a crossed value into a Kotlin type: the only Kotlin values
 * here are the seams and the fixtures — `OrderFixtures.TERMS`' amounts and deadlines, its blob, and
 * the invoices `SettlementFixtures` derives (decision **D**), with preimages from `PaymentFixtures`.
 *
 * T74: nothing a library door can give is retyped. The order opens from the proposal itself
 * ([openOrderFromProposal]); each receipt is checked against the terms its checker decoded off the
 * proposal it holds (`JsOrderProposal.terms`); and every §8.4 point is a door's sighting
 * ([proposalFeeSighting], [acceptanceFeeSighting], [paymentRequestFeeSighting]), never one built from
 * a `fee` row. The only [JsOrderTerms] typed is the proposal's input.
 *
 * T78: every message the buyer receives is stepped through the door that reads it — the acceptance
 * through [stepOrderWithStatusUpdate], the commitment through [stepOrderWithCommitment], the release
 * through [stepOrderWithRelease] — so nothing here passes a `fromParty` or builds a [JsCommitment] or
 * [JsRelease] for a received message, and the settled order records both delivery checks.
 *
 * T81: three more cheats on those doors, each stepping nothing — a stranger's own `type=1` under the
 * order's id and terms with its `cancelled` (T80's `NOT_THIS_ORDERS_PROPOSAL`), a stranger's commitment
 * with the stranger named as the provider (T79's `PROVIDER_NOT_THIS_ORDERS`), and the fee recipient's
 * commitment with the right provider (§11.2's `WRONG_SENDER`). The first two, offered to `stepOrder`
 * with the `fromParty` a page would fill in, move the order, so each refusal is the door's.
 *
 * T88: the fee recipient learns of the order as §8.7 says (decision T). At step 7 the buyer seals the
 * copy [proposalForFeeRecipient] answers, and the fee recipient builds its request with
 * [buildFeeRequestFromProposal] from the one rumor in its inbox, so it is handed no terms, no order id
 * and no buyer key. A buyer that sends no copy leaves it nothing to build from, and the order stays
 * `committed`.
 *
 * T91: two cheats on those doors, each sending nothing — the buyer's second `type=1` under the order's
 * id and terms naming a stranger as the fee recipient (T90's `NOT_THIS_ORDERS_PROPOSAL` from
 * [proposalForFeeRecipient]), and the fee recipient's request with the provider as its `p` (T89's
 * `NOT_ADDRESSED_TO_BUYER` from [buildFeeRequestFromProposal]).
 *
 * T95: one more, at step 4 — the buyer proposing with itself as the fee recipient (T93's
 * `FEE_RECIPIENT_IS_BUYER` from [buildProposalAgainst]), which seals nothing.
 *
 * T98: a fee is not enforced against the buyer, and §11's table is the buyer's order (decision U). The
 * provider releases on its own receipt alone, through [verifySettlement] and [buildReleaseAfterPayment]
 * called directly, since [Trade.paid] pays the fee; the buyer's order still waits for the fee receipt,
 * and refuses that release until it is `paid`. An order the provider opens wants the fee request too.
 *
 * T99: the fee recipient cannot verify its receipt from its inbox alone (decision U), because it stored
 * no request. Its acceptance and fee-request points are the one exception to T74's door-made sightings:
 * no door gives a fee recipient those points, so its test types the copy's `fee` row in for them.
 *
 * T109: §8.1's SHOULD-warn (T108). The trade's proposal and order answer `feeRecipientIsProvider` false;
 * a proposal naming the provider answers true and still builds and is accepted, since it is a warning.
 *
 * T116: a fee recipient that holds §8.7's copy and never answers (decision V). With no fee request
 * the buyer's fee-bearing order does not pass `committed`, and the buyer leaves it by §11.2's
 * cancellation, sealed to the provider: its own order and the one the provider opened read
 * `cancelled`, where the fee recipient's own `cancelled` is §11.2's wrong sender. Before `paid` the
 * same cancellation also ends an order at `awaiting_payment`.
 *
 * The clock is [OrderFixtures.BEFORE_DEADLINES] throughout: before the proposal's `expiration`, so the
 * acceptance builds, and before `deliver_by`, so no deadline row fires. Each invoice is stamped
 * [SettlementFixtures.INVOICE_AGE_SECONDS] before that reading, so §9.2 check 5 is live at acceptance.
 */
class JsTradeWalkthroughTest {

    /** The honest trade: offer to `settled`, every step's answer read before the next is taken. */
    @Test
    @JsName("one_honest_trade_through_the_exported_entry_points_ends_settled")
    fun `one honest trade through the exported entry points ends settled`() {
        val trade = Trade()
        val paid = trade.paid(trade.providerReceipt(Trade.PROVIDER_PREIMAGE))

        // Guide step 9 — the provider's release, gated on its own evidence (T65), and stepped by the
        // buyer from the rumor itself (T78).
        val released = trade.stepped(trade.receivedRelease(paid, trade.releaseAfterPayment()))
        assertEquals("RELEASED", released.state, "§11.2's `paid → released`")

        // Guide step 10 — §10.4, step 1 on its own and then the whole check inside the order.
        val commitment = released.commitment ?: fail("a released order holds its commitment")
        val served = verifyServedBytes(commitment, encodeLowerHex(OrderFixtures.blob.served))
        assertTrue(served.ok, "§10.4 step 1: the served bytes hash to `x`: ${served.reason}")
        assertNull(served.deliveryFailure, "a verified blob names no `delivery_refused` failure")
        val settled = trade.step(
            released,
            JsOrderEvent(
                type = "delivery_verified",
                commitment = commitment,
                servedBytesHex = encodeLowerHex(OrderFixtures.blob.served),
                plaintextBytesHex = encodeLowerHex(OrderFixtures.blob.plaintext),
            ),
        )
        assertEquals("SETTLED", settled.state, "§11.2's `released → settled`")
        assertTrue(settled.terminal, "`settled` is terminal")
        assertEquals(emptyList(), settled.paymentChecksNotPerformedHere.toList(), "§17: no §9.2 check left undone")
        // T78: the commitment and the release were stepped from their rumors, so both checks are recorded.
        for (check in listOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY, DeliveryCheck.RELEASE_ORDER_BINDING)) {
            assertTrue(check.name in settled.deliveryChecksPerformed, "§17: ${check.name} performed")
            assertFalse(check.name in settled.deliveryChecksNotPerformedHere, "and not also listed as not performed")
        }
    }

    /**
     * T88's control: the same fee-bearing trade with no §8.7 copy. The fee recipient opened nothing, so
     * it has no request to send; the buyer holds only the provider's, and `payment_requests_received`
     * with it alone is refused `PAYMENT_REQUESTS_INCOMPLETE`, the order still `committed`. With the copy
     * sent, the fee recipient's inbox holds exactly the order's proposal, and the order moves.
     */
    @Test
    @JsName("a_fee_bearing_trade_whose_buyer_sends_no_copy_has_no_fee_request_and_stays_committed")
    fun `a fee-bearing trade whose buyer sends no copy has no fee request and stays committed`() {
        val silent = Trade(sendsCopy = false)
        assertEquals(emptyList(), silent.inbox(silent.feeRecipient), "the fee recipient opened nothing")
        assertNull(silent.feeRequest, "so it has no order to request a fee for")
        assertEquals(listOf("provider"), silent.buyerRecords.map { it.payee }, "the buyer holds the provider's alone")
        val outcome = silent.requestsReceived
        assertFalse(outcome.ok, "§11.2: every required payee's request, or the order does not move")
        assertEquals(TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE.name, outcome.reason)
        assertEquals("COMMITTED", outcome.order?.state, "the order is handed back unchanged")

        val told = Trade()
        val copy = told.inbox(told.feeRecipient).single()
        assertEquals(told.proposal.toSelf.idHex, copy.idHex, "§8.7: the order's own `type=1`, unchanged")
        assertEquals(told.buyer.key, copy.author, "sealed by the buyer")
        assertEquals(listOf("provider", "fee"), told.buyerRecords.map { it.payee })
        assertEquals("AWAITING_PAYMENT", told.awaitingPayment.state)
    }

    /**
     * A provider receipt naming the stored invoice — so §9.2 checks 1, 4 and 5 pass — with another
     * fixture's preimage as its proof. Check 3 refuses it on both sides, so the buyer has no evidence
     * to move the order and the provider none to release against.
     */
    @Test
    @JsName("a_receipt_carrying_another_fixture_s_preimage_is_refused_PREIMAGE_MISMATCH")
    fun `a receipt carrying another fixture's preimage is refused PREIMAGE_MISMATCH`() {
        val trade = Trade()
        val receipt = trade.providerReceipt(Trade.OTHER_PREIMAGE)

        val buyerCheck = verifySettlement(receipt.toSelf, trade.buyerStore.seam, trade.buyerTerms)
        assertFalse(buyerCheck.ok, "§9.2 check 3: the proof does not hash to the stored invoice's `p`")
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH.name, buyerCheck.reason)
        assertEquals("PaymentRejection", buyerCheck.reasonVocabulary, "check 3 keeps its own name")
        assertNull(buyerCheck.settlement, "a refusal carries no settlement")

        val providerCheck = verifySettlement(receipt.toRecipient, trade.providerStore.seam, trade.providerTerms)
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH.name, providerCheck.reason, "the provider's own check agrees")
        assertNull(providerCheck.settlement, "so the provider has nothing to hand `buildReleaseAfterPayment`")

        // The same order, offered the honest receipt instead, verifies: the refusal is the preimage's.
        val honest = trade.providerReceipt(Trade.PROVIDER_PREIMAGE)
        val honestCheck = verifySettlement(honest.toSelf, trade.buyerStore.seam, trade.buyerTerms)
        assertTrue(honestCheck.settlement?.evidenced == true, "the stored invoice's own preimage evidences")
    }

    /**
     * Negative control on guide step 8: the fee receipt checked with the acceptance's door-made point
     * left out of `earlierPoints` is refused `FEE_TERM_POINT_MISSING`, where the three points the
     * guide names, all from the library's doors, evidence it.
     */
    @Test
    @JsName("a_fee_receipt_without_the_acceptance_s_door_made_point_is_refused_FEE_TERM_POINT_MISSING")
    fun `a fee receipt without the acceptance's door-made point is refused FEE_TERM_POINT_MISSING`() {
        val trade = Trade()
        val receipt = trade.feeReceipt()

        val missing = trade.feeCheck(receipt, arrayOf(trade.proposalSighting, trade.feeRequestSighting))
        assertFalse(missing.ok, "§8.4: the acceptance is a REQUIRED point")
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING.name, missing.reason)
        assertNull(missing.settlement, "a refusal carries no settlement")

        val whole = trade.feeCheck(
            receipt,
            arrayOf(trade.proposalSighting, trade.acceptanceSighting, trade.feeRequestSighting),
        )
        assertTrue(whole.settlement?.evidenced == true, "the guide's three points evidence it: ${whole.reason}")
    }

    /**
     * A release built by the raw `buildRelease` with its `size` retyped disputes the `paid` order, where
     * the T65 release over the same commitment, offered to the same order, releases it.
     */
    @Test
    @JsName("a_raw_release_with_size_retyped_disputes_the_paid_order_the_T65_release_releases")
    fun `a raw release with size retyped disputes the paid order the T65 release releases`() {
        val trade = Trade()
        val paid = trade.paid(trade.providerReceipt(Trade.PROVIDER_PREIMAGE))
        val commitment = paid.commitment ?: fail("a paid order holds its commitment")

        val honestOutcome = trade.receivedRelease(paid, trade.releaseAfterPayment())
        assertEquals("RELEASED", honestOutcome.order?.state, "the T65 release releases: ${honestOutcome.reason}")

        val retyped = JsRelease(
            xHex = commitment.xHex,
            oxHex = commitment.oxHex,
            fileType = commitment.mimeType,
            sizeBytes = (commitment.sizeBytes.toLong() + 1).toString(),
        )
        val raw = buildRelease(
            trade.providerEnvelope(),
            trade.orderIdHex,
            retyped,
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )
        assertTrue(raw.ok, "`buildRelease` asks for no evidence and checks nothing against the commitment")
        val cheatOutcome = trade.receivedRelease(paid, trade.send(raw, trade.provider, trade.buyer).toRecipient)
        assertTrue(cheatOutcome.ok, "§10.3: a divergent release moves the order, to `disputed`")
        assertEquals("DISPUTED", cheatOutcome.order?.state, "§10.3's identity check fails on `size`")
        assertEquals(
            "RELEASE_DOES_NOT_MATCH_COMMITMENT",
            cheatOutcome.order?.disputeGround,
            "and the order says why",
        )
    }

    /**
     * T81, T80's cheat: a stranger writes its own `type=1` under the order's id and terms, and a
     * `cancelled`, and seals both to the buyer. Stepped through the door against the stranger's
     * proposal, nothing moves — `NOT_THIS_ORDERS_PROPOSAL`. The same update through `stepOrder`, with
     * the `BUYER` a page would read off the stranger's own proposal, cancels the order: the refusal is
     * the door's.
     */
    @Test
    @JsName("a_stranger_s_proposal_and_cancellation_are_refused_NOT_THIS_ORDERS_PROPOSAL")
    fun `a stranger's proposal and cancellation are refused NOT_THIS_ORDERS_PROPOSAL`() {
        val trade = Trade()
        val forged = trade.send(
            buildProposalAgainst(
                trade.listing,
                trade.environment,
                trade.envelope(trade.stranger, trade.provider),
                trade.orderIdHex,
                trade.proposedTerms,
                feeRecipient = trade.feeRecipient.key,
            ),
            trade.stranger,
            trade.buyer,
        )
        assertEquals(trade.orderIdHex, forged.orderIdHex, "the forgery names the order's own id")
        val cancellation = trade.send(
            buildStatusUpdate(trade.envelope(trade.stranger, trade.buyer), trade.orderIdHex, "cancelled"),
            trade.stranger,
            trade.buyer,
        )

        val door = stepOrderWithStatusUpdate(
            trade.machine,
            trade.proposed,
            forged.toRecipient,
            cancellation.toRecipient,
            trade.provider.key,
        )
        assertNotStepped(door, ChannelRejection.NOT_THIS_ORDERS_PROPOSAL.name, "ChannelRejection")

        val asBuyer = partyOf(forged.toRecipient, trade.provider.key, cancellation.toRecipient.author)
        assertEquals("BUYER", asBuyer.party, "against its own proposal the stranger is the buyer")
        val token = decodeStatusUpdate(cancellation.toRecipient).statusUpdate?.statusToken
            ?: fail("the buyer reads the stranger's update")
        val unchecked = stepOrder(
            trade.machine,
            trade.proposed,
            JsOrderEvent(type = "status_update", statusToken = token, fromParty = asBuyer.party),
        )
        assertEquals("CANCELLED", unchecked.order?.state, "through `stepOrder` the stranger cancels: ${unchecked.reason}")
    }

    /**
     * T81, T79's cheat: once the order is accepted, a stranger seals a commitment to the buyer, and a
     * page names the stranger as the provider. The door refuses that key — `PROVIDER_NOT_THIS_ORDERS` —
     * and nothing moves, where the same commitment through `stepOrder` as `PROVIDER` commits.
     */
    @Test
    @JsName("a_stranger_s_commitment_with_the_stranger_as_provider_is_refused_PROVIDER_NOT_THIS_ORDERS")
    fun `a stranger's commitment with the stranger as provider is refused PROVIDER_NOT_THIS_ORDERS`() {
        val trade = Trade()
        val forged = trade.commitmentFrom(trade.stranger)

        val door = stepOrderWithCommitment(
            trade.machine,
            trade.accepted,
            trade.proposal.toSelf,
            forged.toRecipient,
            trade.stranger.key,
        )
        assertNotStepped(door, ChannelRejection.PROVIDER_NOT_THIS_ORDERS.name, "ChannelRejection")

        val read = decodeCommitment(forged.toRecipient).commitment ?: fail("the buyer reads the stranger's commitment")
        val unchecked = stepOrder(
            trade.machine,
            trade.accepted,
            JsOrderEvent(type = "delivery_committed", fromParty = "PROVIDER", commitment = read.commitment),
        )
        assertEquals("COMMITTED", unchecked.order?.state, "through `stepOrder` the stranger commits: ${unchecked.reason}")
    }

    /**
     * T81, T77's reviewer: the fee recipient seals a commitment, and the page names the right provider.
     * The door maps the sender to `FEE_RECIPIENT`, and §11.2 refuses that sender — `WRONG_SENDER` — so
     * the order stays `accepted`.
     */
    @Test
    @JsName("the_fee_recipient_s_commitment_with_the_right_provider_is_refused_WRONG_SENDER")
    fun `the fee recipient's commitment with the right provider is refused WRONG_SENDER`() {
        val trade = Trade()
        val theirs = trade.commitmentFrom(trade.feeRecipient)
        assertEquals(
            "FEE_RECIPIENT",
            partyOf(trade.proposal.toSelf, trade.provider.key, theirs.toRecipient.author).party,
            "the sender is the order's fee recipient",
        )

        val door = stepOrderWithCommitment(
            trade.machine,
            trade.accepted,
            trade.proposal.toSelf,
            theirs.toRecipient,
            trade.provider.key,
        )
        assertFalse(door.ok, "§11.2: a commitment is the provider's")
        assertEquals(TransitionRejection.WRONG_SENDER.name, door.reason)
        assertEquals("TransitionRejection", door.reasonVocabulary, "the machine's own refusal")
        assertEquals("ACCEPTED", door.order?.state, "the order is handed back unchanged")
    }

    /**
     * T91, T90's cheat: the buyer writes a second `type=1` under the order's id and terms naming a
     * stranger as the fee recipient. `proposalForFeeRecipient` over the committed order refuses it —
     * `NOT_THIS_ORDERS_PROPOSAL` — so there is no copy to seal and the stranger's inbox stays empty; the
     * one copy sent went to the order's own fee recipient.
     */
    @Test
    @JsName("the_buyer_s_second_proposal_naming_a_stranger_as_fee_recipient_gets_no_copy")
    fun `the buyer's second proposal naming a stranger as fee recipient gets no copy`() {
        val trade = Trade()
        val second = trade.send(
            buildProposalAgainst(
                trade.listing,
                trade.environment,
                trade.buyerEnvelope(),
                trade.orderIdHex,
                trade.proposedTerms,
                feeRecipient = trade.stranger.key,
            ),
            trade.buyer,
            trade.provider,
        )
        assertEquals(trade.orderIdHex, second.orderIdHex, "the second proposal names the order's own id")
        val read = decodeProposal(second.toSelf).proposal ?: fail("the buyer reads its second proposal")
        assertEquals(trade.stranger.key, read.feeRecipient, "and the stranger as its fee recipient")

        val copy = proposalForFeeRecipient(trade.committed, second.toSelf, trade.provider.key)
        assertFalse(copy.ok, "§8.7: the copy is the order's own `type=1`")
        assertEquals(ChannelRejection.NOT_THIS_ORDERS_PROPOSAL.name, copy.reason)
        assertEquals("ChannelRejection", copy.reasonVocabulary)
        assertNull(copy.event, "a refusal gives nothing to seal")
        assertNull(copy.recipientPubkey, "and names no key to seal it to")
        copy.event?.let { trade.send(it, trade.buyer, trade.stranger) }
        assertEquals(emptyList(), trade.inbox(trade.stranger), "nothing reached the stranger")
        assertEquals(
            listOf(trade.proposal.toSelf.idHex),
            trade.inbox(trade.feeRecipient).map { it.idHex },
            "the order's fee recipient holds the order's own proposal, and nothing else",
        )
    }

    /**
     * T91, T89's cheat: the fee recipient builds its request from the copy it opened, with the provider
     * as the envelope's `p`. `buildFeeRequestFromProposal` refuses it — `NOT_ADDRESSED_TO_BUYER` — and
     * nothing is sent; the same copy with its author as the one `p` builds, so the refusal is the `p`'s.
     */
    @Test
    @JsName("the_fee_recipient_s_request_addressed_to_the_provider_is_refused_NOT_ADDRESSED_TO_BUYER")
    fun `the fee recipient's request addressed to the provider is refused NOT_ADDRESSED_TO_BUYER`() {
        val trade = Trade()
        val copy = trade.inbox(trade.feeRecipient).single()
        val providerInbox = trade.inbox(trade.provider)
        val buyerInbox = trade.inbox(trade.buyer)

        val answer = buildFeeRequestFromProposal(trade.envelope(trade.feeRecipient, trade.provider), copy, trade.feeInvoice)
        assertFalse(answer.ok, "§8.7: the fee request is addressed to the buyer alone")
        assertEquals(ChannelRejection.NOT_ADDRESSED_TO_BUYER.name, answer.reason)
        assertEquals("ChannelRejection", answer.reasonVocabulary)
        assertNull(answer.event, "a refusal builds nothing")
        assertNull(answer.buyerPubkey, "and names no key to seal it to")
        answer.event?.let { trade.send(it, trade.feeRecipient, trade.provider) }
        assertEquals(providerInbox, trade.inbox(trade.provider), "nothing reached the provider")
        assertEquals(buyerInbox, trade.inbox(trade.buyer), "nor the buyer")

        val honest = buildFeeRequestFromProposal(trade.envelope(trade.feeRecipient, trade.buyer), copy, trade.feeInvoice)
        assertTrue(honest.ok, "the same copy addressed to its author builds: ${honest.reason} — ${honest.detail}")
    }

    /**
     * T95, T93's cheat: the buyer proposes with its own key as the fee recipient. `buildProposalAgainst`
     * refuses it — `FEE_RECIPIENT_IS_BUYER` — so nothing is sealed and the provider's inbox is empty; the
     * same proposal naming the order's fee recipient builds, and is the one the provider opened.
     */
    @Test
    @JsName("the_buyer_s_proposal_naming_itself_as_fee_recipient_is_refused_FEE_RECIPIENT_IS_BUYER")
    fun `the buyer's proposal naming itself as fee recipient is refused FEE_RECIPIENT_IS_BUYER`() {
        val trade = Trade()
        val cheat = trade.selfFeeProposal
        assertFalse(cheat.ok, "§8.1: the fee recipient MUST NOT be the buyer's pubkey")
        assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER.name, cheat.reason)
        assertEquals("ChannelRejection", cheat.reasonVocabulary)
        assertNull(cheat.event, "a refusal builds nothing to seal")
        assertEquals(emptyList(), trade.providerInboxAfterSelfFeeProposal, "nothing reached the provider")

        assertEquals(
            listOf(trade.proposal.toRecipient.idHex),
            trade.inbox(trade.provider).take(1).map { it.idHex },
            "the honest proposal, naming the order's fee recipient, is the first thing the provider opened",
        )
        assertEquals(trade.feeRecipient.key, decodeProposal(trade.proposal.toRecipient).proposal?.feeRecipient)
    }

    /**
     * T109, §8.1's SHOULD-warn as the guide's buyer step 4 and provider step 2 ask it (T108). The trade's
     * proposal, read by either side, and its order answer `feeRecipientIsProvider` false: the fee
     * recipient is a third key. A second proposal naming the provider answers true, and is a warning, not
     * a refusal: it still builds, the provider's acceptance of it builds, and the order accepted on it
     * answers true too.
     */
    @Test
    @JsName("the_trade_answers_feeRecipientIsProvider_false_and_a_proposal_naming_the_provider_answers_true_and_builds")
    fun `the trade answers feeRecipientIsProvider false and a proposal naming the provider answers true and builds`() {
        val trade = Trade()
        for (copy in listOf(trade.proposal.toSelf, trade.proposal.toRecipient)) {
            val read = decodeProposal(copy).proposal ?: fail("each side reads the trade's proposal")
            assertFalse(read.feeRecipientIsProvider(trade.provider.key), "§8.1: the fee recipient is not the provider")
        }
        for (order in listOf(trade.accepted, trade.committed)) {
            assertFalse(order.feeRecipientIsProvider, "the ${order.state} order answers the same")
        }

        val built = buildProposalAgainst(
            trade.listing,
            trade.environment,
            trade.buyerEnvelope(),
            SettlementFixtures.orderHex(OrderFixtures.ORDER_INDEX + 1),
            trade.proposedTerms,
            feeRecipient = trade.provider.key,
        )
        assertTrue(built.ok, "a warning, not a refusal: the proposal builds: ${built.reason} — ${built.detail}")
        val agent = trade.send(built, trade.buyer, trade.provider)
        for (copy in listOf(agent.toSelf, agent.toRecipient)) {
            val read = decodeProposal(copy).proposal ?: fail("each side reads the proposal naming the provider")
            assertTrue(read.feeRecipientIsProvider(trade.provider.key), "§8.1: warn, the fee recipient is the provider")
            assertFalse(read.feeRecipientIsProvider(trade.buyer.key), "the buyer's key is not the fee recipient")
        }

        val proposed = openOrderFromProposal(trade.machine, agent.toSelf).order ?: fail("the order opens")
        assertFalse(proposed.feeRecipientIsProvider, "before an acceptance the order holds no provider key")
        val acceptance = trade.send(
            buildAgreementAgainst(trade.listing, trade.environment, trade.providerEnvelope(), agent.toRecipient),
            trade.provider,
            trade.buyer,
        )
        val accepted = trade.stepped(
            stepOrderWithStatusUpdate(trade.machine, proposed, agent.toSelf, acceptance.toRecipient, trade.provider.key),
        )
        assertEquals("ACCEPTED", accepted.state, "the acceptance builds and the order accepts on it")
        assertTrue(accepted.feeRecipientIsProvider, "the accepted order answers true")
    }

    /**
     * T98: with no §8.7 copy no fee invoice exists, and the provider's own receipt, checked against its
     * own store, is evidenced — so its release builds, and the buyer reads it. The fee recipient was
     * never asked to do anything, and the provider's release did not wait for it.
     */
    @Test
    @JsName("with_no_fee_invoice_the_provider_s_own_receipt_is_evidenced_and_its_release_builds")
    fun `with no fee invoice the provider's own receipt is evidenced and its release builds`() {
        val silent = Trade(sendsCopy = false)
        assertNull(silent.feeRequest, "no fee invoice exists")
        val receipt = silent.providerReceipt(Trade.PROVIDER_PREIMAGE)

        val own = silent.providerOwnSettlement(receipt)
        assertEquals(Payee.PROVIDER.token, own.payee, "the provider's own payment")
        val built = silent.releaseOn(own)
        assertTrue(built.ok, "§10.3: the provider's release rests on its own evidence: ${built.reason} — ${built.detail}")
        val release = silent.send(built, silent.provider, silent.buyer).toRecipient
        assertTrue(decodeRelease(release).ok, "the buyer reads the release")
        assertEquals(emptyList(), silent.inbox(silent.feeRecipient), "and the fee recipient was sent nothing")
    }

    /**
     * T98: the buyer pays the provider and never the fee. Its own provider receipt, alone in
     * `receipts_verified`, is refused `RECEIPTS_INCOMPLETE` and the order stays `awaiting_payment`; the
     * provider's release, built on its own evidence, is refused at the door `RELEASE_ORDER_NOT_PAID`, with
     * nothing offered. The release still decodes, and the served bytes still verify against the
     * commitment: neither door reads the order.
     */
    @Test
    @JsName("a_fee_bearing_trade_with_the_fee_unpaid_stays_awaiting_payment_and_refuses_the_release")
    fun `a fee-bearing trade with the fee unpaid stays awaiting payment and refuses the release`() {
        val trade = Trade()
        val receipt = trade.providerReceipt(Trade.PROVIDER_PREIMAGE)

        val buyerCheck = verifySettlement(receipt.toSelf, trade.buyerStore.seam, trade.buyerTerms)
        val buyerOwn = buyerCheck.settlement ?: fail("the provider payment must verify: ${buyerCheck.reason}")
        assertTrue(buyerOwn.evidenced, "§9.2's evidence exists for the provider payment")
        val incomplete = stepOrder(
            trade.machine,
            trade.awaitingPayment,
            JsOrderEvent(type = "receipts_verified", settlements = arrayOf(buyerOwn)),
        )
        assertFalse(incomplete.ok, "§11.2: every required payee's receipt, or the order does not move")
        assertEquals(TransitionRejection.RECEIPTS_INCOMPLETE.name, incomplete.reason)
        assertEquals("AWAITING_PAYMENT", incomplete.order?.state, "the order is handed back unchanged")

        val release = trade.send(trade.releaseOn(trade.providerOwnSettlement(receipt)), trade.provider, trade.buyer)
        val door = trade.receivedRelease(trade.awaitingPayment, release.toRecipient)
        assertNotStepped(door, ChannelRejection.RELEASE_ORDER_NOT_PAID.name, "ChannelRejection")

        assertTrue(decodeRelease(release.toRecipient).ok, "the release still decodes")
        val commitment = trade.awaitingPayment.commitment ?: fail("an awaiting order holds its commitment")
        val served = verifyServedBytes(commitment, encodeLowerHex(OrderFixtures.blob.served))
        assertTrue(served.ok, "§10.4 step 1 still answers: ${served.reason}")
        assertEquals(listOf(trade.proposal.toSelf.idHex), trade.inbox(trade.feeRecipient).map { it.idHex }, "no fee receipt was sent")
    }

    /**
     * T98's honest buyer: the provider's early release, built before the fee is paid, is refused
     * `RELEASE_ORDER_NOT_PAID` while the order awaits payment; once the fee receipt verifies and the
     * order is `paid`, the same release rumor steps it to `released`.
     */
    @Test
    @JsName("an_early_release_is_refused_until_the_fee_receipt_verifies_then_releases_the_paid_order")
    fun `an early release is refused until the fee receipt verifies then releases the paid order`() {
        val trade = Trade()
        val receipt = trade.providerReceipt(Trade.PROVIDER_PREIMAGE)
        val early = trade.send(trade.releaseOn(trade.providerOwnSettlement(receipt)), trade.provider, trade.buyer).toRecipient

        val tooSoon = trade.receivedRelease(trade.awaitingPayment, early)
        assertNotStepped(tooSoon, ChannelRejection.RELEASE_ORDER_NOT_PAID.name, "ChannelRejection")

        val paid = trade.paid(receipt)
        val released = trade.stepped(trade.receivedRelease(paid, early), "the early release offered to the PAID order")
        assertEquals("RELEASED", released.state, "§11.2's `paid → released`, on the release sent early")
    }

    /**
     * T98: an order the provider opens from the proposal it received, stepped over its own acceptance
     * and commitment copies. Its own invoice record alone, offered as `payment_requests_received`, is
     * refused `PAYMENT_REQUESTS_INCOMPLETE` and the order stays `committed`: §11's table is the buyer's,
     * and the fee request is not sent to the provider.
     */
    @Test
    @JsName("an_order_the_provider_opens_with_its_own_request_alone_is_refused_PAYMENT_REQUESTS_INCOMPLETE")
    fun `an order the provider opens with its own request alone is refused PAYMENT_REQUESTS_INCOMPLETE`() {
        val trade = Trade()
        val committed = trade.providerCommitted()

        val own = trade.providerStore.find(trade.orderIdHex, Payee.PROVIDER.token)
            ?: fail("the provider keeps its own request")
        val outcome = stepOrder(
            trade.machine,
            committed,
            JsOrderEvent(type = "payment_requests_received", requests = arrayOf(own)),
        )
        assertFalse(outcome.ok, "§11.2: every required payee's request, or the order does not move")
        assertEquals(TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE.name, outcome.reason)
        assertEquals("COMMITTED", outcome.order?.state, "the order is handed back unchanged")
    }

    /**
     * T99: the fee recipient holds only the §8.7 copy and the fee receipt, and no stored request. The
     * order it opens from the copy, an empty store and the copy's own point refuse
     * `FEE_TERM_POINT_MISSING`; the copy's `fee` row typed in for the acceptance's and the fee
     * request's points gets past §8.4 and is refused `NO_STORED_REQUEST`, as `verifySettlement` over
     * the copy's terms is. The buyer's check of the same receipt evidences, so each refusal is the
     * empty store's.
     */
    @Test
    @JsName("a_fee_recipient_s_receipt_from_its_inbox_alone_is_refused_NO_STORED_REQUEST")
    fun `a fee recipient's receipt from its inbox alone is refused NO_STORED_REQUEST`() {
        val trade = Trade()
        val copy = trade.inbox(trade.feeRecipient).single()
        val opened = openOrderFromProposal(trade.machine, copy)
        val order = opened.order ?: fail("the fee recipient's order must open: ${opened.reason} — ${opened.detail}")
        val store = JsFlowFixtures.CrossedStore()
        val receipt = trade.feeReceipt()

        val copyPoint = proposalFeeSighting(copy).sighting ?: fail("the copy's point must read")
        val alone = verifyFeeReceipt(receipt.toRecipient, store.seam, order, arrayOf(copyPoint))
        assertFalse(alone.ok, "§8.4: the acceptance and the fee request are REQUIRED points")
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING.name, alone.reason)
        assertNull(alone.settlement, "a refusal carries no settlement")

        val typed = arrayOf("ACCEPTANCE", "FEE_PAYMENT_REQUEST").map { JsFeeTermSighting(it, copyPoint.feeTag) }
        val retyped = verifyFeeReceipt(receipt.toRecipient, store.seam, order, arrayOf(copyPoint) + typed)
        assertFalse(retyped.ok, "§9.2 check 1: the fee recipient stored no request")
        assertEquals(SettlementRejection.NO_STORED_REQUEST.name, retyped.reason)
        assertNull(retyped.settlement, "a refusal carries no settlement")

        val terms = (decodeProposal(copy).proposal ?: fail("the fee recipient reads the copy")).terms
        val plain = verifySettlement(receipt.toRecipient, store.seam, terms)
        assertFalse(plain.ok, "§9.2 check 1 again, without check 6")
        assertEquals(SettlementRejection.NO_STORED_REQUEST.name, plain.reason)
        assertNull(plain.settlement, "a refusal carries no settlement")

        val buyer = trade.feeCheck(
            receipt,
            arrayOf(trade.proposalSighting, trade.acceptanceSighting, trade.feeRequestSighting),
        )
        assertTrue(buyer.settlement?.evidenced == true, "the buyer's store, order and points evidence it: ${buyer.reason}")
    }

    /**
     * T116: the fee recipient opened §8.7's copy and sends no request. The buyer's store holds the
     * provider's request and no fee request, so `payment_requests_received` is refused
     * `PAYMENT_REQUESTS_INCOMPLETE` and the order stays `committed` — §11.2's row wants every required
     * payee's request, and `feePayeeRequired` says the fee's is. The fee recipient's own `cancelled`
     * moves nothing: §11.2 takes a cancellation from a party, and the door names it `FEE_RECIPIENT`. The
     * buyer's `cancelled`, sealed to the provider, ends its own order, stepped from its `toSelf` copy,
     * and the order the provider opened, stepped from the copy the provider received.
     */
    @Test
    @JsName("a_fee_request_that_does_not_arrive_leaves_the_buyer_s_order_committed_until_it_cancels")
    fun `a fee request that does not arrive leaves the buyer's order committed until it cancels`() {
        val waiting = Trade(answers = false)
        assertEquals(
            listOf(waiting.proposal.toSelf.idHex),
            waiting.inbox(waiting.feeRecipient).map { it.idHex },
            "§8.7: the fee recipient holds the order's own proposal",
        )
        assertNull(waiting.feeRequest, "and sent no request")
        val outcome = waiting.requestsReceived
        assertFalse(outcome.ok, "§11.2: every required payee's request, or the order does not move")
        assertEquals(TransitionRejection.PAYMENT_REQUESTS_INCOMPLETE.name, outcome.reason)
        val committed = outcome.order ?: fail("a refused step hands the order back")
        assertEquals("COMMITTED", committed.state, "the order is handed back unchanged")

        // The wait, read as a page reads it: the fee is required, and the store holds no fee request.
        assertTrue(committed.feePayeeRequired, "§8.3: the fee is not `0`, so its payee's request is required")
        assertNull(waiting.buyerStore.find(waiting.orderIdHex, Payee.FEE.token), "no fee request was stored")
        assertNotNull(waiting.buyerStore.find(waiting.orderIdHex, Payee.PROVIDER.token), "the provider's was")

        val theirs = waiting.send(
            buildStatusUpdate(waiting.envelope(waiting.feeRecipient, waiting.buyer), waiting.orderIdHex, "cancelled"),
            waiting.feeRecipient,
            waiting.buyer,
        )
        val refused = stepOrderWithStatusUpdate(
            waiting.machine,
            committed,
            waiting.proposal.toSelf,
            theirs.toRecipient,
            waiting.provider.key,
        )
        assertFalse(refused.ok, "§11.2: a cancellation is a party's, and the fee recipient is not one")
        assertEquals(TransitionRejection.WRONG_SENDER.name, refused.reason)
        assertEquals("TransitionRejection", refused.reasonVocabulary, "the machine's own refusal")
        assertEquals("COMMITTED", refused.order?.state, "the order is handed back unchanged")

        val providerOrder = waiting.providerCommitted()
        val cancel = waiting.buyerCancellation()
        val own = waiting.stepped(
            stepOrderWithStatusUpdate(waiting.machine, committed, waiting.proposal.toSelf, cancel.toSelf, waiting.provider.key),
            "the buyer's own cancellation offered to its COMMITTED order",
        )
        assertEquals("CANCELLED", own.state, "§11.2's `committed → cancelled`, on the buyer's own `type=3`")
        assertTrue(own.terminal, "`cancelled` is terminal")
        val provider = waiting.stepped(
            stepOrderWithStatusUpdate(
                waiting.machine,
                providerOrder,
                waiting.proposal.toRecipient,
                cancel.toRecipient,
                waiting.provider.key,
            ),
            "the buyer's cancellation offered to the provider's COMMITTED order",
        )
        assertEquals("CANCELLED", provider.state, "the provider's order reads the same cancellation")
        assertTrue(provider.terminal, "`cancelled` is terminal")
    }

    /**
     * T116: with the fee request received, the buyer's order is at `awaiting_payment`, and the order the
     * provider opened is still `committed`, since the fee request is sent to the buyer alone. Before
     * `paid`, §11.2 takes the buyer's `cancelled` from either state: both orders read `cancelled`.
     */
    @Test
    @JsName("a_buyer_s_cancel_at_awaiting_payment_ends_its_order_and_the_committed_order_the_provider_opened")
    fun `a buyer's cancel at awaiting payment ends its order and the committed order the provider opened`() {
        val trade = Trade()
        val awaiting = trade.awaitingPayment
        val providerOrder = trade.providerCommitted()
        assertEquals("COMMITTED", providerOrder.state, "the provider never holds the fee request")

        val cancel = trade.buyerCancellation()
        val own = trade.stepped(
            stepOrderWithStatusUpdate(trade.machine, awaiting, trade.proposal.toSelf, cancel.toSelf, trade.provider.key),
            "the buyer's own cancellation offered to its AWAITING_PAYMENT order",
        )
        assertEquals("CANCELLED", own.state, "§11.2's `awaiting_payment → cancelled`")
        assertTrue(own.terminal, "`cancelled` is terminal")
        val provider = trade.stepped(
            stepOrderWithStatusUpdate(
                trade.machine,
                providerOrder,
                trade.proposal.toRecipient,
                cancel.toRecipient,
                trade.provider.key,
            ),
            "the buyer's cancellation offered to the provider's COMMITTED order",
        )
        assertEquals("CANCELLED", provider.state, "§11.2's `committed → cancelled`, on the provider's side")
    }

    // -----------------------------------------------------------------------------------------

    /** [outcome] is a door's refusal [reason] from [vocabulary], with no event offered and so no order. */
    private fun assertNotStepped(outcome: JsOrderOutcome, reason: String, vocabulary: String) {
        assertFalse(outcome.ok, "the door must refuse: $reason")
        assertEquals(reason, outcome.reason)
        assertEquals(vocabulary, outcome.reasonVocabulary)
        assertNull(outcome.order, "nothing was offered to the order, so there is no outcome order")
    }

    /** One message as both parties hold it: the recipient's opened copy and the sender's `toSelf`. */
    private class Delivered(val toRecipient: JsRumor, val toSelf: JsRumor) {
        val orderIdHex: String? get() = toRecipient.orderIdHex
    }

    /**
     * One trade, walked through guide steps 1, 2 and 4 to 7 on construction — to `awaiting_payment` the
     * first time [awaitingPayment] is read — with the rest left to each test. [sendsCopy] false skips
     * §8.7's copy at step 7, for the control that the fee recipient then has nothing to act on.
     * [answers] false sends the copy but has the fee recipient build no request (T116), so the
     * buyer's order waits at `committed`.
     */
    private class Trade(val sendsCopy: Boolean = true, val answers: Boolean = true) {

        companion object {
            /** Small streams are free for named parties (`FakeKeyRanges`). */
            const val BUYER_STREAM: Long = 67L
            const val PROVIDER_STREAM: Long = 68L
            const val FEE_RECIPIENT_STREAM: Long = 69L

            const val NOW: Long = OrderFixtures.BEFORE_DEADLINES

            /** Three generated preimages: the provider's payment, the fee's, and one no invoice here carries. */
            private val PREIMAGES: List<String> = PaymentFixtures.preimageHex(3)
            val PROVIDER_PREIMAGE: String = PREIMAGES[0]
            val FEE_PREIMAGE: String = PREIMAGES[1]
            val OTHER_PREIMAGE: String = PREIMAGES[2]
        }

        val buyer = Party(FakeCryptoSigner(FakeKey(BUYER_STREAM)))
        val provider = Party(FakeCryptoSigner(FakeKey(PROVIDER_STREAM)))
        val feeRecipient = Party(FakeCryptoSigner(FakeKey(FEE_RECIPIENT_STREAM)))

        /** T81's cheats: `EnvelopeFixtures`' stranger stream, a key holding no role in this order. */
        val stranger = Party(FakeCryptoSigner(FakeKey(EnvelopeFixtures.STRANGER_STREAM)))

        val environment: JsEnvironment = JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(NOW),
            randomness = RecordingRandomness(),
            secp = FakeSecp256k1Ops(),
            ephemeral = FakeEphemeralSigners(),
        )
        val machine = JsOrderMachine(environment)

        /**
         * The one [JsOrderTerms] typed here: the buyer's input to its proposal. The buyer and the
         * provider read the terms back off the proposal they hold ([buyerTerms], [providerTerms]).
         */
        val proposedTerms = JsOrderTerms(
            priceMsat = OrderFixtures.TERMS.split.price.millisatoshis.toString(),
            feeBasisPoints = OrderFixtures.FEE_BASIS_POINTS,
            expirationSeconds = OrderFixtures.EXPIRATION.toString(),
            deliverBySeconds = OrderFixtures.DELIVER_BY.toString(),
        )
        val orderIdHex: String = SettlementFixtures.orderHex(OrderFixtures.ORDER_INDEX)

        val providerInvoice: String = invoice(PROVIDER_PREIMAGE, OrderFixtures.TERMS.split.price)
        val feeInvoice: String = invoice(FEE_PREIMAGE, OrderFixtures.TERMS.split.fee)

        val buyerStore = JsFlowFixtures.CrossedStore()
        val providerStore = JsFlowFixtures.CrossedStore()

        /** Each rumor a party opened from a wrap sealed to it, with that party; read through [inbox]. */
        private val received = mutableListOf<Pair<Party, JsRumor>>()

        // Guide step 1 — the provider posts an offer.
        private val offer: JsBuildResult = buildListingOffer(
            JsAuthoredListing(
                authorPubkey = provider.key,
                createdAtSeconds = (NOW - 86_400L).toString(),
                dValue = "t67-walkthrough-offer",
                title = "A 30-second animation",
                priceMsat = proposedTerms.priceMsat,
                alt = "An offer: a 30-second animation",
            ),
        ).also { assertTrue(it.ok, "the offer must build: ${it.reason} — ${it.detail}") }

        // Guide step 2 — the buyer reads the board.
        val listing: JsListing = run {
            val json = offer.event?.unsignedJson()?.value ?: fail("a built offer serialises")
            val read = decodeListing(json)
            val listing = read.listing ?: fail("the buyer must decode the offer: ${read.reason}")
            assertTrue(matchesBoard(listing, JsBoardQuery(kinds = arrayOf(NenyaKind.OFFER))), "§5.5's re-filter")
            assertEquals("ACTIVE", listingActivity(listing, environment), "§5.6: a listing with no `expiration`")
            assertTrue(checkDeadlines(listing, null, environment).permitted, "§5.6 permits acting on it")
            listing
        }

        /**
         * T95's cheat at guide step 4, tried before the honest proposal so nothing has reached the
         * provider yet: the buyer names itself as the fee recipient. Whatever it builds is sealed to the
         * provider; [providerInboxAfterSelfFeeProposal] is the provider's inbox once that is done.
         */
        val selfFeeProposal: JsBuildResult = buildProposalAgainst(
            listing,
            environment,
            buyerEnvelope(),
            orderIdHex,
            proposedTerms,
            feeRecipient = buyer.key,
        )
        val providerInboxAfterSelfFeeProposal: List<JsRumor> = run {
            selfFeeProposal.event?.let { send(it, buyer, provider) }
            inbox(provider)
        }

        // Guide step 4 — the buyer proposes and opens its order.
        val proposal: Delivered = send(
            buildProposalAgainst(
                listing,
                environment,
                buyerEnvelope(),
                orderIdHex,
                proposedTerms,
                feeRecipient = feeRecipient.key,
            ),
            buyer,
            provider,
        )
        val proposed: JsOrder = run {
            val opened = openOrderFromProposal(machine, proposal.toSelf)
            val order = opened.order ?: fail("the order must open: ${opened.reason} — ${opened.detail}")
            assertEquals("PROPOSED", order.state)
            assertEquals(orderIdHex, order.idHex, "the order id is the proposal's own")
            order
        }

        /** The terms the buyer checks its receipts against, read off its own proposal copy. */
        val buyerTerms: JsOrderTerms =
            (decodeProposal(proposal.toSelf).proposal ?: fail("the buyer must read its own proposal")).terms

        // Guide step 5 — the provider reads and accepts, keeping the proposal's terms (provider step
        // 2); the buyer checks and steps.
        private val providerRead: JsOrderProposal =
            decodeProposal(proposal.toRecipient).proposal ?: fail("the provider must read the proposal")
        val providerTerms: JsOrderTerms = providerRead.terms
        val acceptance: Delivered = run {
            val read = providerRead
            assertEquals(listing.coordinate, read.itemCoordinate, "§7.5's `item` is the offer")
            assertEquals(orderIdHex, read.orderIdHex)
            assertEquals(proposedTerms.priceMsat, read.priceMsat)
            assertEquals(feeRecipient.key, read.feeRecipient)
            send(buildAgreementAgainst(listing, environment, providerEnvelope(), proposal.toRecipient), provider, buyer)
        }
        val accepted: JsOrder = run {
            val check = checkAcceptance(proposal.toSelf, acceptance.toRecipient, provider.key)
            assertEquals("accepted", check.kind, "§7.6: all four terms byte-identical")
            stepped(
                stepOrderWithStatusUpdate(machine, proposed, proposal.toSelf, acceptance.toRecipient, provider.key),
            ).also { assertEquals("ACCEPTED", it.state) }
        }

        // Guide step 6 — the provider commits; the buyer steps from the commitment itself and reads its
        // `url` for step 10.
        val commitment: Delivered = send(
            buildCommitment(
                providerEnvelope(),
                orderIdHex,
                JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX),
            ),
            provider,
            buyer,
        )
        val committed: JsOrder = run {
            val read = decodeCommitment(commitment.toRecipient).commitment ?: fail("the buyer must read the commitment")
            assertEquals(DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX), read.url, "step 10 fetches from it")
            stepped(
                stepOrderWithCommitment(machine, accepted, proposal.toSelf, commitment.toRecipient, provider.key),
            ).also {
                assertEquals("COMMITTED", it.state)
                assertEquals(listOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY.name), it.deliveryChecksPerformed.toList())
            }
        }

        // Guide step 7 opens with §8.7's copy (T86): the buyer's door reads the fee recipient off the
        // committed order's own proposal, and the buyer seals that proposal, unchanged, to it.
        init {
            if (sendsCopy) {
                val copy = proposalForFeeRecipient(committed, proposal.toSelf, provider.key)
                assertTrue(copy.ok, "the committed order has a copy: ${copy.reason} — ${copy.detail}")
                send(
                    copy.event ?: fail("a built copy carries its event"),
                    buyer,
                    party(copy.recipientPubkey ?: fail("a built copy names its recipient")),
                )
            }
        }

        /**
         * The fee recipient's side (T87): it acts only on the copy it opened. Its request takes the order
         * id and the terms off that copy, its envelope names the copy's author, and it is sealed to the
         * key the door names. With no copy in its inbox there is nothing to build from, and no request;
         * when it does not [answers], it holds the copy and sends nothing.
         */
        val feeRequest: Delivered? = inbox(feeRecipient).singleOrNull()?.takeIf { answers }?.let { copy ->
            val answer = buildFeeRequestFromProposal(
                JsRumorEnvelope(feeRecipient.key, NOW.toString(), arrayOf(arrayOf(copy.author))),
                copy,
                feeInvoice,
            )
            assertTrue(answer.ok, "the fee recipient's request must build: ${answer.reason} — ${answer.detail}")
            send(
                answer.event ?: fail("a built request carries its event"),
                feeRecipient,
                party(answer.buyerPubkey ?: fail("a built request names the buyer")),
            )
        }

        // Then both payees' requests reach the buyer, which accepts each into its store, and the
        // provider its own into its store (provider step 4).
        val providerRequest: Delivered = send(
            buildPaymentRequest(providerEnvelope(), orderIdHex, Payee.PROVIDER.token, providerInvoice),
            provider,
            buyer,
        )

        /** The records the buyer accepted, in arrival order: the provider's, then the fee's if one came. */
        val buyerRecords: List<JsAcceptedRequest> = run {
            val signed = arrayOf(proposalSighting, acceptanceSighting)
            listOfNotNull(providerRequest, feeRequest).map { request ->
                val answer = acceptPaymentRequest(
                    request.toRecipient,
                    proposal.toSelf,
                    acceptance.toRecipient,
                    provider.key,
                    buyerStore.seam,
                    environment,
                    signed,
                )
                answer.request ?: fail("the buyer must accept the request: ${answer.reason} — ${answer.detail}")
            }
        }

        /** `payment_requests_received` with [buyerRecords], offered to the committed order. */
        val requestsReceived: JsOrderOutcome = run {
            val own = acceptPaymentRequest(
                providerRequest.toSelf,
                proposal.toRecipient,
                acceptance.toSelf,
                provider.key,
                providerStore.seam,
                environment,
            )
            assertTrue(own.ok, "the provider keeps its own request: ${own.reason} — ${own.detail}")
            stepOrder(
                machine,
                committed,
                JsOrderEvent(type = "payment_requests_received", requests = buyerRecords.toTypedArray()),
            )
        }

        /** The order [requestsReceived] advanced, which must be `awaiting_payment`. */
        val awaitingPayment: JsOrder by lazy {
            stepped(requestsReceived, "payment_requests_received offered to a COMMITTED order")
                .also { assertEquals("AWAITING_PAYMENT", it.state) }
        }

        /** Guide step 8's provider receipt, its proof [preimageHex] and its reference the stored invoice. */
        fun providerReceipt(preimageHex: String): Delivered = send(
            buildReceipt(buyerEnvelope(), orderIdHex, Payee.PROVIDER.token, providerInvoice, preimageHex),
            buyer,
            provider,
        )

        /**
         * Guide step 8, the rest of it: the fee receipt, both checked by the buyer against its own store,
         * and the order stepped to `paid`. The provider checks [receipt] against its own store too
         * (provider step 5), and keeps the settlement for [releaseAfterPayment].
         */
        fun paid(receipt: Delivered): JsOrder {
            val providerPayment = evidenced(verifySettlement(receipt.toSelf, buyerStore.seam, buyerTerms), "provider")
            val feePayment = evidenced(
                feeCheck(feeReceipt(), arrayOf(proposalSighting, acceptanceSighting, feeRequestSighting)),
                "fee",
            )
            providerSettlement = evidenced(
                verifySettlement(receipt.toRecipient, providerStore.seam, providerTerms),
                "the provider's own",
            )
            return step(
                awaitingPayment,
                JsOrderEvent(type = "receipts_verified", settlements = arrayOf(providerPayment, feePayment)),
            ).also { assertEquals("PAID", it.state) }
        }

        /** Guide step 8's fee receipt, sealed by the buyer to the fee recipient. */
        fun feeReceipt(): Delivered = send(
            buildReceipt(
                envelope(buyer, feeRecipient),
                orderIdHex,
                Payee.FEE.token,
                feeInvoice,
                FEE_PREIMAGE,
                feeRecipient = feeRecipient.key,
                terms = buyerTerms,
            ),
            buyer,
            feeRecipient,
        )

        /** The buyer's `verifyFeeReceipt` over its own copy of [receipt], with [earlierPoints]. */
        fun feeCheck(receipt: Delivered, earlierPoints: Array<JsFeeTermSighting>): JsSettlementResult =
            verifyFeeReceipt(receipt.toSelf, buyerStore.seam, awaitingPayment, earlierPoints)

        /** Guide step 7's two door-made points, kept for step 8. */
        val proposalSighting: JsFeeTermSighting
            get() = sighting(proposalFeeSighting(proposal.toSelf), "ORDER_PROPOSAL")
        val acceptanceSighting: JsFeeTermSighting
            get() = sighting(acceptanceFeeSighting(acceptance.toRecipient), "ACCEPTANCE")

        /** Guide step 8's fee-request point, read against the same proposal, acceptance and provider. */
        val feeRequestSighting: JsFeeTermSighting
            get() = sighting(
                paymentRequestFeeSighting(
                    (feeRequest ?: fail("the fee recipient sent no request")).toRecipient,
                    proposal.toSelf,
                    acceptance.toRecipient,
                    provider.key,
                ),
                "FEE_PAYMENT_REQUEST",
            )

        private var providerSettlement: JsSettlement? = null

        /** Guide step 9's T65 release, from the provider's own commitment copy, delivered to the buyer. */
        fun releaseAfterPayment(): JsRumor = send(
            buildReleaseAfterPayment(
                providerEnvelope(),
                commitment.toSelf,
                providerSettlement ?: fail("the provider has verified no payment"),
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
            provider,
            buyer,
        ).toRecipient

        /** T98: the provider's own check of its copy of [receipt] against its own store, which must evidence. */
        fun providerOwnSettlement(receipt: Delivered): JsSettlement =
            evidenced(verifySettlement(receipt.toRecipient, providerStore.seam, providerTerms), "the provider's own")

        /** T98: guide step 9's release built directly on [settlement], with no fee receipt asked for. */
        fun releaseOn(settlement: JsSettlement): JsBuildResult = buildReleaseAfterPayment(
            providerEnvelope(),
            commitment.toSelf,
            settlement,
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )

        /**
         * T98: the order the provider opens from the proposal it received, stepped over its own
         * acceptance and commitment copies to `committed`.
         */
        fun providerCommitted(): JsOrder {
            val opened = openOrderFromProposal(machine, proposal.toRecipient)
            val proposed = opened.order ?: fail("the provider's order must open: ${opened.reason} — ${opened.detail}")
            val accepted = stepped(
                stepOrderWithStatusUpdate(machine, proposed, proposal.toRecipient, acceptance.toSelf, provider.key),
            ).also { assertEquals("ACCEPTED", it.state) }
            return stepped(
                stepOrderWithCommitment(machine, accepted, proposal.toRecipient, commitment.toSelf, provider.key),
            ).also { assertEquals("COMMITTED", it.state) }
        }

        /** T116: §11.2's buyer cancellation, a `type=3` `cancelled` sealed by the buyer to the provider. */
        fun buyerCancellation(): Delivered =
            send(buildStatusUpdate(buyerEnvelope(), orderIdHex, "cancelled"), buyer, provider)

        // -------------------------------------------------------------------------------------

        /** [built] sealed by [from] to [to], opened by [to] and — its `toSelf` copy — by [from]. */
        fun send(built: JsBuildResult, from: Party, to: Party): Delivered =
            send(built.event ?: fail("the message must build: ${built.reason} — ${built.detail}"), from, to)

        /** [event] sealed by [from] to [to], opened by [to] and — its `toSelf` copy — by [from]. */
        fun send(event: JsWireEvent, from: Party, to: Party): Delivered {
            val sealed = seal(event, to.key, from.signer, environment)
            assertTrue(sealed.ok, "the message must seal: ${sealed.reason}")
            val toRecipient = sealed.toRecipient ?: fail("a sealed message has a recipient copy")
            val toSelf = sealed.toSelf ?: fail("a sealed message has a self copy")
            return Delivered(opened(toRecipient, to, from), opened(toSelf, from, from)).also {
                received += Pair(to, it.toRecipient)
            }
        }

        /** Every rumor [reader] opened from a wrap another party sealed to it, in arrival order. */
        fun inbox(reader: Party): List<JsRumor> = received.filter { it.first === reader }.map { it.second }

        /** The one party of this trade whose key is [key]; a key that is none of theirs fails. */
        fun party(key: String): Party =
            listOf(buyer, provider, feeRecipient).singleOrNull { it.key == key } ?: fail("no party of this trade holds that key")

        private fun opened(wrap: JsOutgoingWrap, reader: Party, author: Party): JsRumor {
            val answer = open(wrap.json, reader.signer, environment)
            val rumor = answer.rumor ?: fail("the addressee must open its copy: ${answer.reason}")
            assertEquals(author.key, rumor.author, "§7.2: the rumor is the sealing key's")
            assertEquals("SIGNATURE_VERIFIED", rumor.attribution, "§7.2: the seal's signature verified")
            return rumor
        }

        /** [order] stepped by [event], which must advance it. */
        fun step(order: JsOrder, event: JsOrderEvent): JsOrder =
            stepped(stepOrder(machine, order, event), "${event.type} offered to a ${order.state} order")

        /** The order [outcome] advanced to; a refusal fails, naming [what] was offered. */
        fun stepped(outcome: JsOrderOutcome, what: String = "the received message"): JsOrder {
            assertTrue(outcome.ok, "$what must advance it: ${outcome.reason} — ${outcome.detail}")
            return outcome.order ?: fail("an advanced outcome carries its order")
        }

        /** Guide step 9's buyer side: [releaseRumor] offered to [order] from the rumor itself (T78). */
        fun receivedRelease(order: JsOrder, releaseRumor: JsRumor): JsOrderOutcome =
            stepOrderWithRelease(machine, order, proposal.toSelf, releaseRumor, provider.key)

        fun buyerEnvelope(): JsRumorEnvelope = envelope(buyer, provider)

        fun providerEnvelope(): JsRumorEnvelope = envelope(provider, buyer)

        /** Guide step 6's commitment, built and sealed to the buyer by [author] — for T81's cheats. */
        fun commitmentFrom(author: Party): Delivered = send(
            buildCommitment(
                envelope(author, buyer),
                orderIdHex,
                JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX),
            ),
            author,
            buyer,
        )

        fun envelope(author: Party, to: Party): JsRumorEnvelope =
            JsRumorEnvelope(author.key, NOW.toString(), arrayOf(arrayOf(to.key)))

        /** The sighting a §8.4 door answered, which must be [point]'s. */
        private fun sighting(answer: JsFeeTermSightingResult, point: String): JsFeeTermSighting {
            val sighting = answer.sighting ?: fail("the $point door must read its point: ${answer.reason} — ${answer.detail}")
            assertEquals(point, sighting.point, "the door names its own point")
            return sighting
        }

        private fun evidenced(answer: JsSettlementResult, what: String): JsSettlement {
            val settlement = answer.settlement ?: fail("the $what receipt must verify: ${answer.reason} — ${answer.detail}")
            assertTrue(settlement.evidenced, "§9.2's evidence exists for the $what payment")
            return settlement
        }

        private fun invoice(preimageHex: String, amount: Msat): String = SettlementFixtures.invoice(
            preimageHex,
            amount,
            timestamp = NOW - SettlementFixtures.INVOICE_AGE_SECONDS,
        )
    }

    /** One party: the key it publishes, and the signer a page would supply for it. */
    private class Party(signer: FakeCryptoSigner) {
        val key: String = signer.key.hex
        val signer: JsSigner = JsFlowFixtures.crossed(signer)
    }
}
