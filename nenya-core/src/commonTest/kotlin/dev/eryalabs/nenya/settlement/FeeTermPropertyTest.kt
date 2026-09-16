package dev.eryalabs.nenya.settlement

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §8.4: a checker that answered "consistent" unconditionally and one
 * that answered "diverged" unconditionally must **both** fail, and so must the one an implementer
 * actually writes.
 *
 * That third one is the point of this file. §8.4 names four REQUIRED points and three OPTIONAL
 * ones, and the implementation that ships is the one that compares the **proposal against the
 * receipt** and calls it done. It passes every single-comparison test ever written for it: the
 * proposal agrees with the receipt in every honest order, and a counterparty that re-quoted the
 * fee at the acceptance or at the fee `type=2` walks straight through. So the property here is
 * per-point: mutate the pair at **each** point in turn, one point at a time, and the answer must
 * name that point.
 *
 * ### What this corpus is and is not
 *
 * Ten thousand seeded orders from [FeeTermFixtures], cycling §8.1's three shapes — no `fee` tag at
 * all, a stated `["fee", "0"]`, and a stated non-zero term naming a recipient — with §8.4's
 * OPTIONAL points drawn in. Its sightings are raw tags rather than decoded messages, which is a
 * cost decision stated at the generator: what is proved here is the comparison, and that a real
 * message produces the sighting this models is `FeeTermCheckTest`'s job.
 */
class FeeTermPropertyTest {

    private companion object {

        /** Ten thousand, which is the floor the queue sets for a property of this kind. */
        const val CORPUS: Int = 10_000

        /**
         * How many orders the single-point control walks. A bound, not a hope: it is a fact about
         * the sequence length rather than about the values, so every order would answer alike.
         */
        const val POINTS_OF_ONE: Int = 60

        /** One seeded run, shared by every test here so the corpus is built once. */
        val corpus: List<FeeTermFixtures.FeeOrder> by lazy { FeeTermFixtures.orders(CORPUS) }
    }

    @JsName("the_corpus_covers_every_shape_s8_1_admits_with_non_zero_counts")
    @Test
    fun `the corpus covers every shape §8_1 admits, with non-zero counts`() {
        assertEquals(CORPUS, corpus.size)
        assertTrue(
            FeeTermPoint.entries.isNotEmpty(),
            "the modelled point set is empty, so every per-point loop below iterates over nothing",
        )

        val byShape = FeeTermFixtures.FeeShape.entries.associateWith { shape ->
            corpus.count { it.shape == shape }
        }
        for ((shape, count) in byShape) {
            assertTrue(count > 0, "the corpus carries no $shape order at all: $byShape")
        }
        // §8.1's two arities, which are the two the byte comparison has to carry through.
        assertTrue(
            corpus.any { it.term?.size == 2 },
            "no order in the corpus states the two-element `[\"fee\", \"0\"]` §8.1 gives at zero bps",
        )
        assertTrue(
            corpus.any { it.term?.size == 3 },
            "no order in the corpus states §8.1's three-element form above zero bps",
        )
        assertTrue(
            corpus.any { it.term == null },
            "no order in the corpus is §8.1's absent-`fee` proposal, whose pair is `(0, —)`",
        )
        // Every one of §8.4's seven points must actually occur, or the per-point sweep below is
        // a sweep over four of them with three never reached.
        val covered = corpus.flatMap { it.points }.toSet()
        assertEquals(
            FeeTermPoint.entries.toSet(),
            covered,
            "the corpus must reach every point §8.4 names; it reached $covered",
        )
        assertTrue(
            corpus.all { it.sightings.size >= 2 },
            "an order with one point has nothing to compare, and its mutation could not diverge",
        )
    }

    @JsName("an_order_whose_pair_is_identical_at_every_point_is_accepted")
    @Test
    fun `an order whose pair is identical at every point is accepted`() {
        for (order in corpus) {
            val agreement = FeeTermAgreement.across(order.sightings)

            val agreed = assertIs<FeeTermAgreement.Agreed>(agreement, "order ${order.index}")
            assertEquals(
                order.points,
                agreed.points,
                "every point of this order carries the pair, so every one was compared",
            )
            val expected = order.term?.getOrNull(2)
            assertEquals(
                expected,
                agreed.recipient,
                "§8.7's operand is the recipient of the agreed pair, in §4.3's canonical form",
            )
        }
    }

    @JsName("mutating_the_pair_at_each_point_in_turn_is_rejected_naming_that_point")
    @Test
    fun `mutating the pair at each point in turn is rejected, naming that point`() {
        var mutations = 0
        val pointsCaught = mutableSetOf<FeeTermPoint>()
        val elementsCaught = mutableSetOf<FeeTermElement>()

        for (order in corpus) {
            for (at in order.sightings.indices) {
                val mutation = FeeTermFixtures.mutateAt(order, at)

                val diverged = assertIs<FeeTermAgreement.Diverged>(
                    FeeTermAgreement.across(mutation.sightings),
                    "order ${order.index} mutated at point $at (${order.points[at]}) must diverge",
                )
                assertEquals(
                    mutation.point,
                    diverged.point,
                    "order ${order.index} mutated at point $at must be reported at that point — a " +
                        "checker comparing only the proposal against the receipt reports the " +
                        "receipt whatever was changed",
                )
                assertEquals(
                    mutation.element,
                    diverged.element,
                    "and at the side of §8.1's pair that was changed",
                )
                pointsCaught += diverged.point
                elementsCaught += diverged.element
                mutations++
            }
        }

        assertTrue(mutations > CORPUS, "one mutation per point per order, and there were $mutations")
        assertEquals(
            FeeTermPoint.entries.toSet() - FeeTermPoint.ORDER_PROPOSAL,
            pointsCaught,
            "every point but the reference must have been reported as the divergence; the " +
                "proposal is always first, so a mutation there is reported at the second point",
        )
        assertEquals(
            FeeTermElement.entries.toSet(),
            elementsCaught,
            "and all three sides of §8.1's pair must have been reached",
        )
    }

    @JsName("an_optional_point_carrying_no_fee_tag_never_changes_the_answer")
    @Test
    fun `an optional point carrying no fee tag never changes the answer`() {
        // §8.4: "An implementation MUST NOT require a `fee` tag on any of the three." This is the
        // over-strict direction, and it fails *closed* — it refuses only conformant peers, which
        // is why no test written from §8.4's REQUIRED list alone would ever see it. §9.2's own
        // worked `kind:17` is a provider receipt carrying no `fee` tag.
        for (order in corpus) {
            val agreed = assertIs<FeeTermAgreement.Agreed>(FeeTermAgreement.across(order.sightings))

            for (optional in FeeTermPoint.OPTIONAL) {
                val withAbsent = order.sightings + FeeTermSighting.of(optional, null)
                val answer = assertIs<FeeTermAgreement.Agreed>(
                    FeeTermAgreement.across(withAbsent),
                    "order ${order.index}: an absent `fee` tag at $optional MUST NOT be required",
                )
                assertEquals(agreed.recipient, answer.recipient)
                assertEquals(
                    order.points,
                    answer.points,
                    "the dropped point is not among the ones compared, and says so",
                )
            }
        }
    }

    @JsName("an_optional_point_carrying_a_disagreeing_fee_tag_is_rejected")
    @Test
    fun `an optional point carrying a disagreeing fee tag is rejected`() {
        // The other half of the same sentence: "but where one **is** present it MUST match".
        var checked = 0
        for (order in corpus) {
            val disagreeing = FeeTermFixtures.mutateAt(order, 0).sightings.first().term
            for (optional in FeeTermPoint.OPTIONAL) {
                if (optional in order.points) continue
                val withOptional = order.sightings + FeeTermSighting.of(optional, disagreeing)

                val diverged = assertIs<FeeTermAgreement.Diverged>(
                    FeeTermAgreement.across(withOptional),
                    "order ${order.index}: an optional point present and disagreeing MUST match",
                )
                assertEquals(optional, diverged.point)
                checked++
            }
        }
        assertTrue(checked > 0, "no order left an optional point out, so this test proved nothing")
    }

    @JsName("a_sequence_with_fewer_than_two_points_to_compare_is_refused_and_never_agreed")
    @Test
    fun `a sequence with fewer than two points to compare is refused, and never agreed`() {
        val empty = assertFailsWith<SettlementException> { FeeTermAgreement.across(emptyList()) }
        assertEquals(SettlementRejection.FEE_TERM_NOT_COMPARABLE, empty.reason)

        val onlyDropped = assertFailsWith<SettlementException> {
            FeeTermAgreement.across(FeeTermPoint.OPTIONAL.map { FeeTermSighting.of(it, null) })
        }
        assertEquals(
            SettlementRejection.FEE_TERM_NOT_COMPARABLE,
            onlyDropped.reason,
            "three optional points carrying nothing leave nothing compared, and `agreed` over " +
                "nothing is the vacuous answer this whole file is the floor against",
        )

        // One point is the same vacuity with a point attached to it, and it is the shape that
        // would let a message be its own reference: compared against itself a term always agrees.
        for (order in corpus.take(POINTS_OF_ONE)) {
            for (sighting in order.sightings) {
                val alone = assertFailsWith<SettlementException>("order ${order.index}") {
                    FeeTermAgreement.across(listOf(sighting))
                }
                assertEquals(SettlementRejection.FEE_TERM_NOT_COMPARABLE, alone.reason)
            }
        }
    }
}
