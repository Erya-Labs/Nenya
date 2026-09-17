package dev.eryalabs.nenya.settlement

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
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
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

        val split = SettlementFixtures.split(priceMsat, basisPoints)

        val invoices: List<String> = SettlementFixtures.invoices(2)

        val preimages: List<String> = PaymentFixtures.preimageHex(2)

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

        /** Both `type=2`s accepted and stored, at a pinned clock reading (§17 item 6). */
        fun store(): PaymentRequestStore {
            val store = PaymentRequestStore.inMemory()
            val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
            AcceptedPaymentRequest.accept(feeRequest, store, clock)
            AcceptedPaymentRequest.accept(providerRequest, store, clock)
            return store
        }

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
            messages.feePaymentHash,
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
            setOf(
                PaymentCheck.PAYMENT_HASH_PROVENANCE,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
            ),
            evidenced.checksNotPerformedHere,
            "what is left is check 3's provenance and checks 4 and 5, all three of which need the " +
                "BOLT-11 parser this library does not have — and a check may never be on both " +
                "sides of the same statement. The provenance is what this expectation gained: the " +
                "fullest path in the library still takes the payment hash as a parameter",
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
            Settlement.verify(messages.feeReceipt, messages.feePaymentHash, messages.store()),
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
            ConformanceStatus.PARTIAL,
            item6.status,
            "item 6 governs reaching `paid` and is otherwise unchanged by this task",
        )
        assertEquals(
            setOf(
                PaymentCheck.INVOICE_IDENTITY,
                PaymentCheck.PAYMENT_HASH_PROVENANCE,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
            ),
            item6.notPerformed,
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
                messages.feePaymentHash,
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

    @JsName("a_different_bps_at_the_fee_type_2_is_a_terms_mismatch_naming_that_point")
    @Test
    fun `a different bps at the fee type=2 is a terms mismatch naming that point`() {
        val messages = FeeOrderMessages(
            feeOnFeeRequest = ProposalFixtures.feeTag(0, BASIS_POINTS + 1),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.feePaymentHash,
                messages.store(),
                messages.order(),
                messages.earlierPoints(),
            )
        }

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
                            SettlementFixtures.invoices(1).single(),
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
                PaymentFixtures.paymentHashOf(Preimage.ofHex(messages.preimages[1])),
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

    @JsName("a_fee_receipt_settling_an_invoice_that_arrived_from_the_provider_is_refused")
    @Test
    fun `a fee receipt settling an invoice that arrived from the provider is refused`() {
        // §9.2 check 6's second third, at settlement. The operand is the seal the **stored**
        // `type=2` arrived under, because the receipt's own seal is the buyer's (§9.2's example)
        // and a check that required otherwise would refuse every conformant fee receipt.
        val messages = FeeOrderMessages(feeRequestSealedBy = SettlementFixtures.provider(0))

        val refused = assertFailsWith<SettlementException> {
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.feePaymentHash,
                messages.store(),
                messages.order(),
                messages.earlierPoints(),
            )
        }

        assertEquals(SettlementRejection.FEE_SEAL_NOT_RECIPIENT, refused.reason)
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
                messages.feePaymentHash,
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
                payment = SettlementFixtures.requestPaymentTag(SettlementFixtures.invoices(1).single()),
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
                    messages.feePaymentHash,
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
                messages.feePaymentHash,
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
            messages.feePaymentHash,
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
            Settlement.verify(onAnotherRail, messages.feePaymentHash, messages.store()),
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
                messages.feePaymentHash,
                messages.store(),
                messages.order(OrderState.COMMITTED),
                emptyList(),
            ),
        )
        assertIs<Settlement.Unverified>(
            Settlement.verifyFeeReceipt(
                onAnotherRail,
                messages.feePaymentHash,
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
                messages.feePaymentHash,
                messages.store(),
                messages.order(OrderState.COMMITTED),
                messages.earlierPoints(),
            )
        }

        assertEquals(SettlementRejection.FEE_RECEIPT_STATE_NOT_AWAITING_PAYMENT, refused.reason)
        assertNull(refused.tag, "§8.5's rule is about a state and names no tag")
    }

    @JsName("a_fee_receipt_arriving_once_the_order_is_already_past_awaiting_payment_is_refused_too")
    @Test
    fun `a fee receipt arriving once the order is already past awaiting_payment is refused too`() {
        // §9.2 check 6 says "the state MUST already be `awaiting_payment`", which is an equality
        // and not §8.5's "before". Past that state a fee receipt is a duplicate or a replay, it
        // evidences nothing the order does not already carry, and refusing it is the fail-closed
        // direction. The constant is named for the equality rather than for "before", so the
        // refusal does not state the opposite of what happened.
        val messages = FeeOrderMessages()

        for (state in listOf(OrderState.PAID, OrderState.RELEASED, OrderState.SETTLED)) {
            val refused = assertFailsWith<SettlementException>("at $state") {
                Settlement.verifyFeeReceipt(
                    messages.feeReceipt,
                    messages.feePaymentHash,
                    messages.store(),
                    messages.order(state),
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
            OrderEvent.PaymentRequestsReceived(required),
        )
        assertEquals(OrderState.AWAITING_PAYMENT, awaiting.state)

        // And never earlier: the same event from `accepted` is refused by §11.2's own table.
        val accepted = messages.order(OrderState.ACCEPTED)
        OrderFixtures.refusal(machine, accepted, OrderEvent.PaymentRequestsReceived(required))

        // The receipt, which is the half §8.5 does refuse, is now accepted at `awaiting_payment`.
        assertIs<Settlement.Evidenced>(
            Settlement.verifyFeeReceipt(
                messages.feeReceipt,
                messages.feePaymentHash,
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
                messages.feePaymentHash,
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
