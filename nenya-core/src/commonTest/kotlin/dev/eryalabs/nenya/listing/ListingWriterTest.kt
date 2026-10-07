package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §5.1's and §5.2's write half: the emission order held equal to the document's own worked listings,
 * those listings reproduced byte for byte, and one negative control per rule the writer enforces.
 *
 * The generated round trip is in `ListingWriterPropertyTest`; this file is the part a generator
 * cannot reach. A corpus proves that build → decode → build is a fixed point, and would prove it
 * just as happily for a writer that emitted §5.3's rows in alphabetical order and refused nothing
 * the document forbids. So the two assertions here that matter most are both against
 * `spec/NENYA-1.md` parsed at test time:
 *
 * - "the emission order is the order the document's own worked listings print"
 * - "each worked listing rebuilt by the writer reproduces the document's own tags"
 *
 * Every refusal below is asserted by [ListingBuild.Refused.reason] and
 * [ListingBuild.Refused.tag] — data, never the wording of [ListingBuild.Refused.detail] — and the
 * §5.3 Encoding-column refusals also by [ListingBuild.Refused.tagReason], which is where the tag
 * layer's own answer survives.
 */
class ListingWriterTest {

    private companion object {

        /** The three kinds §5.1 and §5.2 define, which is every kind this writer writes. */
        val KINDS: List<Int> = ListingWriterFixtures.KINDS

        /** §5.2's request `status` tokens, which §5.1 forbids on an offer by name. */
        val REQUEST_ONLY_STATUSES: List<ListingStatus> =
            (ListingStatusCodec.REQUEST_VOCABULARY - ListingStatusCodec.OFFER_VOCABULARY).toList()

        /** A listing of [kind] carrying every MUST row, as the paired positive control. */
        fun legal(kind: Int): AuthoredListing = ListingWriterFixtures.minimal(kind)
    }

    // ---------------------------------------------------------------------------------------------
    // The emission order, against the document.
    // ---------------------------------------------------------------------------------------------

    /**
     * §4.1 hashes the tags **in the order they appear**, so the emission order is part of every event
     * this writer produces and had to be chosen. It is not chosen here: for every row §5.1's and
     * §5.2's worked listings print, it is the order those examples print.
     *
     * The assertion expands each name to its own count rather than comparing distinct lists, so it
     * pins the **grouping** too: a writer that emitted `["t", "nenya"]`, then the `image` tags, then
     * the rest of the `t` tokens would satisfy a `distinct()` comparison and fail this one.
     */
    @JsName("the_emission_order_is_the_order_the_documents_own_worked_listings_print")
    @Test
    fun `the emission order is the order the document's own worked listings print`() {
        val examples = Section51.all()
        assertEquals(2, examples.size, "§5.1 and §5.2 print one worked listing each")
        assertEquals(NenyaKind.OFFER, examples[0].kind, "§5.1's example is the offer kind")
        assertEquals(NenyaKind.REQUEST, examples[1].kind, "§5.2's example is the request kind")

        for (example in examples) {
            val names = example.tags.map { it[0] }
            val counts = names.groupingBy { it }.eachCount()
            val expected = ListingWriter.TAG_ORDER
                .filter { counts.containsKey(it) }
                .flatMap { name -> List(counts.getValue(name)) { name } }

            assertEquals(
                expected,
                names,
                "ListingWriter.TAG_ORDER must emit the rows of \"${example.heading}\" in the order " +
                    "${Section51.specPath()} prints them, and must keep each row's occurrences " +
                    "together",
            )
            assertTrue(
                counts.keys.all { it in ListingWriter.TAG_ORDER },
                "\"${example.heading}\" prints a row this writer cannot emit: " +
                    "${counts.keys - ListingWriter.TAG_ORDER.toSet()}",
            )
        }
    }

    /**
     * The rows the two worked listings do **not** print follow them, in §5.3's own table order.
     *
     * The examples fix the order of what they print and say nothing about `p`, `fee`, `license` or
     * `deliver_by`. The rule for those is derived from [NenyaTags] — which `TagVocabularyTest` holds
     * equal to §5.3's table parsed out of the document — rather than chosen, and they come after
     * everything the examples print rather than being interleaved with it, which is the half the two
     * filtered comparisons on their own would not pin.
     */
    @JsName("the_rows_the_worked_listings_do_not_print_follow_them_in_s5_3_table_order")
    @Test
    fun `the rows the worked listings do not print follow them, in §5_3's table order`() {
        val printed = Section51.all().flatMap { example -> example.tags.map { it[0] } }.toSet()
        val tail = ListingWriter.TAG_ORDER.filter { it !in printed }
        val tableOrder = NenyaTags.ALL.map { it.name }.filter { it in tail }

        assertTrue(tail.isNotEmpty(), "the examples print every emittable row, so this proves nothing")
        assertEquals(tableOrder, tail, "§5.3's table order is what fixes the rows the examples omit")
        assertTrue(
            ListingWriter.TAG_ORDER.indexOfLast { it in printed } <
                ListingWriter.TAG_ORDER.indexOfFirst { it !in printed },
            "every row the worked listings print must precede every row they do not; interleaving " +
                "them would satisfy both filtered comparisons above while emitting a third order",
        )
    }

    /**
     * Every §5.3 row is either emittable or marked MUST NOT, and no row is both.
     *
     * The set equality is what stops a row added to §5.3 from being silently unemittable: `TagSet`
     * would read it, `Listing` would publish it, and this writer would drop it — producing an event
     * that is not the listing the caller asked for and whose id nothing could predict.
     */
    @JsName("every_s5_3_row_is_either_emittable_or_marked_must_not")
    @Test
    fun `every §5_3 row is either emittable or marked MUST NOT`() {
        val forbidden = Listing.forbiddenTags(NenyaKind.REQUEST)

        assertEquals(
            NenyaTags.NAMES,
            ListingWriter.TAG_ORDER.toSet() + forbidden,
            "§5.3's vocabulary must be exactly what this writer emits plus what §5.3 marks MUST NOT",
        )
        assertEquals(
            ListingWriter.TAG_ORDER.size,
            ListingWriter.TAG_ORDER.toSet().size,
            "a row named twice in the emission order would be emitted twice",
        )
        for (name in forbidden) {
            assertTrue(name !in ListingWriter.TAG_ORDER, "§5.3 marks `$name` MUST NOT; it is emittable")
        }
        assertEquals(
            forbidden,
            Listing.forbiddenTags(NenyaKind.OFFER),
            "§5.3's MUST NOT rows do not vary by listing kind",
        )
    }

    /** §4.5's `nenya` value is the document's major version, which both worked listings print. */
    @JsName("the_nenya_version_this_writer_emits_is_the_one_the_worked_listings_print")
    @Test
    fun `the nenya version this writer emits is the one the worked listings print`() {
        for (example in Section51.all()) {
            val tag = example.tags.single { it[0] == "nenya" }
            assertEquals(2, tag.size, "§4.5 encodes `nenya` as a name and a decimal major version")
            assertEquals(
                ListingWriter.NENYA_MAJOR_VERSION.toString(),
                tag[1],
                "\"${example.heading}\" in ${Section51.specPath()} prints a `nenya` version this " +
                    "writer does not emit",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The inverse of the round trip: the document's own listings.
    // ---------------------------------------------------------------------------------------------

    /**
     * §5.1's and §5.2's worked listings, decoded and rebuilt, reproduce the document's tag arrays
     * byte for byte.
     *
     * This is the control the generated round trip cannot be: that corpus is built by code in this
     * repository, so build → decode → build agreeing proves the writer is a fixed point of its own
     * decoder and nothing about whether either agrees with the document. Here the tags come from
     * `spec/NENYA-1.md`, and the only thing substituted is the author pubkey — the examples print the
     * placeholder `<provider-pubkey-hex>`, which §4.3 could never accept.
     */
    @JsName("each_worked_listing_rebuilt_by_the_writer_reproduces_the_documents_own_tags")
    @Test
    fun `each worked listing rebuilt by the writer reproduces the document's own tags`() {
        for (example in Section51.all()) {
            val original = ListingWriterFixtures.checkedExample(example)
            val decoded = Listing.decode(original)
            val rebuilt = ListingWriterFixtures.built(
                ListingWriterFixtures.build(example.kind, ListingWriterFixtures.reauthored(decoded)),
                "\"${example.heading}\" rebuilt from what the decode reports",
            )

            assertEquals(
                example.tags,
                rebuilt.tags,
                "the writer must reproduce the tag arrays \"${example.heading}\" prints in " +
                    "${Section51.specPath()}, element for element and in order",
            )
            ListingWriterFixtures.assertSameBytes(
                original.event,
                rebuilt,
                "\"${example.heading}\" rebuilt",
            )
        }
    }

    /** The three entry points write the three kinds, and the decoder reads each back as itself. */
    @JsName("the_three_entry_points_write_the_three_listing_kinds")
    @Test
    fun `the three entry points write the three listing kinds`() {
        val written = mutableListOf<Int>()
        for (kind in KINDS) {
            val event = ListingWriterFixtures.built(
                ListingWriterFixtures.build(kind, legal(kind)),
                "the minimal kind:$kind listing",
            )
            val decoded = ListingWriterFixtures.decode(event)

            assertEquals(kind, event.kind)
            assertEquals(kind, decoded.kind)
            assertEquals(ListingSide.requiredOn(kind), decoded.side, "§5.1 and §5.2 fix the side token")
            assertEquals(ListingStatus.ACTIVE, decoded.status, "§5.3: an absent `status` means active")
            assertNull(decoded.statusToken, "and the writer emitted no `status` tag for it")
            written += kind
        }
        assertEquals(NenyaKind.LISTING_KINDS, written.toSet(), "one entry point per listing kind")
    }

    // ---------------------------------------------------------------------------------------------
    // The negative controls, each asserting its reason.
    // ---------------------------------------------------------------------------------------------

    /**
     * §5.2 makes `alt` REQUIRED on a request and §5.1 leaves it a SHOULD on an offer, so the *same*
     * values are a refusal through one entry point and a legal listing through the other two.
     *
     * The requirement is derived from `Listing.requiredTags`, not from a rule written in the writer,
     * which is why the paired positive control is here: it is what shows the refusal is about the
     * kind rather than about the absence.
     */
    @JsName("a_request_with_no_alt_is_refused_naming_that_tag_and_the_same_values_are_a_legal_offer")
    @Test
    fun `a request with no alt is refused naming that tag, and the same values are a legal offer`() {
        val withoutAlt = AuthoredListing(
            authorPubkey = TagFixtures.pubkeyFor(0),
            createdAt = ListingWriterFixtures.CREATED_AT,
            dValue = "lighthouse-loop",
            title = "a title",
            price = Msat.ofSat(120_000L),
        )

        val refused = ListingWriterFixtures.refused(
            ListingWriter.request(withoutAlt),
            "a kind:30404 request carrying no `alt`",
        )
        assertEquals(ListingRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals("alt", refused.tag, "the refusal must name the row as data, not in its wording")
        assertTrue("alt" in Listing.requiredTags(NenyaKind.REQUEST), "and §5.3 must still require it")

        for (kind in listOf(NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)) {
            val event = ListingWriterFixtures.built(
                ListingWriterFixtures.build(kind, withoutAlt),
                "the same values as a kind:$kind offer",
            )
            assertNull(ListingWriterFixtures.decode(event).alt, "§5.1 leaves `alt` a SHOULD")
        }
    }

    /**
     * §5.1: an offer's `status` MUST be `active` or `sold` "and nothing else", because a third value
     * is non-conformant in every existing NIP-99 client — the distribution §5.1 exists to keep.
     *
     * Every token §5.2 adds for a request is tried, so a writer that closed the vocabulary against
     * one of the three would still be caught.
     */
    @JsName("an_offer_carrying_a_requests_status_value_is_refused_as_outside_its_vocabulary")
    @Test
    fun `an offer carrying a request's status value is refused as outside its vocabulary`() {
        assertTrue(REQUEST_ONLY_STATUSES.isNotEmpty(), "§5.2's extension must have tokens to try")

        for (status in REQUEST_ONLY_STATUSES) {
            for (kind in listOf(NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)) {
                val refused = ListingWriterFixtures.refused(
                    ListingWriterFixtures.build(kind, authored(kind, status = status)),
                    "a kind:$kind offer carrying `${status.token}`",
                )
                assertEquals(ListingRejection.STATUS_OUTSIDE_VOCABULARY, refused.reason, "$status")
                assertEquals("status", refused.tag)
            }
            // The paired positive control: §5.2 permits exactly these on a request.
            val event = ListingWriterFixtures.built(
                ListingWriter.request(authored(NenyaKind.REQUEST, status = status)),
                "a kind:30404 request carrying `${status.token}`",
            )
            assertEquals(status, ListingWriterFixtures.decode(event).status)
        }
    }

    /**
     * §5.2's `unknown` is the required **treatment** of somebody else's unrecognised token, not a
     * token: emitting one would republish a stranger's value as though this library had understood it.
     */
    @JsName("the_unknown_status_is_not_emittable_on_any_listing_kind")
    @Test
    fun `the unknown status is not emittable on any listing kind`() {
        assertNull(ListingStatus.UNKNOWN.token, "§5.2 gives `unknown` no token to emit")

        for (kind in KINDS) {
            val refused = ListingWriterFixtures.refused(
                ListingWriterFixtures.build(kind, authored(kind, status = ListingStatus.UNKNOWN)),
                "a kind:$kind listing carrying the unknown status",
            )
            assertEquals(ListingRejection.UNKNOWN_IS_NOT_EMITTABLE, refused.reason)
            assertEquals("status", refused.tag)
        }
    }

    /**
     * §5.3 marks `g` and `location` MUST NOT on a listing, and §12 item 5 gives the reason: a
     * location leak with no compensating function.
     *
     * The rows are **derived** from `Listing.forbiddenTags`, so a row §5.3 starts forbidding is tried
     * here without anybody editing this test. `item` has its own reason and its own control below.
     */
    @JsName("every_row_s5_3_marks_must_not_is_refused_outright")
    @Test
    fun `every row §5_3 marks MUST NOT is refused outright`() {
        val forbidden = Listing.forbiddenTags(NenyaKind.REQUEST).filter { it != "item" }
        assertTrue(forbidden.isNotEmpty(), "§5.3 must mark something other than `item` MUST NOT")

        for (name in forbidden) {
            for (kind in KINDS) {
                val refused = ListingWriterFixtures.refused(
                    ListingWriterFixtures.build(
                        kind,
                        authored(kind, extraTags = listOf(listOf(name, "something"))),
                    ),
                    "a kind:$kind listing carrying `$name`",
                )
                assertEquals(ListingRejection.FORBIDDEN_TAG, refused.reason, name)
                assertEquals(name, refused.tag)
            }
        }
    }

    /** §5.3: `item` is refused for the reason §5.3 gives — "a listing cannot reference itself". */
    @JsName("an_item_tag_is_refused_as_a_self_reference_rather_than_as_a_forbidden_tag")
    @Test
    fun `an item tag is refused as a self reference rather than as a forbidden tag`() {
        val coordinate = TagFixtures.coordinate(NenyaKind.OFFER, 0, "sdxl-portrait-commission")
        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(NenyaKind.OFFER, extraTags = listOf(listOf("item", coordinate, "1"))),
            ),
            "an offer referencing a listing",
        )

        assertEquals(ListingRejection.SELF_REFERENCE, refused.reason)
        assertEquals("item", refused.tag)
        assertNotEquals(
            ListingRejection.FORBIDDEN_TAG,
            refused.reason,
            "§5.3 gives `item` a structural reason and `g` a privacy one; a client that showed the " +
                "same message for both would hide that somebody published a self-referencing listing",
        )
    }

    /**
     * §4.4's supply bound on the way **out**, which `Msat` cannot enforce at construction: an
     * above-cap amount can only arrive by arithmetic, and `Msat.times` returns one.
     *
     * So the refusal is real rather than theoretical, and it is the one §4.4 names — a price above
     * the bitcoin supply is one every implementation MUST reject on the way back in, so emitting it
     * would put an unreadable listing on the board.
     */
    @JsName("a_price_above_the_supply_cap_is_refused_as_above_supply")
    @Test
    fun `a price above the supply cap is refused as above supply`() {
        val aboveCap = Msat.SUPPLY_CAP * 2L
        assertTrue(!aboveCap.isWithinSupply(), "the control needs an amount §4.4 refuses")

        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(authored(NenyaKind.OFFER, price = aboveCap)),
            "an offer priced above the bitcoin supply",
        )
        assertEquals(ListingRejection.MALFORMED_TAG, refused.reason)
        assertEquals("price", refused.tag)
        assertEquals(
            TagRejection.ABOVE_SUPPLY,
            refused.tagReason,
            "§4.4's reason must survive the writer rather than being flattened into `malformed`",
        )
        // And the cap itself is legal: the bound is `>` and not `>=`. Decoded as well as built, so the
        // one price the writer accepts at §4.4's bound is shown to be a price its decoder can read.
        val atCap = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, price = Msat.SUPPLY_CAP)),
            "an offer priced at exactly the supply cap",
        )
        assertEquals(Msat.SUPPLY_CAP, ListingWriterFixtures.decode(atCap).price)
    }

    /**
     * §4.4, "Never lossy": the public `price` tag is satoshi-denominated, so a millisatoshi amount
     * that does not land on a whole satoshi is refused rather than rounded.
     *
     * This is the writer's own refusal rather than the type's: `Msat.ofMsat(1_500)` is a perfectly
     * legal amount, and only the satoshi-denominated field makes it lossy.
     */
    @JsName("a_price_that_is_not_a_whole_satoshi_is_refused_as_lossy")
    @Test
    fun `a price that is not a whole satoshi is refused as lossy`() {
        val partSatoshi = Msat.ofMsat(1_500L)

        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(authored(NenyaKind.OFFER, price = partSatoshi)),
            "an offer priced at one and a half satoshis",
        )
        assertEquals(ListingRejection.MALFORMED_TAG, refused.reason)
        assertEquals("price", refused.tag)
        assertEquals(TagRejection.LOSSY, refused.tagReason, "§4.4 forbids rounding it")
    }

    /**
     * Every §5.3 Encoding-column refusal names **its own row**, which is why the rows are emitted one
     * at a time rather than inside one `try`.
     *
     * `d` is T40's named control — §5.3 says a `d` value MUST be non-empty, because it is the
     * addressability key §4.2 is built on — and the three optional timestamps are here beside it
     * because they reach the same catch by a different route from `created_at`, which is an event
     * field and has its own reason.
     */
    @JsName("every_s5_3_encoding_column_refusal_names_its_own_row")
    @Test
    fun `every §5_3 Encoding-column refusal names its own row`() {
        val empty = ListingWriterFixtures.refused(
            ListingWriter.offer(authored(NenyaKind.OFFER, dValue = "")),
            "an offer with an empty `d`",
        )
        assertEquals(ListingRejection.MALFORMED_TAG, empty.reason)
        assertEquals("d", empty.tag)
        assertEquals(TagRejection.EMPTY_VALUE, empty.tagReason)

        // §4.3 fixes a timestamp as a non-negative decimal integer, on every row that carries one.
        val negative = mapOf(
            "published_at" to authored(NenyaKind.OFFER, publishedAt = -1L),
            "expiration" to authored(NenyaKind.OFFER, expiration = -1L),
            "deliver_by" to authored(NenyaKind.OFFER, deliverBy = -1L),
        )
        for ((row, listing) in negative) {
            val refused = ListingWriterFixtures.refused(
                ListingWriter.offer(listing),
                "an offer whose `$row` is before 1970",
            )
            assertEquals(ListingRejection.MALFORMED_TAG, refused.reason, row)
            assertEquals(row, refused.tag, "the refusal must name the row, not merely the Encoding column")
            assertEquals(TagRejection.MALFORMED_TIMESTAMP, refused.tagReason, row)
        }
    }

    /**
     * A §5.3 row handed in as an extension is refused — but as a **duplicate** only when the listing
     * really would carry two of it.
     *
     * The distinction is the whole reason the extension check runs after the rows are assembled. A
     * `["summary", …]` extension on a listing whose `summary` parameter is `null` would produce an
     * event carrying exactly one `summary`, which `Listing.decode` accepts; calling that a duplicate
     * would be a refusal stating something untrue about the event it declined to make.
     */
    @JsName("a_s5_3_row_handed_in_as_an_extension_is_a_duplicate_only_when_it_really_would_be_one")
    @Test
    fun `a §5_3 row handed in as an extension is a duplicate only when it really would be one`() {
        val alsoEmitted = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(
                    NenyaKind.OFFER,
                    summary = "the parameter's summary",
                    extraTags = listOf(listOf("summary", "a summary")),
                ),
            ),
            "an offer whose `summary` is supplied twice",
        )
        assertEquals(ListingRejection.DUPLICATE_TAG, alsoEmitted.reason)
        assertEquals("summary", alsoEmitted.tag)

        val notEmitted = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(NenyaKind.OFFER, extraTags = listOf(listOf("summary", "a summary"))),
            ),
            "an offer whose only `summary` arrives as an extension",
        )
        assertEquals(
            ListingRejection.ROW_IS_NOT_AN_EXTENSION,
            notEmitted.reason,
            "one occurrence is not a duplicate, and §5.3's row is still not an extension",
        )
        assertEquals("summary", notEmitted.tag)

        // §4.3's duplicate rule reaches only a row §5.3 gives cardinality `1` or `0–1`. A second
        // `image` is legal on a listing — the decoder accepts an event carrying two — so the
        // extension is still refused, but calling it a duplicate would state something untrue about
        // the event this writer declined to make.
        val multiOccurrence = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(
                    NenyaKind.OFFER,
                    images = listOf(ImageRef("https://example.invalid/a.png")),
                    extraTags = listOf(listOf("image", "https://example.invalid/b.png")),
                ),
            ),
            "an offer whose second `image` arrives as an extension",
        )
        assertEquals(ListingRejection.ROW_IS_NOT_AN_EXTENSION, multiOccurrence.reason)
        assertEquals("image", multiOccurrence.tag)
        assertFalse(
            assertNotNull(NenyaTags.byName("image")).cardinality.singleOccurrence,
            "the reason above is only right while §5.3 gives `image` a multi-occurrence cardinality",
        )

        // A side token arriving as an extension is the same event as one arriving in `topics`, so it
        // must get the same reason. Two reasons for one event would be the writer disagreeing with
        // itself about which rule the author broke.
        val sideToken = ListingWriterFixtures.refused(
            ListingWriter.request(
                authored(NenyaKind.REQUEST, extraTags = listOf(listOf("t", "wts"))),
            ),
            "a request naming `wts` as an extension tag",
        )
        assertEquals(ListingRejection.MISSING_TOPIC, sideToken.reason)
        assertEquals("t", sideToken.tag)
        assertEquals(
            ListingWriterFixtures.refused(
                ListingWriter.request(authored(NenyaKind.REQUEST, topics = listOf("wts"))),
                "the same request naming `wts` as an extra topic",
            ).reason,
            sideToken.reason,
            "one event, one reason, whichever parameter the token arrived in",
        )
    }

    /**
     * A §5.3 row handed in as an extra tag is a second occurrence of a row the writer already emits,
     * and §4.3 requires rejecting that rather than resolving it by taking the first or the last.
     *
     * `title` is the case T40 names, and it is the dangerous one: two titles on a signed listing are
     * two prices away from two implementations disagreeing about what the publisher offered.
     */
    @JsName("a_second_title_tag_is_refused_as_a_duplicate")
    @Test
    fun `a second title tag is refused as a duplicate`() {
        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(NenyaKind.OFFER, extraTags = listOf(listOf("title", "a second title"))),
            ),
            "an offer carrying two `title` tags",
        )

        assertEquals(ListingRejection.DUPLICATE_TAG, refused.reason)
        assertEquals("title", refused.tag)
        // And the decoder refuses the same shape, which is what makes this a builder rule rather
        // than an invention: the writer refuses only what its own decoder would.
        val byHand = ListingWriterFixtures.minimal(NenyaKind.OFFER)
        val legal = ListingWriterFixtures.built(ListingWriter.offer(byHand), "the same offer alone")
        assertEquals(1, legal.tags.count { it[0] == "title" })
    }

    /**
     * §5.3 requires **exactly one** of `wtb` / `wts` on a listing, and the writer emits the one §5.1
     * or §5.2 fixes for the kind — so a second one named among the extra topics is refused.
     *
     * Both cases are tried on both sides, including the one that looks harmless: naming the side the
     * writer was going to emit anyway still produces two side tokens on the wire.
     *
     * The paired positive control is §4.5's discovery token, and it is the half that keeps this rule
     * honest: `nenya` named again **is** permitted, because §5.3 gives `t` cardinality `≥2`, §4.3's
     * duplicate rule does not reach it and the decoder accepts the event. A writer refusing that would
     * be refusing what its own decoder accepts, which T40 forbids.
     */
    @JsName("a_side_token_among_the_extra_topics_is_refused_and_a_second_nenya_is_not")
    @Test
    fun `a side token among the extra topics is refused, and a second nenya is not`() {
        for (kind in KINDS) {
            for (side in ListingSide.entries) {
                for (token in listOf(side.token, side.token.uppercase())) {
                    val refused = ListingWriterFixtures.refused(
                        ListingWriterFixtures.build(kind, authored(kind, topics = listOf(token))),
                        "a kind:$kind listing naming `$token` as an extra topic",
                    )
                    assertEquals(ListingRejection.MISSING_TOPIC, refused.reason, "$kind / $token")
                    assertEquals("t", refused.tag)
                }
            }

            val side = assertNotNull(ListingSide.requiredOn(kind))
            val event = ListingWriterFixtures.built(
                ListingWriterFixtures.build(kind, authored(kind, topics = listOf("nenya"))),
                "a kind:$kind listing naming `nenya` twice",
            )
            assertEquals(
                listOf("nenya", side.token, "nenya"),
                ListingWriterFixtures.decode(event).topics,
                "§5.3 gives `t` cardinality ≥2 and §4.3's duplicate rule does not reach it",
            )
        }
    }

    /**
     * §4.3's fifth resource bound: reject, never truncate — and the bound is the injected one,
     * because §4.3 says the bounds SHOULD be configurable.
     *
     * The bound is injected rather than defaulted so the control is cheap: proving the rule needs two
     * `image` tags against a bound of one, not sixty-five against sixty-four.
     */
    @JsName("more_image_tags_than_the_injected_bound_is_refused_rather_than_truncated")
    @Test
    fun `more image tags than the injected bound is refused rather than truncated`() {
        val images = List(2) { ImageRef("https://example.invalid/$it.png") }
        val listing = authored(NenyaKind.OFFER, images = images)

        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(listing, TagLimits(maxImageTags = 1)),
            "an offer carrying two `image` tags against a bound of one",
        )
        assertEquals(ListingRejection.LIMIT_EXCEEDED, refused.reason)
        assertEquals("image", refused.tag)

        // The paired positive control, and the half that shows nothing was truncated: under the
        // default bound both tags are emitted.
        val event = ListingWriterFixtures.built(
            ListingWriter.offer(listing),
            "the same offer under §4.3's default bound",
        )
        assertEquals(2, event.tags.count { it[0] == "image" })
    }

    /**
     * §4.1's and §4.3's **event fields** — not §5.3's tags — refused with their own reason.
     *
     * [ListingRejection.MALFORMED_EVENT_FIELD] rather than [ListingRejection.MALFORMED_TAG], because
     * a caller told their pubkey is a malformed tag goes looking through §5.3's table for a row that
     * is not there.
     */
    @JsName("a_malformed_author_pubkey_and_a_negative_created_at_are_refused_as_event_fields")
    @Test
    fun `a malformed author pubkey and a negative created_at are refused as event fields`() {
        val shortPubkey = TagFixtures.pubkeyFor(0).dropLast(1)
        val notHex = TagFixtures.pubkeyFor(0).dropLast(1) + "z"

        for (pubkey in listOf("", shortPubkey, notHex)) {
            val refused = ListingWriterFixtures.refused(
                ListingWriter.offer(authored(NenyaKind.OFFER, authorPubkey = pubkey)),
                "an offer authored by a pubkey of ${pubkey.length} characters",
            )
            assertEquals(ListingRejection.MALFORMED_EVENT_FIELD, refused.reason)
            assertNull(refused.tag, "§4.1's five id-bearing fields carry no tag")
        }

        val negative = ListingWriterFixtures.refused(
            ListingWriter.offer(authored(NenyaKind.OFFER, createdAt = -1L)),
            "an offer created before 1970",
        )
        assertEquals(ListingRejection.MALFORMED_EVENT_FIELD, negative.reason)
    }

    // ---------------------------------------------------------------------------------------------
    // The distinctions a writer collapses by accident.
    // ---------------------------------------------------------------------------------------------

    /**
     * §8.1: an absent `fee` term and a stated `["fee", "0"]` agree on the number and disagree on the
     * wire, because the second is "itself a signed statement" that no fee applies.
     *
     * A writer that emitted the stated form for both — or neither for both — would put a term on the
     * wire the proposer never signed, and §8.4 requires that pair be reproduced byte-identically at
     * four separate points and aborts the order on any divergence.
     */
    @JsName("a_stated_zero_fee_and_an_absent_fee_term_are_different_events")
    @Test
    fun `a stated zero fee and an absent fee term are different events`() {
        val absent = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, fee = FeeTerm.Absent)),
            "an offer with no fee term",
        )
        val statedZero = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, fee = FeeTerm.of(0))),
            "an offer with a stated zero fee",
        )

        assertEquals(0, absent.tags.count { it[0] == "fee" }, "§8.1: an absent term has no wire form")
        assertEquals(listOf("fee", "0"), statedZero.tags.single { it[0] == "fee" })
        assertNotEquals(absent.canonicalSerialisation(), statedZero.canonicalSerialisation())
        assertEquals(FeeTerm.Absent, ListingWriterFixtures.decode(absent).fee)
        assertEquals(FeeTerm.of(0), ListingWriterFixtures.decode(statedZero).fee)
    }

    /** §8.1 makes the recipient part of the term, so one without a term has nothing to belong to. */
    @JsName("a_fee_recipient_with_no_fee_term_is_refused")
    @Test
    fun `a fee recipient with no fee term is refused`() {
        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(
                authored(
                    NenyaKind.OFFER,
                    fee = FeeTerm.Absent,
                    feeRecipient = TagFixtures.pubkeyFor(7),
                ),
            ),
            "an offer naming a fee recipient with no fee term",
        )

        assertEquals(ListingRejection.MALFORMED_TAG, refused.reason)
        assertEquals("fee", refused.tag)
        assertEquals(TagRejection.WRONG_ARITY, refused.tagReason)
    }

    /**
     * §5.3: an absent `status` **means** `active`, so the two are different events with different ids.
     *
     * The distinction is the one the round trip would otherwise hide: `Listing.status` answers
     * [ListingStatus.ACTIVE] for both, and a reconstruction reading it rather than
     * `Listing.statusToken` would turn every un-statused listing into a statused one.
     */
    @JsName("an_absent_status_and_a_stated_active_status_are_different_events")
    @Test
    fun `an absent status and a stated active status are different events`() {
        val absent = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, status = null)),
            "an offer with no status tag",
        )
        val stated = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, status = ListingStatus.ACTIVE)),
            "an offer stating `active`",
        )

        assertEquals(0, absent.tags.count { it[0] == "status" })
        assertEquals(listOf("status", "active"), stated.tags.single { it[0] == "status" })
        assertNotEquals(absent.canonicalSerialisation(), stated.canonicalSerialisation())

        val decodedAbsent = ListingWriterFixtures.decode(absent)
        val decodedStated = ListingWriterFixtures.decode(stated)
        assertEquals(ListingStatus.ACTIVE, decodedAbsent.status, "§5.3: absence means active")
        assertEquals(ListingStatus.ACTIVE, decodedStated.status)
        assertNull(decodedAbsent.statusToken)
        assertEquals("active", decodedStated.statusToken)
    }

    /**
     * §4.3: an implementation MUST NOT drop tags it does not understand, and the writer emits them
     * last so a client's extension can never displace a §5.3 row.
     */
    @JsName("extra_tags_are_emitted_verbatim_after_every_s5_3_row")
    @Test
    fun `extra tags are emitted verbatim after every §5_3 row`() {
        val extras = listOf(
            listOf("client", "nenya-test"),
            listOf("x-nenya-experiment"),
            listOf("relays", "wss://relay.example.invalid", "wss://other.example.invalid"),
        )
        val event = ListingWriterFixtures.built(
            ListingWriter.offer(authored(NenyaKind.OFFER, extraTags = extras)),
            "an offer carrying three extensions",
        )

        assertEquals(extras, event.tags.takeLast(extras.size), "§4.3: verbatim, in order, and last")
        assertEquals(extras, ListingWriterFixtures.decode(event).unknownTags)
        assertTrue(
            event.tags.dropLast(extras.size).all { it[0] in ListingWriter.TAG_ORDER },
            "every tag before the extensions must be a §5.3 row",
        )
    }

    /** §4.3 requires rejecting an event carrying an empty tag array; the writer refuses to emit one. */
    @JsName("an_empty_extra_tag_is_refused")
    @Test
    fun `an empty extra tag is refused`() {
        val refused = ListingWriterFixtures.refused(
            ListingWriter.offer(authored(NenyaKind.OFFER, extraTags = listOf(emptyList<String>()))),
            "an offer carrying an empty tag array",
        )

        assertEquals(ListingRejection.MALFORMED_TAG, refused.reason)
    }

    /**
     * One legal listing of each kind carrying every optional row at once, so the writer's widest
     * output is decoded rather than only its narrowest.
     */
    @JsName("a_listing_carrying_every_optional_row_decodes_to_the_values_it_was_built_from")
    @Test
    fun `a listing carrying every optional row decodes to the values it was built from`() {
        for (kind in KINDS) {
            val listing = AuthoredListing(
                authorPubkey = TagFixtures.pubkeyFor(1),
                createdAt = ListingWriterFixtures.CREATED_AT,
                dValue = "every-row",
                title = "a title",
                price = Msat.ofSat(50_000L),
                content = "a description",
                summary = "a summary",
                publishedAt = ListingWriterFixtures.CREATED_AT - 1_000L,
                status = ListingStatus.ACTIVE,
                mimeType = "image/png",
                topics = listOf("Ai-Image"),
                images = listOf(ImageRef("https://example.invalid/a.png", "64x64")),
                expiration = ListingWriterFixtures.CREATED_AT + 1_000L,
                alt = "a fallback rendering",
                pubkeyRefs = listOf(PubkeyRef(TagFixtures.pubkeyFor(2))),
                fee = FeeTerm.of(250),
                feeRecipient = TagFixtures.pubkeyFor(3),
                license = "cc-by-4.0",
                deliverBy = ListingWriterFixtures.CREATED_AT + 2_000L,
                extraTags = listOf(listOf("client", "nenya-test")),
            )

            val event = ListingWriterFixtures.built(
                ListingWriterFixtures.build(kind, listing),
                "a kind:$kind listing carrying every optional row",
            )
            val decoded = ListingWriterFixtures.decode(event)

            assertEquals(listing.dValue, decoded.dValue)
            assertEquals(listing.title, decoded.title)
            assertEquals(listing.price, decoded.price)
            assertEquals(listing.summary, decoded.summary)
            assertEquals(listing.publishedAt, decoded.publishedAt)
            assertEquals(listing.mimeType, decoded.mimeType)
            assertEquals(listing.expiration, decoded.expiration)
            assertEquals(listing.alt, decoded.alt)
            assertEquals(listing.license, decoded.license)
            assertEquals(listing.deliverBy, decoded.deliverBy)
            assertEquals(listing.fee, decoded.fee)
            assertEquals(listing.feeRecipient, decoded.feeRecipient)
            assertEquals(listing.images, decoded.images)
            assertEquals(listing.pubkeyRefs, decoded.pubkeyRefs)
            assertEquals(listing.extraTags, decoded.unknownTags)
            val side = assertNotNull(ListingSide.requiredOn(kind), "§5.1 and §5.2 fix a side per kind")
            assertEquals(
                listOf("nenya", side.token, "ai-image"),
                decoded.topics,
                "§5.3 requires lowercasing a `t` token on write; a relay tag index is byte-exact",
            )
            // Every emittable row appears, so this really is the widest output.
            assertEquals(
                ListingWriter.TAG_ORDER.toSet(),
                event.tags.map { it[0] }.toSet() - setOf("client"),
                "a row missing here is a row no test in this file emits",
            )
        }
    }

    /**
     * The minimal listing of each kind carries exactly §5.3's MUST rows and nothing else.
     *
     * The required set is derived from `Listing.requiredTags`, so this is a statement about §5.3's
     * table rather than about a list in this file.
     */
    @JsName("the_minimal_listing_of_each_kind_carries_exactly_the_rows_s5_3_marks_must")
    @Test
    fun `the minimal listing of each kind carries exactly the rows §5_3 marks MUST`() {
        for (kind in KINDS) {
            val event = ListingWriterFixtures.built(
                ListingWriterFixtures.build(kind, legal(kind)),
                "the minimal kind:$kind listing",
            )

            assertEquals(
                Listing.requiredTags(kind).toSet(),
                event.tags.map { it[0] }.toSet(),
                "the minimal kind:$kind listing must carry §5.3's MUST rows and no other",
            )
            assertEquals(
                2,
                event.tags.count { it[0] == "t" },
                "§5.3 marks `t` cardinality ≥2: `nenya` and this kind's side token",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    /**
     * A legal listing of [kind] with one value replaced — the shape every negative control above
     * takes, so each differs from a passing listing in exactly the thing it is about.
     */
    private fun authored(
        kind: Int,
        authorPubkey: String = TagFixtures.pubkeyFor(0),
        createdAt: Long = ListingWriterFixtures.CREATED_AT,
        dValue: String = "sdxl-portrait-commission",
        price: Msat = Msat.ofSat(50_000L),
        summary: String? = null,
        publishedAt: Long? = null,
        status: ListingStatus? = null,
        topics: List<String> = emptyList(),
        images: List<ImageRef> = emptyList(),
        expiration: Long? = null,
        fee: FeeTerm = FeeTerm.Absent,
        feeRecipient: String? = null,
        deliverBy: Long? = null,
        extraTags: List<List<String>> = emptyList(),
    ): AuthoredListing = AuthoredListing(
        authorPubkey = authorPubkey,
        createdAt = createdAt,
        dValue = dValue,
        title = "Custom SDXL portrait, 1024x1024",
        price = price,
        summary = summary,
        publishedAt = publishedAt,
        status = status,
        topics = topics,
        images = images,
        expiration = expiration,
        // MUST on a request (§5.2), so every control reaches the rule it is about rather than this one.
        alt = if (kind == NenyaKind.REQUEST) "a fallback rendering" else null,
        fee = fee,
        feeRecipient = feeRecipient,
        deliverBy = deliverBy,
        extraTags = extraTags,
    )
}
