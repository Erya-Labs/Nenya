package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.conformance.ConformanceStatus
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.2 check 1 against the store, §9.4's other rails, and the §17 honesty rule the composition must
 * not break.
 *
 * Check 1 has **three** outcomes and this file keeps them apart: matched, not byte-identical, and
 * *no such request was stored*. §9.2 names the third separately for a reason a caller acts on —
 * told "mismatch" it looks for a wrong invoice, told this it looks for a `type=2` its store never
 * saw. The uppercase case is a fourth answer inside the second, and it exists because §4.3 permits
 * no case folding here while permitting it on the payment hash one line away.
 */
class SettlementCheckOneTest {

    private companion object {

        /** §9.2 check 6's three obligations, which apply to a fee receipt and to nothing else. */
        val CHECK_SIX: Set<PaymentCheck> = setOf(
            PaymentCheck.FEE_TERM_MATCH,
            PaymentCheck.FEE_SEALING_KEY,
            PaymentCheck.FEE_STATE_PRECONDITION,
        )

        /** The bech32 character U+017F folds onto under a Unicode case-insensitive comparison. */
        const val LONG_S_FOLD: Char = 's'

        /** LATIN SMALL LETTER LONG S, which is outside the bech32 alphabet and is not uppercase. */
        const val LONG_S: Char = 'ſ'

        /** How many fixtures to search for one carrying an `s`. Every derived invoice runs to
         * hundreds of bech32 characters and the alphabet has thirty-two of them, so missing `s`
         * in all sixteen is not a case anyone will meet — and the search fails loudly if it does. */
        const val HOMOGLYPH_SEARCH: Int = 16

        /**
         * §8.3's split every fixture invoice in this file was built for — check 4's expected
         * amounts, selected by the receipt's own payee.
         *
         * This file is about §9.2 check 1, so every control here wants check 4 to pass and get out
         * of the way. `SettlementCheckFourAndFiveTest` is where a split that disagrees with the
         * invoice is the point.
         */
        val SPLIT: FeeSplit = SettlementFixtures.split()
    }

    /** One stored request and the receipt that settles it, both from the seeded corpus. */
    private class Settled(
        val fixture: SettlementFixtures.Fixture,
        val store: PaymentRequestStore,
    )

    private fun settled(index: Int = 0): Settled {
        val fixture = SettlementFixtures.pairs(index + 1)[index]
        val store = PaymentRequestStore.inMemory()
        AcceptedPaymentRequest.accept(
            SettlementFixtures.request(fixture.requestTags),
            store,
            FakeClock(SettlementFixtures.ACCEPTED_AT),
        )
        return Settled(fixture, store)
    }

    // ---------------------------------------------------------------------------------------
    // The accepting direction, and what it may claim.
    // ---------------------------------------------------------------------------------------

    @JsName("a_receipt_matching_its_stored_request_evidences_the_payment")
    @Test
    fun `a receipt matching its stored request evidences the payment`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)

        val settlement = Settlement.verify(receipt, settled.fixture.paymentHash, settled.store, SPLIT)

        val evidenced = assertIs<Settlement.Evidenced>(settlement)
        assertEquals(settled.fixture.payee, evidenced.payee)
        assertEquals(PaymentMedium.LIGHTNING, evidenced.medium)
        assertEquals(settled.fixture.paymentHash, evidenced.payment.paymentHash)
    }

    @JsName("the_result_records_the_union_of_check_1_and_t3s_two")
    @Test
    fun `the result records the union of check 1 and T3's two`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)

        val evidenced = assertIs<Settlement.Evidenced>(
            Settlement.verify(receipt, settled.fixture.paymentHash, settled.store, SPLIT),
        )

        assertEquals(
            VerifiedPayment.CHECKS_PERFORMED_HERE + setOf(
                PaymentCheck.INVOICE_IDENTITY,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
            ),
            evidenced.checksPerformed,
            "checks 1, 4 and 5 were performed on this path and T3's two were performed inside it; " +
                "the set is composed from T3's rather than listed here",
        )
        assertEquals(
            Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
            evidenced.checksPerformed,
            "and the published constant is the same set, so a caller can branch on it",
        )
        assertFalse(
            PaymentCheck.INVOICE_IDENTITY in evidenced.checksNotPerformedHere,
            "a check cannot be on both sides of the same statement",
        )
        // Exact, not containment: this path subtracts checks 1, 4 and 5 and must subtract nothing
        // else, and only an exact set can say so. PAYMENT_HASH_PROVENANCE is what is left — check
        // 3's operand is still the caller's parameter however well check 1 went, and parsing the
        // stored invoice for its *amount* does not make it otherwise.
        assertEquals(
            setOf(PaymentCheck.PAYMENT_HASH_PROVENANCE),
            evidenced.checksNotPerformedHere,
            "what is left is check 3's provenance alone: this path parses the stored invoice for " +
                "checks 4 and 5 and still takes the payment hash as a parameter",
        )
    }

    @JsName("a_fee_receipt_still_names_check_6s_three_obligations_and_a_provider_receipt_does_not")
    @Test
    fun `a fee receipt still names check 6's three obligations, and a provider receipt does not`() {
        val provider = settled(index = 0)
        val fee = settled(index = 1)
        assertEquals(Payee.PROVIDER, provider.fixture.payee)
        assertEquals(Payee.FEE, fee.fixture.payee, "the corpus must cover both roles")

        val providerResult = assertIs<Settlement.Evidenced>(
            Settlement.verify(
                SettlementFixtures.receipt(provider.fixture.receiptTags),
                provider.fixture.paymentHash,
                provider.store,
                SPLIT,
            ),
        )
        val feeResult = assertIs<Settlement.Evidenced>(
            Settlement.verify(
                SettlementFixtures.receipt(fee.fixture.receiptTags),
                fee.fixture.paymentHash,
                fee.store,
                SPLIT,
            ),
        )

        assertTrue(
            CHECK_SIX.all { it in feeResult.checksNotPerformedHere },
            "§8.4's fee term, §8.7's sealing key and §8.5's state precondition are all unperformed " +
                "here: ${feeResult.checksNotPerformedHere}",
        )
        assertTrue(
            CHECK_SIX.none { it in providerResult.checksNotPerformedHere },
            "check 6 does not apply to a provider receipt at all, and recording an inapplicable " +
                "obligation as not-performed is a different false statement",
        )
        // And exactly, on both sides: the fee result is the provider result plus check 6 and
        // nothing else. A containment assertion alone cannot see a check dropped from the record,
        // which is what this file's own §17 rule is about.
        assertEquals(
            setOf(PaymentCheck.PAYMENT_HASH_PROVENANCE),
            providerResult.checksNotPerformedHere,
        )
        assertEquals(
            providerResult.checksNotPerformedHere + CHECK_SIX,
            feeResult.checksNotPerformedHere,
            "the same provenance, plus check 6's three — composed from the provider's record " +
                "rather than listed again",
        )
    }

    /**
     * The §17 honesty rule this task must not break, asserted from both ends.
     *
     * The mutation the queue names is to widen `VerifiedPayment.CHECKS_PERFORMED_HERE` to include
     * `INVOICE_IDENTITY`. A bare `VerifiedPayment.verify` performs no check 1, so that would be a
     * global claim about work only this package's store path does — and it would silently empty
     * §17 item 6's not-performed set, because the conformance surface derives it by subtraction.
     */
    @JsName("t3s_global_claim_is_unchanged_and_s17_item_6_still_names_invoice_identity")
    @Test
    fun `T3's global claim is unchanged, and §17 item 6 still names invoice identity`() {
        assertFalse(
            PaymentCheck.INVOICE_IDENTITY in VerifiedPayment.CHECKS_PERFORMED_HERE,
            "a bare VerifiedPayment.verify performs neither check 1 nor anything else this package " +
                "adds; widening its constant would be the §17 over-claim",
        )
        assertTrue(
            PaymentCheck.INVOICE_IDENTITY in Capabilities.PAYMENT_CHECKS_NOT_PERFORMED,
            "the conformance surface derives its set from that constant by subtraction",
        )

        val item = Capabilities.item(6) ?: fail("§17 item 6 is not published")
        assertEquals(ConformanceStatus.PARTIAL, item.status)
        assertTrue(
            PaymentCheck.INVOICE_IDENTITY in item.notPerformed,
            "the item is a claim about a bare VerifiedPayment.verify, which holds no store and " +
                "compares no invoice string, so check 1 stays where it is",
        )
        assertTrue(
            PaymentCheck.PAYMENT_HASH_PROVENANCE in item.notPerformed,
            "and check 3's provenance stays for a stronger reason — no path in this library " +
                "subtracts it, because nothing here parses the invoice's `p` field",
        )
        assertTrue(
            PaymentCheck.PAYMENT_HASH_PROVENANCE in Capabilities.PAYMENT_CHECKS_NOT_PERFORMED,
            "the derived union picks the new constant up by subtraction, and must",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Check 1's three outcomes.
    // ---------------------------------------------------------------------------------------

    @JsName("an_invoice_differing_by_one_character_is_refused_as_not_byte_identical")
    @Test
    fun `an invoice differing by one character is refused as not byte-identical`() {
        val settled = settled()
        val altered = settled.fixture.invoice.dropLast(1) + otherBech32(settled.fixture.invoice.last())
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                settled.fixture.receiptTags,
                SettlementFixtures.paymentTag(altered, settled.fixture.preimageHex),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, settled.fixture.paymentHash, settled.store, SPLIT)
        }

        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, refused.reason)
    }

    @JsName("the_same_invoice_in_uppercase_is_refused_as_uppercase_and_never_folded")
    @Test
    fun `the same invoice in uppercase is refused as uppercase, and never folded`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                settled.fixture.receiptTags,
                SettlementFixtures.paymentTag(
                    settled.fixture.invoice.uppercase(),
                    settled.fixture.preimageHex,
                ),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, settled.fixture.paymentHash, settled.store, SPLIT)
        }

        assertEquals(
            SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED,
            refused.reason,
            "§9.2 check 1 names case folding among the things it forbids and §4.3 lists the BOLT-11 " +
                "string as one of exactly two values where an uppercase form is rejected outright. " +
                "Comparing the two case-insensitively is the mutation this control exists for",
        )
    }

    @JsName("a_reference_differing_by_a_unicode_homoglyph_is_a_mismatch_and_not_uppercase")
    @Test
    fun `a reference differing by a unicode homoglyph is a mismatch, and not uppercase`() {
        // The diagnosis above asks whether the reference carries an ASCII uppercase character,
        // which is what Appendix C and §4.3 forbid — not whether the two strings are equal
        // ignoring case. `String.equals(ignoreCase = true)` folds Unicode: U+017F LATIN SMALL
        // LETTER LONG S folds onto `s`, which is in the bech32 alphabet, so the looser form would
        // tell a caller its encoder emitted uppercase when it emitted a homoglyph. The verdict
        // would be right and the reason would send a reader to the wrong place.
        val fixture = SettlementFixtures.pairs(HOMOGLYPH_SEARCH).firstOrNull { LONG_S_FOLD in it.invoice }
            ?: fail("no generated invoice in the first $HOMOGLYPH_SEARCH carries a `$LONG_S_FOLD`")
        val store = PaymentRequestStore.inMemory()
        AcceptedPaymentRequest.accept(
            SettlementFixtures.request(fixture.requestTags),
            store,
            FakeClock(SettlementFixtures.ACCEPTED_AT),
        )
        val homoglyph = fixture.invoice.replaceFirst(LONG_S_FOLD, LONG_S)
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                fixture.receiptTags,
                SettlementFixtures.paymentTag(homoglyph, fixture.preimageHex),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, fixture.paymentHash, store, SPLIT)
        }

        assertTrue(
            homoglyph.equals(fixture.invoice, ignoreCase = true),
            "the fixture must be one a case-insensitive comparison would call equal, or this test " +
                "is about nothing",
        )
        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, refused.reason)
    }

    @JsName("the_paired_control_an_uppercase_payment_hash_is_accepted_and_normalised")
    @Test
    fun `the paired control — an uppercase payment hash is accepted and normalised`() {
        // §4.3's exception list is exhaustive and the payment hash is not on it, so the rule one
        // line away from the control above points the other way. Without this pair, "uppercase is
        // rejected" reads as a rule about hex rather than about these two values.
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)
        val uppercase = PaymentHash.ofHex(settled.fixture.paymentHash.toHex().uppercase())

        assertEquals(settled.fixture.paymentHash, uppercase)
        assertIs<Settlement.Evidenced>(Settlement.verify(receipt, uppercase, settled.store, SPLIT))
    }

    @JsName("a_receipt_with_no_stored_request_is_refused_as_no_stored_request")
    @Test
    fun `a receipt with no stored request is refused as no stored request`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val receipt = SettlementFixtures.receipt(fixture.receiptTags)

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, fixture.paymentHash, PaymentRequestStore.inMemory(), SPLIT)
        }

        assertEquals(
            SettlementRejection.NO_STORED_REQUEST,
            refused.reason,
            "§9.2 check 1: \"If no such payment request was received and stored, the receipt MUST " +
                "be rejected.\" Answering `matched` here is the other mutation this file pins",
        )
    }

    @JsName("a_receipt_matched_against_the_other_payees_request_is_refused")
    @Test
    fun `a receipt matched against the other payee's request is refused`() {
        // §8.6's two separate invoices, each for the amount its own payee is owed — so what refuses
        // the crossed receipt below is check 1's byte comparison and not check 4's arithmetic,
        // which would be the right verdict reported for the wrong reason.
        val preimages = PaymentFixtures.preimageHex(2)
        val invoices = listOf(
            SettlementFixtures.invoice(preimages[0], SettlementFixtures.amountFor(Payee.PROVIDER, SPLIT)),
            SettlementFixtures.invoice(preimages[1], SettlementFixtures.amountFor(Payee.FEE, SPLIT)),
        )
        val order = SettlementFixtures.orderHex(0)
        val store = PaymentRequestStore.inMemory()
        val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
        // Only the fee payee's request is stored, so a provider receipt finds nothing at all.
        AcceptedPaymentRequest.accept(
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    order = order,
                    payment = SettlementFixtures.requestPaymentTag(invoices[1]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
                ),
            ),
            store,
            clock,
        )
        val preimageHex = preimages[0]
        val providerReceipt = SettlementFixtures.receipt(
            SettlementFixtures.receiptTags(
                order = order,
                payment = SettlementFixtures.paymentTag(invoices[0], preimageHex),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
            ),
        )
        val hash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimageHex))

        val missing = assertFailsWith<SettlementException> {
            Settlement.verify(providerReceipt, hash, store, SPLIT)
        }
        assertEquals(SettlementRejection.NO_STORED_REQUEST, missing.reason)

        // Now store the provider's own request too, and present a provider receipt carrying the
        // **fee** invoice: the key finds a record and the bytes disagree.
        AcceptedPaymentRequest.accept(
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    order = order,
                    payment = SettlementFixtures.requestPaymentTag(invoices[0]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
                ),
            ),
            store,
            clock,
        )
        val crossed = SettlementFixtures.receipt(
            SettlementFixtures.receiptTags(
                order = order,
                payment = SettlementFixtures.paymentTag(invoices[1], preimageHex),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
            ),
        )
        val mismatch =
            assertFailsWith<SettlementException> { Settlement.verify(crossed, hash, store, SPLIT) }
        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, mismatch.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §9.2 checks 2 and 3, composed through T3.
    // ---------------------------------------------------------------------------------------

    @JsName("a_preimage_that_does_not_hash_to_the_payment_hash_yields_t3s_own_refusal")
    @Test
    fun `a preimage that does not hash to the payment hash yields T3's own refusal`() {
        val settled = settled()
        val other = SettlementFixtures.pairs(2)[1]
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)

        val refused = assertFailsWith<PaymentException> {
            Settlement.verify(receipt, other.paymentHash, settled.store, SPLIT)
        }

        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "§9.2 check 3 is T3's rule and its refusal reaches the caller as T3 threw it; re-badging " +
                "it here would put two names on the failure §9 calls the load-bearing rule",
        )
    }

    @JsName("an_uppercase_proof_is_refused_as_check_2s_case_rule")
    @Test
    fun `an uppercase proof is refused as check 2's case rule`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.replacing(
            fixture.receiptTags,
            SettlementFixtures.paymentTag(fixture.invoice, fixture.preimageHex.uppercase()),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.PREIMAGE_MALFORMED, refused.reason)
        val cause = assertIs<PaymentException>(refused.cause, "T3's reason must survive on the cause")
        assertEquals(PaymentRejection.UPPERCASE_NOT_PERMITTED, cause.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §9.4's other rails: parsed, and evidencing nothing.
    // ---------------------------------------------------------------------------------------

    @JsName("a_bitcoin_or_ecash_receipt_is_parsed_and_yields_no_evidence")
    @Test
    fun `a bitcoin or ecash receipt is parsed and yields no evidence`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val store = PaymentRequestStore.inMemory()

        for (medium in listOf(PaymentMedium.BITCOIN, PaymentMedium.ECASH, PaymentMedium.UNKNOWN)) {
            val token = medium.token ?: "some-rail-nenya-does-not-implement"
            val receipt = SettlementFixtures.receipt(
                SettlementFixtures.replacing(
                    fixture.receiptTags,
                    SettlementFixtures.paymentTag("whatever-that-rail-uses", "its-own-proof", token),
                ),
            )
            assertEquals(medium, receipt.medium)
            assertNull(receipt.preimage, "§9.4 defines no rule, so the proof is not read as a preimage")

            val settlement: Settlement =
                Settlement.verify(receipt, fixture.paymentHash, store, SPLIT)

            val unverified = assertIs<Settlement.Unverified>(
                settlement,
                "§9.4: an implementation encountering one MUST treat the payment as unverified",
            )
            assertEquals(
                emptySet<PaymentCheck>(),
                unverified.checksPerformed,
                "nothing was checked, and the empty store was never even consulted",
            )
            assertFalse(
                unverified is Settlement.Evidenced,
                "there is nothing here to advance an order",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The receipt envelope.
    // ---------------------------------------------------------------------------------------

    @JsName("a_rumor_of_another_kind_is_refused_as_not_a_receipt")
    @Test
    fun `a rumor of another kind is refused as not a receipt`() {
        val tags = SettlementFixtures.requestTags()

        val refused = assertFailsWith<SettlementException> {
            PaymentReceipt.decode(SettlementFixtures.bound(tags, NenyaKind.ORDER_MESSAGE))
        }

        assertEquals(SettlementRejection.NOT_A_RECEIPT, refused.reason)
    }

    @JsName("a_receipt_carrying_a_requests_three_element_payment_tag_is_refused")
    @Test
    fun `a receipt carrying a request's three-element payment tag is refused`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.replacing(
            fixture.receiptTags,
            SettlementFixtures.requestPaymentTag(fixture.invoice),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.WRONG_ARITY, refused.reason)
        assertEquals(SettlementVocabulary.PAYMENT, refused.tag)
    }

    @JsName("a_receipt_with_no_payee_tag_is_refused_because_check_1s_key_is_a_pair")
    @Test
    fun `a receipt with no payee tag is refused, because check 1's key is a pair`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.without(fixture.receiptTags, SettlementVocabulary.PAYEE)

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals(SettlementVocabulary.PAYEE, refused.tag)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    /** A bech32 character that is not [character], so the altered invoice is still well-shaped. */
    private fun otherBech32(character: Char): Char =
        Bolt11Reference.BECH32_ALPHABET.first { it != character }
}
