package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.EventId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §4.3's round-trip rule over ten thousand generated §10.1 / §10.3 pairs, proved the way §4.3 states
 * the consequence: through the **event id**.
 *
 * §4.3 requires unknown tags be preserved verbatim when re-publishing an event this implementation
 * did not author and says what dropping one costs — it "silently strips extensions and **changes the
 * event id**". So the assertion is not a list comparison: every fixture is re-encoded after decoding
 * and its id recomputed inside T8, which is the only statement that covers tag order, tag arity,
 * unknown tags and `content` all at once.
 *
 * The non-vacuity floor is the second half. A codec that replaced every decoded value with a
 * constant would round-trip perfectly, because `encode()` re-emits the rumor rather than the decoded
 * values — so each drawn value is asserted back out, and the two key encodings and the two nonce
 * encodings are asserted to have non-zero counts, because a corpus that happened to draw hex ten
 * thousand times would leave §10.2's base64 clause untested while looking thorough.
 */
class DeliveryMessagePropertyTest {

    private val fixtures = DeliveryMessageFixtures.pairs(CORPUS)

    /** The whole corpus decodes, which is what makes every assertion below about something. */
    @JsName("every_generated_pair_decodes")
    @Test
    fun `every generated pair decodes`() {
        assertEquals(CORPUS, fixtures.size)

        for (fixture in fixtures) {
            val commitment = fixtures(fixture).first
            val release = fixtures(fixture).second

            assertEquals(OrderId.ofHex(DeliveryMessageFixtures.orderHex(fixture.index)), commitment.order)
            assertEquals(commitment.order, release.order)
        }
    }

    /**
     * §4.3, through §4.1: decode then encode reproduces the identical event id, for both messages.
     */
    @JsName("decoding_and_re_encoding_reproduces_the_identical_event_id")
    @Test
    fun `decoding and re-encoding reproduces the identical event id`() {
        for (fixture in fixtures) {
            val (commitment, release) = fixtures(fixture)

            assertEquals(
                EventId.of(
                    ChannelFixtures.checked(
                        DeliveryMessageFixtures.pubkey(fixture.index),
                        NenyaKind.ORDER_MESSAGE,
                        fixture.commitmentTags,
                    ).event,
                ),
                EventId.of(commitment.encode()),
                "the re-encoded commitment at ${fixture.index} has a different id",
            )
            assertEquals(commitment.id, EventId.of(commitment.encode()))
            assertEquals(release.id, EventId.of(release.encode()))
        }
    }

    /**
     * Every drawn value comes back out — the floor T10's review asked for, because a codec that
     * answered a constant would pass the id property above unchanged.
     */
    @JsName("every_drawn_value_is_the_decoded_value")
    @Test
    fun `every drawn value is the decoded value`() {
        for (fixture in fixtures) {
            val (commitment, release) = fixtures(fixture)

            assertEquals(fixture.mimeType, commitment.commitment.mimeType)
            assertEquals(fixture.mimeType, release.release.fileType)
            assertEquals(fixture.size, commitment.commitment.sizeBytes)
            assertEquals(fixture.size, release.release.sizeBytes)
            assertEquals(
                DeliveryMessageFixtures.servedHash(fixture.index),
                commitment.commitment.x.toHex(),
            )
            assertEquals(
                DeliveryMessageFixtures.plaintextHash(fixture.index),
                commitment.commitment.ox.toHex(),
            )
            assertEquals(DeliveryMessageFixtures.url(fixture.index), commitment.url)
        }
        // A corpus drawing one MIME type or one size ten thousand times would satisfy every
        // assertion above while proving nothing about the delegation.
        assertTrue(fixtures.map { it.mimeType }.toSet().size > 1, "the corpus draws one MIME type")
        assertTrue(fixtures.map { it.size }.toSet().size > CORPUS / 2, "the corpus draws few sizes")
    }

    /** Every generated pair is §10.3-identical over the **bytes**, which is how §10.3 states it. */
    @JsName("every_generated_pair_is_identical_over_the_bytes")
    @Test
    fun `every generated pair is identical over the bytes`() {
        for (fixture in fixtures) {
            val (commitment, release) = fixtures(fixture)

            assertEquals(
                emptyList(),
                release.divergenceFrom(commitment),
                "the generated pair at ${fixture.index} must be §10.3-identical",
            )
            commitment.commitment.checkReleaseIdentity(release.release)
        }
    }

    /**
     * §10.2's two encodings for the key and two for the nonce, all four exercised with a non-zero
     * count.
     *
     * Asserted rather than assumed: this is the line that fails if a later change to the generator
     * stops drawing base64, which would leave the clause §10.2 marks SHOULD covered by nothing while
     * every other test in this file stayed green.
     */
    @JsName("both_key_encodings_and_both_nonce_encodings_are_drawn")
    @Test
    fun `both key encodings and both nonce encodings are drawn`() {
        val hexKeys = fixtures.count { it.keyIsHex }
        val hexNonces = fixtures.count { it.nonceIsHex }

        assertTrue(hexKeys > 0 && hexKeys < CORPUS, "hex keys drawn: $hexKeys of $CORPUS")
        assertTrue(hexNonces > 0 && hexNonces < CORPUS, "hex nonces drawn: $hexNonces of $CORPUS")

        // And the corpus really carries ten thousand *different* keys and nonces. An earlier
        // generator seeded on the label's length rather than its content, which produced four
        // distinct keys across ten thousand indices while satisfying every other assertion in this
        // file — because the property under test is a length rule, which four values prove as well
        // as ten thousand. A corpus that claims ten thousand should be ten thousand.
        assertEquals(
            CORPUS,
            fixtures.map { DeliveryMessageFixtures.hex(DeliveryMessageFixtures.keyBytes(it.index)) }
                .toSet().size,
            "the corpus must carry a distinct key per fixture",
        )
        assertEquals(
            CORPUS,
            fixtures.map { DeliveryMessageFixtures.hex(DeliveryMessageFixtures.nonceBytes(it.index)) }
                .toSet().size,
            "and a distinct nonce per fixture",
        )
    }

    /** Both messages, decoded once per fixture. */
    private fun fixtures(
        fixture: DeliveryMessageFixtures.Fixture,
    ): Pair<DeliveryCommitmentMessage, DeliverableReleaseMessage> = Pair(
        DeliveryMessageFixtures.commitment(fixture.commitmentTags, fixture.index),
        DeliveryMessageFixtures.release(fixture.releaseTags, fixture.index),
    )

    private companion object {

        /** The queue's non-vacuity floor for this task. */
        const val CORPUS: Int = 10_000
    }
}
