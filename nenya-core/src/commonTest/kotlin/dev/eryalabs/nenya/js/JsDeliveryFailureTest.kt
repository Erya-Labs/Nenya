package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.delivery.DeliveryRejection
import dev.eryalabs.nenya.order.DeliveryFailure
import dev.eryalabs.nenya.order.DisputeGround
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T73: §10.4's two digest refusals cross naming the `delivery_refused` token they are, on every door
 * that can refuse them, and that token takes the order to `disputed` on the matching ground.
 *
 * `verifyServedBytes` can refuse step 1 only; `stepOrder`'s `delivery_verified` makes both checks.
 * Each mismatch is a real wrong digest — one byte of the fixture blob flipped, its length kept — so
 * the size rules pass and the digest comparison is the only thing that can refuse it.
 */
class JsDeliveryFailureTest {

    /** `verifyServedBytes`: step 1's mismatch names its token; a size refusal and a pass name none. */
    @Test
    @JsName("verifyServedBytes_names_step_1_s_delivery_failure")
    fun `verifyServedBytes names step 1's delivery failure`() {
        val blob = OrderFixtures.blob
        val commitment = JsBoundaryFixtures.crossed(blob.commitment)

        val mismatch = verifyServedBytes(commitment, encodeLowerHex(flipped(blob.served)))
        assertFalse(mismatch.ok, "a blob one byte away from the committed one is not `x`")
        assertEquals(DeliveryRejection.SERVED_BYTES_MISMATCH.name, mismatch.reason, "§10.4 step 1")
        assertEquals(
            DeliveryFailure.SERVED_BYTES_HASH_MISMATCH.name,
            mismatch.deliveryFailure,
            "the page offers `delivery_refused` this token and maps nothing by hand",
        )

        val short = verifyServedBytes(commitment, encodeLowerHex(blob.served.copyOf(blob.served.size - 1)))
        assertEquals(DeliveryRejection.SIZE_DISAGREEMENT.name, short.reason, "§10.4's length rule")
        assertNull(short.deliveryFailure, "a length refusal is no §10.4 step's failure")

        val notHex = verifyServedBytes(commitment, "zz")
        assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, notHex.reason, "the crossing's own refusal")
        assertNull(notHex.deliveryFailure, "a crossing refusal hashed nothing")

        val honest = verifyServedBytes(commitment, encodeLowerHex(blob.served))
        assertTrue(honest.ok, "the committed blob is `x`")
        assertNull(honest.deliveryFailure, "a pass names no failure")
    }

    /**
     * `stepOrder`'s `delivery_verified`: each mismatch names its own step's token, the order does not
     * move, and the token offered back as `delivery_refused` reaches `disputed` on the matching
     * ground — equal, field for field, to the Kotlin machine's answer to the same `DeliveryRefused`.
     */
    @Test
    @JsName("delivery_verified_names_each_step_s_failure_and_it_disputes_the_order")
    fun `delivery_verified names each step's failure and it disputes the order`() {
        val released = released()
        val blob = OrderFixtures.blob
        val cases = listOf(
            Triple("served bytes not `x`", flipped(blob.served), blob.plaintext) to
                DeliveryFailure.SERVED_BYTES_HASH_MISMATCH,
            Triple("plaintext not `ox`", blob.served, flipped(blob.plaintext)) to
                DeliveryFailure.PLAINTEXT_BYTES_HASH_MISMATCH,
        )
        for ((bytes, failure) in cases) {
            val (what, served, plaintext) = bytes
            val refused = stepOrder(machine(), JsOrder(released), verified(served, plaintext))
            assertFalse(refused.ok, "$what: the digest refusal moves nothing")
            assertEquals("DeliveryRejection", refused.reasonVocabulary, "$what: §10.4's own refusal")
            assertEquals(failure.name, refused.deliveryFailure, "$what: the step's own token")

            val token = refused.deliveryFailure ?: fail("$what: no token to offer")
            val disputed = stepOrder(
                machine(),
                JsOrder(released),
                JsOrderEvent(type = "delivery_refused", deliveryFailure = token),
            )
            val expected = OrderFixtures.machineBeforeDeadlines().on(released, OrderEvent.DeliveryRefused(failure))
            JsFlowFixtures.assertSameOutcome(disputed, expected, "$what: `delivery_refused`")
            val order = disputed.order ?: fail("$what: the dispute carries no order")
            assertEquals(OrderState.DISPUTED.name, order.state, "$what: §10.4 says the order MUST dispute")
            assertEquals(
                DisputeGround.valueOf(failure.name).name,
                order.disputeGround,
                "$what: the dispute ground is the step that failed",
            )
        }
    }

    /** An honest `delivery_verified` advances and names no failure; another event's refusal names none. */
    @Test
    @JsName("an_honest_delivery_verified_and_other_refusals_name_no_failure")
    fun `an honest delivery_verified and other refusals name no failure`() {
        val released = released()
        val blob = OrderFixtures.blob
        val honest = stepOrder(machine(), JsOrder(released), verified(blob.served, blob.plaintext))
        assertTrue(honest.ok, "the committed blob and its plaintext settle the order: ${honest.reason}")
        assertNull(honest.deliveryFailure, "an advance names no failure")

        // §10.4's length rule refuses a `delivery_verified` before any digest: no step failed.
        val short = stepOrder(
            machine(),
            JsOrder(released),
            verified(blob.served.copyOf(blob.served.size - 1), blob.plaintext),
        )
        assertEquals(DeliveryRejection.SIZE_DISAGREEMENT.name, short.reason, "§10.4's length rule")
        assertNull(short.deliveryFailure, "a length refusal through `stepOrder` is no step's failure")

        // A `DeliveryRejection` from another event's translation is not a §10.4 step's failure.
        val badCommitment = stepOrder(
            machine(),
            JsOrder(released),
            JsOrderEvent(
                type = "delivery_committed",
                fromParty = "PROVIDER",
                commitment = JsCommitment("ab", "ab", blob.commitment.mimeType, "1"),
            ),
        )
        assertEquals("DeliveryRejection", badCommitment.reasonVocabulary, "the hex reader's refusal")
        assertNull(badCommitment.deliveryFailure, "a malformed commitment is no step's failure")

        // §11.2's own refusal of a `delivery_verified` (the evidence is for another commitment).
        val other = OrderFixtures.otherBlob
        val foreign = stepOrder(
            machine(),
            JsOrder(released),
            JsOrderEvent(
                type = "delivery_verified",
                commitment = JsBoundaryFixtures.crossed(other.commitment),
                servedBytesHex = encodeLowerHex(other.served),
                plaintextBytesHex = encodeLowerHex(other.plaintext),
            ),
        )
        assertEquals("TransitionRejection", foreign.reasonVocabulary, "§11.2's refusal, not §10.4's")
        assertNull(foreign.deliveryFailure, "the digests matched their own commitment; no step failed")
    }

    private fun released(): Order =
        OrderFixtures.orders()[OrderState.RELEASED] ?: fail("the chain must reach released")

    private fun machine(): JsOrderMachine =
        JsOrderMachine(JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)))

    private fun verified(served: ByteArray, plaintext: ByteArray): JsOrderEvent = JsOrderEvent(
        type = "delivery_verified",
        commitment = JsBoundaryFixtures.crossed(OrderFixtures.blob.commitment),
        servedBytesHex = encodeLowerHex(served),
        plaintextBytesHex = encodeLowerHex(plaintext),
    )

    /** The same bytes with the first one's low bit flipped: the same length, another SHA-256. */
    private fun flipped(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
}
