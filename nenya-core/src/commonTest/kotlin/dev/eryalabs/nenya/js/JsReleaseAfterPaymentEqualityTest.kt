package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T65 across the JavaScript boundary: [buildReleaseAfterPayment] answers exactly what
 * `PaymentWriter.release` answers.
 *
 * The positive case is the provider's own path through crossed calls: its `type=2` accepted into a
 * page-side store by [acceptPaymentRequest], the buyer's receipt checked against that store by
 * [verifySettlement], and the [JsSettlement] that answered handed to [buildReleaseAfterPayment]. The
 * release it builds decodes, passes §10.3's identity check, and steps the `OrderFixtures` order from
 * `paid` to `released`. Each refusal is compared with its vocabulary attached and carries no event.
 */
class JsReleaseAfterPaymentEqualityTest {

    private val messages: OrderFixtures.Messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)

    /** The provider's crossed settlement builds the release `PaymentWriter.release` builds, and it steps `paid` → `released`. */
    @Test
    @JsName("a_crossed_settlement_builds_the_release_payment_writer_builds_and_steps_paid_to_released")
    fun `a crossed settlement builds the release PaymentWriter builds and steps paid to released`() {
        val store = JsFlowFixtures.CrossedStore()
        val accepted = acceptPaymentRequest(
            request = JsRumor(messages.requestRumors.getValue(Payee.PROVIDER)),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            store = store.seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = FakeClock(SettlementFixtures.ACCEPTED_AT)),
        )
        assertTrue(accepted.ok, "the provider's own request must be accepted: ${accepted.reason}")
        val verified = verifySettlement(
            receipt = JsRumor(messages.receiptRumor(Payee.PROVIDER)),
            store = store.seam,
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        val settlement = verified.settlement ?: fail("the buyer's receipt must verify: ${verified.reason}")
        assertTrue(settlement.evidenced, "§9.2's evidence exists for the provider's payment")

        val crossed = releaseBothWays("the provider's release", settlement, expectedPayment = null)
        assertTrue(crossed.ok, "the release must build: ${crossed.reason} — ${crossed.detail}")

        val event = crossed.event?.event ?: fail("a built answer carries an event")
        val commitment = DeliveryCommitmentMessage.decode(ReleaseFixtures.commitmentRumor())
        val release = DeliverableReleaseMessage.decode(RumorWriterFixtures.bound(event))
        commitment.commitment.checkReleaseIdentity(release.release)
        assertEquals(emptyList(), release.divergenceFrom(commitment), "§10.3's raw comparison finds nothing")
        val paid = OrderFixtures.orders()[OrderState.PAID] ?: fail("the fixture chain must reach paid")
        val released = OrderFixtures.advanced(
            OrderFixtures.machineBeforeDeadlines(),
            paid,
            release.asOrderEvent(paid, Party.PROVIDER),
        )
        assertEquals(OrderState.RELEASED, released.state, "§11.2's `paid → released`")
    }

    /** Each of T65's four refusals, crossed as a value equal to the Kotlin one, with no event. */
    @Test
    @JsName("each_release_refusal_crosses_as_the_same_settlement_rejection_with_no_event")
    fun `each release refusal crosses as the same SettlementRejection with no event`() {
        val cases = listOf(
            Triple(
                SettlementRejection.RELEASE_WITHOUT_EVIDENCE,
                ReleaseFixtures.bitcoinSettlement(),
                ReleaseFixtures.commitmentRumor(),
            ),
            Triple(
                SettlementRejection.RELEASE_NOT_PROVIDER_PAYMENT,
                OrderFixtures.receipt(Payee.FEE),
                ReleaseFixtures.commitmentRumor(),
            ),
            Triple(
                SettlementRejection.RELEASE_FOR_ANOTHER_ORDER,
                OrderFixtures.receipt(Payee.PROVIDER, index = OrderFixtures.OTHER_ORDER_INDEX),
                ReleaseFixtures.commitmentRumor(),
            ),
            Triple(
                SettlementRejection.RELEASE_OF_ANOTHER_COMMITMENT,
                OrderFixtures.receipt(Payee.PROVIDER),
                ReleaseFixtures.commitmentRumor(SettlementFixtures.stranger(OrderFixtures.ORDER_INDEX)),
            ),
        )
        for ((reason, payment, commitment) in cases) {
            val crossed = releaseBothWays(reason.name, JsSettlement(payment), payment, commitment)
            assertFalse(crossed.ok, "$reason: no release is built")
            assertEquals(reason.name, crossed.reason, "the refusal is named")
            assertEquals("SettlementRejection", crossed.reasonVocabulary, "§10.3's gate is a settlement rule")
        }
    }

    /** A rumor that is not a commitment is `DeliveryCommitmentMessage.decode`'s refusal, crossed. */
    @Test
    @JsName("a_receipt_offered_as_the_commitment_is_the_decoder_s_refusal")
    fun `a receipt offered as the commitment is the decoder's refusal`() {
        val crossed = buildReleaseAfterPayment(
            JsBoundaryFixtures.crossed(ReleaseFixtures.providerEnvelope()),
            JsRumor(messages.receiptRumor(Payee.PROVIDER)),
            JsSettlement(OrderFixtures.receipt(Payee.PROVIDER)),
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )
        assertFalse(crossed.ok, "a receipt is not a commitment")
        assertEquals(ChannelRejection.NOT_A_DELIVERY_COMMITMENT.name, crossed.reason, "§10.1's decoder refuses it by name")
        assertEquals("ChannelRejection", crossed.reasonVocabulary, "the commitment decoder's own vocabulary")
    }

    // -----------------------------------------------------------------------------------------

    /**
     * [settlement] through [buildReleaseAfterPayment] and [payment] — its Kotlin value, or the
     * provider's own `Settlement.verify` over the same receipt and store when `null` — through
     * `PaymentWriter.release`, held equal.
     */
    private fun releaseBothWays(
        what: String,
        settlement: JsSettlement,
        expectedPayment: Settlement?,
        commitment: AttributedRumor.Bound = ReleaseFixtures.commitmentRumor(),
    ): JsBuildResult {
        val payment = expectedPayment ?: Settlement.verify(
            PaymentReceipt.decode(messages.receiptRumor(Payee.PROVIDER)),
            messages.store,
            OrderFixtures.TERMS.split,
        )
        val expected = JsBoundaryFixtures.answerOf(
            PaymentWriter.release(
                ReleaseFixtures.providerEnvelope(),
                DeliveryCommitmentMessage.decode(commitment),
                payment,
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
        )
        val crossed = buildReleaseAfterPayment(
            JsBoundaryFixtures.crossed(ReleaseFixtures.providerEnvelope()),
            JsRumor(commitment),
            settlement,
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )
        JsBoundaryFixtures.assertSameAnswer(crossed, expected, what)
        return crossed
    }
}
