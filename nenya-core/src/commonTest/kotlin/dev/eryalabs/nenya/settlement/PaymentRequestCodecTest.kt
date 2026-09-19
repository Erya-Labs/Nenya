package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §8.6's payment request, §9.3's static-address rule, and §17 item 6's persistence obligation —
 * each control asserting the **reason** rather than merely that something was refused.
 *
 * The vocabulary is not transcribed: [Section86], [Section92] and [Section94] parse the tag shapes
 * out of `spec/NENYA-1.md` at test time, and the first four tests hold this package's tokens and
 * arities equal to them. Everything below that runs on the generator described in
 * [SettlementFixtures] — whose header says, at length, that its strings are invoice-**shaped** and
 * are not invoices.
 */
class PaymentRequestCodecTest {

    private companion object {

        /** §8.6's two roles, as this library declares them, for the spec anchor to be held against. */
        val ROLE_TOKENS: List<String> = listOf(Payee.PROVIDER.token, Payee.FEE.token)

        /** §8.3's own worked zero-fee case: `floor(3000 × 1 / 10000) = 0`. */
        const val ZERO_FEE_PRICE_MSAT: Long = 3000L

        /** One basis point — non-zero, so the *term* names a recipient while the *amount* is zero. */
        const val ZERO_FEE_BASIS_POINTS: Int = 1

        /**
         * §7.6's checked acceptance for the order at index 0, which is the one
         * `SettlementFixtures.requestTags()` names by default.
         *
         * Built once: `AcceptedPaymentRequest.accept` takes one, and every clock control below
         * needs an acceptance that is beside the point of what it is testing.
         */
        val ORDER_ZERO: SettlementFixtures.Accepted by lazy { SettlementFixtures.accepted(0) }

        /** The same for the second order the store-keying control uses. */
        val ORDER_ONE: SettlementFixtures.Accepted by lazy { SettlementFixtures.accepted(1) }

        fun acceptanceFor(index: Int): SettlementFixtures.Accepted =
            if (index == 0) ORDER_ZERO else ORDER_ONE
    }

    // ---------------------------------------------------------------------------------------
    // The specification anchors.
    // ---------------------------------------------------------------------------------------

    @JsName("s86_payee_block_gives_this_librarys_two_roles_and_their_arities")
    @Test
    fun `§8_6's payee block gives this library's two roles and their arities`() {
        val tags = Section86.payeeTags

        assertEquals(
            ROLE_TOKENS,
            tags.map { it[SettlementVocabulary.ROLE_INDEX] },
            "§8.6's fenced block in ${SpecTagShapes.specPath()} names the payee roles, and Payee " +
                "must carry exactly those tokens in that order",
        )
        assertTrue(
            tags.all { it[SettlementVocabulary.NAME_INDEX] == SettlementVocabulary.PAYEE },
            "both rows must be `${SettlementVocabulary.PAYEE}` tags: $tags",
        )
        assertEquals(
            SettlementVocabulary.PAYEE_ELEMENTS_PROVIDER,
            tags[0].size,
            "§8.6 prints the provider payee with no recipient",
        )
        assertEquals(
            SettlementVocabulary.PAYEE_ELEMENTS_FEE,
            tags[1].size,
            "§8.6 prints the fee payee with the recipient's pubkey",
        )
    }

    @JsName("s86_payment_tag_is_the_three_element_lightning_form")
    @Test
    fun `§8_6's payment tag is the three-element lightning form`() {
        val tag = Section86.paymentRequestTag

        assertEquals(SettlementVocabulary.PAYMENT_ELEMENTS_REQUEST, tag.size, "printed as $tag")
        assertEquals(SettlementVocabulary.PAYMENT, tag[SettlementVocabulary.NAME_INDEX])
        assertEquals(
            PaymentMedium.LIGHTNING.token,
            tag[SettlementVocabulary.MEDIUM_INDEX],
            "§8.6 fixes the medium of a payment request, and PaymentMedium must spell it the same way",
        )
    }

    @JsName("s92_receipt_payment_tag_carries_a_fourth_proof_element")
    @Test
    fun `§9_2's receipt payment tag carries a fourth proof element`() {
        val shape = Section92.paymentShape

        assertEquals(
            SettlementVocabulary.PAYMENT_ELEMENTS_RECEIPT,
            shape.size,
            "§9.2's GammaMarkets shape in ${SpecTagShapes.specPath()} is name, medium, reference " +
                "and proof; a request carries the first three and a receipt all four: $shape",
        )
        assertEquals(SettlementVocabulary.PAYMENT, shape[SettlementVocabulary.NAME_INDEX])
    }

    @JsName("s94_names_the_two_rails_this_library_parses_and_evidences_nothing_from")
    @Test
    fun `§9_4 names the two rails this library parses and evidences nothing from`() {
        val tokens = Section94.railTags.map { it[SettlementVocabulary.MEDIUM_INDEX] }

        assertEquals(
            listOf(PaymentMedium.BITCOIN.token, PaymentMedium.ECASH.token),
            tokens,
            "§9.4 in ${SpecTagShapes.specPath()} recognises exactly these rails as GammaMarkets " +
                "vocabulary, and PaymentMedium must spell them the same way",
        )
        for (token in tokens) {
            assertFalse(
                PaymentMedium.of(token!!).hasVerificationRule,
                "§9.4: NENYA-1 v1 defines no verification rule for `$token`",
            )
        }
        assertEquals(
            PaymentMedium.UNKNOWN,
            PaymentMedium.of("nenya-does-not-implement-this-rail"),
            "§9.4's treatment reaches a medium this library has never heard of too, and an " +
                "unrecognised token MUST NOT be mapped onto the nearest known one",
        )
        assertNull(PaymentMedium.UNKNOWN.token, "the sink has no wire form, so nothing can emit it")
    }

    // ---------------------------------------------------------------------------------------
    // The accepting direction.
    // ---------------------------------------------------------------------------------------

    @JsName("a_well_formed_type_2_decodes_to_its_order_payee_and_verbatim_invoice")
    @Test
    fun `a well-formed type=2 decodes to its order, payee and verbatim invoice`() {
        val invoice = invoices(1).single()
        val tags = SettlementFixtures.requestTags(
            payment = SettlementFixtures.requestPaymentTag(invoice),
        )

        val request = SettlementFixtures.request(tags)

        assertEquals(SettlementFixtures.orderHex(0), request.order.toHex())
        assertEquals(Payee.PROVIDER, request.payee)
        assertNull(request.feeRecipient, "§8.6's provider payee names no recipient")
        assertEquals(invoice, request.invoice.text, "§9.2 check 1 compares this string byte for byte")
        assertEquals(SettlementFixtures.pubkey(0), request.sender, "§7.2's attributed key")
        assertEquals(SettlementFixtures.CREATED_AT, request.createdAt)
    }

    @JsName("a_fee_payment_request_carries_the_recipient_s86_names")
    @Test
    fun `a fee payment request carries the recipient §8_6 names`() {
        val tags = SettlementFixtures.requestTags(
            payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
        )

        val request = SettlementFixtures.request(tags)

        assertEquals(Payee.FEE, request.payee)
        assertEquals(
            SettlementFixtures.pubkey(2),
            request.feeRecipient,
            "§8.7's binding is a comparison between this and the signed fee term's recipient, so " +
                "both operands must be reachable — and neither comparison is made here",
        )
    }

    @JsName("a_requests_uppercase_fee_recipient_is_accepted_and_normalised")
    @Test
    fun `a request's uppercase fee recipient is accepted and normalised`() {
        // §4.3's exception list has exactly two entries — the BOLT-11 string and the preimage — and
        // a pubkey is on neither, so this one follows the general accept-and-normalise rule. The
        // contrast with the uppercase-invoice control below is the whole point of both.
        val tags = SettlementFixtures.requestTags(
            payeeTag = listOf(
                SettlementVocabulary.PAYEE,
                Payee.FEE.token,
                SettlementFixtures.uppercasePubkey(2),
            ),
        )

        val request = SettlementFixtures.request(tags)

        assertEquals(SettlementFixtures.pubkey(2), request.feeRecipient)
    }

    @JsName("a_decoded_request_re_encodes_byte_for_byte")
    @Test
    fun `a decoded request re-encodes byte for byte`() {
        val tags = SettlementFixtures.with(
            SettlementFixtures.requestTags(),
            listOf("x-nenya-experiment", "kept verbatim"),
        )

        val request = SettlementFixtures.request(tags)

        assertEquals(tags, request.encode().tags, "§4.3: unknown tags survive, and dropping one changes the id")
    }

    // ---------------------------------------------------------------------------------------
    // The envelope and the two tags §5.3 does not carry.
    // ---------------------------------------------------------------------------------------

    @JsName("a_message_of_another_type_is_refused_as_not_a_payment_request")
    @Test
    fun `a message of another type is refused as not a payment request`() {
        val tags = SettlementFixtures.requestTags(type = NenyaKind.OrderMessageType.STATUS.toString())

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(SettlementRejection.NOT_A_PAYMENT_REQUEST, refused.reason)
        assertEquals(ChannelTags.TYPE, refused.tag)
    }

    @JsName("a_second_payment_tag_is_refused_and_a_missing_one_too")
    @Test
    fun `a second payment tag is refused, and a missing one too`() {
        val invoices = invoices(2)
        val base = SettlementFixtures.requestTags(
            payment = SettlementFixtures.requestPaymentTag(invoices[0]),
        )

        val duplicated = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.with(base, SettlementFixtures.requestPaymentTag(invoices[1])),
            )
        }
        assertEquals(SettlementRejection.DUPLICATE_TAG, duplicated.reason)
        assertEquals(SettlementVocabulary.PAYMENT, duplicated.tag)

        val absent = assertFailsWith<SettlementException> {
            SettlementFixtures.request(SettlementFixtures.without(base, SettlementVocabulary.PAYMENT))
        }
        assertEquals(SettlementRejection.MISSING_REQUIRED_TAG, absent.reason)
        assertEquals(SettlementVocabulary.PAYMENT, absent.tag)
    }

    @JsName("a_second_payee_tag_is_refused_and_a_missing_one_too")
    @Test
    fun `a second payee tag is refused, and a missing one too`() {
        val base = SettlementFixtures.requestTags()

        val duplicated = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.with(base, SettlementFixtures.payeeTag(Payee.FEE)),
            )
        }
        assertEquals(SettlementRejection.DUPLICATE_TAG, duplicated.reason)
        assertEquals(SettlementVocabulary.PAYEE, duplicated.tag)

        val absent = assertFailsWith<SettlementException> {
            SettlementFixtures.request(SettlementFixtures.without(base, SettlementVocabulary.PAYEE))
        }
        assertEquals(SettlementRejection.MISSING_REQUIRED_TAG, absent.reason)
        assertEquals(SettlementVocabulary.PAYEE, absent.tag)
    }

    @JsName("a_request_whose_payment_tag_is_not_s86s_three_elements_is_refused")
    @Test
    fun `a request whose payment tag is not §8_6's three elements is refused`() {
        val invoice = invoices(1).single()
        // Both directions: §9.2's four-element receipt form, which carries a proof no request can
        // have, and the two truncations below it. One branch, so all three are the same rule.
        val wrong = listOf(
            SettlementFixtures.paymentTag(invoice, "no proof exists before payment"),
            listOf(SettlementVocabulary.PAYMENT, PaymentMedium.LIGHTNING.token!!),
            listOf(SettlementVocabulary.PAYMENT),
        )

        for (payment in wrong) {
            val refused = assertFailsWith<SettlementException>("arity ${payment.size}") {
                SettlementFixtures.request(SettlementFixtures.requestTags(payment = payment))
            }

            assertEquals(SettlementRejection.WRONG_ARITY, refused.reason, "arity ${payment.size}")
            assertEquals(SettlementVocabulary.PAYMENT, refused.tag)
        }
    }

    /**
     * §8.6 (revision `1.5`): a second `type=2` under one `(order, payee)` key is **refused**, and
     * the first record survives byte for byte.
     *
     * This file used to pin the opposite, and the inversion is gap G1d: `AcceptedPaymentRequest`
     * documented that a second request replaced the first, so a stranger — or the provider itself —
     * could re-point §9.2 check 1 at another invoice after the fact, including after the buyer had
     * paid the one it displaced. Check 1 is a comparison against *the* stored string, and there is
     * no version of that sentence that survives the string being replaceable.
     *
     * Both invoices here are for the provider's own expected amount, so what refuses the second is
     * the uniqueness rule and not §9.2 check 4 — the wrong verdict for the right reason is exactly
     * what this file's constants exist to keep apart.
     */
    @JsName("a_second_request_under_one_key_is_refused_and_the_first_record_survives")
    @Test
    fun `a second request under one key is refused, and the first record survives`() {
        val store = PaymentRequestStore.inMemory()
        val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
        val owed = SettlementFixtures.amountFor(Payee.PROVIDER, SettlementFixtures.split())
        val invoices = PaymentFixtures.preimageHex(2).map { SettlementFixtures.invoice(it, owed) }
        assertEquals(
            invoices.size,
            invoices.toSet().size,
            "the two fixtures must be different invoices, or `refused` and `kept` look the same",
        )

        val first = store(store, clock, 0, Payee.PROVIDER, invoices[0])
        assertSame(first, store.find(first.order, Payee.PROVIDER))

        val refused = assertFailsWith<SettlementException> {
            store(store, clock, 0, Payee.PROVIDER, invoices[1])
        }

        assertEquals(SettlementRejection.REQUEST_ALREADY_STORED, refused.reason)
        assertSame(
            first,
            store.find(first.order, Payee.PROVIDER),
            "the record already held is untouched — the whole content of the rule is that the " +
                "**first** one survives",
        )
        assertEquals(invoices[0], store.find(first.order, Payee.PROVIDER)?.invoice?.text)
    }

    @JsName("a_payee_tag_at_the_other_roles_arity_is_refused")
    @Test
    fun `a payee tag at the other role's arity is refused`() {
        val shortFee = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    payeeTag = listOf(SettlementVocabulary.PAYEE, Payee.FEE.token),
                ),
            )
        }
        assertEquals(SettlementRejection.WRONG_ARITY, shortFee.reason)

        val longProvider = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    payeeTag = listOf(
                        SettlementVocabulary.PAYEE,
                        Payee.PROVIDER.token,
                        SettlementFixtures.pubkey(2),
                    ),
                ),
            )
        }
        assertEquals(SettlementRejection.WRONG_ARITY, longProvider.reason)
        assertEquals(SettlementVocabulary.PAYEE, longProvider.tag)
    }

    @JsName("an_unrecognised_payee_role_is_refused_by_name_and_never_mapped")
    @Test
    fun `an unrecognised payee role is refused by name, and never mapped`() {
        val tags = SettlementFixtures.requestTags(
            payeeTag = listOf(SettlementVocabulary.PAYEE, "escrow"),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(SettlementRejection.UNKNOWN_PAYEE_ROLE, refused.reason)
        assertEquals(SettlementVocabulary.PAYEE, refused.tag)
    }

    @JsName("a_fee_recipient_that_is_not_64_hex_characters_is_refused")
    @Test
    fun `a fee recipient that is not 64 hex characters is refused`() {
        val tags = SettlementFixtures.requestTags(
            payeeTag = listOf(SettlementVocabulary.PAYEE, Payee.FEE.token, "not-a-pubkey"),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(SettlementRejection.MALFORMED_PAYEE_RECIPIENT, refused.reason)
        assertNotNull(refused.cause, "the §5.3 reason must survive on the cause")
    }

    // ---------------------------------------------------------------------------------------
    // §8.6's medium, and the opposite answer §9.4 gives a receipt.
    // ---------------------------------------------------------------------------------------

    @JsName("s94s_rails_are_refused_on_a_type_2_request")
    @Test
    fun `§9_4's rails are refused on a type=2 request`() {
        for (medium in listOf(PaymentMedium.BITCOIN, PaymentMedium.ECASH, PaymentMedium.UNKNOWN)) {
            val token = medium.token ?: "some-rail-nenya-does-not-implement"
            val tags = SettlementFixtures.requestTags(
                payment = SettlementFixtures.requestPaymentTag("whatever-that-rail-uses", token),
            )

            val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

            assertEquals(
                SettlementRejection.MEDIUM_NOT_LIGHTNING,
                refused.reason,
                "§8.6 makes the medium of a payment **request** a MUST; §9.4's permission is a " +
                    "receipt rule, and reading it onto a request stores an invoice that can never " +
                    "be verified. Medium under test: $medium",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // §9.3 and the BOLT-11 recogniser.
    // ---------------------------------------------------------------------------------------

    @JsName("a_lud16_address_is_refused_as_a_static_address_and_not_as_malformed")
    @Test
    fun `a lud16 address is refused as a static address, and not as malformed`() {
        val tags = SettlementFixtures.requestTags(
            payment = SettlementFixtures.requestPaymentTag("name@example.invalid"),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(
            SettlementRejection.INVOICE_STATIC_ADDRESS,
            refused.reason,
            "§9.3 refuses a static address for a privacy reason and not a syntactic one: a peer " +
                "told its invoice is malformed goes to fix the wrong thing",
        )
    }

    @JsName("an_lnurl_string_is_refused_the_same_way")
    @Test
    fun `an lnurl string is refused the same way`() {
        // Without the explicit `lnurl` rule this would satisfy the human-readable part below —
        // `lnurl` is `ln` followed by letters — and be accepted as an invoice, which is precisely
        // the substitution §9.3 forbids. The data part is deliberately long enough to reach it.
        val data = invoices(1).single().substringAfterLast('1')
        val tags = SettlementFixtures.requestTags(
            payment = SettlementFixtures.requestPaymentTag("lnurl1$data"),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(SettlementRejection.INVOICE_STATIC_ADDRESS, refused.reason)
    }

    @JsName("an_uppercase_invoice_is_refused_as_uppercase_and_never_normalised")
    @Test
    fun `an uppercase invoice is refused as uppercase, and never normalised`() {
        val invoice = invoices(1).single()
        val tags = SettlementFixtures.requestTags(
            payment = SettlementFixtures.requestPaymentTag(invoice.uppercase()),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.request(tags) }

        assertEquals(
            SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED,
            refused.reason,
            "Appendix C: a mixed-case invoice is invalid. §4.3 names the BOLT-11 string as one of " +
                "exactly two values where no normalisation is permitted — the payment hash is on " +
                "neither list and is accepted and normalised, which is the paired control above",
        )
        // And the lowercase original is accepted, so the refusal above is about the case and not
        // about the string.
        assertEquals(
            invoice,
            SettlementFixtures.request(
                SettlementFixtures.requestTags(payment = SettlementFixtures.requestPaymentTag(invoice)),
            ).invoice.text,
        )
    }

    @JsName("the_vendored_uppercase_pubkeys_are_refused_for_four_independent_reasons")
    @Test
    fun `the vendored uppercase pubkeys are refused for four independent reasons`() {
        // The one fixture in this test nobody here authored: the `public key` column of the
        // vendored BIP-340 vectors. Four things are wrong with it as a `payment` reference, and
        // the second call proves they are independent rather than one reason reported four ways.
        val key = SettlementFixtures.uppercasePubkey(0)

        val uppercase = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(payment = SettlementFixtures.requestPaymentTag(key)),
            )
        }
        assertEquals(SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED, uppercase.reason)

        val lowered = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    payment = SettlementFixtures.requestPaymentTag(key.lowercase()),
                ),
            )
        }
        assertEquals(
            SettlementRejection.INVOICE_MALFORMED,
            lowered.reason,
            "lowercased, the key still has no `ln` human-readable part, carries `b`, `i` and `o` " +
                "outside the bech32 alphabet, and is far shorter than Appendix C's timestamp and " +
                "signature together",
        )
    }

    @JsName("the_recogniser_refuses_the_four_shapes_appendix_c_rules_out")
    @Test
    fun `the recogniser refuses the four shapes Appendix C rules out`() {
        val invoice = invoices(1).single()
        val separator = invoice.lastIndexOf('1')
        val data = invoice.substring(separator + 1)

        // No separator at all.
        assertRefusedAsMalformed(invoice.replace("1", "q"))
        // A human-readable part that is not `ln` + a network prefix + an optional amount.
        assertRefusedAsMalformed("xxbc1$data")
        // A data part below Appendix C's 7-character timestamp plus 104-character signature.
        assertRefusedAsMalformed("lnbc1" + data.take(Bolt11Reference.MIN_DATA_CHARACTERS - 1))
        // A character outside the bech32 alphabet, which excludes `1`, `b`, `i` and `o`.
        assertRefusedAsMalformed("lnbc1" + "b" + data.drop(1))
    }

    // ---------------------------------------------------------------------------------------
    // §8.3's and §8.6's zero-amount rule, on the request side.
    // ---------------------------------------------------------------------------------------

    @JsName("a_fee_request_for_an_order_whose_fee_computes_to_zero_is_refused")
    @Test
    fun `a fee request for an order whose fee computes to zero is refused`() {
        val split = SettlementFixtures.split(ZERO_FEE_PRICE_MSAT, ZERO_FEE_BASIS_POINTS)
        assertTrue(split.term.namesRecipient, "the term must name a payee, or this proves nothing")
        assertEquals(0L, split.fee.millisatoshis, "§8.3's worked zero-fee case")

        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(payeeTag = SettlementFixtures.payeeTag(Payee.FEE)),
                split,
            )
        }

        assertEquals(
            SettlementRejection.PAYEE_NOT_REQUIRED,
            refused.reason,
            "§8.6: when the amount is 0, no fee payment request exists at all and any that arrives " +
                "MUST be rejected. An implementation that accepts one strands the order",
        )
    }

    @JsName("a_provider_request_for_a_zero_price_order_is_refused_by_the_same_clause")
    @Test
    fun `a provider request for a zero-price order is refused by the same clause`() {
        // Not stated anywhere as its own case: it falls out of §9.2's generic "the expected amount
        // is non-zero", which `Payee.requiredPayees` owns and this codec consumes.
        val split = SettlementFixtures.split(priceMsat = 0L, basisPoints = null)

        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.request(SettlementFixtures.requestTags(), split)
        }

        assertEquals(SettlementRejection.PAYEE_NOT_REQUIRED, refused.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §17 item 6's second value: the clock reading, and the fail-closed rule.
    // ---------------------------------------------------------------------------------------

    @JsName("an_accepted_request_records_the_clock_reading_and_is_findable_by_order_and_payee")
    @Test
    fun `an accepted request records the clock reading, and is findable by order and payee`() {
        val store = PaymentRequestStore.inMemory()
        val request = SettlementFixtures.requestFrom(Payee.PROVIDER, SettlementFixtures.requestTags())

        val accepted = SettlementFixtures.accept(
            request,
            store,
            FakeClock(SettlementFixtures.ACCEPTED_AT),
            ORDER_ZERO,
        )

        assertEquals(SettlementFixtures.ACCEPTED_AT, accepted.acceptedAt)
        assertEquals(request.invoice, accepted.invoice)
        assertSame(accepted, store.find(request.order, Payee.PROVIDER))
        assertNull(
            store.find(request.order, Payee.FEE),
            "§8.6's two invoices are two records; one payee's acceptance is not the other's",
        )
    }

    @JsName("with_a_fail_closed_clock_a_well_formed_request_is_not_stored_at_all")
    @Test
    fun `with a fail-closed clock a well-formed request is not stored at all`() {
        val store = PaymentRequestStore.inMemory()
        val request = SettlementFixtures.requestFrom(Payee.PROVIDER, SettlementFixtures.requestTags())

        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.accept(request, store, NenyaClock.FAIL_CLOSED, ORDER_ZERO)
        }

        assertEquals(
            SettlementRejection.CLOCK_UNAVAILABLE,
            refused.reason,
            "§9.2 check 5 is unperformable without the acceptance reading and §17 item 6 requires " +
                "it, so there is no shape that stores an invoice against no time",
        )
        assertNull(
            store.find(request.order, Payee.PROVIDER),
            "and nothing reached the store: a defaulted `now` is exactly what this refuses",
        )
    }

    @JsName("a_clock_reporting_before_1970_is_refused_as_broken")
    @Test
    fun `a clock reporting before 1970 is refused as broken`() {
        val store = PaymentRequestStore.inMemory()
        val request = SettlementFixtures.requestFrom(Payee.PROVIDER, SettlementFixtures.requestTags())

        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.accept(request, store, FakeClock(-1L), ORDER_ZERO)
        }

        assertEquals(SettlementRejection.CLOCK_BEFORE_EPOCH, refused.reason)
        assertNull(store.find(request.order, Payee.PROVIDER))
    }

    @JsName("the_epoch_itself_is_a_reading_and_not_a_break")
    @Test
    fun `the epoch itself is a reading, and not a break`() {
        // §4.6 makes the injected clock authoritative: a clock reporting 1970 is implausible and
        // usable, and only a *negative* reading is broken. The boundary is asserted, because an
        // implementation written from memory writes `<= 0`.
        val store = PaymentRequestStore.inMemory()
        val request = SettlementFixtures.requestFrom(Payee.PROVIDER, SettlementFixtures.requestTags())

        val accepted = SettlementFixtures.accept(request, store, FakeClock(0L), ORDER_ZERO)

        assertEquals(0L, accepted.acceptedAt)
    }

    @JsName("the_store_is_keyed_by_the_pair_s92_check_1_names")
    @Test
    fun `the store is keyed by the pair §9_2 check 1 names`() {
        val store = PaymentRequestStore.inMemory()
        val invoices = invoices(4)
        val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
        // Two payees under one order, and two orders under one payee.
        val requests = listOf(
            store(store, clock, 0, Payee.PROVIDER, invoices[0]),
            store(store, clock, 0, Payee.FEE, invoices[1]),
            store(store, clock, 1, Payee.PROVIDER, invoices[2]),
            store(store, clock, 1, Payee.FEE, invoices[3]),
        )

        for (accepted in requests) {
            assertSame(
                accepted,
                store.find(accepted.order, accepted.payee),
                "each record must be found under its own (order, payee) pair and no other",
            )
        }
        assertEquals(
            invoices.size,
            requests.map { it.invoice.text }.toSet().size,
            "the four fixtures must carry four different invoices, or no cross-match could show",
        )
    }

    // ---------------------------------------------------------------------------------------
    // §12 item 11 and STOP RULE 14.
    // ---------------------------------------------------------------------------------------

    @JsName("no_string_representation_here_carries_an_invoice_an_order_id_or_a_preimage")
    @Test
    fun `no string representation here carries an invoice, an order id or a preimage`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val store = PaymentRequestStore.inMemory()
        val request = SettlementFixtures.requestFrom(fixture.payee, fixture.requestTags)
        val accepted = SettlementFixtures.accept(
            request,
            store,
            FakeClock(SettlementFixtures.ACCEPTED_AT),
            ORDER_ZERO,
        )
        val receipt = SettlementFixtures.receipt(fixture.receiptTags)
        val settlement =
            Settlement.verify(receipt, store, SettlementFixtures.split())
        val secrets = listOf(fixture.invoice, fixture.orderHex, fixture.preimageHex)
        val printed = listOf(
            request.toString(),
            request.invoice.toString(),
            accepted.toString(),
            store.toString(),
            receipt.toString(),
            settlement.toString(),
        )

        for (text in printed) {
            for (secret in secrets) {
                assertFalse(
                    secret in text,
                    "§12 item 11 and STOP RULE 14 name the order id, the invoice and the preimage " +
                        "as values that MUST NOT appear in the string representation of anything " +
                        "this library exposes; this one does: $text",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * [count] distinct real invoices, each derived from a vendored example (decision D).
     *
     * Distinct because each carries the SHA-256 of its own preimage in its `p` field, which is what
     * several controls here turn on — a store keyed by `(order, payee)` cannot be shown to key on
     * the pair if the four records hold one string. The amounts cycle §8.6's two payee roles so
     * that a request stored for a fee payee is one §9.2 check 4 would accept for that payee; no
     * test in this file reaches check 4, and building the fixtures as though one might is what
     * stops the next one from silently not reaching it either.
     */
    private fun invoices(count: Int): List<String> {
        val split = SettlementFixtures.split()
        return PaymentFixtures.preimageHex(count).mapIndexed { at, preimage ->
            SettlementFixtures.invoice(
                preimage,
                SettlementFixtures.amountFor(Payee.entries[at % Payee.entries.size], split),
            )
        }
    }

    private fun assertRefusedAsMalformed(reference: String) {
        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    payment = SettlementFixtures.requestPaymentTag(reference),
                ),
            )
        }
        assertEquals(
            SettlementRejection.INVOICE_MALFORMED,
            refused.reason,
            "this reference does not have Appendix C's shape and must be refused as malformed " +
                "rather than as a static address or as uppercase",
        )
    }

    /**
     * One `(order, payee)` record stored through the only door that mints one.
     *
     * [index] rather than a bare order id, because revision `1.5` made three more of this
     * fixture's values travel together: the order the acceptance names, the provider key a
     * `payee=provider` request must be sealed by, and the fee recipient named in both the signed
     * `fee` term and the `payee` tag. `SettlementFixtures` derives all of them from the one index,
     * so a helper taking the order id alone could no longer build a request that stores.
     */
    private fun store(
        store: PaymentRequestStore,
        clock: NenyaClock,
        index: Int,
        payee: Payee,
        invoice: String,
    ): AcceptedPaymentRequest {
        val tags = SettlementFixtures.requestTags(
            index = index,
            payment = SettlementFixtures.requestPaymentTag(invoice),
            payeeTag = SettlementFixtures.payeeTag(payee, index),
            fee = if (payee == Payee.FEE) SettlementFixtures.feeTag(index) else null,
        )
        return SettlementFixtures.accept(
            SettlementFixtures.requestFrom(payee, tags, index = index),
            store,
            clock,
            acceptanceFor(index),
        )
    }
}
