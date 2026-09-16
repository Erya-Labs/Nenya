package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of `HostileInputSweepTest` that is common Kotlin: the corpus floors. [HostileCorpus]
 * carries every mutation kind, reproducibly; its inputs reach the tag, listing and bid codecs and
 * round-trip through them; and every mutation reaches a decoder on every listing kind.
 *
 * Abstract, and run as `HostileInputSweepTest` on every target, so every test keeps its name. The
 * sweep itself, its harness control and its completeness check recognise a named rejection and
 * enumerate entry points through `java.lang.reflect`, and stay in the JVM `HostileInputSweepTest`,
 * whose KDoc gives the rule.
 */
abstract class PortableHostileInputSweepTest {

    protected companion object {

        /** Thirteen mutation kinds, so each is drawn a hundred times. */
        const val CORPUS: Int = 1_300
    }

    /** One corpus entry, plus whatever of it survived the layers below the entry point. */
    internal class Context(
        val input: HostileCorpus.Input,
        val event: WireEvent?,
        val checked: CheckedEvent?,
        val tagSet: TagSet?,
    ) {

        /** The four hostile strings, so a String-taking door sees shapes it was not written for. */
        val strings: List<String>
            get() = listOf(input.text, input.hex, input.coordinate, input.decimal)
    }

    /** As much of the layer stack as this input survives — the rest of the targets skip it. */
    internal fun contextOf(input: HostileCorpus.Input): Context {
        val event = runCatching { build(input) }.getOrNull()
        val checked = event?.let {
            runCatching { CheckedEvent.checkEventId(EventId.of(it).toHex(), it) }.getOrNull()
        }
        val tagSet = checked?.let {
            runCatching { TagSet.read(it, contextFor(input.kind)) }.getOrNull()
        }
        return Context(input, event, checked, tagSet)
    }

    internal fun build(input: HostileCorpus.Input): WireEvent = WireEvent(
        pubkey = input.pubkey,
        createdAt = input.createdAt,
        kind = input.kind,
        tags = input.tags,
        content = input.content,
    )

    /** §5.3's "a shared tag validator MUST take the kind" — the context the event's kind names. */
    internal fun contextFor(kind: Int): TagContext = when (kind) {
        NenyaKind.PUBLIC_BID -> TagContext.publicBid()
        NenyaKind.OFFER, NenyaKind.OFFER_DRAFT, NenyaKind.REQUEST -> TagContext.listing(kind)
        else -> TagContext.rumor(kind)
    }

    /** The corpus floor: non-empty, and every mutation kind actually drawn. */
    @JsName("the_corpus_carries_every_mutation_kind")
    @Test
    fun `the corpus carries every mutation kind`() {
        val corpus = HostileCorpus.corpus(CORPUS)

        assertTrue(corpus.isNotEmpty(), "an empty corpus would make the sweep vacuously green")
        val counts = HostileCorpus.Mutation.entries.associateWith { mutation ->
            corpus.count { it.mutation == mutation }
        }
        for ((mutation, count) in counts) {
            assertTrue(
                count > 0,
                "the corpus drew $mutation zero times, so nothing was swept for it: $counts",
            )
        }
        // A generator that silently produced only well-formed input cannot pass: it is one kind of
        // thirteen, and the twelve that break something have to be there too.
        assertTrue(
            counts.getValue(HostileCorpus.Mutation.WELL_FORMED) < corpus.size / 2,
            "most of the corpus is well-formed, which is a corpus of fixtures rather than of " +
                "hostile input: $counts",
        )
        // Reproducible from the seed: two runs must be identical, or a failure cannot be re-run.
        // Compared as values rather than as strings — `Input.toString` redacts down to the
        // mutation and the index, both of which are pure functions of the list position, so a
        // string comparison here would pass just as happily over an unseeded `Random()`.
        assertEquals(corpus, HostileCorpus.corpus(CORPUS))
        assertTrue(
            corpus.map { it.tags }.toSet().size > corpus.size / 2,
            "the corpus repeats itself: only ${corpus.map { it.tags }.toSet().size} distinct tag " +
                "lists across ${corpus.size} inputs",
        )
    }

    /**
     * The corpus reaches past the wire layer, and the encoders really run.
     *
     * Without this the sweep could be green because every input died in `WireEvent`'s constructor
     * and no tag, listing or bid decoder ever ran on anything but well-formed input. And since
     * every listing and bid this reaches was **derived from a mutated fixture**, the re-encoded
     * event id is §4.3's round-trip guarantee proved over hostile input rather than over the
     * well-formed corpora `ListingPropertyTest` and `BidPropertyTest` use: an event this library
     * did not author, carrying tags it does not model, must come back out byte-identical or a
     * stranger's signature stops verifying.
     */
    @JsName("the_corpus_reaches_the_tag_listing_and_bid_codecs_in_both_directions")
    @Test
    fun `the corpus reaches the tag, listing and bid codecs in both directions`() {
        var checked = 0
        var tagSets = 0
        var listings = 0
        var bids = 0
        // Per mutation kind, because that is the floor that discriminates. An aggregate count is
        // met by the well-formed tenth of the corpus on its own, so a regression in which every
        // *mutated* input died at the wire layer would leave an aggregate assertion green while
        // this test's whole claim — that the decoders see broken input — became false.
        val reached = HostileCorpus.Mutation.entries.associateWith { 0 }.toMutableMap()
        for (input in HostileCorpus.corpus(CORPUS)) {
            val context = contextOf(input)
            val event = context.checked ?: continue
            checked++
            reached[input.mutation] = reached.getValue(input.mutation) + 1
            if (context.tagSet != null) tagSets++
            runCatching { Listing.decode(event) }.getOrNull()?.let { listing ->
                if (input.mutation != HostileCorpus.Mutation.WELL_FORMED) listings++
                assertEquals(
                    event.id.toHex(),
                    EventId.of(listing.encode()).toHex(),
                    "a listing decoded from $input did not re-encode to the same event id",
                )
            }
            runCatching { Bid.decode(event) }.getOrNull()?.let { bid ->
                if (input.mutation != HostileCorpus.Mutation.WELL_FORMED) bids++
                assertEquals(
                    event.id.toHex(),
                    EventId.of(bid.encode()).toHex(),
                    "a bid decoded from $input did not re-encode to the same event id",
                )
            }
        }

        val perMutation = CORPUS / HostileCorpus.Mutation.entries.size
        for ((mutation, count) in reached) {
            // The smallest of these is JUST_OVER_BOUND, whose four variants are chosen by index
            // rather than drawn, so its count is a fixed 25 rather than a binomial draw around it.
            // A drawn variant would put this floor inside one standard deviation, and a reviewer
            // changing SEED — which HostileCorpus invites — would see it fail for no reason.
            assertTrue(
                count > perMutation / 5,
                "only $count of about $perMutation $mutation inputs got past the wire layer, so " +
                    "the tag, listing and bid decoders were barely swept for it: $reached",
            )
        }
        assertTrue(checked > CORPUS / 2, "only $checked of $CORPUS inputs became a CheckedEvent")
        assertTrue(tagSets > 300, "only $tagSets inputs reached a TagSet, so §5.3's codec was barely swept")
        // Counted over **mutated** inputs only: the well-formed tenth alone yields more listings
        // than a naive floor here would ask for.
        assertTrue(listings > 100, "only $listings mutated inputs decoded as a listing")
        assertTrue(bids > 50, "only $bids mutated inputs decoded as a bid")
    }

    /**
     * Every mutation reaches a decoder on **every** kind, not only on the first of the three.
     *
     * The three mutations that can break either the wire layer or a decoder split on
     * `index % 5`, and `KINDS` has three entries selected by `index % 3`. An `index % 3` split
     * would send every wire-breaking input to `KINDS[0]` and none to the other two — so §5.2's
     * request path would never see a control character at a decoder, while the wire bound for
     * those mutations would only ever be exercised on a request. Both halves would look fully
     * covered in the aggregate counts. This is the assertion that says otherwise.
     */
    @JsName("every_mutation_reaches_a_decoder_on_every_listing_kind")
    @Test
    fun `every mutation reaches a decoder on every listing kind`() {
        val reached = mutableMapOf<Pair<HostileCorpus.Mutation, Int>, Int>()
        for (input in HostileCorpus.corpus(CORPUS)) {
            if (contextOf(input).checked == null) continue
            val key = input.mutation to input.kind
            reached[key] = (reached[key] ?: 0) + 1
        }

        val kinds = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.PUBLIC_BID)
        for (mutation in HostileCorpus.Mutation.entries) {
            for (kind in kinds) {
                assertTrue(
                    (reached[mutation to kind] ?: 0) > 0,
                    "no $mutation input of kind:$kind got past the wire layer, so that mutation " +
                        "is swept on two kinds out of three and nobody would notice: $reached",
                )
            }
        }
    }
}
