package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.ListingBuild
import dev.eryalabs.nenya.listing.ListingRejection
import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.settlement.Bolt11Examples
import dev.eryalabs.nenya.settlement.Bolt11Invoice
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §12 item 2's automatic check in the two public-event writers: "any invoice string MUST NOT appear in
 * a public event; implementations SHOULD check this automatically over every event they can construct."
 *
 * Every invoice here is one of the vendored BOLT-11 document's lowercase valid examples (decision
 * **D**), read through `Bolt11Examples`; none is typed. The one-character-changed controls swap the
 * last data character for another bech32 character, so the checksum no longer verifies and the run
 * is no invoice — the paired positive control that shows the check refuses invoices and not merely
 * long bech32-looking strings.
 *
 * Refusals are asserted by `reason` and `tag`, and by the matched text being absent from `tag`,
 * `detail` and `toString()` (STOP RULE 14) — never by the wording of `detail`.
 */
class PublicEventInvoiceTest {

    private companion object {

        /** The vendored document's lowercase valid invoices, each confirmed parseable here first. */
        val INVOICES: List<String> = Bolt11Examples.extract()
            .filter { it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase() }
            .map { it.invoice }

        /** The first of [INVOICES], for the controls that need only one. */
        val INVOICE: String get() = INVOICES.first()

        /** §12 item 2's prefix a wallet link carries, which ends a run rather than hiding one. */
        const val LIGHTNING_URI: String = "lightning:"

        /** [invoice] with its last character replaced by another bech32 character: no longer an invoice. */
        fun broken(invoice: String): String {
            val last = invoice.last()
            val other = Bolt11Reference.BECH32_ALPHABET.first { it != last }
            return invoice.dropLast(1) + other
        }

        /**
         * Tag names carrying [INVOICE] glued inside a longer run, lowercase and uppercased: neither
         * run is an invoice (its human-readable part no longer starts `ln`), so the scan does not
         * match the name itself — and it must still not name it.
         */
        fun gluedNames(): List<String> = listOf("x$INVOICE", "X${INVOICE.uppercase()}")

        fun offer(listing: AuthoredListing): ListingBuild = ListingWriter.offer(listing)

        fun listingWith(
            summary: String? = null,
            content: String = "",
            extraTags: List<List<String>> = emptyList(),
        ): AuthoredListing {
            val minimal = ListingWriterFixtures.minimal(NenyaKind.OFFER)
            return AuthoredListing(
                authorPubkey = minimal.authorPubkey,
                createdAt = minimal.createdAt,
                dValue = minimal.dValue,
                title = minimal.title,
                price = minimal.price,
                content = content,
                summary = summary,
                extraTags = extraTags,
            )
        }

        fun bidWith(content: String = "", extraTags: List<List<String>> = emptyList()): AuthoredBid {
            val minimal = BidWriterFixtures.minimal()
            return AuthoredBid(
                bidderPubkey = minimal.bidderPubkey,
                createdAt = minimal.createdAt,
                listing = minimal.listing,
                price = minimal.price,
                content = content,
                extraTags = extraTags,
            )
        }

        fun listingRefused(build: ListingBuild, what: String): ListingBuild.Refused =
            ListingWriterFixtures.refused(build, what)

        /** STOP RULE 14: neither spelling of the invoice reaches any string the refusal exposes. */
        fun assertNoEcho(tag: String?, detail: String, printed: String, invoice: String, what: String) {
            for (form in listOf(invoice, invoice.uppercase())) {
                assertFalse(tag?.contains(form) == true, "$what: the refusal's tag holds the invoice")
                assertFalse(detail.contains(form), "$what: the refusal's detail holds the invoice")
                assertFalse(printed.contains(form), "$what: the refusal's toString() holds the invoice")
            }
        }
    }

    @Test
    @JsName("every_fixture_invoice_is_one_the_settlement_reader_accepts")
    fun `every fixture invoice is one the settlement reader accepts`() {
        assertTrue(INVOICES.size >= 10, "the vendored document has ${INVOICES.size} lowercase valid invoices")
        for (invoice in INVOICES) Bolt11Invoice.parse(Bolt11Reference.recognise(invoice))
    }

    // ---------------------------------------------------------------------------------------------
    // ListingWriter.
    // ---------------------------------------------------------------------------------------------

    @Test
    @JsName("an_offer_whose_summary_is_an_invoice_is_refused_naming_summary")
    fun `an offer whose summary is an invoice is refused naming summary`() {
        for (invoice in INVOICES) {
            val refused = listingRefused(offer(listingWith(summary = invoice)), "an invoice as `summary`")
            assertEquals(ListingRejection.INVOICE_IN_PUBLIC_EVENT, refused.reason)
            assertEquals("summary", refused.tag)
            assertNull(refused.tagReason)
            assertNoEcho(refused.tag, refused.detail, refused.toString(), invoice, "listing summary")
        }
        ListingWriterFixtures.built(offer(listingWith(summary = broken(INVOICE))), "one character changed")
    }

    @Test
    @JsName("a_listing_s_content_carrying_an_invoice_uri_uppercased_is_refused_with_no_tag")
    fun `a listing's content carrying an invoice URI uppercased is refused with no tag`() {
        for (form in listOf(INVOICE, INVOICE.uppercase())) {
            val content = "pay me at $LIGHTNING_URI$form please"
            for (kind in ListingWriterFixtures.KINDS) {
                val base = ListingWriterFixtures.minimal(kind)
                val listing = AuthoredListing(
                    authorPubkey = base.authorPubkey,
                    createdAt = base.createdAt,
                    dValue = base.dValue,
                    title = base.title,
                    price = base.price,
                    alt = base.alt,
                    content = content,
                )
                val refused = listingRefused(ListingWriterFixtures.build(kind, listing), "kind:$kind content")
                assertEquals(ListingRejection.INVOICE_IN_PUBLIC_EVENT, refused.reason)
                assertNull(refused.tag)
                assertNoEcho(refused.tag, refused.detail, refused.toString(), INVOICE, "listing content")
            }
        }
        ListingWriterFixtures.built(
            offer(listingWith(content = LIGHTNING_URI + broken(INVOICE).uppercase())),
            "one character changed",
        )
    }

    @Test
    @JsName("a_listing_extension_tag_carrying_an_invoice_as_name_or_value_is_refused")
    fun `a listing extension tag carrying an invoice as name or value is refused`() {
        val asValue = listingRefused(
            offer(listingWith(extraTags = listOf(listOf("client", "x", LIGHTNING_URI + INVOICE)))),
            "an invoice as an extension tag's value",
        )
        assertEquals(ListingRejection.INVOICE_IN_PUBLIC_EVENT, asValue.reason)
        assertEquals("client", asValue.tag)
        assertNoEcho(asValue.tag, asValue.detail, asValue.toString(), INVOICE, "listing extension value")

        val asName = listingRefused(
            offer(listingWith(extraTags = listOf(listOf(INVOICE, "x")))),
            "an invoice as an extension tag's name",
        )
        assertEquals(ListingRejection.INVOICE_IN_PUBLIC_EVENT, asName.reason)
        assertNull(asName.tag)
        assertNoEcho(asName.tag, asName.detail, asName.toString(), INVOICE, "listing extension name")

        // A name holding the invoice inside a longer run is no invoice itself, so the value is what
        // matches — and naming the tag would echo the invoice. Both cases of the glued name.
        for (name in gluedNames()) {
            val glued = listingRefused(
                offer(listingWith(extraTags = listOf(listOf(name, INVOICE)))),
                "an invoice as a value beside a name that contains it",
            )
            assertEquals(ListingRejection.INVOICE_IN_PUBLIC_EVENT, glued.reason)
            assertNull(glued.tag)
            assertNoEcho(glued.tag, glued.detail, glued.toString(), INVOICE, "listing glued name")
        }

        ListingWriterFixtures.built(
            offer(listingWith(extraTags = listOf(listOf("client", broken(INVOICE)), listOf(broken(INVOICE))))),
            "one character changed",
        )
    }

    @Test
    @JsName("a_mixed_case_run_is_no_invoice_and_the_listing_builds")
    fun `a mixed-case run is no invoice and the listing builds`() {
        // Bech32 forbids mixed case, so a run with one character flipped is not an invoice at all.
        val mixed = INVOICE.replaceFirstChar { it.uppercaseChar() }
        ListingWriterFixtures.built(offer(listingWith(summary = mixed)), "a mixed-case run")
    }

    // ---------------------------------------------------------------------------------------------
    // BidWriter.
    // ---------------------------------------------------------------------------------------------

    @Test
    @JsName("a_bid_s_content_with_a_lightning_uri_uppercased_is_refused_with_no_tag")
    fun `a bid's content with a lightning URI uppercased is refused with no tag`() {
        for (invoice in INVOICES) {
            val refused = BidWriterFixtures.refused(
                BidWriter.bid(bidWith(content = (LIGHTNING_URI + invoice).uppercase())),
                "an uppercased invoice URI in a bid's content",
            )
            assertEquals(BidRejection.INVOICE_IN_PUBLIC_EVENT, refused.reason)
            assertNull(refused.tag)
            assertNull(refused.tagReason)
            assertNoEcho(refused.tag, refused.detail, refused.toString(), invoice, "bid content")
        }
        BidWriterFixtures.built(
            BidWriter.bid(bidWith(content = (LIGHTNING_URI + broken(INVOICE)).uppercase())),
            "one character changed",
        )
    }

    @Test
    @JsName("a_bid_extension_tag_carrying_an_invoice_as_name_or_value_is_refused")
    fun `a bid extension tag carrying an invoice as name or value is refused`() {
        val asValue = BidWriterFixtures.refused(
            BidWriter.bid(bidWith(extraTags = listOf(listOf("client", INVOICE)))),
            "an invoice as an extension tag's value",
        )
        assertEquals(BidRejection.INVOICE_IN_PUBLIC_EVENT, asValue.reason)
        assertEquals("client", asValue.tag)
        assertNoEcho(asValue.tag, asValue.detail, asValue.toString(), INVOICE, "bid extension value")

        val asName = BidWriterFixtures.refused(
            BidWriter.bid(bidWith(extraTags = listOf(listOf(INVOICE.uppercase())))),
            "an invoice as an extension tag's name",
        )
        assertEquals(BidRejection.INVOICE_IN_PUBLIC_EVENT, asName.reason)
        assertNull(asName.tag)
        assertNoEcho(asName.tag, asName.detail, asName.toString(), INVOICE, "bid extension name")

        for (name in gluedNames()) {
            val glued = BidWriterFixtures.refused(
                BidWriter.bid(bidWith(extraTags = listOf(listOf(name, INVOICE)))),
                "an invoice as a value beside a name that contains it",
            )
            assertEquals(BidRejection.INVOICE_IN_PUBLIC_EVENT, glued.reason)
            assertNull(glued.tag)
            assertNoEcho(glued.tag, glued.detail, glued.toString(), INVOICE, "bid glued name")
        }

        BidWriterFixtures.built(
            BidWriter.bid(bidWith(extraTags = listOf(listOf("client", broken(INVOICE)), listOf(broken(INVOICE))))),
            "one character changed",
        )
    }

    @Test
    @JsName("every_generated_listing_and_bid_case_still_builds")
    fun `every generated listing and bid case still builds`() {
        for (case in ListingWriterFixtures.cases(CASES)) {
            ListingWriterFixtures.built(ListingWriterFixtures.build(case.kind, case.listing), "listing case ${case.index}")
        }
        for (case in BidWriterFixtures.cases(CASES)) {
            BidWriterFixtures.built(BidWriter.bid(case.bid), "bid case ${case.index}")
        }
    }
}

/** Generated cases per writer; the round trip over the full corpus is in the property tests. */
private const val CASES: Int = 200
