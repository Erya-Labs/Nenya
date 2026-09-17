package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.settlement.Bolt11Examples.Group
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The vendored BOLT-11 document is the file PROVENANCE.md describes, and [Bolt11Examples] reads
 * every example out of it — so a truncated, substituted or misparsed file fails loudly here,
 * before any invoice parser is ever held to what the extractor returns.
 *
 * No invoice is decoded in this file. The only computation over example data is SHA-256 of the
 * document's own "hex of data for signing", compared with the digest the document states next to
 * it: a check that the extractor copied both values faithfully, anchored to Nenya's NIST-proven
 * SHA-256 rather than to anything typed here.
 *
 * The negative controls run the SAME assertion helpers on an in-memory altered copy of the text;
 * the vendored file itself is never touched.
 */
class Bolt11ExamplesTest {

    private val text: String get() = Bolt11Examples.text()

    private fun checksumOf(bytes: ByteArray): String = TestText.lowerHex(sha256(bytes))

    private fun assertMatchesRecordedChecksum(text: String) {
        assertEquals(Bolt11Examples.recordedChecksum(), checksumOf(TestText.utf8(text)), "SHA-256 of ${Bolt11Examples.PATH}")
    }

    @JsName("the_vendored_bolt11_document_matches_the_checksum_provenance_records")
    @Test
    fun `the vendored BOLT-11 document matches the checksum PROVENANCE records`() {
        assertMatchesRecordedChecksum(text)
    }

    @JsName("the_extractor_finds_exactly_16_valid_and_10_invalid_examples")
    @Test
    fun `the extractor finds exactly 16 valid and 10 invalid examples`() {
        val examples = Bolt11Examples.extract()
        Bolt11Examples.assertDocumentedCounts(examples)
        assertEquals(examples.size, examples.map { it.invoice }.toSet().size, "every example invoice is distinct")
        assertTrue(examples.all { it.invoiceLine > it.headingLine }, "every invoice follows its heading")
    }

    @JsName("the_first_and_last_example_of_each_group_are_the_documented_ones")
    @Test
    fun `the first and last example of each group are the documented ones`() {
        val examples = Bolt11Examples.extract()
        val valid = examples.filter { it.group == Group.VALID }
        val invalid = examples.filter { it.group == Group.INVALID }
        assertTrue(valid.first().heading.startsWith("Please make a donation of any amount"), "first valid: ${valid.first()}")
        assertEquals("Public-key recovery with high-S signature", valid.last().heading)
        assertEquals("Same, but adding invalid unknown feature 100", invalid.first().heading)
        assertEquals("Non canonical signature (high-S) with 'n' field defined", invalid.last().heading)
    }

    /** The P2TR example's invoice is the one upstream wrote outside the blockquote. */
    @JsName("the_one_invoice_written_outside_the_blockquote_is_found")
    @Test
    fun `the one invoice written outside the blockquote is found`() {
        val unquoted = Bolt11Examples.extract().filter { !it.blockquoted }
        assertEquals(1, unquoted.size, "invoices not on a '> ' line: $unquoted")
        assertEquals(Group.VALID, unquoted.single().group)
        assertTrue("P2TR" in unquoted.single().heading, "unquoted invoice belongs to ${unquoted.single()}")
    }

    @JsName("every_valid_invoice_is_single_case_and_exactly_one_is_all_uppercase")
    @Test
    fun `every valid invoice is single-case and exactly one is all uppercase`() {
        val valid = Bolt11Examples.extract().filter { it.group == Group.VALID }
        val mixed = valid.filter { it.invoice != it.invoice.lowercase() && it.invoice != it.invoice.uppercase() }
        assertEquals(emptyList(), mixed.map { it.toString() }, "valid invoices in mixed case")
        assertEquals(1, valid.count { it.invoice == it.invoice.uppercase() }, "all-uppercase valid invoices")
    }

    /**
     * How many examples state each value, counted independently of this extractor with a separate
     * script over the pinned file. A pattern that silently stops matching changes one of these.
     */
    @JsName("the_extractor_reads_every_stated_breakdown_value")
    @Test
    fun `the extractor reads every stated breakdown value`() {
        val examples = Bolt11Examples.extract()
        fun count(group: Group, has: (Bolt11Examples.Example) -> Boolean) = examples.count { it.group == group && has(it) }
        assertEquals(15 to 1, count(Group.VALID) { it.timestamp != null } to count(Group.INVALID) { it.timestamp != null }, "timestamps")
        assertEquals(13 to 1, count(Group.VALID) { it.amount != null } to count(Group.INVALID) { it.amount != null }, "amounts")
        assertEquals(3 to 0, count(Group.VALID) { it.expirySeconds != null } to count(Group.INVALID) { it.expirySeconds != null }, "expiries")
        assertEquals(8 to 1, count(Group.VALID) { it.description != null } to count(Group.INVALID) { it.description != null }, "descriptions")
        assertEquals(4 to 0, count(Group.VALID) { it.paymentHashHex != null } to count(Group.INVALID) { it.paymentHashHex != null }, "payment hashes")
        assertEquals(11 to 0, count(Group.VALID) { it.signature != null } to count(Group.INVALID) { it.signature != null }, "signature breakdowns")
    }

    /**
     * The one description the document escapes, and the guard that keeps the unescaping honest.
     *
     * Exactly one line of the pinned file carries a backslash at all: the two `\"` pairs inside the
     * pico-BTC example's description. The invoice's `d` field holds the unescaped character, so the
     * extractor undoes that quoting — and this pins both that it happened and that nothing else
     * arrives still escaped, so a future file with a notation it does not know cannot pass quietly.
     */
    @JsName("exactly_one_stated_description_is_unescaped_and_none_arrives_still_escaped")
    @Test
    fun `exactly one stated description is unescaped and none arrives still escaped`() {
        val descriptions = Bolt11Examples.extract().mapNotNull { it.description }
        assertEquals(9, descriptions.size, "stated descriptions")
        assertEquals(1, descriptions.count { '"' in it }, "descriptions carrying a double quote")
        assertEquals(emptyList(), descriptions.filter { '\\' in it }, "descriptions still carrying a backslash")
    }

    @JsName("every_stated_signing_digest_is_the_sha_256_of_the_stated_signing_data")
    @Test
    fun `every stated signing digest is the SHA-256 of the stated signing data`() {
        val breakdowns = Bolt11Examples.extract().mapNotNull { e -> e.signature?.let { e to it } }
        assertEquals(11, breakdowns.size, "signature breakdowns")
        val failures = breakdowns.filter { (_, s) ->
            checksumOf(unhex(s.signingDataHex)) != s.signingDataSha256Hex
        }.map { it.first.toString() }
        assertEquals(emptyList(), failures, "stated signing data that does not hash to the stated digest")
        assertTrue(breakdowns.all { (_, s) -> s.recoveryFlag in 0..3 }, "recovery flags are 0-3")
    }

    /** Every stated amount uses a multiplier the document's own table defines, under the same word. */
    @JsName("every_stated_amount_agrees_with_the_document_s_multiplier_table")
    @Test
    fun `every stated amount agrees with the document's multiplier table`() {
        val table = Bolt11Examples.multipliers()
        assertEquals(listOf("m", "u", "n", "p"), table.map { it.letter }, "multiplier letters")
        val words = table.associate { it.letter to it.word }
        val amounts = Bolt11Examples.extract().mapNotNull { it.amount }
        assertEquals(14, amounts.size)
        for (a in amounts) {
            assertEquals(words[a.letter], a.unitWord, "amount '${a.written}' is stated in ${a.unitWord}-bitcoin")
        }
    }

    // ---- negative controls: the same assertions, on an altered in-memory copy ----

    @JsName("negative_control_one_changed_byte_fails_the_checksum_assertion")
    @Test
    fun `negative control - one changed byte fails the checksum assertion`() {
        val original = text
        val at = original.indexOf(Bolt11Examples.VALID_SECTION) + 2
        val altered = original.substring(0, at) + (if (original[at] == 'E') 'e' else 'E') + original.substring(at + 1)
        assertEquals(original.length, altered.length)
        assertNotEquals(original, altered)
        assertFailsWith<AssertionError> { assertMatchesRecordedChecksum(altered) }
    }

    @JsName("negative_control_a_truncated_copy_fails_the_checksum_and_count_assertions")
    @Test
    fun `negative control - a truncated copy fails the checksum and count assertions`() {
        val original = text
        // Cut just before the last invalid example's heading: the sections still exist, one example is gone.
        val cut = original.lastIndexOf("\n> ### Non canonical signature")
        assertTrue(cut > 0, "truncation point not found")
        val truncated = original.substring(0, cut) + "\n\n" + Bolt11Examples.END_SECTION + "\n"
        assertFailsWith<AssertionError> { assertMatchesRecordedChecksum(truncated) }
        val examples = Bolt11Examples.extract(truncated)
        assertEquals(Bolt11Examples.INVALID_COUNT - 1, examples.count { it.group == Group.INVALID })
        assertFailsWith<AssertionError> { Bolt11Examples.assertDocumentedCounts(examples) }
    }

    @JsName("negative_control_deleting_the_unquoted_p2tr_invoice_fails_loudly")
    @Test
    fun `negative control - deleting the unquoted P2TR invoice fails loudly`() {
        val lines = TestText.lines(text)
        val p2tr = Bolt11Examples.extract().single { !it.blockquoted }
        val altered = (lines.subList(0, p2tr.invoiceLine - 1) + lines.subList(p2tr.invoiceLine, lines.size)).joinToString("\n")
        assertFailsWith<AssertionError> { Bolt11Examples.assertDocumentedCounts(Bolt11Examples.extract(altered)) }
    }

    @JsName("negative_control_a_provenance_with_no_bolt11_line_fails_rather_than_skipping")
    @Test
    fun `negative control - a PROVENANCE with no BOLT-11 line fails rather than skipping`() {
        val provenance = dev.eryalabs.nenya.VendoredFile(Bolt11Examples.PROVENANCE_PATH).text
        val stripped = TestText.lines(provenance).filter { Bolt11Examples.PROVENANCE_NAME !in it || !it.trim().endsWith(Bolt11Examples.PROVENANCE_NAME) }
        assertEquals(Bolt11Examples.recordedChecksum(), Bolt11Examples.recordedChecksum(provenance))
        assertFailsWith<AssertionError> { Bolt11Examples.recordedChecksum(stripped.joinToString("\n")) }
    }

    private fun unhex(hex: String): ByteArray {
        assertEquals(0, hex.length % 2, "odd-length hex")
        return ByteArray(hex.length / 2) { i ->
            val hi = hex[2 * i].digitToIntOrNull(16) ?: fail("not hex: $hex")
            val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: fail("not hex: $hex")
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun fail(message: String): Nothing = kotlin.test.fail(message)
}
