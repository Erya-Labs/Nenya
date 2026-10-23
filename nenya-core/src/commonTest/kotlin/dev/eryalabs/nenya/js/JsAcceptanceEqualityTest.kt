package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.settlement.AcceptedPaymentRequest
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T44 gap (1) across the JavaScript boundary: [acceptPaymentRequest] answers exactly what
 * `AcceptedPaymentRequest.accept` answers, over the same `type=2`.
 *
 * ### Why this one gets the task's named mutation
 *
 * This is the entry point that **writes**, and it sits on the evidence path: §9.2 check 1 compares
 * a receipt against *the stored request*, so whatever this call lets into the store is what every
 * later payment claim for the order is anchored to. The seven refusals behind it are the only
 * thing standing between a page and a stored bill nobody checked — §8.6's provider sender rule,
 * §8.7's fee sealing key, Appendix C's parser, check 4's amount, check 5's expiry against the
 * clock reading taken at acceptance, and §8.6's two uniqueness rules.
 *
 * T44's mutation is "make the new bill entry point skip the library call and store the request
 * directly; confirm the amount-padding control turns red", and
 * [a padded bill is refused by the amount the terms name] is that control: the padded request is
 * conformant in every other respect, so a facade that stored it without asking the library would
 * answer `accepted` where this library answers `INVOICE_AMOUNT_MISMATCH`.
 *
 * ### Two stores, one clock
 *
 * Each case runs the call twice, and the two runs cannot share a store: §8.6's revision `1.5` rule
 * refuses a second `type=2` for an `(order, payee)` pair, so the second run would be refused
 * `REQUEST_ALREADY_STORED` for having succeeded the first time. So each side gets its own empty
 * store and both get the same pinned clock reading, which is the value §17 item 6 requires be
 * persisted and the one §9.2 check 5 is evaluated against.
 */
class JsAcceptanceEqualityTest {

    /**
     * §8.6's whole acceptance path for both payees, across the boundary — the positive case.
     *
     * The record's five published values are compared, `invoice` above all: §9.2 check 1 is a
     * byte-identical comparison and a crossing that normalised one character of a BOLT-11 string
     * would re-point this order's payment evidence at an invoice nobody issued.
     */
    @Test
    @JsName("acceptPaymentRequest_crosses_section_8_6_s_acceptance_for_both_payees")
    fun `acceptPaymentRequest crosses §8_6's acceptance for both payees`() {
        for (payee in Payee.entries) {
            val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
            val page = JsFlowFixtures.CrossedStore()
            val crossed = acceptBothWays(
                what = "a $payee bill",
                messages = messages,
                requestRumor = messages.requestRumors.getValue(payee),
                points = signedPoints(messages),
                crossedStore = page,
            )

            assertTrue(crossed.ok, "a conformant `type=2` for a required payee must be accepted")
            val record = crossed.request ?: fail("an accepted answer carries a record")
            assertEquals(payee.token, record.payee, "§8.6's payee role, as its token")
            assertEquals(
                SettlementFixtures.ACCEPTED_AT.toString(),
                record.acceptedAtSeconds,
                "§17 item 6's second value is the injected clock's reading, not a fabricated now",
            )

            // The write-through, which no entry point before this one could exercise: the page's
            // own `store` function was called, and what it holds is findable under §9.2 check 1's
            // own key. A facade that minted a record and never handed it over would leave every
            // later receipt refused `NO_STORED_REQUEST`.
            assertEquals(1, page.size, "the page's store was written exactly once")
            val held = page.find(record.orderIdHex, record.payee)
                ?: fail("the page must hold the record under (order, payee)")
            assertEquals(record.invoice, held.invoice, "the stored record is the one minted")
        }
    }

    /**
     * §9.2 check 4, through the facade: a bill for more than the accepted terms owe is refused.
     *
     * **This is the control T44's mutation is aimed at.** Everything else about the message is
     * right — the order, the payee, the seal, a real BOLT-11 whose `p` is this stream's payment
     * hash — and the only thing wrong is the amount, which is a value no part of the crossing
     * looks at. A facade that stored the request without calling `AcceptedPaymentRequest.accept`
     * would therefore answer `accepted` here while the library answers `INVOICE_AMOUNT_MISMATCH`,
     * and [JsFlowFixtures.assertSameAccepted]'s first assertion is what fails.
     */
    @Test
    @JsName("a_padded_bill_is_refused_by_the_amount_the_terms_name")
    fun `a padded bill is refused by the amount the terms name`() {
        for (payee in Payee.entries) {
            val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
            val owed = SettlementFixtures.amountFor(payee, messages.accepted.terms.split)
            val page = JsFlowFixtures.CrossedStore()
            val crossed = acceptBothWays(
                what = "a padded $payee bill",
                messages = messages,
                // One millisatoshi above what §8.3 owes. Composed into a real invoice, never typed.
                requestRumor = messages.paddedRequestRumor(payee, owed.millisatoshis + 1L),
                points = signedPoints(messages),
                crossedStore = page,
            )

            assertFalse(crossed.ok, "§9.2 check 4: the invoice must bill the amount the terms name")
            assertEquals(
                SettlementRejection.INVOICE_AMOUNT_MISMATCH.name,
                crossed.reason,
                "§9.2 check 4, and decision I: an invoice this library would refuse a receipt " +
                    "against is an invoice it must not have stored",
            )
            assertEquals("SettlementRejection", crossed.reasonVocabulary, "§9.2's own vocabulary")
            assertEquals(0, page.size, "a refused bill is never handed to the page's store")
        }
    }

    /**
     * The rest of §8.6's and §9.2's refusals on this path, returned as values.
     *
     * Each is a different layer saying no, and the point of asserting all four is that the facade
     * routes to the library for every one of them rather than for the common case only.
     */
    @Test
    @JsName("the_acceptance_entry_point_returns_the_library_s_refusals_as_values")
    fun `the acceptance entry point returns the library's refusals as values`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val request = messages.requestRumors.getValue(Payee.PROVIDER)

        // §8.6's revision 1.5 uniqueness rule, against the store that already holds both records.
        val held = JsFlowFixtures.CrossedStore()
        val first = acceptBothWays("the first provider bill", messages, request, signedPoints(messages), held)
        assertTrue(first.ok, "the first acceptance must succeed for the second to mean anything")
        val second = acceptPaymentRequest(
            request = JsRumor(request),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = SettlementFixtures.provider(messages.index),
            store = held.seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = clock()),
            earlierPoints = signedPoints(messages).map { JsFlowFixtures.crossed(it) }.toTypedArray(),
        )
        assertFalse(second.ok, "§8.6: a second `type=2` for one (order, payee) MUST be rejected")
        assertEquals(SettlementRejection.REQUEST_ALREADY_STORED.name, second.reason, "§8.6")
        assertEquals(1, held.size, "the record already held is untouched")

        // §9.2 check 5 has no operand without a clock reading, so nothing is stored at all.
        val noClock = acceptPaymentRequest(
            request = JsRumor(request),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = SettlementFixtures.provider(messages.index),
            store = JsFlowFixtures.CrossedStore().seam,
            earlierPoints = signedPoints(messages).map { JsFlowFixtures.crossed(it) }.toTypedArray(),
        )
        assertFalse(noClock.ok, "an absent clock fails closed, as the library default does")
        assertEquals(SettlementRejection.CLOCK_UNAVAILABLE.name, noClock.reason, "§9.2 check 1, §17 item 6")

        // §8.4's three REQUIRED points absent on the fee side: refused rather than compared smaller.
        val noPoints = acceptBothWays(
            what = "a fee bill with no earlier §8.4 points",
            messages = messages,
            requestRumor = messages.requestRumors.getValue(Payee.FEE),
            points = emptyList(),
        )
        assertFalse(noPoints.ok, "§8.4's points cannot be absent on a fee request")
        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING.name, noPoints.reason, "§8.4, §8.7")

        // §7.6's own refusal, reached before the request is looked at: the key the page resolved is
        // the buyer's, which §7.6 says MUST be rejected even where every term is byte-identical.
        val providerIsBuyer = acceptBothWays(
            what = "a bill accepted against the buyer's own key",
            messages = messages,
            requestRumor = request,
            points = signedPoints(messages),
            providerPubkey = messages.proposal.buyer,
        )
        assertFalse(providerIsBuyer.ok, "§7.6: the provider is resolved independently of the proposal")
        assertEquals(ChannelRejection.PROVIDER_IS_BUYER.name, providerIsBuyer.reason, "§7.6")
        assertEquals("ChannelRejection", providerIsBuyer.reasonVocabulary, "§7.6's own vocabulary")
    }

    /**
     * The three refusals about **who** and **which order**, which are the ones this entry point
     * exists to be safe against.
     *
     * Decision J's own scenario is the first: a page handing order A's `type=2` with order B's
     * proposal and acceptance. Every operand `accept` uses comes from the acceptance — the provider
     * key, §8.3's expected amount, the signed fee term — so judging the bill against it would
     * compare it to terms nobody signed for it **and report each of those checks as performed**.
     * The other two are §8.6's and §8.7's sender rules, one per payee, and each is a real key
     * belonging to the wrong party rather than a malformed anything: a provider invoice sealed by a
     * stranger is somebody else's bill under the provider's name, and a fee invoice forwarded by
     * the provider is exactly what §8.7 was written to refuse.
     */
    @Test
    @JsName("the_bill_s_order_and_the_key_that_sealed_it_are_the_library_s_rulings")
    fun `the bill's order and the key that sealed it are the library's rulings`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val other = OrderFixtures.messages(OrderFixtures.OTHER_ORDER_INDEX, stream = 0)

        // Decision J: another order's bill, against this order's acceptance.
        val otherOrder = acceptBothWays(
            what = "another order's bill against this order's acceptance",
            messages = messages,
            requestRumor = other.requestRumors.getValue(Payee.PROVIDER),
            points = signedPoints(messages),
        )
        assertFalse(otherOrder.ok, "every operand `accept` uses comes from the acceptance")
        assertEquals(
            SettlementRejection.REQUEST_FOR_ANOTHER_ORDER.name,
            otherOrder.reason,
            "§8.6, decision J: three halves of three different orders must not be assemblable",
        )

        // §8.6: a provider `type=2` whose seal is not the key §7.6 checked the acceptance against.
        val fromAStranger = acceptBothWays(
            what = "a provider bill sealed by a stranger",
            messages = messages,
            requestRumor = requestSealedBy(SettlementFixtures.stranger(messages.index), messages, Payee.PROVIDER),
            points = signedPoints(messages),
        )
        assertFalse(fromAStranger.ok, "§8.6: a provider invoice from another key is somebody else's bill")
        assertEquals(
            SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER.name,
            fromAStranger.reason,
            "§8.6: the tag is present and well formed; what is wrong is who sealed the message",
        )

        // §8.7: a fee `type=2` forwarded by the provider rather than sealed by the fee recipient.
        val forwardedFee = acceptBothWays(
            what = "a fee bill sealed by the provider",
            messages = messages,
            requestRumor = requestSealedBy(SettlementFixtures.provider(messages.index), messages, Payee.FEE),
            points = signedPoints(messages),
        )
        assertFalse(forwardedFee.ok, "§8.7 binds a fee invoice to its recipient through the transport")
        assertEquals(
            SettlementRejection.FEE_SEAL_NOT_RECIPIENT.name,
            forwardedFee.reason,
            "§8.7: the seal's pubkey must equal the recipient named in the signed fee term",
        )
    }

    /**
     * §8.6's zero-amount rule, which is the one thing `PaymentRequest.decode` reads the split for —
     * and therefore the only observable consequence of taking that split off the **acceptance**.
     *
     * §8.6: "when a payee's expected amount is `0`, no payment request exists at all for it and any
     * that arrives MUST be rejected". The order here carries no `fee` tag, which §8.1 makes a
     * complete statement of a zero fee, so a `payee=fee` bill for it names a payee no receipt may
     * ever be required from. A facade that had taken the split as a fourth crossed value could have
     * been handed one that disagreed with the signed terms and would have stored this bill.
     */
    @Test
    @JsName("a_bill_for_a_payee_the_signed_terms_owe_nothing_is_refused")
    fun `a bill for a payee the signed terms owe nothing is refused`() {
        val index = OrderFixtures.ORDER_INDEX
        // §8.1's absent fee term: complete terms meaning zero fee, so `requiredPayees` names only
        // the provider. Built here rather than from OrderFixtures, whose every order is fee-bearing.
        val proposalTags = ProposalFixtures.proposalTags(
            index = index,
            amountMsat = OrderFixtures.PRICE.millisatoshis.toString(),
            fee = null,
        )
        val crossed = acceptBothWays(
            what = "a fee bill on an order whose terms state no fee",
            requestRumor = SettlementFixtures.boundSealedBy(
                SettlementFixtures.feeRecipient(index),
                SettlementFixtures.requestTags(
                    index = index,
                    payment = SettlementFixtures.requestPaymentTag(
                        OrderFixtures.invoice(index, Payee.FEE, stream = 0),
                    ),
                    payeeTag = SettlementFixtures.payeeTag(Payee.FEE, index),
                ),
            ),
            proposalRumor = ProposalFixtures.bound(proposalTags, index),
            acceptanceRumor = ProposalFixtures.bound(
                ProposalFixtures.acceptanceTags(proposalTags, index),
                index + 1,
            ),
            providerPubkey = SettlementFixtures.provider(index),
            points = emptyList(),
        )
        assertFalse(crossed.ok, "§8.6: a payee owed nothing has no payment request at all")
        assertEquals(
            SettlementRejection.PAYEE_NOT_REQUIRED.name,
            crossed.reason,
            "§8.6 and §8.3: accepting one strands the order, and §11.1 says such an order expires",
        )
    }

    /**
     * §7.6's counter-proposal offered where an acceptance was required: the crossing's own refusal.
     *
     * The one answer on this path with no Kotlin exception to compare against — `accepts` *returns*
     * `Acceptance.CounterProposal`, and `AcceptedPaymentRequest.accept` has no parameter for one.
     * So the boundary reports [JsCrossing.UNCONSTRUCTIBLE_VALUE] under a vocabulary no section of
     * NENYA-1 uses, exactly as [stepOrder]'s `acceptance_received` does for the same value and
     * through the same `crossedAcceptance`. The assertion is that it is **not** reported as a §8.6
     * refusal, which a page could act on as though the bill were the problem.
     */
    @Test
    @JsName("a_counter_proposal_cannot_be_the_acceptance_a_bill_is_accepted_against")
    fun `a counter-proposal cannot be the acceptance a bill is accepted against`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val divergent = ProposalFixtures.bound(
            ProposalFixtures.replacing(
                ProposalFixtures.acceptanceTags(messages.proposalTags, messages.index),
                listOf(ChannelTags.AMOUNT_MSAT, (OrderFixtures.PRICE.millisatoshis + 1L).toString()),
            ),
            messages.index + 1,
        )
        // The fixture is only a control if §7.6 really answers a counter-proposal for it.
        val answer = OrderProposal.decode(messages.proposalRumor).accepts(
            OrderStatusMessage.decode(divergent),
            SettlementFixtures.provider(messages.index),
        )
        assertTrue(answer is Acceptance.CounterProposal, "the control must diverge on a term, not refuse")

        val crossed = acceptPaymentRequest(
            request = JsRumor(messages.requestRumors.getValue(Payee.PROVIDER)),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(divergent),
            providerPubkey = SettlementFixtures.provider(messages.index),
            store = JsFlowFixtures.CrossedStore().seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = clock()),
            earlierPoints = signedPoints(messages).map { JsFlowFixtures.crossed(it) }.toTypedArray(),
        )
        assertFalse(crossed.ok, "§7.6 found the terms divergent, so there is no acceptance")
        assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, crossed.reason, "the crossing's own reason")
        assertEquals(
            JsCrossing.VOCABULARY,
            crossed.reasonVocabulary,
            "a page must never be able to read this as a §8.6 refusal of the bill",
        )
        assertTrue(
            crossed.reason in JsCrossing.REASONS,
            "every reason this boundary reports must be one of the constants it declares",
        )
    }

    /** §7.4's `kind:14` offered as a bill: the crossing's refusal, as everywhere else. */
    @Test
    @JsName("a_chat_rumor_offered_as_a_bill_is_the_crossing_s_own_refusal")
    fun `a chat rumor offered as a bill is the crossing's own refusal`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val chat: AttributedRumor = ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())
        val crossed = acceptPaymentRequest(
            request = JsRumor(chat),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = SettlementFixtures.provider(messages.index),
            store = JsFlowFixtures.CrossedStore().seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = clock()),
        )
        assertFalse(crossed.ok, "a chat is not a payment request")
        assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, crossed.reason, "the crossing's own reason")
        assertEquals(JsCrossing.VOCABULARY, crossed.reasonVocabulary, "never a §8.6 ruling")
        assertNotNull(crossed.tag, "the crossing names which argument it was reading")
    }

    // -----------------------------------------------------------------------------------------
    // The call, made twice.
    // -----------------------------------------------------------------------------------------

    /** The same, for an order [OrderFixtures.Messages] already holds every message of. */
    private fun acceptBothWays(
        what: String,
        messages: OrderFixtures.Messages,
        requestRumor: AttributedRumor.Bound,
        points: List<FeeTermSighting>,
        crossedStore: JsFlowFixtures.CrossedStore = JsFlowFixtures.CrossedStore(),
        providerPubkey: String = SettlementFixtures.provider(messages.index),
    ): JsAcceptedRequestResult = acceptBothWays(
        what = what,
        requestRumor = requestRumor,
        proposalRumor = messages.proposalRumor,
        acceptanceRumor = messages.acceptanceRumor,
        providerPubkey = providerPubkey,
        points = points,
        crossedStore = crossedStore,
    )

    /**
     * One `type=2` through `AcceptedPaymentRequest.accept` and through [acceptPaymentRequest].
     *
     * The expected side rebuilds §7.6's acceptance from the same two rumors the facade is handed
     * and with the same resolved key, rather than reusing a fixture's — so a control that varies
     * the provider key, or that hands the three rumors of two different orders, varies both sides
     * and the comparison stays about the crossing.
     */
    private fun acceptBothWays(
        what: String,
        requestRumor: AttributedRumor.Bound,
        proposalRumor: AttributedRumor.Bound,
        acceptanceRumor: AttributedRumor.Bound,
        providerPubkey: String,
        points: List<FeeTermSighting>,
        crossedStore: JsFlowFixtures.CrossedStore = JsFlowFixtures.CrossedStore(),
    ): JsAcceptedRequestResult {
        val expected = JsFlowFixtures.acceptedAnswerOf {
            val acceptance = OrderProposal.decode(proposalRumor).accepts(
                OrderStatusMessage.decode(acceptanceRumor),
                providerPubkey,
            ) as? Acceptance.Accepted ?: fail("$what: the expected side needs a checked acceptance")
            AcceptedPaymentRequest.accept(
                request = PaymentRequest.decode(requestRumor, acceptance.terms.split),
                acceptance = acceptance,
                into = PaymentRequestStore.inMemory(),
                clock = clock(),
                feeTermPoints = points,
            )
        }
        val crossed = acceptPaymentRequest(
            request = JsRumor(requestRumor),
            proposalRumor = JsRumor(proposalRumor),
            acceptanceRumor = JsRumor(acceptanceRumor),
            providerPubkey = providerPubkey,
            store = crossedStore.seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = clock()),
            earlierPoints = points.map { JsFlowFixtures.crossed(it) }.toTypedArray(),
        )
        JsFlowFixtures.assertSameAccepted(crossed, expected, what)
        return crossed
    }

    /**
     * This order's `type=2` for [payee], sealed by [author] instead of by the key §8.6 or §8.7
     * names.
     *
     * The message is conformant in every other respect — same order, same payee tag, the same real
     * invoice the fixture bills — so the only thing a refusal can be about is who sealed it.
     */
    private fun requestSealedBy(
        author: String,
        messages: OrderFixtures.Messages,
        payee: Payee,
    ): AttributedRumor.Bound = SettlementFixtures.boundSealedBy(
        author,
        SettlementFixtures.requestTags(
            index = messages.index,
            payment = SettlementFixtures.requestPaymentTag(
                OrderFixtures.invoice(messages.index, payee, messages.stream),
            ),
            payeeTag = SettlementFixtures.payeeTag(payee, messages.index),
            // §8.4 marks the tag REQUIRED on the fee request and OPTIONAL on the provider's.
            fee = if (payee == Payee.FEE) messages.feeTag else null,
        ),
    )

    /** §17 item 6's second value, pinned: the reading the clock held at the moment of acceptance. */
    private fun clock(): NenyaClock = FakeClock(SettlementFixtures.ACCEPTED_AT)

    /** §8.4's two points that exist **before** a `type=2`: the proposal and the acceptance. */
    private fun signedPoints(messages: OrderFixtures.Messages): List<FeeTermSighting> = listOf(
        FeeTermSighting.onProposal(messages.proposal),
        FeeTermSighting.onAcceptance(messages.acceptance),
    )
}
