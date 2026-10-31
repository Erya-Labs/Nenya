package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.RumorBuild
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.payment.Payee
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * T65: §10.3's release written by `PaymentWriter.release` from the provider's own commitment, only
 * once the provider's own payment has verified.
 *
 * The positive case goes the whole way: the built release decodes, passes §10.3's identity check
 * against the commitment it was read from, has no raw divergence from it, and steps the
 * `OrderFixtures` order from `paid` to `released`. Each negative control is refused by its own
 * `SettlementRejection` and carries no event.
 */
class PaymentWriterReleaseTest {

    /** The provider's commitment and its own verified payment build a release that steps `paid` → `released`. */
    @Test
    @JsName("a_release_after_the_provider_s_own_payment_decodes_matches_and_steps_paid_to_released")
    fun `a release after the provider's own payment decodes, matches and steps paid to released`() {
        val commitment = ReleaseFixtures.commitment()
        val built = built(
            PaymentWriter.release(
                ReleaseFixtures.providerEnvelope(),
                commitment,
                OrderFixtures.receipt(Payee.PROVIDER),
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
        )

        val release = DeliverableReleaseMessage.decode(RumorWriterFixtures.bound(built))
        assertEquals(commitment.order, release.order, "the release's `order` is the commitment's")
        commitment.commitment.checkReleaseIdentity(release.release)
        OrderFixtures.blob.commitment.checkReleaseIdentity(release.release)
        assertEquals(emptyList(), release.divergenceFrom(commitment), "§10.3's raw comparison finds nothing")

        // The same event `RumorWriter.release` writes when the four values are typed in by hand.
        val typed = RumorWriter.release(
            ReleaseFixtures.providerEnvelope(),
            OrderFixtures.ORDER_ID,
            OrderFixtures.blob.matchingRelease(),
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )
        RumorWriterFixtures.assertSameBytes(
            (typed as? RumorBuild.Built ?: fail("the typed release must build: $typed")).event,
            built,
            "the release read off the commitment",
        )

        val paid = OrderFixtures.orders()[OrderState.PAID] ?: fail("the fixture chain must reach paid")
        val released = OrderFixtures.advanced(
            OrderFixtures.machineBeforeDeadlines(),
            paid,
            release.asOrderEvent(paid, Party.PROVIDER),
        )
        assertEquals(OrderState.RELEASED, released.state, "§11.2's `paid → released`")
    }

    /** An envelope author in uppercase is §4.3's same key, so the commitment is still its own. */
    @Test
    @JsName("an_uppercase_envelope_author_is_the_same_key_as_the_commitment_s_sender")
    fun `an uppercase envelope author is the same key as the commitment's sender`() {
        val provider = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX)
        built(
            PaymentWriter.release(
                ReleaseFixtures.envelope(provider.uppercase()),
                ReleaseFixtures.commitment(),
                OrderFixtures.receipt(Payee.PROVIDER),
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
        )
    }

    /** §9.4's `bitcoin` receipt evidences nothing, so it releases nothing. */
    @Test
    @JsName("an_unverified_payment_is_refused_as_release_without_evidence")
    fun `an unverified payment is refused as RELEASE_WITHOUT_EVIDENCE`() {
        assertRefused(
            SettlementRejection.RELEASE_WITHOUT_EVIDENCE,
            payment = ReleaseFixtures.bitcoinSettlement(),
        )
    }

    /** The fee recipient's verified payment is not the provider's. */
    @Test
    @JsName("the_fee_payment_is_refused_as_release_not_provider_payment")
    fun `the fee payment is refused as RELEASE_NOT_PROVIDER_PAYMENT`() {
        assertRefused(
            SettlementRejection.RELEASE_NOT_PROVIDER_PAYMENT,
            payment = OrderFixtures.receipt(Payee.FEE),
        )
    }

    /** The provider's verified payment for another order does not release this one. */
    @Test
    @JsName("another_order_s_payment_is_refused_as_release_for_another_order")
    fun `another order's payment is refused as RELEASE_FOR_ANOTHER_ORDER`() {
        assertRefused(
            SettlementRejection.RELEASE_FOR_ANOTHER_ORDER,
            payment = OrderFixtures.receipt(Payee.PROVIDER, index = OrderFixtures.OTHER_ORDER_INDEX),
        )
    }

    /** A commitment a stranger sealed is not the provider's own, even for the provider's order. */
    @Test
    @JsName("a_stranger_s_commitment_is_refused_as_release_of_another_commitment")
    fun `a stranger's commitment is refused as RELEASE_OF_ANOTHER_COMMITMENT`() {
        assertRefused(
            SettlementRejection.RELEASE_OF_ANOTHER_COMMITMENT,
            commitment = ReleaseFixtures.commitment(author = SettlementFixtures.stranger(OrderFixtures.ORDER_INDEX)),
        )
        assertRefused(
            SettlementRejection.RELEASE_OF_ANOTHER_COMMITMENT,
            commitment = ReleaseFixtures.commitment(author = SettlementFixtures.buyer(OrderFixtures.ORDER_INDEX)),
        )
    }

    /** The checks run in the documented order: the first that fails is the one reported. */
    @Test
    @JsName("the_release_refusals_are_reported_in_their_documented_order")
    fun `the release refusals are reported in their documented order`() {
        val stranger = ReleaseFixtures.commitment(author = SettlementFixtures.stranger(OrderFixtures.ORDER_INDEX))
        assertRefused(
            SettlementRejection.RELEASE_WITHOUT_EVIDENCE,
            commitment = stranger,
            payment = ReleaseFixtures.bitcoinSettlement(OrderFixtures.OTHER_ORDER_INDEX),
        )
        assertRefused(
            SettlementRejection.RELEASE_NOT_PROVIDER_PAYMENT,
            commitment = stranger,
            payment = OrderFixtures.receipt(Payee.FEE, index = OrderFixtures.OTHER_ORDER_INDEX),
        )
        assertRefused(
            SettlementRejection.RELEASE_FOR_ANOTHER_ORDER,
            commitment = stranger,
            payment = OrderFixtures.receipt(Payee.PROVIDER, index = OrderFixtures.OTHER_ORDER_INDEX),
        )
    }

    /** Once every gate passes, §10.2's key rule is `RumorWriter`'s, reported on `channelReason`. */
    @Test
    @JsName("a_malformed_key_after_the_gates_is_rumor_writer_s_refusal")
    fun `a malformed key after the gates is RumorWriter's refusal`() {
        val refused = refusal(
            PaymentWriter.release(
                ReleaseFixtures.providerEnvelope(),
                ReleaseFixtures.commitment(),
                OrderFixtures.receipt(Payee.PROVIDER),
                ReleaseFixtures.KEY_HEX.uppercase(),
                ReleaseFixtures.NONCE_HEX,
            ),
        )
        assertNull(refused.reason, "§10.2's encoding is not a settlement rule")
        assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL, refused.channelReason, "§10.2: lowercase hex on write")
    }

    // -----------------------------------------------------------------------------------------

    private fun assertRefused(
        expected: SettlementRejection,
        commitment: DeliveryCommitmentMessage = ReleaseFixtures.commitment(),
        payment: Settlement = OrderFixtures.receipt(Payee.PROVIDER),
    ) {
        val refused = refusal(
            PaymentWriter.release(
                ReleaseFixtures.providerEnvelope(),
                commitment,
                payment,
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
        )
        assertEquals(expected, refused.reason, "the refusal is named")
        assertNull(refused.channelReason, "a settlement refusal names no channel reason")
    }

    private fun built(build: PaymentBuild) = when (build) {
        is PaymentBuild.Built -> build.event
        is PaymentBuild.Refused -> fail("the release must build: ${build.reason} ${build.channelReason} — ${build.detail}")
    }

    private fun refusal(build: PaymentBuild): PaymentBuild.Refused = when (build) {
        is PaymentBuild.Refused -> build
        is PaymentBuild.Built -> fail("the release must be refused, but it built ${build.event.tags.size} tag(s)")
    }
}
