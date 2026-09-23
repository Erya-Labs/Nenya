package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FixedRandomness
import dev.eryalabs.nenya.seam.Nip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.Nip44PayloadSigner
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.wire.EventJson
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §7.1 step 5: four timestamps per message, drawn independently and uniformly into
 * `[now − 172 800, now]`, and never the true time.
 *
 * ### Why this is a property test and not three examples
 *
 * The failure step 5 exists to prevent is not "the timestamp is wrong" but "the timestamp is
 * correlated": a seal carrying the true time, two copies sharing one draw, or a window that is
 * one second too wide are each invisible in any single message and visible only across many. So
 * the assertions here are over ten thousand seeded seals, plus the two boundary draws — which a
 * uniform sampler reaches with probability 1 in 172 801 and a test must therefore construct
 * rather than wait for.
 *
 * The window itself is **parsed out of §7.1** rather than transcribed, through `Section71`, so a
 * specification revision that widened or narrowed it turns this red instead of leaving the code
 * and the document disagreeing.
 */
class GiftWrapTimestampTest {

    /** The four `created_at` values of one message, in the order §7.1 step 5 draws them. */
    private class Timestamps(
        val sealToRecipient: Long,
        val wrapToRecipient: Long,
        val sealToSelf: Long,
        val wrapToSelf: Long,
    ) {

        fun all(): List<Long> = listOf(sealToRecipient, wrapToRecipient, sealToSelf, wrapToSelf)
    }

    @JsName("the_window_is_the_one_the_specification_states")
    @Test
    fun `the window is the one the specification states`() {
        assertEquals(
            Section71.randomisationWindowSeconds(Section71.specificationLines()).toLong(),
            GiftWrap.RANDOMISATION_WINDOW_SECONDS,
            "§7.1 step 5's interval, read from ${Section71.specPath()}",
        )
    }

    /**
     * Ten thousand seeded messages, forty thousand timestamps, and none of them outside the window
     * or after `now`.
     *
     * The mutation this is aimed at is `now + r` in place of `now − r`, and a window of 172 801:
     * either puts a timestamp outside the interval, and the first also puts it in the future, which
     * §7.1 step 5 forbids outright.
     */
    @JsName("over_ten_thousand_seals_every_timestamp_lies_in_the_window")
    @Test
    fun `over ten thousand seals every timestamp lies in the window`() {
        val now = EnvelopeFixtures.NOW
        val floor = now - GiftWrap.RANDOMISATION_WINDOW_SECONDS
        var counted = 0

        for (run in 0 until RUNS) {
            for (value in timestampsOf(randomness = RecordingRandomness(seed = SEED + run)).all()) {
                counted++
                assertTrue(
                    value in floor..now,
                    "§7.1 step 5 draws uniformly from [$floor, $now] and run $run produced $value",
                )
            }
        }

        assertEquals(RUNS * TIMESTAMPS_PER_MESSAGE, counted, "every message must yield four values")
    }

    /**
     * Both ends of the window are reachable, which a uniform sampler reaches once in 172 801 draws.
     *
     * The bytes are **computed** from the window's own width rather than typed: the low endpoint is
     * the draw zero and the high one is the draw that is the width minus one, each written out
     * big-endian by [fourBytes]. A sampler that quietly clamped either end would pass every
     * in-range assertion above and fail here.
     */
    @JsName("both_endpoints_of_the_window_are_reachable")
    @Test
    fun `both endpoints of the window are reachable`() {
        val now = EnvelopeFixtures.NOW
        val floor = now - GiftWrap.RANDOMISATION_WINDOW_SECONDS

        val lowest = timestampsOf(randomness = FixedRandomness(fourBytes(0L)))
        val highest = timestampsOf(
            randomness = FixedRandomness(fourBytes(GiftWrap.RANDOMISATION_WINDOW_SECONDS)),
        )

        assertEquals(List(TIMESTAMPS_PER_MESSAGE) { floor }, lowest.all(), "the oldest end")
        assertEquals(List(TIMESTAMPS_PER_MESSAGE) { now }, highest.all(), "and `now` itself")
    }

    /**
     * §7.1 step 5's "four values in total, drawn **independently**".
     *
     * The mutation: one draw reused for a copy's seal and its wrap. Two independent draws from a
     * 172 801-wide window collide once in 172 801, so requiring them to differ in almost every
     * sample is a statement a reused draw cannot satisfy — while a handful of genuine collisions
     * over a few hundred runs would not fail the suite.
     */
    @JsName("the_four_draws_are_independent_of_each_other")
    @Test
    fun `the four draws are independent of each other`() {
        var sealDifferedFromWrap = 0
        var copiesDiffered = 0

        for (run in 0 until INDEPENDENCE_RUNS) {
            val drawn = timestampsOf(randomness = RecordingRandomness(seed = SEED + run))
            if (drawn.sealToRecipient != drawn.wrapToRecipient) sealDifferedFromWrap++
            if (drawn.sealToRecipient != drawn.sealToSelf) copiesDiffered++
        }

        assertTrue(
            sealDifferedFromWrap > INDEPENDENCE_RUNS - ALLOWED_COLLISIONS,
            "a seal and its own wrap shared a draw in ${INDEPENDENCE_RUNS - sealDifferedFromWrap} " +
                "of $INDEPENDENCE_RUNS runs; §7.1 step 5 draws them independently",
        )
        assertTrue(
            copiesDiffered > INDEPENDENCE_RUNS - ALLOWED_COLLISIONS,
            "the two copies' seals shared a draw in ${INDEPENDENCE_RUNS - copiesDiffered} of " +
                "$INDEPENDENCE_RUNS runs",
        )
    }

    /**
     * §4.3 has no negative timestamp, so the window's floor is `max(0, now − 172 800)` and not
     * `now − 172 800`.
     *
     * A clock inside the first two days of 1970 is not a plausible reading, and that is the point:
     * §4.6 makes the injected clock authoritative, so this library uses what it is given and the
     * arithmetic must stay inside §4.3's range for every reading it accepts.
     */
    @JsName("a_clock_inside_the_window_never_produces_a_negative_timestamp")
    @Test
    fun `a clock inside the window never produces a negative timestamp`() {
        val now = 100L

        for (run in 0 until SMALL_RUNS) {
            val drawn = timestampsOf(
                randomness = RecordingRandomness(seed = SEED + run),
                clock = FakeClock(now),
            )
            for (value in drawn.all()) {
                assertTrue(value in 0L..now, "a clock at $now produced $value on run $run")
            }
        }
    }

    /**
     * §7.1 steps 3 and 4 over a thousand messages: two thousand wraps, two thousand distinct keys.
     *
     * Read off the emitted wraps rather than off the source that handed them out, so what is
     * asserted is the key that actually signed each wrap.
     */
    @JsName("a_thousand_seals_give_two_thousand_distinct_wrap_pubkeys")
    @Test
    fun `a thousand seals give two thousand distinct wrap pubkeys`() {
        val ephemeral = Nip44PayloadEphemeralSigners()
        val keys = mutableListOf<String>()

        for (run in 0 until WRAP_KEY_RUNS) {
            val message = GiftWrap.seal(
                rumor = EnvelopeFixtures.minimalRumor(),
                recipientPubkey = EnvelopeFixtures.recipientKey(),
                sender = EnvelopeFixtures.senderSigner(),
                ephemeral = ephemeral,
                clock = FakeClock(EnvelopeFixtures.NOW),
                randomness = RecordingRandomness(seed = SEED + run),
            )
            for (wrap in listOf(message.toRecipient, message.toSelf)) {
                keys += EventJson.read(wrap.json, EnvelopeFixtures.WRAP_LIMITS).event.pubkey
            }
        }

        assertEquals(WRAP_KEY_RUNS * 2, keys.size)
        assertEquals(
            keys.size,
            keys.toSet().size,
            "§7.1 step 3: a freshly generated keypair, new for every single wrap — a key on two " +
                "wraps links them",
        )
        assertTrue(
            EnvelopeFixtures.senderKey() !in keys && EnvelopeFixtures.recipientKey() !in keys,
            "§7.1 step 4: and none of them is a key either party uses elsewhere",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /**
     * One message's four timestamps, read back the way a counterparty reads them: the wrap's from
     * its JSON, the seal's from the seal only its addressee can decrypt.
     */
    private fun timestampsOf(
        randomness: Randomness,
        clock: FakeClock = FakeClock(EnvelopeFixtures.NOW),
    ): Timestamps {
        val sender: Nip44PayloadSigner = EnvelopeFixtures.senderSigner()
        val recipient: Nip44PayloadSigner = EnvelopeFixtures.recipientSigner()
        val message = GiftWrap.seal(
            rumor = EnvelopeFixtures.minimalRumor(),
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = sender,
            ephemeral = Nip44PayloadEphemeralSigners(),
            clock = clock,
            randomness = randomness,
        )
        val (wrapToRecipient, sealToRecipient) =
            EnvelopeFixtures.envelopeTimestamps(message.toRecipient.json, recipient)
        val (wrapToSelf, sealToSelf) = EnvelopeFixtures.envelopeTimestamps(message.toSelf.json, sender)
        return Timestamps(sealToRecipient, wrapToRecipient, sealToSelf, wrapToSelf)
    }

    /** [value] as four big-endian bytes — the draw `GiftWrap` reads back out of the source. */
    private fun fourBytes(value: Long): ByteArray = ByteArray(4) { index ->
        ((value ushr ((3 - index) * 8)) and 0xffL).toByte()
    }

    private companion object {

        /** Pinned so a failure is reproducible, and varied per run so the runs are not one run. */
        const val SEED: Long = 20260924L

        const val RUNS: Int = 10_000

        const val INDEPENDENCE_RUNS: Int = 500

        const val SMALL_RUNS: Int = 200

        const val WRAP_KEY_RUNS: Int = 1_000

        const val TIMESTAMPS_PER_MESSAGE: Int = 4

        /**
         * Two independent draws from a 172 801-wide window collide about once in 172 801, so five
         * collisions in a few hundred runs is beyond any plausible seed while one draw reused for
         * two timestamps fails every run.
         */
        const val ALLOWED_COLLISIONS: Int = 5
    }
}
