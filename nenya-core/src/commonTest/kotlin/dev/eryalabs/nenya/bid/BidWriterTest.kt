package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.SpecAnchor
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.TagEncoding
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §6's write half: the emission order and the required set held to the document, and one negative
 * control per rule the writer enforces.
 *
 * The generated round trip is in `BidWriterPropertyTest`, and it would pass just as happily for a
 * writer emitting §6's tags in alphabetical order. So the assertions that matter most here are the
 * ones against `spec/NENYA-1.md` parsed at test time by [Section6]:
 *
 * - "the emission order is the order section 6's worked bid prints"
 * - "the required set is the set section 6's MUST bullets name", with its mutated-prose control.
 *
 * Every refusal is asserted by [BidBuild.Refused.reason] and [BidBuild.Refused.tag] — data, never
 * the wording of [BidBuild.Refused.detail]. Where the refusal is of a tag the writer was handed as an
 * extension, the same tags are also fed straight to `Bid.decode`, which must refuse them naming the
 * same tag: a writer refusing something its decoder accepts would be a defect too.
 */
class BidWriterTest {

    private companion object {

        /** A legal bid on a request — the paired positive control. */
        fun legal(): AuthoredBid = BidWriterFixtures.minimal()

        /** [legal] with [extra] as its one extension tag. */
        fun withExtra(extra: List<String>): AuthoredBid {
            val base = legal()
            return AuthoredBid(base.bidderPubkey, base.createdAt, base.listing, base.price, extraTags = listOf(extra))
        }

        /** The legal bid's own tags plus [extra], straight to the decoder. */
        fun decoderAnswer(extra: List<String>): BidException {
            val event = BidWriterFixtures.built(BidWriter.bid(legal()), "the legal bid")
            val spoiled = WireEvent(event.pubkey, event.createdAt, event.kind, event.tags + listOf(extra), event.content)
            return assertFailsWith<BidException>("Bid.decode must refuse what the writer refused") {
                BidWriterFixtures.decode(spoiled)
            }
        }

        /** The specification's lines with [from] replaced by [to], asserted to touch exactly one line. */
        fun mutatedSpec(from: String, to: String): Section6 {
            val lines = SpecAnchor.specFile().readLines()
            val mutated = lines.map { it.replace(from, to) }
            val changed = lines.indices.count { lines[it] != mutated[it] }
            assertEquals(1, changed, "the mutation `$from` must change exactly one line of the specification")
            return Section6(mutated, "a mutated copy of ${SpecAnchor.specFile().location}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Against the document.
    // ---------------------------------------------------------------------------------------------

    /**
     * §4.1 hashes the tags in order, so the emission order had to be chosen. For every tag §6's worked
     * bid prints, it is the order that example prints — its first entries, exactly.
     */
    @JsName("the_emission_order_is_the_order_section_6s_worked_bid_prints")
    @Test
    fun `the emission order is the order section 6's worked bid prints`() {
        val printed = Section6.fromSpec().exampleTagNames()
        assertEquals(printed.size, printed.toSet().size, "§6's example prints each tag once")
        assertEquals(
            printed,
            BidWriter.TAG_ORDER.take(printed.size),
            "BidWriter.TAG_ORDER must open with the tag names §6's worked bid prints, in its order",
        )
    }

    /**
     * After §6's tags come every other §5.3 row a bid may carry, in §5.3's table order — derived from
     * [NenyaTags], which `TagVocabularyTest` holds equal to the table parsed out of the document. The
     * rows a bid may not carry are `item` (cardinality `0` on a public bid) and the rows with no
     * encoding, `g` and `location`, which §5.3 says MUST NOT be emitted.
     */
    @JsName("the_rows_section_6_does_not_print_follow_in_s5_3_table_order")
    @Test
    fun `the rows section 6 does not print follow, in §5_3's table order`() {
        val printed = Section6.fromSpec().exampleTagNames()
        val notOnABid = NenyaTags.ALL
            .filter { it.encoding == TagEncoding.NONE || it.encoding == TagEncoding.ITEM }
            .map { it.name }
        assertEquals(listOf("item", "g", "location"), notOnABid, "§5.3's three rows a bid may not carry")

        val tail = NenyaTags.ALL.map { it.name }.filter { it !in printed && it !in notOnABid }
        assertEquals(tail, BidWriter.TAG_ORDER.drop(printed.size), "the tail is §5.3's table order")
        assertEquals(BidWriter.TAG_ORDER.size, BidWriter.TAG_ORDER.toSet().size, "no tag emitted twice")
        assertTrue(
            NenyaTags.NAMES.all { it in BidWriter.TAG_ORDER || it in notOnABid },
            "every §5.3 row is emittable on a bid or one of the three it may not carry",
        )
    }

    /** `BidVocabulary.REQUIRED`, which the decoder and this writer's audit both walk, is §6's set. */
    @JsName("the_required_set_is_the_set_section_6s_must_bullets_name")
    @Test
    fun `the required set is the set section 6's MUST bullets name`() {
        val section6 = Section6.fromSpec()
        val named = section6.requiredTagNames()
        assertEquals(named.size, named.toSet().size)
        assertEquals(named.toSet(), BidVocabulary.REQUIRED.toSet(), "§6's MUST bullets and BidVocabulary.REQUIRED")
        assertEquals(BidVocabulary.REQUIRED.size, BidVocabulary.REQUIRED.toSet().size)
        assertTrue(
            named.all { it in section6.exampleTagNames() },
            "§6's own worked bid carries every tag its rules require",
        )
    }

    /**
     * The negative control the parser owes: §6 with `price` dropped from "A bid MUST carry `price`,
     * `t=nenya` and `nenya`" no longer names `price`, so the equality above turns red.
     */
    @JsName("section_6_fed_its_rules_with_price_dropped_turns_the_required_set_red")
    @Test
    fun `section 6 fed its rules with price dropped turns the required set red`() {
        val mutated = mutatedSpec("A bid MUST carry `price`, `t=nenya`", "A bid MUST carry `t=nenya`")
        val named = mutated.requiredTagNames().toSet()
        assertTrue("price" !in named, "the mutated rules still name `price`: $named")
        assertNotEquals(BidVocabulary.REQUIRED.toSet(), named, "dropping `price` must turn the equality red")
        assertEquals(BidVocabulary.REQUIRED.toSet() - "price", named, "and change nothing else")
    }

    /** The same for the order: §6's example with its `K` line renamed no longer opens TAG_ORDER. */
    @JsName("section_6_fed_its_example_with_a_tag_renamed_turns_the_emission_order_red")
    @Test
    fun `section 6 fed its example with a tag renamed turns the emission order red`() {
        val mutated = mutatedSpec("[\"K\", \"30404\"]", "[\"x-renamed\", \"30404\"]")
        val printed = mutated.exampleTagNames()
        assertEquals("x-renamed", printed[1], "the mutation reached the example")
        assertNotEquals(BidWriter.TAG_ORDER.take(printed.size), printed)
    }

    // ---------------------------------------------------------------------------------------------
    // What a built bid carries.
    // ---------------------------------------------------------------------------------------------

    /** The minimal bid carries exactly §6's required tags and nothing else, in emission order. */
    @JsName("the_minimal_bid_carries_exactly_the_tags_section_6_requires")
    @Test
    fun `the minimal bid carries exactly the tags section 6 requires`() {
        val event = BidWriterFixtures.built(BidWriter.bid(legal()), "the minimal bid")
        assertEquals(NenyaKind.PUBLIC_BID, event.kind)
        assertEquals(BidWriter.TAG_ORDER.filter { it in Bid.requiredTags() }, event.tags.map { it[0] })
        val decoded = BidWriterFixtures.decode(event)
        assertTrue(decoded.openEnded, "§6: no `expiration` is an open-ended commitment, not a refusal")
        assertEquals(legal().listing, decoded.listing)
        assertEquals(legal().price, decoded.price)
    }

    /** A bid may be a counter-offer on an offer (§6), and on a draft as `Bid.decode` permits. */
    @JsName("a_bid_on_each_listing_kind_is_built_and_decodes")
    @Test
    fun `a bid on each listing kind is built and decodes`() {
        for (kind in NenyaKind.LISTING_KINDS) {
            val event = BidWriterFixtures.built(BidWriter.bid(BidWriterFixtures.minimal(kind)), "a bid on kind:$kind")
            assertEquals(kind, BidWriterFixtures.decode(event).listingKind)
            assertEquals(listOf("K", kind.toString()), event.tags.single { it[0] == "K" })
        }
    }

    /** §5.3's side tokens are a listing rule: a bid carrying one is permitted and decodes. */
    @JsName("a_side_token_on_a_bid_is_permitted_because_the_side_rule_is_a_listing_rule")
    @Test
    fun `a side token on a bid is permitted, because the side rule is a listing rule`() {
        val base = legal()
        val bid = AuthoredBid(base.bidderPubkey, base.createdAt, base.listing, base.price, topics = listOf("Request"))
        val event = BidWriterFixtures.built(BidWriter.bid(bid), "a bid tagged request")
        assertEquals(listOf("nenya", "request"), BidWriterFixtures.decode(event).topics, "lowercased on write")
    }

    /** §8.1: an absent term and a stated zero are different events. */
    @JsName("a_stated_zero_fee_and_an_absent_fee_are_different_bids")
    @Test
    fun `a stated zero fee and an absent fee are different bids`() {
        val base = legal()
        val zero = AuthoredBid(base.bidderPubkey, base.createdAt, base.listing, base.price, fee = FeeTerm.of(0))
        val absent = BidWriterFixtures.built(BidWriter.bid(base), "absent")
        val stated = BidWriterFixtures.built(BidWriter.bid(zero), "stated zero")
        assertEquals(listOf("fee", "0"), stated.tags.single { it[0] == "fee" })
        assertTrue(absent.tags.none { it[0] == "fee" })
        assertNotEquals(absent.canonicalSerialisation(), stated.canonicalSerialisation())
    }

    // ---------------------------------------------------------------------------------------------
    // The negative controls the task names: `k`, `item` and `E`, each refused by name.
    // ---------------------------------------------------------------------------------------------

    /**
     * A `k` that disagrees with the coordinate's kind is §6's `LISTING_KIND_MISMATCH`, naming `k`; one
     * that agrees is still a second `k`, which `Bid.decode` refuses as a duplicate.
     */
    @JsName("a_k_disagreeing_with_the_coordinates_kind_is_refused_naming_k")
    @Test
    fun `a k disagreeing with the coordinate's kind is refused naming k`() {
        val disagreeing = listOf("k", NenyaKind.OFFER.toString())
        val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(disagreeing)), "a disagreeing `k`")
        assertEquals(BidRejection.LISTING_KIND_MISMATCH, refused.reason)
        assertEquals("k", refused.tag)
        assertEquals("k", decoderAnswer(disagreeing).tag, "the decoder names `k` too")

        val agreeing = listOf("k", NenyaKind.REQUEST.toString())
        val duplicate = BidWriterFixtures.refused(BidWriter.bid(withExtra(agreeing)), "an agreeing `k`")
        assertEquals(BidRejection.DUPLICATE_TAG, duplicate.reason)
        assertEquals("k", duplicate.tag)
        assertEquals(BidRejection.DUPLICATE_TAG, decoderAnswer(agreeing).reason)
    }

    /** §5.3 gives `item` cardinality `0` on a public bid — a second binding — refused naming `item`. */
    @JsName("an_item_tag_on_a_bid_is_refused_as_a_double_binding_naming_item")
    @Test
    fun `an item tag on a bid is refused as a double binding naming item`() {
        val item = listOf("item", legal().listing.toTagValue(), "1")
        val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(item)), "an `item` extension")
        assertEquals(BidRejection.DOUBLE_BINDING, refused.reason)
        assertEquals("item", refused.tag)
        val decoder = decoderAnswer(item)
        assertEquals(BidRejection.DOUBLE_BINDING, decoder.reason)
        assertEquals("item", decoder.tag)
    }

    /** §6: a bid MUST scope to a coordinate, never to an event id — `E` and `e` each refused by name. */
    @JsName("an_e_scope_on_a_bid_is_refused_as_scoped_by_event_id_naming_it")
    @Test
    fun `an E scope on a bid is refused as scoped by event id, naming it`() {
        val id = BidWriterFixtures.checked(BidWriterFixtures.built(BidWriter.bid(legal()), "the legal bid")).id.toHex()
        for (name in listOf("E", "e")) {
            val scope = listOf(name, id)
            val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(scope)), "an `$name` extension")
            assertEquals(BidRejection.SCOPED_BY_EVENT_ID, refused.reason)
            assertEquals(name, refused.tag)
            val decoder = decoderAnswer(scope)
            assertEquals(BidRejection.SCOPED_BY_EVENT_ID, decoder.reason)
            assertEquals(name, decoder.tag)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The rest of the refusals, each asserting its reason.
    // ---------------------------------------------------------------------------------------------

    /** The other scope tags handed in again: a disagreement named for its §6 rule, an agreement a duplicate. */
    @JsName("the_other_scope_tags_handed_in_again_are_refused_for_the_rule_they_break")
    @Test
    fun `the other scope tags handed in again are refused for the rule they break`() {
        val other = BidWriterFixtures.coordinate(NenyaKind.REQUEST, 40, "another-listing")
        val stranger = TagFixtures.pubkeyFor(41)
        val cases = listOf(
            listOf("A", other.toTagValue()) to BidRejection.SCOPE_DISAGREEMENT,
            listOf("a", "not a coordinate") to BidRejection.SCOPE_DISAGREEMENT,
            listOf("K", "30402") to BidRejection.LISTING_KIND_MISMATCH,
            listOf("P", stranger) to BidRejection.LISTING_AUTHOR_MISMATCH,
            listOf("p", stranger) to BidRejection.LISTING_AUTHOR_MISMATCH,
            listOf("P", legal().listing.pubkey.uppercase()) to BidRejection.DUPLICATE_TAG,
            listOf("a", legal().listing.toTagValue()) to BidRejection.DUPLICATE_TAG,
        )
        for ((extra, reason) in cases) {
            val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(extra)), "extra `${extra[0]}`")
            assertEquals(reason, refused.reason, "extra `${extra[0]}`")
            assertEquals(extra[0], refused.tag)
            decoderAnswer(extra)
        }
    }

    /** §5.3: an implementation MUST NOT emit `g` — nor `location`. */
    @JsName("g_and_location_are_refused_as_forbidden_to_emit")
    @Test
    fun `g and location are refused as forbidden to emit`() {
        for (name in listOf("g", "location")) {
            val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(listOf(name, "x"))), "`$name`")
            assertEquals(BidRejection.FORBIDDEN_TAG, refused.reason)
            assertEquals(name, refused.tag)
        }
    }

    /** A §5.3 row handed in as an extension: a duplicate when the writer emits it once already. */
    @JsName("a_s5_3_row_handed_in_as_an_extension_is_refused_naming_it")
    @Test
    fun `a §5_3 row handed in as an extension is refused naming it`() {
        val duplicate = BidWriterFixtures.refused(BidWriter.bid(withExtra(listOf("price", "1", "SAT"))), "price")
        assertEquals(BidRejection.DUPLICATE_TAG, duplicate.reason)
        assertEquals("price", duplicate.tag)
        for (name in listOf("title", "t", "image")) {
            val refused = BidWriterFixtures.refused(BidWriter.bid(withExtra(listOf(name, "x"))), "`$name`")
            assertEquals(BidRejection.ROW_IS_NOT_AN_EXTENSION, refused.reason, "`$name`")
            assertEquals(name, refused.tag)
        }
        val empty = BidWriterFixtures.refused(BidWriter.bid(withExtra(emptyList())), "an empty tag")
        assertEquals(BidRejection.MALFORMED_TAG, empty.reason)
    }

    /** A coordinate on something that is not a listing is no §6 bid. */
    @JsName("a_coordinate_on_a_non_listing_kind_is_refused")
    @Test
    fun `a coordinate on a non-listing kind is refused`() {
        val base = legal()
        val notAListing = BidWriterFixtures.coordinate(NenyaKind.PUBLIC_BID, 40, "x")
        val refused = BidWriterFixtures.refused(
            BidWriter.bid(AuthoredBid(base.bidderPubkey, base.createdAt, notAListing, base.price)),
            "a bid on a kind:1111",
        )
        assertEquals(BidRejection.NOT_A_LISTING_COORDINATE, refused.reason)
        assertEquals("A", refused.tag)
    }

    /** §4.3's event fields, before any tag is spelled. */
    @JsName("a_malformed_bidder_pubkey_and_a_negative_created_at_are_refused_as_event_fields")
    @Test
    fun `a malformed bidder pubkey and a negative created_at are refused as event fields`() {
        val base = legal()
        val pubkey = BidWriterFixtures.refused(
            BidWriter.bid(AuthoredBid(base.bidderPubkey.dropLast(1), base.createdAt, base.listing, base.price)),
            "a 63-character pubkey",
        )
        assertEquals(BidRejection.MALFORMED_EVENT_FIELD, pubkey.reason)
        assertEquals(TagRejection.WRONG_LENGTH, pubkey.tagReason)
        val createdAt = BidWriterFixtures.refused(
            BidWriter.bid(AuthoredBid(base.bidderPubkey, -1L, base.listing, base.price)),
            "a negative created_at",
        )
        assertEquals(BidRejection.MALFORMED_EVENT_FIELD, createdAt.reason)
    }

    /** §4.3's fifth bound is rejected rather than truncated, against the injected limit. */
    @JsName("more_image_tags_than_the_injected_bound_is_refused")
    @Test
    fun `more image tags than the injected bound is refused`() {
        val base = legal()
        val images = List(3) { ImageRef("https://example.invalid/$it.png") }
        val bid = AuthoredBid(base.bidderPubkey, base.createdAt, base.listing, base.price, images = images)
        val refused = BidWriterFixtures.refused(BidWriter.bid(bid, TagLimits(maxImageTags = 2)), "three images")
        assertEquals(BidRejection.LIMIT_EXCEEDED, refused.reason)
        assertEquals("image", refused.tag)
        BidWriterFixtures.built(BidWriter.bid(bid, TagLimits(maxImageTags = 3)), "three images at a bound of three")
    }

    /** §5.3's Encoding column names the row it refused, with the tag layer's reason beside it. */
    @JsName("every_encoding_column_refusal_names_its_own_row")
    @Test
    fun `every Encoding-column refusal names its own row`() {
        val base = legal()
        fun with(build: (AuthoredBid) -> AuthoredBid) = BidWriter.bid(build(base))
        val cases = listOf(
            Triple("d", TagRejection.EMPTY_VALUE, with { AuthoredBid(it.bidderPubkey, it.createdAt, it.listing, it.price, dValue = "") }),
            Triple("t", TagRejection.MALFORMED_TOKEN, with { AuthoredBid(it.bidderPubkey, it.createdAt, it.listing, it.price, topics = listOf("two words")) }),
            Triple("deliver_by", TagRejection.MALFORMED_TIMESTAMP, with { AuthoredBid(it.bidderPubkey, it.createdAt, it.listing, it.price, deliverBy = -1L) }),
            Triple("fee", TagRejection.WRONG_ARITY, with { AuthoredBid(it.bidderPubkey, it.createdAt, it.listing, it.price, feeRecipient = TagFixtures.pubkeyFor(9)) }),
            Triple("price", TagRejection.LOSSY, with { AuthoredBid(it.bidderPubkey, it.createdAt, it.listing, Msat.ofMsat(1_500L)) }),
        )
        for ((row, tagReason, build) in cases) {
            val refused = BidWriterFixtures.refused(build, "a malformed `$row`")
            assertEquals(BidRejection.MALFORMED_TAG, refused.reason, "`$row`")
            assertEquals(row, refused.tag)
            assertEquals(tagReason, refused.tagReason, "`$row`")
        }
    }

    /** Extension tags are emitted verbatim, after every row the writer spells. */
    @JsName("extra_tags_are_emitted_verbatim_after_every_row")
    @Test
    fun `extra tags are emitted verbatim after every row`() {
        val extra = listOf("client", "nenya-test", "")
        val event = BidWriterFixtures.built(BidWriter.bid(withExtra(extra)), "a bid with an extension")
        assertEquals(extra, event.tags.last())
        assertTrue(event.tags.dropLast(1).all { it[0] in BidWriter.TAG_ORDER })
    }
}
