package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §9.2 check 1: a comparator that answered `matched` unconditionally and
 * one that answered `not matched` unconditionally must **both** fail.
 *
 * The first is what the corpus below proves cannot happen — every one of ten thousand generated
 * receipts settles against its own stored request — and the second is what the rotated store proves:
 * every one of the same ten thousand is refused when the record under its own `(order, payee)` key
 * holds a different invoice.
 *
 * ### The exhaustive half, and its stated bound
 *
 * "Rejected against every other" is checked two ways, because the literal reading is ten thousand
 * squared. Over the whole corpus, every receipt is checked against a rotated neighbour, and every
 * generated invoice is asserted **distinct** — so no two fixtures could coincidentally match.
 * Within a [EXHAUSTIVE]-fixture sub-corpus, every ordered pair is checked, so "matched exactly when
 * the indices agree" is an observed fact over a real all-pairs sweep rather than an inference from
 * distinctness. The sub-corpus bound is stated here rather than left implicit: it is a cost
 * decision, and the whole corpus is still covered by the rotation.
 */
class SettlementPropertyTest {

    private companion object {

        /** Ten thousand, which is the floor the queue sets for a property of this kind. */
        const val CORPUS: Int = 10_000

        /** The sub-corpus every **ordered pair** of which is checked. See this class's note. */
        const val EXHAUSTIVE: Int = 60

        /** One seeded run, shared by every test here so the corpus is built once. */
        val corpus: List<SettlementFixtures.Fixture> by lazy { SettlementFixtures.pairs(CORPUS) }

        /** Every request decoded through §8.6's codec. */
        val requests: List<PaymentRequest> by lazy {
            corpus.map { SettlementFixtures.request(it.requestTags) }
        }

        /** Every receipt decoded through §9.2's codec. */
        val receipts: List<PaymentReceipt> by lazy {
            corpus.map { SettlementFixtures.receipt(it.receiptTags) }
        }

        /** Every request accepted at a pinned clock reading and stored under its own key. */
        val store: PaymentRequestStore by lazy {
            val out = PaymentRequestStore.inMemory()
            for (request in requests) {
                AcceptedPaymentRequest.accept(request, out, FakeClock(SettlementFixtures.ACCEPTED_AT))
            }
            out
        }

        /** The role a fixture is *not* for, for the cross-key assertions. */
        fun otherRole(payee: Payee): Payee =
            if (payee == Payee.PROVIDER) Payee.FEE else Payee.PROVIDER
    }

    @JsName("the_whole_corpus_decodes_stores_and_covers_both_payee_roles")
    @Test
    fun `the whole corpus decodes, stores and covers both payee roles`() {
        assertEquals(CORPUS, corpus.size)
        assertEquals(CORPUS, requests.size)
        assertEquals(CORPUS, receipts.size)

        var providers = 0
        var fees = 0
        for ((index, request) in requests.withIndex()) {
            val fixture = corpus[index]
            assertEquals(fixture.orderHex, request.order.toHex())
            assertEquals(fixture.payee, request.payee)
            // Verbatim: §9.2 check 1 forbids normalisation, re-encoding and a bech32 round-trip,
            // and a codec that quietly canonicalised would be invisible to every other assertion.
            assertEquals(fixture.invoice, request.invoice.text)
            assertEquals(fixture.payee, receipts[index].payee)
            assertEquals(PaymentMedium.LIGHTNING, receipts[index].medium)
            if (request.payee == Payee.PROVIDER) providers++ else fees++
        }

        assertTrue(providers > 0 && fees > 0, "the corpus covers $providers providers and $fees fees")
        assertEquals(
            CORPUS,
            corpus.map { it.invoice }.toSet().size,
            "every generated invoice must be distinct, or a cross-match below could be a collision " +
                "rather than a comparison",
        )
        assertEquals(
            CORPUS,
            corpus.map { it.preimageHex }.toSet().size,
            "and every preimage, for the same reason on §9.2 check 3's side",
        )
    }

    @JsName("every_stored_request_is_under_its_own_order_and_payee_and_no_other")
    @Test
    fun `every stored request is under its own order and payee, and no other`() {
        var hits = 0
        var misses = 0
        for (fixture in corpus) {
            val request = requests[fixture.index]
            val found = assertNotNull(
                store.find(request.order, fixture.payee),
                "the record must be under the pair §9.2 check 1 names",
            )
            assertEquals(fixture.invoice, found.invoice.text)
            assertEquals(SettlementFixtures.ACCEPTED_AT, found.acceptedAt, "§17 item 6's second value")
            hits++
            if (store.find(request.order, otherRole(fixture.payee)) == null) misses++
        }

        assertEquals(CORPUS, hits, "the stored-hit path must be exercised by every fixture")
        assertEquals(
            CORPUS,
            misses,
            "and the stored-miss path too: each order carries one payee's request, so the other " +
                "payee's key must find nothing — §8.6's two invoices are two records",
        )
    }

    @JsName("every_receipt_settles_against_its_own_stored_request")
    @Test
    fun `every receipt settles against its own stored request`() {
        for (fixture in corpus) {
            val settlement = Settlement.verify(receipts[fixture.index], fixture.paymentHash, store)

            val evidenced = assertIs<Settlement.Evidenced>(settlement, "fixture ${fixture.index}")
            assertEquals(fixture.payee, evidenced.payee)
            assertEquals(
                Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
                evidenced.checksPerformed,
                "check 1 plus T3's two, on every fixture",
            )
        }
    }

    @JsName("every_receipt_is_refused_when_its_own_key_holds_a_neighbours_invoice")
    @Test
    fun `every receipt is refused when its own key holds a neighbour's invoice`() {
        // The rotated store holds, under each fixture's own (order, payee) key, the **next**
        // fixture's invoice. So every lookup succeeds and every comparison must fail: a comparator
        // that answered `matched` unconditionally dies here, and one that answered `no stored
        // request` would fail the test above instead.
        val rotated = PaymentRequestStore.inMemory()
        for (fixture in corpus) {
            val neighbour = corpus[(fixture.index + 1) % CORPUS]
            val tags = SettlementFixtures.requestTags(
                index = fixture.index,
                order = fixture.orderHex,
                payment = SettlementFixtures.requestPaymentTag(neighbour.invoice),
                payeeTag = SettlementFixtures.payeeTag(fixture.payee, fixture.index),
            )
            AcceptedPaymentRequest.accept(
                SettlementFixtures.request(tags),
                rotated,
                FakeClock(SettlementFixtures.ACCEPTED_AT),
            )
        }

        for (fixture in corpus) {
            val refused = assertFailsWith<SettlementException>("fixture ${fixture.index}") {
                Settlement.verify(receipts[fixture.index], fixture.paymentHash, rotated)
            }
            assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, refused.reason)
        }
    }

    @JsName("within_a_sub_corpus_a_receipt_matches_exactly_the_request_with_its_own_index")
    @Test
    fun `within a sub-corpus, a receipt matches exactly the request with its own index`() {
        // The all-pairs half: EXHAUSTIVE × EXHAUSTIVE ordered pairs, each stored under the
        // receipt's own key so the lookup always succeeds and only the bytes decide.
        var matched = 0
        var refused = 0
        for (receiptIndex in 0 until EXHAUSTIVE) {
            val fixture = corpus[receiptIndex]
            for (requestIndex in 0 until EXHAUSTIVE) {
                val paired = PaymentRequestStore.inMemory()
                val tags = SettlementFixtures.requestTags(
                    index = fixture.index,
                    order = fixture.orderHex,
                    payment = SettlementFixtures.requestPaymentTag(corpus[requestIndex].invoice),
                    payeeTag = SettlementFixtures.payeeTag(fixture.payee, fixture.index),
                )
                AcceptedPaymentRequest.accept(
                    SettlementFixtures.request(tags),
                    paired,
                    FakeClock(SettlementFixtures.ACCEPTED_AT),
                )
                if (receiptIndex == requestIndex) {
                    assertIs<Settlement.Evidenced>(
                        Settlement.verify(receipts[receiptIndex], fixture.paymentHash, paired),
                    )
                    matched++
                } else {
                    val answer = assertFailsWith<SettlementException>("$receiptIndex vs $requestIndex") {
                        Settlement.verify(receipts[receiptIndex], fixture.paymentHash, paired)
                    }
                    assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, answer.reason)
                    refused++
                }
            }
        }

        assertEquals(EXHAUSTIVE, matched, "one match per receipt, and exactly one")
        assertEquals(EXHAUSTIVE * (EXHAUSTIVE - 1), refused, "and every other pair refused")
    }

    @JsName("an_empty_store_refuses_the_whole_corpus_as_no_stored_request")
    @Test
    fun `an empty store refuses the whole corpus as no stored request`() {
        val empty = PaymentRequestStore.inMemory()
        var count = 0

        for (fixture in corpus) {
            val refused = assertFailsWith<SettlementException>("fixture ${fixture.index}") {
                Settlement.verify(receipts[fixture.index], fixture.paymentHash, empty)
            }
            assertEquals(SettlementRejection.NO_STORED_REQUEST, refused.reason)
            count++
        }

        assertEquals(CORPUS, count)
        assertNull(
            empty.find(requests.first().order, requests.first().payee),
            "and the store really was empty throughout, so the refusal is check 1's and not a " +
                "side effect of verification",
        )
    }
}
