package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.settlement.Bolt11Examples.Group
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [Bolt11Invoice.parse] held to the vendored BOLT-11 document, and to nothing this repository wrote.
 *
 * ### Nothing here is typed
 *
 * Every invoice is one of the 26 vendored examples read through [Bolt11Examples], or one of them
 * with a single part changed by [Bolt11Composer] — which `Bolt11ComposerTest` proves rebuilds every
 * vendored example character for character. Every expected value is either stated in that document's
 * own prose beside the invoice it belongs to, parsed out of Appendix C of `spec/NENYA-1.md`, or
 * computed here from the two. The amounts in particular are **computed** from the document's stated
 * figure and the specification's multiplier table rather than written down, so a wrong factor cannot
 * agree with a wrong expectation.
 *
 * ### Every negative control names the reason and never merely the refusal
 *
 * Two of the document's invalid examples fail more than one of this parser's rules at once — its
 * bad-checksum example carries no payment secret either — so a control asserting "rejected" would
 * still pass with the checksum check deleted. Each control below names the **door** that refuses
 * (the recogniser or the parser) and the constant it refuses with; a reason the recogniser gives is
 * asserted at `Bolt11Reference.recognise`, because the call throws and no parse ever happens.
 *
 * ### The departures from BOLT-11 are derived, not listed
 *
 * `the departures from BOLT-11 are exactly these` runs all 26 examples through both doors and
 * asserts the **computed** sets. A list a test writes and then counts proves nothing.
 */
class Bolt11InvoiceTest {

    private companion object {

        /** The four field types BOLT-11 gives a fixed length. `n` is 33 bytes; the rest are 32. */
        val FIXED_LENGTH_TYPES: List<Char> = listOf('p', 'h', 's', 'n')

        /** A `n` node-id field is 33 bytes — 264 bits, which is 53 five-bit groups. */
        const val NODE_ID_GROUPS: Int = 53

        /**
         * How many wrong-length fixed fields the vendored "including fields which must be ignored"
         * example carries: two `p`, two `h`, two `s` and two `n`.
         *
         * Asserted on the decomposition rather than assumed, because the whole of that example's
         * value as a control is that the fields are really there.
         */
        const val WRONG_LENGTH_FIELDS_IN_EXAMPLE_14: Int = 8

        /** The twelve groups below a 65-bit field's leading one. */
        const val TWELVE_GROUPS: Int = 12

        /** Thirteen groups is 65 bits — the narrowest field that can hold 2^64 or more. */
        const val SIXTY_FIVE_BIT_GROUPS: Int = 13

        /**
         * A field far wider than the value bound, for the leading-zero control.
         *
         * At exactly thirteen groups the parser's leading-zero skip is not load-bearing — the
         * refusal branch is reached and declined on the leading group's own value — so a control
         * that stopped there would stay green with the skip deleted, while a legal zero-padded `x`
         * became `EXPIRY_OUT_OF_RANGE`.
         */
        const val WIDE_GROUPS: Int = 20

        /** 2^63 = 8 × 2^60, which is where a signed `Long` stops holding the value. */
        const val LEADING_GROUP_AT_TWO_TO_THE_63: Int = 8

        /** 2^64 = 16 × 2^60 — the refusal bound, which is on the value and not on the width. */
        const val LEADING_GROUP_AT_TWO_TO_THE_64: Int = 16

        /** One below [LEADING_GROUP_AT_TWO_TO_THE_64]: with twelve full groups, exactly 2^64 − 1. */
        const val LEADING_GROUP_AT_THE_LARGEST_LEGAL_VALUE: Int = 15

        /** A bech32 group is five bits, so a twelve-group tail is sixty. */
        const val TWELVE_GROUP_BITS: Int = 60

        fun correctLengthOf(type: Char): Int =
            if (type == 'n') NODE_ID_GROUPS else Bolt11Composer.HASH_GROUPS
    }

    /** Pinned so a failure is reproducible; see [JdkRandom] on why it is the JDK's algorithm. */
    private val seed: Long = 20260924L

    private val examples: List<Bolt11Examples.Example> get() = Bolt11Examples.extract()

    /**
     * The counts `Bolt11ExamplesTest` pins independently, repeated rather than derived.
     *
     * A count computed from the extractor would fall silently to zero with it, which is the vacuity
     * these floors exist to stop — the same reason `Bolt11ComposerTest` repeats them.
     */
    private val lowercaseValidCount = Bolt11Examples.VALID_COUNT - 1
    private val statedTimestamps = 15
    private val statedAmounts = 13
    private val statedExpiries = 3
    private val statedPaymentHashes = 4
    private val statedMinFinalCltv = 1
    private val signatureBreakdowns = 11

    /** The one example whose heading names its feature bits in prose. */
    private val featuresInHeading = Regex("""features ([0-9]+), ([0-9]+) and ([0-9]+)""")

    private fun valid(): List<Bolt11Examples.Example> = examples.filter { it.group == Group.VALID }

    private fun invalid(): List<Bolt11Examples.Example> = examples.filter { it.group == Group.INVALID }

    private fun lowercaseValid(): List<Bolt11Examples.Example> =
        valid().filter { it.invoice == it.invoice.lowercase() }

    private fun parse(invoice: String): Bolt11Invoice =
        Bolt11Invoice.parse(Bolt11Reference.recognise(invoice))

    /** The reason [block] refused with, failing loudly if it did not refuse at all. */
    private fun reasonOf(label: String, block: () -> Unit): SettlementRejection =
        assertFailsWith<SettlementException>(label) { block() }.reason

    // -----------------------------------------------------------------------------------------
    // Appendix C's multiplier table is the parser's own.
    // -----------------------------------------------------------------------------------------

    /**
     * `Bolt11Multiplier` is held **equal** to Appendix C's table, parsed at test time.
     *
     * Both directions and in order, so a row added to the specification and not to the parser — or
     * the reverse — turns this red rather than leaving one side quietly short.
     */
    @JsName("the_parsers_multiplier_table_is_appendix_cs")
    @Test
    fun `the parser's multiplier table is Appendix C's`() {
        val rows = AppendixC.multipliers
        assertTrue(rows.isNotEmpty(), "Appendix C's multiplier table parsed to no rows at all")
        assertEquals(
            rows.map { "${it.letter} ${it.numerator}/${it.denominator}" },
            Bolt11Multiplier.entries.map { "${it.letter} ${it.msatPerUnitNumerator}/${it.msatPerUnitDenominator}" },
            "Bolt11Multiplier must equal Appendix C's table, row for row and in the same order",
        )
        // And the one row that makes the fraction load-bearing is really a fraction.
        val pico = rows.single { it.denominator != 1L }
        assertEquals(10L, pico.denominator, "exactly one Appendix C row is finer than a millisatoshi")
    }

    // -----------------------------------------------------------------------------------------
    // Every all-lowercase valid example parses, and agrees with what the document states.
    // -----------------------------------------------------------------------------------------

    /**
     * The headline positive: each stated value is reproduced, and each *unstated* one takes
     * Appendix C's default.
     *
     * The defaults are asserted on the same pass rather than separately, because "3600 when there is
     * no `x`" is the branch an implementation forgets and the one §9.2 check 5 then measures against
     * nothing.
     */
    @JsName("every_all_lowercase_valid_example_parses_to_the_values_the_document_states")
    @Test
    fun `every all-lowercase valid example parses to the values the document states`() {
        val lowercase = lowercaseValid()
        assertEquals(lowercaseValidCount, lowercase.size, "all-lowercase valid examples")

        var timestamps = 0
        var amounts = 0
        var anyAmount = 0
        var expiries = 0
        var hashes = 0
        var cltvs = 0
        val failures = ArrayList<String>()

        for (example in lowercase) {
            val invoice = parse(example.invoice)

            example.timestamp?.let {
                timestamps++
                if (invoice.timestamp != it) failures += "$example: timestamp ${invoice.timestamp} != $it"
            }

            val stated = example.amount
            if (stated == null) {
                anyAmount++
                if (invoice.amount != null) failures += "$example: amount ${invoice.amount} where none is stated"
            } else {
                amounts++
                val expected = millisatoshisOf(stated)
                if (invoice.amount != expected) failures += "$example: amount ${invoice.amount} != $expected"
            }

            val expectedExpiry = example.expirySeconds ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS
            example.expirySeconds?.let { expiries++ }
            if (invoice.expirySeconds != expectedExpiry) {
                failures += "$example: expiry ${invoice.expirySeconds} != $expectedExpiry"
            }

            example.paymentHashHex?.let {
                hashes++
                if (invoice.paymentHash.toHex() != it) failures += "$example: payment hash != $it"
            }

            val expectedCltv = example.minFinalCltvExpiry ?: Bolt11Invoice.DEFAULT_MIN_FINAL_CLTV_EXPIRY
            example.minFinalCltvExpiry?.let { cltvs++ }
            if (invoice.minFinalCltvExpiry != expectedCltv) {
                failures += "$example: min_final_cltv_expiry ${invoice.minFinalCltvExpiry} != $expectedCltv"
            }

            // The three fields Appendix C makes REQUIRED hold on every one of them.
            if (Bolt11Field.PAYMENT_HASH !in invoice.fieldsPresent) failures += "$example: no payment hash field"
            if (Bolt11Field.PAYMENT_SECRET !in invoice.fieldsPresent) failures += "$example: no payment secret field"
            val descriptions = invoice.fieldsPresent.count {
                it == Bolt11Field.DESCRIPTION || it == Bolt11Field.DESCRIPTION_HASH
            }
            if (descriptions != 1) failures += "$example: $descriptions description fields"
            if (invoice.networkPrefix.isEmpty()) failures += "$example: empty network prefix"
        }

        assertEquals(emptyList(), failures, "parses that disagree with the document's own prose")
        assertEquals(
            listOf(statedTimestamps, statedAmounts, statedExpiries, statedPaymentHashes, statedMinFinalCltv),
            listOf(timestamps, amounts, expiries, hashes, cltvs),
            "examples fed to the timestamp, amount, expiry, payment-hash and `c` anchors",
        )
        assertEquals(
            lowercaseValidCount - statedAmounts,
            anyAmount,
            "all-lowercase valid examples with no amount in their human-readable part",
        )
        assertTrue(anyAmount > 0, "an `Msat?` whose null branch is never reached proves nothing about it")
    }

    /** The two networks the vendored examples use, read off the parses rather than written down. */
    @JsName("the_network_prefix_is_the_human_readable_part_without_its_ln")
    @Test
    fun `the network prefix is the human-readable part without its ln`() {
        val failures = ArrayList<String>()
        for (example in lowercaseValid()) {
            val parts = Bolt11Composer.decompose(example.invoice)
            val expected = parts.networkPrefix.removePrefix("ln")
            val read = parse(example.invoice).networkPrefix
            if (read != expected) failures += "$example: network prefix '$read' != '$expected'"
            if (parts.networkPrefix == expected) failures += "$example: the `ln` was not removed at all"
        }
        assertEquals(emptyList(), failures, "network prefixes the parser disagrees with the composer about")
        // Both the mainnet and the testnet example are in the corpus, so the prefix is really read
        // rather than assumed.
        assertTrue(
            lowercaseValid().map { parse(it.invoice).networkPrefix }.toSet().size > 1,
            "every vendored example is on one network, so the prefix could be a constant",
        )
    }

    /**
     * Example 14 is "Same, but including fields which must be ignored": example 12 with eight
     * wrong-length `p`, `h`, `s` and `n` fields spliced in.
     *
     * So the two must read the same. This is the whole of decision K's relaxation in one assertion —
     * refuse those fields and example 14 does not parse; *accept* them as values and its payment
     * hash is a run of zeros rather than example 12's.
     */
    @JsName("the_ignored_fields_example_reads_the_same_as_the_example_it_was_built_from")
    @Test
    fun `the ignored-fields example reads the same as the example it was built from`() {
        val valid = valid()
        val coffeeBeans = valid[11]
        val withIgnored = valid[13]
        assertTrue("supports features" in coffeeBeans.heading, "valid example 12 is $coffeeBeans")
        assertTrue("must be ignored" in withIgnored.heading, "valid example 14 is $withIgnored")

        val plain = Bolt11Composer.decompose(coffeeBeans.invoice)
        val padded = Bolt11Composer.decompose(withIgnored.invoice)
        // The document put them there on purpose, and the control is worthless if they are absent.
        val wrongLength = padded.fields.count {
            it.type in FIXED_LENGTH_TYPES && it.dataLength != correctLengthOf(it.type)
        }
        assertEquals(WRONG_LENGTH_FIELDS_IN_EXAMPLE_14, wrongLength, "wrong-length fixed fields in $withIgnored")

        assertEquals(
            parse(coffeeBeans.invoice).paymentHash.toHex(),
            parse(withIgnored.invoice).paymentHash.toHex(),
            "the skipped `p` fields changed which payment hash was read",
        )
        // The secret and the description are not published — §12 — so they are compared through the
        // composer, which reads the same fields the parser walked past.
        assertEquals(
            plain.fields('s').single { it.dataLength == Bolt11Composer.HASH_GROUPS }.bytes().toList(),
            padded.fields('s').single { it.dataLength == Bolt11Composer.HASH_GROUPS }.bytes().toList(),
            "the payment secrets differ",
        )
        assertEquals(
            plain.field('d')!!.bytes().toList(),
            padded.field('d')!!.bytes().toList(),
            "the descriptions differ",
        )
        assertEquals(parse(coffeeBeans.invoice).amount, parse(withIgnored.invoice).amount)
        assertEquals(parse(coffeeBeans.invoice).featureBits, parse(withIgnored.invoice).featureBits)
    }

    // -----------------------------------------------------------------------------------------
    // The signature breakdowns, which is the widest externally-authored anchor in the document.
    // -----------------------------------------------------------------------------------------

    /**
     * Every stated signature breakdown reproduces from the parse.
     *
     * This is what proves the field walk reached the *right* place: the signed data is the timestamp
     * and every tagged field and nothing else, so a parser that mislaid a field boundary produces a
     * different digest even when every published value happens to look right.
     */
    @JsName("every_stated_signature_breakdown_reproduces_from_the_parse")
    @Test
    fun `every stated signature breakdown reproduces from the parse`() {
        val breakdowns = examples.mapNotNull { e -> e.signature?.let { e to it } }
            .filter { (e, _) -> e.invoice == e.invoice.lowercase() }
        assertEquals(signatureBreakdowns, breakdowns.size, "examples fed to the signature anchor")
        val failures = ArrayList<String>()
        for ((example, stated) in breakdowns) {
            val invoice = parse(example.invoice)
            val signature = TestText.lowerHex(invoice.signature())
            if (signature != stated.signatureHex) failures += "$example: r||s $signature"
            if (invoice.recoveryId != stated.recoveryFlag) {
                failures += "$example: recovery id ${invoice.recoveryId} != ${stated.recoveryFlag}"
            }
            val digest = TestText.lowerHex(invoice.signedDataSha256())
            if (digest != stated.signingDataSha256Hex) failures += "$example: signing digest $digest"
        }
        assertEquals(emptyList(), failures, "signature breakdowns the parser did not reproduce")
        // Both recovery flags the document states appear, so the byte is read rather than defaulted.
        assertTrue(
            breakdowns.map { it.second.recoveryFlag }.toSet().size > 1,
            "every breakdown states the same recovery flag, so a constant would pass",
        )
    }

    /** The published arrays are copies: a caller that mutates one cannot reach the invoice. */
    @JsName("the_published_signature_and_digest_are_copies")
    @Test
    fun `the published signature and digest are copies`() {
        val invoice = parse(lowercaseValid().first().invoice)
        val signature = invoice.signature()
        val digest = invoice.signedDataSha256()
        signature[0] = (signature[0] + 1).toByte()
        digest[0] = (digest[0] + 1).toByte()
        assertEquals(TestText.lowerHex(invoice.signature()), TestText.lowerHex(parse(lowercaseValid().first().invoice).signature()))
        assertTrue(!signature.contentEquals(invoice.signature()), "signature() handed out its own array")
        assertTrue(!digest.contentEquals(invoice.signedDataSha256()), "signedDataSha256() handed out its own array")
    }

    /** A redacted `toString`, for the reason `Bolt11Reference`'s is (§12 items 1 and 11). */
    @JsName("to_string_carries_nothing_of_the_invoice")
    @Test
    fun `toString carries nothing of the invoice`() {
        val example = lowercaseValid().first { it.paymentHashHex != null }
        val invoice = parse(example.invoice)
        val printed = invoice.toString()
        assertTrue(example.paymentHashHex!! !in printed, "the payment hash is in the string representation")
        assertTrue(example.invoice.substring(10, 30) !in printed, "invoice characters are in the string representation")
        assertEquals("Bolt11Invoice(redacted)", printed)
    }

    // -----------------------------------------------------------------------------------------
    // Feature bits, anchored twice to the document and derived once from those two.
    // -----------------------------------------------------------------------------------------

    /**
     * The three feature-bit sets the corpus carries, two of them anchored to the document's own
     * numbers and the third derived from those two.
     *
     * The document states its feature bits three different ways — a complete bit string, an elided
     * one, and a sum — so only one of them can be read mechanically from a breakdown. The second
     * anchor is an example whose *heading* names its features in prose. The third set is then the
     * **intersection** of the two, which is computed rather than written.
     */
    @JsName("the_feature_bits_agree_with_the_two_sets_the_document_states_in_numbers")
    @Test
    fun `the feature bits agree with the two sets the document states in numbers`() {
        val lowercase = lowercaseValid()

        val summed = lowercase.filter { it.statedFeatureBits != null }
        assertEquals(1, summed.size, "examples stating their feature bits as a sum: $summed")
        val fromSum = summed.single()
        assertEquals(fromSum.statedFeatureBits, parse(fromSum.invoice).featureBits, "$fromSum")

        val headed = lowercase.filter { featuresInHeading.containsMatchIn(it.heading) }
        assertEquals(1, headed.size, "examples naming their features in their heading: $headed")
        val fromHeading = headed.single()
        val statedInHeading = featuresInHeading.find(fromHeading.heading)!!
            .groupValues.drop(1).map { it.toInt() }.toSet()
        assertEquals(statedInHeading, parse(fromHeading.invoice).featureBits, "$fromHeading")

        val distinct = lowercase.map { parse(it.invoice).featureBits }.toSet()
        assertEquals(3, distinct.size, "distinct feature-bit sets across the corpus: $distinct")
        val common = statedInHeading.intersect(fromSum.statedFeatureBits!!)
        assertTrue(common.isNotEmpty(), "the two anchored sets share no bit, so the third is not derivable")
        assertEquals(
            setOf(common, statedInHeading, fromSum.statedFeatureBits!!),
            distinct,
            "the third set must be exactly what the two anchored ones have in common",
        )
        assertEquals(
            lowercase.size,
            lowercase.count { Bolt11Field.FEATURES in parse(it.invoice).fieldsPresent },
            "every vendored example carries a `9` field",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The departures from BOLT-11, computed rather than listed.
    // -----------------------------------------------------------------------------------------

    /**
     * Every example, valid and invalid, through both doors — and the three sets that come out.
     *
     * The invalid examples that nevertheless parse are exactly the three whose defect is a rule
     * Nenya does not apply: an unknown even feature bit, an unrecoverable signature, and a
     * non-canonical high-S signature. That is the whole of what "Nenya checks no signature and judges
     * no feature bit" means in practice, and it is asserted as a computed set so a fourth appearing
     * — or one of the three ceasing to parse — is caught.
     */
    @JsName("the_departures_from_bolt11_are_exactly_these")
    @Test
    fun `the departures from BOLT-11 are exactly these`() {
        val all = examples
        Bolt11Examples.assertDocumentedCounts(all)

        val parsed = LinkedHashSet<String>()
        val refusedUppercase = LinkedHashSet<String>()
        var recogniseAttempts = 0
        var parseAttempts = 0

        for (example in all) {
            recogniseAttempts++
            val label = labelOf(all, example)
            val reference = try {
                Bolt11Reference.recognise(example.invoice)
            } catch (refused: SettlementException) {
                if (refused.reason == SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED) {
                    refusedUppercase += label
                }
                continue
            }
            parseAttempts++
            // A typed catch, never `runCatching`: anything the parser throws that is not one of its
            // own named refusals is a decoder crashing on hostile input (§4.3), and counting it as
            // "did not parse" would hide exactly that.
            try {
                Bolt11Invoice.parse(reference)
                parsed += label
            } catch (refused: SettlementException) {
                if (refused.reason !in SettlementRejection.APPENDIX_C_PARSER) {
                    fail("$label was refused by the parser with ${refused.reason}, which is not a parser reason")
                }
            }
        }

        assertEquals(
            Bolt11Examples.VALID_COUNT + Bolt11Examples.INVALID_COUNT,
            recogniseAttempts,
            "every example must be fed to the recogniser",
        )
        assertTrue(parseAttempts > recogniseAttempts / 2, "only $parseAttempts examples reached the parser")

        assertEquals(
            setOf("invalid 1", "invalid 5", "invalid 10"),
            parsed.filter { it.startsWith("invalid") }.toSet(),
            "the invalid examples Nenya parses are the ones whose defect is a rule it does not apply: " +
                "an unknown even feature bit, an unrecoverable signature, and a high-S signature",
        )
        assertEquals(
            setOf("valid 13", "invalid 4"),
            refusedUppercase,
            "the examples refused for case must be exactly the all-uppercase one and the mixed-case one",
        )
        assertEquals(
            setOf("valid 13"),
            (1..Bolt11Examples.VALID_COUNT).map { "valid $it" }.toSet() - parsed,
            "the only valid example Nenya does not parse is the all-uppercase one (§4.3)",
        )
    }

    private fun labelOf(all: List<Bolt11Examples.Example>, example: Bolt11Examples.Example): String {
        val group = all.filter { it.group == example.group }
        val position = group.indexOf(example) + 1
        if (position == 0) fail("$example is not in its own group")
        return if (example.group == Group.VALID) "valid $position" else "invalid $position"
    }

    // -----------------------------------------------------------------------------------------
    // Negative controls at the recogniser: the call throws, so no parse exists to assert on.
    // -----------------------------------------------------------------------------------------

    /**
     * The four vendored examples and the one valid example the **recogniser** refuses, each with the
     * reason it gives.
     *
     * Named at that door and never duplicated in the parser: `Bolt11Invoice.parse` takes a
     * `Bolt11Reference`, and for these five there is no `Bolt11Reference` to hand it.
     */
    @JsName("the_recogniser_refuses_these_examples_before_any_parse_exists")
    @Test
    fun `the recogniser refuses these examples before any parse exists`() {
        val valid = valid()
        val invalid = invalid()
        val controls = listOf(
            Triple("invalid 3 (no separator)", invalid[2], SettlementRejection.INVOICE_MALFORMED),
            Triple("invalid 4 (mixed case)", invalid[3], SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED),
            Triple("invalid 6 (too short)", invalid[5], SettlementRejection.INVOICE_MALFORMED),
            Triple("invalid 7 (invalid multiplier)", invalid[6], SettlementRejection.INVOICE_MALFORMED),
            Triple("valid 13 (all uppercase)", valid[12], SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED),
        )
        // The controls are the examples the document says they are, and not whatever sits at an index.
        assertTrue("no 1" in controls[0].second.heading, "invalid 3 is ${controls[0].second}")
        assertTrue("mixed case" in controls[1].second.heading, "invalid 4 is ${controls[1].second}")
        assertTrue("too short" in controls[2].second.heading.lowercase(), "invalid 6 is ${controls[2].second}")
        assertTrue("multiplier" in controls[3].second.heading, "invalid 7 is ${controls[3].second}")
        assertTrue("upper case" in controls[4].second.heading, "valid 13 is ${controls[4].second}")

        val reasons = LinkedHashMap<String, SettlementRejection>()
        for ((label, example, expected) in controls) {
            val reason = reasonOf(label) { Bolt11Reference.recognise(example.invoice) }
            reasons[label] = reason
            assertEquals(expected, reason, "$label: ${example.heading}")
        }
        assertEquals(controls.size, reasons.size, "every recogniser control must run")
        assertEquals(
            setOf(SettlementRejection.INVOICE_MALFORMED, SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED),
            reasons.values.toSet(),
            "both recogniser reasons named above must be produced",
        )
    }

    /**
     * The corrected data-part floor, which no vendored vector reaches.
     *
     * `Bolt11Reference.MIN_DATA_CHARACTERS` used to be 111 — a timestamp and a signature — while the
     * value it was compared against still carried the 6-character checksum, so a data part six
     * characters short of holding both was accepted. The document's own "String is too short"
     * example has a 109-character data part and was refused either way, so the correction gets a
     * control derived from a vendored invoice instead: every tagged field removed, then the signature
     * truncated by one to six groups, which walks the data part down from 117 to 111.
     */
    @JsName("the_data_part_floor_counts_the_checksum")
    @Test
    fun `the data part floor counts the checksum`() {
        // Computed from the composer's own widths, which are held to the vendored strings and share
        // no constant with production code.
        val floor = Bolt11Composer.TIMESTAMP_GROUPS + Bolt11Composer.SIGNATURE_GROUPS +
            Bolt11Composer.CHECKSUM_GROUPS
        assertEquals(floor, Bolt11Reference.MIN_DATA_CHARACTERS, "the floor is timestamp + signature + checksum")
        val bare = Bolt11Composer.decompose(lowercaseValid().first().invoice).withFields(emptyList())
        val failures = ArrayList<String>()
        for (k in 0..Bolt11Composer.CHECKSUM_GROUPS) {
            val derived = Bolt11Composer.compose(bare.withSignatureGroups(Bolt11Composer.SIGNATURE_GROUPS - k))
            val length = derived.length - derived.lastIndexOf(Bolt11Composer.SEPARATOR) - 1
            if (length != floor - k) failures += "k=$k: data part is $length characters, not ${floor - k}"
            if (k == 0) {
                // Recognised at exactly the floor. It carries no tagged field, so the *parser* then
                // refuses it — at the right door, and for the right reason.
                Bolt11Reference.recognise(derived)
                val reason = reasonOf("k=0") { Bolt11Invoice.parse(Bolt11Reference.recognise(derived)) }
                if (reason != SettlementRejection.PAYMENT_HASH_MISSING) failures += "k=0: parser said $reason"
            } else {
                val reason = reasonOf("k=$k") { Bolt11Reference.recognise(derived) }
                if (reason != SettlementRejection.INVOICE_MALFORMED) failures += "k=$k: recogniser said $reason"
            }
        }
        assertEquals(emptyList(), failures, "data parts from the floor down to six characters below it")
    }

    // -----------------------------------------------------------------------------------------
    // Negative controls at the parser.
    // -----------------------------------------------------------------------------------------

    /** One parser control: a label, the invoice it is built from, and the reason it must give. */
    private class Control(val label: String, val invoice: String, val expected: SettlementRejection)

    /**
     * Every parser refusal, each from a vendored example or a composer derivation of one.
     *
     * The base for every derivation is the first all-lowercase valid example — the "any amount"
     * donation invoice, which carries a `p`, an `s`, a `d` and a `9` and neither an amount nor an
     * `x`, so each derivation below adds or removes exactly the one thing it is about.
     */
    private fun parserControls(): List<Control> {
        val invalid = invalid()
        val base = Bolt11Composer.decompose(lowercaseValid().first().invoice)
        val descriptionHash = Bolt11Composer.decompose(
            lowercaseValid().first { Bolt11Composer.decompose(it.invoice).field('h') != null }.invoice,
        ).field('h')!!.groups
        // A vendored figure, never one written here.
        val figure = examples.mapNotNull { it.amount }.first().number
        val aboveCap = (Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_BTC + 1L).toString()

        return listOf(
            Control("invalid 2 (bad checksum)", invalid[1].invoice, SettlementRejection.CHECKSUM_INVALID),
            Control("invalid 8 (sub-millisatoshi)", invalid[7].invoice, SettlementRejection.AMOUNT_SUB_MSAT),
            Control("invalid 9 (no payment secret)", invalid[8].invoice, SettlementRejection.PAYMENT_SECRET_MISSING),
            Control(
                "two correct-length `p` fields",
                Bolt11Composer.compose(base.plusField('p', base.field('p')!!.groups)),
                SettlementRejection.PAYMENT_HASH_DUPLICATED,
            ),
            Control(
                "no `p` field",
                Bolt11Composer.compose(base.withoutField('p')),
                SettlementRejection.PAYMENT_HASH_MISSING,
            ),
            Control(
                "no `s` field",
                Bolt11Composer.compose(base.withoutField('s')),
                SettlementRejection.PAYMENT_SECRET_MISSING,
            ),
            Control(
                "two `x` fields",
                Bolt11Composer.compose(
                    base.plusField('x', Bolt11Composer.groupsOfNumber(1L, 2))
                        .plusField('x', Bolt11Composer.groupsOfNumber(2L, 2)),
                ),
                SettlementRejection.EXPIRY_DUPLICATED,
            ),
            Control(
                "both a `d` and an `h`",
                Bolt11Composer.compose(base.plusField('h', descriptionHash)),
                SettlementRejection.DESCRIPTION_NOT_EXACTLY_ONE,
            ),
            Control(
                "neither a `d` nor an `h`",
                Bolt11Composer.compose(base.withoutField('d')),
                SettlementRejection.DESCRIPTION_NOT_EXACTLY_ONE,
            ),
            Control("a field whose length runs past the signature", truncatedField(base), SettlementRejection.FIELD_TRUNCATED),
            Control(
                "an `x` of 2^64",
                Bolt11Composer.compose(base.plusField('x', sixtyFiveBitGroups(LEADING_GROUP_AT_TWO_TO_THE_64, 0L))),
                SettlementRejection.EXPIRY_OUT_OF_RANGE,
            ),
            Control(
                "a `c` of 2^64",
                Bolt11Composer.compose(base.plusField('c', sixtyFiveBitGroups(LEADING_GROUP_AT_TWO_TO_THE_64, 0L))),
                SettlementRejection.EXPIRY_OUT_OF_RANGE,
            ),
            Control(
                "a whole-BTC amount one above the supply cap",
                Bolt11Composer.compose(base.withAmount(aboveCap)),
                SettlementRejection.AMOUNT_OUT_OF_RANGE,
            ),
            Control(
                "an amount with a leading zero",
                Bolt11Composer.compose(base.withAmount("0$figure")),
                SettlementRejection.HRP_INVALID,
            ),
            Control(
                "an amount of zero",
                Bolt11Composer.compose(base.withAmount("0")),
                SettlementRejection.HRP_INVALID,
            ),
        )
    }

    /**
     * Each control refuses with the reason it names — **and** the reasons between them are exactly
     * `SettlementRejection.APPENDIX_C_PARSER`.
     *
     * The second half is the non-vacuity floor, and it is enumerated from the production constant set
     * rather than from a list written here: a rejection the parser can produce and nothing exercises,
     * and a constant the parser can no longer produce at all, both turn this red.
     */
    @JsName("every_parser_rejection_is_produced_by_a_control_and_no_control_produces_another")
    @Test
    fun `every parser rejection is produced by a control, and no control produces another`() {
        val controls = parserControls()
        val produced = LinkedHashSet<SettlementRejection>()
        val failures = ArrayList<String>()
        for (control in controls) {
            val reason = try {
                Bolt11Invoice.parse(Bolt11Reference.recognise(control.invoice)).let { null }
            } catch (refused: SettlementException) {
                refused.reason
            }
            if (reason == null) {
                failures += "${control.label}: parsed, where ${control.expected} was required"
                continue
            }
            produced += reason
            if (reason != control.expected) failures += "${control.label}: $reason != ${control.expected}"
        }
        assertEquals(emptyList(), failures, "parser controls that did not refuse as named")
        assertEquals(
            SettlementRejection.APPENDIX_C_PARSER,
            produced,
            "the reasons the controls produce must be exactly the parser's published set",
        )
    }

    /**
     * A seeded single-character substitution in every all-lowercase valid example is caught by the
     * checksum, and by the checksum **first**.
     *
     * bech32's BCH code detects any single-character substitution, so all fifteen must come back
     * `CHECKSUM_INVALID` — including the ones where the substitution also breaks a field length,
     * which is what shows the checksum is verified before the walk rather than after it.
     */
    @JsName("a_single_substituted_character_in_every_valid_example_is_caught_by_the_checksum")
    @Test
    fun `a single substituted character in every valid example is caught by the checksum`() {
        val random = JdkRandom(seed)
        val lowercase = lowercaseValid()
        assertEquals(lowercaseValidCount, lowercase.size, "valid examples fed to the substitution control")
        val failures = ArrayList<String>()
        for (example in lowercase) {
            val separator = example.invoice.lastIndexOf(Bolt11Composer.SEPARATOR)
            val hrp = example.invoice.substring(0, separator)
            val data = example.invoice.substring(separator + 1)
            val at = random.nextInt(data.length)
            val original = Bolt11Reference.BECH32_ALPHABET.indexOf(data[at])
            val substitute = (original + 1 + random.nextInt(31)) % 32
            if (substitute == original) {
                failures += "$example: the substitution changed nothing"
                continue
            }
            val mutated = hrp + Bolt11Composer.SEPARATOR + data.substring(0, at) +
                Bolt11Reference.BECH32_ALPHABET[substitute] + data.substring(at + 1)
            if (mutated == example.invoice) failures += "$example: the derivation changed nothing"
            val reason = reasonOf("$example") { Bolt11Invoice.parse(Bolt11Reference.recognise(mutated)) }
            if (reason != SettlementRejection.CHECKSUM_INVALID) failures += "$example: $reason at index $at"
        }
        assertEquals(emptyList(), failures, "examples a one-character substitution did not turn CHECKSUM_INVALID")
    }

    // -----------------------------------------------------------------------------------------
    // The 64-bit boundary on `x` and `c`, which is a value bound and not a width bound.
    // -----------------------------------------------------------------------------------------

    /**
     * 2^63 and 2^64 − 1 parse and saturate; 2^64 is refused; and the bound is on the value.
     *
     * The three leading groups are derived from the arithmetic rather than written down: `8L shl 60`
     * is exactly 2^63, which a signed `Long` shows as `Long.MIN_VALUE`, and `16L shl 60` is exactly
     * 2^64, which it shows as zero. Those two identities are asserted here, so the constants below
     * cannot quietly mean something else.
     */
    @JsName("an_expiry_is_bounded_by_its_value_and_not_by_its_field_width")
    @Test
    fun `an expiry is bounded by its value and not by its field width`() {
        assertEquals(
            Long.MIN_VALUE,
            LEADING_GROUP_AT_TWO_TO_THE_63.toLong() shl TWELVE_GROUP_BITS,
            "8 << 60 is exactly 2^63",
        )
        assertEquals(
            0L,
            LEADING_GROUP_AT_TWO_TO_THE_64.toLong() shl TWELVE_GROUP_BITS,
            "16 << 60 is exactly 2^64, which a 64-bit register shows as zero",
        )
        assertEquals(
            -1L,
            (LEADING_GROUP_AT_THE_LARGEST_LEGAL_VALUE.toLong() shl TWELVE_GROUP_BITS) or
                ((1L shl TWELVE_GROUP_BITS) - 1L),
            "a leading group of 15 over twelve full groups is exactly 2^64 - 1",
        )

        val base = Bolt11Composer.decompose(lowercaseValid().first().invoice)
        fun expiryOf(groups: List<Int>): Bolt11Invoice =
            parse(Bolt11Composer.compose(base.plusField('x', groups)))

        // 2^63 and 2^64 - 1 both parse, saturated: for §9.2 check 5 that is an expiry that never
        // runs out, and a negative one would have expired the invoice instantly instead.
        assertEquals(
            Long.MAX_VALUE,
            expiryOf(sixtyFiveBitGroups(LEADING_GROUP_AT_TWO_TO_THE_63, 0L)).expirySeconds,
            "an `x` of 2^63",
        )
        assertEquals(
            Long.MAX_VALUE,
            expiryOf(
                sixtyFiveBitGroups(
                    LEADING_GROUP_AT_THE_LARGEST_LEGAL_VALUE,
                    (1L shl TWELVE_GROUP_BITS) - 1L,
                ),
            ).expirySeconds,
            "an `x` of 2^64 - 1",
        )
        // 2^64 itself is refused — asserted in the control table, restated here as the boundary.
        assertEquals(
            SettlementRejection.EXPIRY_OUT_OF_RANGE,
            reasonOf("2^64") { expiryOf(sixtyFiveBitGroups(LEADING_GROUP_AT_TWO_TO_THE_64, 0L)) },
        )
        // And the bound is on the value, never on the width: a field of leading zeros holding a
        // small number is that number, however wide it is. Both a 13-group field — the width at
        // which the refusal can bite — and a much wider one, because the leading-zero skip is not
        // load-bearing at 13 groups and a control that stopped there would leave it unproven.
        for (width in listOf(SIXTY_FIVE_BIT_GROUPS, WIDE_GROUPS)) {
            assertEquals(
                Bolt11Invoice.DEFAULT_EXPIRY_SECONDS,
                expiryOf(paddedGroups(width, Bolt11Invoice.DEFAULT_EXPIRY_SECONDS)).expirySeconds,
                "a $width-group field holding the default value",
            )
        }
        assertTrue(WIDE_GROUPS > SIXTY_FIVE_BIT_GROUPS, "the wide control must be wider than the bound")

        // A repeated `c` is bounds-checked on every occurrence, not only on the one that wins:
        // Appendix C states the rule over the field. A first `c` inside the range followed by a
        // second at 2^64 is refused.
        assertEquals(
            SettlementRejection.EXPIRY_OUT_OF_RANGE,
            reasonOf("a second `c` at 2^64") {
                parse(
                    Bolt11Composer.compose(
                        base.plusField('c', Bolt11Composer.groupsOfNumber(Bolt11Invoice.DEFAULT_MIN_FINAL_CLTV_EXPIRY, 2))
                            .plusField('c', sixtyFiveBitGroups(LEADING_GROUP_AT_TWO_TO_THE_64, 0L)),
                    ),
                )
            },
        )
    }

    /** A 65-bit field: a leading group, then twelve more holding [low]. */
    private fun sixtyFiveBitGroups(leading: Int, low: Long): List<Int> =
        listOf(leading) + Bolt11Composer.groupsOfNumber(low, TWELVE_GROUPS)

    /** [value] as [width] five-bit groups, zero-padded on the left. */
    private fun paddedGroups(width: Int, value: Long): List<Int> =
        List(width - TWELVE_GROUPS) { 0 } + Bolt11Composer.groupsOfNumber(value, TWELVE_GROUPS)

    // -----------------------------------------------------------------------------------------
    // The amount arithmetic, over multipliers no vendored example carries.
    // -----------------------------------------------------------------------------------------

    /**
     * Two multipliers the vendored corpus never uses — `n`, and none at all — computed from
     * Appendix C's table, plus the supply cap exactly.
     *
     * The vendored examples between them use `m`, `u` and `p` only, so without this the `n` row and
     * the whole-BTC row of the parser's table would be unexercised, and the mutation that drops
     * either would stay green.
     */
    @JsName("the_multipliers_no_vendored_example_carries_are_computed_from_the_table")
    @Test
    fun `the multipliers no vendored example carries are computed from the table`() {
        val base = Bolt11Composer.decompose(lowercaseValid().first().invoice)
        val figure = examples.mapNotNull { it.amount }.first().number
        val used = examples.mapNotNull { it.amount?.letter }.filter { it.isNotEmpty() }.toSet()
        assertTrue("n" !in used, "a vendored example already carries an `n` amount: $used")
        assertTrue(used.isNotEmpty(), "no vendored example states a multiplier at all")

        for (letter in listOf("n", "")) {
            val row = AppendixC.multipliers.single { it.letter == letter.firstOrNull() }
            val expected = Msat.ofMsat(figure.toLong() * row.numerator / row.denominator)
            assertEquals(
                expected,
                parse(Bolt11Composer.compose(base.withAmount(figure + letter))).amount,
                "an amount of '$figure$letter'",
            )
        }

        // The cap itself parses, which is the boundary the out-of-range control sits one above.
        val cap = (Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_BTC).toString()
        assertEquals(
            Msat.ofMsat(Msat.SUPPLY_CAP_MSAT),
            parse(Bolt11Composer.compose(base.withAmount(cap))).amount,
            "a whole-BTC amount at exactly the supply cap",
        )
    }

    /**
     * The document's stated figure and Appendix C's table, multiplied here rather than transcribed.
     *
     * `number` is the decimal the document prints beside the invoice, `letter` its multiplier; the
     * factor is the specification's. Division comes first for the same reason the parser does it
     * first — the pico example's figure times its numerator would be a different number entirely if
     * the order were swapped.
     */
    private fun millisatoshisOf(amount: Bolt11Examples.StatedAmount): Msat {
        val row = AppendixC.multipliers.single { it.letter == amount.letter.firstOrNull() }
        val figure = amount.number.toLong()
        if (figure % row.denominator != 0L) {
            fail("'${amount.written}' is not a whole number of millisatoshis under $row")
        }
        return Msat.ofMsat(figure / row.denominator * row.numerator)
    }

    /**
     * [base] with its first tagged field's `data_length` set to 1023 groups, which runs past the
     * signature.
     *
     * Built out of the raw groups rather than through `Bolt11Parts`, because that type writes a
     * field's length back out from its data and so cannot express a length that disagrees with it.
     * The checksum is recomputed over the altered groups, so the parser reaches the field walk rather
     * than stopping at `CHECKSUM_INVALID`.
     */
    private fun truncatedField(base: Bolt11Parts): String {
        val covered = Bolt11Composer.coveredGroups(base).toMutableList()
        val lengthAt = Bolt11Composer.TIMESTAMP_GROUPS + 1
        covered[lengthAt] = 31
        covered[lengthAt + 1] = 31
        val groups = covered + base.signature
        return base.hrp + Bolt11Composer.SEPARATOR + Bolt11Composer.charactersOf(groups) +
            Bolt11Composer.checksum(base.hrp, groups)
    }

    // -----------------------------------------------------------------------------------------
    // What this parser does not do, stated as a test rather than only as prose.
    // -----------------------------------------------------------------------------------------

    /**
     * The parser reports no signature check, and there is no constant for one.
     *
     * `PaymentCheck` is what §17 item 6's machine-readable statement is built from, and T20 refuses
     * `paid` while any applicable check is unperformed. A constant for "signature verified" would
     * therefore be unperformable *and* blocking: every priced order would be stranded. So the
     * assertion is that no such constant exists, made over the enum rather than over a list.
     */
    @JsName("no_payment_check_constant_claims_anything_about_a_signature")
    @Test
    fun `no PaymentCheck constant claims anything about a signature`() {
        val named = dev.eryalabs.nenya.payment.PaymentCheck.entries.filter {
            "SIGNATURE" in it.name || "NODE" in it.name || "RECOVER" in it.name
        }
        assertEquals(emptyList(), named, "no PaymentCheck may name a signature, a node key or key recovery")
        // And the values that would let one be claimed are published as data, not as a verdict.
        val invoice = parse(lowercaseValid().first().invoice)
        assertEquals(64, invoice.signature().size)
        assertEquals(32, invoice.signedDataSha256().size)
        assertTrue(invoice.recoveryId in 0..255, "the recovery id is read, never judged")
        assertNull(invoice.amount, "the first lowercase valid example is an `any amount` invoice")
    }
}
