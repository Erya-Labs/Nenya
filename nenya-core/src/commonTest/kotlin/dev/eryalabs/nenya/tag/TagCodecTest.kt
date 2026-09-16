package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.MoneyRejection
import dev.eryalabs.nenya.money.Msat
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §5.3's Encoding column, §4.3's structural rules and §8.1's two arities, each control asserting
 * the **reason** rather than merely a failure.
 *
 * A codec that rejects everything passes a test that only asks "did this fail". Every rejection
 * here names the [TagRejection] it expects, and the ones that exist to draw a distinction —
 * unsupported versus malformed, duplicate versus missing, lossy versus above supply — are paired
 * with the positive control that gives the rejection its meaning.
 */
class TagCodecTest {

    private fun refusal(block: () -> Unit): TagException = assertFailsWith<TagException>(block = block)

    private val request = TagContext.listing(NenyaKind.REQUEST)
    private val offer = TagContext.listing(NenyaKind.OFFER)
    private val bid = TagContext.publicBid()
    private val proposal = TagContext.orderMessage(NenyaKind.OrderMessageType.PROPOSAL)

    // ---------------------------------------------------------------- the two worked examples

    @JsName("the_specification_s_own_worked_request_and_offer_read")
    @Test
    fun `the specification's own worked request and offer read`() {
        val read = TagFixtures.read(TagFixtures.minimalRequest(), request)

        assertEquals("lighthouse-loop-2026-09", read.dValue)
        assertEquals(Msat.ofSat(120_000), read.price)
        assertEquals(listOf("nenya", "wtb"), read.topics)
        assertEquals(1, read.nenyaVersion)
        assertEquals(FeeTerm.Absent, read.fee)

        val fromOffer = TagFixtures.read(TagFixtures.minimalOffer(), offer)
        assertEquals(Msat.ofSat(50_000), fromOffer.price)
        assertEquals(listOf("nenya", "wts"), fromOffer.topics)
    }

    // ------------------------------------------------- the trap §5.3 names about its own columns

    /**
     * §5.3's headline control, and the reason [TagContext] is a parameter at all.
     *
     * §5.3 says the Card. and Requirement columns are listing rules and MUST NOT be applied to a
     * bid, and says what happens to a codec that ignores that: "Applying the listing rules
     * everywhere rejects this document's own bid example", which carries a single `["t", "nenya"]`
     * and no `wtb`/`wts`. §6 repeats it: "a single `["t", "nenya"]`, with no `wtb`/`wts`, is
     * correct on a bid, as the example above shows."
     */
    @JsName("a_single_t_nenya_is_correct_on_a_bid_and_refused_on_a_listing")
    @Test
    fun `a single t nenya is correct on a bid and refused on a listing`() {
        val bidTags = TagFixtures.minimalBid()
        val read = TagFixtures.read(bidTags, bid)
        assertEquals(listOf("nenya"), read.topics)

        // The same tag set again, but declared a listing: §5.3's `≥2` now applies.
        val listingTags = TagFixtures.minimalRequest()
        listingTags.removeAll { it[0] == "t" }
        listingTags += listOf("t", "nenya")

        val refused = refusal { TagFixtures.read(listingTags, request) }
        assertEquals(
            TagRejection.CARDINALITY_TOO_FEW,
            refused.reason,
            "§5.3 marks `t` cardinality ≥2 on a listing; the same set also fails the wtb/wts " +
                "clause, and the cardinality is the rule that reaches it first",
        )
    }

    /** The cardinality gate again, one rule further in: two `t` tags, neither naming a side. */
    @JsName("a_listing_with_two_topics_and_no_side_token_is_refused")
    @Test
    fun `a listing with two topics and no side token is refused`() {
        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "t" }
        tags += listOf("t", "nenya")
        tags += listOf("t", "ai-video")

        assertEquals(TagRejection.MISSING_TOPIC, refusal { TagFixtures.read(tags, request) }.reason)
    }

    /** §5.3 matches `t` tokens case-insensitively on read, because relay tag indexes are byte-exact. */
    @JsName("topic_matching_is_case_insensitive_on_read_and_the_verbatim_token_is_kept")
    @Test
    fun `topic matching is case-insensitive on read and the verbatim token is kept`() {
        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "t" }
        tags += listOf("t", "Nenya")
        tags += listOf("t", "WTB")

        val read = TagFixtures.read(tags, request)
        assertEquals(listOf("Nenya", "WTB"), read.topics, "§4.3 round-tripping needs the bytes as sent")
        assertEquals(listOf("nenya", "wtb"), read.normalisedTopics)
    }

    @JsName("a_listing_carrying_both_side_tokens_is_refused")
    @Test
    fun `a listing carrying both side tokens is refused`() {
        val tags = TagFixtures.minimalRequest()
        tags += listOf("t", "wts")

        assertEquals(TagRejection.MISSING_TOPIC, refusal { TagFixtures.read(tags, request) }.reason)
    }

    // ----------------------------------------------------------------- §4.3's duplicate rule

    /**
     * §4.3: a tag marked `1` or `0–1` appearing twice is **rejected**, never resolved by taking
     * the first, the last or the smallest. Both orderings of two different values are asserted, so
     * neither a first-wins nor a last-wins implementation can pass.
     */
    @JsName("a_duplicate_price_is_refused_in_both_orderings")
    @Test
    fun `a duplicate price is refused in both orderings`() {
        for (pair in listOf(
            listOf(listOf("price", "1", "SAT"), listOf("price", "2", "SAT")),
            listOf(listOf("price", "2", "SAT"), listOf("price", "1", "SAT")),
        )) {
            val tags = TagFixtures.minimalRequest()
            tags.removeAll { it[0] == "price" }
            tags += pair

            val refused = refusal { TagFixtures.read(tags, request) }
            assertEquals(
                TagRejection.DUPLICATE_TAG,
                refused.reason,
                "§4.3: \"First wins\" and \"last wins\" are both defensible, which is exactly the " +
                    "problem — two conformant implementations would then disagree about the price " +
                    "of the same signed event",
            )
        }
    }

    /**
     * §4.3's duplicate rule is about signed events rather than about listings, so it is enforced
     * outside a listing too — where §5.3's *presence* rules deliberately are not.
     */
    @JsName("a_duplicate_price_is_refused_on_a_bid_as_well_as_on_a_listing")
    @Test
    fun `a duplicate price is refused on a bid as well as on a listing`() {
        val tags = TagFixtures.minimalBid()
        tags += listOf("price", "1", "SAT")

        assertEquals(TagRejection.DUPLICATE_TAG, refusal { TagFixtures.read(tags, bid) }.reason)
    }

    @JsName("a_tag_marked_zero_or_n_may_repeat")
    @Test
    fun `a tag marked zero-or-n may repeat`() {
        val tags = TagFixtures.minimalRequest()
        tags += listOf("p", TagFixtures.pubkeyFor(1))
        tags += listOf("p", TagFixtures.pubkeyFor(2), "wss://relay.example.invalid")

        val read = TagFixtures.read(tags, request)
        assertEquals(2, read.pubkeyRefs.size)
        assertNull(read.pubkeyRefs[0].relay)
        assertEquals("wss://relay.example.invalid", read.pubkeyRefs[1].relay)
    }

    // -------------------------------------------------------------------------- §8.1's fee tag

    /**
     * §8.1 and T2's log are both explicit: `Absent` is a case and not a zero. A codec that
     * collapsed them destroys "the absence of a fee is itself a signed statement".
     */
    @JsName("a_stated_zero_fee_and_a_missing_fee_tag_are_different_values")
    @Test
    fun `a stated zero fee and a missing fee tag are different values`() {
        val stated = TagFixtures.read(TagFixtures.minimalRequest().also { it += listOf("fee", "0") }, request)
        val missing = TagFixtures.read(TagFixtures.minimalRequest(), request)

        assertEquals(FeeTerm.of(0), stated.fee)
        assertNull(stated.feeRecipient)
        assertSame(FeeTerm.Absent, missing.fee)
        assertFalse(stated.fee == missing.fee, "§8.1: a stated `[\"fee\", \"0\"]` is a signed statement")
        assertEquals(0, stated.fee.basisPoints)
        assertEquals(0, missing.fee.basisPoints)
    }

    /** §8.1: a three-element zero-bps fee and a two-element non-zero fee "are both malformed". */
    @JsName("both_wrong_fee_arities_are_refused_as_arity")
    @Test
    fun `both wrong fee arities are refused as arity`() {
        val withRecipientAtZero = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "0", TagFixtures.pubkeyFor(3)) }
        val withoutRecipientAboveZero = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "250") }

        assertEquals(
            TagRejection.WRONG_ARITY,
            refusal { TagFixtures.read(withRecipientAtZero, request) }.reason,
            "§8.1 requires the recipient be OMITTED at zero basis points",
        )
        assertEquals(
            TagRejection.WRONG_ARITY,
            refusal { TagFixtures.read(withoutRecipientAboveZero, request) }.reason,
            "§8.1 makes the recipient REQUIRED above zero basis points",
        )
    }

    /**
     * §8.1's range, and its explicit instruction not to refuse an in-range value for being large:
     * a local policy limit "MUST NOT be applied on read", and reporting one as a malformed fee tag
     * would make a legal peer look broken.
     */
    @JsName("ten_thousand_and_one_bps_is_refused_and_nine_thousand_is_accepted")
    @Test
    fun `ten thousand and one bps is refused and nine thousand is accepted`() {
        val above = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "10001", TagFixtures.pubkeyFor(4)) }
        val legal = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "9000", TagFixtures.pubkeyFor(4)) }
        val boundary = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "10000", TagFixtures.pubkeyFor(4)) }

        assertEquals(TagRejection.BPS_ABOVE_MAXIMUM, refusal { TagFixtures.read(above, request) }.reason)
        assertEquals(9000, TagFixtures.read(legal, request).fee.basisPoints)
        assertEquals(10000, TagFixtures.read(boundary, request).fee.basisPoints)
        assertEquals(TagFixtures.pubkeyFor(4), TagFixtures.read(legal, request).feeRecipient)
    }

    @JsName("a_fee_recipient_is_read_in_either_case_and_normalised")
    @Test
    fun `a fee recipient is read in either case and normalised`() {
        val uppercase = TagFixtures.uppercasePubkeys.first()
        val tags = TagFixtures.minimalRequest().also { it += listOf("fee", "250", uppercase) }

        assertEquals(uppercase.lowercase(), TagFixtures.read(tags, request).feeRecipient)
    }

    /**
     * §8.1 names the upper bound itself, so a `fee` value too long to parse is reported as above
     * the maximum rather than as unreadable. Reporting `10^19`% as merely malformed would tell the
     * caller its peer is broken when the peer in fact asked for more than 100%, which is the
     * confusion §8.1's "never make a legal peer look broken" clause is about, in reverse.
     */
    @JsName("a_fee_value_too_long_to_parse_is_refused_as_above_the_maximum")
    @Test
    fun `a fee value too long to parse is refused as above the maximum`() {
        val tags = TagFixtures.minimalRequest()
            .also { it += listOf("fee", "99999999999999999999", TagFixtures.pubkeyFor(4)) }

        assertEquals(TagRejection.BPS_ABOVE_MAXIMUM, refusal { TagFixtures.read(tags, request) }.reason)
    }

    @JsName("a_fee_in_a_non_canonical_decimal_form_is_refused")
    @Test
    fun `a fee in a non-canonical decimal form is refused`() {
        val tags = TagFixtures.minimalRequest().also { it += listOf("fee", "0250", TagFixtures.pubkeyFor(4)) }

        assertEquals(
            TagRejection.MALFORMED_NUMBER,
            refusal { TagFixtures.read(tags, request) }.reason,
            "§8.1 gives the basis points \"in the same strict form as a price (§4.4)\"",
        )
    }

    // ------------------------------------------------------------------------ §4.4's price tag

    /**
     * §4.4: the optional NIP-99 `<frequency>` fourth element MUST NOT be emitted by Nenya v1 and
     * "MUST be treated as **unsupported** on read" — which is a different answer from malformed,
     * because a recurring-price NIP-99 listing is well formed and simply not implemented here.
     */
    @JsName("the_nip_99_recurring_price_form_is_unsupported_and_not_malformed")
    @Test
    fun `the NIP-99 recurring price form is unsupported and not malformed`() {
        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "price" }
        tags += listOf("price", "50000", "SAT", "monthly")

        assertEquals(TagRejection.UNSUPPORTED, refusal { TagFixtures.read(tags, request) }.reason)
    }

    @JsName("a_two_element_price_is_refused_as_arity")
    @Test
    fun `a two-element price is refused as arity`() {
        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "price" }
        tags += listOf("price", "50000")

        assertEquals(TagRejection.WRONG_ARITY, refusal { TagFixtures.read(tags, request) }.reason)
    }

    /** §4.4's permissive-on-read list, all eight tokens, each normalised to millisatoshis. */
    @JsName("all_eight_permissive_unit_tokens_are_accepted_and_normalised")
    @Test
    fun `all eight permissive unit tokens are accepted and normalised`() {
        val expected = Msat.ofSat(50_000)
        val cases = listOf(
            listOf("price", "50000", "SAT"),
            listOf("price", "50000", "sat"),
            listOf("price", "50000", "sats"),
            listOf("price", "50000", "SATS"),
            listOf("price", "50000000", "msat"),
            listOf("price", "50000000", "MSAT"),
            listOf("price", "0.00050000", "BTC"),
            listOf("price", "0.0005", "btc"),
        )

        for (price in cases) {
            val tags = TagFixtures.minimalRequest()
            tags.removeAll { it[0] == "price" }
            tags += price

            assertEquals(expected, TagFixtures.read(tags, request).price, "unit token ${price[2]}")
        }
    }

    @JsName("a_unit_token_outside_the_eight_is_refused_as_an_unknown_unit")
    @Test
    fun `a unit token outside the eight is refused as an unknown unit`() {
        for (unit in listOf("Sat", "mSat", "msats", "USD", "")) {
            val tags = TagFixtures.minimalRequest()
            tags.removeAll { it[0] == "price" }
            tags += listOf("price", "50000", unit)

            assertEquals(
                TagRejection.UNKNOWN_UNIT,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§4.4 lists the eight tokens an implementation MUST accept; `$unit` is not one",
            )
        }
    }

    /**
     * §4.4's "Never lossy", reached through a tag: a `price` is satoshi-denominated on the wire,
     * so an `msat` value that is not a multiple of 1000 MUST fail loudly and MUST NOT round.
     *
     * The paired positive control is the multiple of 1000 immediately below it, so the rejection
     * is about the precision and not about the unit.
     */
    @JsName("an_msat_price_that_is_not_a_whole_satoshi_is_refused_as_lossy")
    @Test
    fun `an msat price that is not a whole satoshi is refused as lossy`() {
        val lossy = TagFixtures.minimalRequest()
        lossy.removeAll { it[0] == "price" }
        lossy += listOf("price", "1500", "msat")

        val refused = refusal { TagFixtures.read(lossy, request) }
        assertEquals(TagRejection.LOSSY, refused.reason)
        assertEquals(
            MoneyRejection.NOT_WHOLE_SATOSHIS,
            (refused.cause as MoneyException).reason,
            "T1 owns this rule; the tag codec maps its reason rather than re-deriving it",
        )

        val whole = TagFixtures.minimalRequest()
        whole.removeAll { it[0] == "price" }
        whole += listOf("price", "2000", "msat")
        assertEquals(Msat.ofMsat(2000), TagFixtures.read(whole, request).price)
    }

    /** The same rule from the `BTC` side: finer than a millisatoshi, and finer than a satoshi. */
    @JsName("a_btc_price_finer_than_a_satoshi_is_refused_as_lossy")
    @Test
    fun `a BTC price finer than a satoshi is refused as lossy`() {
        for (value in listOf("0.000000000001", "0.000000005")) {
            val tags = TagFixtures.minimalRequest()
            tags.removeAll { it[0] == "price" }
            tags += listOf("price", value, "BTC")

            assertEquals(TagRejection.LOSSY, refusal { TagFixtures.read(tags, request) }.reason, value)
        }
    }

    @JsName("a_price_above_the_bitcoin_supply_is_refused_as_above_supply")
    @Test
    fun `a price above the bitcoin supply is refused as above supply`() {
        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "price" }
        tags += listOf("price", "2100000000000001", "SAT")

        val refused = refusal { TagFixtures.read(tags, request) }
        assertEquals(TagRejection.ABOVE_SUPPLY, refused.reason)
        assertEquals(MoneyRejection.ABOVE_SUPPLY, (refused.cause as MoneyException).reason)
    }

    @JsName("a_price_that_is_not_a_decimal_at_all_is_refused_as_a_malformed_amount")
    @Test
    fun `a price that is not a decimal at all is refused as a malformed amount`() {
        for (value in listOf("-1", "1e6", "+1", "50 000", "٥٠", "")) {
            val tags = TagFixtures.minimalRequest()
            tags.removeAll { it[0] == "price" }
            tags += listOf("price", value, "SAT")

            assertEquals(
                TagRejection.MALFORMED_AMOUNT,
                refusal { TagFixtures.read(tags, request) }.reason,
                "`$value` is not §4.4's decimal amount",
            )
        }
    }

    // ------------------------------------------------------------------- §4.2's coordinate

    /**
     * §5.3 makes `d` opaque, so a `d` value may contain a colon — and only a split with a limit of
     * three survives one. An unbounded split truncates the address to its first segment, and every
     * reference to that listing then addresses a different event.
     */
    @JsName("a_coordinate_whose_d_value_contains_colons_round_trips_intact")
    @Test
    fun `a coordinate whose d value contains colons round-trips intact`() {
        val dValue = "season:2:episode:7"
        val value = "${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(9)}:$dValue"

        val coordinate = Coordinate.parse(value)
        assertEquals(dValue, coordinate.dValue)
        assertEquals(NenyaKind.REQUEST, coordinate.kind)
        assertEquals(value, coordinate.toTagValue())
    }

    @JsName("an_empty_d_value_is_refused_in_a_coordinate_and_in_a_d_tag")
    @Test
    fun `an empty d value is refused, in a coordinate and in a d tag`() {
        assertEquals(
            TagRejection.EMPTY_VALUE,
            refusal { Coordinate.parse("${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(9)}:") }.reason,
        )

        val tags = TagFixtures.minimalRequest()
        tags.removeAll { it[0] == "d" }
        tags += listOf("d", "")
        assertEquals(TagRejection.EMPTY_VALUE, refusal { TagFixtures.read(tags, request) }.reason)
    }

    /**
     * §4.3: a pubkey is exactly 64 hex characters, and an implementation MUST reject a value of
     * the wrong length rather than padding or truncating it. The paired positive control is the
     * **uppercase** key immediately below — §4.3's no-normalisation exception list names only the
     * BOLT-11 invoice string and the preimage, and states that it is exhaustive.
     *
     * Both fixtures are the same externally-authored value: the BIP-340 vectors are uppercase in
     * the file, so the normalisation control and the fixture cannot drift apart.
     */
    @JsName("a_coordinate_pubkey_of_63_characters_is_refused_as_the_wrong_length")
    @Test
    fun `a coordinate pubkey of 63 characters is refused as the wrong length`() {
        val uppercase = TagFixtures.uppercasePubkeys[1]
        val truncated = uppercase.dropLast(1)

        val refused = refusal { Coordinate.parse("${NenyaKind.REQUEST}:$truncated:d") }
        assertEquals(TagRejection.WRONG_LENGTH, refused.reason)

        val accepted = Coordinate.parse("${NenyaKind.REQUEST}:$uppercase:d")
        assertEquals(uppercase.lowercase(), accepted.pubkey)
        assertEquals("${NenyaKind.REQUEST}:${uppercase.lowercase()}:d", accepted.toTagValue())
    }

    @JsName("a_coordinate_pubkey_that_is_not_hex_is_refused_as_not_hex")
    @Test
    fun `a coordinate pubkey that is not hex is refused as not hex`() {
        val notHex = "g".repeat(64)
        assertEquals(
            TagRejection.NOT_HEX,
            refusal { Coordinate.parse("${NenyaKind.REQUEST}:$notHex:d") }.reason,
        )
    }

    @JsName("a_coordinate_with_fewer_than_three_fields_is_refused_as_arity")
    @Test
    fun `a coordinate with fewer than three fields is refused as arity`() {
        assertEquals(
            TagRejection.WRONG_ARITY,
            refusal { Coordinate.parse("${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(9)}") }.reason,
        )
    }

    @JsName("a_coordinate_kind_in_a_non_canonical_form_is_refused")
    @Test
    fun `a coordinate kind in a non-canonical form is refused`() {
        assertEquals(
            TagRejection.MALFORMED_NUMBER,
            refusal { Coordinate.parse("030404:${TagFixtures.pubkeyFor(9)}:d") }.reason,
            "a coordinate is a relay address key compared byte-exactly; two spellings of one " +
                "address is the disagreement §4.3's duplicate rule exists to prevent one layer up",
        )
    }

    // ------------------------------------------------------------------------- §5.3's item tag

    @JsName("an_item_tag_is_required_on_a_proposal_and_on_a_private_bid_and_forbidden_elsewhere")
    @Test
    fun `an item tag is required on a proposal and on a private bid, and forbidden elsewhere`() {
        val read = TagFixtures.read(TagFixtures.minimalProposal(), proposal)
        assertNotNull(read.item)
        assertEquals(ItemRef.CANONICAL_QUANTITY, read.item!!.quantity)

        val privateBid = TagContext.orderMessage(NenyaKind.OrderMessageType.PRIVATE_BID)
        assertNotNull(TagFixtures.read(TagFixtures.minimalProposal(), privateBid).item)

        val missing = TagFixtures.minimalProposal().also { tags -> tags.removeAll { it[0] == "item" } }
        assertEquals(TagRejection.MISSING_REQUIRED, refusal { TagFixtures.read(missing, proposal) }.reason)

        val onPublicBid = TagFixtures.minimalBid().also { it += listOf("item", TagFixtures.coordinate(), "1") }
        assertEquals(
            TagRejection.FORBIDDEN_TAG,
            refusal { TagFixtures.read(onPublicBid, bid) }.reason,
            "§5.3: `A`/`a` already carry the binding on a public bid, and a second binding that " +
                "could disagree with the first is precisely what §4.2 exists to prevent",
        )

        val onListing = TagFixtures.minimalRequest().also { it += listOf("item", TagFixtures.coordinate(), "1") }
        assertEquals(TagRejection.FORBIDDEN_TAG, refusal { TagFixtures.read(onListing, request) }.reason)
    }

    @JsName("two_item_tags_are_a_rejection_everywhere")
    @Test
    fun `two item tags are a rejection everywhere`() {
        val tags = TagFixtures.minimalProposal().also { it += listOf("item", TagFixtures.coordinate(index = 5), "1") }

        assertEquals(TagRejection.DUPLICATE_TAG, refusal { TagFixtures.read(tags, proposal) }.reason)
    }

    @JsName("an_item_quantity_other_than_the_string_one_is_refused")
    @Test
    fun `an item quantity other than the string one is refused`() {
        for (quantity in listOf("2", "01", "1.0", "")) {
            val tags = TagFixtures.minimalProposal()
            tags.removeAll { it[0] == "item" }
            tags += listOf("item", TagFixtures.coordinate(), quantity)

            assertEquals(
                TagRejection.MALFORMED_NUMBER,
                refusal { TagFixtures.read(tags, proposal) }.reason,
                "§5.3: the quantity MUST be the string \"1\" in Nenya v1 and MUST be rejected " +
                    "otherwise; `$quantity` is not it",
            )
        }
    }

    // ------------------------------------------------------------------------ §5.3's image tag

    @JsName("an_image_that_is_not_an_https_url_is_refused")
    @Test
    fun `an image that is not an https URL is refused`() {
        for (url in listOf(
            "data:image/png;base64,AAAA",
            "http://example.invalid/sample.png",
            "ftp://example.invalid/sample.png",
        )) {
            val tags = TagFixtures.minimalRequest().also { it += listOf("image", url, "64x64") }

            assertEquals(
                TagRejection.NOT_HTTPS,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§5.3: an `image` MUST be an `https:` URL, never inline bytes and never a `data:` URI",
            )
        }

        val https = TagFixtures.minimalRequest().also { it += listOf("image", "https://example.invalid/a.png", "64x64") }
        assertEquals(1, TagFixtures.read(https, request).images.size)
    }

    /**
     * The stated narrowing, exercised rather than only argued for in a KDoc: §5.3's Encoding cell
     * prints three elements and NIP-99 makes the dimensions optional, so a two-element `image` is
     * read with `dimensions == null`. §5.1 is the reason — a Nenya offer is an **unmodified**
     * NIP-99 classified listing, and rejecting the two-element form would reject a conformant one.
     *
     * A *present* third element is still held to the `<width>x<height>` form the column fixes.
     */
    @JsName("an_image_tag_is_read_in_both_arities_and_a_malformed_dimension_is_refused")
    @Test
    fun `an image tag is read in both arities and a malformed dimension is refused`() {
        val twoElement = TagFixtures.minimalRequest()
            .also { it += listOf("image", "https://example.invalid/a.png") }

        assertNull(TagFixtures.read(twoElement, request).images.single().dimensions)
        assertEquals(
            "1024x768",
            TagFixtures.read(
                TagFixtures.minimalRequest().also { it += listOf("image", "https://e.invalid/a.png", "1024x768") },
                request,
            ).images.single().dimensions,
        )

        for (dimensions in listOf("1024X768", "1024", "abc", "1024x", "x768", "")) {
            val tags = TagFixtures.minimalRequest()
                .also { it += listOf("image", "https://example.invalid/a.png", dimensions) }

            assertEquals(
                TagRejection.MALFORMED_NUMBER,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§5.3 encodes the third element as `<width>x<height>`; `$dimensions` is not it",
            )
        }

        val oneElement = TagFixtures.minimalRequest().also { it += listOf("image") }
        assertEquals(TagRejection.WRONG_ARITY, refusal { TagFixtures.read(oneElement, request) }.reason)
    }

    /** §4.3's fifth bound, the one T8 left to this package: reject, never truncate. */
    @JsName("the_sixty_fifth_image_tag_is_refused_as_limit_exceeded_and_sixty_four_are_accepted")
    @Test
    fun `the sixty-fifth image tag is refused as limit exceeded, and sixty-four are accepted`() {
        fun listingWith(images: Int) = TagFixtures.minimalRequest().also { tags ->
            repeat(images) { tags += listOf("image", "https://example.invalid/$it.png", "8x8") }
        }

        assertEquals(64, TagFixtures.read(listingWith(64), request).images.size)
        assertEquals(
            TagRejection.LIMIT_EXCEEDED,
            refusal { TagFixtures.read(listingWith(65), request) }.reason,
        )
    }

    /** §4.3 says the bounds SHOULD be configurable, so the bound is injected and a test can pin it. */
    @JsName("the_image_bound_is_injected")
    @Test
    fun `the image bound is injected`() {
        val tags = TagFixtures.minimalRequest().also { list ->
            repeat(3) { list += listOf("image", "https://example.invalid/$it.png", "8x8") }
        }

        assertEquals(3, TagFixtures.read(tags, request, TagLimits(maxImageTags = 3)).images.size)
        assertEquals(
            TagRejection.LIMIT_EXCEEDED,
            refusal { TagFixtures.read(tags, request, TagLimits(maxImageTags = 2)) }.reason,
        )
        assertEquals(
            TagRejection.NON_POSITIVE_LIMIT,
            refusal { TagLimits(maxImageTags = 0) }.reason,
        )
    }

    // ------------------------------------------------- §5.3's listing requirement and forbidden rows

    /**
     * §5.3's Requirement column, per listing kind. `alt` is MUST on a request and SHOULD on an
     * offer — so the *same* tag set is a rejection under one listing kind and legal under another,
     * which is the whole content of the conditional row.
     */
    @JsName("alt_is_required_on_a_request_and_optional_on_an_offer")
    @Test
    fun `alt is required on a request and optional on an offer`() {
        val withoutAlt = TagFixtures.minimalRequest().also { tags -> tags.removeAll { it[0] == "alt" } }

        assertEquals(
            TagRejection.MISSING_REQUIRED,
            refusal { TagFixtures.read(withoutAlt, request) }.reason,
        )
        assertNull(TagFixtures.read(TagFixtures.minimalOffer(), offer).alt)
    }

    @JsName("every_must_row_is_enforced_on_a_listing_and_none_of_them_on_a_bid")
    @Test
    fun `every MUST row is enforced on a listing and none of them on a bid`() {
        for (name in listOf("d", "title", "price", "nenya")) {
            val tags = TagFixtures.minimalRequest().also { list -> list.removeAll { it[0] == name } }

            assertEquals(
                TagRejection.MISSING_REQUIRED,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§5.3 marks `$name` MUST on a listing",
            )
        }
        // §5.3: the Requirement column MUST NOT be applied to a bid. §6 requires `price`,
        // `t=nenya` and `nenya` on a bid by its own rules, and none of `d`, `title` or `alt`.
        assertNull(TagFixtures.read(TagFixtures.minimalBid(), bid).dValue)
        assertNull(TagFixtures.read(TagFixtures.minimalBid(), bid).title)
    }

    @JsName("g_and_location_are_refused_on_a_listing")
    @Test
    fun `g and location are refused on a listing`() {
        for (name in listOf("g", "location")) {
            val tags = TagFixtures.minimalRequest().also { it += listOf(name, "u4pruydqqvj") }

            assertEquals(
                TagRejection.FORBIDDEN_TAG,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§5.3: a geohash on a Nenya listing is a location leak with no compensating function",
            )
        }
    }

    /**
     * The other half of §5.3's MUST NOT rows, and the reason they are not refused everywhere:
     * Card. is a **listing** rule. §5.3 says an implementation MUST NOT emit `g` and SHOULD *warn*
     * if it parses one — which is a statement about what to do with a value that arrives, so the
     * value is carried through untouched rather than parsed into a location this library would
     * then be holding, and §4.3's round-trip rule keeps it byte-identical.
     */
    @JsName("g_and_location_outside_a_listing_are_carried_through_rather_than_refused")
    @Test
    fun `g and location outside a listing are carried through rather than refused`() {
        val tags = TagFixtures.minimalBid()
            .also { it += listOf("g", "u4pruydqqvj") }
            .also { it += listOf("location", "Reykjavik") }

        val read = TagFixtures.read(tags, bid)
        assertEquals(tags.toList(), read.republish())
        assertEquals(listOf(listOf("g", "u4pruydqqvj")), read.occurrences("g"))
        assertTrue(
            read.unknownTags.isEmpty(),
            "`g` and `location` are in §5.3's table, so they are recognised tags with no encoding " +
                "— not unknown ones",
        )
    }

    // ---------------------------------------------------------------------- §4.3's timestamps

    @JsName("a_timestamp_that_is_not_a_non_negative_decimal_integer_is_refused")
    @Test
    fun `a timestamp that is not a non-negative decimal integer is refused`() {
        for (value in listOf("-1", "1.5", "1e9", "+1757000000", "now", "")) {
            val tags = TagFixtures.minimalRequest().also { it += listOf("expiration", value) }

            assertEquals(
                TagRejection.MALFORMED_TIMESTAMP,
                refusal { TagFixtures.read(tags, request) }.reason,
                "§4.3 forbids a floating-point, exponential or signed form; `$value` is one",
            )
        }

        val legal = TagFixtures.minimalRequest().also { it += listOf("expiration", "1759592000") }
        assertEquals(1_759_592_000L, TagFixtures.read(legal, request).expiration)
    }

    // ------------------------------------------------------------------------ §4.3's unknown tags

    @JsName("unknown_tags_are_ignored_on_read_and_kept_verbatim")
    @Test
    fun `unknown tags are ignored on read and kept verbatim`() {
        val unknown = listOf("client", "some-client", "0.1")
        val tags = TagFixtures.minimalRequest().also { it += unknown }

        val read = TagFixtures.read(tags, request)
        assertEquals(listOf(unknown), read.unknownTags)
        assertEquals(tags.toList(), read.republish())
    }

    // ------------------------------------------------------------------------- the context itself

    @JsName("reading_an_event_under_the_wrong_kind_is_refused")
    @Test
    fun `reading an event under the wrong kind is refused`() {
        val checked = TagFixtures.checked(TagFixtures.minimalRequest(), NenyaKind.REQUEST)

        assertEquals(
            TagRejection.KIND_MISMATCH,
            refusal { TagSet.read(checked, offer) }.reason,
            "§5.3's cardinality and requirement rules are a function of the kind",
        )
    }

    @JsName("a_context_must_name_a_type_for_a_kind_sixteen_and_must_not_elsewhere")
    @Test
    fun `a context must name a type for a kind sixteen and must not elsewhere`() {
        assertEquals(
            TagRejection.MALFORMED_CONTEXT,
            refusal { TagContext(NenyaKind.ORDER_MESSAGE) }.reason,
        )
        assertEquals(
            TagRejection.MALFORMED_CONTEXT,
            refusal { TagContext(NenyaKind.PUBLIC_BID, orderMessageType = 1) }.reason,
        )
        assertEquals(
            TagRejection.MALFORMED_CONTEXT,
            refusal { TagContext.listing(NenyaKind.PUBLIC_BID) }.reason,
        )
    }

    /**
     * §7.4 says an implementation MUST ignore a `kind:16` whose `type` it does not implement and
     * MUST NOT map an unknown `type` onto the nearest known one — so a context has to be able to
     * name a type this library does not model in order to ignore it correctly.
     */
    @JsName("a_context_may_name_an_order_message_type_this_library_does_not_implement")
    @Test
    fun `a context may name an order message type this library does not implement`() {
        val context = TagContext.orderMessage(99)

        assertEquals(99, context.orderMessageType)
        assertFalse(context.requiresItem, "an unknown type carries no `item` requirement")
        assertFalse(context.isListing)
    }

    // ------------------------------------------------------------------- STOP RULE 14 / §12

    /**
     * §12 item 11 and STOP RULE 14: an order id, a listing id and a counterparty pubkey MUST NOT
     * appear in a log or in the string representation of anything this library exposes.
     *
     * A `kind:16` rumor's tags carry all three. A default Kotlin `toString` would print them; the
     * debugging `toString` somebody adds later is what this control is for — the same one T5
     * carries for the order id and T3 for the preimage.
     */
    @JsName("no_tag_value_leaks_through_a_string_representation")
    @Test
    fun `no tag value leaks through a string representation`() {
        val counterparty = TagFixtures.pubkeyFor(1)
        val read = TagFixtures.read(TagFixtures.minimalProposal(), proposal)

        val renderings = listOf(
            read.toString(),
            read.item.toString(),
            read.item!!.coordinate.toString(),
            read.pubkeyRefs.single().toString(),
            read.context.toString(),
        )
        for (rendering in renderings) {
            assertFalse(counterparty in rendering, "a counterparty pubkey leaked: $rendering")
            assertFalse(TagFixtures.coordinate() in rendering, "a listing coordinate leaked: $rendering")
            assertFalse(TagFixtures.pubkeyFor(2) in rendering, "a fee recipient leaked: $rendering")
        }
        assertTrue(
            TagFixtures.coordinate() == read.item!!.coordinate.toTagValue(),
            "…and the value must actually still be reachable, or this control proves nothing",
        )
    }
}
