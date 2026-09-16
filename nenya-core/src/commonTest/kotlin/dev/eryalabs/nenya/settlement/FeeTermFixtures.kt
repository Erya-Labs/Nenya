package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.money.FeeTerm
import kotlin.test.fail

/**
 * The generator behind §8.4's non-vacuity floor: seeded orders whose `(bps, recipient)` pair is
 * the same at every point it appears, and the one-point-at-a-time mutations of them.
 *
 * ### Nothing here is typed
 *
 * The queue's Definition of done forbids an encoded value appearing in a test as something somebody
 * wrote out. The recipient pubkeys are real BIP-340 keys read out of the vendored,
 * externally-authored `bip340-vectors.csv` through [SettlementFixtures.feeRecipient]; the basis
 * points are small decimal integers drawn from the seeded run and written by
 * `Int.toString`, which is §4.4's canonical form by construction rather than by transcription. A
 * reviewer can change [SEED], re-run the suite, and every property must still hold.
 *
 * ### The stated bound: these sightings are raw, and the codec half is covered separately
 *
 * [orders] builds its sightings through [FeeTermSighting.of] — the raw door — rather than by
 * decoding ten thousand proposals, acceptances, `type=2`s and `kind:17`s, each of which recomputes
 * a SHA-256 event id in pure Kotlin. That is a cost decision and it is stated rather than implied:
 * what this corpus proves is that §8.4's **comparison** is neither vacuously true nor vacuously
 * false across every shape §8.1 admits, at every point §8.4 names. That the sighting a real
 * message produces is the one this corpus models is proved separately, over a small sub-corpus,
 * by `FeeTermCheckTest` — which builds its fixtures from T14's proposal codec and T15's request
 * codec and asserts the two agree.
 *
 * ### Why every point in a generated order carries a tag
 *
 * §8.4's three OPTIONAL points are dropped from the comparison when they carry no `fee` tag, which
 * is the MUST NOT this library has to honour. A corpus that generated an absent optional point
 * would therefore generate a point no mutation could move, and "mutating each point in turn is
 * rejected" would quietly stop being true of it. So an optional point is either present with the
 * agreed pair or not in the order at all, and the dropped direction is its own property
 * ([SettlementFixtures] has no part in it) rather than noise inside this one.
 */
internal object FeeTermFixtures {

    /** Pinned so a failure is reproducible. [JdkRandom] for the reason `WireFixtures` gives. */
    const val SEED: Long = 20260924L

    /** The three shapes §8.1 admits for an order's fee term, all of which §8.4 carries unchanged. */
    enum class FeeShape {

        /** No `fee` tag at all. §8.1's read rule: zero fee, and the pair is `(0, —)`. */
        ABSENT,

        /** §8.1's two-element `["fee", "0"]` — the signed statement that there is no fee. */
        STATED_ZERO,

        /** §8.1's three-element form above zero basis points, which names a recipient. */
        STATED_NONZERO,
    }

    /** One generated order: its shape, the pair every point carries, and the points themselves. */
    class FeeOrder(
        val index: Int,
        val shape: FeeShape,

        /** The agreed `fee` tag, raw, or `null` for [FeeShape.ABSENT]. */
        val term: List<String>?,

        /** §8.4's points for this order, in the order a caller would have observed them. */
        val sightings: List<FeeTermSighting>,
    ) {

        /** The points, by name. Every one of them participates in the comparison. */
        val points: List<FeeTermPoint> get() = sightings.map { it.point }
    }

    /**
     * One order with exactly one point's pair changed, and what §8.4 must say about it.
     *
     * [point] is not always the point that was changed, and that is §8.4's own reference rule
     * rather than a fudge: [FeeTermAgreement.across] compares every later point against the
     * **first**, so changing the first makes the *second* the divergence. The expectation is
     * computed here so the property test cannot quietly assert the weaker "something diverged".
     */
    class Mutation(
        val sightings: List<FeeTermSighting>,
        val point: FeeTermPoint,
        val element: FeeTermElement,
    )

    /** §8.1's fee tag at [basisPoints], naming [recipient] above zero. */
    fun feeTag(basisPoints: Int, recipient: String): List<String> =
        if (basisPoints == 0) listOf(SettlementVocabulary.FEE, "0")
        else listOf(SettlementVocabulary.FEE, basisPoints.toString(), recipient)

    /** [count] orders whose fee pair is identical at every point it appears. */
    fun orders(count: Int): List<FeeOrder> {
        val random = JdkRandom(SEED)
        return List(count) { index -> order(random, index) }
    }

    /**
     * [order] with the pair at [at] replaced by one §8.4 calls a divergence, and nothing else
     * touched.
     *
     * The replacement is always a **present** tag, never a removal: removing one at an OPTIONAL
     * point would drop it from the comparison rather than diverge from it, so a mutation built
     * that way would silently test nothing at three of the seven points.
     */
    fun mutateAt(order: FeeOrder, at: Int): Mutation {
        val element = elementFor(order, at)
        val replacement = diverging(order, element, at)
        val sightings = order.sightings.mapIndexed { index, sighting ->
            if (index == at) FeeTermSighting.of(sighting.point, replacement) else sighting
        }
        // The reference is the first point, so changing it moves the divergence to the second.
        val reported = if (at == 0) order.sightings[1].point else order.sightings[at].point
        return Mutation(sightings, reported, element)
    }

    private fun order(random: JdkRandom, index: Int): FeeOrder {
        // Cycled rather than drawn, so the corpus cannot end up short of one shape — the counts
        // are still asserted in the test, because a cycle that silently stopped covering one is
        // exactly the vacuity this floor exists for.
        val shape = FeeShape.entries[index % FeeShape.entries.size]
        val recipient = SettlementFixtures.feeRecipient(index)
        val term = when (shape) {
            FeeShape.ABSENT -> null
            FeeShape.STATED_ZERO -> feeTag(0, recipient)
            // Above zero and within §8.1's `0..10000`, so the tag is one T9 would also accept.
            FeeShape.STATED_NONZERO -> feeTag(1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS), recipient)
        }

        val points = mutableListOf<FeeTermPoint>()
        // §8.4's first two REQUIRED points exist for every order, whatever its shape.
        points += FeeTermPoint.ACCEPTANCE
        if (shape == FeeShape.STATED_NONZERO) {
            // §8.3 and §8.6: a fee `type=2` and a fee `kind:17` exist only where a fee is owed.
            points += FeeTermPoint.FEE_PAYMENT_REQUEST
            points += FeeTermPoint.FEE_RECEIPT
        }
        if (shape != FeeShape.ABSENT) {
            // §8.4's OPTIONAL points, carrying the pair — "where one **is** present it MUST match".
            for (optional in FeeTermPoint.OPTIONAL) if (random.nextBoolean()) points += optional
        }
        points.shuffleAll(random)
        // §8.4's list starts at the proposal and §8.1's read rule fixes the pair there, so the
        // proposal is always first and is always the reference.
        points.add(0, FeeTermPoint.ORDER_PROPOSAL)

        return FeeOrder(index, shape, term, points.map { FeeTermSighting.of(it, term) })
    }

    /** Which half of the pair a divergence at [at] can be built out of, given [order]'s shape. */
    private fun elementFor(order: FeeOrder, at: Int): FeeTermElement = when (order.shape) {
        // The pair is `(0, —)` stated by an absent tag, so the only thing a present tag can be is
        // a presence divergence.
        FeeShape.ABSENT -> FeeTermElement.PRESENCE
        // Alternated by position so neither half goes untested across the corpus.
        else -> if (at % 2 == 0) FeeTermElement.BASIS_POINTS else FeeTermElement.RECIPIENT
    }

    /** A `fee` tag differing from [order]'s in exactly [element]. */
    private fun diverging(order: FeeOrder, element: FeeTermElement, at: Int): List<String> {
        val term = order.term
        val other = otherRecipient(term?.getOrNull(RECIPIENT_ELEMENT), order.index)
        if (term == null) {
            if (element != FeeTermElement.PRESENCE) {
                fail("an absent fee term can only diverge by presence; asked for $element")
            }
            return feeTag(0, other)
        }
        val basisPoints = term[BASIS_POINTS_ELEMENT].toInt()
        return when (element) {
            // A different canonical decimal, inside §8.1's range, at the arity §8.1 gives that
            // value — so the first differing element is the basis points and the tag is still one
            // T9 would accept on the wire.
            FeeTermElement.BASIS_POINTS -> {
                val moved =
                    if (basisPoints == FeeTerm.MAX_BASIS_POINTS) basisPoints - 1 else basisPoints + 1
                feeTag(moved, if (term.size == ELEMENTS_NONZERO) term[RECIPIENT_ELEMENT] else other)
            }
            // At three elements, another key in the same position. At two, the arity itself:
            // §8.1's two arities differ in exactly the recipient, and `["fee", "0", X]` — which
            // §8.1 names as malformed — is precisely somebody adding a fee recipient to a signed
            // zero-fee term, which is the divergence and not a syntax error to report instead.
            FeeTermElement.RECIPIENT -> listOf(term[0], term[BASIS_POINTS_ELEMENT], other)
            FeeTermElement.PRESENCE ->
                fail("a stated fee term at position $at diverges by value, not by presence")
        }
    }

    /** `["fee", "<bps>", …]` — the basis points sit second. */
    private const val BASIS_POINTS_ELEMENT: Int = 1

    /** `["fee", …, "<recipient-pubkey-hex>"]` — the recipient sits third. */
    private const val RECIPIENT_ELEMENT: Int = 2

    /** §8.1's arity above zero basis points. */
    private const val ELEMENTS_NONZERO: Int = 3

    /** How far to scan the vendored key list for one that is genuinely a different key. */
    private const val RECIPIENT_SEARCH: Int = 64

    /**
     * A real BIP-340 key that is **not** [notThis], found by scanning rather than by a stride.
     *
     * A fixed offset is what a fixture like this is usually written with, and it is wrong here in
     * a way that only shows on some indices: the vendored `bip340-vectors.csv` **repeats** public
     * keys across its rows — most of its failure cases are different signatures against one key —
     * so `index + k` lands on the same key for some `index` however `k` is chosen. A mutation that
     * substituted a recipient for itself would then diverge in nothing and the property would
     * report a false negative on exactly those orders. Scanning, and failing loudly if nothing
     * differs, removes the failure mode instead of hoping past it.
     */
    private fun otherRecipient(notThis: String?, index: Int): String {
        for (step in 1..RECIPIENT_SEARCH) {
            val candidate = SettlementFixtures.feeRecipient(index + step)
            if (candidate != notThis) return candidate
        }
        fail("no key within $RECIPIENT_SEARCH of $index differs from the fixture's own recipient")
    }

    /** Reorders in place, so the corpus does not test one point order ten thousand times. */
    private fun MutableList<FeeTermPoint>.shuffleAll(random: JdkRandom) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }
}
