package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.wire.EventId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §6's own required set, §6's scoping rules, and the three controls in the **accepting** direction
 * that matter more than any of them.
 *
 * An over-strict bid codec refuses conformant peers and looks correct doing it: every refusal it
 * emits is a peer it cannot trade with, and no test of the refusing direction can tell the
 * difference. So §6's worked example parses here, a bid with no `expiration` parses, and a bid whose
 * prose contradicts its tags parses — each for a reason §6 states in so many words.
 */
class BidCodecTest {

    private companion object {

        /** The three listing kinds a bid may scope to (§6, §5.1). */
        val LISTING_KINDS: List<Int> = BidFixtures.LISTING_KINDS
    }

    @JsName("the_required_set_is_s6_s_own_and_is_not_s5_3_s_listing_set")
    @Test
    fun `the required set is §6's own and is not §5_3's listing set`() {
        val required = Bid.requiredTags()

        assertTrue(required.isNotEmpty(), "the per-tag removal loop cannot iterate over nothing")
        assertEquals(
            setOf("A", "a", "K", "k", "P", "p", "price", "t", "nenya"),
            required.toSet(),
            "§6: `A`/`a`, `K`/`k` and `P`/`p` all present, plus `price`, `t=nenya` and `nenya`",
        )
        // §5.3 says its Card. and Requirement columns "MUST NOT be applied to a bid". `d`, `title`
        // and `alt` are MUST on a listing and this set must not carry any of them, or the codec is
        // deriving a bid's rules from the listing table — the mistake §5.3 names itself.
        for (listingOnly in listOf("d", "title", "alt", "summary", "status")) {
            assertTrue(
                listingOnly !in required,
                "§5.3's listing rules MUST NOT reach a bid, and `$listingOnly` is one of them",
            )
        }
    }

    @JsName("removing_each_required_tag_in_turn_is_refused_naming_that_tag")
    @Test
    fun `removing each REQUIRED tag in turn is refused naming that tag`() {
        for (kind in LISTING_KINDS) {
            val whole = BidFixtures.minimalBid(kind)
            BidFixtures.decode(whole) // the paired positive control

            for (name in Bid.requiredTags()) {
                val without = BidFixtures.without(whole, name)
                val refused = assertFailsWith<BidException>("kind:$kind without `$name`") {
                    BidFixtures.decode(without)
                }
                assertEquals(
                    BidRejection.MISSING_REQUIRED_TAG,
                    refused.reason,
                    "kind:$kind, `$name`",
                )
                assertEquals(
                    name,
                    refused.tag,
                    "the refusal must name the tag that is missing, as data rather than as wording",
                )
            }
        }
    }

    /**
     * §6's own worked example, which §5.3 names as the event a codec applying the listing rules
     * everywhere would reject: "Applying the listing rules everywhere rejects this document's own
     * bid example, which legitimately carries a single `["t", "nenya"]` and no `wtb`/`wts`."
     */
    @JsName("a_single_t_nenya_with_no_wtb_or_wts_parses_because_s5_3_s_cardinality_is_a_listing_rule")
    @Test
    fun `a single t nenya with no wtb or wts parses, because §5_3's cardinality is a listing rule`() {
        val tags = BidFixtures.minimalBid()
        assertEquals(1, tags.count { it[0] == "t" }, "the fixture must be the shape §5.3 warns about")

        val bid = BidFixtures.decode(tags)

        assertEquals(listOf("nenya"), bid.topics)
        assertEquals(Msat.ofSat(90_000L), bid.price)
        assertEquals(1, bid.nenyaVersion)
    }

    /**
     * §6 makes `expiration` a SHOULD: "A bid SHOULD carry `expiration`. A bid with no expiration is
     * an open-ended commitment and implementations SHOULD render it as such."
     *
     * The SHOULD/MUST distinction, and the mutation a codec written from memory rather than from §6
     * actually contains. An implementation that made it REQUIRED would refuse every open-ended bid
     * on the board.
     */
    @JsName("a_bid_with_no_expiration_parses_and_is_flagged_open_ended_rather_than_refused")
    @Test
    fun `a bid with no expiration parses and is flagged open-ended rather than refused`() {
        val open = BidFixtures.decode(BidFixtures.minimalBid())
        assertNull(open.expiration)
        assertTrue(open.openEnded, "§6 asks implementations to render an open-ended bid as such")

        val bounded = BidFixtures.decode(
            BidFixtures.with(BidFixtures.minimalBid(), listOf("expiration", "1757260000")),
        )
        assertEquals(1_757_260_000L, bounded.expiration)
        assertTrue(!bounded.openEnded)
    }

    /**
     * §6: "Per §5.4 that prose carries **no** machine meaning — an implementation MUST derive every
     * term from the tags — so a bid whose prose and tags disagree is a display defect, never a terms
     * dispute."
     */
    @JsName("a_bid_whose_content_contradicts_its_tags_parses_with_every_term_taken_from_the_tags")
    @Test
    fun `a bid whose content contradicts its tags parses, with every term taken from the tags`() {
        val tags = BidFixtures.with(
            BidFixtures.minimalBid(),
            listOf("deliver_by", "1757066034"),
        )
        val prose = "I can do this for 5 sats, delivered in 2019, no fee, and I also withdraw it."

        val bid = BidFixtures.decode(tags, prose)

        assertEquals(Msat.ofSat(90_000L), bid.price, "§6: every term comes from the tags")
        assertEquals(1_757_066_034L, bid.deliverBy)
        assertEquals(FeeTerm.Absent, bid.fee)
        assertEquals(prose, bid.content, "the prose is carried verbatim and never parsed")
    }

    /**
     * §8.1 forbids refusing an in-range `fee` on read, and says a client's own lower limit is a
     * separate, named local-policy refusal. T9 enforces it at the tag layer; this is the rule
     * reached through a second caller, which is where a policy check tends to be quietly added.
     */
    @JsName("a_fee_of_9000_bps_parses_on_a_bid_and_10001_does_not")
    @Test
    fun `a fee of 9000 bps parses on a bid and 10001 does not`() {
        val recipient = TagFixtures.pubkeyFor(9)
        val high = BidFixtures.decode(
            BidFixtures.with(BidFixtures.minimalBid(), listOf("fee", "9000", recipient)),
        )
        assertEquals(FeeTerm.of(9000), high.fee)
        assertEquals(recipient, high.feeRecipient)

        val refused = assertFailsWith<BidException> {
            BidFixtures.decode(
                BidFixtures.with(BidFixtures.minimalBid(), listOf("fee", "10001", recipient)),
            )
        }
        assertEquals(BidRejection.MALFORMED_TAG, refused.reason)
        assertEquals(TagRejection.BPS_ABOVE_MAXIMUM, (refused.cause as TagException).reason)
    }

    /**
     * §6: "A bid MUST scope to a coordinate (`A`/`a`), never to an event id (`E`/`e`). A bid scoped
     * by event id detaches the moment the listing is edited."
     *
     * Refused as *scoped by event id* and **not** as a missing `a` tag — the conflation an
     * implementer makes, and the one that loses §6's stated reason on the way to the user.
     */
    @JsName("a_bid_scoped_by_event_id_is_refused_as_scoped_by_event_id_and_not_as_a_missing_a_tag")
    @Test
    fun `a bid scoped by event id is refused as scoped by event id and not as a missing a tag`() {
        val eventId = EventId.of(BidFixtures.checked(BidFixtures.minimalBid()).event).toHex()

        for (name in listOf("E", "e")) {
            val scopedByIdInstead = BidFixtures.without(
                BidFixtures.without(BidFixtures.minimalBid(), "A"),
                "a",
            ) + listOf(listOf(name, eventId))

            val refused = assertFailsWith<BidException>(name) {
                BidFixtures.decode(scopedByIdInstead)
            }
            assertEquals(
                BidRejection.SCOPED_BY_EVENT_ID,
                refused.reason,
                "§6 gives the reason itself; reporting this as a missing `a` tag hides that the " +
                    "peer used the referencing scheme §4.2 exists to replace",
            )
            assertEquals(name, refused.tag)
            assertNotEquals(BidRejection.MISSING_REQUIRED_TAG, refused.reason)
        }
    }

    /**
     * A NIP-22 **reply to a bid** carries the bid's event id as its parent, so it lands on the same
     * rule — with §6's reason rather than as a shrug about a tag nobody recognised. It is a
     * legitimate NIP-22 event and simply not a Nenya bid; see [Bid]'s scope statement.
     */
    @JsName("a_reply_whose_parent_is_an_event_id_is_refused_with_s6_s_reason_even_though_a_and_a_are_present")
    @Test
    fun `a reply whose parent is an event id is refused with §6's reason even though A and a are present`() {
        val parent = EventId.of(BidFixtures.checked(BidFixtures.minimalBid()).event).toHex()
        val reply = BidFixtures.with(BidFixtures.minimalBid(), listOf("e", parent))

        val refused = assertFailsWith<BidException> { BidFixtures.decode(reply) }

        assertEquals(BidRejection.SCOPED_BY_EVENT_ID, refused.reason)
        assertEquals("e", refused.tag)
    }

    /** §6: "`K` and `k` MUST both be present and MUST both equal the listing's kind as a decimal string." */
    @JsName("k_or_k_disagreeing_with_the_coordinate_s_kind_is_refused")
    @Test
    fun `K or k disagreeing with the coordinate's kind is refused`() {
        for (name in listOf("K", "k")) {
            val wrong = BidFixtures.replacing(
                BidFixtures.minimalBid(NenyaKind.REQUEST),
                name,
                listOf(name, NenyaKind.OFFER.toString()),
            )
            val refused = assertFailsWith<BidException>(name) { BidFixtures.decode(wrong) }
            assertEquals(BidRejection.LISTING_KIND_MISMATCH, refused.reason)
            assertEquals(name, refused.tag)
        }

        // "as a decimal string" is byte-exact: a coordinate is a relay address key compared
        // byte-for-byte, and a padded spelling of the same number is a second address for one event.
        val padded = BidFixtures.replacing(
            BidFixtures.minimalBid(NenyaKind.REQUEST),
            "k",
            listOf("k", "0" + NenyaKind.REQUEST.toString()),
        )
        assertEquals(
            BidRejection.LISTING_KIND_MISMATCH,
            assertFailsWith<BidException> { BidFixtures.decode(padded) }.reason,
        )
    }

    /** §6: "`P` and `p` MUST both be present and MUST both equal the listing author's pubkey." */
    @JsName("p_or_p_disagreeing_with_the_coordinate_s_pubkey_is_refused")
    @Test
    fun `P or p disagreeing with the coordinate's pubkey is refused`() {
        val somebodyElse = TagFixtures.pubkeyFor(42)

        for (name in listOf("P", "p")) {
            val wrong = BidFixtures.replacing(
                BidFixtures.minimalBid(),
                name,
                listOf(name, somebodyElse),
            )
            val refused = assertFailsWith<BidException>(name) { BidFixtures.decode(wrong) }
            assertEquals(BidRejection.LISTING_AUTHOR_MISMATCH, refused.reason)
            assertEquals(name, refused.tag)
        }
    }

    /** §6: both the uppercase and the lowercase scope tag "MUST be present". */
    @JsName("a_present_with_a_absent_is_refused_naming_the_absent_one")
    @Test
    fun `A present with a absent is refused naming the absent one`() {
        val refused = assertFailsWith<BidException> {
            BidFixtures.decode(BidFixtures.without(BidFixtures.minimalBid(), "a"))
        }
        assertEquals(BidRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals("a", refused.tag)

        val other = assertFailsWith<BidException> {
            BidFixtures.decode(BidFixtures.without(BidFixtures.minimalBid(), "A"))
        }
        assertEquals(BidRejection.MISSING_REQUIRED_TAG, other.reason)
        assertEquals("A", other.tag)
    }

    /**
     * §6: "A top-level bid has the listing as both root and parent, so the uppercase and lowercase
     * tags carry the same values." Two different coordinates is a NIP-22 reply shape, which this
     * codec states it does not read rather than guessing which half the bidder meant.
     */
    @JsName("a_and_a_naming_different_coordinates_on_a_top_level_bid_is_refused")
    @Test
    fun `A and a naming different coordinates on a top-level bid is refused`() {
        val elsewhere = "${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(43)}:some-other-listing"
        val split = BidFixtures.replacing(BidFixtures.minimalBid(), "a", listOf("a", elsewhere))

        val refused = assertFailsWith<BidException> { BidFixtures.decode(split) }

        assertEquals(BidRejection.SCOPE_DISAGREEMENT, refused.reason)
        assertEquals("a", refused.tag)
    }

    /**
     * §5.3's `item` row: cardinality `0` on a public bid, "where `A`/`a` already carry the binding
     * and a second binding that could disagree with the first is precisely what §4.2 exists to
     * prevent". Refused for that reason and not as a tag nobody recognised.
     */
    @JsName("an_item_tag_on_a_bid_is_refused_as_a_double_binding")
    @Test
    fun `an item tag on a bid is refused as a double binding`() {
        val tags = BidFixtures.with(
            BidFixtures.minimalBid(),
            listOf("item", BidFixtures.coordinateOf(), "1"),
        )

        val refused = assertFailsWith<BidException> { BidFixtures.decode(tags) }

        assertEquals(BidRejection.DOUBLE_BINDING, refused.reason)
        assertEquals("item", refused.tag)
        assertEquals(TagRejection.FORBIDDEN_TAG, (refused.cause as TagException).reason)
    }

    /**
     * §6: "Bids are made **against requests** in the normal flow, and MAY also be made against
     * offers as a counter-offer... An implementation MUST support parsing both."
     */
    @JsName("a_bid_parses_against_a_request_and_against_an_offer_alike")
    @Test
    fun `a bid parses against a request and against an offer alike`() {
        for (kind in LISTING_KINDS) {
            val bid = BidFixtures.decode(BidFixtures.minimalBid(kind))
            assertEquals(kind, bid.listingKind, "kind:$kind")
            assertEquals(BidFixtures.listingAuthor(), bid.listingAuthorPubkey)
            assertEquals(BidFixtures.coordinateOf(kind), bid.listing.toTagValue())
        }
    }

    /** §6 scopes a bid to a listing; a coordinate naming any other kind is not a shape §6 defines. */
    @JsName("a_coordinate_naming_a_non_listing_kind_is_refused")
    @Test
    fun `a coordinate naming a non-listing kind is refused`() {
        val notAListing = "${NenyaKind.PUBLIC_BID}:${BidFixtures.listingAuthor()}:whatever"
        val tags = BidFixtures.minimalBid()
            .map { if (it[0] == "A" || it[0] == "a") listOf(it[0], notAListing) else it }
            .map { if (it[0] == "K" || it[0] == "k") listOf(it[0], NenyaKind.PUBLIC_BID.toString()) else it }

        val refused = assertFailsWith<BidException> { BidFixtures.decode(tags) }

        assertEquals(BidRejection.NOT_A_LISTING_COORDINATE, refused.reason)
    }

    /**
     * §4.3's rationale, reaching NIP-22's rows: "First wins" and "last wins" are both defensible,
     * which is exactly the problem. Asserted for **both** orderings, so neither resolution passes.
     */
    @JsName("a_duplicated_scope_tag_is_refused_for_both_orderings")
    @Test
    fun `a duplicated scope tag is refused for both orderings`() {
        val elsewhere = "${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(44)}:another-listing"
        val mine = BidFixtures.coordinateOf()

        for (pair in listOf(listOf(mine, elsewhere), listOf(elsewhere, mine))) {
            val tags = BidFixtures.without(BidFixtures.minimalBid(), "a") +
                pair.map { listOf("a", it) }
            val refused = assertFailsWith<BidException>(pair.toString()) { BidFixtures.decode(tags) }
            assertEquals(BidRejection.DUPLICATE_TAG, refused.reason)
            assertEquals("a", refused.tag)
        }
    }

    /** §4.3's duplicate rule as §5.3 states it, reaching a bid through the tag layer. */
    @JsName("a_duplicated_price_is_refused_for_both_orderings_and_never_resolved")
    @Test
    fun `a duplicated price is refused for both orderings and never resolved`() {
        for (pair in listOf(listOf("90000", "5"), listOf("5", "90000"))) {
            val tags = BidFixtures.without(BidFixtures.minimalBid(), "price") +
                pair.map { listOf("price", it, "SAT") }
            val refused = assertFailsWith<BidException>(pair.toString()) { BidFixtures.decode(tags) }
            assertEquals(BidRejection.DUPLICATE_TAG, refused.reason)
            assertEquals("price", refused.tag)
        }
    }

    /**
     * §6 requires `t=nenya` specifically, and §5.3's further "exactly one of `wtb`/`wts`" rule is a
     * listing rule that MUST NOT be applied here. A `t` tag carrying something else is therefore a
     * *missing topic* and not a missing tag.
     */
    @JsName("a_t_tag_that_is_not_nenya_is_refused_as_a_missing_topic")
    @Test
    fun `a t tag that is not nenya is refused as a missing topic`() {
        val tags = BidFixtures.replacing(BidFixtures.minimalBid(), "t", listOf("t", "ai-video"))

        val refused = assertFailsWith<BidException> { BidFixtures.decode(tags) }

        assertEquals(BidRejection.MISSING_TOPIC, refused.reason)
        assertEquals("t", refused.tag)
    }

    /** §5.3: `t` tokens SHOULD be matched case-insensitively on read, because tag indexes are byte-exact. */
    @JsName("an_uppercase_t_token_is_still_the_nenya_discovery_token_on_read")
    @Test
    fun `an uppercase t token is still the nenya discovery token on read`() {
        val tags = BidFixtures.replacing(BidFixtures.minimalBid(), "t", listOf("t", "NENYA"))

        val bid = BidFixtures.decode(tags)

        assertEquals(listOf("NENYA"), bid.topics, "§4.3 forbids re-writing the author's bytes")
        assertEquals(listOf("nenya"), bid.tags.normalisedTopics)
    }

    /**
     * §4.3's accept-and-normalise rule, with the fixture taken from the vendored, externally
     * authored BIP-340 vector file — which is uppercase in the file, so the fixture and the control
     * are the same value and nobody typed it.
     */
    @JsName("an_uppercase_pubkey_in_the_coordinate_and_in_p_and_p_is_accepted_and_normalised")
    @Test
    fun `an uppercase pubkey in the coordinate and in P and p is accepted and normalised`() {
        val uppercase = TagFixtures.uppercasePubkeys.first()
        val coordinate = "${NenyaKind.REQUEST}:$uppercase:lighthouse-loop-2026-09"
        val tags = listOf(
            listOf("A", coordinate),
            listOf("a", coordinate),
            listOf("K", NenyaKind.REQUEST.toString()),
            listOf("k", NenyaKind.REQUEST.toString()),
            listOf("P", uppercase),
            // Deliberately the *other* case in the lowercase tag: §6 asks for equality of the key,
            // not of its spelling, and §4.3's exception list names neither a pubkey nor a coordinate.
            listOf("p", uppercase.lowercase()),
            listOf("price", "90000", "SAT"),
            listOf("t", "nenya"),
            listOf("nenya", "1"),
        )

        val bid = BidFixtures.decode(tags)

        assertEquals(uppercase.lowercase(), bid.listingAuthorPubkey)
        assertEquals(uppercase.lowercase(), bid.listing.pubkey)
    }

    /** §4.3: a value of the wrong length is rejected rather than padded or truncated. */
    @JsName("a_p_tag_of_63_hex_characters_is_refused_as_the_wrong_length")
    @Test
    fun `a P tag of 63 hex characters is refused as the wrong length`() {
        val short = BidFixtures.listingAuthor().dropLast(1)
        val tags = BidFixtures.replacing(BidFixtures.minimalBid(), "P", listOf("P", short))

        val refused = assertFailsWith<BidException> { BidFixtures.decode(tags) }

        assertEquals(BidRejection.MALFORMED_TAG, refused.reason)
        assertEquals(TagRejection.WRONG_LENGTH, (refused.cause as TagException).reason)
    }

    /** §5.3 makes `d` opaque, so a colon inside one is legal — and §4.2's split has a limit for it. */
    @JsName("a_coordinate_whose_d_value_contains_a_colon_round_trips_intact")
    @Test
    fun `a coordinate whose d value contains a colon round-trips intact`() {
        val dValue = "series:2026:09:lighthouse"
        val tags = BidFixtures.minimalBid(dValue = dValue)

        val bid = BidFixtures.decode(tags)

        assertEquals(dValue, bid.listing.dValue)
        assertEquals(BidFixtures.coordinateOf(dValue = dValue), bid.listing.toTagValue())
    }

    /** §6's `fee` is optional, and §8.1's `Absent` is not the same value as a stated zero. */
    @JsName("an_absent_fee_is_absent_and_a_stated_zero_is_a_stated_zero")
    @Test
    fun `an absent fee is Absent and a stated zero is a stated zero`() {
        assertEquals(FeeTerm.Absent, BidFixtures.decode(BidFixtures.minimalBid()).fee)
        assertEquals(
            FeeTerm.of(0),
            BidFixtures.decode(BidFixtures.with(BidFixtures.minimalBid(), listOf("fee", "0"))).fee,
        )
    }

    /** §4.3's fifth resource bound, injected because §4.3 says the bounds SHOULD be configurable. */
    @JsName("more_image_tags_than_the_injected_bound_is_refused_as_limit_exceeded_never_truncated")
    @Test
    fun `more image tags than the injected bound is refused as limit exceeded, never truncated`() {
        val images = List(3) { listOf("image", "https://example.invalid/$it.png") }
        val tags = BidFixtures.minimalBid() + images

        Bid.decode(BidFixtures.checked(tags), TagLimits(maxImageTags = 3))

        val refused = assertFailsWith<BidException> {
            Bid.decode(BidFixtures.checked(tags), TagLimits(maxImageTags = 2))
        }
        assertEquals(BidRejection.LIMIT_EXCEEDED, refused.reason)
        assertEquals("image", refused.tag)
    }

    /** §4.4's recurring NIP-99 form: **unsupported** on read, which is not the same as malformed. */
    @JsName("a_recurring_nip_99_price_is_refused_as_unsupported_and_not_as_malformed")
    @Test
    fun `a recurring NIP-99 price is refused as unsupported and not as malformed`() {
        val tags = BidFixtures.replacing(
            BidFixtures.minimalBid(),
            "price",
            listOf("price", "50000", "SAT", "monthly"),
        )

        val refused = assertFailsWith<BidException> { BidFixtures.decode(tags) }

        assertEquals(BidRejection.UNSUPPORTED, refused.reason)
        assertEquals("price", refused.tag)
    }

    /** §6's required set MUST NOT be applied to a listing or to an order rumor, so the kind is checked. */
    @JsName("an_event_that_is_not_a_kind_1111_is_refused_as_not_a_bid_kind")
    @Test
    fun `an event that is not a kind 1111 is refused as not a bid kind`() {
        val refused = assertFailsWith<BidException> {
            Bid.decode(BidFixtures.checkedOnKind(BidFixtures.minimalBid(), NenyaKind.REQUEST))
        }

        assertEquals(BidRejection.NOT_A_BID_KIND, refused.reason)
        assertNull(refused.tag)
    }

    /** §4.3's round-trip guarantee on the one fixture §6 prints, before the corpus proves it at scale. */
    @JsName("decode_then_encode_reproduces_the_identical_event_id")
    @Test
    fun `decode then encode reproduces the identical event id`() {
        val tags = BidFixtures.with(BidFixtures.minimalBid(), listOf("x-unknown", "kept", "verbatim"))
        val checked = BidFixtures.checked(tags, "I can do this for 90k sats.")

        val bid = Bid.decode(checked)

        assertEquals(tags, bid.encode().tags)
        assertEquals(checked.id, EventId.of(bid.encode()))
        assertEquals(listOf(listOf("x-unknown", "kept", "verbatim")), bid.unknownTags.filter { it[0] == "x-unknown" })
    }
}
