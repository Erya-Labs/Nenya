package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.bid.BidWriterFixtures
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.ListingOrderBuild
import dev.eryalabs.nenya.channel.ListingOrderWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.delivery.DeliveryFixtures
import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.envelope.GiftWrap
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.listing.ListingStatusCodec
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.Nip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.settlement.FeeTermPoint
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.ReleaseFixtures
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.reflect.KCallable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
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
 * exactly one field of an otherwise legal crossing, and a value has to come back.
 *
 * ### Every entry point, and how "every" is held (T54)
 *
 * The file began with thirteen of the package's entry points. Every call now goes through [call], which
 * records an entry point's name **only once its call has returned** — a throw propagates past the
 * recording and fails the test — and [SWEPT_ENTRY_POINTS] pins the set the whole sweep must record.
 * `JsSurfaceTest` holds that pin, plus its one named exemption, equal to the entry points the compiled
 * package exports, so an export added without a hostile case here turns that equality red.
 *
 * The name recorded is the callable reference's own, resolved by the compiler rather than a string
 * somebody typed beside it — though that each case's block calls the function its reference names is
 * held by reading it.
 *
 * ### What the breadth assertion is for
 *
 * "No entry point throws" is satisfied by a sweep that never reaches a refusal at all, so the reasons
 * reached are collected and their count is pinned. A sweep that stopped refusing would go red rather
 * than quietly prove nothing.
 */
class JsRefusalByValueTest {

    internal companion object {

        /**
         * Every entry point the sweep in `the hostile sweep reaches every entry point …` must record.
         *
         * Forty of `JsSurfaceTest.ENTRY_POINTS`' forty-one: `capabilities` takes nothing, so it
         * has no hostile value to be handed and is that file's one named exemption.
         */
        val SWEPT_ENTRY_POINTS: Set<String> = setOf(
            "decodeListing",
            "decodeBid",
            "buildListingRequest",
            "buildListingOffer",
            "buildListingDraft",
            "buildChat",
            "buildProposal",
            "buildStatusUpdate",
            "buildCommitment",
            "buildRelease",
            "buildPrivateBid",
            "buildPaymentRequest",
            "buildReceipt",
            "seal",
            "open",
            "verifySettlement",
            "verifyFeeReceipt",
            "openOrder",
            "stepOrder",
            "acceptPaymentRequest",
            "decodeProposal",
            "decodeCommitment",
            "decodeRelease",
            "verifyServedBytes",
            "buildBid",
            "decodeStatusUpdate",
            "checkAcceptance",
            "listingActivity",
            "bidActivity",
            "checkDeadlines",
            "buildProposalAgainst",
            "buildAgreementAgainst",
            "matchesBoard",
            "sealAsync",
            "openAsync",
            "decodePrivateBid",
            "bidFeeSighting",
            "privateBidFeeSighting",
            "buildProposalForBid",
            "buildReleaseAfterPayment",
        )

        /** Decimal fields, spoiled. Each is a form [JsDecimal] does not write and must not read. */
        val HOSTILE_DECIMALS: List<String> = listOf("", "12x", "1.5", "007", "-1", " 1", "1e9")

        /** Hex fields, spoiled: wrong length, wrong case, wrong alphabet, empty. */
        val HOSTILE_HEX: List<String> = listOf("", "abc", "00", "NOTHEX", "ZZ")

        /** Texts no JSON reader should be handed, each reaching a different place in `EventJson.read`. */
        val HOSTILE_TEXTS: List<String> = listOf(
            "", " ", "{", "}", "[]", "null", "0", "\"a string\"", "{\"id\":", "{\"id\":}",
            "{}", "{\"id\":null}", "{\"tags\":[[]]}", "﻿{}", "\uD800", "a".repeat(10_000),
            "[".repeat(200) + "]".repeat(200),
        )

        /**
         * A page's clock, spoiled: absent, answering nothing, and answering each of [HOSTILE_DECIMALS] —
         * `"-1"` among them, the negative reading.
         */
        val HOSTILE_CLOCKS: List<(() -> String?)?> =
            listOf(null, clockSaying(null)) + HOSTILE_DECIMALS.map { clockSaying(it) }

        fun clockSaying(text: String?): () -> String? = { text }

        /** [base] with its clock replaced by [now], every other seam kept. */
        fun withClock(base: JsEnvironment, now: (() -> String?)?): JsEnvironment =
            JsEnvironment(now, base.randomBytesHex, base.verifySchnorr, base.freshSigner)

        /** A signer answering [bad] to every one of its four questions. */
        fun hostileSigner(bad: String): JsSigner = JsSigner(
            publicKeyHex = { bad },
            signEvent = { bad },
            nip44Encrypt = { _, _ -> bad },
            nip44Decrypt = { _, _ -> JsDecryption(true, bad) },
        )

        /** [honest]'s own key, and [bad] for the other three. */
        fun spoiledSigner(honest: JsSigner, bad: String): JsSigner = JsSigner(
            publicKeyHex = honest.publicKeyHex,
            signEvent = { bad },
            nip44Encrypt = { _, _ -> bad },
            nip44Decrypt = { _, _ -> JsDecryption(true, bad) },
        )

        /** [legal] with one field spoiled at a time, for every builder taking an envelope. */
        fun hostileEnvelopes(legal: JsRumorEnvelope): List<JsRumorEnvelope> =
            HOSTILE_HEX.map { bad ->
                JsRumorEnvelope(bad, legal.createdAtSeconds, legal.counterparties, legal.content, legal.nenyaVersion)
            } + HOSTILE_DECIMALS.map { bad ->
                JsRumorEnvelope(legal.authorPubkey, bad, legal.counterparties, legal.content, legal.nenyaVersion)
            }

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

        /** A legal §6 bid, likewise. */
        val BID: JsAuthoredBid = JsBoundaryFixtures.crossed(BidWriterFixtures.minimal())

        /** The deadline every expiring listing and bid below carries. */
        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        /** A §5.2 request, expiring at [DEADLINE] when [expiring]. */
        fun listing(expiring: Boolean): Listing {
            val tags = ListingFixtures.minimal(NenyaKind.REQUEST)
            if (expiring) tags += listOf(listOf("expiration", DEADLINE.toString()))
            return ListingFixtures.decode(tags, NenyaKind.REQUEST)
        }
    }

    /** Every entry point whose call returned, by its own name. See [call]. */
    private val returned = mutableSetOf<String>()

    /** Every reason any case in this file reached, so the breadth of the sweep is a measured number. */
    private val reasons = mutableSetOf<String>()

    /** Every vocabulary any case reached, so an arm wired to the wrong one is visible. */
    private val vocabularies = mutableSetOf<String>()

    /**
     * The one door every case goes through: [invoke] is made, and **then** [entryPoint]'s name is
     * recorded.
     *
     * Recording after the call is the whole of the mechanism. An entry point that throws never reaches
     * the second line, so it is missing from [returned] and the pinned-set check names it — as well as
     * the throw failing the case that made it. Typed by the reference's own return type, which ties the
     * answer's type to it and no more: fifteen entry points share `JsBuildResult`, so that each block
     * calls the function it names is held by reading it, not by the compiler.
     */
    private fun <R> call(entryPoint: KCallable<R>, invoke: () -> R): R {
        val answer = invoke()
        returned += entryPoint.name
        return answer
    }

    // -----------------------------------------------------------------------------------------
    // The eleven T42 builders.
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
        fun offer(what: String, listing: JsAuthoredListing) =
            record(what, call(::buildListingOffer) { buildListingOffer(listing) })

        // Every decimal field, every hostile form.
        for (bad in HOSTILE_DECIMALS) {
            offer("createdAtSeconds=`$bad`", spoiled(createdAtSeconds = bad))
            offer("priceMsat=`$bad`", spoiled(priceMsat = bad))
            offer("publishedAtSeconds=`$bad`", spoiled(publishedAtSeconds = bad))
            offer("expirationSeconds=`$bad`", spoiled(expirationSeconds = bad))
            offer("deliverBySeconds=`$bad`", spoiled(deliverBySeconds = bad))
        }
        // Every hex field.
        for (bad in HOSTILE_HEX) {
            offer("authorPubkey=`$bad`", spoiled(authorPubkey = bad))
            offer("feeRecipient=`$bad`", spoiled(feeBasisPoints = 250, feeRecipient = bad))
        }
        // §8.1's range, both sides of it, and §4.4's cap.
        for (bps in listOf(-1, FeeTerm.MAX_BASIS_POINTS + 1)) {
            offer("feeBasisPoints=$bps", spoiled(feeBasisPoints = bps))
        }
        offer("a price above §4.4's supply cap", spoiled(priceMsat = JsDecimal.of(Msat.SUPPLY_CAP_MSAT + 1L)))
        // Rows of an arity §5.3 gives no form for, in both directions.
        offer("an empty image row", spoiled(images = arrayOf(emptyArray())))
        offer(
            "a three-element image row",
            spoiled(images = arrayOf(arrayOf("https://x.invalid/a.png", "1x1", "no"))),
        )
        offer("an empty p row", spoiled(pubkeyRefs = arrayOf(emptyArray())))
        // §5.1's two `status` refusals — the pair this file was written for.
        val requestToken = (
            ListingStatusCodec.vocabulary(NenyaKind.REQUEST) -
                ListingStatusCodec.vocabulary(NenyaKind.OFFER)
            ).firstNotNullOfOrNull { it.token }
            ?: fail("§5.2's request vocabulary no longer holds a status §5.1's offer vocabulary lacks")
        offer("statusToken=`$requestToken` on an offer", spoiled(statusToken = requestToken))
        offer("an invented statusToken", spoiled(statusToken = "part-exchanged"))

        assertTrue(
            "STATUS_OUTSIDE_VOCABULARY" in reasons,
            "§5.1's closed vocabulary refuses by throwing from the translation, and the boundary must " +
                "return it. That arm was missing from `jsBuild` until this test existed; reasons " +
                "reached were $reasons",
        )
        // The three other listing entry points take the same object, so a missing arm in one is a
        // missing arm in all three — but the routing is per-kind and each must be exercised.
        for (bad in HOSTILE_DECIMALS) {
            record(
                "request priceMsat=`$bad`",
                call(::buildListingRequest) { buildListingRequest(spoiled(priceMsat = bad)) },
            )
            record(
                "draft priceMsat=`$bad`",
                call(::buildListingDraft) { buildListingDraft(spoiled(priceMsat = bad)) },
            )
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
        fun chat(what: String, envelope: JsRumorEnvelope, orderIdHex: String? = null) =
            record(what, call(::buildChat) { buildChat(envelope, orderIdHex) })

        for (bad in HOSTILE_DECIMALS) {
            chat("chat createdAtSeconds=`$bad`", spoiled(createdAtSeconds = bad))
        }
        for (bad in HOSTILE_HEX) {
            chat("chat authorPubkey=`$bad`", spoiled(authorPubkey = bad))
            chat("chat orderIdHex=`$bad`", spoiled(), bad)
            chat("chat counterparty=`$bad`", spoiled(counterparties = arrayOf(arrayOf(bad))))
        }
        chat("an empty p row", spoiled(counterparties = arrayOf(emptyArray())))
        chat(
            "a three-element p row",
            spoiled(counterparties = arrayOf(arrayOf(legal.authorPubkey, "wss://r.invalid", "x"))),
        )
        for (version in listOf(-1, 0, Int.MAX_VALUE)) {
            chat("chat nenyaVersion=$version", spoiled(nenyaVersion = version))
        }
        chat("an empty extra tag row", spoiled(extraTags = arrayOf(emptyArray())))
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

        fun propose(what: String, terms: JsOrderTerms, coordinate: String = item, amountSat: String? = null) =
            record(
                what,
                call(::buildProposal) {
                    buildProposal(envelope, order, coordinate, terms, PROPOSAL.feeRecipient, amountSat)
                },
            )

        fun terms(
            priceMsat: String = legalTerms.priceMsat,
            feeBasisPoints: Int? = legalTerms.feeBasisPoints,
            expirationSeconds: String? = legalTerms.expirationSeconds,
            deliverBySeconds: String? = legalTerms.deliverBySeconds,
        ): JsOrderTerms = JsOrderTerms(priceMsat, feeBasisPoints, expirationSeconds, deliverBySeconds)

        // §7.5's ordering rule, reached three ways: equal, inverted, and a negative deadline.
        propose("equal deadlines", terms(expirationSeconds = "1700000000", deliverBySeconds = "1700000000"))
        propose("inverted deadlines", terms(expirationSeconds = "1700000001", deliverBySeconds = "1700000000"))
        propose("a negative deliver_by", terms(expirationSeconds = null, deliverBySeconds = "-5"))
        assertTrue(
            "DEADLINES_INVERTED" in reasons,
            "§7.5's ordering rule is enforced by `OrderTerms`'s own init, which throws; the boundary " +
                "must return it. Reasons reached: $reasons",
        )

        for (bad in HOSTILE_DECIMALS) {
            propose("terms priceMsat=`$bad`", terms(priceMsat = bad))
            propose("terms expirationSeconds=`$bad`", terms(expirationSeconds = bad))
            propose("terms deliverBySeconds=`$bad`", terms(deliverBySeconds = bad))
            propose("proposal amountSat=`$bad`", terms(), amountSat = bad)
        }
        for (bps in listOf(-1, FeeTerm.MAX_BASIS_POINTS + 1)) {
            propose("terms feeBasisPoints=$bps", terms(feeBasisPoints = bps))
        }
        for (bad in listOf("", "not-a-coordinate", "30402:short:d", "x:y:z", "30402::d")) {
            propose("itemCoordinate=`$bad`", terms(), coordinate = bad)
        }
        propose("a price above §4.4's supply cap", terms(priceMsat = JsDecimal.of(Msat.SUPPLY_CAP_MSAT + 1L)))

        // The two other entry points taking the same terms object.
        for (bad in HOSTILE_DECIMALS) {
            record(
                "private bid priceMsat=`$bad`",
                call(::buildPrivateBid) {
                    buildPrivateBid(envelope, item, terms(priceMsat = bad), PROPOSAL.feeRecipient)
                },
            )
        }
        val updateOrder = (UPDATE.order ?: fail("the update must carry an order id")).toHex()
        val updateStatus = (UPDATE.status ?: fail("the update must carry a status")).token
            ?: fail("the update's status must have a token")
        for (bad in HOSTILE_DECIMALS) {
            record(
                "status update priceMsat=`$bad`",
                call(::buildStatusUpdate) {
                    buildStatusUpdate(envelope, updateOrder, updateStatus, item, terms(priceMsat = bad), UPDATE.feeRecipient)
                },
            )
        }
        for (bad in listOf("", "part-exchanged", "ACCEPTED", " accepted")) {
            record(
                "statusToken=`$bad`",
                call(::buildStatusUpdate) { buildStatusUpdate(envelope, updateOrder, bad, null, null, null) },
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
        fun commit(what: String, commitment: JsCommitment, at: String = url) = record(
            what,
            call(::buildCommitment) { buildCommitment(commitmentEnvelope, commitmentOrder, commitment, at) },
        )

        for (bad in HOSTILE_HEX) {
            commit(
                "commitment xHex=`$bad`",
                JsCommitment(bad, legalCommitment.oxHex, legalCommitment.mimeType, legalCommitment.sizeBytes),
            )
            commit(
                "commitment oxHex=`$bad`",
                JsCommitment(legalCommitment.xHex, bad, legalCommitment.mimeType, legalCommitment.sizeBytes),
            )
        }
        for (bad in HOSTILE_DECIMALS) {
            commit(
                "commitment sizeBytes=`$bad`",
                JsCommitment(legalCommitment.xHex, legalCommitment.oxHex, legalCommitment.mimeType, bad),
            )
        }
        for (bad in listOf("", "not a media type", "video")) {
            commit(
                "commitment mimeType=`$bad`",
                JsCommitment(legalCommitment.xHex, legalCommitment.oxHex, bad, legalCommitment.sizeBytes),
            )
        }
        for (bad in listOf("", "not a url", "ftp://x.invalid/a")) {
            commit("commitment url=`$bad`", legalCommitment, bad)
        }

        val releaseEnvelope = JsBoundaryFixtures.crossed(RELEASE.envelope)
        val releaseOrder = (RELEASE.order ?: fail("the release must carry an order id")).toHex()
        val legalRelease = JsBoundaryFixtures.crossed(
            RELEASE.release ?: fail("the release must carry §10.3's values"),
        )
        val key = RELEASE.decryptionKey ?: fail("the release must carry §10.2's key")
        val nonce = RELEASE.decryptionNonce ?: fail("the release must carry §10.2's nonce")
        fun release(what: String, release: JsRelease, keyHex: String = key, nonceHex: String = nonce) = record(
            what,
            call(::buildRelease) { buildRelease(releaseEnvelope, releaseOrder, release, keyHex, nonceHex) },
        )

        for (bad in HOSTILE_HEX) {
            release(
                "release xHex=`$bad`",
                JsRelease(bad, legalRelease.oxHex, legalRelease.fileType, legalRelease.sizeBytes),
            )
            release("release decryptionKeyHex=`$bad`", legalRelease, keyHex = bad)
            release("release decryptionNonceHex=`$bad`", legalRelease, nonceHex = bad)
        }
        for (bad in HOSTILE_DECIMALS) {
            release(
                "release sizeBytes=`$bad`",
                JsRelease(legalRelease.xHex, legalRelease.oxHex, legalRelease.fileType, bad),
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
        fun request(
            what: String,
            orderIdHex: String = requestOrder,
            payeeToken: String = payee,
            invoiceText: String = invoice,
            mediumToken: String? = null,
        ) = record(
            what,
            call(::buildPaymentRequest) {
                if (mediumToken == null) {
                    buildPaymentRequest(
                        requestEnvelope, orderIdHex, payeeToken, invoiceText, REQUEST.feeRecipient,
                        terms = requestTerms,
                    )
                } else {
                    buildPaymentRequest(
                        requestEnvelope, orderIdHex, payeeToken, invoiceText, REQUEST.feeRecipient,
                        mediumToken = mediumToken, terms = requestTerms,
                    )
                }
            },
        )

        for (bad in listOf("", "treasury", "PROVIDER", " fee")) {
            request("request payeeToken=`$bad`", payeeToken = bad)
        }
        for (bad in listOf("", "not-an-invoice", "lnbc", invoice.uppercase())) {
            request("request invoice=`$bad`", invoiceText = bad)
        }
        for (bad in listOf("", "cash", "LIGHTNING")) {
            request("request mediumToken=`$bad`", mediumToken = bad)
        }
        for (bad in HOSTILE_HEX) {
            request("request orderIdHex=`$bad`", orderIdHex = bad)
        }

        val receiptEnvelope = JsBoundaryFixtures.crossed(RECEIPT.envelope)
        val receiptOrder = (RECEIPT.order ?: fail("the receipt must carry an order id")).toHex()
        val reference = RECEIPT.reference ?: fail("the receipt must carry a reference")
        val proof = RECEIPT.proof ?: fail("the receipt must carry a proof")
        val receiptPayee = (RECEIPT.payee ?: fail("the receipt must name a payee")).token
        val receiptMedium = RECEIPT.medium.token ?: fail("the receipt's medium must have a token")
        val receiptTerms = RECEIPT.terms?.let { JsBoundaryFixtures.crossed(it) }
        fun receipt(
            what: String,
            payeeToken: String = receiptPayee,
            referenceText: String = reference,
            proofText: String = proof,
        ) = record(
            what,
            call(::buildReceipt) {
                buildReceipt(
                    receiptEnvelope, receiptOrder, payeeToken, referenceText, proofText, RECEIPT.feeRecipient,
                    receiptMedium, receiptTerms,
                )
            },
        )

        for (bad in listOf("", "treasury", "PROVIDER")) {
            receipt("receipt payeeToken=`$bad`", payeeToken = bad)
        }
        for (bad in listOf("", "not-a-reference")) {
            receipt("receipt reference=`$bad`", referenceText = bad)
        }
        for (bad in listOf("", "not-a-preimage", "ZZ")) {
            receipt("receipt proof=`$bad`", proofText = bad)
        }
    }

    /**
     * T54: the string-bearing parameters the five sweeps above left legal, spoiled — the envelope of
     * every builder taking one, every order id, `item` and fee recipient, and the receipt's medium and
     * terms.
     */
    @Test
    @JsName("every_builder_s_envelope_order_id_and_fee_recipient_come_back_as_values")
    fun `every builder's envelope order id and fee recipient come back as values`() {
        val proposalEnvelope = JsBoundaryFixtures.crossed(PROPOSAL.envelope)
        val proposalOrder = (PROPOSAL.order ?: fail("the proposal must carry an order id")).toHex()
        val item = (PROPOSAL.item ?: fail("the proposal must carry an item")).coordinate.toTagValue()
        val proposalTerms = JsBoundaryFixtures.crossed(PROPOSAL.terms ?: fail("the proposal must carry terms"))
        for (envelope in hostileEnvelopes(proposalEnvelope)) {
            record(
                "proposal envelope",
                call(::buildProposal) {
                    buildProposal(envelope, proposalOrder, item, proposalTerms, PROPOSAL.feeRecipient)
                },
            )
            record(
                "private bid envelope",
                call(::buildPrivateBid) { buildPrivateBid(envelope, item, proposalTerms, PROPOSAL.feeRecipient) },
            )
        }
        for (bad in HOSTILE_HEX) {
            record(
                "proposal orderIdHex=`$bad`",
                call(::buildProposal) { buildProposal(proposalEnvelope, bad, item, proposalTerms, PROPOSAL.feeRecipient) },
            )
            record(
                "proposal feeRecipient=`$bad`",
                call(::buildProposal) { buildProposal(proposalEnvelope, proposalOrder, item, proposalTerms, bad) },
            )
            record(
                "private bid feeRecipient=`$bad`",
                call(::buildPrivateBid) { buildPrivateBid(proposalEnvelope, item, proposalTerms, bad) },
            )
            record(
                "private bid itemCoordinate=`$bad`",
                call(::buildPrivateBid) { buildPrivateBid(proposalEnvelope, bad, proposalTerms, PROPOSAL.feeRecipient) },
            )
        }

        val updateEnvelope = JsBoundaryFixtures.crossed(UPDATE.envelope)
        val updateOrder = (UPDATE.order ?: fail("the update must carry an order id")).toHex()
        val updateStatus = (UPDATE.status ?: fail("the update must carry a status")).token
            ?: fail("the update's status must have a token")
        for (envelope in hostileEnvelopes(updateEnvelope)) {
            record(
                "status update envelope",
                call(::buildStatusUpdate) { buildStatusUpdate(envelope, updateOrder, updateStatus) },
            )
        }
        for (bad in HOSTILE_HEX) {
            record(
                "status update orderIdHex=`$bad`",
                call(::buildStatusUpdate) { buildStatusUpdate(updateEnvelope, bad, updateStatus) },
            )
            record(
                "status update itemCoordinate=`$bad`",
                call(::buildStatusUpdate) { buildStatusUpdate(updateEnvelope, updateOrder, updateStatus, bad) },
            )
            record(
                "status update feeRecipient=`$bad`",
                call(::buildStatusUpdate) {
                    buildStatusUpdate(updateEnvelope, updateOrder, updateStatus, item, proposalTerms, bad)
                },
            )
        }

        val commitment = JsBoundaryFixtures.crossed(COMMITMENT.commitment ?: fail("the commitment's values"))
        val url = COMMITMENT.url ?: fail("the commitment must carry a url")
        val commitmentOrder = (COMMITMENT.order ?: fail("the commitment must carry an order id")).toHex()
        for (envelope in hostileEnvelopes(JsBoundaryFixtures.crossed(COMMITMENT.envelope))) {
            record(
                "commitment envelope",
                call(::buildCommitment) { buildCommitment(envelope, commitmentOrder, commitment, url) },
            )
        }
        val release = JsBoundaryFixtures.crossed(RELEASE.release ?: fail("the release's values"))
        val key = RELEASE.decryptionKey ?: fail("the release must carry §10.2's key")
        val nonce = RELEASE.decryptionNonce ?: fail("the release must carry §10.2's nonce")
        val releaseOrder = (RELEASE.order ?: fail("the release must carry an order id")).toHex()
        for (envelope in hostileEnvelopes(JsBoundaryFixtures.crossed(RELEASE.envelope))) {
            record(
                "release envelope",
                call(::buildRelease) { buildRelease(envelope, releaseOrder, release, key, nonce) },
            )
        }
        for (bad in HOSTILE_HEX) {
            record(
                "commitment orderIdHex=`$bad`",
                call(::buildCommitment) {
                    buildCommitment(JsBoundaryFixtures.crossed(COMMITMENT.envelope), bad, commitment, url)
                },
            )
            record(
                "release orderIdHex=`$bad`",
                call(::buildRelease) {
                    buildRelease(JsBoundaryFixtures.crossed(RELEASE.envelope), bad, release, key, nonce)
                },
            )
        }

        val requestOrder = (REQUEST.order ?: fail("the request must carry an order id")).toHex()
        val invoice = (REQUEST.invoice ?: fail("the request must carry an invoice")).text
        val payee = (REQUEST.payee ?: fail("the request must name a payee")).token
        val requestTerms = REQUEST.terms?.let { JsBoundaryFixtures.crossed(it) }
        for (envelope in hostileEnvelopes(JsBoundaryFixtures.crossed(REQUEST.envelope))) {
            record(
                "request envelope",
                call(::buildPaymentRequest) {
                    buildPaymentRequest(envelope, requestOrder, payee, invoice, REQUEST.feeRecipient, terms = requestTerms)
                },
            )
        }
        for (bad in HOSTILE_HEX) {
            record(
                "request feeRecipient=`$bad`",
                call(::buildPaymentRequest) {
                    buildPaymentRequest(
                        JsBoundaryFixtures.crossed(REQUEST.envelope), requestOrder, payee, invoice, bad,
                        terms = requestTerms,
                    )
                },
            )
        }
        for (bad in HOSTILE_DECIMALS) {
            record(
                "request terms priceMsat=`$bad`",
                call(::buildPaymentRequest) {
                    buildPaymentRequest(
                        JsBoundaryFixtures.crossed(REQUEST.envelope), requestOrder, payee, invoice,
                        REQUEST.feeRecipient, terms = JsOrderTerms(priceMsat = bad),
                    )
                },
            )
        }

        val receiptEnvelope = JsBoundaryFixtures.crossed(RECEIPT.envelope)
        val receiptOrder = (RECEIPT.order ?: fail("the receipt must carry an order id")).toHex()
        val reference = RECEIPT.reference ?: fail("the receipt must carry a reference")
        val proof = RECEIPT.proof ?: fail("the receipt must carry a proof")
        val receiptPayee = (RECEIPT.payee ?: fail("the receipt must name a payee")).token
        val receiptMedium = RECEIPT.medium.token ?: fail("the receipt's medium must have a token")
        val receiptTerms = RECEIPT.terms?.let { JsBoundaryFixtures.crossed(it) }
        fun receipt(
            what: String,
            envelope: JsRumorEnvelope = receiptEnvelope,
            orderIdHex: String = receiptOrder,
            feeRecipient: String? = RECEIPT.feeRecipient,
            mediumToken: String = receiptMedium,
            terms: JsOrderTerms? = receiptTerms,
        ) = record(
            what,
            call(::buildReceipt) {
                buildReceipt(envelope, orderIdHex, receiptPayee, reference, proof, feeRecipient, mediumToken, terms)
            },
        )
        for (envelope in hostileEnvelopes(receiptEnvelope)) receipt("receipt envelope", envelope = envelope)
        for (bad in HOSTILE_HEX) {
            receipt("receipt orderIdHex=`$bad`", orderIdHex = bad)
            receipt("receipt feeRecipient=`$bad`", feeRecipient = bad)
        }
        for (bad in listOf("", "cash", "LIGHTNING")) receipt("receipt mediumToken=`$bad`", mediumToken = bad)
        for (bad in HOSTILE_DECIMALS) receipt("receipt terms priceMsat=`$bad`", terms = JsOrderTerms(priceMsat = bad))
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
        // The decoders' own reasons and vocabularies, kept apart from the file's: the anchor below runs
        // this sweep after the builders, which do reach the crossing's vocabulary.
        val decoderReasons = mutableSetOf<String>()
        val decoderVocabularies = mutableSetOf<String>()
        for (text in HOSTILE_TEXTS) {
            val listing = call(::decodeListing) { decodeListing(text) }
            assertFalse(listing.ok, "`${text.take(20)}` is not a listing")
            assertNotNull(listing.reason, "a refusal must name a reason")
            assertNotNull(listing.reasonVocabulary, "and the vocabulary it came from")
            assertNull(listing.listing, "a refusal must carry no listing")
            decoderVocabularies += listing.reasonVocabulary ?: ""
            decoderReasons += listing.reason ?: ""

            val bid = call(::decodeBid) { decodeBid(text) }
            assertFalse(bid.ok, "`${text.take(20)}` is not a bid")
            assertNotNull(bid.reason, "a refusal must name a reason")
            assertNull(bid.bid, "a refusal must carry no bid")
            decoderVocabularies += bid.reasonVocabulary ?: ""
            decoderReasons += bid.reason ?: ""
        }
        reasons += decoderReasons
        vocabularies += decoderVocabularies
        assertTrue(
            decoderReasons.size >= 4,
            "only $decoderReasons were reached across ${HOSTILE_TEXTS.size} hostile texts, so this sweep " +
                "is landing on one arm of the ladder rather than on the ladder",
        )
        assertFalse(
            JsCrossing.VOCABULARY in decoderVocabularies,
            "a decoder answered the crossing's own vocabulary for malformed JSON, which would be the " +
                "boundary deciding a §4.3 question itself: $decoderVocabularies",
        )
    }

    // -----------------------------------------------------------------------------------------
    // T54: the twenty entry points T43 to T53 added, each handed a hostile value in every
    // string-bearing parameter and, where it takes a `JsEnvironment`, a silent or negative clock.
    // -----------------------------------------------------------------------------------------

    /** §7.1's two procedures: a spoiled key, signer, clock, randomness, one-time signer, verifier or wrap. */
    @Test
    @JsName("seal_and_open_return_every_hostile_input_as_a_value")
    fun `seal and open return every hostile input as a value`() {
        val rumor = JsWireEvent(EnvelopeFixtures.rumor())
        val recipient = EnvelopeFixtures.recipientKey()
        val sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner())
        fun environment(): JsEnvironment = JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
            ephemeral = Nip44PayloadEphemeralSigners(),
        )
        fun sealed(
            what: String,
            recipientPubkey: String = recipient,
            signer: JsSigner = sender,
            env: JsEnvironment = environment(),
            hint: String? = null,
        ) = record(what, call(::seal) { seal(rumor, recipientPubkey, signer, env, hint) })

        for (bad in HOSTILE_HEX) {
            sealed("seal recipientPubkey=`$bad`", recipientPubkey = bad)
            sealed("seal sender answering `$bad`", signer = hostileSigner(bad))
            sealed("seal sender signing `$bad`", signer = spoiledSigner(sender, bad))
            val base = environment()
            sealed(
                "seal randomness answering `$bad`",
                env = JsEnvironment(base.nowSeconds, { _ -> bad }, base.verifySchnorr, base.freshSigner),
            )
            sealed(
                "seal one-time signer answering `$bad`",
                env = JsEnvironment(base.nowSeconds, base.randomBytesHex, base.verifySchnorr, { hostileSigner(bad) }),
            )
            sealed(
                "seal verifier answering `$bad`",
                env = JsEnvironment(base.nowSeconds, base.randomBytesHex, { _, _, _ -> bad }, base.freshSigner),
            )
        }
        for (clock in HOSTILE_CLOCKS) sealed("seal under a hostile clock", env = withClock(environment(), clock))
        for (bad in listOf("", "not a url", "wss://")) sealed("seal recipientRelayHint=`$bad`", hint = bad)

        val wrapJson = GiftWrap.seal(
            rumor = EnvelopeFixtures.rumor(),
            recipientPubkey = recipient,
            sender = EnvelopeFixtures.senderSigner(),
            ephemeral = Nip44PayloadEphemeralSigners(),
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
        ).toRecipient.json
        val reader = JsFlowFixtures.crossed(EnvelopeFixtures.recipientSigner())
        fun opened(what: String, json: String = wrapJson, me: JsSigner = reader, env: JsEnvironment = JsEnvironment()) =
            record(what, call(::open) { open(json, me, env) })

        for (text in HOSTILE_TEXTS) opened("open wrapJson=`${text.take(20)}`", json = text)
        for (bad in HOSTILE_HEX) {
            opened("open reader answering `$bad`", me = hostileSigner(bad))
            opened("open reader decrypting to `$bad`", me = spoiledSigner(reader, bad))
            opened("open verifier answering `$bad`", env = JsEnvironment(verifySchnorr = { _, _, _ -> bad }))
        }
        for (clock in HOSTILE_CLOCKS) opened("open under a hostile clock", env = JsEnvironment(nowSeconds = clock))
    }

    /**
     * T55: the same hostile inputs through [sealAsync] and [openAsync], each signer answering through a
     * synchronously completing fake, so the one answer has been delivered by the time the call returns.
     */
    @Test
    @JsName("seal_async_and_open_async_return_every_hostile_input_as_a_value")
    fun `sealAsync and openAsync return every hostile input as a value`() {
        val rumor = JsWireEvent(EnvelopeFixtures.rumor())
        val recipient = EnvelopeFixtures.recipientKey()
        val sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner())
        fun async(signer: JsSigner): JsAsyncSigner =
            JsFlowFixtures.crossedAsync(signer, JsFlowFixtures.Completions(immediate = true))
        fun environment(): JsEnvironment = JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
            ephemeral = Nip44PayloadEphemeralSigners(),
        )
        fun sealed(
            what: String,
            recipientPubkey: String = recipient,
            signer: JsSigner = sender,
            env: JsEnvironment = environment(),
            hint: String? = null,
        ) {
            val answers = mutableListOf<JsSealResult>()
            call(::sealAsync) { sealAsync(rumor, recipientPubkey, async(signer), { answers += it }, env, hint) }
            assertEquals(1, answers.size, "$what: a synchronously completing signer is answered once, at once")
            record(what, answers.single())
        }

        for (bad in HOSTILE_HEX) {
            sealed("sealAsync recipientPubkey=`$bad`", recipientPubkey = bad)
            sealed("sealAsync sender answering `$bad`", signer = hostileSigner(bad))
            sealed("sealAsync sender signing `$bad`", signer = spoiledSigner(sender, bad))
            val base = environment()
            sealed(
                "sealAsync randomness answering `$bad`",
                env = JsEnvironment(base.nowSeconds, { _ -> bad }, base.verifySchnorr, base.freshSigner),
            )
            sealed(
                "sealAsync one-time signer answering `$bad`",
                env = JsEnvironment(base.nowSeconds, base.randomBytesHex, base.verifySchnorr, { hostileSigner(bad) }),
            )
            sealed(
                "sealAsync verifier answering `$bad`",
                env = JsEnvironment(base.nowSeconds, base.randomBytesHex, { _, _, _ -> bad }, base.freshSigner),
            )
        }
        for (clock in HOSTILE_CLOCKS) sealed("sealAsync under a hostile clock", env = withClock(environment(), clock))
        for (bad in listOf("", "not a url", "wss://")) sealed("sealAsync recipientRelayHint=`$bad`", hint = bad)

        val wrapJson = GiftWrap.seal(
            rumor = EnvelopeFixtures.rumor(),
            recipientPubkey = recipient,
            sender = EnvelopeFixtures.senderSigner(),
            ephemeral = Nip44PayloadEphemeralSigners(),
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
        ).toRecipient.json
        val reader = JsFlowFixtures.crossed(EnvelopeFixtures.recipientSigner())
        fun opened(what: String, json: String = wrapJson, me: JsSigner = reader, env: JsEnvironment = JsEnvironment()) {
            val answers = mutableListOf<JsOpenResult>()
            call(::openAsync) { openAsync(json, async(me), { answers += it }, env) }
            assertEquals(1, answers.size, "$what: a synchronously completing signer is answered once, at once")
            record(what, answers.single())
        }

        for (text in HOSTILE_TEXTS) opened("openAsync wrapJson=`${text.take(20)}`", json = text)
        for (bad in HOSTILE_HEX) {
            opened("openAsync reader answering `$bad`", me = hostileSigner(bad))
            opened("openAsync reader decrypting to `$bad`", me = spoiledSigner(reader, bad))
            opened("openAsync verifier answering `$bad`", env = JsEnvironment(verifySchnorr = { _, _, _ -> bad }))
        }
        for (clock in HOSTILE_CLOCKS) opened("openAsync under a hostile clock", env = JsEnvironment(nowSeconds = clock))
    }

    /** §9.2's two checks and §8.6's acceptance door, with every string-bearing value spoiled. */
    @Test
    @JsName("the_settlement_entry_points_return_every_hostile_input_as_a_value")
    fun `the settlement entry points return every hostile input as a value`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val legalTerms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS)
        val providerReceipt = JsRumor(messages.receiptRumor(Payee.PROVIDER))
        val store = JsFlowFixtures.crossed(messages.store)
        fun verify(what: String, receipt: JsRumor = providerReceipt, seam: JsPaymentRequestStore = store, terms: JsOrderTerms = legalTerms) =
            record(what, call(::verifySettlement) { verifySettlement(receipt, seam, terms) })

        for (bad in HOSTILE_DECIMALS) {
            verify("settlement terms priceMsat=`$bad`", terms = JsOrderTerms(priceMsat = bad))
            verify("settlement terms expirationSeconds=`$bad`", terms = JsOrderTerms(legalTerms.priceMsat, legalTerms.feeBasisPoints, bad, legalTerms.deliverBySeconds))
            verify("settlement terms deliverBySeconds=`$bad`", terms = JsOrderTerms(legalTerms.priceMsat, legalTerms.feeBasisPoints, legalTerms.expirationSeconds, bad))
        }
        verify("an empty store", seam = JsPaymentRequestStore())
        verify("a chat offered as a receipt", receipt = JsRumor(ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())))

        val awaiting = JsOrder(
            OrderFixtures.orders()[OrderState.AWAITING_PAYMENT] ?: fail("the fixture chain must reach awaiting_payment"),
        )
        val feeReceipt = JsRumor(messages.receiptRumor(Payee.FEE))
        val legalPoints = messages.earlierPoints.map { JsFlowFixtures.crossed(it) }
        fun hostilePoints(): List<Array<JsFeeTermSighting>> =
            listOf("", "proposal", "ORDER_PROPOSAL ").map { arrayOf(JsFeeTermSighting(it)) + legalPoints } +
                HOSTILE_DECIMALS.map { bad -> arrayOf(JsFeeTermSighting("ORDER_PROPOSAL", arrayOf("fee", bad))) } +
                HOSTILE_HEX.map { bad -> arrayOf(JsFeeTermSighting("ORDER_PROPOSAL", arrayOf("fee", "250", bad))) }
        for (points in hostilePoints()) {
            record("fee receipt with hostile points", call(::verifyFeeReceipt) { verifyFeeReceipt(feeReceipt, store, awaiting, points) })
        }

        val signed = listOf(
            FeeTermSighting.onProposal(messages.proposal),
            FeeTermSighting.onAcceptance(messages.acceptance),
        ).map { JsFlowFixtures.crossed(it) }.toTypedArray()
        fun accept(
            what: String,
            payee: Payee = Payee.PROVIDER,
            providerPubkey: String = SettlementFixtures.provider(messages.index),
            env: JsEnvironment = JsFlowFixtures.crossedEnvironment(clock = FakeClock(SettlementFixtures.ACCEPTED_AT)),
            points: Array<JsFeeTermSighting> = signed,
        ) = record(
            what,
            call(::acceptPaymentRequest) {
                acceptPaymentRequest(
                    JsRumor(messages.requestRumors.getValue(payee)),
                    JsRumor(messages.proposalRumor),
                    JsRumor(messages.acceptanceRumor),
                    providerPubkey,
                    JsFlowFixtures.CrossedStore().seam,
                    env,
                    points,
                )
            },
        )
        for (bad in HOSTILE_HEX) accept("acceptance providerPubkey=`$bad`", providerPubkey = bad)
        for (clock in HOSTILE_CLOCKS) accept("acceptance under a hostile clock", env = JsEnvironment(nowSeconds = clock))
        for (points in hostilePoints()) accept("fee acceptance with hostile points", payee = Payee.FEE, points = points)

        // T65: §10.3's release after payment, with every string-bearing value spoiled and every
        // settlement it must refuse.
        val providerSettlement = JsSettlement(OrderFixtures.receipt(Payee.PROVIDER))
        val legalEnvelope = JsBoundaryFixtures.crossed(ReleaseFixtures.providerEnvelope())
        val commitmentRumor = JsRumor(ReleaseFixtures.commitmentRumor())
        fun release(
            what: String,
            envelope: JsRumorEnvelope = legalEnvelope,
            commitment: JsRumor = commitmentRumor,
            settlement: JsSettlement = providerSettlement,
            key: String = ReleaseFixtures.KEY_HEX,
            nonce: String = ReleaseFixtures.NONCE_HEX,
        ) = record(
            what,
            call(::buildReleaseAfterPayment) { buildReleaseAfterPayment(envelope, commitment, settlement, key, nonce) },
        )
        for (bad in HOSTILE_HEX) {
            release("release-after-payment decryptionKeyHex=`$bad`", key = bad)
            release("release-after-payment decryptionNonceHex=`$bad`", nonce = bad)
            release(
                "release-after-payment authorPubkey=`$bad`",
                envelope = JsRumorEnvelope(bad, legalEnvelope.createdAtSeconds, legalEnvelope.counterparties),
            )
        }
        for (bad in HOSTILE_DECIMALS) {
            release(
                "release-after-payment createdAtSeconds=`$bad`",
                envelope = JsRumorEnvelope(legalEnvelope.authorPubkey, bad, legalEnvelope.counterparties),
            )
        }
        release("release-after-payment on an unverified payment", settlement = JsSettlement(ReleaseFixtures.bitcoinSettlement()))
        release("release-after-payment on the fee payment", settlement = JsSettlement(OrderFixtures.receipt(Payee.FEE)))
        release(
            "release-after-payment on another order's payment",
            settlement = JsSettlement(OrderFixtures.receipt(Payee.PROVIDER, index = OrderFixtures.OTHER_ORDER_INDEX)),
        )
        release(
            "release-after-payment on a stranger's commitment",
            commitment = JsRumor(ReleaseFixtures.commitmentRumor(SettlementFixtures.stranger(OrderFixtures.ORDER_INDEX))),
        )
        release("release-after-payment on a receipt offered as a commitment", commitment = providerReceipt)
        release(
            "release-after-payment on a chat offered as a commitment",
            commitment = JsRumor(ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())),
        )
    }

    /** §11.2's two doors, with every string-bearing value of the machine, the terms and the event spoiled. */
    @Test
    @JsName("the_order_entry_points_return_every_hostile_input_as_a_value")
    fun `the order entry points return every hostile input as a value`() {
        val environment = JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES))
        val machine = JsOrderMachine(environment)
        val orderId = OrderFixtures.ORDER_ID.toHex()
        val terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS)
        fun opened(what: String, at: JsOrderMachine = machine, id: String = orderId, with: JsOrderTerms = terms, createdAt: String? = null) =
            record(what, call(::openOrder) { openOrder(at, id, with, createdAt) })

        for (bad in HOSTILE_HEX) opened("openOrder orderIdHex=`$bad`", id = bad)
        for (bad in HOSTILE_DECIMALS) {
            opened("openOrder priceMsat=`$bad`", with = JsOrderTerms(priceMsat = bad))
            opened("openOrder createdAtSeconds=`$bad`", createdAt = bad)
            opened("openOrder releaseTimeoutSeconds=`$bad`", at = JsOrderMachine(environment, releaseTimeoutSeconds = bad))
            opened(
                "openOrder verificationTimeoutSeconds=`$bad`",
                at = JsOrderMachine(environment, verificationTimeoutSeconds = bad),
            )
        }
        for (clock in HOSTILE_CLOCKS) opened("openOrder under a hostile clock", at = JsOrderMachine(JsEnvironment(nowSeconds = clock)))

        val proposed = JsOrder(OrderMachine().open(OrderEvent.Proposal(OrderFixtures.ORDER_ID, OrderFixtures.TERMS)))
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val blob = DeliveryFixtures.blob()
        val commitment = JsBoundaryFixtures.crossed(blob.commitment)
        val served = encodeLowerHex(blob.served)
        fun step(what: String, event: JsOrderEvent, at: JsOrderMachine = machine) =
            record(what, call(::stepOrder) { stepOrder(at, proposed, event) })

        for (bad in listOf("", "settled", "PROPOSAL", " proposal")) step("event type=`$bad`", JsOrderEvent(type = bad))
        for (bad in listOf("", "ACCEPTED", "part-exchanged")) {
            step("status_update statusToken=`$bad`", JsOrderEvent(type = "status_update", statusToken = bad, fromParty = "PROVIDER"))
            step("status_update fromParty=`$bad`", JsOrderEvent(type = "status_update", statusToken = "accepted", fromParty = bad))
            step("delivery_refused deliveryFailure=`$bad`", JsOrderEvent(type = "delivery_refused", deliveryFailure = bad))
        }
        for (bad in HOSTILE_DECIMALS) {
            step(
                "status_update createdAtSeconds=`$bad`",
                JsOrderEvent(type = "status_update", createdAtSeconds = bad, statusToken = "accepted", fromParty = "PROVIDER"),
            )
            step("proposal terms priceMsat=`$bad`", JsOrderEvent(type = "proposal", orderIdHex = orderId, terms = JsOrderTerms(priceMsat = bad)))
            step(
                "delivery_verified maxServedBytes=`$bad`",
                JsOrderEvent(type = "delivery_verified", commitment = commitment, servedBytesHex = served, plaintextBytesHex = served, maxServedBytes = bad),
            )
            step(
                "delivery_committed sizeBytes=`$bad`",
                JsOrderEvent(
                    type = "delivery_committed", fromParty = "PROVIDER",
                    commitment = JsCommitment(commitment.xHex, commitment.oxHex, commitment.mimeType, bad),
                ),
            )
            step(
                "clock_checked under a machine timeout of `$bad`",
                JsOrderEvent(type = "clock_checked"),
                at = JsOrderMachine(environment, releaseTimeoutSeconds = bad),
            )
        }
        for (bad in HOSTILE_HEX) {
            step("proposal orderIdHex=`$bad`", JsOrderEvent(type = "proposal", orderIdHex = bad, terms = terms))
            step(
                "acceptance_received providerPubkey=`$bad`",
                JsOrderEvent(
                    type = "acceptance_received", fromParty = "PROVIDER",
                    proposalRumor = JsRumor(messages.proposalRumor), acceptanceRumor = JsRumor(messages.acceptanceRumor),
                    providerPubkey = bad,
                ),
            )
            step(
                "delivery_verified servedBytesHex=`$bad`",
                JsOrderEvent(type = "delivery_verified", commitment = commitment, servedBytesHex = bad, plaintextBytesHex = served),
            )
            step(
                "delivery_verified plaintextBytesHex=`$bad`",
                JsOrderEvent(type = "delivery_verified", commitment = commitment, servedBytesHex = served, plaintextBytesHex = bad),
            )
            step(
                "deliverable_released orderIdHex=`$bad`",
                JsOrderEvent(
                    type = "deliverable_released", fromParty = "PROVIDER", orderIdHex = bad,
                    release = JsBoundaryFixtures.crossed(RELEASE.release ?: fail("the release's values")),
                ),
            )
        }
        for (clock in HOSTILE_CLOCKS) {
            step("clock_checked under a hostile clock", JsOrderEvent(type = "clock_checked"), at = JsOrderMachine(JsEnvironment(nowSeconds = clock)))
        }
    }

    /**
     * The five §6.1 and §7 message decoders take a handle and no string, so each is handed a rumor it is not
     * for — §7.4's `kind:14`, which no bound decoder can be given, and a bound message of the wrong
     * `type`.
     */
    @Test
    @JsName("the_message_decoders_return_a_rumor_they_are_not_for_as_a_value")
    fun `the message decoders return a rumor they are not for as a value`() {
        val chat = JsRumor(ChannelFixtures.attribute(NenyaKind.CHAT, emptyList()))
        val delivery = DeliveryMessageFixtures.pairs(1).single()
        val commitment = JsRumor(
            DeliveryMessageFixtures.bound(delivery.commitmentTags, NenyaKind.ORDER_MESSAGE, delivery.index),
        )
        val proposal = JsRumor(OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0).proposalRumor)

        for ((what, rumor) in listOf("a chat" to chat, "a commitment" to commitment)) {
            val decoded = call(::decodeProposal) { decodeProposal(rumor) }
            record("decodeProposal of $what", decoded.ok, decoded.kind, decoded.reason, decoded.reasonVocabulary, decoded.detail)
            val update = call(::decodeStatusUpdate) { decodeStatusUpdate(rumor) }
            record("decodeStatusUpdate of $what", update.ok, update.kind, update.reason, update.reasonVocabulary, update.detail)
            val release = call(::decodeRelease) { decodeRelease(rumor) }
            record("decodeRelease of $what", release.ok, release.kind, release.reason, release.reasonVocabulary, release.detail)
            val bid = call(::decodePrivateBid) { decodePrivateBid(rumor) }
            record("decodePrivateBid of $what", bid.ok, bid.kind, bid.reason, bid.reasonVocabulary, bid.detail)
            val fee = call(::privateBidFeeSighting) { privateBidFeeSighting(rumor) }
            assertNull(fee.sighting, "privateBidFeeSighting of $what: a refusal carries no sighting")
            record("privateBidFeeSighting of $what", fee.ok, fee.kind, fee.reason, fee.reasonVocabulary, fee.detail)
        }
        for ((what, rumor) in listOf("a chat" to chat, "a proposal" to proposal)) {
            val decoded = call(::decodeCommitment) { decodeCommitment(rumor) }
            record("decodeCommitment of $what", decoded.ok, decoded.kind, decoded.reason, decoded.reasonVocabulary, decoded.detail)
        }
    }

    /** §10.4 step 1, §6's bid builder and §7.6's verdict, with every string-bearing value spoiled. */
    @Test
    @JsName("served_bytes_the_bid_builder_and_the_acceptance_check_return_hostile_input_as_values")
    fun `served bytes the bid builder and the acceptance check return hostile input as values`() {
        val blob = DeliveryFixtures.blob()
        val commitment = JsBoundaryFixtures.crossed(blob.commitment)
        val served = encodeLowerHex(blob.served)
        fun bytes(what: String, with: JsCommitment = commitment, hex: String = served, bound: String? = null) {
            val answer = call(::verifyServedBytes) { verifyServedBytes(with, hex, bound) }
            record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)
        }
        for (bad in HOSTILE_HEX) {
            bytes("servedBytesHex=`$bad`", hex = bad)
            bytes("commitment xHex=`$bad`", with = JsCommitment(bad, commitment.oxHex, commitment.mimeType, commitment.sizeBytes))
            bytes("commitment oxHex=`$bad`", with = JsCommitment(commitment.xHex, bad, commitment.mimeType, commitment.sizeBytes))
        }
        for (bad in HOSTILE_DECIMALS) {
            bytes("maxServedBytes=`$bad`", bound = bad)
            bytes("commitment sizeBytes=`$bad`", with = JsCommitment(commitment.xHex, commitment.oxHex, commitment.mimeType, bad))
        }
        bytes("commitment mimeType=``", with = JsCommitment(commitment.xHex, commitment.oxHex, "", commitment.sizeBytes))

        fun bid(
            what: String,
            bidderPubkey: String = BID.bidderPubkey,
            createdAtSeconds: String = BID.createdAtSeconds,
            listingCoordinate: String = BID.listingCoordinate,
            priceMsat: String = BID.priceMsat,
            feeRecipient: String? = BID.feeRecipient,
            feeBasisPoints: Int? = BID.feeBasisPoints,
            deliverBySeconds: String? = BID.deliverBySeconds,
            expirationSeconds: String? = BID.expirationSeconds,
            publishedAtSeconds: String? = BID.publishedAtSeconds,
        ) = record(
            what,
            call(::buildBid) {
                buildBid(
                    JsAuthoredBid(
                        bidderPubkey = bidderPubkey,
                        createdAtSeconds = createdAtSeconds,
                        listingCoordinate = listingCoordinate,
                        priceMsat = priceMsat,
                        content = BID.content,
                        feeBasisPoints = feeBasisPoints,
                        feeRecipient = feeRecipient,
                        deliverBySeconds = deliverBySeconds,
                        expirationSeconds = expirationSeconds,
                        topics = BID.topics,
                        nenyaVersion = BID.nenyaVersion,
                        alt = BID.alt,
                        publishedAtSeconds = publishedAtSeconds,
                    ),
                )
            },
        )
        for (bad in HOSTILE_HEX) {
            bid("bid bidderPubkey=`$bad`", bidderPubkey = bad)
            bid("bid feeRecipient=`$bad`", feeRecipient = bad, feeBasisPoints = 250)
        }
        for (bad in HOSTILE_DECIMALS) {
            bid("bid createdAtSeconds=`$bad`", createdAtSeconds = bad)
            bid("bid priceMsat=`$bad`", priceMsat = bad)
            bid("bid deliverBySeconds=`$bad`", deliverBySeconds = bad)
            bid("bid expirationSeconds=`$bad`", expirationSeconds = bad)
            bid("bid publishedAtSeconds=`$bad`", publishedAtSeconds = bad)
        }
        for (bad in listOf("", "not-a-coordinate", "30402:short:d", "x:y:z", "30402::d")) {
            bid("bid listingCoordinate=`$bad`", listingCoordinate = bad)
        }

        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        for (bad in HOSTILE_HEX) {
            val check = call(::checkAcceptance) {
                checkAcceptance(JsRumor(messages.proposalRumor), JsRumor(messages.acceptanceRumor), bad)
            }
            record(
                "checkAcceptance providerPubkey=`$bad`",
                check.kind != "refused", check.kind, check.reason, check.reasonVocabulary, check.detail,
            )
        }
    }

    /**
     * §5.6's and §4.6's deadlines, T51's and T52's writers against a listing, and §5.5's re-filter.
     *
     * The three deadline answers take a handle and a `JsEnvironment` and nothing else, so their hostile
     * value is the clock: every one of [HOSTILE_CLOCKS] must answer `CANNOT_SAY` for a deadline that is
     * there, and never `ACTIVE` or `EXPIRED`.
     */
    @Test
    @JsName("the_deadline_and_board_entry_points_return_every_hostile_input_as_a_value")
    fun `the deadline and board entry points return every hostile input as a value`() {
        val expiring = JsListing(listing(expiring = true))
        val openEnded = JsListing(listing(expiring = false))
        val bidTags = BidFixtures.minimalBid()
        bidTags += listOf(listOf("expiration", (BidFixtures.CREATED_AT + 10_000L).toString()))
        val bid = JsBid(BidFixtures.decode(bidTags))
        // §8.4's bid point takes a decoded bid and nothing else, so it has no hostile value left to be
        // handed: the hostile bid is `decodeBid`'s refusal above. It must still answer, not throw.
        assertEquals(FeeTermPoint.BID.name, call(::bidFeeSighting) { bidFeeSighting(bid) }.point)

        for (clock in HOSTILE_CLOCKS) {
            val environment = JsEnvironment(nowSeconds = clock)
            assertEquals(
                ListingActivity.CANNOT_SAY.name,
                call(::listingActivity) { listingActivity(expiring, environment) },
                "a silent or negative clock cannot say whether a listing has expired",
            )
            assertEquals(
                ListingActivity.CANNOT_SAY.name,
                call(::bidActivity) { bidActivity(bid, environment) },
                "a silent or negative clock cannot say whether a bid has expired",
            )
            for (against in listOf<JsBid?>(null, bid)) {
                val check = call(::checkDeadlines) { checkDeadlines(expiring, against, environment) }
                assertFalse(check.permitted, "a deadline the clock could not read permits nothing")
                assertEquals("refused", check.kind)
                val reason = check.reason ?: fail("a refusal names its reason")
                assertNotNull(check.side, "a refusal names its side")
                reasons += reason
            }
        }

        val case = PROPOSAL
        val envelope = JsBoundaryFixtures.crossed(case.envelope)
        val order = (case.order ?: fail("the proposal must carry an order id")).toHex()
        val terms = JsBoundaryFixtures.crossed(case.terms ?: fail("the proposal must carry terms"))
        fun propose(
            what: String,
            against: JsListing = openEnded,
            env: JsEnvironment = JsEnvironment(nowSeconds = clockSaying((DEADLINE - 1).toString())),
            with: JsRumorEnvelope = envelope,
            id: String = order,
            on: JsOrderTerms = terms,
            feeRecipient: String? = case.feeRecipient,
            amountSat: String? = null,
        ) = record(
            what,
            call(::buildProposalAgainst) { buildProposalAgainst(against, env, with, id, on, feeRecipient, amountSat) },
        )
        for (clock in HOSTILE_CLOCKS) propose("proposal against under a hostile clock", against = expiring, env = JsEnvironment(nowSeconds = clock))
        for (hostile in hostileEnvelopes(envelope)) propose("proposal against envelope", with = hostile)
        for (bad in HOSTILE_HEX) {
            propose("proposal against orderIdHex=`$bad`", id = bad)
            propose("proposal against feeRecipient=`$bad`", feeRecipient = bad)
        }
        for (bad in HOSTILE_DECIMALS) {
            propose("proposal against priceMsat=`$bad`", on = JsOrderTerms(priceMsat = bad))
            propose("proposal against amountSat=`$bad`", amountSat = bad)
        }
        assertTrue("LISTING_CANNOT_SAY" in reasons, "§5.6's refusal under a silent clock: $reasons")

        // A proposal carrying its own `expiration`, so T57's deadline for acceptance has a value to refuse on.
        val (proposalRumor, proposalExpiration) = RumorWriterFixtures.cases(64)
            .filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }
            .firstNotNullOfOrNull { candidate ->
                val expiration = candidate.terms?.expiration ?: return@firstNotNullOfOrNull null
                val built = ListingOrderWriter.proposal(
                    expiring.listing, FakeClock(DEADLINE - 1), candidate.envelope, candidate.order!!,
                    candidate.terms!!, candidate.feeRecipient, candidate.amountSat,
                )
                (built as? ListingOrderBuild.Built)?.let { JsRumor(RumorWriterFixtures.attribute(it.event)) to expiration }
            } ?: fail("one proposal carrying an `expiration` must build against the expiring listing")
        // Before the proposal's own deadline, so each hostile envelope is what the agreement refuses on.
        val beforeEither = JsEnvironment(nowSeconds = clockSaying((proposalExpiration - 1).toString()))
        val providerEnvelope = JsRumorEnvelope(
            authorPubkey = SettlementFixtures.provider(0),
            createdAtSeconds = (DEADLINE - 1).toString(),
            counterparties = arrayOf(arrayOf(proposalRumor.rumor.author)),
        )
        fun agree(what: String, env: JsEnvironment, with: JsRumorEnvelope = providerEnvelope) = record(
            what,
            call(::buildAgreementAgainst) { buildAgreementAgainst(expiring, env, with, proposalRumor) },
        )
        for (clock in HOSTILE_CLOCKS) agree("agreement against under a hostile clock", JsEnvironment(nowSeconds = clock))
        for (hostile in hostileEnvelopes(providerEnvelope)) agree("agreement against envelope", beforeEither, hostile)
        agree(
            "agreement against at the proposal's own expiration",
            JsEnvironment(nowSeconds = clockSaying(proposalExpiration.toString())),
        )
        assertTrue("PROPOSAL_EXPIRED" in reasons, "§7.5's deadline for acceptance: $reasons")

        // T61's proposal from the chosen bid. `bid` is scoped to the request authored at index 1, not to
        // `expiring`, so it gets its own expiring copy of that request: on a request the buyer is the
        // listing's author and the provider the bidder.
        val requestTags = ListingFixtures.minimal(NenyaKind.REQUEST)
        requestTags += listOf(listOf("expiration", DEADLINE.toString()))
        val chosenOn = JsListing(ListingFixtures.decode(requestTags, NenyaKind.REQUEST, index = 1))
        val buyerEnvelope = JsRumorEnvelope(
            authorPubkey = bid.bid.listingAuthorPubkey,
            createdAtSeconds = (DEADLINE - 1).toString(),
            counterparties = arrayOf(arrayOf(bid.bid.bidderPubkey)),
        )
        fun forBid(
            what: String,
            env: JsEnvironment = JsEnvironment(nowSeconds = clockSaying((DEADLINE - 1).toString())),
            with: JsRumorEnvelope = buyerEnvelope,
            id: String = order,
            acceptBy: String? = null,
            chosen: JsBid = bid,
        ) = record(
            what,
            call(::buildProposalForBid) { buildProposalForBid(chosenOn, chosen, env, with, id, acceptBy) },
        )
        for (clock in HOSTILE_CLOCKS) forBid("proposal for bid under a hostile clock", env = JsEnvironment(nowSeconds = clock))
        for (hostile in hostileEnvelopes(buyerEnvelope)) forBid("proposal for bid envelope", with = hostile)
        for (bad in HOSTILE_HEX) forBid("proposal for bid orderIdHex=`$bad`", id = bad)
        for (bad in HOSTILE_DECIMALS) forBid("proposal for bid acceptBy=`$bad`", acceptBy = bad)
        forBid(
            "proposal for bid sealed by the provider",
            with = JsRumorEnvelope(
                authorPubkey = bid.bid.bidderPubkey,
                createdAtSeconds = (DEADLINE - 1).toString(),
                counterparties = arrayOf(arrayOf(bid.bid.listingAuthorPubkey)),
            ),
        )
        assertTrue("WRONG_PARTIES" in reasons, "decision H's parties: $reasons")
        // T62: the same bid with a `deliver_by` the default reading, `DEADLINE - 1`, has already reached.
        val lateTags = BidFixtures.minimalBid()
        lateTags += listOf(listOf("deliver_by", (DEADLINE - 100L).toString()))
        val late = JsBid(BidFixtures.decode(lateTags))
        forBid("proposal for bid past its deliver_by", chosen = late)
        forBid("proposal for bid at its acceptBy", acceptBy = (DEADLINE - 1).toString())
        assertTrue("DELIVERY_PASSED" in reasons, "§6's deliver_by against the clock: $reasons")

        val kinds = arrayOf(NenyaKind.REQUEST)
        for (bad in HOSTILE_HEX + HOSTILE_DECIMALS) {
            for (query in listOf(
                JsBoardQuery(kinds, topics = arrayOf(bad)),
                JsBoardQuery(kinds, mimeType = bad),
                JsBoardQuery(kinds, directedTo = bad),
            )) {
                assertFalse(
                    call(::matchesBoard) { matchesBoard(openEnded, query) },
                    "`$bad` is a condition the minimal request does not meet, and the answer is a value",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The anchors.
    // -----------------------------------------------------------------------------------------

    /**
     * The sweeps above reach every pinned entry point, and many arms of many ladders, not one.
     *
     * One `@Test`, because a fresh instance runs each test: recording in one and checking in another
     * would check an empty set. So every sweep runs again here, the set [call] recorded is held equal
     * to [SWEPT_ENTRY_POINTS], and the breadth of the refusals reached is pinned. The vocabularies are
     * pinned too, because an arm wired to the wrong rejection enum is a caller branching on the wrong
     * thing.
     */
    @Test
    @JsName("the_hostile_sweep_reaches_every_entry_point_and_many_refusals_across_many_vocabularies")
    fun `the hostile sweep reaches every entry point and many refusals across many vocabularies`() {
        `every spoiled listing field comes back as a value`()
        `every spoiled envelope field comes back as a value`()
        `spoiled order terms come back as values including section 7 5's inverted deadlines`()
        `spoiled delivery fields come back as values`()
        `spoiled settlement fields come back as values`()
        `every builder's envelope order id and fee recipient come back as values`()
        `neither decoder throws for any text`()
        `seal and open return every hostile input as a value`()
        `sealAsync and openAsync return every hostile input as a value`()
        `the settlement entry points return every hostile input as a value`()
        `the order entry points return every hostile input as a value`()
        `the message decoders return a rumor they are not for as a value`()
        `served bytes the bid builder and the acceptance check return hostile input as values`()
        `the deadline and board entry points return every hostile input as a value`()

        assertSweptExactly(returned)

        // 15 and 6 over T42's thirteen entry points; 59 and 15, measured, once T54 swept all thirty-three;
        // 61, measured, once T59's `decodePrivateBid` joined the sweep; 62, measured, once T61's
        // `buildProposalForBid` added `WRONG_PARTIES`; 63, measured, once T62's added `DELIVERY_PASSED`;
        // 67, measured, once T65's `buildReleaseAfterPayment` added its four `RELEASE_…` refusals.
        assertTrue(
            reasons.size >= 67,
            "only ${reasons.size} distinct refusal(s) were reached — $reasons — which is not the " +
                "breadth these cases are written for",
        )
        assertTrue(
            vocabularies.size >= 15,
            "only $vocabularies were reached; the entry points' ladders name more rejection enums than that",
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
            // T54's: the vocabularies only the twenty later entry points can answer in.
            "EnvelopeRejection",
            "SettlementRejection",
            "TransitionRejection",
            "DeliveryRejection",
            "ChannelRejection",
            "BidRejection",
            "DeadlineRefusal",
            // T57's: §7.5's deadline for acceptance, answered only by `buildAgreementAgainst`.
            "ProposalDeadlineRefusal",
        )) {
            assertTrue(vocabulary in vocabularies, "$vocabulary was not reached: $vocabularies")
        }
    }

    /**
     * The pinned-set check fails for a sweep missing any one entry point, and for one recording a name
     * the pin does not hold — each of [SWEPT_ENTRY_POINTS] dropped in turn.
     */
    @Test
    @JsName("the_pinned_set_check_fails_for_a_sweep_missing_one_entry_point")
    fun `the pinned-set check fails for a sweep missing one entry point`() {
        assertSweptExactly(SWEPT_ENTRY_POINTS)
        for (name in SWEPT_ENTRY_POINTS) {
            assertFails("a sweep that never returned from `$name` must fail the check") {
                assertSweptExactly(SWEPT_ENTRY_POINTS - name)
            }
        }
        assertFails("a name the pin does not hold must fail the check") {
            assertSweptExactly(SWEPT_ENTRY_POINTS + "capabilities")
        }
    }

    /** A probe whose call throws is never recorded, and one whose call returns is. */
    @Test
    @JsName("a_probe_whose_call_throws_is_never_recorded")
    fun `a probe whose call throws is never recorded`() {
        assertFailsWith<IllegalStateException> { call(::probeThatThrows) { probeThatThrows() } }
        assertFalse("probeThatThrows" in returned, "a call that threw was recorded as having returned: $returned")

        call(::probeThatReturns) { probeThatReturns() }
        assertEquals(setOf("probeThatReturns"), returned, "the recorder records the reference's own name")
    }

    /** Stands in for an entry point that throws. */
    private fun probeThatThrows(): JsBuildResult =
        throw IllegalStateException("a probe standing in for an entry point that throws")

    /** Stands in for an entry point that returns. */
    private fun probeThatReturns(): String = "returned"

    /** [recorded] is exactly [SWEPT_ENTRY_POINTS]: nothing missing, nothing extra. */
    private fun assertSweptExactly(recorded: Set<String>) {
        assertEquals(
            SWEPT_ENTRY_POINTS,
            recorded,
            "the hostile sweep must return from every pinned entry point and no other. Missing: " +
                "${SWEPT_ENTRY_POINTS - recorded}; not pinned: ${recorded - SWEPT_ENTRY_POINTS}",
        )
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
        assertNull(answer.event, "$what: a refusal must carry no event")
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)
    }

    /** [record] for the other result shapes, which publish the same five fields. */
    private fun record(what: String, answer: JsSealResult) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    private fun record(what: String, answer: JsOpenResult) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    private fun record(what: String, answer: JsSettlementResult) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    private fun record(what: String, answer: JsAcceptedRequestResult) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    private fun record(what: String, answer: JsOrderResult) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    private fun record(what: String, answer: JsOrderOutcome) =
        record(what, answer.ok, answer.kind, answer.reason, answer.reasonVocabulary, answer.detail)

    /** The five fields every result publishes: an answer, or a refusal naming its reason and vocabulary. */
    private fun record(what: String, ok: Boolean, kind: String, reason: String?, vocabulary: String?, detail: String?) {
        if (ok) {
            assertNull(reason, "$what: an answer must name no reason")
            return
        }
        assertEquals("refused", kind, "$what: `kind` must discriminate with `ok`")
        val named = reason ?: fail("$what: a refusal must name a reason")
        val from = vocabulary ?: fail("$what: a refusal must name its vocabulary")
        assertTrue(named.isNotEmpty(), "$what: the reason must not be empty")
        assertNotNull(detail, "$what: a refusal must carry a sentence a caller can log")
        reasons += named
        vocabularies += from
    }
}
