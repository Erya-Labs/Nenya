package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderMessageKind
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §8.6's and §9.2's write half, held to the document and to the acceptance path that already
 * exists.
 *
 * `RumorWriterPropertyTest` proves build → decode → rebuild is byte-identical for both of these
 * messages over a generated corpus. This file carries the three things that proof cannot be:
 *
 * 1. **§9.2's emission order against the document.** The round trip drops the names the writer
 *    spells before rebuilding, so a wrong order is a fixed point of it;
 *    [PaymentWriter.RECEIPT_TAG_ORDER] is therefore held equal to the six tag names §9.2's own
 *    worked receipt prints, parsed out of `spec/NENYA-1.md` at test time.
 * 2. **The §8.6 proof T41 names: the existing acceptance path, not a new rule.** T41's STOP RULE 5
 *    note says the payment-request builder is "held to §8.6 by the store's existing acceptance path
 *    in its proof, not by a new rule of its own". So the whole flow runs on builder output and
 *    nothing else: a `type=1`, an `accepted` `type=3`, §7.6's own comparison minting the
 *    `Acceptance.Accepted`, a `type=2` decoded against §8.3's split, that request accepted into a
 *    store through `AcceptedPaymentRequest.accept`, and a `kind:17` verified against it by
 *    `Settlement.verify`. Every refusal §8.6 and §9.2 state is applied by the code that already
 *    stated them, to events this writer produced.
 * 3. **The negative controls**, each asserting its reason as data rather than by message wording.
 */
class PaymentWriterTest {

    private companion object {

        /** §7.5's buyer: the key that authors the proposal and the receipt. */
        val BUYER: String = RumorWriterFixtures.pubkeyFor(0)

        /** §7.6's provider, resolved outside both messages — the key that authors the `type=2`. */
        val PROVIDER: String = RumorWriterFixtures.pubkeyFor(100)

        /** §8.1's fee recipient, distinct from both parties. */
        val FEE_RECIPIENT: String = RumorWriterFixtures.pubkeyFor(200)

        val ORDER: OrderId = RumorWriterFixtures.orderFor(0)

        /** §9.2's preimage for the flow below, computed and never typed. */
        val PROOF: String = RumorWriterFixtures.proofFor(0)

        /**
         * The recipient's position in both tags that name it: §8.6's `["payee", "fee", <pubkey>]`
         * and §8.1's `["fee", "<bps>", "<recipient>"]`.
         *
         * The two rows carry different things in the first two positions and the same key in the
         * third, which is exactly why §8.4's byte-identity rule has a subject at all.
         */
        const val RECIPIENT_ELEMENT: Int = 2

        /** §8.3's terms: a price and a 250-bps fee, so both payees are required (§8.6). */
        val TERMS: OrderTerms = OrderTerms.of(
            Msat.ofSat(50_000L),
            FeeTerm.of(250),
            SettlementFixtures.ACCEPTED_AT + 1_000L,
            SettlementFixtures.ACCEPTED_AT + 90_000L,
        )

        /** §7.5's `item`, the same value on the proposal and on the acceptance (§7.6). */
        val ITEM: ItemRef = ItemRef(Coordinate.parse(TagFixtures.coordinate(index = 11)))

        fun envelope(
            author: String,
            counterparty: String,
            extraTags: List<List<String>> = emptyList(),
        ): RumorEnvelope = RumorEnvelope(
            authorPubkey = author,
            createdAt = RumorWriterFixtures.CREATED_AT,
            counterparties = listOf(PubkeyRef(counterparty)),
            extraTags = extraTags,
        )

        /** A real invoice for [amount] whose `p` field is SHA-256 of [PROOF] (decision **D**). */
        fun invoiceFor(amount: Msat): Bolt11Reference =
            Bolt11Reference.recognise(SettlementFixtures.invoice(PROOF, amount))
    }

    // -------------------------------------------------------------------------------------------
    // 1. The emission orders.
    // -------------------------------------------------------------------------------------------

    /**
     * §9.2's worked receipt prints six tags, and [PaymentWriter.RECEIPT_TAG_ORDER] is their order.
     *
     * The document is one side of this comparison, which is what makes it a control rather than the
     * writer agreeing with itself. `Section92` fails loudly if the fence or the count changes.
     */
    @JsName("the_receipt_emission_order_is_the_one_s9_2s_worked_example_prints")
    @Test
    fun `the receipt emission order is the one §9_2's worked example prints`() {
        assertEquals(
            Section92.receiptTagNames,
            PaymentWriter.RECEIPT_TAG_ORDER - ChannelVocabulary.FEE,
            "§4.1 hashes the tags in order, so §9.2's own example is what fixes this writer's",
        )
        // The one extra, documented rather than excused: §9.2's worked receipt is a
        // `payee=provider` one and §8.4 says so itself — "the kind:17 example in §9.2 correctly
        // carries no `fee` tag" — while making the pair REQUIRED on a `payee=fee` receipt.
        assertTrue(ChannelVocabulary.FEE in PaymentWriter.RECEIPT_TAG_ORDER, "§8.4 point 4")
        assertFalse(ChannelVocabulary.FEE in Section92.receiptTagNames)
    }

    /**
     * §8.6 prints no whole worked event, so the request's order is this writer's — and what can be
     * held to something outside this file is its **set**: every tag the decoder requires must be in
     * it, or the writer could never satisfy that decoder.
     *
     * The second half says what §8.6 does *not* ask a request to carry, and it is the one a writer
     * adds by habit: `amount_msat`. §8.6's refusals are all stated about the invoice, and §9.2
     * check 4 reads the amount out of the invoice's human-readable part rather than from a tag
     * beside it, so a request carrying one would be stating an amount nothing compares.
     */
    @JsName("the_request_emission_order_carries_every_tag_its_decoder_requires_and_no_amount")
    @Test
    fun `the request emission order carries every tag its decoder requires, and no amount`() {
        for (name in PaymentWriter.requestRequiredTags()) {
            assertTrue(
                name in PaymentWriter.REQUEST_TAG_ORDER,
                "`$name` is required on a type=2 and this writer could never emit it",
            )
        }
        assertFalse(
            ChannelVocabulary.AMOUNT_MSAT in PaymentWriter.REQUEST_TAG_ORDER,
            "§8.6 does not ask a request to carry `amount_msat`: the amount on a request is the " +
                "invoice, and §9.2 check 4 reads it from the human-readable part",
        )
        // §7.4 puts the `type` discriminator on a kind:16 and on nothing else.
        assertTrue(ChannelVocabulary.TYPE in PaymentWriter.REQUEST_TAG_ORDER)
        assertFalse(
            ChannelVocabulary.TYPE in PaymentWriter.RECEIPT_TAG_ORDER,
            "§7.4: a kind:17 carries no `type`",
        )
    }

    /**
     * For every tag §8.6's and §9.2's decoders require, removing it from a built event makes **that
     * decoder** refuse it naming **that tag**.
     *
     * A loop over the writer's published lists, which is what holds them equal to what the decoders
     * really demand: a tag the writer thinks is required and the decoder does not would make the
     * removal pass silently.
     */
    @JsName("removing_any_required_tag_makes_the_settlement_decoder_refuse_naming_it")
    @Test
    fun `removing any required tag makes the settlement decoder refuse naming it`() {
        var checked = 0
        val cases = listOf(
            Triple(PaymentWriter.requestRequiredTags(), request(Payee.PROVIDER), true),
            Triple(PaymentWriter.receiptRequiredTags(), receipt(Payee.PROVIDER), false),
        )
        for ((required, event, isRequest) in cases) {
            assertTrue(required.isNotEmpty(), "each message must require at least one tag")
            for (name in required) {
                val stripped = without(event, name)
                assertTrue(
                    stripped.tags.size < event.tags.size,
                    "the built event carries no `$name`, so removing it proves nothing",
                )
                assertEquals(
                    name,
                    refusalTag(stripped, isRequest),
                    "removing `$name` must be refused naming that tag",
                )
                checked++
            }
        }
        assertTrue(checked >= 11, "the loop checked only $checked tags, which is not the vocabulary")
    }

    // -------------------------------------------------------------------------------------------
    // 2. The §8.6 proof: the acceptance path, on builder output alone.
    // -------------------------------------------------------------------------------------------

    /**
     * §7.5, §7.6, §8.6 and §9.2 end to end, with **every event built by these writers** — which is
     * the proof T41's STOP RULE 5 note asks for.
     *
     * No tag array is assembled by hand anywhere in this test, and no rule is restated: §7.6's
     * byte-identical comparison is `OrderProposal.accepts`, §8.6's payee rule is
     * `PaymentRequest.decode` against §8.3's split, §8.6's sealing-key rule and §9.2 checks 4 and 5
     * are `AcceptedPaymentRequest.accept`, and §9.2's five checks are `Settlement.verify`. If a
     * builder emitted something any of those refuses, this test is where it fails — and it fails
     * naming the rule, because the refusal comes from the code that states it.
     *
     * Both payees, because §8.6's whole subject is that there are two invoices and never one: the
     * provider's for `price_msat` and the fee recipient's for `fee_msat`, each stored under its own
     * `(order, payee)` key.
     */
    @JsName("a_built_proposal_acceptance_request_and_receipt_pass_s7_6_s8_6_and_s9_2")
    @Test
    fun `a built proposal, acceptance, request and receipt pass §7_6, §8_6 and §9_2`() {
        // §7.5's `type=1`, from the buyer.
        val proposal = OrderProposal.decode(
            bound(
                RumorWriterFixtures.built(
                    RumorWriter.proposal(
                        envelope(BUYER, PROVIDER),
                        ORDER,
                        ITEM,
                        TERMS,
                        FEE_RECIPIENT,
                    ),
                    "§7.5's proposal",
                ),
            ),
        )
        // §7.6's acceptance: a `type=3` from the provider repeating the same four terms. The
        // comparison is over the **bytes** of `item`, `amount_msat`, `fee` and `deliver_by`, so
        // this is also the assertion that the two writers spell those four identically.
        val update = OrderStatusMessage.decode(
            bound(
                RumorWriterFixtures.built(
                    RumorWriter.statusUpdate(
                        envelope(PROVIDER, BUYER),
                        ORDER,
                        OrderState.ACCEPTED,
                        ITEM,
                        TERMS,
                        FEE_RECIPIENT,
                    ),
                    "§7.6's acceptance",
                ),
            ),
        )
        val answer = proposal.accepts(update, PROVIDER)
        val acceptance = answer as? dev.eryalabs.nenya.channel.Acceptance.Accepted ?: fail(
            "§7.6: a built `type=3` repeating a built `type=1`'s four terms MUST be an acceptance " +
                "and not $answer — a divergence here is the two writers disagreeing about how a " +
                "term is spelled, which is exactly what §7.6 compares byte-identically",
        )
        val points = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(update),
        )
        val store = PaymentRequestStore.inMemory()
        val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)

        // §8.6: two invoices, never one. Both payees are required here because the fee is non-zero,
        // and `Payee.requiredPayees` is what says so rather than this test.
        assertEquals(
            setOf(Payee.PROVIDER, Payee.FEE),
            Payee.requiredPayees(TERMS.split),
            "§8.6's two-invoice rule only has a subject when both payees are owed something",
        )
        for (payee in Payee.requiredPayees(TERMS.split)) {
            // §8.6's `type=2`, sealed by the provider — which `accept` compares against the key
            // §7.6's acceptance was checked against.
            val request = PaymentRequest.decode(bound(request(payee)), TERMS.split)
            assertEquals(payee, request.payee)
            val stored = AcceptedPaymentRequest.accept(request, acceptance, store, clock, points)
            assertEquals(payee, stored.payee)
            assertEquals(ORDER, stored.order)
            assertEquals(SettlementFixtures.ACCEPTED_AT, stored.acceptedAt, "§17 item 6's second value")

            // §9.2's receipt against that stored request: check 1's byte-identical invoice, check
            // 2's lowercase preimage, check 3's SHA-256, check 4's amount and check 5's expiry, all
            // performed by `Settlement.verify` over an event this writer produced.
            val settlement = Settlement.verify(
                PaymentReceipt.decode(bound(receipt(payee))),
                store,
                TERMS.split,
            )
            val evidenced = settlement as? Settlement.Evidenced ?: fail(
                "§9.2's five checks MUST pass on a receipt built from the same invoice and " +
                    "preimage this request was accepted against; got $settlement",
            )
            assertEquals(payee, evidenced.payee)
            assertEquals(PaymentMedium.LIGHTNING, evidenced.medium)
        }
    }

    // -------------------------------------------------------------------------------------------
    // 3. §8.6's payee arities and §9.4's rails.
    // -------------------------------------------------------------------------------------------

    /**
     * §8.6 prints one `payee` arity per role: `["payee", "provider"]` and
     * `["payee", "fee", "<pubkey>"]`.
     *
     * A two-element `fee` tag names no recipient for the one payee §8.6 requires one of, and is
     * refused. There is deliberately **no** mirror-image control for a three-element `provider`
     * tag, and the reason is §8.4 rather than an omission: the recipient parameter is the *order's*
     * fee recipient, which §8.4's pair names at every point and §8.6's `payee` tag names only on a
     * fee message — so a provider request given one is a conformant message carrying §8.4's
     * optional pair, and its `payee` tag still has two elements. That is asserted below, because it
     * is the case a writer gets wrong by refusing.
     */
    @JsName("s8_6s_two_payee_arities_are_checked_against_the_role")
    @Test
    fun `§8_6's two payee arities are checked against the role`() {
        val fee = RumorWriterFixtures.paidRefusal(
            PaymentWriter.paymentRequest(
                envelope(FEE_RECIPIENT, BUYER),
                ORDER,
                Payee.FEE,
                invoiceFor(TERMS.split.fee),
                terms = TERMS,
            ),
            "a fee request naming no recipient",
        )
        assertEquals(SettlementRejection.WRONG_ARITY, fee.reason)
        assertEquals("payee", fee.tag)

        // Both legal arities build, so the refusal above is about the mismatch and not about either
        // role. The emitted tag is asserted, because its arity is the rule.
        assertEquals(
            listOf("payee", Payee.PROVIDER.token),
            request(Payee.PROVIDER).tags.single { it[0] == "payee" },
            "§8.6 prints the provider payee with two elements, and the order's fee recipient — " +
                "which §8.4's pair on this very message names — is not one of them",
        )
        assertEquals(
            listOf("payee", Payee.FEE.token, FEE_RECIPIENT),
            request(Payee.FEE).tags.single { it[0] == "payee" },
        )
        // And §8.4's optional pair really is on that provider request, so the two-element `payee`
        // above is not the writer having dropped the recipient altogether.
        assertEquals(
            listOf(ChannelVocabulary.FEE, "250", FEE_RECIPIENT),
            request(Payee.PROVIDER).tags.single { it[0] == ChannelVocabulary.FEE },
        )
    }

    /**
     * §4.3 and §8.6 fix §8.6's third `payee` element as 64 **lowercase** hex characters, so an
     * uppercase recipient is emitted lowercased rather than verbatim.
     *
     * Three assertions, because emitting the caller's spelling is wrong three separate ways:
     *
     * 1. §8.6 names that element "a 64-character **lowercase** hex x-only public key", so any other
     *    spelling is one §4.3 forbids this library to emit;
     * 2. `TagWriter.fee` lowercases the recipient **it** writes, so the `payee` and `fee` tags would
     *    name one key under two spellings inside a single event this library authored — and §8.4
     *    requires the pair appear byte-identically wherever a `fee` tag appears for an order;
     * 3. `PaymentRequest.decode` normalises on read, so build → decode → rebuild would emit the
     *    lowercase form and the two events would differ — T41's headline property, false for this
     *    input.
     *
     * The generated corpus cannot reach any of it: `RumorWriterFixtures.pubkeyFor` is lowercase by
     * construction, so the round trip was silent here and this is where the case is drawn.
     */
    @JsName("an_uppercase_fee_recipient_is_emitted_as_s4_3s_lowercase_hex")
    @Test
    fun `an uppercase fee recipient is emitted as §4_3's lowercase hex`() {
        assertTrue(
            FEE_RECIPIENT != FEE_RECIPIENT.uppercase(),
            "the generated recipient carries no hex letter, so there is no uppercase case to draw",
        )

        // **Both** messages, because each spells its `payee` tag through its own call to
        // `PaymentWriter`'s normaliser — so reverting either one alone has to turn this red. The
        // receipt half is the one nothing else in this file can reach: every other assertion on an
        // emitted `payee` tag is on a request, and the end-to-end test's receipts go through
        // `Settlement.verify`, which normalises both sides on read and therefore passes whichever
        // spelling was emitted. A control that covered the request alone would leave the twin line
        // unverified, which is a gap in the verifier rather than in the code.
        val messages = listOf<Pair<String, (String) -> WireEvent>>(
            "request" to { recipient ->
                RumorWriterFixtures.paid(
                    PaymentWriter.paymentRequest(
                        envelope(FEE_RECIPIENT, BUYER),
                        ORDER,
                        Payee.FEE,
                        invoiceFor(TERMS.split.fee),
                        feeRecipient = recipient,
                        terms = TERMS,
                    ),
                    "a fee request naming an uppercase recipient",
                )
            },
            "receipt" to { recipient ->
                RumorWriterFixtures.paid(
                    PaymentWriter.receipt(
                        envelope(BUYER, PROVIDER),
                        ORDER,
                        Payee.FEE,
                        invoiceFor(TERMS.split.fee).text,
                        PROOF,
                        feeRecipient = recipient,
                        terms = TERMS,
                    ),
                    "a fee receipt naming an uppercase recipient",
                )
            },
        )

        for ((what, build) in messages) {
            val upper = build(FEE_RECIPIENT.uppercase())
            assertEquals(
                listOf("payee", Payee.FEE.token, FEE_RECIPIENT),
                upper.tags.single { it[0] == "payee" },
                "$what: §8.6 fixes the recipient as 64 lowercase hex characters, whatever the " +
                    "caller spelled",
            )
            assertEquals(
                FEE_RECIPIENT,
                upper.tags.single { it[0] == ChannelVocabulary.FEE }[RECIPIENT_ELEMENT],
                "$what: §8.4 — one key, one spelling, in one event. `TagWriter.fee` lowercases the " +
                    "recipient it writes and §8.6's `payee` tag must agree with it byte for byte",
            )
            RumorWriterFixtures.assertSameBytes(
                build(FEE_RECIPIENT),
                upper,
                "a fee $what whose recipient was given in uppercase",
            )
        }
    }

    /** §4.3 fixes a fee recipient's pubkey as exactly 64 hex characters, refused rather than padded. */
    @JsName("a_malformed_fee_recipient_on_a_payee_tag_is_refused")
    @Test
    fun `a malformed fee recipient on a payee tag is refused`() {
        for (recipient in listOf("", "abc", FEE_RECIPIENT.dropLast(1), "g".repeat(64))) {
            val refused = RumorWriterFixtures.paidRefusal(
                PaymentWriter.paymentRequest(
                    envelope(FEE_RECIPIENT, BUYER),
                    ORDER,
                    Payee.FEE,
                    invoiceFor(TERMS.split.fee),
                    feeRecipient = recipient,
                ),
                "a fee request naming a ${recipient.length}-character recipient",
            )
            assertEquals(SettlementRejection.MALFORMED_PAYEE_RECIPIENT, refused.reason)
            assertEquals("payee", refused.tag)
        }
    }

    /**
     * §8.6: a `type=2` MUST carry exactly one `["payment", "lightning", "<bolt11>"]` tag, and §9.4's
     * other rails are a **receipt** permission rather than a request one.
     *
     * The paired control is the receipt, where both of those rails *are* emittable — §9.4 says a
     * receipt naming one MAY be parsed and MUST be treated as unverified — so the refusal above is
     * §8.6's rule about a request and not this writer refusing a rail outright.
     */
    @JsName("s9_4s_other_rails_are_refused_on_a_request_and_permitted_on_a_receipt")
    @Test
    fun `§9_4's other rails are refused on a request and permitted on a receipt`() {
        for (medium in listOf(PaymentMedium.BITCOIN, PaymentMedium.ECASH)) {
            val refused = RumorWriterFixtures.paidRefusal(
                PaymentWriter.paymentRequest(
                    envelope(PROVIDER, BUYER),
                    ORDER,
                    Payee.PROVIDER,
                    invoiceFor(TERMS.split.price),
                    medium = medium,
                ),
                "a request on §9.4's ${medium.token} rail",
            )
            assertEquals(SettlementRejection.MEDIUM_NOT_LIGHTNING, refused.reason)
            assertEquals("payment", refused.tag)

            // The same rail on a receipt builds, and its decode reports no preimage at all: §9.4
            // defines no verification rule for it, so the proof is not read as one.
            val event = RumorWriterFixtures.paid(
                PaymentWriter.receipt(
                    envelope(BUYER, PROVIDER),
                    ORDER,
                    Payee.PROVIDER,
                    "an-address-or-mint",
                    "a-txid-or-mint-proof",
                    medium = medium,
                ),
                "a receipt on §9.4's ${medium.token} rail",
            )
            val decoded = PaymentReceipt.decode(bound(event))
            assertEquals(medium, decoded.medium)
            assertEquals(
                null,
                decoded.preimage,
                "§9.4 defines no verification rule for ${medium.token}, so its proof is not a preimage",
            )
        }
    }

    /**
     * §9.4's unrecognised `<medium>` is the required treatment of a token this implementation has
     * never heard of, not a rail of its own — so it has no wire form, on either message.
     *
     * Its own constant rather than [SettlementRejection.MEDIUM_NOT_LIGHTNING], which is about real
     * rails arriving on the wrong message. `PaymentMedium.UNKNOWN` carrying a `null` token is what
     * makes a sentinel unrepresentable.
     */
    @JsName("s9_4s_unknown_medium_is_not_emittable_on_either_message")
    @Test
    fun `§9_4's unknown medium is not emittable on either message`() {
        assertEquals(null, PaymentMedium.UNKNOWN.token, "§9.4's sink carries no token on purpose")

        val request = RumorWriterFixtures.paidRefusal(
            PaymentWriter.paymentRequest(
                envelope(PROVIDER, BUYER),
                ORDER,
                Payee.PROVIDER,
                invoiceFor(TERMS.split.price),
                medium = PaymentMedium.UNKNOWN,
            ),
            "a request on §9.4's unknown rail",
        )
        assertEquals(SettlementRejection.UNKNOWN_MEDIUM_IS_NOT_EMITTABLE, request.reason)

        val receipt = RumorWriterFixtures.paidRefusal(
            PaymentWriter.receipt(
                envelope(BUYER, PROVIDER),
                ORDER,
                Payee.PROVIDER,
                "a-reference",
                PROOF,
                medium = PaymentMedium.UNKNOWN,
            ),
            "a receipt on §9.4's unknown rail",
        )
        assertEquals(SettlementRejection.UNKNOWN_MEDIUM_IS_NOT_EMITTABLE, receipt.reason)
        assertEquals("payment", receipt.tag)
    }

    // -------------------------------------------------------------------------------------------
    // 4. §9.2 check 2, on write.
    // -------------------------------------------------------------------------------------------

    /**
     * §9.2 check 2: "The proof MUST be **lowercase** hex and MUST decode to exactly 32 bytes.
     * Uppercase and mixed case are rejected rather than normalised."
     *
     * Applied on write for the one rail §9.2 states it over, which the paired control above shows
     * is not every rail. The round trip cannot see this at all — a writer that emitted an uppercase
     * proof would round-trip it perfectly and the decoder would refuse the result — so refusing it
     * here is the same refusal one step earlier, and both are asserted.
     *
     * The second half holds the writer's length rule to `Preimage`'s own, which is the comparison
     * that would catch the two drifting apart: the proof this writer accepts is exactly the proof
     * T3's reader accepts.
     */
    @JsName("s9_2_check_2s_lowercase_hex_rule_is_applied_on_write")
    @Test
    fun `§9_2 check 2's lowercase hex rule is applied on write`() {
        val illegal = listOf(
            "uppercase" to PROOF.uppercase(),
            // The first hex *letter* uppercased, not the first character: a digest's first
            // character is a digit about six times in ten, and `uppercase()` on a digit is a no-op
            // — so "mixed case" built that way is the lowercase proof itself, and the control
            // passes while asserting nothing. Measured, not assumed: that is what it did.
            "mixed case" to mixedCase(PROOF),
            "too short" to PROOF.dropLast(2),
            "too long" to PROOF + "ab",
            "not hex" to "g".repeat(64),
        )
        for ((what, proof) in illegal) {
            val refused = RumorWriterFixtures.paidRefusal(
                PaymentWriter.receipt(
                    envelope(BUYER, PROVIDER),
                    ORDER,
                    Payee.PROVIDER,
                    invoiceFor(TERMS.split.price).text,
                    proof,
                ),
                "a lightning receipt whose proof is $what",
            )
            assertEquals(SettlementRejection.PREIMAGE_MALFORMED, refused.reason, what)
            assertEquals("payment", refused.tag, what)
            assertFalse(
                proof in refused.detail,
                "§12 item 11 and STOP RULE 14: a refusal MUST NOT echo a preimage",
            )
            // And the decoder refuses the same value, so the writer is not inventing a rule: the
            // event a mutated writer would emit is one this library's own codec rejects.
            val mutated = replacingProof(receipt(Payee.PROVIDER), proof)
            val thrown = runCatching { PaymentReceipt.decode(bound(mutated)) }
                .exceptionOrNull() as? SettlementException
                ?: fail("§9.2 check 2 requires the decoder reject a $what proof")
            assertEquals(SettlementRejection.PREIMAGE_MALFORMED, thrown.reason, what)
        }

        // The writer's length rule and T3's reader agree: what one accepts the other does.
        Preimage.ofHex(PROOF)
        assertEquals(64, PROOF.length, "§9.2 check 2 fixes the proof at 32 bytes of hex")
    }

    // -------------------------------------------------------------------------------------------
    // 5. §8.3's zero-fee rule, and §4.3's extension rules.
    // -------------------------------------------------------------------------------------------

    /**
     * §8.6 and §8.3: "when a payee's expected amount is `0`, **no payment request exists at all**
     * for it and any that arrives MUST be rejected."
     *
     * This writer does not know the split — it is handed a payee and an invoice — so it emits the
     * request and §8.6's own refusal is what catches it, in `PaymentRequest.decode`. That is T41's
     * STOP RULE 5 note working as written: the builder is held to §8.6 by the existing acceptance
     * path rather than by a second copy of the rule, and a second copy is how the two drift apart.
     *
     * The control asserts both halves — that the writer emits it and that the decoder refuses it
     * naming `payee` — because "the builder adds no rule" is otherwise a claim about absence.
     */
    @JsName("a_fee_request_for_a_zero_fee_order_is_refused_by_s8_6s_own_rule")
    @Test
    fun `a fee request for a zero-fee order is refused by §8_6's own rule`() {
        // §8.3's zero-fee case, and it has to be *this* shape to be reachable at all: a
        // `FeeTerm.of(0)` names no recipient (§8.1), so §8.6's three-element `payee=fee` tag could
        // not be well formed for it and there would be no event to offer the decoder. A term above
        // zero whose *computed* fee rounds to zero is the case §8.3 calls out, and it produces a
        // perfectly well-formed fee request for a payee §8.6 says has none.
        val zeroFee = OrderTerms.of(Msat.ofSat(3L), FeeTerm.of(1))
        assertEquals(Msat.ZERO, zeroFee.split.fee, "§8.3: floor(3000 × 1 / 10000) is 0")
        assertEquals(
            setOf(Payee.PROVIDER),
            Payee.requiredPayees(zeroFee.split),
            "§8.6: a payee owed zero has no request at all, and `requiredPayees` owns that rule",
        )

        // The writer emits it: it is handed a payee and an invoice and has no business deciding
        // §8.3's arithmetic, and inventing a rule here is what T41's STOP RULE 5 note forbids.
        val event = RumorWriterFixtures.paid(
            PaymentWriter.paymentRequest(
                envelope(FEE_RECIPIENT, BUYER),
                ORDER,
                Payee.FEE,
                invoiceFor(Msat.ofSat(1L)),
                feeRecipient = FEE_RECIPIENT,
                terms = zeroFee,
            ),
            "a fee request on an order whose fee rounds to zero",
        )

        // And §8.6's own refusal catches it.
        val thrown = runCatching { PaymentRequest.decode(bound(event), zeroFee.split) }
            .exceptionOrNull() as? SettlementException
            ?: fail("§8.6 requires a request for a payee owed zero be rejected")
        assertEquals(SettlementRejection.PAYEE_NOT_REQUIRED, thrown.reason)
        assertEquals("payee", thrown.tag)
    }

    /**
     * §8.4 points 3 and 4 make the `(bps, recipient)` pair **REQUIRED** on a `payee=fee` request and
     * on a `payee=fee` receipt, and **OPTIONAL** on the two `payee=provider` ones — where §8.4 adds
     * that an implementation MUST NOT require one.
     *
     * Both halves, because each is a separate way to be wrong: a writer that omitted the pair on a
     * fee message would emit one `Settlement.checkFeePaymentRequest` refuses, and a writer that
     * required it on a provider message would refuse the shape §9.2's own worked receipt prints.
     *
     * The pair is spelled by the same `TagWriter.fee` §7.5's proposal and §7.6's acceptance go
     * through, which is what makes §8.4's four points byte-identical rather than identical-looking —
     * and the end-to-end test above is where that is actually proved, by `FeeTermAgreement` over
     * built events.
     */
    @JsName("s8_4s_fee_pair_is_required_on_a_fee_message_and_optional_on_a_provider_one")
    @Test
    fun `§8_4's fee pair is required on a fee message and optional on a provider one`() {
        // REQUIRED: a fee request and a fee receipt built without the terms are refused by name.
        val request = RumorWriterFixtures.paidRefusal(
            PaymentWriter.paymentRequest(
                envelope(FEE_RECIPIENT, BUYER),
                ORDER,
                Payee.FEE,
                invoiceFor(TERMS.split.fee),
                feeRecipient = FEE_RECIPIENT,
            ),
            "a fee request carrying no §8.4 pair",
        )
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, request.reason)
        assertEquals(ChannelVocabulary.FEE, request.tag)

        val receipt = RumorWriterFixtures.paidRefusal(
            PaymentWriter.receipt(
                envelope(BUYER, PROVIDER),
                ORDER,
                Payee.FEE,
                invoiceFor(TERMS.split.fee).text,
                PROOF,
                feeRecipient = FEE_RECIPIENT,
            ),
            "a fee receipt carrying no §8.4 pair",
        )
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, receipt.reason)

        // OPTIONAL: both provider messages build with no terms and carry no `fee` tag at all.
        for (what in listOf("request", "receipt")) {
            val event = if (what == "request") {
                RumorWriterFixtures.paid(
                    PaymentWriter.paymentRequest(
                        envelope(PROVIDER, BUYER),
                        ORDER,
                        Payee.PROVIDER,
                        invoiceFor(TERMS.split.price),
                    ),
                    "a provider request carrying no §8.4 pair",
                )
            } else {
                RumorWriterFixtures.paid(
                    PaymentWriter.receipt(
                        envelope(BUYER, PROVIDER),
                        ORDER,
                        Payee.PROVIDER,
                        invoiceFor(TERMS.split.price).text,
                        PROOF,
                    ),
                    "a provider receipt carrying no §8.4 pair",
                )
            }
            assertTrue(
                event.tags.none { it[0] == ChannelVocabulary.FEE },
                "§8.4 makes the pair OPTIONAL on a provider $what and forbids requiring one",
            )
        }

        // And where it IS given on a fee message, it is the pair §8.4 compares — spelled exactly as
        // §7.5's proposal spells it, which is the whole of "byte-identically".
        val pair = listOf(ChannelVocabulary.FEE, "250", FEE_RECIPIENT)
        assertEquals(pair, request(Payee.FEE).tags.single { it[0] == ChannelVocabulary.FEE })
        assertEquals(
            pair,
            RumorWriterFixtures.built(
                RumorWriter.proposal(
                    RumorEnvelope(BUYER, RumorWriterFixtures.CREATED_AT, listOf(PubkeyRef(PROVIDER))),
                    ORDER,
                    ITEM,
                    TERMS,
                    FEE_RECIPIENT,
                ),
                "§7.5's proposal",
            ).tags.single { it[0] == ChannelVocabulary.FEE },
            "§8.4: the same pair, byte-identically, wherever a `fee` tag appears for an order",
        )
    }

    /**
     * §8.6 states the cardinality of `payee` and `payment` as exactly one each, so either handed in
     * as an extension is a second occurrence §4.3 requires be rejected.
     *
     * Routed through [RumorWriter]'s own extra-tag audit rather than a copy here, which is why the
     * reason is a [ChannelRejection] on `channelReason` rather than a [SettlementRejection]: §4.3's
     * rule is the same rule whichever message it is about, and the field says which layer answered.
     */
    @JsName("s8_6s_two_rows_handed_in_as_extensions_are_refused_as_duplicates")
    @Test
    fun `§8_6's two rows handed in as extensions are refused as duplicates`() {
        for (name in listOf("payee", "payment")) {
            val refused = RumorWriterFixtures.paidRefusal(
                PaymentWriter.paymentRequest(
                    envelope(PROVIDER, BUYER, extraTags = listOf(listOf(name, "anything"))),
                    ORDER,
                    Payee.PROVIDER,
                    invoiceFor(TERMS.split.price),
                ),
                "a request carrying a second `$name`",
            )
            assertEquals(ChannelRejection.DUPLICATE_TAG, refused.channelReason)
            assertEquals(null, refused.reason, "§4.3's rule is the channel layer's, not §8.6's")
            assertEquals(name, refused.tag)
        }
    }

    /**
     * §4.1's event fields and §4.5's version are reported on [PaymentBuild.Refused.channelReason],
     * verbatim from [RumorWriter].
     *
     * That is the whole point of the delegation: §7.4's envelope rules live in one place, and this
     * writer reports their answer rather than translating it into a §8.6 constant that would be
     * about the wrong rule.
     */
    @JsName("s7_4s_envelope_refusals_arrive_on_the_channel_reason")
    @Test
    fun `§7_4's envelope refusals arrive on the channel reason`() {
        val refused = RumorWriterFixtures.paidRefusal(
            PaymentWriter.paymentRequest(
                RumorEnvelope("not-a-pubkey", RumorWriterFixtures.CREATED_AT, listOf(PubkeyRef(BUYER))),
                ORDER,
                Payee.PROVIDER,
                invoiceFor(TERMS.split.price),
            ),
            "a request authored by a malformed pubkey",
        )
        assertEquals(ChannelRejection.MALFORMED_EVENT_FIELD, refused.channelReason)
        assertEquals(null, refused.reason)
    }

    /**
     * §9.2's `amount_msat` on a receipt is check 4's **expected amount for that payee**, computed by
     * the one function in this library that reads §9.2's sentence.
     *
     * `price_msat` for `provider` and §8.3's `fee_msat` for `fee`, which are different numbers — so
     * a writer that emitted the price for both would pass a provider-only test and state the wrong
     * figure on every fee receipt.
     */
    @JsName("a_receipts_amount_msat_is_check_4s_expected_amount_for_that_payee")
    @Test
    fun `a receipt's amount_msat is check 4's expected amount for that payee`() {
        assertTrue(
            TERMS.split.price != TERMS.split.fee,
            "the two expected amounts must differ for this control to discriminate",
        )
        for (payee in listOf(Payee.PROVIDER, Payee.FEE)) {
            val tags = receipt(payee).tags
            assertEquals(
                listOf(
                    ChannelVocabulary.AMOUNT_MSAT,
                    Settlement.expectedAmount(payee, TERMS.split).millisatoshis.toString(),
                ),
                tags.single { it[0] == ChannelVocabulary.AMOUNT_MSAT },
                "§9.2 check 4's expected amount for ${payee.token}",
            )
        }
        // And a receipt built with no terms carries none at all, which §9.2 treats identically.
        assertTrue(
            RumorWriterFixtures.paid(
                PaymentWriter.receipt(
                    envelope(BUYER, PROVIDER),
                    ORDER,
                    Payee.PROVIDER,
                    invoiceFor(TERMS.split.price).text,
                    PROOF,
                ),
                "a receipt carrying no amount",
            ).tags.none { it[0] == ChannelVocabulary.AMOUNT_MSAT },
        )
    }

    // -------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------

    /** §8.6's `type=2` for [payee], built and asserted to have built. */
    private fun request(payee: Payee): WireEvent = RumorWriterFixtures.paid(
        PaymentWriter.paymentRequest(
            envelope(if (payee == Payee.FEE) FEE_RECIPIENT else PROVIDER, BUYER),
            ORDER,
            payee,
            invoiceFor(Settlement.expectedAmount(payee, TERMS.split)),
            // The **order's** fee recipient, on both payees: §8.4's pair names it wherever a `fee`
            // tag appears, and §8.6's `payee` tag takes it only on the fee side.
            feeRecipient = FEE_RECIPIENT,
            terms = TERMS,
        ),
        "§8.6's type=2 for ${payee.token}",
    )

    /** §9.2's `kind:17` for [payee], against the same invoice and preimage [request] used. */
    private fun receipt(payee: Payee): WireEvent = RumorWriterFixtures.paid(
        PaymentWriter.receipt(
            envelope(BUYER, PROVIDER),
            ORDER,
            payee,
            invoiceFor(Settlement.expectedAmount(payee, TERMS.split)).text,
            PROOF,
            feeRecipient = FEE_RECIPIENT,
            terms = TERMS,
        ),
        "§9.2's kind:17 for ${payee.token}",
    )

    /** [event] attributed to the key that authored it — §7.2's positive case. */
    private fun bound(event: WireEvent): AttributedRumor.Bound = RumorWriterFixtures.bound(event)

    /**
     * [hex] with its first `a`–`f` uppercased, and a loud failure if it carries none.
     *
     * The failure matters: a digest with no hex letter at all would make the mixed-case control
     * silently equal to the lowercase value, which is the bug this function exists to have fixed.
     */
    private fun mixedCase(hex: String): String {
        val at = hex.indexOfFirst { it in 'a'..'f' }
        assertTrue(at >= 0, "the generated proof carries no hex letter, so no mixed case exists")
        return hex.substring(0, at) + hex[at].uppercaseChar() + hex.substring(at + 1)
    }

    /** [event] with every occurrence of [name] removed, which is what the removal loop builds. */
    private fun without(event: WireEvent, name: String): WireEvent = WireEvent(
        event.pubkey,
        event.createdAt,
        event.kind,
        event.tags.filter { it[0] != name },
        event.content,
    )

    /** [event] with its `payment` tag's fourth element replaced — a mutated writer's output. */
    private fun replacingProof(event: WireEvent, proof: String): WireEvent = WireEvent(
        event.pubkey,
        event.createdAt,
        event.kind,
        event.tags.map { if (it[0] == "payment") it.dropLast(1) + proof else it },
        event.content,
    )

    /** The tag §8.6's or §9.2's decoder names when it refuses [event]. */
    private fun refusalTag(event: WireEvent, isRequest: Boolean): String? {
        val thrown = runCatching {
            val rumor = bound(event)
            if (isRequest) PaymentRequest.decode(rumor, TERMS.split) else PaymentReceipt.decode(rumor)
        }.exceptionOrNull()
        // §7.4's envelope is refused by `AttributedRumor.attribute` as a ChannelException and
        // §8.6's own rows by the two decoders as a SettlementException. Both are real answers to
        // "which tag was missing", and flattening them would hide which layer made the refusal.
        return when (thrown) {
            is SettlementException -> thrown.tag
            is dev.eryalabs.nenya.channel.ChannelException -> thrown.tag
            else -> fail("a message missing a required tag must be refused; got $thrown")
        }
    }

    /** §7.4's `type=2`, so the control above names the message it is about. */
    @Suppress("unused")
    private val requestType: Int? = OrderMessageKind.PAYMENT_REQUEST.type
}
