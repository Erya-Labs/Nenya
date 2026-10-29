package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T53: §5.5's client-side re-filter. A relay ORs the values of one tag filter, so [BoardQuery.matches]
 * is the conjunction a client redoes after receipt — every condition, or no match.
 *
 * The corpus is `ListingWriterFixtures`' seeded run, decoded, so the queries are built from values
 * the listings actually carry and nothing is typed out as a pubkey.
 */
class BoardQueryTest {

    private companion object {

        const val CORPUS: Int = 60

        val ALL_KINDS: Set<Int> = ListingWriterFixtures.KINDS.toSet()

        val corpus: List<Listing> by lazy {
            ListingWriterFixtures.cases(CORPUS).map { case ->
                ListingWriterFixtures.decode(
                    ListingWriterFixtures.built(
                        ListingWriterFixtures.build(case.kind, case.listing),
                        "corpus listing ${case.index}",
                    ),
                )
            }
        }

        /** The query naming every value [listing] carries that this query can name. */
        fun ownQuery(listing: Listing): BoardQuery = BoardQuery(
            kinds = setOf(listing.kind),
            topics = listing.topics.toSet(),
            mimeType = listing.mimeType,
            directedTo = listing.pubkeyRefs.firstOrNull()?.pubkey,
        )

        /**
         * Queries built up one condition at a time from [seed]'s values: each one is the previous one
         * plus one condition, so each must match a subset of what the previous one matched.
         */
        fun narrowingChain(seed: Listing): List<BoardQuery> {
            val chain = mutableListOf(BoardQuery(ALL_KINDS))
            var topics = emptySet<String>()
            for (topic in seed.topics) {
                topics = topics + topic
                chain += BoardQuery(ALL_KINDS, topics)
            }
            var last = BoardQuery(ALL_KINDS, topics)
            if (seed.mimeType != null) {
                last = BoardQuery(ALL_KINDS, topics, seed.mimeType)
                chain += last
            }
            val directed = seed.pubkeyRefs.firstOrNull()?.pubkey
            if (directed != null) {
                last = BoardQuery(ALL_KINDS, topics, last.mimeType, directed)
                chain += last
            }
            chain += BoardQuery(setOf(seed.kind), topics, last.mimeType, last.directedTo)
            return chain
        }

        /** A §5.1 offer or §5.2 request decoded from raw tags, so a `t` token may arrive uppercase. */
        fun raw(kind: Int, vararg extra: List<String>, index: Int = 0): Listing {
            val tags = ListingFixtures.minimal(kind)
            tags += extra.toList()
            return ListingFixtures.decode(tags, kind, index = index)
        }
    }

    @JsName("a_query_naming_a_listing_s_own_values_matches_it")
    @Test
    fun `a query naming a listing's own values matches it`() {
        var directed = 0
        var withMime = 0
        for ((index, listing) in corpus.withIndex()) {
            val query = ownQuery(listing)
            if (query.directedTo != null) directed++
            if (query.mimeType != null) withMime++
            assertTrue(query.matches(listing), "corpus listing $index must match its own values")
        }
        assertTrue(directed > 0 && withMime > 0, "non-vacuity: $directed directed, $withMime with `m`")
    }

    @JsName("adding_a_condition_never_widens_the_match_set")
    @Test
    fun `adding a condition never widens the match set`() {
        var narrowed = 0
        for (seed in corpus) {
            val chain = narrowingChain(seed)
            for (step in 1 until chain.size) {
                val before = corpus.filter { chain[step - 1].matches(it) }.toSet()
                val after = corpus.filter { chain[step].matches(it) }.toSet()
                assertTrue(
                    before.containsAll(after),
                    "step $step of the chain from ${seed.coordinate.dValue.length}-char d widened the match set",
                )
                if (after.size < before.size) narrowed++
            }
            assertTrue(chain.last().matches(seed), "the fully narrowed query still matches its seed")
        }
        assertTrue(narrowed > 0, "non-vacuity: no step narrowed anything, so the chain tests nothing")
    }

    @JsName("an_offer_tagged_nenya_and_wts_is_refused_by_topics_nenya_and_request")
    @Test
    fun `an offer tagged nenya and wts is refused by topics nenya and request`() {
        val offer = raw(NenyaKind.OFFER)
        val query = BoardQuery(ALL_KINDS, setOf("nenya", "request"))

        assertEquals(listOf("nenya", "wts"), offer.topics, "the offer carries exactly §5.1's two tokens")
        assertFalse(query.matches(offer), "§5.5: a relay's OR is not a client's AND")
        assertTrue(query.matches(raw(NenyaKind.REQUEST)), "and the request it was asked for matches")

        // The OR trap itself: the relay's reading of the same filter, which this query must not be.
        val relayReading = query.topics.any { it in offer.tags.normalisedTopics }
        assertTrue(relayReading, "a predicate using `any` over the topics admits the offer")
    }

    @JsName("a_listing_directed_at_another_pubkey_is_refused")
    @Test
    fun `a listing directed at another pubkey is refused`() {
        val me = TagFixtures.pubkeyFor(91)
        val other = TagFixtures.pubkeyFor(92)
        val directedAtOther = raw(NenyaKind.REQUEST, listOf("p", other))
        val directedAtMe = raw(NenyaKind.REQUEST, listOf("p", other), listOf("p", me))
        val undirected = raw(NenyaKind.REQUEST)

        assertFalse(BoardQuery(ALL_KINDS, directedTo = me).matches(directedAtOther))
        assertFalse(BoardQuery(ALL_KINDS, directedTo = me).matches(undirected), "no `p` is not directed at me")
        assertTrue(BoardQuery(ALL_KINDS, directedTo = me).matches(directedAtMe), "any one of the `p` tags")
        assertTrue(BoardQuery(ALL_KINDS, directedTo = me.uppercase()).matches(directedAtMe), "A–F lowered as on read")
    }

    @JsName("a_directed_to_that_is_not_a_pubkey_matches_nothing_and_does_not_throw")
    @Test
    fun `a directedTo that is not a pubkey matches nothing and does not throw`() {
        val directed = raw(NenyaKind.REQUEST, listOf("p", TagFixtures.pubkeyFor(91)))
        for (text in listOf("", "not hex", TagFixtures.pubkeyFor(91).drop(1), TagFixtures.pubkeyFor(91) + "0")) {
            assertFalse(BoardQuery(ALL_KINDS, directedTo = text).matches(directed), "'${text.length} chars'")
        }
    }

    @JsName("empty_kinds_matches_nothing_and_empty_topics_adds_no_condition")
    @Test
    fun `empty kinds matches nothing and empty topics adds no condition`() {
        val nothing = BoardQuery(emptySet(), setOf("nenya"))
        assertTrue(corpus.none { nothing.matches(it) }, "an empty kinds set is a query for nothing")

        val everything = BoardQuery(ALL_KINDS)
        assertTrue(corpus.all { everything.matches(it) }, "every listing kind, no further condition")

        val offers = BoardQuery(setOf(NenyaKind.OFFER))
        for (listing in corpus) {
            assertEquals(listing.kind == NenyaKind.OFFER, offers.matches(listing), "kind ${listing.kind}")
        }
    }

    @JsName("topics_compare_case_insensitively_in_both_directions")
    @Test
    fun `topics compare case-insensitively in both directions`() {
        val listing = raw(NenyaKind.REQUEST, listOf("t", "Animation"))

        assertTrue(BoardQuery(ALL_KINDS, setOf("animation")).matches(listing), "an uppercase token on the wire")
        assertTrue(BoardQuery(ALL_KINDS, setOf("NENYA", "Request", "ANIMATION")).matches(listing), "in the query")
        assertFalse(BoardQuery(ALL_KINDS, setOf("animation", "video")).matches(listing), "every topic, not one")
    }

    @JsName("a_mime_type_compares_byte_for_byte")
    @Test
    fun `a mime type compares byte for byte`() {
        val png = raw(NenyaKind.OFFER, listOf("m", "image/png"))

        assertTrue(BoardQuery(ALL_KINDS, mimeType = "image/png").matches(png))
        assertFalse(BoardQuery(ALL_KINDS, mimeType = "IMAGE/PNG").matches(png))
        assertFalse(BoardQuery(ALL_KINDS, mimeType = "image/png ").matches(png))
        assertFalse(BoardQuery(ALL_KINDS, mimeType = "image/png").matches(raw(NenyaKind.OFFER)), "no `m` at all")
    }

    @JsName("neither_status_nor_expiration_is_consulted")
    @Test
    fun `neither status nor expiration is consulted`() {
        val soldAndExpired = raw(NenyaKind.OFFER, listOf("status", "sold"), listOf("expiration", "1"))

        assertEquals(ListingStatus.SOLD, soldAndExpired.status)
        assertEquals(ListingActivity.EXPIRED, soldAndExpired.activity(FakeClock(2L)))
        assertTrue(BoardQuery(setOf(NenyaKind.OFFER), setOf("wts")).matches(soldAndExpired), "§5.6's question, not §5.5's")
    }

    @JsName("the_query_holds_copies_of_the_caller_s_collections")
    @Test
    fun `the query holds copies of the caller's collections`() {
        val kinds = mutableSetOf(NenyaKind.REQUEST)
        val topics = mutableSetOf("nenya")
        val query = BoardQuery(kinds, topics)
        kinds.clear()
        topics += "video"

        assertTrue(query.matches(raw(NenyaKind.REQUEST)), "a caller's later edit reaches no query")
        assertEquals(setOf(NenyaKind.REQUEST), query.kinds)
        assertEquals(setOf("nenya"), query.topics)
        assertEquals("BoardQuery(kinds=1, topics=1, mimeType=false, directedTo=false)", query.toString())
    }
}
