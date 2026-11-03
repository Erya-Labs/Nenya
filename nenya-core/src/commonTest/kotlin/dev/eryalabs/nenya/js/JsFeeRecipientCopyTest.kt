package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.FeeRecipientCopy
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T86: decision T's buyer door, `proposalForFeeRecipient`, through the exported entry points.
 *
 * Buyer, provider and fee recipient are three [FakeCryptoSigner]s crossed by [JsFlowFixtures.crossed];
 * every message is built, [seal]ed and [open]ed, and the buyer's order is opened from its own `toSelf`
 * copy of the proposal and stepped to `committed` through the `stepOrderWith…` doors. The copy the door
 * answers is then sealed by the buyer to the key the door named, and the fee recipient opens it.
 */
class JsFeeRecipientCopyTest {

    private companion object {
        /** Small streams are free for named parties (`FakeKeyRanges`). */
        const val BUYER_STREAM: Long = 86L
        const val PROVIDER_STREAM: Long = 87L
        const val FEE_RECIPIENT_STREAM: Long = 88L

        const val NOW: Long = OrderFixtures.BEFORE_DEADLINES
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

    /**
     * The proof: the copy the door answers, sealed by the buyer to the key it named, is opened by the
     * fee recipient as a rumor equal to the buyer's own `toSelf` copy, attributed to the buyer, and
     * `decodeProposal` reads the same terms off it. The crossed answer equals the Kotlin call's.
     */
    @Test
    @JsName("the_fee_recipient_opens_the_buyer_s_own_proposal_with_the_same_terms")
    fun `the fee recipient opens the buyer's own proposal, with the same terms`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val copy = proposalForFeeRecipient(trade.committed, trade.proposal.toSelf, provider.key)
        assertTrue(copy.ok, "the committed fee-bearing order has a copy: ${copy.reason} — ${copy.detail}")
        assertEquals("built", copy.kind)
        assertEquals(feeRecipient.key, copy.recipientPubkey, "the key the fee term names")
        assertFalse(feeRecipient.key in copy.toString() || buyer.key in copy.toString(), "the answer's text names no key")

        // Crossed equals Kotlin, field for field.
        val kotlin = FeeRecipientCopy.of(trade.committed.order, OrderProposal.decode(bound(trade.proposal.toSelf)), provider.key)
        assertEquals(kotlin.recipient, copy.recipientPubkey)
        val event = copy.event ?: fail("a built copy carries its event")
        assertEquals(JsWireEvent(kotlin.encode()).unsignedJson().value, event.unsignedJson().value)

        // Sealed by the buyer, opened by the fee recipient.
        val sealed = seal(event, copy.recipientPubkey ?: fail("a built copy names its recipient"), buyer.signer, environment)
        assertTrue(sealed.ok, "the copy must seal: ${sealed.reason}")
        val wrap = sealed.toRecipient ?: fail("a sealed copy has a recipient copy")
        val opened = open(wrap.json, feeRecipient.signer, environment).rumor ?: fail("the fee recipient must open its copy")
        assertEquals(buyer.key, opened.author, "§7.2: attributed to the buyer")
        assertEquals("SIGNATURE_VERIFIED", opened.attribution)
        JsFlowFixtures.assertSameRumor(opened, trade.proposal.toSelf.rumor, "the fee recipient's copy against the buyer's own")
        assertEquals(
            JsWireEvent(trade.proposal.toSelf.rumor.encode()).unsignedJson().value,
            JsWireEvent(opened.rumor.encode()).unsignedJson().value,
            "the type=1, unchanged",
        )

        val theirs = decodeProposal(opened).proposal ?: fail("the fee recipient must read the proposal")
        val ours = decodeProposal(trade.proposal.toSelf).proposal ?: fail("the buyer reads its own")
        assertEquals(ours.orderIdHex, theirs.orderIdHex)
        assertEquals(ours.itemCoordinate, theirs.itemCoordinate)
        assertEquals(ours.priceMsat, theirs.priceMsat)
        assertEquals(ours.feeRecipient, theirs.feeRecipient)
        assertEquals(feeRecipient.key, theirs.feeRecipient, "the fee recipient finds itself named in the term")
        assertEquals(ours.terms.priceMsat, theirs.terms.priceMsat)
        assertEquals(ours.terms.feeBasisPoints, theirs.terms.feeBasisPoints)
        assertEquals(ours.terms.expirationSeconds, theirs.terms.expirationSeconds)
        assertEquals(ours.terms.deliverBySeconds, theirs.terms.deliverBySeconds)
    }

    /** Before `committed` there is no copy: `proposed` and `accepted` each answer `ORDER_NOT_COMMITTED`. */
    @Test
    @JsName("an_order_not_in_committed_builds_nothing")
    fun `an order not in committed builds nothing`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        for (order in listOf(trade.proposed, trade.accepted)) {
            assertBuildsNothing(
                proposalForFeeRecipient(order, trade.proposal.toSelf, provider.key),
                ChannelRejection.ORDER_NOT_COMMITTED,
                null,
                "an order in ${order.state}",
            )
        }
    }

    /** A proposal `OrderBinding.check` refuses builds nothing: another author, or a provider key the acceptance was not checked against. */
    @Test
    @JsName("a_proposal_the_binding_check_refuses_builds_nothing")
    fun `a proposal the binding check refuses builds nothing`() {
        val trade = Trade(feeBearing, feeRecipient.key)
        val forged = trade.send(trade.proposalBuilt(stranger), stranger, provider).toSelf
        assertEquals(stranger.key, forged.author, "the stranger's own type=1, under the order's id and terms")
        assertBuildsNothing(
            proposalForFeeRecipient(trade.committed, forged, provider.key),
            ChannelRejection.NOT_THIS_ORDERS_PROPOSAL,
            null,
            "the stranger's proposal",
        )
        assertBuildsNothing(
            proposalForFeeRecipient(trade.committed, trade.proposal.toSelf, stranger.key),
            ChannelRejection.PROVIDER_NOT_THIS_ORDERS,
            null,
            "the stranger as provider",
        )
    }

    /**
     * No fee to request builds nothing: a proposal with no `fee` tag, and §8.3's own zero-fee example —
     * 1 bps on 3 000 msat — whose term names a recipient yet owes it `0`.
     */
    @Test
    @JsName("an_order_with_no_fee_to_request_builds_nothing")
    fun `an order with no fee to request builds nothing`() {
        val absent = Trade(JsOrderTerms(feeBearing.priceMsat, null, feeBearing.expirationSeconds, feeBearing.deliverBySeconds), null)
        val roundsToZero = Trade(
            JsOrderTerms("3000", 1, feeBearing.expirationSeconds, feeBearing.deliverBySeconds),
            feeRecipient.key,
        )
        for ((what, trade) in listOf("no fee term" to absent, "a fee that rounds to 0" to roundsToZero)) {
            assertEquals("COMMITTED", trade.committed.state, "$what: the order is committed")
            assertBuildsNothing(
                proposalForFeeRecipient(trade.committed, trade.proposal.toSelf, provider.key),
                ChannelRejection.NO_FEE_TO_REQUEST,
                "fee",
                what,
            )
        }
    }

    private fun assertBuildsNothing(answer: JsFeeRecipientCopy, expected: ChannelRejection, tag: String?, what: String) {
        assertFalse(answer.ok, what)
        assertEquals("refused", answer.kind, what)
        assertEquals(expected.name, answer.reason, what)
        assertEquals("ChannelRejection", answer.reasonVocabulary, what)
        assertEquals(tag, answer.tag, what)
        assertNull(answer.event, "$what: nothing built")
        assertNull(answer.recipientPubkey, "$what: no addressee")
        for (key in listOf(buyer.key, provider.key, feeRecipient.key, stranger.key)) {
            assertFalse(key in answer.toString() || key in answer.detail.orEmpty(), "$what: the refusal names no key")
        }
    }

    private fun bound(rumor: JsRumor): AttributedRumor.Bound =
        rumor.rumor as? AttributedRumor.Bound ?: fail("a proposal is a bound rumor")

    /** One message as both parties hold it: the recipient's opened copy and the sender's `toSelf`. */
    private class Delivered(val toRecipient: JsRumor, val toSelf: JsRumor)

    /** The buyer's order on [terms], from the proposal to `committed`, every message sealed and opened. */
    private inner class Trade(private val terms: JsOrderTerms, private val feeRecipientKey: String?) {

        fun proposalBuilt(author: Party): JsBuildResult =
            buildProposal(envelope(author, provider), orderIdHex, item, terms, feeRecipient = feeRecipientKey)

        val proposal: Delivered = send(proposalBuilt(buyer), buyer, provider)

        val proposed: JsOrder =
            stepped(openOrderFromProposal(machine, proposal.toSelf).order, "the order must open")

        val accepted: JsOrder = run {
            val read = decodeProposal(proposal.toRecipient).proposal ?: fail("the provider must read the proposal")
            val acceptance = send(
                buildStatusUpdate(envelope(provider, buyer), orderIdHex, "accepted", read.itemCoordinate, read.terms, read.feeRecipient),
                provider,
                buyer,
            )
            val outcome = stepOrderWithStatusUpdate(machine, proposed, proposal.toSelf, acceptance.toRecipient, provider.key)
            stepped(outcome.order?.takeIf { outcome.ok }, "the acceptance must step: ${outcome.reason} — ${outcome.detail}")
        }

        val committed: JsOrder = run {
            val commitment = send(
                buildCommitment(
                    envelope(provider, buyer),
                    orderIdHex,
                    JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                    DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX),
                ),
                provider,
                buyer,
            )
            val outcome = stepOrderWithCommitment(machine, accepted, proposal.toSelf, commitment.toRecipient, provider.key)
            stepped(outcome.order?.takeIf { outcome.ok }, "the commitment must step: ${outcome.reason} — ${outcome.detail}")
                .also { assertEquals("COMMITTED", it.state) }
        }

        /** [built] sealed by [from] to [to], opened by [to] and — its `toSelf` copy — by [from]. */
        fun send(built: JsBuildResult, from: Party, to: Party): Delivered {
            val event = built.event ?: fail("the message must build: ${built.reason} — ${built.detail}")
            val sealed = seal(event, to.key, from.signer, environment)
            assertTrue(sealed.ok, "the message must seal: ${sealed.reason}")
            fun opened(wrap: JsOutgoingWrap?, reader: Party): JsRumor =
                open((wrap ?: fail("a sealed message has both copies")).json, reader.signer, environment).rumor
                    ?: fail("the addressee must open its copy")
            return Delivered(opened(sealed.toRecipient, to), opened(sealed.toSelf, from))
        }

        private fun stepped(order: JsOrder?, what: String): JsOrder = order ?: fail(what)

        private fun envelope(author: Party, to: Party): JsRumorEnvelope =
            JsRumorEnvelope(author.key, NOW.toString(), arrayOf(arrayOf(to.key)))
    }

    /** One party: the key it publishes, and the signer a page would supply for it. */
    private class Party(signer: FakeCryptoSigner) {
        val key: String = signer.key.hex
        val signer: JsSigner = JsFlowFixtures.crossed(signer)
    }
}
