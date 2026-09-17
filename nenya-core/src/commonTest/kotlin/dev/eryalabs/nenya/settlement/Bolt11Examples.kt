package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.VendoredFile
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A pure-text reader for the example invoices in the vendored BOLT-11 document,
 * `vectors/bolt11/11-payment-encoding.md` (lightning/bolts, pinned; see `vectors/PROVENANCE.md`).
 *
 * **This is not an invoice parser.** It decodes nothing: no bech32, no bit slicing, no amount
 * arithmetic. It only locates each example's heading and invoice string in the Markdown, and
 * copies out the values the document *states* for that invoice in its "Breakdown" prose — the
 * timestamp, the amount as written, the expiry, the description text, the payment hash, and the
 * signature breakdown. Those stated values are what a parser written later is held to, so they
 * must come from the document and never be typed into a test.
 *
 * It is strict on purpose. Every `>` line inside the two example sections must be a heading, a
 * bare blockquote line, or an invoice; a heading with no invoice after it, a value stated twice
 * for one example, or a half-present signature breakdown fails loudly naming the line, rather
 * than quietly yielding fewer examples.
 *
 * Three quirks of the upstream file are handled deliberately:
 *  - the P2TR example's invoice is not on a `> ` line; it follows a bare `> ` line;
 *  - the first invalid example's heading is `> #`, not `> ###`;
 *  - several examples (the all-uppercase one, and invalid examples 2-10) have no breakdown.
 *
 * Every pattern here is also valid JavaScript under the `u` flag, because this runs on Kotlin/JS.
 */
internal object Bolt11Examples {

    /** The vendored file, as a path from the repository root (the `VendoredTestFiles` key). */
    const val PATH: String = "nenya-core/src/commonTest/resources/vectors/bolt11/11-payment-encoding.md"

    /** The name PROVENANCE.md records the checksum under, relative to `vectors/`. */
    const val PROVENANCE_NAME: String = "bolt11/11-payment-encoding.md"

    const val PROVENANCE_PATH: String = "nenya-core/src/commonTest/resources/vectors/PROVENANCE.md"

    /** How many examples of each kind the pinned document publishes. */
    const val VALID_COUNT: Int = 16
    const val INVALID_COUNT: Int = 10

    const val VALID_SECTION: String = "# Examples"
    const val INVALID_SECTION: String = "# Examples of Invalid Invoices"
    const val END_SECTION: String = "# Authors"

    enum class Group { VALID, INVALID }

    /** `2500u`: amount (2500 micro-bitcoin) — [written] is `2500u`, [number] 2500, [unitWord] `micro`. */
    class StatedAmount(val written: String, val number: String, val letter: String, val unitWord: String)

    /** The four values of a "Signature breakdown", all lowercase hex except the recovery flag. */
    class SignatureBreakdown(
        val signatureHex: String,
        val recoveryFlag: Int,
        val signingDataHex: String,
        val signingDataSha256Hex: String,
    )

    class Example(
        val group: Group,
        val heading: String,
        /** 1-based line numbers in the vendored file. */
        val headingLine: Int,
        val invoiceLine: Int,
        val invoice: String,
        /** False for the one invoice upstream wrote outside the blockquote. */
        val blockquoted: Boolean,
        val timestamp: Long?,
        val amount: StatedAmount?,
        val expirySeconds: Long?,
        val description: String?,
        val paymentHashHex: String?,
        val signature: SignatureBreakdown?,
    ) {
        override fun toString(): String = "$group example '$heading' (line $headingLine)"
    }

    /** One `multiplier` definition: `m` (milli): multiply by 0.001. */
    class Multiplier(val letter: String, val word: String, val factor: String)

    private val HEADING = Regex("^> #{1,3} (.+)\$")
    private val BARE_QUOTE = Regex("^> ?\$")
    private val INVOICE = Regex("^(> )?([0-9A-Za-z]+)\$")
    private val TOP_FIELD = Regex("^\\* `([^`]+)`: (.*)\$")
    private val TIMESTAMP = Regex("^timestamp \\(([0-9]+)\\)\$")
    private val AMOUNT = Regex("^amount \\(([0-9]+) ([a-z]+)-bitcoin[ )]")
    private val WRITTEN_AMOUNT = Regex("^([0-9]+)([a-z]?)\$")
    private val SUB_VALUE = Regex("^  \\* `([0-9a-z]+)`: (.*)\$")
    private val QUOTED_TEXT = Regex("^'(.*)'\$")
    private val SECONDS = Regex("^([0-9]+) seconds( |\$)")
    private val PAYMENT_HASH = Regex("^payment hash ([0-9a-f]{64})\$")
    private val SIGNATURE_BREAKDOWN = Regex("^\\* Signature breakdown:\$")
    private val SIG_DATA = Regex("^  \\* `([0-9a-f]{128})` hex of signature data \\(32-byte r, 32-byte s\\)\$")
    private val RECOVERY_FLAG = Regex("^  \\* `([0-9])` \\(int\\) recovery flag contained in `signature`\$")
    private val SIGNING_DATA = Regex("^  \\* `([0-9a-f]+)` hex of data for signing ")
    private val SIGNING_SHA = Regex("^  \\* `([0-9a-f]{64})` hex of SHA256 of the preimage\$")
    private val MULTIPLIER = Regex("^\\* `([a-z])` \\(([a-z]+)\\): multiply by (0\\.[0-9]+)\$")
    private val PROVENANCE_CHECKSUM = Regex("^([0-9a-f]{64})  bolt11/11-payment-encoding\\.md\$")

    fun text(): String = VendoredFile(PATH).text

    /** Every example in [text], valid ones first, in document order. */
    fun extract(text: String = text()): List<Example> {
        val lines = TestText.lines(text)
        val valid = sectionStart(lines, VALID_SECTION)
        val invalid = sectionStart(lines, INVALID_SECTION)
        val end = sectionStart(lines, END_SECTION)
        if (!(valid < invalid && invalid < end)) fail("$PATH: sections out of order ($valid, $invalid, $end)")
        return section(lines, valid + 1, invalid, Group.VALID) + section(lines, invalid + 1, end, Group.INVALID)
    }

    /** The document's own multiplier table, from its `multiplier` definition list. */
    fun multipliers(text: String = text()): List<Multiplier> =
        TestText.lines(text).mapNotNull { line ->
            MULTIPLIER.find(line)?.groupValues?.let { Multiplier(it[1], it[2], it[3]) }
        }

    /** Fails unless [examples] holds exactly the documented number of valid and invalid examples. */
    fun assertDocumentedCounts(examples: List<Example>) {
        assertEquals(VALID_COUNT, examples.count { it.group == Group.VALID }, "valid BOLT-11 examples extracted")
        assertEquals(INVALID_COUNT, examples.count { it.group == Group.INVALID }, "invalid BOLT-11 examples extracted")
    }

    /** The checksum PROVENANCE.md records for the vendored file; exactly one such line must exist. */
    fun recordedChecksum(provenance: String = VendoredFile(PROVENANCE_PATH).text): String {
        val found = TestText.lines(provenance).mapNotNull { PROVENANCE_CHECKSUM.find(it.trim())?.groupValues?.get(1) }
        assertEquals(1, found.size, "PROVENANCE.md must record exactly one checksum for $PROVENANCE_NAME")
        return found.single()
    }

    private fun sectionStart(lines: List<String>, title: String): Int {
        val at = lines.indices.filter { lines[it] == title }
        if (at.size != 1) fail("$PATH: expected exactly one '$title' line, found ${at.size}")
        return at.single()
    }

    private fun section(lines: List<String>, from: Int, until: Int, group: Group): List<Example> {
        val headings = (from until until).filter { HEADING.containsMatchIn(lines[it]) }
        return headings.mapIndexed { n, at ->
            example(lines, at, headings.getOrElse(n + 1) { until }, group)
        }.also {
            // Nothing blockquoted may sit between the section title and its first heading.
            for (i in from until (headings.firstOrNull() ?: until)) {
                if (lines[i].startsWith(">")) fail("$PATH:${i + 1}: blockquote before the first example heading")
            }
        }
    }

    private fun example(lines: List<String>, at: Int, next: Int, group: Group): Example {
        val heading = HEADING.find(lines[at])!!.groupValues[1]
        var i = at + 1
        while (i < next && (lines[i].isEmpty() || BARE_QUOTE.containsMatchIn(lines[i]))) i++
        val invoiceMatch = (if (i < next) INVOICE.find(lines[i]) else null)
            ?: fail("$PATH:${at + 1}: heading '$heading' is not followed by an invoice line")
        val invoiceAt = i

        var field: String? = null
        var timestamp: Long? = null
        var amount: StatedAmount? = null
        var expiry: Long? = null
        var description: String? = null
        var paymentHash: String? = null
        val sig = arrayOfNulls<String>(4)

        fun <T> once(current: T?, value: T, what: String, line: Int): T {
            if (current != null) fail("$PATH:${line + 1}: '$heading' states its $what twice")
            return value
        }

        for (j in invoiceAt + 1 until next) {
            val line = lines[j]
            if (line.startsWith(">") && !BARE_QUOTE.containsMatchIn(line)) {
                fail("$PATH:${j + 1}: unexpected blockquote line inside '$heading'")
            }
            if (SIGNATURE_BREAKDOWN.containsMatchIn(line)) {
                field = SIGNATURE
                continue
            }
            val top = TOP_FIELD.find(line)
            if (top != null) {
                field = top.groupValues[1]
                val prose = top.groupValues[2]
                TIMESTAMP.find(prose)?.let { timestamp = once(timestamp, it.groupValues[1].toLong(), "timestamp", j) }
                AMOUNT.find(prose)?.let {
                    val written = WRITTEN_AMOUNT.find(field!!)
                        ?: fail("$PATH:${j + 1}: amount written as '$field' is not digits and a multiplier")
                    if (written.groupValues[1] != it.groupValues[1]) {
                        fail("$PATH:${j + 1}: amount written '$field' but stated as ${it.groupValues[1]}")
                    }
                    amount = once(amount, StatedAmount(field!!, it.groupValues[1], written.groupValues[2], it.groupValues[2]), "amount", j)
                }
                continue
            }
            if (field == SIGNATURE) {
                SIG_DATA.find(line)?.let { sig[0] = once(sig[0], it.groupValues[1], "signature data", j) }
                RECOVERY_FLAG.find(line)?.let { sig[1] = once(sig[1], it.groupValues[1], "recovery flag", j) }
                SIGNING_DATA.find(line)?.let { sig[2] = once(sig[2], it.groupValues[1], "signing data", j) }
                SIGNING_SHA.find(line)?.let { sig[3] = once(sig[3], it.groupValues[1], "signing-data SHA-256", j) }
                continue
            }
            val sub = SUB_VALUE.find(line) ?: continue
            val value = sub.groupValues[2]
            when (field) {
                "d" -> QUOTED_TEXT.find(value)?.let { description = once(description, it.groupValues[1], "description", j) }
                "x" -> SECONDS.find(value)?.let { expiry = once(expiry, it.groupValues[1].toLong(), "expiry", j) }
                "p" -> PAYMENT_HASH.find(value)?.let { paymentHash = once(paymentHash, it.groupValues[1], "payment hash", j) }
            }
        }

        val present = sig.count { it != null }
        if (present != 0 && present != 4) fail("$PATH:${at + 1}: '$heading' has a partial signature breakdown ($present of 4)")
        return Example(
            group = group,
            heading = heading,
            headingLine = at + 1,
            invoiceLine = invoiceAt + 1,
            invoice = invoiceMatch.groupValues[2],
            blockquoted = invoiceMatch.groupValues[1].isNotEmpty(),
            timestamp = timestamp,
            amount = amount,
            expirySeconds = expiry,
            description = description,
            paymentHashHex = paymentHash,
            signature = if (present == 4) SignatureBreakdown(sig[0]!!, sig[1]!!.toInt(), sig[2]!!, sig[3]!!) else null,
        )
    }

    private const val SIGNATURE: String = "<signature breakdown>"
}
