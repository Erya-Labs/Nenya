package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.listing.BoardQuery
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T53's crossing, [matchesBoard], held equal to `BoardQuery.matches` over the writer's seeded corpus
 * and a set of queries drawn from that corpus's own values, with the kinds crossing as numbers.
 */
class JsBoardQueryEqualityTest {

    private companion object {

        val corpus: List<Listing> by lazy {
            ListingWriterFixtures.cases(30).map { case ->
                ListingWriterFixtures.decode(
                    ListingWriterFixtures.built(
                        ListingWriterFixtures.build(case.kind, case.listing),
                        "corpus listing ${case.index}",
                    ),
                )
            }
        }

        /** Each query once as Kotlin and once as the page's plain object. */
        fun queries(): List<Pair<BoardQuery, JsBoardQuery>> = buildList {
            val kindSets = listOf(emptyList(), listOf(NenyaKind.OFFER), ListingWriterFixtures.KINDS)
            for (seed in corpus.take(10)) {
                val topicSets = listOf(emptyList(), listOf("nenya"), seed.topics, seed.topics.map { it.uppercase() })
                for (kinds in kindSets) {
                    for (topics in topicSets) {
                        for (mime in listOf(null, seed.mimeType)) {
                            for (directed in listOf(null, seed.pubkeyRefs.firstOrNull()?.pubkey)) {
                                add(
                                    BoardQuery(kinds.toSet(), topics.toSet(), mime, directed) to
                                        JsBoardQuery(kinds.toTypedArray(), topics.toTypedArray(), mime, directed),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @JsName("matches_board_answers_what_board_query_matches_answers")
    @Test
    fun `matchesBoard answers what BoardQuery matches answers`() {
        var matched = 0
        var refused = 0
        for ((query, page) in queries()) {
            for ((index, listing) in corpus.withIndex()) {
                val expected = query.matches(listing)
                assertEquals(expected, matchesBoard(JsListing(listing), page), "listing $index under $query")
                if (expected) matched++ else refused++
            }
        }
        assertTrue(matched > 0 && refused > 0, "non-vacuity: $matched matched, $refused refused")
    }

    @JsName("the_or_trap_is_refused_across_the_boundary_too")
    @Test
    fun `the OR trap is refused across the boundary too`() {
        val offer = JsListing(ListingFixtures.decode(ListingFixtures.minimalOffer(), NenyaKind.OFFER))
        val request = JsListing(ListingFixtures.decode(ListingFixtures.minimalRequest(), NenyaKind.REQUEST))
        val query = JsBoardQuery(arrayOf(NenyaKind.OFFER, NenyaKind.REQUEST), arrayOf("nenya", "request"))

        assertFalse(matchesBoard(offer, query), "an offer tagged nenya + wts")
        assertTrue(matchesBoard(request, query))
        assertFalse(matchesBoard(request, JsBoardQuery(emptyArray())), "no kind is a query for nothing")
    }

    @JsName("a_listing_directed_at_another_pubkey_is_refused_across_the_boundary")
    @Test
    fun `a listing directed at another pubkey is refused across the boundary`() {
        val tags = ListingFixtures.minimalRequest()
        tags += listOf(listOf("p", TagFixtures.pubkeyFor(92)))
        val listing = JsListing(ListingFixtures.decode(tags, NenyaKind.REQUEST))
        val kinds = arrayOf(NenyaKind.REQUEST)

        assertFalse(matchesBoard(listing, JsBoardQuery(kinds, directedTo = TagFixtures.pubkeyFor(91))))
        assertTrue(matchesBoard(listing, JsBoardQuery(kinds, directedTo = TagFixtures.pubkeyFor(92))))
        assertEquals("JsBoardQuery(kinds=1, topics=0)", JsBoardQuery(kinds).toString())
    }
}
