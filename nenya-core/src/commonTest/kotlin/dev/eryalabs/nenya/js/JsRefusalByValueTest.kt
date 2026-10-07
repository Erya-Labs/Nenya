package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.ListingStatusCodec
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Decision **P**'s "refusals are returned, never thrown", made executable over every entry point.
 *
 * ### Why a whole file, and why it is the one that found the bugs
 *
 * `jsBuild`'s catch list is a list of named exception types, deliberately — a translation layer that
 * caught `Throwable` would report a defect in this library as a conformance refusal. The cost of that
 * choice is that a **missing** arm is invisible until the input which reaches it arrives, and the
 * reasoning that produced the list is exactly the kind that reads as complete and is not.
 *
 * Two arms were missing when this file was first run, and both were on the path of an ordinary caller:
 *
 * - **`ListingException`.** §5.1's `status` vocabulary is closed, so `ListingStatusCodec.read` refuses
 *   one of §5.2's request tokens on an offer by *throwing*, during the translation and before
 *   `ListingWriter` is reached. A page passing `statusToken: "fulfilled"` to `buildListingOffer` got an
 *   exception across the boundary instead of a `JsBuildResult`.
 * - **`OrderStateException`.** §7.5 requires `expiration` to fall strictly before `deliver_by`, and
 *   `OrderTerms`'s own `init` enforces it. A page proposing inverted deadlines got the same.
 *
 * Both are now caught and returned. This file is what keeps the list honest: every case below spoils
 * exactly one field of an otherwise legal crossing, and a `JsBuildResult` has to come back.
 *
 * ### What the breadth assertion is for
 *
 * "No entry point throws" is satisfied by a sweep that never reaches a refusal at all, so the reasons
 * reached are collected and their count is pinned. A sweep that stopped refusing would go red rather
 * than quietly prove nothing.
 */
class JsRefusalByValueTest {

    private companion object {

        /** Decimal fields, spoiled. Each is a form [JsDecimal] does not write and must not read. */
        val HOSTILE_DECIMALS: List<String> = listOf("", "12x", "1.5", "007", "-1", " 1", "1e9")

        /** Hex fields, spoiled: wrong length, wrong case, wrong alphabet, empty. */
        val HOSTILE_HEX: List<String> = listOf("", "abc", "00", "NOTHEX", "ZZ")

        /** A legal §8.6 payment request, for the settlement cases to spoil one field of. */
        val REQUEST: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.PAYMENT_REQUEST }

        /** A legal §9.2 receipt, likewise. */
        val RECEIPT: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.RECEIPT }

        /** A legal §10.1 commitment, likewise. */
        val COMMITMENT: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.COMMITMENT }

        /** A legal §10.3 release, likewise. */
        val RELEASE: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.RELEASE }

        /** A legal §7.5 proposal, likewise. */
        val PROPOSAL: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        /** A legal §11.1 status update, likewise. */
        val UPDATE: RumorWriterFixtures.Case = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.ORDER_UPDATE }
    }

    /** Every reason any case in this file reached, so the breadth of the sweep is a measured number. */
    private val reasons = mutableSetOf<String>()

    /** Every vocabulary any case reached, so an arm wired to the wrong one is visible. */
    private val vocabularies = mutableSetOf<String>()

    // -----------------------------------------------------------------------------------------
    // The eleven builders.
    // -----------------------------------------------------------------------------------------

    /**
     * Every field of a listing a page can spoil comes back as a value.
     *
     * One field at a time, from a legal base, so each case's refusal is about the field it names. The
     * `status` cases are the two §5.1 rules: a token from §5.2's request vocabulary, which the
     * translation refuses by throwing, and a token neither names, which the writer refuses.
     */
    @Test
    @JsName("every_spoiled_listing_field_comes_back_as_a_value")
    fun `every spoiled listing field comes back as a value`() {
        val legal = JsBoundaryFixtures.crossed(ListingWriterFixtures.minimal(NenyaKind.OFFER))
        fun spoiled(
            createdAtSeconds: String = legal.createdAtSeconds,
            priceMsat: String = legal.priceMsat,
            authorPubkey: String = legal.authorPubkey,
            statusToken: String? = null,
            publishedAtSeconds: String? = null,
            expirationSeconds: String? = null,
            deliverBySeconds: String? = null,
            feeBasisPoints: Int? = null,
            feeRecipient: String? = null,
            images: Array<Array<String>> = emptyArray(),
            pubkeyRefs: Array<Array<String>> = emptyArray(),
        ): JsAuthoredListing = JsAuthoredListing(
            authorPubkey = authorPubkey,
            createdAtSeconds = createdAtSeconds,
            dValue = legal.dValue,
            title = legal.title,
            priceMsat = priceMsat,
            statusToken = statusToken,
            publishedAtSeconds = publishedAtSeconds,
            expirationSeconds = expirationSeconds,
            deliverBySeconds = deliverBySeconds,
            feeBasisPoints = feeBasisPoints,
            feeRecipient = feeRecipient,
            images = images,
            pubkeyRefs = pubkeyRefs,
            alt = legal.alt,
        )

        // Every decimal field, every hostile form.
        for (bad in HOSTILE_DECIMALS) {
            record("createdAtSeconds=`$bad`", buildListingOffer(spoiled(createdAtSeconds = bad)))
            record("priceMsat=`$bad`", buildListingOffer(spoiled(priceMsat = bad)))
            record("publishedAtSeconds=`$bad`", buildListingOffer(spoiled(publishedAtSeconds = bad)))
            record("expirationSeconds=`$bad`", buildListingOffer(spoiled(expirationSeconds = bad)))
            record("deliverBySeconds=`$bad`", buildListingOffer(spoiled(deliverBySeconds = bad)))
        }
        // Every hex field.
        for (bad in HOSTILE_HEX) {
            record("authorPubkey=`$bad`", buildListingOffer(spoiled(authorPubkey = bad)))
            record(
                "feeRecipient=`$bad`",
                buildListingOffer(spoiled(feeBasisPoints = 250, feeRecipient = bad)),
            )
        }
        // §8.1's range, both sides of it, and §4.4's cap.
        for (bps in listOf(-1, FeeTerm.MAX_BASIS_POINTS + 1)) {
            record("feeBasisPoints=$bps", buildListingOffer(spoiled(feeBasisPoints = bps)))
        }
        record(
            "a price above §4.4's supply cap",
            buildListingOffer(spoiled(priceMsat = JsDecimal.of(Msat.SUPPLY_CAP_MSAT + 1L))),
        )
        // Rows of an arity §5.3 gives no form for, in both directions.
        record("an empty image row", buildListingOffer(spoiled(images = arrayOf(emptyArray()))))
        record(
            "a three-element image row",
            buildListingOffer(spoiled(images = arrayOf(arrayOf("https://x.invalid/a.png", "1x1", "no")))),
        )
        record("an empty p row", buildListingOffer(spoiled(pubkeyRefs = arrayOf(emptyArray()))))
        // §5.1's two `status` refusals — the pair this file was written for.
        val requestToken = (
            ListingStatusCodec.vocabulary(NenyaKind.REQUEST) -
                ListingStatusCodec.vocabulary(NenyaKind.OFFER)
            ).firstNotNullOfOrNull { it.token }
            ?: fail("§5.2's request vocabulary no longer holds a status §5.1's offer vocabulary lacks")
        record(
            "statusToken=`$requestToken` on an offer",
            buildListingOffer(spoiled(statusToken = requestToken)),
        )
        record("an invented statusToken", buildListingOffer(spoiled(statusToken = "part-exchanged")))

        assertTrue(
            "STATUS_OUTSIDE_VOCABULARY" in reasons,
            "§5.1's closed vocabulary refuses by throwing from the translation, and the boundary must " +
                "return it. That arm was missing from `jsBuild` until this test existed; reasons " +
                "reached were $reasons",
        )
        // The three other listing entry points take the same object, so a missing arm in one is a
        // missing arm in all three — but the routing is per-kind and each must be exercised.
        for (bad in HOSTILE_DECIMALS) {
            record("request priceMsat=`$bad`", buildListingRequest(spoiled(priceMsat = bad)))
            record("draft priceMsat=`$bad`", buildListingDraft(spoiled(priceMsat = bad)))
        }
    }

    /** Every field of a §7.4 envelope a page can spoil comes back as a value, through `buildChat`. */
    @Test
    @JsName("every_spoiled_envelope_field_comes_back_as_a_value")
    fun `every spoiled envelope field comes back as a value`() {
        val legal = JsBoundaryFixtures.crossed(PROPOSAL.envelope)
        fun spoiled(
            createdAtSeconds: String = legal.createdAtSeconds,
            authorPubkey: String = legal.authorPubkey,
            counterparties: Array<Array<String>> = legal.counterparties,
            nenyaVersion: Int = legal.nenyaVersion,
            extraTags: Array<Array<String>> = emptyArray(),
        ): JsRumorEnvelope = JsRumorEnvelope(
            authorPubkey = authorPubkey,
            createdAtSeconds = createdAtSeconds,
            counterparties = counterparties,
            content = legal.content,
            nenyaVersion = nenyaVersion,
            extraTags = extraTags,
        )

        for (bad in HOSTILE_DECIMALS) {
            record("chat createdAtSeconds=`$bad`", buildChat(spoiled(createdAtSeconds = bad)))
        }
        for (bad in HOSTILE_HEX) {
            record("chat authorPubkey=`$bad`", buildChat(spoiled(authorPubkey = bad)))
            record("chat orderIdHex=`$bad`", buildChat(spoiled(), bad))
            record(
                "chat counterparty=`$bad`",
                buildChat(spoiled(counterparties = arrayOf(arrayOf(bad)))),
            )
        }
        record("an empty p row", buildChat(spoiled(counterparties = arrayOf(emptyArray()))))
        record(
            "a three-element p row",
            buildChat(spoiled(counterparties = arrayOf(arrayOf(legal.authorPubkey, "wss://r.invalid", "x")))),
        )
        for (version in listOf(-1, 0, Int.MAX_VALUE)) {
            record("chat nenyaVersion=$version", buildChat(spoiled(nenyaVersion = version)))
        }
        record("an empty extra tag row", buildChat(spoiled(extraTags = arrayOf(emptyArray()))))
    }

    /**
     * §7.5's inverted deadlines, and every other field of the terms object — the second arm this file
     * found missing.
     *
     * `OrderTerms`'s own `init` refuses `expiration >= deliver_by` by throwing, so a page proposing one
     * got an `OrderStateException` across the boundary rather than a refusal it could read.
     */
    @Test
    @JsName("spoiled_order_terms_come_back_as_values_including_section_7_5s_inverted_deadlines")
    fun `spoiled order terms come back as values including section 7 5's inverted deadlines`() {
        val envelope = JsBoundaryFixtures.crossed(PROPOSAL.envelope)
        val order = (PROPOSAL.order ?: fail("the proposal must carry an order id")).toHex()
        val item = (PROPOSAL.item ?: fail("the proposal must carry an item")).coordinate.toTagValue()
        val legalTerms = JsBoundaryFixtures.crossed(
            PROPOSAL.terms ?: fail("the proposal must carry terms"),
        )

        fun propose(terms: JsOrderTerms, coordinate: String = item, amountSat: String? = null) =
            buildProposal(envelope, order, coordinate, terms, PROPOSAL.feeRecipient, amountSat)

        fun terms(
            priceMsat: String = legalTerms.priceMsat,
            feeBasisPoints: Int? = legalTerms.feeBasisPoints,
            expirationSeconds: String? = legalTerms.expirationSeconds,
            deliverBySeconds: String? = legalTerms.deliverBySeconds,
        ): JsOrderTerms = JsOrderTerms(priceMsat, feeBasisPoints, expirationSeconds, deliverBySeconds)

        // §7.5's ordering rule, reached three ways: equal, inverted, and a negative deadline.
        record(
            "equal deadlines",
            propose(terms(expirationSeconds = "1700000000", deliverBySeconds = "1700000000")),
        )
        record(
            "inverted deadlines",
            propose(terms(expirationSeconds = "1700000001", deliverBySeconds = "1700000000")),
        )
        record(
            "a negative deliver_by",
            propose(terms(expirationSeconds = null, deliverBySeconds = "-5")),
        )
        assertTrue(
            "DEADLINES_INVERTED" in reasons,
            "§7.5's ordering rule is enforced by `OrderTerms`'s own init, which throws; the boundary " +
                "must return it. Reasons reached: $reasons",
        )

        for (bad in HOSTILE_DECIMALS) {
            record("terms priceMsat=`$bad`", propose(terms(priceMsat = bad)))
            record("terms expirationSeconds=`$bad`", propose(terms(expirationSeconds = bad)))
            record("terms deliverBySeconds=`$bad`", propose(terms(deliverBySeconds = bad)))
            record("proposal amountSat=`$bad`", propose(terms(), amountSat = bad))
        }
        for (bps in listOf(-1, FeeTerm.MAX_BASIS_POINTS + 1)) {
            record("terms feeBasisPoints=$bps", propose(terms(feeBasisPoints = bps)))
        }
        for (bad in listOf("", "not-a-coordinate", "30402:short:d", "x:y:z", "30402::d")) {
            record("itemCoordinate=`$bad`", propose(terms(), coordinate = bad))
        }
        record(
            "a price above §4.4's supply cap",
            propose(terms(priceMsat = JsDecimal.of(Msat.SUPPLY_CAP_MSAT + 1L))),
        )

        // The two other entry points taking the same terms object.
        for (bad in HOSTILE_DECIMALS) {
            record(
                "private bid priceMsat=`$bad`",
                buildPrivateBid(envelope, item, terms(priceMsat = bad), PROPOSAL.feeRecipient),
            )
        }
        val updateOrder = (UPDATE.order ?: fail("the update must carry an order id")).toHex()
        val updateStatus = (UPDATE.status ?: fail("the update must carry a status")).token
            ?: fail("the update's status must have a token")
        for (bad in HOSTILE_DECIMALS) {
            record(
                "status update priceMsat=`$bad`",
                buildStatusUpdate(
                    envelope,
                    updateOrder,
                    updateStatus,
                    item,
                    terms(priceMsat = bad),
                    UPDATE.feeRecipient,
                ),
            )
        }
        for (bad in listOf("", "part-exchanged", "ACCEPTED", " accepted")) {
            record(
                "statusToken=`$bad`",
                buildStatusUpdate(envelope, updateOrder, bad, null, null, null),
            )
        }
    }

    /** §10.1's and §10.3's fields, spoiled one at a time. */
    @Test
    @JsName("spoiled_delivery_fields_come_back_as_values")
    fun `spoiled delivery fields come back as values`() {
        val commitmentEnvelope = JsBoundaryFixtures.crossed(COMMITMENT.envelope)
        val commitmentOrder = (COMMITMENT.order ?: fail("the commitment must carry an order id")).toHex()
        val legalCommitment = JsBoundaryFixtures.crossed(
            COMMITMENT.commitment ?: fail("the commitment must carry §10.1's values"),
        )
        val url = COMMITMENT.url ?: fail("the commitment must carry a url")

        for (bad in HOSTILE_HEX) {
            record(
                "commitment xHex=`$bad`",
                buildCommitment(
                    commitmentEnvelope,
                    commitmentOrder,
                    JsCommitment(bad, legalCommitment.oxHex, legalCommitment.mimeType, legalCommitment.sizeBytes),
                    url,
                ),
            )
            record(
                "commitment oxHex=`$bad`",
                buildCommitment(
                    commitmentEnvelope,
                    commitmentOrder,
                    JsCommitment(legalCommitment.xHex, bad, legalCommitment.mimeType, legalCommitment.sizeBytes),
                    url,
                ),
            )
        }
        for (bad in HOSTILE_DECIMALS) {
            record(
                "commitment sizeBytes=`$bad`",
                buildCommitment(
                    commitmentEnvelope,
                    commitmentOrder,
                    JsCommitment(legalCommitment.xHex, legalCommitment.oxHex, legalCommitment.mimeType, bad),
                    url,
                ),
            )
        }
        for (bad in listOf("", "not a media type", "video")) {
            record(
                "commitment mimeType=`$bad`",
                buildCommitment(
                    commitmentEnvelope,
                    commitmentOrder,
                    JsCommitment(legalCommitment.xHex, legalCommitment.oxHex, bad, legalCommitment.sizeBytes),
                    url,
                ),
            )
        }
        for (bad in listOf("", "not a url", "ftp://x.invalid/a")) {
            record(
                "commitment url=`$bad`",
                buildCommitment(commitmentEnvelope, commitmentOrder, legalCommitment, bad),
            )
        }

        val releaseEnvelope = JsBoundaryFixtures.crossed(RELEASE.envelope)
        val releaseOrder = (RELEASE.order ?: fail("the release must carry an order id")).toHex()
        val legalRelease = JsBoundaryFixtures.crossed(
            RELEASE.release ?: fail("the release must carry §10.3's values"),
        )
        val key = RELEASE.decryptionKey ?: fail("the release must carry §10.2's key")
        val nonce = RELEASE.decryptionNonce ?: fail("the release must carry §10.2's nonce")

        for (bad in HOSTILE_HEX) {
            record(
                "release xHex=`$bad`",
                buildRelease(
                    releaseEnvelope,
                    releaseOrder,
                    JsRelease(bad, legalRelease.oxHex, legalRelease.fileType, legalRelease.sizeBytes),
                    key,
                    nonce,
                ),
            )
            record(
                "release decryptionKeyHex=`$bad`",
                buildRelease(releaseEnvelope, releaseOrder, legalRelease, bad, nonce),
            )
            record(
                "release decryptionNonceHex=`$bad`",
                buildRelease(releaseEnvelope, releaseOrder, legalRelease, key, bad),
            )
        }
        for (bad in HOSTILE_DECIMALS) {
            record(
                "release sizeBytes=`$bad`",
                buildRelease(
                    releaseEnvelope,
                    releaseOrder,
                    JsRelease(legalRelease.xHex, legalRelease.oxHex, legalRelease.fileType, bad),
                    key,
                    nonce,
                ),
            )
        }
    }

    /** §8.6's and §9.2's fields, spoiled one at a time. */
    @Test
    @JsName("spoiled_settlement_fields_come_back_as_values")
    fun `spoiled settlement fields come back as values`() {
        val requestEnvelope = JsBoundaryFixtures.crossed(REQUEST.envelope)
        val requestOrder = (REQUEST.order ?: fail("the request must carry an order id")).toHex()
        val invoice = (REQUEST.invoice ?: fail("the request must carry an invoice")).text
        val payee = (REQUEST.payee ?: fail("the request must name a payee")).token
        val requestTerms = REQUEST.terms?.let { JsBoundaryFixtures.crossed(it) }

        for (bad in listOf("", "treasury", "PROVIDER", " fee")) {
            record(
                "request payeeToken=`$bad`",
                buildPaymentRequest(
                    requestEnvelope, requestOrder, bad, invoice, REQUEST.feeRecipient,
                    terms = requestTerms,
                ),
            )
        }
        for (bad in listOf("", "not-an-invoice", "lnbc", invoice.uppercase())) {
            record(
                "request invoice=`$bad`",
                buildPaymentRequest(
                    requestEnvelope, requestOrder, payee, bad, REQUEST.feeRecipient,
                    terms = requestTerms,
                ),
            )
        }
        for (bad in listOf("", "cash", "LIGHTNING")) {
            record(
                "request mediumToken=`$bad`",
                buildPaymentRequest(
                    requestEnvelope, requestOrder, payee, invoice, REQUEST.feeRecipient,
                    mediumToken = bad, terms = requestTerms,
                ),
            )
        }
        for (bad in HOSTILE_HEX) {
            record(
                "request orderIdHex=`$bad`",
                buildPaymentRequest(
                    requestEnvelope, bad, payee, invoice, REQUEST.feeRecipient, terms = requestTerms,
                ),
            )
        }

        val receiptEnvelope = JsBoundaryFixtures.crossed(RECEIPT.envelope)
        val receiptOrder = (RECEIPT.order ?: fail("the receipt must carry an order id")).toHex()
        val reference = RECEIPT.reference ?: fail("the receipt must carry a reference")
        val proof = RECEIPT.proof ?: fail("the receipt must carry a proof")
        val receiptPayee = (RECEIPT.payee ?: fail("the receipt must name a payee")).token
        val receiptMedium = RECEIPT.medium.token ?: fail("the receipt's medium must have a token")
        val receiptTerms = RECEIPT.terms?.let { JsBoundaryFixtures.crossed(it) }

        for (bad in listOf("", "treasury", "PROVIDER")) {
            record(
                "receipt payeeToken=`$bad`",
                buildReceipt(
                    receiptEnvelope, receiptOrder, bad, reference, proof, RECEIPT.feeRecipient,
                    receiptMedium, receiptTerms,
                ),
            )
        }
        for (bad in listOf("", "not-a-reference")) {
            record(
                "receipt reference=`$bad`",
                buildReceipt(
                    receiptEnvelope, receiptOrder, receiptPayee, bad, proof, RECEIPT.feeRecipient,
                    receiptMedium, receiptTerms,
                ),
            )
        }
        for (bad in listOf("", "not-a-preimage", "ZZ")) {
            record(
                "receipt proof=`$bad`",
                buildReceipt(
                    receiptEnvelope, receiptOrder, receiptPayee, reference, bad, RECEIPT.feeRecipient,
                    receiptMedium, receiptTerms,
                ),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two decoders.
    // -----------------------------------------------------------------------------------------

    /**
     * Neither decoder throws for any text, including the shapes a JSON reader is most likely to.
     *
     * The decoders have their own catch ladders rather than going through `jsBuild`, so they need their
     * own sweep: an unbalanced brace, a bare array, a deeply nested value and a lone surrogate are four
     * different places inside `EventJson.read`.
     */
    @Test
    @JsName("neither_decoder_throws_for_any_text")
    fun `neither decoder throws for any text`() {
        val texts = listOf(
            "", " ", "{", "}", "[]", "null", "0", "\"a string\"", "{\"id\":", "{\"id\":}",
            "{}", "{\"id\":null}", "{\"tags\":[[]]}", "﻿{}", "\uD800", "a".repeat(10_000),
            "[".repeat(200) + "]".repeat(200),
        )
        for (text in texts) {
            val listing = decodeListing(text)
            assertFalse(listing.ok, "`${text.take(20)}` is not a listing")
            assertNotNull(listing.reason, "a refusal must name a reason")
            assertNotNull(listing.reasonVocabulary, "and the vocabulary it came from")
            assertNull(listing.listing, "a refusal must carry no listing")
            vocabularies += listing.reasonVocabulary ?: ""
            reasons += listing.reason ?: ""

            val bid = decodeBid(text)
            assertFalse(bid.ok, "`${text.take(20)}` is not a bid")
            assertNotNull(bid.reason, "a refusal must name a reason")
            assertNull(bid.bid, "a refusal must carry no bid")
            vocabularies += bid.reasonVocabulary ?: ""
            reasons += bid.reason ?: ""
        }
        assertTrue(
            reasons.size >= 4,
            "only $reasons were reached across ${texts.size} hostile texts, so this sweep is landing " +
                "on one arm of the ladder rather than on the ladder",
        )
        assertFalse(
            JsCrossing.VOCABULARY in vocabularies,
            "a decoder answered the crossing's own vocabulary for malformed JSON, which would be the " +
                "boundary deciding a §4.3 question itself: $vocabularies",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The breadth anchor.
    // -----------------------------------------------------------------------------------------

    /**
     * The sweeps above reach many arms of many ladders, not one.
     *
     * "No entry point throws" is satisfied by a sweep that never provokes a refusal, so this runs every
     * builder case again in one place and pins how wide the result is. The vocabularies are pinned too,
     * because an arm wired to the wrong rejection enum is a caller branching on the wrong thing.
     */
    @Test
    @JsName("the_hostile_sweep_reaches_many_refusals_across_many_vocabularies")
    fun `the hostile sweep reaches many refusals across many vocabularies`() {
        `every spoiled listing field comes back as a value`()
        `every spoiled envelope field comes back as a value`()
        `spoiled order terms come back as values including section 7 5's inverted deadlines`()
        `spoiled delivery fields come back as values`()
        `spoiled settlement fields come back as values`()

        assertTrue(
            reasons.size >= 15,
            "only ${reasons.size} distinct refusal(s) were reached — $reasons — which is not the " +
                "breadth these cases are written for",
        )
        assertTrue(
            vocabularies.size >= 6,
            "only $vocabularies were reached; the builders' ladders name more rejection enums than that",
        )
        // The two arms this file was written for, by name, so a later edit cannot drop them silently.
        for (reason in listOf("STATUS_OUTSIDE_VOCABULARY", "DEADLINES_INVERTED")) {
            assertTrue(
                reason in reasons,
                "$reason is raised as an exception by the library and must be **returned** by the " +
                    "boundary; it was the arm `jsBuild` was missing. Reasons reached: $reasons",
            )
        }
        // The money refusals, by name. Without these, `record` accepting a *built* answer would make
        // the negative and above-cap price cases vacuous: if `Msat.ofMsat` stopped refusing either, the
        // crossing would build an event and this sweep would have stayed green. Naming them turns the
        // breadth anchor into a statement that the money rules were actually reached through the
        // boundary.
        for (reason in listOf("NEGATIVE", "ABOVE_SUPPLY", "BPS_ABOVE_MAXIMUM")) {
            assertTrue(
                reason in reasons,
                "$reason is §4.4's or §8.1's own refusal and must arrive through the crossing; a " +
                    "spoiled amount that was quietly *accepted* would otherwise pass. Reasons " +
                    "reached: $reasons",
            )
        }
        for (vocabulary in listOf(
            "ListingRejection",
            "OrderStateRejection",
            "MoneyRejection",
            JsCrossing.VOCABULARY,
        )) {
            assertTrue(vocabulary in vocabularies, "$vocabulary was not reached: $vocabularies")
        }
    }

    /**
     * One case: it came back as a value, it is a refusal that names itself, and it carries no event.
     *
     * The assertion that it did not throw is the call itself — a throw propagates and fails the test —
     * and that is deliberate: wrapping it in a `try` here would be the `catch (Throwable)` this
     * boundary refuses to write, one level up in the test.
     */
    private fun record(what: String, answer: JsBuildResult) {
        if (answer.ok) {
            assertNotNull(answer.event, "$what: a built answer must carry an event")
            return
        }
        assertEquals("refused", answer.kind, "$what: `kind` must discriminate with `ok`")
        assertNull(answer.event, "$what: a refusal must carry no event")
        val reason = answer.reason ?: fail("$what: a refusal must name a reason")
        val vocabulary = answer.reasonVocabulary ?: fail("$what: a refusal must name its vocabulary")
        assertTrue(reason.isNotEmpty(), "$what: the reason must not be empty")
        assertNotNull(answer.detail, "$what: a refusal must carry a sentence a caller can log")
        reasons += reason
        vocabularies += vocabulary
    }
}
