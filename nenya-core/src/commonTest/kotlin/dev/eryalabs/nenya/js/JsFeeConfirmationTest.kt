package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.bid.AuthoredBid
import dev.eryalabs.nenya.bid.BidWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T94: §8.1's confirmation line read off each of the five decoded types a page shows a fee on —
 * `JsListing`, `JsBid`, `JsPrivateBid`, `JsOrderProposal` and `JsOrder` — so the page reads
 * `feeConfirmationRequired` instead of hardcoding `500`.
 *
 * Each is built and decoded through the exported entry points at `500`, `501`, `10000` and with no fee
 * term at all, and the answer is held to what §8.1 says at each: above `500` and only above it.
 */
class JsFeeConfirmationTest {

    private companion object {
        /** `null` is no `fee` tag at all. */
        val EXPECTED: List<Pair<Int?, Boolean>> = listOf(500 to false, 501 to true, 10_000 to true, null to false)

        val ITEM: String = ProposalFixtures.coordinate(0)
        val ORDER_HEX: String = ProposalFixtures.orderHex(0)
    }

    private val environment: JsEnvironment = JsFlowFixtures.crossedEnvironment(
        clock = FakeClock(OrderFixtures.BEFORE_DEADLINES),
        randomness = RecordingRandomness(),
        secp = FakeSecp256k1Ops(),
        ephemeral = FakeEphemeralSigners(),
    )

    private val author: String = TagFixtures.pubkeyFor(0)

    /** A fee recipient that is not [author] — §8.1 refuses the buyer as its own fee recipient (T93). */
    private val recipient: String = TagFixtures.pubkeyFor(1).also { assertNotEquals(author, it) }

    private val envelope = JsRumorEnvelope(author, OrderFixtures.BEFORE_DEADLINES.toString(), arrayOf(arrayOf(TagFixtures.pubkeyFor(2))))

    private fun terms(bps: Int?): JsOrderTerms = JsOrderTerms(
        priceMsat = OrderFixtures.TERMS.split.price.millisatoshis.toString(),
        feeBasisPoints = bps,
        expirationSeconds = OrderFixtures.EXPIRATION.toString(),
        deliverBySeconds = OrderFixtures.DELIVER_BY.toString(),
    )

    private fun fee(bps: Int?): FeeTerm = bps?.let { FeeTerm.of(it) } ?: FeeTerm.Absent

    private fun recipientFor(bps: Int?): String? = recipient.takeIf { bps != null && bps > 0 }

    private fun rumorOf(built: JsBuildResult, what: String): JsRumor {
        val event = built.event ?: fail("$what must build: ${built.reason} — ${built.detail}")
        return JsRumor(RumorWriterFixtures.bound(event.event))
    }

    private fun assertEachAnswer(what: String, answer: (Int?) -> Boolean) {
        for ((bps, expected) in EXPECTED) {
            assertEquals(expected, answer(bps), "$what at ${bps ?: "no fee term"}")
        }
    }

    @Test
    @JsName("a_listing_answers_section_8_1_s_line")
    fun `a listing answers §8_1's line`() {
        assertEachAnswer("JsListing") { bps ->
            val base = ListingWriterFixtures.minimal(NenyaKind.OFFER)
            val authored = AuthoredListing(
                authorPubkey = base.authorPubkey,
                createdAt = base.createdAt,
                dValue = base.dValue,
                title = base.title,
                price = base.price,
                fee = fee(bps),
                feeRecipient = recipientFor(bps),
            )
            val built = buildListingOffer(JsBoundaryFixtures.crossed(authored))
            val json = built.event?.unsignedJson()?.value ?: fail("the offer must build: ${built.reason} — ${built.detail}")
            val listing = decodeListing(json).listing ?: fail("the offer must decode")
            assertEquals(bps, listing.feeBasisPoints)
            listing.feeConfirmationRequired
        }
    }

    @Test
    @JsName("a_public_bid_answers_section_8_1_s_line")
    fun `a public bid answers §8_1's line`() {
        assertEachAnswer("JsBid") { bps ->
            val base = BidWriterFixtures.minimal()
            val authored = AuthoredBid(
                bidderPubkey = base.bidderPubkey,
                createdAt = base.createdAt,
                listing = base.listing,
                price = base.price,
                fee = fee(bps),
                feeRecipient = recipientFor(bps),
            )
            val built = buildBid(JsBoundaryFixtures.crossed(authored))
            val json = built.event?.unsignedJson()?.value ?: fail("the bid must build: ${built.reason} — ${built.detail}")
            val bid = decodeBid(json).bid ?: fail("the bid must decode")
            assertEquals(bps, bid.feeBasisPoints)
            bid.feeConfirmationRequired
        }
    }

    @Test
    @JsName("a_private_bid_answers_section_8_1_s_line")
    fun `a private bid answers §8_1's line`() {
        assertEachAnswer("JsPrivateBid") { bps ->
            val rumor = rumorOf(buildPrivateBid(envelope, ITEM, terms(bps), recipientFor(bps)), "the private bid")
            val bid = decodePrivateBid(rumor).privateBid ?: fail("the private bid must decode")
            assertEquals(bps, bid.feeBasisPoints)
            bid.feeConfirmationRequired
        }
    }

    @Test
    @JsName("a_proposal_answers_section_8_1_s_line")
    fun `a proposal answers §8_1's line`() {
        assertEachAnswer("JsOrderProposal") { bps ->
            val rumor = rumorOf(buildProposal(envelope, ORDER_HEX, ITEM, terms(bps), recipientFor(bps)), "the proposal")
            val proposal = decodeProposal(rumor).proposal ?: fail("the proposal must decode")
            assertEquals(bps, proposal.feeBasisPoints)
            proposal.feeConfirmationRequired
        }
    }

    @Test
    @JsName("an_order_answers_section_8_1_s_line")
    fun `an order answers §8_1's line`() {
        assertEachAnswer("JsOrder") { bps ->
            val opened = openOrder(JsOrderMachine(environment), ORDER_HEX, terms(bps), OrderFixtures.BEFORE_DEADLINES.toString())
            val order = opened.order ?: fail("the order must open: ${opened.reason} — ${opened.detail}")
            assertTrue(opened.ok)
            assertEquals(bps, order.feeBasisPoints)
            order.feeConfirmationRequired
        }
    }
}
