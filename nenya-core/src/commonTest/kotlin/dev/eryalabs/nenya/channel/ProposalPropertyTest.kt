package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.wire.EventId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §7.5 and §7.6: ten thousand seeded proposals and their acceptances, each
 * round-tripping to the **identical event id** and each reproducing the values its generator drew.
 *
 * Two different failures are being ruled out, and they need different assertions:
 *
 * 1. **A codec that dropped or reordered a tag.** §4.3 states its own consequence — doing so
 *    "silently strips extensions **and changes the event id**" — so the property is asserted
 *    through T8's recomputation rather than by comparing tag lists to themselves.
 * 2. **A codec that wired a constant onto a published surface.** T10's review found exactly that: a
 *    `Msat.ofSat(0)` delegated nowhere, which passed every structural test in its package. So every
 *    decoded price, fee term, deadline and coordinate is asserted against the value the generator
 *    drew for that fixture, and not merely against "something".
 *
 * A codec that rejected everything fails the first assertion here; one that accepted everything
 * fails `ProposalCodecTest`. Neither can pass both.
 */
class ProposalPropertyTest {

    /** §4.3's round trip, through the id §4.3 names as the thing that changes when a tag is lost. */
    @JsName("every_generated_proposal_and_acceptance_re_encodes_to_the_identical_event_id")
    @Test
    fun `every generated proposal and acceptance re-encodes to the identical event id`() {
        assertEquals(SAMPLES, CORPUS.size)

        for (fixture in CORPUS) {
            val proposed = ProposalFixtures.bound(fixture.proposalTags, fixture.index)
            val proposal = OrderProposal.decode(proposed)
            assertEquals(
                fixture.proposalTags,
                proposal.encode().tags,
                "§4.1 forbids reordering or normalising tags and §4.3 forbids dropping unknown " +
                    "ones; fixture ${fixture.index}",
            )
            assertEquals(
                proposed.id,
                EventId.of(proposal.encode()),
                "the re-encoded proposal must hash to the id the original did; fixture ${fixture.index}",
            )

            val announced = ProposalFixtures.bound(fixture.acceptanceTags, fixture.index)
            val update = OrderStatusMessage.decode(announced)
            assertEquals(fixture.acceptanceTags, update.encode().tags, "fixture ${fixture.index}")
            assertEquals(
                announced.id,
                EventId.of(update.encode()),
                "the re-encoded acceptance must hash to the id the original did; fixture ${fixture.index}",
            )
            assertIs<Acceptance.Accepted>(
                proposal.accepts(update),
                "§7.6: an acceptance repeating the proposal's four terms byte for byte is an " +
                    "acceptance; fixture ${fixture.index}",
            )
        }
    }

    /**
     * The delegated values, pinned. A codec returning a constant price, a constant fee term or a
     * constant deadline satisfies every structural assertion in this package and fails here.
     */
    @JsName("every_decoded_term_is_the_value_the_generator_drew")
    @Test
    fun `every decoded term is the value the generator drew`() {
        for (fixture in CORPUS) {
            val proposal = ProposalFixtures.proposal(fixture.proposalTags, fixture.index)

            assertEquals(Msat.ofMsat(fixture.priceMsat), proposal.price, "fixture ${fixture.index}")
            assertEquals(fixture.expiration, proposal.terms.expiration, "fixture ${fixture.index}")
            assertEquals(fixture.deliverBy, proposal.terms.deliverBy, "fixture ${fixture.index}")
            assertEquals(
                ProposalFixtures.coordinate(fixture.index),
                proposal.item.coordinate.toTagValue(),
                "fixture ${fixture.index}",
            )

            val term = proposal.terms.split.term
            val drawn = fixture.basisPoints
            if (drawn == null) {
                assertEquals(
                    FeeTerm.Absent,
                    term,
                    "§8.1: no `fee` tag is Absent and not a stated zero; fixture ${fixture.index}",
                )
            } else {
                assertEquals(FeeTerm.of(drawn), term, "fixture ${fixture.index}")
            }
            assertEquals(
                term.feeOn(proposal.price),
                proposal.terms.split.fee,
                "§8.3's fee is derived from the decoded price and term; fixture ${fixture.index}",
            )
        }
    }

    /**
     * The corpus's own coverage, asserted rather than hoped for. A seeded generator that quietly
     * stopped drawing the absent-`fee` case, or the `amount`-present shape, or one of the four
     * deadline combinations, would leave every property above green over a corpus that exercised one
     * branch ten thousand times.
     */
    @JsName("the_corpus_covers_both_fee_arities_the_absent_fee_both_deadlines_and_both_amount_shapes")
    @Test
    fun `the corpus covers both fee arities, the absent fee, both deadlines and both amount shapes`() {
        val tags = CORPUS.flatMap { it.proposalTags }

        assertEquals(
            setOf(FEE_ELEMENTS_ZERO, FEE_ELEMENTS_NONZERO),
            tags.filter { it[0] == ChannelVocabulary.FEE }.map { it.size }.toSet(),
            "§8.1's two arities, both generated",
        )
        assertTrue(CORPUS.any { it.feeAbsent }, "§8.1's absent-`fee` read rule was never exercised")
        assertTrue(CORPUS.any { it.basisPoints == 0 }, "no stated zero was generated")
        assertTrue(CORPUS.any { (it.basisPoints ?: 0) > 0 }, "no charged fee was generated")

        for (expiration in listOf(true, false)) {
            for (deliverBy in listOf(true, false)) {
                assertTrue(
                    CORPUS.any {
                        (it.expiration != null) == expiration && (it.deliverBy != null) == deliverBy
                    },
                    "the corpus carried no proposal with expiration=$expiration, deliver_by=$deliverBy",
                )
            }
        }
        assertTrue(CORPUS.any { it.carriesAmount }, "§7.5's GammaMarkets `amount` was never generated")
        assertTrue(CORPUS.any { !it.carriesAmount }, "the `amount`-absent shape was never generated")
        assertTrue(
            tags.any { it[0] == ChannelVocabulary.SUBJECT },
            "§7.4's `subject` is display metadata that must round-trip; the corpus carried none",
        )
        assertTrue(
            tags.any { it[0] !in OrderProposal.readableTags() },
            "the corpus must carry tags this codec does not read at all, or §4.3's round trip is " +
                "being proved over an event with nothing to drop",
        )
    }

    /** Both anchors and the modelled sets are non-empty, so no equality above is "empty equals empty". */
    @JsName("the_s7_5_parse_and_the_modelled_tag_sets_are_non_empty")
    @Test
    fun `the §7-5 parse and the modelled tag sets are non-empty`() {
        assertTrue(Section75.sectionLines.isNotEmpty())
        assertTrue(Section75.exampleTagNames.isNotEmpty())
        assertTrue(OrderProposal.requiredTags().isNotEmpty())
        assertTrue(OrderProposal.readableTags().isNotEmpty())
        assertTrue(
            OrderProposal.requiredTags().all { it in OrderProposal.readableTags() },
            "every REQUIRED tag must also be a readable one, or the two lists describe different codecs",
        )
    }

    private companion object {

        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000

        /** §8.1's two arities: two elements at zero basis points, three above. */
        const val FEE_ELEMENTS_ZERO: Int = 2
        const val FEE_ELEMENTS_NONZERO: Int = 3

        /**
         * Built once for the whole class rather than per test method.
         *
         * Every test here walks the same ten thousand fixtures, and a JUnit instance-per-method
         * field would regenerate — and re-hash — all of them four times over.
         */
        val CORPUS: List<ProposalFixtures.Fixture> by lazy { ProposalFixtures.pairs(SAMPLES) }
    }
}
