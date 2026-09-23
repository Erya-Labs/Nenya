package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.conformance.ConformanceStatus
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.2 check 6 in full — §8.4's byte-identical fee term across all four points, §8.7's sealing key
 * and §8.5's state precondition — with every fixture built from the codecs the rest of the library
 * actually produces.
 *
 * ### The points are parsed out of the document, not transcribed
 *
 * `FeeTermPoint` is held **equal** to §8.4's own numbered list and following sentence, parsed at
 * test time by [Section84], required and optional kept apart. So a codec covering two of the four
 * cannot pass here, and a §8.4 revision that added a point or moved one between REQUIRED and
 * OPTIONAL turns this red rather than leaving the model quietly stale.
 *
 * ### Fixtures come from T14 and T15, not from hand-assembled tags
 *
 * The proposal and the acceptance are decoded by T14's codec, the `type=2`s by T15's, the
 * `kind:17`s by T15's receipt decoder, and the order is driven through real §11.2 transitions by
 * `OrderFixtures`. That matters for one reason beyond tidiness: §8.4 compares the raw tag elements,
 * and a fixture that assembled those elements by hand would be comparing bytes this library never
 * emitted. The pubkeys are real BIP-340 keys out of the vendored `bip340-vectors.csv`, as
 * everywhere else.
 */
class FeeTermCheckTest {

    private companion object {

        /** §8.3's own worked rate, and the one `OrderFixtures.TERMS` is built on. */
        const val BASIS_POINTS: Int = OrderFixtures.FEE_BASIS_POINTS

        /** §8.3's divisor: `fee_msat = floor(price_msat × bps / 10000)`. */
        const val BPS_DIVISOR: Long = 10_000L

        /** §9.2 check 6's three obligations, spelled here so the derived constant has an anchor. */
        val CHECK_SIX: Set<PaymentCheck> = setOf(
            PaymentCheck.FEE_TERM_MATCH,
            PaymentCheck.FEE_SEALING_KEY,
            PaymentCheck.FEE_STATE_PRECONDITION,
        )
    }

    /**
     * One whole fee-bearing order as messages: §8.4's four REQUIRED points and both OPTIONAL
     * `payee=provider` ones, every one of them decoded.
     *
     * The seals are the ones the document requires: the proposal is the buyer's, the acceptance
     * the provider's, the **fee** `type=2` the fee recipient's own (§8.7), the provider `type=2`
     * the provider's, and both receipts the buyer's — §9.2's worked `kind:17` carries
     * `"pubkey": "<buyer-pubkey-hex>"` and its closing paragraph says it is the payer who sends it.
     */
    private class FeeOrderMessages(
        val index: Int = 0,
        val basisPoints: Int? = BASIS_POINTS,
        val priceMsat: Long = SettlementFixtures.PRICE_MSAT,
        val feeOnFeeRequest: List<String>? = ProposalFixtures.feeTag(0, BASIS_POINTS),
        val feeOnFeeReceipt: List<String>? = ProposalFixtures.feeTag(0, BASIS_POINTS),
        val feeOnProviderRequest: List<String>? = null,
        val feeOnProviderReceipt: List<String>? = null,
        val feeRequestSealedBy: String = SettlementFixtures.feeRecipient(0),

        /**
         * Millisatoshis to add to the fee invoice's amount, for §9.2 check 4's control on this path.
         *
         * Zero on every other fixture, which is what makes the fee invoice one check 4 accepts.
         * A non-zero value builds a fee `type=2` asking for something other than §8.3's `fee_msat`
         * — the padded invoice a fee recipient sends and nothing but check 4 catches.
         */
        val feeAmountDelta: Long = 0L,

        /**
         * The reading the injected clock held when both `type=2`s were accepted (§9.2 check 1).
         *
         * §9.2 check 5's only operand. Defaulted to the value every derived invoice here is built
         * to be live at, and moved past the deadline by the one control that is about check 5.
         */
        val acceptedAt: Long = SettlementFixtures.ACCEPTED_AT,
    ) {

        val feeTag: List<String>? = basisPoints?.let { ProposalFixtures.feeTag(index, it) }

        val proposalTags: List<List<String>> = ProposalFixtures.proposalTags(
            index = index,
            amountMsat = priceMsat.toString(),
            fee = feeTag,
        )

        val proposal: OrderProposal = ProposalFixtures.proposal(proposalTags, index)

        val acceptance: OrderStatusMessage = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, index),
            index + 1,
        )

        /**
         * §7.6's answer, checked against the provider key the caller resolved — the value
         * `AcceptedPaymentRequest.accept` derives the order, the provider's key and §9.2 check 4's
         * expected amount from.
         */
        val accepted: Acceptance.Accepted = proposal.accepts(acceptance, SettlementFixtures.provider(index))
            .let { it as? Acceptance.Accepted ?: fail("the fixture acceptance must accept, not $it") }

        val split = SettlementFixtures.split(priceMsat, basisPoints)

        val preimages: List<String> = PaymentFixtures.preimageHex(2)

        /**
         * The fee `type=2`'s invoice and the provider's, each for the amount §9.2 check 4 will
         * demand of it — `fee_msat` and `price_msat` off [split].
         *
         * The fee side is the "any amount" form when the computed fee is zero, and that is the
         * honest shape rather than a dodge: §8.3 says no fee invoice may exist at all for a
         * zero-amount payee and BOLT-11 can express no amount of zero, so there is nothing else to
         * build. Every such fixture is refused for [SettlementRejection.PAYEE_NOT_REQUIRED] before
         * check 4 is reached, which is the refusal those tests are about.
         */
        val invoices: List<String> = listOf(
            SettlementFixtures.invoice(
                preimages[0],
                split.fee.takeIf { it > Msat.ZERO }?.let { Msat.ofMsat(it.millisatoshis + feeAmountDelta) },
            ),
            SettlementFixtures.invoice(preimages[1], split.price),
        )

        /**
         * Lazy, because §8.3 and §8.6 make a fee `type=2` **unbuildable** for a zero-fee order —
         * `PaymentRequest.decode` refuses one, which is T15's own control. A fixture for §8.1's
         * absent-`fee` proposal has a proposal and an acceptance and no fee messages at all, and
         * eagerly constructing one would throw inside the fixture rather than inside the test.
         */
        val feeRequest: PaymentRequest by lazy {
            SettlementFixtures.requestSealedBy(
                feeRequestSealedBy,
                SettlementFixtures.requestTags(
                    index = index,
                    payment = SettlementFixtures.requestPaymentTag(invoices[0]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.FEE, index),
                    fee = feeOnFeeRequest,
                ),
                split,
            )
        }

        val providerRequest: PaymentRequest = SettlementFixtures.requestSealedBy(
            SettlementFixtures.provider(index),
            SettlementFixtures.requestTags(
                index = index,
                payment = SettlementFixtures.requestPaymentTag(invoices[1]),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
                fee = feeOnProviderRequest,
            ),
            split,
        )

        /** Lazy for the same reason as [feeRequest], and stored alongside it. */
        val feeReceipt: PaymentReceipt by lazy {
            SettlementFixtures.receiptSealedBy(
                SettlementFixtures.buyer(index),
                SettlementFixtures.receiptTags(
                    index = index,
                    payment = SettlementFixtures.paymentTag(invoices[0], preimages[0]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.FEE, index),
                    fee = feeOnFeeReceipt,
                ),
            )
        }

        val providerReceipt: PaymentReceipt = SettlementFixtures.receiptSealedBy(
            SettlementFixtures.buyer(index),
            SettlementFixtures.receiptTags(
                index = index,
                payment = SettlementFixtures.paymentTag(invoices[1], preimages[1]),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
                fee = feeOnProviderReceipt,
            ),
        )

        val feePaymentHash: PaymentHash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimages[0]))

        /** §8.4's points before the fee receipt: the proposal, the acceptance and the fee `type=2`. */
        fun earlierPoints(): List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(acceptance),
            FeeTermSighting.onPaymentRequest(feeRequest),
        )

        /**
         * Both `type=2`s accepted and stored, at a pinned clock reading (§17 item 6).
         *
         * Since revision `1.5` this is a **checking** door rather than a recording one: the fee
         * request goes through §8.4 and §8.7 and both go through §9.2 checks 4 and 5, so a fixture
         * built to break one of those throws here instead of storing. [storeRefusal] is what the
         * four controls about those breakages now assert on.
         */
        fun store(): PaymentRequestStore {
            val store = PaymentRequestStore.inMemory()
            val clock = FakeClock(acceptedAt)
            AcceptedPaymentRequest.accept(feeRequest, accepted, store, clock, signedPoints())
            AcceptedPaymentRequest.accept(providerRequest, accepted, store, clock)
            return store
        }

        /** §8.4's two points that precede any `type=2` — the signed term §8.7 compares against. */
        fun signedPoints(): List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(acceptance),
        )

        /** The refusal [store] made, failing loudly if it stored instead. */
        fun storeRefusal(): SettlementException = assertFailsWith { store() }

        /** The order these messages are about, in [state], reached by real §11.2 transitions. */
        fun order(state: OrderState = OrderState.AWAITING_PAYMENT, terms: OrderTerms = terms()): Order =
            OrderFixtures.orders(terms)[state] ?: fail("the fixture chain did not reach $state")

        fun terms(): OrderTerms = OrderTerms.of(
            Msat.ofMsat(priceMsat),
            if (basisPoints == null) FeeTerm.Absent else FeeTerm.of(basisPoints),
            OrderFixtures.EXPIRATION,
            OrderFixtures.DELIVER_BY,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The anchor: §8.4's seven points, parsed.
    // ---------------------------------------------------------------------------------------

    @JsName("the_modelled_points_are_the_ones_s8_4_names_required_and_optional_kept_apart")
    @Test
    fun `the modelled points are the ones §8_4 names, required and optional kept apart`() {
        val required = Section84.requiredDescriptors
        val optional = Section84.optionalDescriptors

        assertTrue(required.isNotEmpty(), "§8.4's REQUIRED list parsed to nothing at all")
        assertTrue(optional.isNotEmpty(), "§8.4's OPTIONAL sentence parsed to nothing at all")
        assertEquals(
            required,
            FeeTermPoint.REQUIRED.map { it.descriptor },
            "the modelled REQUIRED points must be §8.4's own numbered list, in its order. A codec " +
                "covering two of the four cannot pass this, and a §8.4 revision turns it red.",
        )
        assertEquals(
            optional.toSet(),
            FeeTermPoint.OPTIONAL.map { it.descriptor }.toSet(),
            "and the OPTIONAL ones must be the three §8.4's following sentence names",
        )
        assertEquals(
            FeeTermPoint.entries.toSet(),
            FeeTermPoint.REQUIRED + FeeTermPoint.OPTIONAL,
            "every modelled point is one or the other, and none is both",
        )
        assertTrue(
            FeeTermPoint.REQUIRED.none { it in FeeTermPoint.OPTIONAL },
            "REQUIRED and OPTIONAL are kept apart: a point in both would satisfy either assertion",
        )
    }

    @JsName("s8_4_still_requires_a_divergence_abort_the_order_and_forbids_the_three_wrong_answers")
    @Test
    fun `§8_4 still requires a divergence abort the order, and forbids the three wrong answers`() {
        val clause = Section84.divergenceClause

        assertTrue(
            "terms mismatch" in clause,
            "§8.4 no longer calls a divergence a terms mismatch, which is what the named outcome " +
                "in this package exists to carry: $clause",
        )
        for (wrongAnswer in listOf("take the newest", "take the smaller", "renegotiate silently")) {
            assertTrue(
                wrongAnswer in clause,
                "§8.4 no longer forbids \"$wrongAnswer\", which is one of the three an " +
                    "implementation reaches for when it is told only that something diverged",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The accepting direction, and what it may claim.
    // ---------------------------------------------------------------------------------------

    @JsName("a_fee_receipt_at_awaiting_payment_whose_pair_matches_everywhere_evidences_the_payment")
    @Test
    fun `a fee receipt at awaiting_payment whose pair matches everywhere evidences the payment`() {
        val messages = FeeOrderMessages()

        val settlement = Settlement.verifyFeeReceipt(
            messages.feeReceipt,
            messages.store(),
            messages.order(OrderState.AWAITING_PAYMENT),
            messages.earlierPoints(),
        )

        val evidenced = assertIs<Settlement.Evidenced>(settlement)
        assertEquals(Payee.FEE, evidenced.payee)
        assertEquals(
            Settlement.CHECKS_PERFORMED_ON_THE_FEE_RECEIPT_PATH,
            evidenced.checksPerformed,
            "check 1, T3's two, and check 6's three",
        )
        assertTrue(
            CHECK_SIX.all { it in evidenced.checksPerformed },
            "§8.4's fee term, §8.7's sealing key and §8.5's state precondition were all performed " +
                "on this path: ${evidenced.checksPerformed}",
        )
        assertEquals(
            emptySet<PaymentCheck>(),
            evidenced.checksNotPerformedHere,
            "nothing is left. This is the fullest path in the library and it now performs every " +
                "§9.2 check that applies to a fee receipt: checks 4 and 5 left this expectation " +
                "when it began parsing the stored invoice, and check 3's provenance left it when " +
                "the function stopped taking a payment hash at all",
        )
        assertEquals(
            messages.feePaymentHash,
            evidenced.payment.paymentHash,
            "the hash check 3 ran against is the fee invoice's `p` field. Nothing here handed one " +
                "in — this door takes none. On its own this equality discriminates nothing, " +
                "because `VerifiedPayment.verify` throws unless the operand hashes from the proof " +
                "and the proof is this preimage; what makes it a statement about *provenance* is " +
                "the refusing control below, which keeps the operand and the proof apart",
        )
        assertEquals(
            Msat.ofMsat(SettlementFixtures.PRICE_MSAT * BASIS_POINTS / BPS_DIVISOR),
            messages.split.fee,
            "and the invoice check 4 accepted really was for §8.3's `fee_msat` — a positive " +
                "control over an amount computed here rather than read off the split it is " +
                "checking, so a split that had gone wrong could not make itself agree",
        )
    }

    /**
     * **Probe G1b inverted, on the fee door.** The stored fee invoice, and the *provider's*
     * preimage.
     *
     * `Settlement.verify`'s copy of this is `SettlementCheckOneTest`'s, and it does not reach here:
     * [Settlement.verifyFeeReceipt] reads check 3's operand at its own call site, out of its own
     * parse, so the two are independently deletable. Without this control, replacing this door's
     * operand with `SHA-256(receipt.preimage)` — the caller-supplied shape probe G1b exploited —
     * leaves every test in this repository green while the result goes on reporting
     * `PaymentCheck.PAYMENT_HASH_PROVENANCE` performed and its `checksNotPerformedHere` empty. That
     * result is the one `OrderMachine` consumes for a fee payee, and half of why §17 item 6 is
     * `PERFORMED_HERE`, so it is the door that most needs the refusal proved rather than assumed.
     *
     * The receipt names the **stored** fee invoice, so check 1 matches and checks 4 and 5 pass on
     * it; only its `<proof>` is another derived invoice's preimage. What refuses is check 3,
     * reaching the caller as T3 threw it.
     */
    @JsName("a_fee_receipt_proving_another_invoices_preimage_is_refused_by_check_3")
    @Test
    fun `a fee receipt proving another invoice's preimage is refused by check 3`() {
        val messages = FeeOrderMessages()
        assertNotEquals(
            messages.preimages[0],
            messages.preimages[1],
            "the two fixtures must carry genuinely different preimages, or this proves nothing",
        )

        val crossed = SettlementFixtures.receiptSealedBy(
            SettlementFixtures.buyer(messages.index),
            SettlementFixtures.receiptTags(
                index = messages.index,
                // The fee invoice, verbatim — check 1 must pass — and the provider's preimage.
                payment = SettlementFixtures.paymentTag(messages.invoices[0], messages.preimages[1]),
                payeeTag = SettlementFixtures.payeeTag(Payee.FEE, messages.index),
                fee = messages.feeOnFeeReceipt,
            ),
        )

        val refused = assertFailsWith<PaymentException> {
            Settlement.verifyFeeReceipt(
                crossed,
                messages.store(),
                messages.order(OrderState.AWAITING_PAYMENT),
                messages.earlierPoints(),
            )
        }

        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "check 3's operand on this door is the stored invoice's `p`, and there is no parameter " +
                "through which the matching hash could be supplied instead",
        )

        // And the pairing: the same door, the same store, the same order — with the fee invoice's
        // own preimage it evidences. So what refused above is the preimage and not the door.
        assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(OrderState.AWAITING_PAYMENT),
                messages.earlierPoints(),
            ),
        )
    }

    /**
     * The deployed shape: **one** store holding both of an order's invoices, and both receipts
     * settled against it.
     *
     * The two doors are exercised apart everywhere else in this repository, and `OrderFixtures`
     * builds its two receipts against two separate stores because each is drawn at its own preimage
     * stream. Neither is the shape a client runs: §8.6's two `type=2`s for one order land in one
     * `PaymentRequestStore`, keyed by `(order, payee)`, and the two receipts are looked up in it one
     * after the other.
     *
     * What that arrangement can get wrong is the lookup key, and only a single store can show it. A
     * `verify` that keyed on the order alone, or that read whichever record it found first, would
     * hold the fee receipt to `price_msat` (refusing check 4) or compare the provider's preimage
     * against the fee invoice's `p` (refusing check 3) — and every two-store test in this suite
     * would stay green, because with one record present there is nothing to confuse it with.
     *
     * The two payment hashes are asserted distinct, which is what separates this from the
     * duplicated-payment control: there the two invoices deliberately share a `p`.
     */
    @JsName("both_receipts_of_one_order_settle_against_one_store_each_against_its_own_invoice")
    @Test
    fun `both receipts of one order settle against one store, each against its own invoice`() {
        val messages = FeeOrderMessages()
        val store = messages.store()
        val order = messages.order(OrderState.AWAITING_PAYMENT)

        val provider = assertIs<Settlement.Evidenced>(
            Settlement.verify(messages.providerReceipt, store, messages.split),
        )
        val fee = assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(messages.feeReceipt, store, order, messages.earlierPoints()),
        )

        assertEquals(Payee.PROVIDER, provider.payee)
        assertEquals(Payee.FEE, fee.payee)
        assertNotEquals(
            provider.payment.paymentHash,
            fee.payment.paymentHash,
            "§8.6 is two payments to two invoices. Equal hashes here would mean the two lookups " +
                "found one record, which is the failure this single store exists to expose",
        )
        assertEquals(
            PaymentFixtures.paymentHashOf(Preimage.ofHex(messages.preimages[1])),
            provider.payment.paymentHash,
            "the provider's operand is the provider invoice's `p`",
        )
        assertEquals(
            messages.feePaymentHash,
            fee.payment.paymentHash,
            "and the fee recipient's is the fee invoice's — the store was asked for each payee's " +
                "own record and answered with it",
        )
        assertEquals(emptySet<PaymentCheck>(), provider.checksNotPerformedHere)
        assertEquals(emptySet<PaymentCheck>(), fee.checksNotPerformedHere)
    }

    /**
     * §9.2 check 4 **on the fee path**, refusing — which is what makes the claim above a fact.
     *
     * The happy path asserts that a `verifyFeeReceipt` result names `INVOICE_AMOUNT` as performed.
     * That set is composed from a constant, so a positive fixture alone cannot tell "checked and
     * matched" from "not checked at all": deleting the check-4 call on this path would leave every
     * assertion in this file green and the library over-claiming, which is exactly the §17 failure
     * the record exists to prevent. This is the control that closes it.
     *
     * The fee recipient's invoice asks for §8.3's `fee_msat` **plus one millisatoshi** — the padded
     * invoice a fee recipient sends, which passes §8.4's term comparison (the `fee` tag is
     * byte-identical everywhere), passes §8.7's seal, passes §8.5's state precondition and carries
     * a preimage that hashes. Check 4 is the only thing between it and an `Evidenced`.
     *
     * ### Since revision `1.5` it is caught one message earlier, and both doors are asserted
     *
     * Decision I as amended applies check 4 to the `type=2` at acceptance, so the padded invoice
     * never reaches the store at all — which is the better outcome, because the buyer has not yet
     * been shown a bill. The first half below asserts that. The second half keeps
     * `Settlement.verifyFeeReceipt`'s own copy of check 4 falsifiable, by handing it an [Order]
     * whose terms owe the fee recipient a different `fee_msat`: that path reads the expected amount
     * off `order.terms.split` and off nothing else, so a fee receipt judged against another order's
     * terms is refused there. Without it, deleting the check-4 call on the fee-receipt path would
     * leave this file green and the result over-claiming `INVOICE_AMOUNT`.
     */
    @JsName("a_padded_fee_invoice_is_refused_at_acceptance_and_check_4_still_bites_at_settlement")
    @Test
    fun `a padded fee invoice is refused at acceptance, and check 4 still bites at settlement`() {
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            FeeOrderMessages(feeAmountDelta = 1L).storeRefusal().reason,
            "§9.2 check 4 is performed on the `type=2` as well as on the receipt (decision I as " +
                "amended), so a fee recipient's padded invoice is refused before it is stored",
        )

        val messages = FeeOrderMessages()
        val otherTerms = OrderTerms.of(
            Msat.ofMsat(messages.priceMsat),
            FeeTerm.of(BASIS_POINTS + 1),
            OrderFixtures.EXPIRATION,
            OrderFixtures.DELIVER_BY,
        )
        assertNotEquals(
            messages.split.fee,
            otherTerms.split.fee,
            "the two terms must owe the fee recipient different amounts, or the refusal below " +
                "would be check 4 agreeing with itself",
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(OrderState.AWAITING_PAYMENT, otherTerms),
                messages.earlierPoints(),
            )
        }

        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            refused.reason,
            "§9.2 check 4 applies to a fee receipt exactly as it does to a provider one, and the " +
                "expected amount is §8.3's `fee_msat` read off the order this path was handed",
        )
    }

    /**
     * §9.2 check 5 on the fee path, for the reason the control above exists.
     *
     * Both `type=2`s are accepted one second past the fee invoice's own `timestamp + expiry`, so
     * the fee invoice was already dead when this implementation took it — and check 5's operand is
     * that acceptance reading and nothing later.
     *
     * Since revision `1.5` the refusal is made **at acceptance**, and unlike check 4 there is no
     * shape that also puts it on the receipt path: `verifyFeeReceipt` measures against
     * `AcceptedPaymentRequest.acceptedAt`, which is the very reading `accept` measured against, so
     * for a record this library minted the two can never disagree. The receipt path keeps the check
     * for an injected store that hands back a record this library never minted (§13).
     */
    @JsName("a_fee_invoice_already_expired_at_acceptance_is_refused_at_acceptance")
    @Test
    fun `a fee invoice already expired at acceptance is refused at acceptance`() {
        val deadline = SettlementFixtures.ACCEPTED_AT -
            SettlementFixtures.INVOICE_AGE_SECONDS + SettlementFixtures.INVOICE_EXPIRY_SECONDS

        val refused = FeeOrderMessages(acceptedAt = deadline + 1L).storeRefusal()
        assertEquals(SettlementRejection.INVOICE_EXPIRED, refused.reason)

        // The pair that makes it the boundary and not merely "some late reading": at the deadline
        // itself the same fixture evidences, so what refused above is the one extra second.
        assertIs<Settlement.Evidenced>(
            FeeOrderMessages(acceptedAt = deadline).let {
                Settlement.verifyFeeReceipt(
                    it.feeReceipt,
                    it.store(),
                    it.order(OrderState.AWAITING_PAYMENT),
                    it.earlierPoints(),
                )
            },
            "an invoice accepted in its final second was live, and `>` at the boundary would " +
                "refuse it",
        )
    }

    @JsName("the_derived_check_six_set_is_the_three_obligations_s9_2_numbers_6")
    @Test
    fun `the derived check-six set is the three obligations §9_2 numbers 6`() {
        assertEquals(
            CHECK_SIX,
            Settlement.CHECK_SIX,
            "the set is derived as what a fee receipt carries and a provider receipt does not, " +
                "rather than listed; if T3's two constants stop differing by exactly these three, " +
                "the derivation is wrong and this is where it shows",
        )
        assertEquals(
            Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH + CHECK_SIX,
            Settlement.CHECKS_PERFORMED_ON_THE_FEE_RECEIPT_PATH,
        )
    }

    @JsName("a_bare_verify_on_the_same_fee_receipt_still_names_check_6_as_not_performed")
    @Test
    fun `a bare verify on the same fee receipt still names check 6 as not performed`() {
        // The §17 rule this task must not break from the other end: `Settlement.verify` performs
        // no part of check 6, and its result must keep saying so however much the fee path does.
        val messages = FeeOrderMessages()

        val evidenced = assertIs<Settlement.Evidenced>(
            Settlement.verify(
                messages.feeReceipt,
                messages.store(),
                messages.split,
            ),
        )

        assertTrue(
            CHECK_SIX.all { it in evidenced.checksNotPerformedHere },
            "a caller that used the general entry point got no check 6, and the record must say " +
                "so: ${evidenced.checksNotPerformedHere}",
        )
        assertFalse(
            PaymentCheck.FEE_TERM_MATCH in VerifiedPayment.CHECKS_PERFORMED_HERE,
            "T3's global claim is about a bare preimage comparison and is not widened by this task",
        )
        assertTrue(
            CHECK_SIX.all { it in Capabilities.PAYMENT_CHECKS_NOT_PERFORMED },
            "the conformance surface derives its union from that constant by subtraction, so all " +
                "three stay in it — what changed is §17 item 5's own claim, not the union",
        )
    }

    @JsName("s17_item_5_no_longer_names_the_fee_term_check_and_item_6_is_unchanged")
    @Test
    fun `§17 item 5 no longer names the fee-term check, and item 6 is unchanged`() {
        val item5 = Capabilities.item(5) ?: fail("§17 item 5 is not published")
        val item6 = Capabilities.item(6) ?: fail("§17 item 6 is not published")

        assertEquals(ConformanceStatus.PARTIAL, item5.status)
        assertFalse(
            PaymentCheck.FEE_TERM_MATCH in item5.notPerformed,
            "§8.4's consistency check against a receipt is performed now; item 5 named it as " +
                "missing for want of the receipt codec, and the codec exists",
        )
        assertTrue(
            item5.notPerformed.isNotEmpty(),
            "item 5 is still PARTIAL — a signed fee term needs BIP-340 verification and §8.1's " +
                "reachability check needs a relay query — so it must still name what is missing",
        )
        assertEquals(
            ConformanceStatus.PERFORMED_HERE,
            item6.status,
            "item 6 governs reaching `paid`, and every §9.2 check is now performed on the path " +
                "`OrderMachine` consumes — the store path's four, T3's two inside them, and check " +
                "6's three through `verifyFeeReceipt`",
        )
        assertEquals(
            emptySet<Enum<*>>(),
            item6.notPerformed,
            "so it has no constant left to point at. What item 5 still names is a different " +
                "shortfall — a signed fee term needs BIP-340 verification — and the two items " +
                "moving apart is the distinction this test exists to keep",
        )
    }

    // ---------------------------------------------------------------------------------------
    // §8.4: the pair, byte-identical, at all four points.
    // ---------------------------------------------------------------------------------------

    @JsName("the_same_bps_with_a_different_recipient_at_the_acceptance_is_a_terms_mismatch")
    @Test
    fun `the same bps with a different recipient at the acceptance is a terms mismatch`() {
        // The divergence a checker comparing the proposal against the receipt never sees, in the
        // half §8.4 exists for: the basis points agree everywhere, so a comparison that read only
        // the number would call this order consistent while the money went somewhere else.
        val messages = FeeOrderMessages()
        val substituted = listOf(
            ChannelVocabulary.FEE,
            BASIS_POINTS.toString(),
            SettlementFixtures.feeRecipient(messages.index + 7),
        )
        val acceptance = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(
                ProposalFixtures.replacing(messages.proposalTags, substituted),
                messages.index,
            ),
            messages.index + 1,
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(),
                listOf(
                    FeeTermSighting.onProposal(messages.proposal),
                    FeeTermSighting.onAcceptance(acceptance),
                    FeeTermSighting.onPaymentRequest(messages.feeRequest),
                ),
            )
        }

        assertEquals(SettlementRejection.FEE_TERM_MISMATCH, refused.reason)
        val diverged = assertIs<FeeTermAgreement.Diverged>(
            refused.feeTermDivergence,
            "§8.4 requires the divergence be surfaced as a terms mismatch, which means the point " +
                "has to survive the throw",
        )
        assertEquals(FeeTermPoint.ACCEPTANCE, diverged.point)
        assertEquals(FeeTermElement.RECIPIENT, diverged.element)
    }

    /**
     * §8.4 at the fee `type=2`, which since revision `1.5` is refused when the request is
     * **accepted** rather than when a receipt for it is judged.
     *
     * The point and the side still travel on the exception, which is what §8.4 requires ("surfaced
     * to the user as a terms mismatch"), and the point named is still `FEE_PAYMENT_REQUEST` — so
     * what moved is the door and not the answer. `Settlement.checkFeePaymentRequest` is the same
     * function it always was; `AcceptedPaymentRequest.accept` now calls it, so a fee request with a
     * re-quoted term is never stored and no receipt for it can exist.
     */
    @JsName("a_different_bps_at_the_fee_type_2_is_a_terms_mismatch_naming_that_point")
    @Test
    fun `a different bps at the fee type=2 is a terms mismatch naming that point`() {
        val messages = FeeOrderMessages(
            feeOnFeeRequest = ProposalFixtures.feeTag(0, BASIS_POINTS + 1),
        )

        val refused = messages.storeRefusal()

        assertEquals(SettlementRejection.FEE_TERM_MISMATCH, refused.reason)
        val diverged = assertIs<FeeTermAgreement.Diverged>(refused.feeTermDivergence)
        assertEquals(FeeTermPoint.FEE_PAYMENT_REQUEST, diverged.point)
        assertEquals(FeeTermElement.BASIS_POINTS, diverged.element)
    }

    @JsName("a_fee_type_2_arriving_at_all_for_a_stated_zero_fee_proposal_is_refused")
    @Test
    fun `a fee type=2 arriving at all for a stated-zero-fee proposal is refused`() {
        // §8.3 and §8.6: when the expected fee amount is 0 no fee payment request exists at all,
        // and any that arrives MUST be rejected. §8.1's read rule reaches the same answer for a
        // proposal carrying no `fee` tag; both are the same clause, read out of
        // `Payee.requiredPayees` rather than re-derived.
        for (basisPoints in listOf<Int?>(0, null)) {
            val refused = assertFailsWith<SettlementException>("bps=$basisPoints") {
                SettlementFixtures.requestSealedBy(
                    SettlementFixtures.feeRecipient(0),
                    SettlementFixtures.requestTags(
                        payment = SettlementFixtures.requestPaymentTag(
                            SettlementFixtures.defaultInvoice(),
                        ),
                        payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
                        fee = basisPoints?.let { ProposalFixtures.feeTag(0, it) },
                    ),
                    SettlementFixtures.split(SettlementFixtures.PRICE_MSAT, basisPoints),
                )
            }

            assertEquals(SettlementRejection.PAYEE_NOT_REQUIRED, refused.reason)
        }
    }

    @JsName("a_non_canonical_fee_basis_point_value_never_reaches_a_s8_4_comparison")
    @Test
    fun `a non-canonical fee basis-point value never reaches a §8_4 comparison`() {
        // §8.4 defines divergence with `["fee","250",X]` against `["fee","0250",X]`, and the
        // reason that comparison is safe to make byte-for-byte is that the second never survives
        // the parse: §4.4's strict decimal form is what keeps the byte-identical comparison
        // honest. Reached here from §8.4's consumer — there is no proposal to take a sighting
        // from, because T9 refused the tag one layer down.
        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(
                    fee = listOf(ChannelVocabulary.FEE, "0250", ProposalFixtures.pubkey(2)),
                ),
            )
        }

        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        val cause = assertIs<TagException>(refused.cause, "§4.4's own reason must survive")
        assertEquals(TagRejection.MALFORMED_NUMBER, cause.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §8.4's OPTIONAL clause, which is a MUST NOT and breaks conformant peers when it is missed.
    // ---------------------------------------------------------------------------------------

    @JsName("a_provider_request_and_a_provider_receipt_carrying_no_fee_tag_are_accepted")
    @Test
    fun `a provider request and a provider receipt carrying no fee tag are accepted`() {
        // §8.4: "An implementation MUST NOT require a `fee` tag on any of the three." §9.2's own
        // worked `kind:17` is a provider receipt and "correctly carries no `fee` tag", so an
        // over-strict checker aborts an order over the document's own example. The failure is
        // closed, it only ever refuses conformant peers, and no test written from §8.4's REQUIRED
        // list alone would see it.
        val messages = FeeOrderMessages()

        val agreement = FeeTermAgreement.across(
            listOf(
                FeeTermSighting.onProposal(messages.proposal),
                FeeTermSighting.onAcceptance(messages.acceptance),
                FeeTermSighting.onPaymentRequest(messages.providerRequest),
                FeeTermSighting.onPaymentRequest(messages.feeRequest),
                FeeTermSighting.onReceipt(messages.providerReceipt),
                FeeTermSighting.onReceipt(messages.feeReceipt),
            ),
        )

        val agreed = assertIs<FeeTermAgreement.Agreed>(agreement)
        assertEquals(
            listOf(
                FeeTermPoint.ORDER_PROPOSAL,
                FeeTermPoint.ACCEPTANCE,
                FeeTermPoint.FEE_PAYMENT_REQUEST,
                FeeTermPoint.FEE_RECEIPT,
            ),
            agreed.points,
            "the two provider points carried no `fee` tag, so they were not compared — and the " +
                "answer says which points were, rather than implying all six",
        )
        assertEquals(SettlementFixtures.feeRecipient(messages.index), agreed.recipient)
    }

    @JsName("a_provider_receipt_carrying_a_fee_tag_that_disagrees_is_rejected")
    @Test
    fun `a provider receipt carrying a fee tag that disagrees is rejected`() {
        // The other half of the same sentence: "but where one **is** present it MUST match".
        val messages = FeeOrderMessages(
            feeOnProviderReceipt = ProposalFixtures.feeTag(0, BASIS_POINTS + 1),
        )

        val diverged = assertIs<FeeTermAgreement.Diverged>(
            FeeTermAgreement.across(
                listOf(
                    FeeTermSighting.onProposal(messages.proposal),
                    FeeTermSighting.onAcceptance(messages.acceptance),
                    FeeTermSighting.onReceipt(messages.providerReceipt),
                ),
            ),
        )

        assertEquals(FeeTermPoint.PROVIDER_RECEIPT, diverged.point)
        assertEquals(FeeTermElement.BASIS_POINTS, diverged.element)
    }

    @JsName("check_6_does_not_apply_to_a_provider_receipt_at_all")
    @Test
    fun `check 6 does not apply to a provider receipt at all`() {
        val messages = FeeOrderMessages()

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.providerReceipt,
                messages.store(),
                messages.order(),
                messages.earlierPoints(),
            )
        }

        assertEquals(SettlementRejection.PAYEE_IS_NOT_FEE, refused.reason)
        assertNotEquals(
            SettlementRejection.FEE_TERM_MISMATCH,
            refused.reason,
            "a provider receipt is not a terms mismatch; §8.4 makes its `fee` tag OPTIONAL",
        )
    }

    // ---------------------------------------------------------------------------------------
    // §8.7: the sealing key, and the shape that makes it matter.
    // ---------------------------------------------------------------------------------------

    @JsName("a_fee_type_2_sealed_by_the_provider_is_refused_as_a_forwarded_fee_invoice")
    @Test
    fun `a fee type=2 sealed by the provider is refused as a forwarded fee invoice`() {
        val messages = FeeOrderMessages(feeRequestSealedBy = SettlementFixtures.provider(0))

        // The `payee` tag itself is present, well formed and names the right recipient — the
        // request decoded — so the only thing wrong is who sealed it.
        assertEquals(Payee.FEE, messages.feeRequest.payee)
        assertEquals(SettlementFixtures.feeRecipient(0), messages.feeRequest.feeRecipient)
        assertEquals(SettlementFixtures.provider(0), messages.feeRequest.sender)

        val refused = assertFailsWith<SettlementException> {
            Settlement.checkFeePaymentRequest(
                messages.feeRequest,
                listOf(
                    FeeTermSighting.onProposal(messages.proposal),
                    FeeTermSighting.onAcceptance(messages.acceptance),
                ),
            )
        }

        assertEquals(
            SettlementRejection.FEE_SEAL_NOT_RECIPIENT,
            refused.reason,
            "§8.7 names this shape exactly: a fee invoice forwarded by the provider MUST be " +
                "rejected",
        )
        assertNotEquals(
            SettlementRejection.MISSING_REQUIRED_TAG,
            refused.reason,
            "the two an implementer conflates: the `payee` tag is there",
        )
        assertNotEquals(
            SettlementRejection.MALFORMED_PAYEE_RECIPIENT,
            refused.reason,
            "and it is well formed — a peer told otherwise goes and fixes a tag that was correct",
        )
    }

    @JsName("a_fee_type_2_sealed_by_the_named_recipient_passes_s8_7_and_yields_the_agreed_term")
    @Test
    fun `a fee type=2 sealed by the named recipient passes §8_7 and yields the agreed term`() {
        val messages = FeeOrderMessages()

        val agreed = Settlement.checkFeePaymentRequest(
            messages.feeRequest,
            listOf(
                FeeTermSighting.onProposal(messages.proposal),
                FeeTermSighting.onAcceptance(messages.acceptance),
            ),
        )

        assertEquals(SettlementFixtures.feeRecipient(0), agreed.recipient)
        assertEquals(agreed.recipient, messages.feeRequest.sender, "which is §8.7's equality")
        assertEquals(
            listOf(
                FeeTermPoint.ORDER_PROPOSAL,
                FeeTermPoint.ACCEPTANCE,
                FeeTermPoint.FEE_PAYMENT_REQUEST,
            ),
            agreed.points,
        )
    }

    /**
     * §8.7 on the way **into** the store, which is where §8.7 itself puts it — and since revision
     * `1.5` there is no way past it.
     *
     * The control above proves `Settlement.checkFeePaymentRequest` refuses a forwarded fee invoice
     * when a caller asks it to. This one proves a caller cannot decline to ask:
     * `AcceptedPaymentRequest.accept` runs the same function, so the forwarded invoice is never
     * stored and no `kind:17` settling it can reach check 1.
     *
     * `verifyFeeReceipt` keeps its own §8.7 comparison against
     * [AcceptedPaymentRequest.sealedBy] — a store is the embedding client's own persistence and
     * §13 does not defend against it — and for a record this library minted the two operands now
     * agree by construction, which is why the refusal is asserted here rather than there.
     */
    @JsName("a_fee_type_2_forwarded_by_the_provider_never_reaches_the_store")
    @Test
    fun `a fee type=2 forwarded by the provider never reaches the store`() {
        val messages = FeeOrderMessages(feeRequestSealedBy = SettlementFixtures.provider(0))

        val refused = messages.storeRefusal()

        assertEquals(SettlementRejection.FEE_SEAL_NOT_RECIPIENT, refused.reason)
    }

    /**
     * §9.2 check 6's second third, at **settlement** — the copy of §8.7 `verifyFeeReceipt` makes
     * against [AcceptedPaymentRequest.sealedBy], which nothing else in this suite can falsify since
     * revision `1.5`.
     *
     * ### Why it needs a fixture this contrived, and why it is not contrived at all
     *
     * For a record this library minted the two operands of that comparison now agree by
     * construction: `accept` refuses a fee `type=2` unless its seal is the recipient in the signed
     * term, so `stored.sealedBy` *is* the agreed recipient. Every ordinary route therefore passes
     * the check without exercising it, and the branch would survive deletion — which it did, until
     * this control. `Settlement.verifyFeeReceipt` states the narrowing that opens it: the
     * `earlierPoints` a caller hands in are **not** checked against the order the receipt names, and
     * "sightings taken from another order's messages agree with each other, and only the caller
     * knows which order they came from".
     *
     * So this is that caller: the store holds **this** order's fee invoice, sealed by this order's
     * fee recipient, and the sightings — including the receipt's own `fee` tag — all name
     * *another* order's recipient. §8.4 is satisfied, because they agree with each other; §8.7 is
     * not, because the key that sealed the stored invoice is not the one they name. That is not a
     * synthetic shape either: it is a client that assembled one order's history against another
     * order's receipt, and §13 is why the library has to fail closed on it rather than trust the
     * assembly.
     */
    @JsName("a_fee_receipt_whose_signed_term_names_another_recipient_than_the_stored_invoices_seal")
    @Test
    fun `a fee receipt whose signed term names another recipient than the stored invoice's seal`() {
        // `feeOnFeeRequest` defaults to index 0's term, so the second order has to be told its own
        // — otherwise its three sightings diverge among themselves and §8.4 speaks before §8.7,
        // which is the right answer to a different question.
        val other = FeeOrderMessages(index = 1, feeOnFeeRequest = ProposalFixtures.feeTag(1, BASIS_POINTS))
        val own = FeeOrderMessages(feeOnFeeReceipt = other.feeTag)

        assertNotEquals(
            SettlementFixtures.feeRecipient(0),
            SettlementFixtures.feeRecipient(1),
            "the two orders must name different fee recipients, or there is nothing to disagree " +
                "about",
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                own.feeReceipt,
                own.store(),
                own.order(),
                other.earlierPoints(),
            )
        }

        assertEquals(
            SettlementRejection.FEE_SEAL_NOT_RECIPIENT,
            refused.reason,
            "§8.7's operand at settlement is the seal the **stored** `type=2` arrived under, and " +
                "it is not the recipient this order's signed term names. Refusing here is what " +
                "stops a fee being credited against an invoice somebody else issued",
        )

        // The pair, so the refusal is about the disagreement and not about the shape: the same
        // receipt judged against its **own** order's points and store evidences.
        val consistent = FeeOrderMessages()
        assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(
                consistent.feeReceipt,
                consistent.store(),
                consistent.order(),
                consistent.earlierPoints(),
            ),
        )
    }

    @JsName("the_fee_receipt_is_the_buyers_and_that_is_why_its_own_seal_is_not_the_operand")
    @Test
    fun `the fee receipt is the buyer's, and that is why its own seal is not the operand`() {
        // The control that keeps the reading above honest: the conformant fee receipt this suite
        // accepts is sealed by the buyer and NOT by the fee recipient, so a check reading the
        // receipt's own seal would have refused it.
        val messages = FeeOrderMessages()

        assertEquals(SettlementFixtures.buyer(0), messages.feeReceipt.sender)
        assertNotEquals(
            SettlementFixtures.feeRecipient(0),
            messages.feeReceipt.sender,
            "§9.2's worked kind:17 carries the buyer's pubkey and says it is the payer who sends " +
                "it; a literal reading of check 6 against this seal deadlocks every fee-bearing " +
                "order",
        )
        assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(),
                messages.earlierPoints(),
            ),
        )
    }

    @JsName("a_fee_type_2_compared_against_nothing_but_itself_is_refused_and_never_agreed")
    @Test
    fun `a fee type=2 compared against nothing but itself is refused, and never agreed`() {
        // The attack the §8.4 sequence exists to stop, and the one a presence guard is the only
        // thing standing in front of: a stranger seals a fee `type=2`, names **themselves** in
        // both its `["payee", "fee", …]` tag and its own `["fee", …]` tag, and the caller has no
        // earlier points to offer. Compared against itself the term agrees with itself and §8.7's
        // equality holds — out of a term nobody signed. So the sequence is refused instead.
        val stranger = SettlementFixtures.feeRecipient(0)
        val ownTerm = ProposalFixtures.feeTag(0, BASIS_POINTS)
        val forged = SettlementFixtures.requestSealedBy(
            stranger,
            SettlementFixtures.requestTags(
                payment = SettlementFixtures.requestPaymentTag(SettlementFixtures.defaultInvoice()),
                payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
                fee = ownTerm,
            ),
            SettlementFixtures.split(),
        )
        // The message is internally consistent, which is the whole point: nothing about it alone
        // is wrong, and only the signed term it is missing can say so.
        assertEquals(stranger, forged.sender)
        assertEquals(stranger, forged.feeRecipient)

        val refused = assertFailsWith<SettlementException> {
            Settlement.checkFeePaymentRequest(forged, emptyList())
        }

        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, refused.reason)
        assertEquals(SettlementVocabulary.FEE, refused.tag)
    }

    @JsName("a_fee_receipt_is_refused_when_a_s8_4_point_it_cannot_exist_without_is_missing")
    @Test
    fun `a fee receipt is refused when a §8_4 point it cannot exist without is missing`() {
        val messages = FeeOrderMessages()
        val all = messages.earlierPoints()

        // Each of §8.4's three earlier points dropped in turn, one at a time.
        for (dropped in all.indices) {
            val short = all.filterIndexed { index, _ -> index != dropped }

            val refused = assertFailsWith<SettlementException>("dropping ${all[dropped].point}") {
                Settlement.verifyFeeReceipt(
                    messages.feeReceipt,
                    messages.store(),
                    messages.order(),
                    short,
                )
            }

            assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, refused.reason)
        }

        // And the empty list, which is the shape that would have agreed with itself.
        val alone = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(),
                emptyList(),
            )
        }
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, alone.reason)
    }

    @JsName("a_s9_4_rail_on_the_fee_path_is_unverified_and_claims_no_part_of_check_6")
    @Test
    fun `a §9_4 rail on the fee path is unverified, and claims no part of check 6`() {
        // §9.4: `bitcoin` and `ecash` are parsed and evidence nothing, so a result claiming check
        // 6 had been performed against one would be work recorded against a payment this library
        // reports as unverified. The branch is taken before check 6 runs, exactly as `verify`
        // takes it before check 1 — and an `Unverified` is not an accepted receipt, so §8.5 is
        // not being sidestepped by it.
        val messages = FeeOrderMessages()
        val onAnotherRail = SettlementFixtures.receiptSealedBy(
            SettlementFixtures.buyer(0),
            SettlementFixtures.receiptTags(
                payment = SettlementFixtures.paymentTag(
                    "whatever-that-rail-uses",
                    "its-own-proof",
                    PaymentMedium.BITCOIN.token!!,
                ),
                payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
                fee = ProposalFixtures.feeTag(0, BASIS_POINTS),
            ),
        )

        val settlement = Settlement.verifyFeeReceipt(
            onAnotherRail,
            messages.store(),
            messages.order(),
            messages.earlierPoints(),
        )

        val unverified = assertIs<Settlement.Unverified>(settlement)
        assertEquals(emptySet<PaymentCheck>(), unverified.checksPerformed)
        assertTrue(
            CHECK_SIX.all { it in unverified.checksNotPerformedHere },
            "check 6 applies to a fee receipt and was not performed on this rail, so all three " +
                "stay on the not-performed side: ${unverified.checksNotPerformedHere}",
        )

        // "Exactly the answer `verify` gives it" is the claim the reordering rests on, so it is
        // asserted rather than said: the two entry points must agree on a §9.4 receipt in both
        // directions, and neither may claim any check.
        val throughVerify = assertIs<Settlement.Unverified>(
            Settlement.verify(
                onAnotherRail,
                messages.store(),
                messages.split,
            ),
        )
        assertEquals(throughVerify.checksPerformed, unverified.checksPerformed)
        assertEquals(throughVerify.checksNotPerformedHere, unverified.checksNotPerformedHere)

        // And the combinations the reordering newly made reachable: the branch is taken before
        // §8.5, before the zero-fee rule and before the point-presence guard, so each answers
        // `Unverified` rather than throwing. None of them accepts anything — §9.4 says a payment
        // on such a rail MUST be treated as unverified, and an `Unverified` carries no evidence
        // that could advance an order in any state.
        assertIs<Settlement.Unverified>(
            Settlement.verifyFeeReceipt(
                onAnotherRail,
                messages.store(),
                messages.order(OrderState.COMMITTED),
                emptyList(),
            ),
        )
        assertIs<Settlement.Unverified>(
            Settlement.verifyFeeReceipt(
                onAnotherRail,
                messages.store(),
                messages.order(OrderState.AWAITING_PAYMENT, OrderFixtures.zeroFeeTerms),
                messages.earlierPoints(),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // §8.5: the state precondition, and the pairing §11.3 invariant 2 is about.
    // ---------------------------------------------------------------------------------------

    @JsName("a_fee_receipt_presented_while_the_order_is_committed_is_refused")
    @Test
    fun `a fee receipt presented while the order is committed is refused`() {
        val messages = FeeOrderMessages()

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(OrderState.COMMITTED),
                messages.earlierPoints(),
            )
        }

        assertEquals(SettlementRejection.FEE_RECEIPT_STATE_NOT_AWAITING_PAYMENT, refused.reason)
        assertNull(refused.tag, "§8.5's rule is about a state and names no tag")
    }

    /**
     * **The coverage T20 had to suspend, restored.**
     *
     * §9.2 check 6 says "the state MUST already be `awaiting_payment`", which is an equality and
     * not §8.5's "before". Past that state a fee receipt is a duplicate or a replay, it evidences
     * nothing the order does not already carry, and refusing it is the fail-closed direction. The
     * constant is named for the equality rather than for "before", so the refusal does not state
     * the opposite of what happened.
     *
     * T20 could not test it: decision B held every fee-bearing order at `awaiting_payment`, and the
     * only orders reaching `paid`, `released` and `settled` were free ones, which owe no fee at all
     * — so `verifyFeeReceipt` answered `PAYEE_NOT_REQUIRED` first and the state rule was never
     * consulted. Rewriting it to assert that answer would have read as "the state rule is covered"
     * while covering something else, so T20 asserted the fact that did hold and said so in its
     * commit. Now that check 3's operand comes out of the stored invoice a priced order reaches
     * those three states, and the original assertion is the one that runs.
     *
     * The order the state is read off is this library's own [Order] (§8.5), not a status token a
     * counterparty sent — which is the whole of check 6's third obligation and the reason the
     * fixture drives a real machine to each state rather than naming one.
     */
    @JsName("a_fee_receipt_arriving_once_the_order_is_already_past_awaiting_payment_is_refused_too")
    @Test
    fun `a fee receipt arriving once the order is already past awaiting_payment is refused too`() {
        val messages = FeeOrderMessages()
        val terms = messages.terms()
        assertTrue(
            Payee.FEE in Payee.requiredPayees(terms.split),
            "this test is about a fee-bearing order, or `PAYEE_NOT_REQUIRED` answers first and " +
                "check 6's state precondition is never reached",
        )

        for (state in listOf(OrderState.PAID, OrderState.RELEASED, OrderState.SETTLED)) {
            val order = messages.order(state, terms)
            assertEquals(
                setOf(Payee.PROVIDER, Payee.FEE),
                Payee.requiredPayees(order.terms.split),
                "the order in $state must still owe a fee, or this is the wrong refusal",
            )

            val refused = assertFailsWith<SettlementException>("at $state") {
                Settlement.verifyFeeReceipt(
                    messages.feeReceipt,
                    messages.store(),
                    order,
                    messages.earlierPoints(),
                )
            }

            assertEquals(SettlementRejection.FEE_RECEIPT_STATE_NOT_AWAITING_PAYMENT, refused.reason)
        }
    }

    @JsName("the_paired_positive_control_the_fee_payment_request_is_accepted_at_committed")
    @Test
    fun `the paired positive control — the fee payment request is accepted at committed`() {
        // §11.3 invariant 2, whole. §8.5: "A rule that rejected the fee `type=2` while the order
        // was still `committed` would therefore make `awaiting_payment` unreachable for every
        // fee-bearing order, and would make this document's own worked order (Appendix A, step 6)
        // illegal." So the pairing is the test, and neither half means anything alone.
        val messages = FeeOrderMessages()
        val machine = OrderFixtures.machineBeforeDeadlines()
        val committed = messages.order(OrderState.COMMITTED)
        val required = Payee.requiredPayees(messages.terms().split)
        assertTrue(Payee.FEE in required, "this order must owe a fee, or the pairing is about nothing")

        // The request is accepted at `committed`: §8.4 and §8.7 both pass on it, and §11.2 moves
        // the order on it.
        Settlement.checkFeePaymentRequest(
            messages.feeRequest,
            listOf(
                FeeTermSighting.onProposal(messages.proposal),
                FeeTermSighting.onAcceptance(messages.acceptance),
            ),
        )
        val awaiting = OrderFixtures.advanced(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(OrderFixtures.chain(messages.terms()).requests),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        // And never earlier: the same event from `accepted` is refused by §11.2's own table.
        val accepted = messages.order(OrderState.ACCEPTED)
        OrderFixtures.refusal(
            machine,
            accepted,
            OrderEvent.PaymentRequestsReceived(OrderFixtures.chain(messages.terms()).requests),
        )

        // The receipt, which is the half §8.5 does refuse, is now accepted at `awaiting_payment`.
        assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                awaiting,
                messages.earlierPoints(),
            ),
        )
    }

    @JsName("a_fee_receipt_for_an_order_whose_fee_msat_is_zero_is_refused")
    @Test
    fun `a fee receipt for an order whose fee_msat is zero is refused`() {
        // §8.3's own worked case: at `bps = 1` and `price_msat = 3000` the fee is
        // `floor(3000 × 1 / 10000) = 0`, so the terms name a fee payee for whom no invoice may
        // legally exist. The request-side control is T15's; this is its receipt-side mirror.
        val messages = FeeOrderMessages()
        val zeroFee = OrderFixtures.zeroFeeTerms
        assertEquals(Msat.ZERO, zeroFee.split.fee, "§8.3's rounding is what makes this case exist")
        assertFalse(zeroFee.split.feePayeeRequired)

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.store(),
                messages.order(OrderState.AWAITING_PAYMENT, zeroFee),
                messages.earlierPoints(),
            )
        }

        assertEquals(SettlementRejection.PAYEE_NOT_REQUIRED, refused.reason)
    }

    // ---------------------------------------------------------------------------------------
    // The sightings a real message produces are the ones the property corpus models.
    // ---------------------------------------------------------------------------------------

    @JsName("a_sighting_taken_off_a_decoded_message_is_the_raw_tag_that_message_carried")
    @Test
    fun `a sighting taken off a decoded message is the raw tag that message carried`() {
        val stated = FeeOrderMessages()
        val absent = FeeOrderMessages(
            basisPoints = null,
            feeOnFeeRequest = null,
            feeOnFeeReceipt = null,
        )

        // The plumbing the property corpus takes on trust: a sighting off T14's proposal codec
        // and T15's request codec carries exactly the elements the event carried, and the point
        // is derived from the message's own `payee` tag rather than supplied by the caller.
        assertEquals(
            FeeTermSighting.of(FeeTermPoint.ORDER_PROPOSAL, stated.feeTag).term,
            FeeTermSighting.onProposal(stated.proposal).term,
        )
        assertEquals(
            FeeTermSighting.of(FeeTermPoint.ACCEPTANCE, stated.feeTag).term,
            FeeTermSighting.onAcceptance(stated.acceptance).term,
        )
        assertEquals(
            FeeTermPoint.FEE_PAYMENT_REQUEST,
            FeeTermSighting.onPaymentRequest(stated.feeRequest).point,
        )
        assertEquals(
            FeeTermPoint.PROVIDER_PAYMENT_REQUEST,
            FeeTermSighting.onPaymentRequest(stated.providerRequest).point,
        )
        assertEquals(FeeTermPoint.FEE_RECEIPT, FeeTermSighting.onReceipt(stated.feeReceipt).point)
        assertEquals(
            FeeTermPoint.PROVIDER_RECEIPT,
            FeeTermSighting.onReceipt(stated.providerReceipt).point,
        )

        // §8.1's read rule, through the codec: a proposal with no `fee` tag is a deliberate
        // absence and not a missing operand, and the acceptance omits it exactly when the
        // proposal did (§7.6).
        assertNull(FeeTermSighting.onProposal(absent.proposal).term)
        assertNull(FeeTermSighting.onAcceptance(absent.acceptance).term)
        val agreed = assertIs<FeeTermAgreement.Agreed>(
            FeeTermAgreement.across(
                listOf(
                    FeeTermSighting.onProposal(absent.proposal),
                    FeeTermSighting.onAcceptance(absent.acceptance),
                ),
            ),
        )
        assertNull(agreed.recipient, "the pair for such an order is `(0, —)`, which names nobody")
    }

    @JsName("a_status_update_that_is_not_an_acceptance_is_not_s8_4s_second_point")
    @Test
    fun `a status update that is not an acceptance is not §8_4's second point`() {
        val messages = FeeOrderMessages()
        val cancelled = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(
                messages.proposalTags,
                messages.index,
                status = OrderState.CANCELLED.token,
            ),
            messages.index + 1,
        )

        val refused = assertFailsWith<SettlementException> { FeeTermSighting.onAcceptance(cancelled) }

        assertEquals(SettlementRejection.NOT_AN_ACCEPTANCE, refused.reason)
        assertEquals(SettlementVocabulary.STATUS, refused.tag)
    }

    @JsName("a_raw_sighting_that_is_not_a_fee_tag_at_one_of_s8_1s_arities_is_refused")
    @Test
    fun `a raw sighting that is not a fee tag at one of §8_1's arities is refused`() {
        val notAFeeTag = assertFailsWith<SettlementException> {
            FeeTermSighting.of(FeeTermPoint.BID, listOf(ChannelVocabulary.PRICE, "1"))
        }
        assertEquals(SettlementRejection.MALFORMED_FEE_TERM, notAFeeTag.reason)

        for (arity in listOf(listOf(ChannelVocabulary.FEE), List(4) { ChannelVocabulary.FEE })) {
            val refused = assertFailsWith<SettlementException> {
                FeeTermSighting.of(FeeTermPoint.BID, arity)
            }
            assertEquals(SettlementRejection.MALFORMED_FEE_TERM, refused.reason)
            assertEquals(ChannelVocabulary.FEE, refused.tag)
        }
    }
}
