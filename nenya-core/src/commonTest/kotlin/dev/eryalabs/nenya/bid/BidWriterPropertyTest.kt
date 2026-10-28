package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagSet
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision **P**'s proof for §6, over a generated corpus: **build → `Bid.decode` → `encode()` → the
 * same bytes**, and a rebuild from what the decode reports landing on the same bytes again.
 *
 * Two comparisons, because they catch different things. `encode()` re-publishes the decoded event
 * verbatim (§4.3), so `encode() == built` proves the decoder accepted the writer's output whole and
 * dropped nothing — a writer emitting a tag the decoder cannot hold would fail it. The rebuild from
 * [BidWriterFixtures.reauthored] proves the decode *reports* enough to author the same bid again — a
 * writer emitting something the decode cannot see would fail that one.
 *
 * ### What a round trip cannot catch, said out loud
 *
 * A symmetric error is a fixed point of it: the reconstruction drops the writer's own `["t", "nenya"]`
 * by position, so a writer that emitted it last would rebuild itself byte for byte. So its position is
 * asserted **positively** below, and the emission order as a whole is held to `spec/NENYA-1.md` by
 * `BidWriterTest` — where one side of the comparison is the document rather than this writer.
 *
 * ### The non-vacuity floors
 *
 * Every case is built and decoded (a refused corpus round-trips trivially); every optional row is drawn
 * present and absent; both relay hints, all three fee cases and all three listing kinds are reached;
 * and the corpus is that many distinct events, not one repeated.
 */
class BidWriterPropertyTest {

    private companion object {

        /** 600 bids, the size `ListingWriterPropertyTest` runs its own corpus at. */
        const val CORPUS: Int = 600
    }

    @JsName("every_built_bid_decodes_encodes_and_rebuilds_to_the_identical_event")
    @Test
    fun `every built bid decodes, encodes and rebuilds to the identical event`() {
        val cases = BidWriterFixtures.cases(CORPUS)
        assertEquals(CORPUS, cases.size)

        var built = 0
        val serialisations = mutableSetOf<String>()
        for (case in cases) {
            val what = "case ${case.index}"
            val first = BidWriterFixtures.built(BidWriter.bid(case.bid), what)
            val decoded = BidWriterFixtures.decode(first)

            assertEquals(NenyaKind.PUBLIC_BID, first.kind, what)
            assertEquals(case.bid.listing, decoded.listing, "$what: the scope tags name the coordinate")
            assertEquals(
                TagSet.TOPIC_NENYA,
                decoded.topics.first(),
                "$what: §6's `t=nenya` comes first — the reconstruction below drops exactly that one",
            )
            BidWriterFixtures.assertSameBytes(first, decoded.encode(), "$what: encode() of the decode")

            val second = BidWriterFixtures.built(
                BidWriter.bid(BidWriterFixtures.reauthored(decoded)),
                "$what rebuilt from what the decode reports",
            )
            BidWriterFixtures.assertSameBytes(first, second, "$what: the rebuild")

            built++
            serialisations += first.canonicalSerialisation()
        }

        assertEquals(CORPUS, built, "a corpus the writer refused would round-trip vacuously")
        assertEquals(CORPUS, serialisations.size, "the corpus must be $CORPUS different bids")
    }

    /** Every optional row is emitted by some case and omitted by another. */
    @JsName("the_bid_corpus_draws_every_optional_row_present_and_absent")
    @Test
    fun `the bid corpus draws every optional row present and absent`() {
        val rows = BidWriterFixtures.OPTIONAL_ROWS
        assertTrue(rows.size >= 10, "the optional rows are the writer's, and it has twelve: $rows")
        val present = mutableSetOf<String>()
        val absent = mutableSetOf<String>()
        for (case in BidWriterFixtures.cases(CORPUS)) {
            val names = BidWriterFixtures.built(BidWriter.bid(case.bid), "case ${case.index}").tags.map { it[0] }.toSet()
            for (row in rows) if (row in names) present += row else absent += row
        }
        assertEquals(rows.toSet(), present, "these optional rows are never emitted by the corpus")
        assertEquals(rows.toSet(), absent, "these optional rows are always emitted")
    }

    /**
     * The shapes a writer collapses by accident are each reached: both relay-hint arities on both
     * pairs, §8.1's absent, stated-zero and recipient-bearing `fee`, both `image` arities, a colon in
     * the coordinate's `d`, mixed-case topics, extensions, and all three listing kinds.
     */
    @JsName("the_bid_corpus_reaches_both_hint_arities_every_fee_case_and_every_listing_kind")
    @Test
    fun `the bid corpus reaches both hint arities, every fee case and every listing kind`() {
        val hintArities = mutableSetOf<Pair<String, Int>>()
        var absentFee = 0
        var statedZeroFee = 0
        var feeWithRecipient = 0
        val imageArities = mutableSetOf<Int>()
        var colonInD = 0
        var mixedCase = 0
        var extensions = 0
        val kinds = mutableSetOf<Int>()

        for (case in BidWriterFixtures.cases(CORPUS)) {
            val event = BidWriterFixtures.built(BidWriter.bid(case.bid), "case ${case.index}")
            for (tag in event.tags) {
                if (tag[0] in BidVocabulary.SINGLE_OCCURRENCE && tag[0] !in listOf("K", "k")) {
                    hintArities += tag[0] to tag.size
                }
                if (tag[0] == "image") imageArities += tag.size
            }
            when (event.tags.singleOrNull { it[0] == "fee" }?.size) {
                null -> absentFee++
                2 -> statedZeroFee++
                else -> feeWithRecipient++
            }
            if (':' in case.bid.listing.dValue) colonInD++
            if (case.bid.topics.any { it != it.lowercase() }) mixedCase++
            if (case.bid.extraTags.isNotEmpty()) extensions++
            kinds += case.bid.listing.kind
        }

        for (name in listOf("A", "P", "a", "p")) {
            assertTrue(name to 2 in hintArities, "`$name` was never emitted without its relay hint")
            assertTrue(name to 3 in hintArities, "`$name` was never emitted with its relay hint")
        }
        assertTrue(absentFee > 0, "§8.1's absent term was never drawn")
        assertTrue(statedZeroFee > 0, "§8.1's stated two-element `fee` was never drawn")
        assertTrue(feeWithRecipient > 0, "§8.1's three-element `fee` was never drawn")
        assertEquals(setOf(2, 3), imageArities, "both `image` arities")
        assertTrue(colonInD > 0, "§4.2's colon inside a `d` value was never reached")
        assertTrue(mixedCase > 0, "§5.3's lowercase-on-write rule was never exercised")
        assertTrue(extensions > 0, "§4.3's unknown-tag preservation was never exercised")
        assertEquals(NenyaKind.LISTING_KINDS, kinds, "§6: requests, and offers as counter-offers")
    }
}
