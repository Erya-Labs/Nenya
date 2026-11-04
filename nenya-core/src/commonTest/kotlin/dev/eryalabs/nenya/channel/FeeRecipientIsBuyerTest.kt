package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.AuthoredBid
import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidWriter
import dev.eryalabs.nenya.bid.BidWriterFixtures
import dev.eryalabs.nenya.js.JsBid
import dev.eryalabs.nenya.js.JsBoundaryFixtures
import dev.eryalabs.nenya.js.JsEnvironment
import dev.eryalabs.nenya.js.JsListing
import dev.eryalabs.nenya.js.JsOrderTerms
import dev.eryalabs.nenya.js.JsRumor
import dev.eryalabs.nenya.js.JsRumorEnvelope
import dev.eryalabs.nenya.js.buildProposal
import dev.eryalabs.nenya.js.buildProposalForBid
import dev.eryalabs.nenya.js.decodeProposal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T93 — §8.1's "The recipient MUST NOT be the buyer's pubkey", held on the `type=1`.
 *
 * Both doors are proved: `OrderProposal.decode` (and the crossed `decodeProposal`) refuses a proposal
 * whose `fee` names its own author, and `RumorWriter.proposal` (and through it `ListingOrderWriter`
 * and the crossed `buildProposal`) builds nothing whose envelope author is the recipient it was
 * handed. The provider as fee recipient is §8.1's MAY, and still builds and decodes.
 *
 * T107 — the bid path. `ListingOrderWriter.proposalForBid` copies the recipient off the bid, so a bid
 * naming the buyer — the request's author on a request, the bidder itself on an offer — reaches the
 * same `RumorWriter` check, and the crossed `buildProposalForBid` answers it as a value.
 */
class FeeRecipientIsBuyerTest {

    private companion object {
        val INDEX: Int = OrderFixtures.ORDER_INDEX

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(160).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        /** A generated proposal whose `fee` names [recipient] as its third element. */
        fun namingAsFeeRecipient(recipient: String): AttributedRumor.Bound = ProposalFixtures.bound(
            ProposalFixtures.proposalTags(
                index = INDEX,
                fee = listOf(ChannelVocabulary.FEE, OrderFixtures.FEE_BASIS_POINTS.toString(), recipient),
            ),
            INDEX,
        )

        fun withFeeRecipient(case: RumorWriterFixtures.Case, recipient: String?): RumorBuild = RumorWriter.proposal(
            case.envelope,
            case.order ?: fail("$case must carry an order id"),
            case.item ?: fail("$case must carry an item"),
            case.terms ?: fail("$case must carry terms"),
            recipient,
            case.amountSat,
        )

        const val LISTING_AUTHOR_INDEX: Int = 1
        const val BIDDER_INDEX: Int = 2

        val LISTING_AUTHOR: String = TagFixtures.pubkeyFor(LISTING_AUTHOR_INDEX)
        val BIDDER: String = TagFixtures.pubkeyFor(BIDDER_INDEX)
        val THIRD_KEY: String = TagFixtures.pubkeyFor(7)

        val BID_ORDER: OrderId = RumorWriterFixtures.orderFor(0)

        /** A request and an offer, the two sides §6 lets a bid be made against. */
        val SIDES: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER)

        /** A listing of [kind] by [LISTING_AUTHOR], open-ended, so no deadline reads the clock. */
        fun bidListing(kind: Int): Listing =
            ListingFixtures.decode(ListingFixtures.minimal(kind), kind, index = LISTING_AUTHOR_INDEX)

        /** A bid by [BIDDER] on [on] whose `fee` names [recipient], built by [BidWriter] and decoded. */
        fun bidNaming(on: Listing, recipient: String): Bid = BidWriterFixtures.decode(
            BidWriterFixtures.built(
                BidWriter.bid(
                    AuthoredBid(
                        bidderPubkey = BIDDER,
                        createdAt = BidWriterFixtures.CREATED_AT,
                        listing = on.coordinate,
                        price = Msat.ofSat(90_000L),
                        fee = FeeTerm.of(OrderFixtures.FEE_BASIS_POINTS),
                        feeRecipient = recipient,
                    ),
                ),
                "a bid on kind ${on.kind} naming a fee recipient",
            ),
        )

        /** Decision H: the buyer on a request is its author, on an offer the bidder. */
        fun buyerOn(listing: Listing): String = if (listing.kind == NenyaKind.REQUEST) LISTING_AUTHOR else BIDDER

        fun providerOn(listing: Listing): String = if (listing.kind == NenyaKind.REQUEST) BIDDER else LISTING_AUTHOR

        /** Decision H's envelope on [listing]: the buyer, to the provider. */
        fun buyerEnvelope(listing: Listing): RumorEnvelope =
            RumorEnvelope(buyerOn(listing), ListingFixtures.CREATED_AT, listOf(PubkeyRef(providerOn(listing))))

        fun proposalFor(listing: Listing, bid: Bid): ListingOrderBuild =
            ListingOrderWriter.proposalForBid(listing, bid, NenyaClock.FAIL_CLOSED, buyerEnvelope(listing), BID_ORDER)

        fun crossedFor(listing: Listing, bid: Bid) = buildProposalForBid(
            JsListing(listing),
            JsBid(bid),
            JsEnvironment(),
            JsBoundaryFixtures.crossed(buyerEnvelope(listing)),
            BID_ORDER.toHex(),
        )
    }

    @Test
    @JsName("a_proposal_naming_its_author_as_fee_recipient_is_refused_by_name_in_either_case")
    fun `a proposal naming its author as fee recipient is refused by name, in either case`() {
        val buyer = SettlementFixtures.buyer(INDEX)
        for (spelled in listOf(buyer, buyer.uppercase())) {
            val what = if (spelled == buyer) "lowercase" else "uppercase"
            val rumor = namingAsFeeRecipient(spelled)
            assertEquals(buyer, rumor.author, "$what: the fixture's author is the buyer")
            val refused = try {
                OrderProposal.decode(rumor)
                fail("$what: §8.1 forbids the buyer as fee recipient")
            } catch (refusal: ChannelException) {
                refusal
            }
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER, refused.reason, what)
            assertEquals(ChannelVocabulary.FEE, refused.tag, what)
            assertFalse(buyer in (refused.message ?: ""), "$what: the refusal names no key")
            assertFalse(buyer.uppercase() in (refused.message ?: ""), "$what: the refusal names no key")

            val crossed = decodeProposal(JsRumor(rumor))
            assertFalse(crossed.ok, what)
            assertEquals("refused", crossed.kind, what)
            assertNull(crossed.proposal, what)
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER.name, crossed.reason, what)
            assertEquals("ChannelRejection", crossed.reasonVocabulary, what)
            assertEquals(ChannelVocabulary.FEE, crossed.tag, what)
        }
    }

    @Test
    @JsName("the_provider_or_a_third_key_as_fee_recipient_still_decodes")
    fun `the provider or a third key as fee recipient still decodes`() {
        for (recipient in listOf(SettlementFixtures.provider(INDEX), SettlementFixtures.feeRecipient(INDEX))) {
            val decoded = OrderProposal.decode(namingAsFeeRecipient(recipient))
            assertEquals(recipient, decoded.feeRecipient)
            assertTrue(decodeProposal(JsRumor(namingAsFeeRecipient(recipient))).ok)
        }
    }

    @Test
    @JsName("every_generated_proposal_still_builds_and_decodes")
    fun `every generated proposal still builds and decodes`() {
        assertTrue(PROPOSALS.isNotEmpty())
        for (case in PROPOSALS) {
            val decoded = OrderProposal.decode(RumorWriterFixtures.bound(RumorWriterFixtures.build(case)))
            assertEquals(case.feeRecipient, decoded.feeRecipient, "$case")
        }
    }

    @Test
    @JsName("the_writer_with_recipient_equal_to_author_builds_nothing_in_either_case")
    fun `the writer with recipient equal to author builds nothing, in either case`() {
        val feeBearing = PROPOSALS.filter { it.feeRecipient != null }
        assertTrue(feeBearing.isNotEmpty(), "a generated proposal names a fee recipient")
        for (case in feeBearing) {
            val author = case.envelope.authorPubkey
            for (recipient in listOf(author.lowercase(), author.uppercase())) {
                val refused = RumorWriterFixtures.refused(withFeeRecipient(case, recipient), "$case")
                assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER, refused.reason, "$case")
                assertEquals(ChannelVocabulary.FEE, refused.tag, "$case")
            }
            // §8.1's MAY: the provider — the envelope's counterparty — is a legal recipient.
            val provider = case.envelope.counterparties.first().pubkey
            val built = RumorWriterFixtures.built(withFeeRecipient(case, provider), "$case to the provider")
            assertEquals(provider, OrderProposal.decode(RumorWriterFixtures.bound(built)).feeRecipient, "$case")
        }
    }

    @Test
    @JsName("ListingOrderWriter_proposal_refuses_the_buyer_as_fee_recipient_through_RumorWriter")
    fun `ListingOrderWriter proposal refuses the buyer as fee recipient through RumorWriter`() {
        val kind = ListingFixtures.KINDS[0]
        val listing = ListingFixtures.decode(ListingFixtures.minimal(kind), kind, index = 1)
        val case = PROPOSALS.first { it.feeRecipient != null }
        val answer = ListingOrderWriter.proposal(
            listing,
            NenyaClock.FAIL_CLOSED,
            case.envelope,
            case.order ?: fail("$case must carry an order id"),
            case.terms ?: fail("$case must carry terms"),
            case.envelope.authorPubkey,
            case.amountSat,
        )
        val refused = assertIs<ListingOrderBuild.Refused>(answer)
        assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER, refused.refusal.reason)
        assertEquals(ChannelVocabulary.FEE, refused.refusal.tag)
    }

    @Test
    @JsName("buildProposal_with_the_buyer_as_fee_recipient_is_refused_as_a_value")
    fun `buildProposal with the buyer as fee recipient is refused as a value`() {
        val buyer = SettlementFixtures.buyer(INDEX)
        val provider = SettlementFixtures.provider(INDEX)
        val terms = JsOrderTerms(
            priceMsat = OrderFixtures.TERMS.split.price.millisatoshis.toString(),
            feeBasisPoints = OrderFixtures.FEE_BASIS_POINTS,
        )
        fun build(recipient: String) = buildProposal(
            JsRumorEnvelope(buyer, ProposalFixtures.CREATED_AT.toString(), arrayOf(arrayOf(provider))),
            SettlementFixtures.orderHex(INDEX),
            ProposalFixtures.coordinate(INDEX),
            terms,
            feeRecipient = recipient,
        )
        for (recipient in listOf(buyer, buyer.uppercase())) {
            val refused = build(recipient)
            assertFalse(refused.ok)
            assertNull(refused.event)
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER.name, refused.reason)
            assertEquals("ChannelRejection", refused.reasonVocabulary)
            assertEquals(ChannelVocabulary.FEE, refused.tag)
        }
        assertTrue(build(provider).ok, "§8.1's MAY: the provider as fee recipient builds")
        assertTrue(build(SettlementFixtures.feeRecipient(INDEX)).ok, "a third key builds")
    }

    @Test
    @JsName("proposalForBid_with_a_bid_naming_the_buyer_as_fee_recipient_is_refused_on_either_side")
    fun `proposalForBid with a bid naming the buyer as fee recipient is refused, on either side`() {
        for (kind in SIDES) {
            val listing = bidListing(kind)
            // On a request, the provider's bid names the request's author; on an offer, the
            // counter-offer bid names its own bidder. Either way the recipient is the buyer.
            val bid = bidNaming(listing, buyerOn(listing))
            assertEquals(buyerOn(listing), bid.feeRecipient, "kind $kind: the bid names the buyer")
            val refused = assertIs<ListingOrderBuild.Refused>(proposalFor(listing, bid), "kind $kind")
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER, refused.refusal.reason, "kind $kind")
            assertEquals(ChannelVocabulary.FEE, refused.refusal.tag, "kind $kind")
        }
    }

    @Test
    @JsName("buildProposalForBid_with_a_bid_naming_the_buyer_as_fee_recipient_is_refused_as_a_value")
    fun `buildProposalForBid with a bid naming the buyer as fee recipient is refused as a value`() {
        for (kind in SIDES) {
            val listing = bidListing(kind)
            val refused = crossedFor(listing, bidNaming(listing, buyerOn(listing)))
            assertFalse(refused.ok, "kind $kind")
            assertNull(refused.event, "kind $kind")
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER.name, refused.reason, "kind $kind")
            assertEquals("ChannelRejection", refused.reasonVocabulary, "kind $kind")
            assertEquals(ChannelVocabulary.FEE, refused.tag, "kind $kind")
        }
    }

    @Test
    @JsName("the_same_bids_naming_a_third_key_build_and_decode_with_that_recipient")
    fun `the same bids naming a third key build and decode with that recipient`() {
        for (kind in SIDES) {
            val listing = bidListing(kind)
            val bid = bidNaming(listing, THIRD_KEY)
            val built = assertIs<ListingOrderBuild.Built>(proposalFor(listing, bid), "kind $kind")
            val decoded = OrderProposal.decode(RumorWriterFixtures.bound(built.event))
            assertEquals(THIRD_KEY, decoded.feeRecipient, "kind $kind")
            assertEquals(buyerOn(listing), decoded.buyer, "kind $kind: the buyer sent it")
            assertTrue(crossedFor(listing, bid).ok, "kind $kind: the crossed build")
        }
    }
}
