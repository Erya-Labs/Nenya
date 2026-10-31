package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.RumorBuild
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import kotlin.test.fail

/**
 * T65's fixtures: the provider's own §10.1 commitment as a rumor, the §10.2 key and nonce, and the
 * settlements `PaymentWriter.release` is handed.
 *
 * Nothing is typed (Definition of done). The commitment is built by `RumorWriter.commitment` over
 * `OrderFixtures.blob`'s generated values — the commitment every `OrderFixtures` order is committed
 * to — and attributed to the key that wrote it, which is how the provider's own copy comes back from
 * `GiftWrap.open`. The key and nonce are `DeliveryMessageFixtures`' digests. The settlements come out
 * of `Settlement.verify` and `Settlement.verifyFeeReceipt` through `OrderFixtures`, over invoices
 * `SettlementFixtures` derives (decision **D**).
 */
internal object ReleaseFixtures {

    /** §4.1's `created_at` on every message here: a reading before every `OrderFixtures` deadline. */
    const val CREATED_AT: Long = OrderFixtures.BEFORE_DEADLINES

    /** §10.2's 256-bit key, 64 lowercase hex characters, from a digest. */
    val KEY_HEX: String = DeliveryMessageFixtures.hex(DeliveryMessageFixtures.keyBytes(OrderFixtures.ORDER_INDEX))

    /** §10.2's 96-bit nonce, 24 lowercase hex characters, from a digest. */
    val NONCE_HEX: String =
        DeliveryMessageFixtures.hex(DeliveryMessageFixtures.nonceBytes(OrderFixtures.ORDER_INDEX))

    /** A §7.4 envelope written by [author] to the buyer of the order at [index]. */
    fun envelope(author: String, index: Int = OrderFixtures.ORDER_INDEX): RumorEnvelope = RumorEnvelope(
        authorPubkey = author,
        createdAt = CREATED_AT,
        counterparties = listOf(PubkeyRef(SettlementFixtures.buyer(index))),
    )

    /** The provider's envelope for the order at [index]: the key every release here is written under. */
    fun providerEnvelope(index: Int = OrderFixtures.ORDER_INDEX): RumorEnvelope =
        envelope(SettlementFixtures.provider(index), index)

    /**
     * §10.1's `type=5` for the order at [index], over `OrderFixtures.blob`, written and sealed by
     * [author] — the provider unless a control says otherwise.
     */
    fun commitmentRumor(
        author: String = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
        index: Int = OrderFixtures.ORDER_INDEX,
    ): AttributedRumor.Bound {
        val build = RumorWriter.commitment(
            envelope = envelope(author, index),
            order = OrderFixtures.orderId(index),
            commitment = OrderFixtures.blob.commitment,
            url = DeliveryMessageFixtures.url(index),
        )
        val built = build as? RumorBuild.Built ?: fail("the fixture commitment must build, not $build")
        return RumorWriterFixtures.bound(built.event)
    }

    /** [commitmentRumor], decoded. */
    fun commitment(
        author: String = SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
        index: Int = OrderFixtures.ORDER_INDEX,
    ): DeliveryCommitmentMessage = DeliveryCommitmentMessage.decode(commitmentRumor(author, index))

    /**
     * §9.4's `bitcoin` receipt for the provider on the order at [index], put through
     * `Settlement.verify`: `Unverified`, the one non-evidence a `Settlement` can be.
     *
     * The reference and proof are words, not encodings: §9.4 defines no rule for this rail and
     * nothing reads either.
     */
    fun bitcoinSettlement(index: Int = OrderFixtures.ORDER_INDEX): Settlement {
        val settlement = Settlement.verify(
            PaymentReceipt.decode(bitcoinReceiptRumor(index)),
            OrderFixtures.messages(index, stream = 0).store,
            OrderFixtures.TERMS.split,
        )
        if (settlement !is Settlement.Unverified) fail("a bitcoin receipt must be unverified, not $settlement")
        return settlement
    }

    /** The `kind:17` behind [bitcoinSettlement], sealed by the buyer. */
    fun bitcoinReceiptRumor(index: Int = OrderFixtures.ORDER_INDEX): AttributedRumor.Bound =
        SettlementFixtures.boundSealedBy(
            SettlementFixtures.buyer(index),
            SettlementFixtures.receiptTags(
                index = index,
                payment = SettlementFixtures.paymentTag(
                    reference = "a transaction id this library does not read",
                    proof = "a proof §9.4 defines no rule for",
                    medium = PaymentMedium.BITCOIN.token ?: fail("bitcoin has a token"),
                ),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
            ),
            NenyaKind.RECEIPT,
        )
}
