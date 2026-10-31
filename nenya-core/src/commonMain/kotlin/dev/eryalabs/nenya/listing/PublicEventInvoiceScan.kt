package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.settlement.Bolt11Invoice
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.SettlementException

/**
 * §12 item 2's automatic check over a public event this library constructs: "any invoice string
 * MUST NOT appear in a public event; implementations SHOULD check this automatically over every
 * event they can construct."
 *
 * ### The rule, as the code runs it
 *
 * The event's `content` is read first, then every element of every tag in emission order, the tag
 * name included. Each string is split into its **maximal runs of ASCII letters and digits** — so a
 * `lightning:` prefix, a space, a quote or any other punctuation ends a run and never hides what
 * follows it. A run carrying no lowercase letter is lowercased, because BIP-173 allows an all-
 * uppercase bech32 string (the form QR codes print) and §12 item 2 does not stop applying to an
 * invoice because it was shouted. A run that is mixed-case is kept as it is: bech32 forbids mixed
 * case, so it is no invoice. Then the run is an invoice exactly when
 * `Bolt11Invoice.parse(Bolt11Reference.recognise(run))` accepts it — the same reader §9.2 uses, so
 * this check and the settlement path cannot disagree about what an invoice is, and a run whose
 * bech32 checksum does not verify is not one.
 *
 * Only a `SettlementException` means "not an invoice". Any other throw from either reader is a
 * defect in that reader and propagates.
 *
 * ### What it does not see
 *
 * An invoice broken across two runs — split by a space, a line break or any non-alphanumeric
 * character — is not found, and nor is one encoded in any other way. This is an automatic check
 * for an invoice pasted by mistake, not a guarantee against an author determined to publish one.
 * It looks for invoices only: an order id or a preimage is 64 hex characters and nothing tells one
 * apart from any other hex.
 *
 * The answer names where the invoice was and never what it was (§12, STOP RULE 14).
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
internal object PublicEventInvoiceScan {

    /**
     * Where the first invoice was found: [tag] is the carrying tag's name, or `null` for `content`,
     * for an invoice in a tag name, and for a name that **contains** the invoice in any case — glued
     * inside a longer run, say — because naming that tag would echo the invoice.
     */
    class Found(val tag: String?)

    /** The first invoice in [content] or [tags], or `null` when neither carries one. */
    fun find(content: String, tags: List<List<String>>): Found? {
        if (invoiceIn(content) != null) return Found(null)
        for (tag in tags) {
            for ((index, element) in tag.withIndex()) {
                val invoice = invoiceIn(element) ?: continue
                val name = tag[0]
                val echoes = index == 0 || name.lowercase().contains(invoice.lowercase())
                return Found(if (echoes) null else name)
            }
        }
        return null
    }

    /** The first run of [text] that is an invoice, as it appears in [text], or `null`. */
    private fun invoiceIn(text: String): String? {
        var start = 0
        while (start < text.length) {
            if (!isRunCharacter(text[start])) {
                start++
                continue
            }
            var end = start
            while (end < text.length && isRunCharacter(text[end])) end++
            val run = text.substring(start, end)
            if (isInvoice(run)) return run
            start = end
        }
        return null
    }

    private fun isRunCharacter(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9'

    private fun isInvoice(run: String): Boolean {
        val candidate = if (run.none { it in 'a'..'z' }) run.lowercase() else run
        return try {
            Bolt11Invoice.parse(Bolt11Reference.recognise(candidate))
            true
        } catch (notAnInvoice: SettlementException) {
            false
        }
    }
}
