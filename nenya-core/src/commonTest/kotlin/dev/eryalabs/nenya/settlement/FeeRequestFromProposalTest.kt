package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.PubkeyRef
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T87: the fee recipient's door for decision T — `PaymentWriter.feeRequestFromProposal` — made by a
 * Kotlin call over `OrderFixtures`' honest chain, with no JavaScript layer in between.
 *
 * The chain's `type=1` stands in for §8.7's copy: the copy is that rumor unchanged, so the fee recipient
 * decodes the same [OrderProposal] the provider does.
 */
class FeeRequestFromProposalTest {

    private val index = OrderFixtures.ORDER_INDEX
    private val chain = OrderFixtures.chain()
    private val buyer = SettlementFixtures.buyer(index)
    private val provider = SettlementFixtures.provider(index)
    private val feeRecipient = SettlementFixtures.feeRecipient(index)
    private val stranger = SettlementFixtures.stranger(index)

    private fun envelope(author: String): RumorEnvelope =
        RumorEnvelope(author, SettlementFixtures.ACCEPTED_AT, listOf(PubkeyRef(buyer)))

    private fun feeInvoice(proposal: OrderProposal = chain.proposal): Bolt11Reference =
        Bolt11Reference.recognise(SettlementFixtures.invoice(PaymentFixtures.preimageHex(1).single(), proposal.terms.split.fee))

    private fun assertRefused(
        answer: PaymentBuild,
        reason: SettlementRejection?,
        channelReason: ChannelRejection?,
        tag: String?,
        what: String,
    ) {
        val refused = answer as? PaymentBuild.Refused ?: fail("$what: must build nothing, built $answer")
        assertEquals(reason, refused.reason, what)
        assertEquals(channelReason, refused.channelReason, what)
        assertEquals(tag, refused.tag, what)
        for (key in listOf(buyer, provider, feeRecipient, stranger)) {
            assertFalse(key in refused.detail || key in refused.toString(), "$what: the refusal names no key")
        }
    }

    /**
     * The proof, Kotlin side: the request the fee recipient writes from the copy is the one
     * [PaymentWriter.paymentRequest] writes with the order id, the recipient and the terms typed, and
     * the buyer's `AcceptedPaymentRequest.accept` stores it under the chain's acceptance.
     */
    @Test
    @JsName("the_fee_request_written_from_the_copy_is_the_typed_one_and_the_buyer_accepts_it")
    fun `the fee request written from the copy is the typed one, and the buyer accepts it`() {
        val invoice = feeInvoice()
        val built = PaymentWriter.feeRequestFromProposal(envelope(feeRecipient), chain.proposal, invoice)
            as? PaymentBuild.Built ?: fail("the fee recipient's request must build")
        val typed = PaymentWriter.paymentRequest(
            envelope(feeRecipient),
            chain.proposal.order,
            Payee.FEE,
            invoice,
            feeRecipient = feeRecipient,
            terms = chain.terms,
        ) as? PaymentBuild.Built ?: fail("the typed request must build")
        assertEquals(typed.event.canonicalSerialisation(), built.event.canonicalSerialisation(), "nothing typed, same event")

        val request = SettlementFixtures.requestSealedBy(feeRecipient, built.event.tags, chain.terms.split)
        assertEquals(Payee.FEE, request.payee)
        val stored = AcceptedPaymentRequest.accept(
            request,
            chain.accepted,
            PaymentRequestStore.inMemory(),
            FakeClock(SettlementFixtures.ACCEPTED_AT),
            chain.signedPoints(),
        )
        assertEquals(chain.proposal.order, stored.order)
        assertEquals(invoice.text, stored.invoice.text)
    }

    /** Only the fee term's recipient writes it: the buyer, the provider and a stranger each build nothing. */
    @Test
    @JsName("an_envelope_author_other_than_the_fee_recipient_builds_nothing")
    fun `an envelope author other than the fee recipient builds nothing`() {
        for ((what, author) in listOf("the buyer" to buyer, "the provider" to provider, "a stranger" to stranger)) {
            assertRefused(
                PaymentWriter.feeRequestFromProposal(envelope(author), chain.proposal, feeInvoice()),
                null,
                ChannelRejection.NOT_THE_FEE_RECIPIENT,
                null,
                what,
            )
        }
    }

    /** No fee term, `["fee", "0"]` and §8.3's 1 bps on 3 000 msat each owe no fee: `PAYEE_NOT_REQUIRED`, naming `fee`. */
    @Test
    @JsName("a_proposal_with_no_fee_to_request_builds_nothing")
    fun `a proposal with no fee to request builds nothing`() {
        val zeroRate = OrderTerms.of(OrderFixtures.PRICE, FeeTerm.of(0), OrderFixtures.EXPIRATION, OrderFixtures.DELIVER_BY)
        for ((what, terms) in listOf(
            "no fee term" to OrderFixtures.absentFeeTerms,
            "a zero rate" to zeroRate,
            "a fee that rounds to 0" to OrderFixtures.zeroFeeTerms,
        )) {
            val proposal = OrderFixtures.chain(terms).proposal
            assertRefused(
                PaymentWriter.feeRequestFromProposal(
                    envelope(feeRecipient),
                    proposal,
                    Bolt11Reference.recognise(SettlementFixtures.invoice(PaymentFixtures.preimageHex(1).single(), Msat.ofMsat(1))),
                ),
                SettlementRejection.PAYEE_NOT_REQUIRED,
                null,
                ChannelVocabulary.FEE,
                what,
            )
        }
    }

    /** §9.2 check 4, at the writer: the price, one msat over the fee, and an any-amount invoice each build nothing. */
    @Test
    @JsName("an_invoice_for_any_amount_but_the_fee_builds_nothing")
    fun `an invoice for any amount but the fee builds nothing`() {
        val preimage = PaymentFixtures.preimageHex(1).single()
        val split = chain.terms.split
        for ((what, amount) in listOf(
            "the price" to split.price,
            "one msat over the fee" to split.fee + Msat.ofMsat(1),
            "no amount" to null,
        )) {
            assertRefused(
                PaymentWriter.feeRequestFromProposal(
                    envelope(feeRecipient),
                    chain.proposal,
                    Bolt11Reference.recognise(SettlementFixtures.invoice(preimage, amount)),
                ),
                if (amount == null) SettlementRejection.INVOICE_AMOUNT_MISSING else SettlementRejection.INVOICE_AMOUNT_MISMATCH,
                null,
                SettlementVocabulary.PAYMENT,
                what,
            )
        }
        assertTrue(
            PaymentWriter.feeRequestFromProposal(envelope(feeRecipient), chain.proposal, feeInvoice()) is PaymentBuild.Built,
            "the control: an invoice for exactly fee_msat builds",
        )
    }
}
