package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.OrderId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §8.6's revision `1.5` rules about what may be **stored**: who sealed it, that it is the first for
 * its `(order, payee)` pair, that its invoice is not already one of this order's, and that it is
 * one §9.2 checks 4 and 5 would not later reject.
 *
 * ### The two gaps this closes, and what each attack actually looked like
 *
 * **G1c — nothing checked who sealed a `payee=provider` `type=2`.** §7.4's Sender column names its
 * sender and §8.7 turned that into a refusal for the fee side only, so the standard fixture's
 * provider request — sealed by the **buyer** — was accepted, stored, and became the operand of §9.2
 * check 1 for the whole order. Closing it took two rules rather than one, and the second is the
 * reason [OrderProposal.accepts] changed: trusting "whoever sealed the acceptance" as the provider
 * would have left the same hole open, because a buyer could seal a byte-identical `type=3` of its
 * own. So §7.6 checks the acceptance against a key the caller resolved, and §8.6 checks the
 * `type=2` against *that* key.
 *
 * **G1d — a second request replaced the first.** `AcceptedPaymentRequest.accept` said so in its own
 * KDoc. §9.2 check 1 compares a receipt against *the* stored request, so a replacement re-points
 * an order's payment evidence after the fact — including after the buyer has paid the invoice it
 * displaced.
 *
 * ### Nothing here is typed
 *
 * Every invoice is derived from a vendored BOLT-11 example by the composer `Bolt11ComposerTest`
 * proves rebuilds each of them character for character (decision D); every pubkey is a real BIP-340
 * key out of the vendored vectors; every order id is a computed digest. See [SettlementFixtures].
 */
class RequestStorageTest {

    private companion object {

        /** The fixture universe every control here uses, unless it needs a second order. */
        const val ORDER: Int = 0

        /** A second order, for the control that offers a request against another order's acceptance. */
        const val OTHER_ORDER: Int = 1

        /** The floor the queue sets for a property of this kind. */
        const val CORPUS: Int = 10_000
    }

    /** One order's §7.6 acceptance and a fresh store, which is what every control below starts from. */
    private fun accepted(index: Int = ORDER) = SettlementFixtures.accepted(index)

    private fun clock() = FakeClock(SettlementFixtures.ACCEPTED_AT)

    /**
     * A `type=2` for [payee] on the order at [index], sealed by [sealedBy] and naming [invoice].
     *
     * The `fee` tag is carried on a fee request and not on a provider one, which is §8.4's own
     * requirement and its own MUST NOT — a fixture that supplied one to the provider would never
     * exercise the branch that drops it.
     */
    private fun request(
        payee: Payee,
        sealedBy: String,
        invoice: String,
        index: Int = ORDER,
        order: String = SettlementFixtures.orderHex(index),
    ): PaymentRequest = SettlementFixtures.requestSealedBy(
        sealedBy,
        SettlementFixtures.requestTags(
            index = index,
            order = order,
            payment = SettlementFixtures.requestPaymentTag(invoice),
            payeeTag = SettlementFixtures.payeeTag(payee, index),
            fee = if (payee == Payee.FEE) SettlementFixtures.feeTag(index) else null,
        ),
    )

    /** The invoice [payee] is owed on the default split, against the preimage at [stream]. */
    private fun owedInvoice(payee: Payee, stream: Int = 1): String = SettlementFixtures.invoice(
        PaymentFixtures.preimageHex(stream + 1)[stream],
        SettlementFixtures.amountFor(payee, SettlementFixtures.split()),
    )

    /** [request] offered to [into] against [order]'s acceptance, returning whatever it threw. */
    private fun refuse(
        request: PaymentRequest,
        into: PaymentRequestStore,
        order: SettlementFixtures.Accepted = accepted(),
    ): SettlementException = assertFailsWith { SettlementFixtures.accept(request, into, clock(), order) }

    private fun store(
        request: PaymentRequest,
        into: PaymentRequestStore,
        order: SettlementFixtures.Accepted = accepted(),
    ): AcceptedPaymentRequest = SettlementFixtures.accept(request, into, clock(), order)

    /**
     * Nothing at all is held for [order], under **either** of §8.6's two roles.
     *
     * Both roles rather than the one under test, because "the store is empty" is what every
     * refusal below claims and a rule that wrote under the wrong key would satisfy a one-role
     * check. The lookup uses the order id off the acceptance — the same value `accept` keys on —
     * rather than a second parse of the hex.
     */
    private fun assertEmpty(store: PaymentRequestStore, order: SettlementFixtures.Accepted) {
        for (payee in Payee.entries) {
            assertNull(
                store.find(order.acceptance.order, payee),
                "nothing may be stored for this order under $payee",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // G1c, inverted: a `payee=provider` request is the provider's or it is not stored.
    // ---------------------------------------------------------------------------------------

    /**
     * The gap as the test program found it, run end to end: a buyer that seals its own acceptance
     * **and** its own provider invoice gets nothing past either door, and the store stays empty.
     *
     * Two refusals rather than one, and both are needed. The first stops the buyer being recorded
     * as its own counterparty; the second stops a request offered against somebody else's honest
     * acceptance. A library with only the second still lets the buyer reach `accepted`, and one
     * with only the first still lets a stranger issue the provider's bill.
     */
    @JsName("the_whole_g1c_route_leaves_the_store_empty")
    @Test
    fun `the whole G1c route leaves the store empty`() {
        val proposalTags = ProposalFixtures.proposalTags(index = ORDER)
        val proposal = ProposalFixtures.proposal(proposalTags, ORDER)
        val buyerSealed = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, ORDER),
            ORDER,
        )
        val order = accepted()
        val store = PaymentRequestStore.inMemory()

        // Door one, §7.6: the buyer's byte-identical `type=3` is not an acceptance at all.
        val refusedAcceptance = assertFailsWith<ChannelException> {
            proposal.accepts(buyerSealed, SettlementFixtures.provider(ORDER))
        }
        assertEquals(ChannelRejection.ACCEPTANCE_NOT_FROM_PROVIDER, refusedAcceptance.reason)

        // Door two, §8.6: and even handed the provider's real acceptance — which the buyer would
        // have if the provider had answered honestly — the buyer's own invoice is refused.
        val refusedRequest = refuse(
            request(Payee.PROVIDER, SettlementFixtures.buyer(ORDER), owedInvoice(Payee.PROVIDER)),
            store,
            order,
        )
        assertEquals(SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER, refusedRequest.reason)
        assertEquals(SettlementVocabulary.PAYEE, refusedRequest.tag)
        assertEmpty(store, order)
    }

    /**
     * The generic half: a stranger's `payee=provider` invoice, refused the same way and by name.
     *
     * Paired with the request sealed by `acceptance.provider`, which stores — so the refusal is
     * about the key and not about the shape of the message, which is identical in both.
     */
    @JsName("a_provider_request_from_a_stranger_is_refused_and_the_providers_own_is_stored")
    @Test
    fun `a provider request from a stranger is refused, and the provider's own is stored`() {
        val order = accepted()
        val invoice = owedInvoice(Payee.PROVIDER)
        val store = PaymentRequestStore.inMemory()

        val refused = refuse(
            request(Payee.PROVIDER, SettlementFixtures.stranger(ORDER), invoice),
            store,
            order,
        )
        assertEquals(SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER, refused.reason)
        assertEmpty(store, order)

        val stored = store(request(Payee.PROVIDER, order.acceptance.provider, invoice), store, order)

        assertSame(stored, store.find(order.acceptance.order, Payee.PROVIDER))
        assertEquals(invoice, stored.invoice.text)
        assertEquals(
            order.acceptance.provider,
            stored.sealedBy,
            "the record keeps the key it was accepted under, so a later consumer compares it " +
                "rather than re-deriving it",
        )
    }

    /**
     * The `provider` key the store checks against is §7.6's checked one, carried on the acceptance
     * — so the two rules cannot come to mean two different providers.
     *
     * Stated as a control because the alternative reading is the tempting one: comparing the
     * request's seal against *the proposal's author* would look like a sender check, pass every
     * shape-level assertion, and be exactly backwards — the proposal's author is the buyer.
     */
    @JsName("the_key_a_provider_request_is_checked_against_is_never_the_proposals_author")
    @Test
    fun `the key a provider request is checked against is never the proposal's author`() {
        val order = accepted()

        assertEquals(SettlementFixtures.provider(ORDER), order.acceptance.provider)
        assertTrue(
            order.acceptance.provider != order.proposal.buyer,
            "the provider and the buyer must be different keys, or neither refusal above means " +
                "anything",
        )
    }

    // ---------------------------------------------------------------------------------------
    // G1d, inverted: one accepted `type=2` per (order, payee).
    // ---------------------------------------------------------------------------------------

    /**
     * A second request for the same order and payee is refused — from a stranger and from the
     * provider itself — and the invoice under that key is still, byte for byte, the first one.
     *
     * The provider half is the one worth naming: it is a conformant-looking flow (an invoice went
     * unanswered, so send another) and §8.6 refuses it anyway, because check 1's operand cannot be
     * allowed to move. The stranger half is the attack.
     */
    @JsName("a_second_request_for_one_order_and_payee_is_refused_from_either_sender")
    @Test
    fun `a second request for one order and payee is refused, from either sender`() {
        val order = accepted()
        val first = owedInvoice(Payee.PROVIDER, stream = 1)
        val second = owedInvoice(Payee.PROVIDER, stream = 2)
        assertTrue(first != second, "the two invoices must differ, or nothing could be re-pointed")

        val store = PaymentRequestStore.inMemory()
        val kept = store(request(Payee.PROVIDER, order.acceptance.provider, first), store, order)

        // A stranger's second request is refused as a **duplicate**, not as a wrong sender: the
        // uniqueness rule is checked after the sender rule, so this sender is refused by the one
        // that speaks first. The stranger's own sender refusal is the control above.
        val strangerSecond = refuse(
            request(Payee.PROVIDER, SettlementFixtures.stranger(ORDER), second),
            store,
            order,
        )
        assertEquals(SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER, strangerSecond.reason)

        val providerSecond = refuse(
            request(Payee.PROVIDER, order.acceptance.provider, second),
            store,
            order,
        )
        assertEquals(SettlementRejection.REQUEST_ALREADY_STORED, providerSecond.reason)

        assertSame(kept, store.find(order.acceptance.order, Payee.PROVIDER))
        assertEquals(
            first,
            assertNotNull(store.find(order.acceptance.order, Payee.PROVIDER)).invoice.text,
            "§9.2 check 1 compares against *the* stored string, and it is still the first one",
        )
    }

    /**
     * The library's own store keeps the rule too, so a client store and this one cannot come to
     * mean different things by a second `store`.
     *
     * `accept` refuses before it ever calls `store`, so this is the only way to observe the store's
     * own guard — and it is worth observing: a client that persists records itself reads
     * [PaymentRequestStore.store]'s contract and not `accept`'s.
     */
    @JsName("the_in_memory_store_refuses_a_duplicate_key_itself")
    @Test
    fun `the in-memory store refuses a duplicate key itself`() {
        val order = accepted()
        val store = PaymentRequestStore.inMemory()
        val kept = store(
            request(Payee.PROVIDER, order.acceptance.provider, owedInvoice(Payee.PROVIDER)),
            store,
            order,
        )

        val refused = assertFailsWith<SettlementException> { store.store(kept) }

        assertEquals(SettlementRejection.REQUEST_ALREADY_STORED, refused.reason)
        assertSame(kept, store.find(order.acceptance.order, Payee.PROVIDER))
    }

    /**
     * `accept`'s own duplicate refusal, observed **independently** of the store's.
     *
     * The rule is kept in two places on purpose — `accept` asks before it writes, and
     * `PaymentRequestStore.inMemory` refuses a second write — and two guards make each other
     * invisible: delete either one and the other still refuses with the same reason, so every
     * control above stays green. That is a real risk rather than a hypothetical, because the
     * second guard is the one a **client** store is free not to keep: §13 puts the client's own
     * persistence outside what this library defends against, and a client that implements
     * [PaymentRequestStore] with a plain map assignment has exactly the shape below.
     *
     * So this control hands `accept` a store that overwrites cheerfully and asserts that the
     * second request is refused anyway, and that the lenient store was never written to.
     */
    @JsName("accept_refuses_a_duplicate_even_where_the_store_would_have_overwritten")
    @Test
    fun `accept refuses a duplicate even where the store would have overwritten`() {
        val order = accepted()
        val lenient = OverwritingStore()
        val first = owedInvoice(Payee.PROVIDER, stream = 1)
        val second = owedInvoice(Payee.PROVIDER, stream = 2)

        val kept = store(request(Payee.PROVIDER, order.acceptance.provider, first), lenient, order)
        assertEquals(1, lenient.writes, "the first request really was written")

        val refused = refuse(
            request(Payee.PROVIDER, order.acceptance.provider, second),
            lenient,
            order,
        )

        assertEquals(SettlementRejection.REQUEST_ALREADY_STORED, refused.reason)
        assertEquals(
            1,
            lenient.writes,
            "and the second never reached the store at all — `accept` asks `find` before it " +
                "writes, which is what makes the rule hold for a client store that keeps no rule " +
                "of its own",
        )
        assertSame(kept, lenient.find(order.acceptance.order, Payee.PROVIDER))
    }

    /**
     * A client store with no uniqueness rule of its own: `store` overwrites, and counts.
     *
     * The shape §13 says this library does not defend against, written out so that what it
     * *cannot* defeat is observable. Not thread-safe and not persistent, like the one this library
     * ships — neither matters for a single assertion.
     */
    private class OverwritingStore : PaymentRequestStore {

        private val records = mutableMapOf<String, AcceptedPaymentRequest>()

        /** How many times [store] was called, which is the assertion this double exists for. */
        var writes: Int = 0
            private set

        override fun store(accepted: AcceptedPaymentRequest): AcceptedPaymentRequest {
            writes++
            records[key(accepted.order, accepted.payee)] = accepted
            return accepted
        }

        override fun find(order: OrderId, payee: Payee): AcceptedPaymentRequest? =
            records[key(order, payee)]

        private fun key(order: OrderId, payee: Payee): String =
            "${order.toHex()}:${payee.token}"
    }

    // ---------------------------------------------------------------------------------------
    // The combined bill: one invoice offered as two payees'.
    // ---------------------------------------------------------------------------------------

    /**
     * The test program's own cheat, inverted: one bill sent as both the provider's and the fee
     * recipient's is refused at the second request, in **either** order, and both records survive.
     *
     * Nenya stored it twice without comment and caught the trick only when the two payment proofs
     * arrived — which is after the buyer has paid. §8.6's non-custodial rule is what it breaks:
     * whoever is paid holds `price_msat + fee_msat` and owes somebody the difference.
     *
     * ### Why the order is priced at §8.1's maximum rate
     *
     * The cross-payee rule is the **last** of `accept`'s seven, so a fixture has to get past §9.2
     * check 4 twice — once for each payee — before it is reached, and check 4 demands the exact
     * figure that payee is owed. One invoice can satisfy both only where `price_msat == fee_msat`,
     * which §8.3 makes true at exactly 10 000 basis points: `floor(price × 10000 / 10000) = price`.
     * That is a legal term (§8.1 fixes the range at `0..10000` and §8.3's own note forbids refusing
     * an in-range value for being large), and it is the one under which a combined bill is
     * arithmetically indistinguishable from two honest ones — which is precisely when a rule
     * comparing invoice **strings** is the only thing left.
     *
     * At any other rate the two invoices differ in amount and check 4 catches the combined bill
     * first: the right verdict, for a reason that would stop applying the day the two figures
     * happened to coincide.
     */
    @JsName("one_invoice_offered_as_two_payees_is_refused_in_either_order")
    @Test
    fun `one invoice offered as two payees is refused, in either order`() {
        val split = SettlementFixtures.split(basisPoints = FeeTerm.MAX_BASIS_POINTS)
        assertEquals(
            split.price,
            split.fee,
            "at §8.1's maximum rate the two payees are owed the same figure, which is what lets " +
                "one invoice pass check 4 for both and leaves only the cross-payee rule",
        )
        val order = SettlementFixtures.acceptedFor(split)
        val shared = SettlementFixtures.invoice(PaymentFixtures.preimageHex(1).single(), split.price)

        for (first in Payee.entries) {
            val second = if (first == Payee.PROVIDER) Payee.FEE else Payee.PROVIDER
            val store = PaymentRequestStore.inMemory()
            val kept = SettlementFixtures.accept(sameBill(first, shared, split), store, clock(), order)

            val refused = assertFailsWith<SettlementException>("$first first") {
                SettlementFixtures.accept(sameBill(second, shared, split), store, clock(), order)
            }

            assertEquals(
                SettlementRejection.INVOICE_STORED_FOR_OTHER_PAYEE,
                refused.reason,
                "$first first",
            )
            assertEquals(SettlementVocabulary.PAYMENT, refused.tag, "$first first")
            assertSame(kept, store.find(order.acceptance.order, first), "$first first: untouched")
            assertNull(
                store.find(order.acceptance.order, second),
                "$first first: and no second record was written on the way to the refusal",
            )
        }
    }

    /**
     * One side of the combined bill: a `type=2` for [payee] naming [invoice], under the maximum-rate
     * [split] both payees are owed the same figure on.
     *
     * Sealed by the key §8.6 requires for that role, so what refuses the second of the pair is the
     * cross-payee rule and not a sender check that fired earlier.
     */
    private fun sameBill(payee: Payee, invoice: String, split: FeeSplit) =
        SettlementFixtures.requestSealedBy(
            when (payee) {
                Payee.PROVIDER -> SettlementFixtures.provider(ORDER)
                Payee.FEE -> SettlementFixtures.feeRecipient(ORDER)
            },
            SettlementFixtures.requestTags(
                index = ORDER,
                payment = SettlementFixtures.requestPaymentTag(invoice),
                payeeTag = SettlementFixtures.payeeTag(payee, ORDER),
                fee = if (payee == Payee.FEE) {
                    SettlementFixtures.feeTag(ORDER, FeeTerm.MAX_BASIS_POINTS)
                } else {
                    null
                },
            ),
            split,
        )

    /**
     * The pair that keeps the rule from being "two records may not coexist": two **different**
     * invoices for the two payees of one order are both stored, which is §8.6's normal case.
     */
    @JsName("two_different_invoices_for_the_two_payees_of_one_order_are_both_stored")
    @Test
    fun `two different invoices for the two payees of one order are both stored`() {
        val order = accepted()
        val store = PaymentRequestStore.inMemory()
        val providerInvoice = owedInvoice(Payee.PROVIDER, stream = 1)
        val feeInvoice = owedInvoice(Payee.FEE, stream = 2)
        assertTrue(providerInvoice != feeInvoice, "§8.6's two invoices are two invoices")

        store(request(Payee.PROVIDER, order.acceptance.provider, providerInvoice), store, order)
        store(request(Payee.FEE, SettlementFixtures.feeRecipient(ORDER), feeInvoice), store, order)

        assertEquals(
            providerInvoice,
            assertNotNull(store.find(order.acceptance.order, Payee.PROVIDER)).invoice.text,
        )
        assertEquals(
            feeInvoice,
            assertNotNull(store.find(order.acceptance.order, Payee.FEE)).invoice.text,
        )
    }

    // ---------------------------------------------------------------------------------------
    // §8.6's amount rule and §9.2 check 5, applied to the request (decision I as amended).
    // ---------------------------------------------------------------------------------------

    /**
     * §8.6: "The provider's payment request MUST be for exactly `price_msat`. An implementation
     * MUST reject a provider invoice whose amount is not exactly `price_msat`, in either
     * direction." One msat either way, and an invoice naming no amount at all.
     *
     * Each is refused **by name** — a mismatch and a missing amount are different facts about the
     * issuer and send a reader to different places — and each is paired with the exact amount,
     * which stores. Without that pair a check that refused everything would pass.
     */
    @JsName("a_provider_invoice_one_msat_either_way_or_naming_no_amount_is_refused")
    @Test
    fun `a provider invoice one msat either way, or naming no amount, is refused`() {
        val order = accepted()
        val owed = SettlementFixtures.amountFor(Payee.PROVIDER, SettlementFixtures.split())
        val preimage = PaymentFixtures.preimageHex(1).single()

        for (delta in listOf(1L, -1L)) {
            val store = PaymentRequestStore.inMemory()
            val wrong = SettlementFixtures.invoice(preimage, Msat.ofMsat(owed.millisatoshis + delta))
            val refused = refuse(
                request(Payee.PROVIDER, order.acceptance.provider, wrong),
                store,
                order,
            )
            assertEquals(SettlementRejection.INVOICE_AMOUNT_MISMATCH, refused.reason, "delta $delta")
            assertEmpty(store, order)
        }

        val anyAmount = PaymentRequestStore.inMemory()
        val refusedAny = refuse(
            request(Payee.PROVIDER, order.acceptance.provider, SettlementFixtures.invoice(preimage, null)),
            anyAmount,
            order,
        )
        assertEquals(SettlementRejection.INVOICE_AMOUNT_MISSING, refusedAny.reason)
        assertEmpty(anyAmount, order)

        val exact = PaymentRequestStore.inMemory()
        val stored = store(
            request(Payee.PROVIDER, order.acceptance.provider, SettlementFixtures.invoice(preimage, owed)),
            exact,
            order,
        )
        assertEquals(SettlementFixtures.invoice(preimage, owed), stored.invoice.text)
    }

    /** The fee side of the same rule, against §8.3's `fee_msat` rather than against `price_msat`. */
    @JsName("a_fee_invoice_one_msat_either_way_is_refused_against_fee_msat")
    @Test
    fun `a fee invoice one msat either way is refused, against fee_msat`() {
        val order = accepted()
        val owed = SettlementFixtures.amountFor(Payee.FEE, SettlementFixtures.split())
        val preimage = PaymentFixtures.preimageHex(1).single()
        assertTrue(
            owed != SettlementFixtures.amountFor(Payee.PROVIDER, SettlementFixtures.split()),
            "the two payees must be owed different figures, or this control could pass against " +
                "`price_msat`",
        )

        for (delta in listOf(1L, -1L)) {
            val store = PaymentRequestStore.inMemory()
            val wrong = SettlementFixtures.invoice(preimage, Msat.ofMsat(owed.millisatoshis + delta))
            val refused = refuse(
                request(Payee.FEE, SettlementFixtures.feeRecipient(ORDER), wrong),
                store,
                order,
            )
            assertEquals(SettlementRejection.INVOICE_AMOUNT_MISMATCH, refused.reason, "delta $delta")
            assertEmpty(store, order)
        }

        val exact = PaymentRequestStore.inMemory()
        val stored = store(
            request(Payee.FEE, SettlementFixtures.feeRecipient(ORDER), SettlementFixtures.invoice(preimage, owed)),
            exact,
            order,
        )
        assertEquals(owed, SettlementFixtures.amountFor(Payee.FEE, SettlementFixtures.split()))
        assertEquals(Payee.FEE, stored.payee)
    }

    /**
     * §9.2 check 5 at the acceptance reading: an invoice already dead is not stored, and one
     * presented in its final second is.
     *
     * The boundary is the half an implementation gets wrong by writing `>` where `>=` belongs, and
     * it is the half that matters: an invoice paid in its final second is paid.
     */
    @JsName("an_invoice_expired_at_the_acceptance_reading_is_refused_and_the_boundary_is_stored")
    @Test
    fun `an invoice expired at the acceptance reading is refused, and the boundary is stored`() {
        val order = accepted()
        val owed = SettlementFixtures.amountFor(Payee.PROVIDER, SettlementFixtures.split())
        val invoice = SettlementFixtures.invoice(PaymentFixtures.preimageHex(1).single(), owed)
        val deadline = SettlementFixtures.ACCEPTED_AT -
            SettlementFixtures.INVOICE_AGE_SECONDS + SettlementFixtures.INVOICE_EXPIRY_SECONDS

        val expired = PaymentRequestStore.inMemory()
        val refused = assertFailsWith<SettlementException> {
            SettlementFixtures.accept(
                request(Payee.PROVIDER, order.acceptance.provider, invoice),
                expired,
                FakeClock(deadline + 1L),
                order,
            )
        }
        assertEquals(SettlementRejection.INVOICE_EXPIRED, refused.reason)
        assertEmpty(expired, order)

        val live = PaymentRequestStore.inMemory()
        val stored = SettlementFixtures.accept(
            request(Payee.PROVIDER, order.acceptance.provider, invoice),
            live,
            FakeClock(deadline),
            order,
        )
        assertEquals(deadline, stored.acceptedAt, "§17 item 6's second value, recorded")
    }

    // ---------------------------------------------------------------------------------------
    // The order the request is about, and the order of the checks.
    // ---------------------------------------------------------------------------------------

    /**
     * A request naming another order than the acceptance is refused before anything else is
     * derived from that acceptance.
     *
     * Everything `accept` checks comes off the acceptance — the provider's key, §8.3's expected
     * amount, the signed fee term — so judging a request against another order's acceptance would
     * compare it against a provider, a price and a term nobody signed for it, and report each of
     * those checks performed.
     */
    @JsName("a_request_naming_another_order_than_the_acceptance_is_refused")
    @Test
    fun `a request naming another order than the acceptance is refused`() {
        val order = accepted(ORDER)
        val store = PaymentRequestStore.inMemory()

        val refused = refuse(
            request(
                Payee.PROVIDER,
                order.acceptance.provider,
                owedInvoice(Payee.PROVIDER),
                order = SettlementFixtures.orderHex(OTHER_ORDER),
            ),
            store,
            order,
        )

        assertEquals(SettlementRejection.REQUEST_FOR_ANOTHER_ORDER, refused.reason)
        assertEquals(SettlementVocabulary.ORDER, refused.tag)
        assertEmpty(store, order)
        assertEmpty(store, accepted(OTHER_ORDER))
    }

    /**
     * The ordering, as a negative control: a request that is **both** from the wrong sealer and
     * over-priced reports the sealer.
     *
     * A caller told "wrong amount" would go and negotiate a price with a stranger. Who sent the
     * message is the first thing there is to say about it, and the pair below is what makes the
     * assertion mean something: the same over-priced invoice from the provider's own key really
     * does reach the amount refusal.
     */
    @JsName("a_request_that_is_both_wrongly_sealed_and_over_priced_reports_the_sealer")
    @Test
    fun `a request that is both wrongly sealed and over-priced reports the sealer`() {
        val order = accepted()
        val owed = SettlementFixtures.amountFor(Payee.PROVIDER, SettlementFixtures.split())
        val overPriced = SettlementFixtures.invoice(
            PaymentFixtures.preimageHex(1).single(),
            Msat.ofMsat(owed.millisatoshis + 1L),
        )

        val both = refuse(
            request(Payee.PROVIDER, SettlementFixtures.stranger(ORDER), overPriced),
            PaymentRequestStore.inMemory(),
            order,
        )
        assertEquals(SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER, both.reason)

        val amountOnly = refuse(
            request(Payee.PROVIDER, order.acceptance.provider, overPriced),
            PaymentRequestStore.inMemory(),
            order,
        )
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            amountOnly.reason,
            "the invoice really is over-priced, or the refusal above would be the only thing " +
                "wrong with that message",
        )
    }

    // ---------------------------------------------------------------------------------------
    // The non-vacuity floor.
    // ---------------------------------------------------------------------------------------

    /**
     * Over [CORPUS] seeded requests: each is stored exactly once, every second attempt is refused,
     * and no request is visible under another order's or payee's key.
     *
     * A control apiece proves a rule fires; this proves the ordinary path reaches it, for both
     * payee roles, across ten thousand distinct orders. A `store` that had quietly stopped keying
     * on the pair would show up as a record visible under a neighbour's key; one that had stopped
     * refusing duplicates would take the refusal count to zero.
     *
     * The cost is stated rather than hidden: this draws its own seeded run of [CORPUS] fixtures —
     * `SettlementFixtures.pairs` is a generator and not a cache, so the one `SettlementPropertyTest`
     * builds cannot be borrowed — and one checked §7.6 acceptance per order besides, which is two
     * more event ids per fixture. That second half is what the task bought: every record counted
     * here went through a door that checked who sent it.
     */
    @JsName("every_request_in_the_corpus_is_stored_exactly_once_and_under_its_own_key_alone")
    @Test
    fun `every request in the corpus is stored exactly once, and under its own key alone`() {
        val corpus = SettlementFixtures.pairs(CORPUS)
        val acceptances = corpus.map { SettlementFixtures.accepted(it.index) }
        val store = PaymentRequestStore.inMemory()
        val requests = corpus.map {
            SettlementFixtures.requestFrom(it.payee, it.requestTags, index = it.index)
        }

        var stored = 0
        var duplicatesRefused = 0
        for ((index, request) in requests.withIndex()) {
            SettlementFixtures.accept(request, store, clock(), acceptances[index])
            stored++

            val again = assertFailsWith<SettlementException>("fixture $index") {
                SettlementFixtures.accept(request, store, clock(), acceptances[index])
            }
            assertEquals(SettlementRejection.REQUEST_ALREADY_STORED, again.reason, "fixture $index")
            duplicatesRefused++
        }

        assertEquals(CORPUS, stored)
        assertEquals(CORPUS, duplicatesRefused)

        var providers = 0
        var fees = 0
        for ((index, fixture) in corpus.withIndex()) {
            val order = acceptances[index].acceptance.order
            val found = assertNotNull(store.find(order, fixture.payee), "fixture $index")
            assertEquals(fixture.invoice, found.invoice.text, "fixture $index")
            assertNull(
                store.find(order, if (fixture.payee == Payee.PROVIDER) Payee.FEE else Payee.PROVIDER),
                "fixture $index: each order here carries one payee's request, so the other payee's " +
                    "key must find nothing — §8.6's two invoices are two records",
            )
            // The neighbour is the other payee (the generator alternates them), so its own record
            // sits under a key this lookup does not name. A store that keyed on the order alone —
            // or on the payee alone — would hand something back here.
            assertNull(
                store.find(acceptances[(index + 1) % CORPUS].acceptance.order, fixture.payee),
                "fixture $index: nothing is visible under a neighbouring order's id paired with " +
                    "this fixture's payee",
            )
            if (fixture.payee == Payee.PROVIDER) providers++ else fees++
        }

        assertTrue(providers > 0 && fees > 0, "$providers providers and $fees fee recipients")
        assertEquals(CORPUS, providers + fees)
    }

    /** The corpus really does go through the checked door: every stored record keeps its sealer. */
    @JsName("every_stored_record_keeps_the_key_s8_6_required_it_to_arrive_under")
    @Test
    fun `every stored record keeps the key §8_6 required it to arrive under`() {
        val order = accepted()
        val store = PaymentRequestStore.inMemory()

        val provider = store(
            request(Payee.PROVIDER, order.acceptance.provider, owedInvoice(Payee.PROVIDER, 1)),
            store,
            order,
        )
        val fee = store(
            request(Payee.FEE, SettlementFixtures.feeRecipient(ORDER), owedInvoice(Payee.FEE, 2)),
            store,
            order,
        )

        assertEquals(order.acceptance.provider, provider.sealedBy)
        assertEquals(SettlementFixtures.feeRecipient(ORDER), fee.sealedBy)
        for (record in listOf(provider, fee)) {
            val printed = record.toString()
            assertTrue(record.sealedBy !in printed, "§12 item 2: the sealing key is not printed")
            assertTrue(record.invoice.text !in printed, "§12 item 1: nor the invoice")
        }
    }

    /**
     * The `feeTermPoints` default is a **refusal** and not a convenience, which is the half of
     * §8.7 that makes it unskippable.
     *
     * `accept`'s KDoc says so; nothing tested it, and a default that quietly let a fee `type=2`
     * through unchecked would undo the whole second half of this task while every other control
     * here stayed green — the fee request would simply be stored, and §8.4's signed term would
     * never be consulted.
     */
    @JsName("a_fee_request_offered_with_no_s8_4_points_is_refused_rather_than_stored_unchecked")
    @Test
    fun `a fee request offered with no §8_4 points is refused rather than stored unchecked`() {
        val order = accepted()
        val store = PaymentRequestStore.inMemory()
        val conformant = request(Payee.FEE, SettlementFixtures.feeRecipient(ORDER), owedInvoice(Payee.FEE))

        val refused = assertFailsWith<SettlementException> {
            AcceptedPaymentRequest.accept(conformant, order.acceptance, store, clock())
        }

        assertEquals(SettlementRejection.FEE_TERM_POINT_MISSING, refused.reason)
        assertEmpty(store, order)

        // And the pair: the same request with §8.4's two signed points is stored, so what refused
        // above is the missing history and not anything about the message.
        assertEquals(Payee.FEE, store(conformant, store, order).payee)
    }
}
