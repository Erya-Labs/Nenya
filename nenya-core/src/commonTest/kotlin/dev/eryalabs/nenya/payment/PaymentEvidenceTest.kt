package dev.eryalabs.nenya.payment

import dev.eryalabs.nenya.SpecAnchor
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §9.2 check 2 and check 3's comparison, and §9.2's "which payees are required" clause.
 *
 * The positive path is anchored outside this repository. §18 publishes the SHA-256 of the
 * vendored `nip44.vectors.json`; that digest is 32 bytes of externally-authored, externally
 * re-derivable hex, so it doubles as a preimage nobody here typed. Every other fixture comes
 * from [PaymentFixtures], the seeded generator committed beside this file.
 *
 * Each negative control asserts the *reason* rather than merely that something failed. That
 * is the whole point of §9.2's checks being separately named: "uppercase" and "malformed" are
 * different bugs on the caller's side, and a caller that cannot tell them apart reports the
 * wrong one to its user.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as
 * `PaymentEvidenceTest` on each target, so every test keeps its `PaymentEvidenceTest` name. The two
 * tests about the JVM-public synthetic constructor reach it through `java.lang.reflect`, and that
 * constructor exists only on the JVM; they are in the JVM `PaymentEvidenceTest` (`src/jvmTest`).
 */
abstract class PortablePaymentEvidenceTest {

    protected companion object {

        /**
         * The externally-authored anchor, in its second role. The digest §18 publishes is
         * exactly 32 bytes of lowercase hex, which is exactly the shape §9.2 check 2 requires
         * of a preimage — so the positive path below is driven by a value published in an RFC
         * rather than by one this repository invented.
         */
        fun anchorPreimageHex(): String = SpecAnchor.publishedNip44Digest()

        fun anchorPreimage(): Preimage = Preimage.ofHex(anchorPreimageHex())

        /** `SHA-256(preimage)` — what a real invoice's `p` field would carry. */
        fun anchorPaymentHash(): PaymentHash = PaymentFixtures.paymentHashOf(anchorPreimage())
    }

    // ---------------------------------------------------------------- the external anchor

    @JsName("the_vendored_nip_44_vectors_hash_to_the_digest_the_specification_publishes")
    @Test
    fun `the vendored NIP-44 vectors hash to the digest the specification publishes`() {
        val vectors = SpecAnchor.nip44VectorsFile()
        val computed = PaymentFixtures.lowerHex(PaymentFixtures.sha256(vectors.readBytes()))

        assertEquals(
            SpecAnchor.publishedNip44Digest(),
            computed,
            "the vendored ${vectors.location} does not hash to the digest §18 publishes; either " +
                "the file was swapped or SHA-256 on this JVM is not SHA-256",
        )
        assertTrue(vectors.length() > 0L, "the vendored vector file must not be empty")
    }

    @JsName("the_published_digest_verifies_as_a_preimage_against_its_own_payment_hash")
    @Test
    fun `the published digest verifies as a preimage against its own payment hash`() {
        val verified = VerifiedPayment.verify(Payee.PROVIDER, anchorPaymentHash(), anchorPreimage())

        assertEquals(Payee.PROVIDER, verified.payee)
        assertEquals(anchorPreimage(), verified.preimage)
        assertEquals(anchorPaymentHash(), verified.paymentHash)
        assertEquals(
            setOf(PaymentCheck.PREIMAGE_SHAPE, PaymentCheck.PREIMAGE_HASH_COMPARISON),
            verified.checksPerformed,
            "§9.2 check 2 and check 3's comparison are what this library performs itself",
        )
    }

    // ------------------------------------------------------- §18's five preimage controls

    @JsName("a_preimage_that_is_thirty_two_bytes_but_wrong_is_rejected_as_a_mismatch")
    @Test
    fun `a preimage that is thirty-two bytes but wrong is rejected as a mismatch`() {
        val (right, wrong) = PaymentFixtures.preimages(2)
        assertNotEquals(right, wrong, "the generator must produce distinct preimages")

        val failure = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(Payee.PROVIDER, PaymentFixtures.paymentHashOf(right), wrong)
        }
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH, failure.reason)
    }

    @JsName("a_thirty_one_byte_preimage_is_rejected_as_the_wrong_length_never_padded")
    @Test
    fun `a thirty-one byte preimage is rejected as the wrong length, never padded`() {
        val short = anchorPreimageHex().substring(0, 62)
        assertEquals(31, short.length / 2)

        val failure = assertFailsWith<PaymentException> { Preimage.ofHex(short) }
        assertEquals(PaymentRejection.WRONG_LENGTH, failure.reason)
    }

    @JsName("a_thirty_three_byte_preimage_is_rejected_as_the_wrong_length_never_truncated")
    @Test
    fun `a thirty-three byte preimage is rejected as the wrong length, never truncated`() {
        val long = anchorPreimageHex() + "ab"
        assertEquals(33, long.length / 2)

        val failure = assertFailsWith<PaymentException> { Preimage.ofHex(long) }
        assertEquals(PaymentRejection.WRONG_LENGTH, failure.reason)
    }

    @JsName("the_correct_preimage_in_uppercase_hex_is_rejected_as_uppercase_not_as_malformed")
    @Test
    fun `the correct preimage in uppercase hex is rejected as uppercase, not as malformed`() {
        val uppercase = anchorPreimageHex().uppercase()
        assertNotEquals(anchorPreimageHex(), uppercase, "the anchor must contain at least one hex letter")

        val failure = assertFailsWith<PaymentException> { Preimage.ofHex(uppercase) }
        assertEquals(
            PaymentRejection.UPPERCASE_NOT_PERMITTED,
            failure.reason,
            "§9.2 check 2 and §4.3's exhaustive two-item exception list make this its own refusal; " +
                "reporting it as malformed sends the caller after the wrong bug",
        )
    }

    @JsName("a_preimage_verified_against_a_different_fixture_s_payment_hash_is_rejected")
    @Test
    fun `a preimage verified against a different fixture's payment hash is rejected`() {
        val (first, second) = PaymentFixtures.preimages(2)

        val failure = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(Payee.PROVIDER, PaymentFixtures.paymentHashOf(second), first)
        }
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH, failure.reason)
    }

    @JsName("mixed_case_hex_is_rejected_for_a_preimage_and_normalised_for_a_payment_hash")
    @Test
    fun `mixed-case hex is rejected for a preimage and normalised for a payment hash`() {
        // §4.3 says "an uppercase **or mixed-case** value MUST be rejected" for the preimage
        // and normalised for everything else. A control that only ever flips the whole string
        // leaves the "or mixed-case" half of both rules untested.
        val hex = anchorPreimageHex()
        val letter = hex.indexOfFirst { it in 'a'..'f' }
        assertTrue(letter >= 0, "the anchor must contain a hex letter to mis-case")
        val mixed = hex.replaceRange(letter, letter + 1, hex[letter].uppercase())
        assertNotEquals(hex, mixed)
        assertNotEquals(hex.uppercase(), mixed, "this must be mixed case, not uppercase")

        assertEquals(
            PaymentRejection.UPPERCASE_NOT_PERMITTED,
            assertFailsWith<PaymentException> { Preimage.ofHex(mixed) }.reason,
        )
        assertEquals(hex, PaymentHash.ofHex(mixed).toHex())
    }

    @JsName("the_capability_record_cannot_be_mutated_by_a_caller")
    @Test
    fun `the capability record cannot be mutated by a caller`() {
        // The three check sets are shared statics. If the unmodifiable wrapper were dropped,
        // one caller removing INVOICE_AMOUNT would make every VerifiedPayment in the JVM
        // claim the amount had been checked — a §17 over-claim with process-wide reach, from
        // one deleted line.
        val verified = VerifiedPayment.verify(Payee.FEE, anchorPaymentHash(), anchorPreimage())

        for (record in listOf(verified.checksPerformed, verified.checksNotPerformedHere)) {
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (record as MutableSet<PaymentCheck>).remove(PaymentCheck.INVOICE_AMOUNT)
            }
        }
        assertEquals(
            setOf(PaymentCheck.INVOICE_AMOUNT),
            verified.checksNotPerformedHere.intersect(setOf(PaymentCheck.INVOICE_AMOUNT)),
            "and it is still there afterwards",
        )
    }

    // ------------------------------------- the paired positive control §4.3 makes necessary

    @JsName("an_uppercase_payment_hash_is_accepted_and_normalised")
    @Test
    fun `an uppercase payment hash is accepted and normalised`() {
        val lowercase = PaymentFixtures.lowerHex(PaymentFixtures.sha256(anchorPreimage().bytes()))

        val fromUppercase = PaymentHash.ofHex(lowercase.uppercase())

        assertEquals(lowercase, fromUppercase.toHex(), "§4.3's general rule is accept-and-normalise")
        assertEquals(PaymentHash.ofHex(lowercase), fromUppercase)
        assertEquals(
            Payee.PROVIDER,
            VerifiedPayment.verify(Payee.PROVIDER, fromUppercase, anchorPreimage()).payee,
            "a normalised payment hash must still verify — §4.3 puts the payment hash under the " +
                "general rule precisely because it is not on the two-item exception list",
        )
    }

    // ---------------------------------------------------------------- the shape controls

    @JsName("odd_length_input_is_rejected_as_the_wrong_length")
    @Test
    fun `odd-length input is rejected as the wrong length`() {
        for (odd in listOf(anchorPreimageHex().substring(0, 63), anchorPreimageHex() + "a")) {
            assertEquals(1, odd.length % 2)
            assertEquals(PaymentRejection.WRONG_LENGTH, assertFailsWith<PaymentException> { Preimage.ofHex(odd) }.reason)
            assertEquals(PaymentRejection.WRONG_LENGTH, assertFailsWith<PaymentException> { PaymentHash.ofHex(odd) }.reason)
        }
    }

    @JsName("an_empty_string_is_rejected_as_the_wrong_length")
    @Test
    fun `an empty string is rejected as the wrong length`() {
        assertEquals(PaymentRejection.WRONG_LENGTH, assertFailsWith<PaymentException> { Preimage.ofHex("") }.reason)
        assertEquals(PaymentRejection.WRONG_LENGTH, assertFailsWith<PaymentException> { PaymentHash.ofHex("") }.reason)
    }

    @JsName("non_hex_input_of_the_right_length_is_rejected_as_not_hex")
    @Test
    fun `non-hex input of the right length is rejected as not hex`() {
        // 'z' is not hex in either case, and 'G' is not hex despite being an uppercase letter:
        // the uppercase rule covers A-F only, and anything else is malformed, not mis-cased.
        for (bad in listOf("z".repeat(64), "G".repeat(64), anchorPreimageHex().replaceRange(0, 1, " "))) {
            assertEquals(64, bad.length)
            assertEquals(PaymentRejection.NOT_HEX, assertFailsWith<PaymentException> { Preimage.ofHex(bad) }.reason)
            assertEquals(PaymentRejection.NOT_HEX, assertFailsWith<PaymentException> { PaymentHash.ofHex(bad) }.reason)
        }
    }

    // ------------------------------------------------------------ §17: what was not checked

    @JsName("a_provider_receipt_names_the_four_invoice_checks_this_library_did_not_perform")
    @Test
    fun `a provider receipt names the four invoice checks this library did not perform`() {
        val verified = VerifiedPayment.verify(Payee.PROVIDER, anchorPaymentHash(), anchorPreimage())

        assertEquals(
            setOf(
                PaymentCheck.INVOICE_IDENTITY,
                PaymentCheck.PAYMENT_HASH_PROVENANCE,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
            ),
            verified.checksNotPerformedHere,
            "§17 forbids reporting unverified things as verified; a provider receipt verified here " +
                "has had neither the invoice identity, nor the provenance of the payment hash that " +
                "was compared, nor the amount, nor the expiry checked. The provenance is a constant " +
                "of its own and this expectation is stricter for it, not looser: the payment hash " +
                "is a parameter this function is handed, so a caller passing the SHA-256 of a " +
                "preimage it chose gets a true comparison about an invoice nobody issued",
        )
        assertTrue(
            PaymentCheck.INVOICE_AMOUNT in verified.checksNotPerformedHere,
            "check 4 is the one that catches a provider claiming ten times price_msat",
        )
    }

    @JsName("a_fee_receipt_also_names_check_six_s_three_obligations")
    @Test
    fun `a fee receipt also names check six's three obligations`() {
        val verified = VerifiedPayment.verify(Payee.FEE, anchorPaymentHash(), anchorPreimage())

        assertEquals(
            setOf(
                PaymentCheck.INVOICE_IDENTITY,
                PaymentCheck.PAYMENT_HASH_PROVENANCE,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
                PaymentCheck.FEE_TERM_MATCH,
                PaymentCheck.FEE_SEALING_KEY,
                PaymentCheck.FEE_STATE_PRECONDITION,
            ),
            verified.checksNotPerformedHere,
            "a fee receipt recording only the first four would silently imply §9.2 check 6 — the " +
                "fee-term match, the sealing key and the awaiting_payment precondition — was performed",
        )
    }

    @JsName("every_check_the_specification_lists_is_either_performed_or_named_as_not_performed")
    @Test
    fun `every check the specification lists is either performed or named as not performed`() {
        for (payee in Payee.entries) {
            val verified = VerifiedPayment.verify(payee, anchorPaymentHash(), anchorPreimage())
            val accounted = verified.checksPerformed + verified.checksNotPerformedHere

            assertTrue(
                verified.checksPerformed.none { it in verified.checksNotPerformedHere },
                "$payee: a check cannot be both performed and not performed",
            )
            val unaccounted = PaymentCheck.entries.filter { it !in accounted }
            assertEquals(
                if (payee == Payee.FEE) emptyList() else listOf(
                    PaymentCheck.FEE_TERM_MATCH,
                    PaymentCheck.FEE_SEALING_KEY,
                    PaymentCheck.FEE_STATE_PRECONDITION,
                ),
                unaccounted,
                "the only checks a receipt may leave unaccounted for are §9.2 check 6's, and only " +
                    "for a provider receipt, where they do not apply at all",
            )
        }
    }

    // ------------------------------------------------- §9.2: which payees are required

    @JsName("an_ordinary_fee_bearing_order_requires_both_payees")
    @Test
    fun `an ordinary fee-bearing order requires both payees`() {
        val split = FeeTerm.of(250).splitOn(Msat.ofMsat(50_000_000L))

        assertEquals(Msat.ofMsat(1_250_000L), split.fee, "§8.3's first worked example")
        assertEquals(setOf(Payee.PROVIDER, Payee.FEE), Payee.requiredPayees(split))
    }

    @JsName("an_order_whose_fee_computes_to_zero_requires_no_fee_receipt")
    @Test
    fun `an order whose fee computes to zero requires no fee receipt`() {
        // §8.3's own case: a non-zero bps that still computes to a zero fee.
        val split = FeeTerm.of(1).splitOn(Msat.ofMsat(3000L))

        assertEquals(Msat.ZERO, split.fee)
        assertTrue(split.term.namesRecipient, "the terms do name a fee payee — that is what makes this the trap")
        assertEquals(
            setOf(Payee.PROVIDER),
            Payee.requiredPayees(split),
            "§8.3: no fee invoice may exist for a zero expected amount, so none may be awaited; " +
                "an implementation that waits for one deadlocks the order into expired",
        )
    }

    @JsName("an_order_with_no_fee_term_at_all_requires_no_fee_receipt")
    @Test
    fun `an order with no fee term at all requires no fee receipt`() {
        val split = FeeTerm.Absent.splitOn(Msat.ofMsat(90_000_000L))

        assertEquals(setOf(Payee.PROVIDER), Payee.requiredPayees(split))
    }

    @JsName("an_order_whose_price_is_zero_requires_no_provider_receipt")
    @Test
    fun `an order whose price is zero requires no provider receipt`() {
        // Not stated as its own case anywhere: it falls out of §9.2's generic "expected amount
        // is non-zero" clause applied to price_msat, exactly as the fee side falls out of it.
        val split = FeeTerm.Absent.splitOn(Msat.ZERO)

        assertEquals(emptySet(), Payee.requiredPayees(split))
    }

    @JsName("a_zero_price_order_at_the_maximum_fee_still_requires_nobody")
    @Test
    fun `a zero-price order at the maximum fee still requires nobody`() {
        val split = FeeTerm.of(FeeTerm.MAX_BASIS_POINTS).splitOn(Msat.ZERO)

        assertEquals(Msat.ZERO, split.fee)
        assertTrue(split.term.namesRecipient)
        assertEquals(emptySet(), Payee.requiredPayees(split))
    }

    @JsName("required_payees_agrees_with_the_split_s_own_fee_flag")
    @Test
    // Deliberately not named "rather than re-deriving it": no behavioural test can separate
    // `split.feePayeeRequired` from an expression that happens to agree with it everywhere.
    // That rule is kept at the call site, by a comment, not pretended to be tested here.
    fun `required payees agrees with the split's own fee flag`() {
        for (bps in listOf(0, 1, 137, 250, 9_999, 10_000)) {
            for (priceMsat in listOf(0L, 1L, 3_000L, 10_000L, 1_001_000L, 50_000_000L)) {
                val split = FeeTerm.of(bps).splitOn(Msat.ofMsat(priceMsat))
                val required = Payee.requiredPayees(split)

                assertEquals(
                    split.feePayeeRequired,
                    Payee.FEE in required,
                    "the fee side of §9.2's rule is T2's feePayeeRequired, consumed and not recomputed",
                )
                assertEquals(split.price > Msat.ZERO, Payee.PROVIDER in required)
            }
        }
    }

    @JsName("the_payee_tokens_are_the_ones_section_8_point_6_writes")
    @Test
    fun `the payee tokens are the ones section 8 point 6 writes`() {
        assertEquals("provider", Payee.PROVIDER.token)
        assertEquals("fee", Payee.FEE.token)
        assertEquals(2, Payee.entries.size, "§8.6 defines two roles and there is no third")
    }

    // ----------------------------------------- §12 item 11 / STOP RULE 14: no leaked secrets

    @JsName("no_string_representation_leaks_the_preimage")
    @Test
    fun `no string representation leaks the preimage`() {
        val hex = anchorPreimageHex()
        val preimage = anchorPreimage()
        val hash = anchorPaymentHash()
        val verified = VerifiedPayment.verify(Payee.FEE, hash, preimage)

        // Pinned exactly, not merely searched for the hex. A future toString returning
        // `value.contentToString()` or Base64 leaks the whole preimage in a form no substring
        // check would notice — and "the debugging toString somebody adds later" is precisely
        // what this control is for.
        assertEquals("Preimage(redacted)", preimage.toString())
        assertEquals("PaymentHash(redacted)", hash.toString())

        for (rendered in listOf(preimage.toString(), hash.toString(), verified.toString())) {
            assertFalse(
                rendered.contains(hex, ignoreCase = true),
                "§12 item 11: a preimage MUST NOT appear in the string representation of anything " +
                    "this library exposes, and this one does: $rendered",
            )
            assertFalse(
                rendered.contains(hash.toHex(), ignoreCase = true),
                "a payment hash is a per-order correlator; it is redacted here too: $rendered",
            )
        }
        assertTrue(verified.toString().contains("fee"), "the rendering must still say something useful")
    }

    @JsName("no_rejection_message_echoes_the_value_that_was_rejected")
    @Test
    fun `no rejection message echoes the value that was rejected`() {
        val hex = anchorPreimageHex()

        val fromUppercase = assertFailsWith<PaymentException> { Preimage.ofHex(hex.uppercase()) }
        val fromShort = assertFailsWith<PaymentException> { Preimage.ofHex(hex.substring(0, 62)) }
        val fromMismatch = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(Payee.PROVIDER, PaymentFixtures.paymentHashOf(PaymentFixtures.preimages(1).single()), anchorPreimage())
        }

        for (failure in listOf(fromUppercase, fromShort, fromMismatch)) {
            val message = failure.message.orEmpty()
            assertFalse(
                message.contains(hex, ignoreCase = true) || message.contains(hex.substring(0, 32), ignoreCase = true),
                "a rejection message must never echo the preimage back into a log: $message",
            )
            assertTrue(message.isNotEmpty(), "every rejection must say why")
        }
    }

    // ----------------------------------------------------------------- value semantics

    @JsName("preimages_and_payment_hashes_compare_by_value")
    @Test
    fun `preimages and payment hashes compare by value`() {
        val (first, second) = PaymentFixtures.preimages(2)

        assertEquals(first, Preimage.ofHex(PaymentFixtures.preimageHex(2).first()))
        assertEquals(first.hashCode(), Preimage.ofHex(PaymentFixtures.preimageHex(2).first()).hashCode())
        assertNotEquals(first, second)
        assertEquals(PaymentFixtures.paymentHashOf(first), PaymentFixtures.paymentHashOf(first))
        assertNotEquals(PaymentFixtures.paymentHashOf(first), PaymentFixtures.paymentHashOf(second))
    }

    @JsName("the_bytes_handed_out_are_a_copy_so_a_caller_cannot_mutate_the_evidence")
    @Test
    fun `the bytes handed out are a copy, so a caller cannot mutate the evidence`() {
        val preimage = anchorPreimage()
        val hash = anchorPaymentHash()

        preimage.bytes()[0] = 0
        hash.bytes()[0] = 0

        assertEquals(anchorPreimageHex(), PaymentFixtures.lowerHex(preimage.bytes()))
        assertEquals(anchorPaymentHash().toHex(), hash.toHex())
        assertEquals(Payee.FEE, VerifiedPayment.verify(Payee.FEE, hash, preimage).payee)
    }
}
