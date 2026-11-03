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
     */
    private class Trade(val sendsCopy: Boolean = true) {

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
         * key the door names. With no copy in its inbox there is nothing to build from, and no request.
         */
        val feeRequest: Delivered? = inbox(feeRecipient).singleOrNull()?.let { copy ->
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
