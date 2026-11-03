package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T87: decision T's fee-recipient door, `buildFeeRequestFromProposal`, through the exported entry points.
 *
 * Buyer, provider and fee recipient are three [FakeCryptoSigner]s crossed by [JsFlowFixtures.crossed].
 * The buyer's order is stepped to `committed` through the `stepOrderWith…` doors, the buyer seals the
 * copy T86's [proposalForFeeRecipient] answers, and the fee recipient writes its request from the copy
 * it opened — typing no order id, no recipient and no terms — and seals it to the key the door named.
 */
class JsFeeRequestFromProposalTest {

    private companion object {
        /** Small streams are free for named parties (`FakeKeyRanges`). */
        const val BUYER_STREAM: Long = 89L
        const val PROVIDER_STREAM: Long = 90L
        const val FEE_RECIPIENT_STREAM: Long = 91L

        const val NOW: Long = OrderFixtures.BEFORE_DEADLINES

        /** Two generated preimages: the provider's invoice and the fee recipient's. */
        private val PREIMAGES: List<String> = PaymentFixtures.preimageHex(2)
    }

    private val buyer = Party(FakeCryptoSigner(FakeKey(BUYER_STREAM)))
    private val provider = Party(FakeCryptoSigner(FakeKey(PROVIDER_STREAM)))
    private val feeRecipient = Party(FakeCryptoSigner(FakeKey(FEE_RECIPIENT_STREAM)))
    private val stranger = Party(FakeCryptoSigner(FakeKey(EnvelopeFixtures.STRANGER_STREAM)))

    private val environment: JsEnvironment = JsFlowFixtures.crossedEnvironment(
        clock = FakeClock(NOW),
        randomness = RecordingRandomness(),
        secp = FakeSecp256k1Ops(),
        ephemeral = FakeEphemeralSigners(),
    )
    private val machine = JsOrderMachine(environment)
    private val orderIdHex: String = SettlementFixtures.orderHex(OrderFixtures.ORDER_INDEX)
    private val item: String = ProposalFixtures.coordinate(OrderFixtures.ORDER_INDEX)

    /** `OrderFixtures.TERMS`: 250 bps on a price large enough that the fee is not `0`. */
    private val feeBearing = JsOrderTerms(
        priceMsat = OrderFixtures.TERMS.split.price.millisatoshis.toString(),
        feeBasisPoints = OrderFixtures.FEE_BASIS_POINTS,
        expirationSeconds = OrderFixtures.EXPIRATION.toString(),
        deliverBySeconds = OrderFixtures.DELIVER_BY.toString(),
    )

    private val providerInvoice: String = invoice(PREIMAGES[0], OrderFixtures.TERMS.split.price)
    private val feeInvoice: String = invoice(PREIMAGES[1], OrderFixtures.TERMS.split.fee)

    /**
     * The proof: the request the fee recipient builds from the copy it opened, sealed to the buyer the
     * door named, passes the buyer's [acceptPaymentRequest]; beside the provider's it moves the buyer's
     * order `committed → awaiting_payment`. The crossed answer equals the Kotlin call's.
     */
    @Test
    @JsName("the_fee_request_built_from_the_copy_moves_the_buyer_s_order_to_awaiting_payment")
    fun `the fee request built from the copy moves the buyer's order to awaiting_payment`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val copy = trade.copyOpenedByFeeRecipient()

        val answer = buildFeeRequestFromProposal(trade.envelope(feeRecipient, buyer), copy, feeInvoice)
        assertTrue(answer.ok, "the fee recipient's request must build: ${answer.reason} — ${answer.detail}")
        assertEquals("built", answer.kind)
        assertEquals(buyer.key, answer.buyerPubkey, "sealed to the proposal's author")
        for (key in listOf(buyer.key, provider.key, feeRecipient.key)) {
            assertFalse(key in answer.toString(), "the answer's text names no key")
        }
        val event = answer.event ?: fail("a built request carries its event")

        // Crossed equals Kotlin.
        val kotlin = PaymentWriter.feeRequestFromProposal(
            trade.envelope(feeRecipient, buyer).asRumorEnvelope(),
            OrderProposal.decode(copy.rumor as? AttributedRumor.Bound ?: fail("the copy is a bound rumor")),
            Bolt11Reference.recognise(feeInvoice),
        ).asJsBuildResult()
        assertEquals(kotlin.event?.unsignedJson()?.value, event.unsignedJson().value)

        // Sealed by the fee recipient to the key the door named; the buyer accepts it.
        val feeRequest = trade.send(event, feeRecipient, answer.buyerPubkey ?: fail("a built request names the buyer"), buyer)
        val providerRequest = trade.send(
            buildPaymentRequest(trade.envelope(provider, buyer), orderIdHex, Payee.PROVIDER.token, providerInvoice).event
                ?: fail("the provider's request must build"),
            provider,
            buyer.key,
            buyer,
        )
        val records = listOf(providerRequest, feeRequest).map { request ->
            val accepted = trade.accept(request)
            accepted.request ?: fail("the buyer must accept the request: ${accepted.reason} — ${accepted.detail}")
        }
        assertEquals(listOf("provider", "fee"), records.map { it.payee })

        val outcome = stepOrder(machine, trade.committed, JsOrderEvent(type = "payment_requests_received", requests = records.toTypedArray()))
        assertTrue(outcome.ok, "both requests move the order: ${outcome.reason} — ${outcome.detail}")
        assertEquals("AWAITING_PAYMENT", outcome.order?.state)

        // The control: the provider's alone does not.
        val alone = stepOrder(machine, trade.committed, JsOrderEvent(type = "payment_requests_received", requests = arrayOf(records[0])))
        assertFalse(alone.ok, "without the fee request the order stays committed")
    }

    /** Only the fee term's recipient writes it: the buyer, the provider and a stranger each build nothing. */
    @Test
    @JsName("an_envelope_author_other_than_the_fee_recipient_builds_nothing")
    fun `an envelope author other than the fee recipient builds nothing`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val copy = trade.copyOpenedByFeeRecipient()
        for ((what, author) in listOf("the buyer" to buyer, "the provider" to provider, "a stranger" to stranger)) {
            assertBuildsNothing(
                buildFeeRequestFromProposal(trade.envelope(author, buyer), copy, feeInvoice),
                ChannelRejection.NOT_THE_FEE_RECIPIENT.name,
                "ChannelRejection",
                null,
                what,
            )
        }
    }

    /**
     * T89: the library addresses the request to the buyer. `p` = the provider, the buyer beside a stranger,
     * and no `p` each build nothing, and each crossed refusal equals the Kotlin call's.
     */
    @Test
    @JsName("an_envelope_not_addressed_to_exactly_the_buyer_builds_nothing_crossed_equal_to_kotlin")
    fun `an envelope not addressed to exactly the buyer builds nothing, crossed equal to Kotlin`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val copy = trade.copyOpenedByFeeRecipient()
        val proposal = OrderProposal.decode(copy.rumor as? AttributedRumor.Bound ?: fail("the copy is a bound rumor"))
        for ((what, to) in listOf(
            "p = the provider" to arrayOf(arrayOf(provider.key)),
            "p = the buyer and a stranger" to arrayOf(arrayOf(buyer.key), arrayOf(stranger.key)),
            "no p" to emptyArray(),
        )) {
            val envelope = JsRumorEnvelope(feeRecipient.key, NOW.toString(), to)
            val answer = buildFeeRequestFromProposal(envelope, copy, feeInvoice)
            assertBuildsNothing(answer, ChannelRejection.NOT_ADDRESSED_TO_BUYER.name, "ChannelRejection", null, what)
            val kotlin = PaymentWriter.feeRequestFromProposal(envelope.asRumorEnvelope(), proposal, Bolt11Reference.recognise(feeInvoice))
                .asJsBuildResult()
            assertEquals(kotlin.reason, answer.reason, "$what: crossed equals Kotlin")
            assertEquals(kotlin.reasonVocabulary, answer.reasonVocabulary, what)
            assertEquals(kotlin.tag, answer.tag, what)
            assertEquals(kotlin.detail, answer.detail, what)
        }
    }

    /** §8.3's own zero-fee example — 1 bps on 3 000 msat — names a recipient yet owes it `0`. */
    @Test
    @JsName("a_proposal_whose_fee_is_zero_builds_nothing")
    fun `a proposal whose fee is zero builds nothing`() {
        val trade = Trade(JsOrderTerms("3000", 1, feeBearing.expirationSeconds, feeBearing.deliverBySeconds), feeRecipient.key)
        assertBuildsNothing(
            buildFeeRequestFromProposal(trade.envelope(feeRecipient, buyer), trade.proposal.toRecipient, invoice(PREIMAGES[1], Msat.ofMsat(1))),
            SettlementRejection.PAYEE_NOT_REQUIRED.name,
            "SettlementRejection",
            "fee",
            "a fee that rounds to 0",
        )
    }

    /** §8.6 and §9.2 check 4: an invoice for the price, or for one msat over the fee, builds nothing. */
    @Test
    @JsName("an_invoice_for_any_amount_but_fee_msat_builds_nothing")
    fun `an invoice for any amount but fee_msat builds nothing`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val copy = trade.copyOpenedByFeeRecipient()
        for ((what, amount) in listOf(
            "the price" to OrderFixtures.TERMS.split.price,
            "one msat over the fee" to OrderFixtures.TERMS.split.fee + Msat.ofMsat(1),
        )) {
            assertBuildsNothing(
                buildFeeRequestFromProposal(trade.envelope(feeRecipient, buyer), copy, invoice(PREIMAGES[1], amount)),
                SettlementRejection.INVOICE_AMOUNT_MISMATCH.name,
                "SettlementRejection",
                "payment",
                what,
            )
        }
    }

    private fun assertBuildsNothing(answer: JsFeeRequest, reason: String, vocabulary: String, tag: String?, what: String) {
        assertFalse(answer.ok, what)
        assertEquals("refused", answer.kind, what)
        assertEquals(reason, answer.reason, what)
        assertEquals(vocabulary, answer.reasonVocabulary, what)
        assertEquals(tag, answer.tag, what)
        assertNull(answer.event, "$what: nothing built")
        assertNull(answer.buyerPubkey, "$what: no addressee")
        for (key in listOf(buyer.key, provider.key, feeRecipient.key, stranger.key)) {
            assertFalse(key in answer.toString() || key in answer.detail.orEmpty(), "$what: the refusal names no key")
        }
    }

    private fun invoice(preimageHex: String, amount: Msat): String =
        SettlementFixtures.invoice(preimageHex, amount, timestamp = NOW - SettlementFixtures.INVOICE_AGE_SECONDS)

    /** One message as both parties hold it: the recipient's opened copy and the sender's `toSelf`. */
    private class Delivered(val toRecipient: JsRumor, val toSelf: JsRumor)

    /** The buyer's order on [terms], from the proposal to `committed`, every message sealed and opened. */
    private inner class Trade(terms: JsOrderTerms, feeRecipientKey: String?) {

        val proposal: Delivered = send(
            buildProposal(envelope(buyer, provider), orderIdHex, item, terms, feeRecipient = feeRecipientKey).event
                ?: fail("the proposal must build"),
            buyer,
            provider.key,
            provider,
        )

        private val proposed: JsOrder =
            openOrderFromProposal(machine, proposal.toSelf).order ?: fail("the order must open")

        private val acceptance: Delivered = run {
            val read = decodeProposal(proposal.toRecipient).proposal ?: fail("the provider must read the proposal")
            send(
                buildStatusUpdate(envelope(provider, buyer), orderIdHex, "accepted", read.itemCoordinate, read.terms, read.feeRecipient).event
                    ?: fail("the acceptance must build"),
                provider,
                buyer.key,
                buyer,
            )
        }

        val committed: JsOrder = run {
            val accepted = stepOrderWithStatusUpdate(machine, proposed, proposal.toSelf, acceptance.toRecipient, provider.key)
            val commitment = send(
                buildCommitment(
                    envelope(provider, buyer),
                    orderIdHex,
                    JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                    DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX),
                ).event ?: fail("the commitment must build"),
                provider,
                buyer.key,
                buyer,
            )
            val outcome = stepOrderWithCommitment(
                machine,
                accepted.order ?: fail("the acceptance must step: ${accepted.reason}"),
                proposal.toSelf,
                commitment.toRecipient,
                provider.key,
            )
            (outcome.order?.takeIf { outcome.ok } ?: fail("the commitment must step: ${outcome.reason}"))
                .also { assertEquals("COMMITTED", it.state) }
        }

        /** T86's copy, sealed by the buyer to the key its door named and opened by the fee recipient. */
        fun copyOpenedByFeeRecipient(): JsRumor {
            val copy = proposalForFeeRecipient(committed, proposal.toSelf, provider.key)
            assertTrue(copy.ok, "the committed order has a copy: ${copy.reason}")
            val to = copy.recipientPubkey ?: fail("a built copy names its recipient")
            assertEquals(feeRecipient.key, to)
            return send(copy.event ?: fail("a built copy carries its event"), buyer, to, feeRecipient).toRecipient
        }

        /** The buyer's [acceptPaymentRequest] over [request], with the proposal's and the acceptance's points. */
        fun accept(request: Delivered): JsAcceptedRequestResult = acceptPaymentRequest(
            request.toRecipient,
            proposal.toSelf,
            acceptance.toRecipient,
            provider.key,
            buyerStore.seam,
            environment,
            arrayOf(
                proposalFeeSighting(proposal.toSelf).sighting ?: fail("the proposal's point"),
                acceptanceFeeSighting(acceptance.toRecipient).sighting ?: fail("the acceptance's point"),
            ),
        )

        private val buyerStore = JsFlowFixtures.CrossedStore()

        /** [event] sealed by [from] to [toKey], opened by [reader] and — its `toSelf` copy — by [from]. */
        fun send(event: JsWireEvent, from: Party, toKey: String, reader: Party): Delivered {
            val sealed = seal(event, toKey, from.signer, environment)
            assertTrue(sealed.ok, "the message must seal: ${sealed.reason}")
            fun opened(wrap: JsOutgoingWrap?, by: Party): JsRumor =
                open((wrap ?: fail("a sealed message has both copies")).json, by.signer, environment).rumor
                    ?: fail("the addressee must open its copy")
            return Delivered(opened(sealed.toRecipient, reader), opened(sealed.toSelf, from))
        }

        fun envelope(author: Party, to: Party): JsRumorEnvelope =
            JsRumorEnvelope(author.key, NOW.toString(), arrayOf(arrayOf(to.key)))
    }

    /** One party: the key it publishes, and the signer a page would supply for it. */
    private class Party(signer: FakeCryptoSigner) {
        val key: String = signer.key.hex
        val signer: JsSigner = JsFlowFixtures.crossed(signer)
    }
}
