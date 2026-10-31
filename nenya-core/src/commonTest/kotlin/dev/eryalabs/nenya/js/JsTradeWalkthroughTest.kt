package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeEphemeralSigners
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T67: one whole trade, walked the way `docs/INTEGRATING.md` walks it, through the exported entry
 * points only — and the two cheats a provider or a buyer can try on the way.
 *
 * Buyer, provider and fee recipient are three [FakeCryptoSigner]s, each crossed into the [JsSigner] a
 * page supplies by [JsFlowFixtures.crossed], and every party shares one [JsEnvironment] built from the
 * same fakes with a pinned clock. Every private message is built by its sender, [seal]ed, and
 * [open]ed by its recipient; a sender that needs its own message later opens its `toSelf` copy, as
 * the guide's provider section says. The steps follow the guide's `Calls:` lines in order, and
 * between two steps nothing reaches past a crossed value into a Kotlin type: the only Kotlin values
 * here are the seams and the fixtures — `OrderFixtures.TERMS`' amounts and deadlines, its blob, and
 * the invoices `SettlementFixtures` derives (decision **D**), with preimages from `PaymentFixtures`.
 *
 * The clock is [OrderFixtures.BEFORE_DEADLINES] throughout: before the proposal's `expiration`, so the
 * acceptance builds, and before `deliver_by`, so no deadline row fires. Each invoice is stamped
 * [SettlementFixtures.INVOICE_AGE_SECONDS] before that reading, so §9.2 check 5 is live at acceptance.
 */
class JsTradeWalkthroughTest {

    /** The honest trade: offer to `settled`, every step's answer read before the next is taken. */
    @Test
    @JsName("one_honest_trade_through_the_exported_entry_points_ends_settled")
    fun `one honest trade through the exported entry points ends settled`() {
        val trade = Trade()
        val paid = trade.paid(trade.providerReceipt(Trade.PROVIDER_PREIMAGE))

        // Guide step 9 — the provider's release, gated on its own evidence (T65).
        val release = trade.releaseAfterPayment()
        val decoded = decodeRelease(release).release ?: fail("the buyer must read the release")
        val released = trade.step(
            paid,
            JsOrderEvent(
                type = "deliverable_released",
                orderIdHex = release.orderIdHex,
                release = decoded.release,
                fromParty = "PROVIDER",
            ),
        )
        assertEquals("RELEASED", released.state, "§11.2's `paid → released`")

        // Guide step 10 — §10.4, step 1 on its own and then the whole check inside the order.
        val commitment = released.commitment ?: fail("a released order holds its commitment")
        val served = verifyServedBytes(commitment, encodeLowerHex(OrderFixtures.blob.served))
        assertTrue(served.ok, "§10.4 step 1: the served bytes hash to `x`: ${served.reason}")
        val settled = trade.step(
            released,
            JsOrderEvent(
                type = "delivery_verified",
                commitment = commitment,
                servedBytesHex = encodeLowerHex(OrderFixtures.blob.served),
                plaintextBytesHex = encodeLowerHex(OrderFixtures.blob.plaintext),
            ),
        )
        assertEquals("SETTLED", settled.state, "§11.2's `released → settled`")
        assertTrue(settled.terminal, "`settled` is terminal")
        assertEquals(emptyList(), settled.paymentChecksNotPerformedHere.toList(), "§17: no §9.2 check left undone")
    }

    /**
     * A provider receipt naming the stored invoice — so §9.2 checks 1, 4 and 5 pass — with another
     * fixture's preimage as its proof. Check 3 refuses it on both sides, so the buyer has no evidence
     * to move the order and the provider none to release against.
     */
    @Test
    @JsName("a_receipt_carrying_another_fixture_s_preimage_is_refused_PREIMAGE_MISMATCH")
    fun `a receipt carrying another fixture's preimage is refused PREIMAGE_MISMATCH`() {
        val trade = Trade()
        val receipt = trade.providerReceipt(Trade.OTHER_PREIMAGE)

        val buyerCheck = verifySettlement(receipt.toSelf, trade.buyerStore.seam, trade.terms)
        assertFalse(buyerCheck.ok, "§9.2 check 3: the proof does not hash to the stored invoice's `p`")
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH.name, buyerCheck.reason)
        assertEquals("PaymentRejection", buyerCheck.reasonVocabulary, "check 3 keeps its own name")
        assertNull(buyerCheck.settlement, "a refusal carries no settlement")

        val providerCheck = verifySettlement(receipt.toRecipient, trade.providerStore.seam, trade.terms)
        assertEquals(PaymentRejection.PREIMAGE_MISMATCH.name, providerCheck.reason, "the provider's own check agrees")
        assertNull(providerCheck.settlement, "so the provider has nothing to hand `buildReleaseAfterPayment`")

        // The same order, offered the honest receipt instead, verifies: the refusal is the preimage's.
        val honest = trade.providerReceipt(Trade.PROVIDER_PREIMAGE)
        val honestCheck = verifySettlement(honest.toSelf, trade.buyerStore.seam, trade.terms)
        assertTrue(honestCheck.settlement?.evidenced == true, "the stored invoice's own preimage evidences")
    }

    /**
     * A release built by the raw `buildRelease` with its `size` retyped disputes the `paid` order, where
     * the T65 release over the same commitment, offered to the same order, releases it.
     */
    @Test
    @JsName("a_raw_release_with_size_retyped_disputes_the_paid_order_the_T65_release_releases")
    fun `a raw release with size retyped disputes the paid order the T65 release releases`() {
        val trade = Trade()
        val paid = trade.paid(trade.providerReceipt(Trade.PROVIDER_PREIMAGE))
        val commitment = paid.commitment ?: fail("a paid order holds its commitment")

        val honest = decodeRelease(trade.releaseAfterPayment()).release ?: fail("the T65 release decodes")
        val honestOutcome = stepOrder(
            trade.machine,
            paid,
            JsOrderEvent(type = "deliverable_released", release = honest.release, fromParty = "PROVIDER"),
        )
        assertEquals("RELEASED", honestOutcome.order?.state, "the T65 release releases: ${honestOutcome.reason}")

        val retyped = JsRelease(
            xHex = commitment.xHex,
            oxHex = commitment.oxHex,
            fileType = commitment.mimeType,
            sizeBytes = (commitment.sizeBytes.toLong() + 1).toString(),
        )
        val raw = buildRelease(
            trade.providerEnvelope(),
            trade.orderIdHex,
            retyped,
            ReleaseFixtures.KEY_HEX,
            ReleaseFixtures.NONCE_HEX,
        )
        assertTrue(raw.ok, "`buildRelease` asks for no evidence and checks nothing against the commitment")
        val cheat = decodeRelease(trade.send(raw, trade.provider, trade.buyer).toRecipient).release
            ?: fail("the buyer reads the raw release")
        val cheatOutcome = stepOrder(
            trade.machine,
            paid,
            JsOrderEvent(type = "deliverable_released", release = cheat.release, fromParty = "PROVIDER"),
        )
        assertTrue(cheatOutcome.ok, "§10.3: a divergent release moves the order, to `disputed`")
        assertEquals("DISPUTED", cheatOutcome.order?.state, "§10.3's identity check fails on `size`")
        assertEquals(
            "RELEASE_DOES_NOT_MATCH_COMMITMENT",
            cheatOutcome.order?.disputeGround,
            "and the order says why",
        )
    }

    // -----------------------------------------------------------------------------------------

    /** One message as both parties hold it: the recipient's opened copy and the sender's `toSelf`. */
    private class Delivered(val toRecipient: JsRumor, val toSelf: JsRumor) {
        val orderIdHex: String? get() = toRecipient.orderIdHex
    }

    /**
     * One trade, walked to `awaiting_payment` on construction — guide steps 1, 2 and 4 to 7 — with
     * the rest left to each test.
     */
    private class Trade {

        companion object {
            /** Small streams are free for named parties (`FakeKeyRanges`). */
            const val BUYER_STREAM: Long = 67L
            const val PROVIDER_STREAM: Long = 68L
            const val FEE_RECIPIENT_STREAM: Long = 69L

            const val NOW: Long = OrderFixtures.BEFORE_DEADLINES

            /** Three generated preimages: the provider's payment, the fee's, and one no invoice here carries. */
            private val PREIMAGES: List<String> = PaymentFixtures.preimageHex(3)
            val PROVIDER_PREIMAGE: String = PREIMAGES[0]
            val FEE_PREIMAGE: String = PREIMAGES[1]
            val OTHER_PREIMAGE: String = PREIMAGES[2]
        }

        val buyer = Party(FakeCryptoSigner(FakeKey(BUYER_STREAM)))
        val provider = Party(FakeCryptoSigner(FakeKey(PROVIDER_STREAM)))
        val feeRecipient = Party(FakeCryptoSigner(FakeKey(FEE_RECIPIENT_STREAM)))

        val environment: JsEnvironment = JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(NOW),
            randomness = RecordingRandomness(),
            secp = FakeSecp256k1Ops(),
            ephemeral = FakeEphemeralSigners(),
        )
        val machine = JsOrderMachine(environment)

        val terms = JsOrderTerms(
            priceMsat = OrderFixtures.TERMS.split.price.millisatoshis.toString(),
            feeBasisPoints = OrderFixtures.FEE_BASIS_POINTS,
            expirationSeconds = OrderFixtures.EXPIRATION.toString(),
            deliverBySeconds = OrderFixtures.DELIVER_BY.toString(),
        )
        val orderIdHex: String = SettlementFixtures.orderHex(OrderFixtures.ORDER_INDEX)

        val providerInvoice: String = invoice(PROVIDER_PREIMAGE, OrderFixtures.TERMS.split.price)
        val feeInvoice: String = invoice(FEE_PREIMAGE, OrderFixtures.TERMS.split.fee)

        val buyerStore = JsFlowFixtures.CrossedStore()
        val providerStore = JsFlowFixtures.CrossedStore()

        // Guide step 1 — the provider posts an offer.
        private val offer: JsBuildResult = buildListingOffer(
            JsAuthoredListing(
                authorPubkey = provider.key,
                createdAtSeconds = (NOW - 86_400L).toString(),
                dValue = "t67-walkthrough-offer",
                title = "A 30-second animation",
                priceMsat = terms.priceMsat,
                alt = "An offer: a 30-second animation",
            ),
        ).also { assertTrue(it.ok, "the offer must build: ${it.reason} — ${it.detail}") }

        // Guide step 2 — the buyer reads the board.
        val listing: JsListing = run {
            val json = offer.event?.unsignedJson()?.value ?: fail("a built offer serialises")
            val read = decodeListing(json)
            val listing = read.listing ?: fail("the buyer must decode the offer: ${read.reason}")
            assertTrue(matchesBoard(listing, JsBoardQuery(kinds = arrayOf(NenyaKind.OFFER))), "§5.5's re-filter")
            assertEquals("ACTIVE", listingActivity(listing, environment), "§5.6: a listing with no `expiration`")
            assertTrue(checkDeadlines(listing, null, environment).permitted, "§5.6 permits acting on it")
            listing
        }

        // Guide step 4 — the buyer proposes and opens its order.
        val proposal: Delivered = send(
            buildProposalAgainst(
                listing,
                environment,
                buyerEnvelope(),
                orderIdHex,
                terms,
                feeRecipient = feeRecipient.key,
            ),
            buyer,
            provider,
        )
        val proposed: JsOrder = (openOrder(machine, orderIdHex, terms).order ?: fail("the order must open"))
            .also { assertEquals("PROPOSED", it.state) }

        // Guide step 5 — the provider reads and accepts; the buyer checks and steps.
        val acceptance: Delivered = run {
            val read = decodeProposal(proposal.toRecipient).proposal ?: fail("the provider must read the proposal")
            assertEquals(listing.coordinate, read.itemCoordinate, "§7.5's `item` is the offer")
            assertEquals(orderIdHex, read.orderIdHex)
            assertEquals(terms.priceMsat, read.priceMsat)
            assertEquals(feeRecipient.key, read.feeRecipient)
            send(buildAgreementAgainst(listing, environment, providerEnvelope(), proposal.toRecipient), provider, buyer)
        }
        val accepted: JsOrder = run {
            val check = checkAcceptance(proposal.toSelf, acceptance.toRecipient, provider.key)
            assertEquals("accepted", check.kind, "§7.6: all four terms byte-identical")
            step(
                proposed,
                JsOrderEvent(
                    type = "acceptance_received",
                    proposalRumor = proposal.toSelf,
                    acceptanceRumor = acceptance.toRecipient,
                    providerPubkey = provider.key,
                ),
            ).also { assertEquals("ACCEPTED", it.state) }
        }

        // Guide step 6 — the provider commits; the buyer reads and steps.
        val commitment: Delivered = send(
            buildCommitment(
                providerEnvelope(),
                orderIdHex,
                JsFlowFixtures.crossed(OrderFixtures.blob.commitment),
                DeliveryMessageFixtures.url(OrderFixtures.ORDER_INDEX),
            ),
            provider,
            buyer,
        )
        val committed: JsOrder = run {
            val read = decodeCommitment(commitment.toRecipient).commitment ?: fail("the buyer must read the commitment")
            step(
                accepted,
                JsOrderEvent(type = "delivery_committed", commitment = read.commitment, fromParty = "PROVIDER"),
            ).also { assertEquals("COMMITTED", it.state) }
        }

        // Guide step 7 — both payees request payment; the buyer accepts each into its store, and the
        // provider its own into its store (provider step 4).
        val providerRequest: Delivered = send(
            buildPaymentRequest(providerEnvelope(), orderIdHex, Payee.PROVIDER.token, providerInvoice),
            provider,
            buyer,
        )
        val feeRequest: Delivered = send(
            buildPaymentRequest(
                envelope(feeRecipient, buyer),
                orderIdHex,
                Payee.FEE.token,
                feeInvoice,
                feeRecipient = feeRecipient.key,
                terms = terms,
            ),
            feeRecipient,
            buyer,
        )
        val awaitingPayment: JsOrder = run {
            val signed = arrayOf(
                sighting("ORDER_PROPOSAL", proposal.toSelf),
                sighting("ACCEPTANCE", acceptance.toRecipient),
            )
            val records = listOf(providerRequest, feeRequest).map { request ->
                val answer = acceptPaymentRequest(
                    request.toRecipient,
                    proposal.toSelf,
                    acceptance.toRecipient,
                    provider.key,
                    buyerStore.seam,
                    environment,
                    signed,
                )
                answer.request ?: fail("the buyer must accept the request: ${answer.reason} — ${answer.detail}")
            }
            val own = acceptPaymentRequest(
                providerRequest.toSelf,
                proposal.toRecipient,
                acceptance.toSelf,
                provider.key,
                providerStore.seam,
                environment,
            )
            assertTrue(own.ok, "the provider keeps its own request: ${own.reason} — ${own.detail}")
            step(
                committed,
                JsOrderEvent(type = "payment_requests_received", requests = records.toTypedArray()),
            ).also { assertEquals("AWAITING_PAYMENT", it.state) }
        }

        /** Guide step 8's provider receipt, its proof [preimageHex] and its reference the stored invoice. */
        fun providerReceipt(preimageHex: String): Delivered = send(
            buildReceipt(buyerEnvelope(), orderIdHex, Payee.PROVIDER.token, providerInvoice, preimageHex),
            buyer,
            provider,
        )

        /**
         * Guide step 8, the rest of it: the fee receipt, both checked by the buyer against its own store,
         * and the order stepped to `paid`. The provider checks [receipt] against its own store too
         * (provider step 5), and keeps the settlement for [releaseAfterPayment].
         */
        fun paid(receipt: Delivered): JsOrder {
            val feeReceipt = send(
                buildReceipt(
                    envelope(buyer, feeRecipient),
                    orderIdHex,
                    Payee.FEE.token,
                    feeInvoice,
                    FEE_PREIMAGE,
                    feeRecipient = feeRecipient.key,
                    terms = terms,
                ),
                buyer,
                feeRecipient,
            )
            val providerPayment = evidenced(verifySettlement(receipt.toSelf, buyerStore.seam, terms), "provider")
            val feePayment = evidenced(
                verifyFeeReceipt(
                    feeReceipt.toSelf,
                    buyerStore.seam,
                    awaitingPayment,
                    arrayOf(
                        sighting("ORDER_PROPOSAL", proposal.toSelf),
                        sighting("ACCEPTANCE", acceptance.toRecipient),
                        sighting("FEE_PAYMENT_REQUEST", feeRequest.toRecipient),
                    ),
                ),
                "fee",
            )
            providerSettlement = evidenced(
                verifySettlement(receipt.toRecipient, providerStore.seam, terms),
                "the provider's own",
            )
            return step(
                awaitingPayment,
                JsOrderEvent(type = "receipts_verified", settlements = arrayOf(providerPayment, feePayment)),
            ).also { assertEquals("PAID", it.state) }
        }

        private var providerSettlement: JsSettlement? = null

        /** Guide step 9's T65 release, from the provider's own commitment copy, delivered to the buyer. */
        fun releaseAfterPayment(): JsRumor = send(
            buildReleaseAfterPayment(
                providerEnvelope(),
                commitment.toSelf,
                providerSettlement ?: fail("the provider has verified no payment"),
                ReleaseFixtures.KEY_HEX,
                ReleaseFixtures.NONCE_HEX,
            ),
            provider,
            buyer,
        ).toRecipient

        // -------------------------------------------------------------------------------------

        /** [built] sealed by [from] to [to], opened by [to] and — its `toSelf` copy — by [from]. */
        fun send(built: JsBuildResult, from: Party, to: Party): Delivered {
            val event = built.event ?: fail("the message must build: ${built.reason} — ${built.detail}")
            val sealed = seal(event, to.key, from.signer, environment)
            assertTrue(sealed.ok, "the message must seal: ${sealed.reason}")
            val toRecipient = sealed.toRecipient ?: fail("a sealed message has a recipient copy")
            val toSelf = sealed.toSelf ?: fail("a sealed message has a self copy")
            return Delivered(opened(toRecipient, to, from), opened(toSelf, from, from))
        }

        private fun opened(wrap: JsOutgoingWrap, reader: Party, author: Party): JsRumor {
            val answer = open(wrap.json, reader.signer, environment)
            val rumor = answer.rumor ?: fail("the addressee must open its copy: ${answer.reason}")
            assertEquals(author.key, rumor.author, "§7.2: the rumor is the sealing key's")
            assertEquals("SIGNATURE_VERIFIED", rumor.attribution, "§7.2: the seal's signature verified")
            return rumor
        }

        /** [order] stepped by [event], which must advance it. */
        fun step(order: JsOrder, event: JsOrderEvent): JsOrder {
            val outcome = stepOrder(machine, order, event)
            assertTrue(outcome.ok, "${event.type} must advance a ${order.state} order: ${outcome.reason} — ${outcome.detail}")
            return outcome.order ?: fail("an advanced outcome carries its order")
        }

        fun buyerEnvelope(): JsRumorEnvelope = envelope(buyer, provider)

        fun providerEnvelope(): JsRumorEnvelope = envelope(provider, buyer)

        private fun envelope(author: Party, to: Party): JsRumorEnvelope =
            JsRumorEnvelope(author.key, NOW.toString(), arrayOf(arrayOf(to.key)))

        /** §8.4's point [point], read off [rumor]'s own `fee` row as the page would read it. */
        private fun sighting(point: String, rumor: JsRumor): JsFeeTermSighting =
            JsFeeTermSighting(point, rumor.event.tags.firstOrNull { it[0] == "fee" })

        private fun evidenced(answer: JsSettlementResult, what: String): JsSettlement {
            val settlement = answer.settlement ?: fail("the $what receipt must verify: ${answer.reason} — ${answer.detail}")
            assertTrue(settlement.evidenced, "§9.2's evidence exists for the $what payment")
            return settlement
        }

        private fun invoice(preimageHex: String, amount: Msat): String = SettlementFixtures.invoice(
            preimageHex,
            amount,
            timestamp = NOW - SettlementFixtures.INVOICE_AGE_SECONDS,
        )
    }

    /** One party: the key it publishes, and the signer a page would supply for it. */
    private class Party(signer: FakeCryptoSigner) {
        val key: String = signer.key.hex
        val signer: JsSigner = JsFlowFixtures.crossed(signer)
    }
}
