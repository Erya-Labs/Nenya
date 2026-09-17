package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.crypto.sha256
import dev.eryalabs.nenya.oracleSha256
import dev.eryalabs.nenya.settlement.Bolt11Examples.Group
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [Bolt11Composer] held to **externally authored strings**, which is the only thing that makes a
 * test-side encoder worth having.
 *
 * Nothing here is typed out. Every invoice comes from the vendored BOLT-11 document through
 * [Bolt11Examples], and every expected value — timestamp, payment hash, description, expiry,
 * signature, signing data and its digest — is what that document *states in prose* beside the
 * invoice it belongs to. The composer is never checked against a Nenya decoder: it is checked
 * against 26 strings written by people who do not have this repository, which is what would catch
 * a wrong alphabet, a wrong checksum constant, a wrong split point or a wrong bit order that a
 * decoder written from the same misunderstanding would happily agree with.
 *
 * Each test that consumes a family of examples asserts **how many** it consumed, against the counts
 * `Bolt11ExamplesTest` pins independently. An extractor that quietly stopped stating values would
 * otherwise let this file pass by comparing nothing.
 */
class Bolt11ComposerTest {

    /** Pinned so a failure is reproducible; see [JdkRandom] on why it is the JDK's algorithm. */
    private val seed: Long = 20260917L

    private val examples: List<Bolt11Examples.Example> get() = Bolt11Examples.extract()

    /**
     * The counts `Bolt11ExamplesTest` pins for the same values, added across the two groups. They
     * are repeated rather than derived: a count computed from the extractor would fall silently to
     * zero with it, which is the vacuity this floor exists to stop.
     */
    private val statedTimestamps = 16
    private val statedPaymentHashes = 4
    private val statedDescriptions = 9
    private val statedExpiries = 3
    private val signatureBreakdowns = 11

    private fun lowercaseValid(): List<Bolt11Examples.Example> =
        examples.filter { it.group == Group.VALID && it.invoice == it.invoice.lowercase() }

    // ---- (1) the composer reproduces every vendored string character for character ----

    @JsName("every_all_lowercase_valid_example_recomposes_character_for_character")
    @Test
    fun `every all-lowercase valid example recomposes character for character`() {
        val all = examples
        Bolt11Examples.assertDocumentedCounts(all)
        val valid = all.filter { it.group == Group.VALID }
        val uppercase = valid.count { it.invoice == it.invoice.uppercase() }
        assertEquals(1, uppercase, "all-uppercase valid examples")
        val lowercase = lowercaseValid()
        assertEquals(
            Bolt11Examples.VALID_COUNT - uppercase,
            lowercase.size,
            "valid examples fed to the round trip",
        )
        val failures = lowercase.filter { Bolt11Composer.compose(Bolt11Composer.decompose(it.invoice)) != it.invoice }
        assertEquals(emptyList(), failures.map { it.toString() }, "examples the composer did not reproduce")
    }

    @JsName("the_all_uppercase_example_recomposes_once_lowercased")
    @Test
    fun `the all-uppercase example recomposes once lowercased`() {
        val uppercase = examples.single { it.group == Group.VALID && it.invoice == it.invoice.uppercase() }
        assertNotEquals(uppercase.invoice, uppercase.invoice.lowercase(), "the example is genuinely uppercase")
        val lowered = uppercase.invoice.lowercase()
        assertEquals(lowered, Bolt11Composer.compose(Bolt11Composer.decompose(lowered)), "$uppercase")
    }

    /**
     * The two examples that make "split at the **last** `1`" load-bearing rather than trivia.
     *
     * Both carry a `1` inside Appendix C's optional amount, so a first-`1` rule cuts the string in
     * the wrong place: valid example 15's human-readable part becomes `lnbc` and invalid example
     * 8's becomes `lnbc250000000`, and in each case the remainder still holds a `1`, which is not
     * a bech32 character at all. Nothing else in this file reaches invalid example 8, so without
     * this test the split point would be unproven for it.
     */
    @JsName("the_split_is_at_the_last_separator_which_is_what_the_two_amount_bearing_examples_need")
    @Test
    fun `the split is at the last separator, which is what the two amount-bearing examples need`() {
        val valid = examples.filter { it.group == Group.VALID }
        val invalid = examples.filter { it.group == Group.INVALID }
        val example15 = valid[14]
        val invalid8 = invalid[7]
        assertTrue("payment metadata" in example15.heading, "valid example 15 is $example15")
        assertTrue("sub-millisatoshi" in invalid8.heading, "invalid example 8 is $invalid8")
        // Collected rather than fail-fast, so a wrong split point names *both* examples and not
        // merely whichever one the loop reached first.
        val failures = ArrayList<String>()
        for (example in listOf(example15, invalid8)) {
            val parts = runCatching { Bolt11Composer.decompose(example.invoice) }
                .getOrElse { failures += "$example: decompose threw ${it.message}"; null } ?: continue
            if (Bolt11Composer.SEPARATOR !in parts.hrp) {
                failures += "$example: its human-readable part must itself carry a '${Bolt11Composer.SEPARATOR}'"
            }
            if (Bolt11Composer.compose(parts) != example.invoice) failures += "$example: did not recompose"
        }
        assertEquals(emptyList(), failures, "examples whose `1` lives inside the human-readable part")
    }

    // ---- (2) the decomposition agrees with what the document states in prose ----

    @JsName("the_decomposition_agrees_with_every_stated_timestamp_hash_description_and_expiry")
    @Test
    fun `the decomposition agrees with every stated timestamp, hash, description and expiry`() {
        var timestamps = 0
        var hashes = 0
        var descriptions = 0
        var expiries = 0
        val failures = ArrayList<String>()
        for (example in examples) {
            if (example.timestamp == null && example.paymentHashHex == null &&
                example.description == null && example.expirySeconds == null
            ) {
                continue
            }
            val parts = Bolt11Composer.decompose(example.invoice.lowercase())
            example.timestamp?.let {
                timestamps++
                if (parts.timestampSeconds != it) failures += "$example: timestamp ${parts.timestampSeconds} != $it"
            }
            example.paymentHashHex?.let {
                hashes++
                val read = parts.paymentHash()?.let(TestText::lowerHex)
                if (read != it) failures += "$example: payment hash $read != $it"
            }
            example.description?.let {
                descriptions++
                val read = parts.field('d')?.bytes()?.let(TestText::lowerHex)
                val stated = TestText.lowerHex(TestText.utf8(it))
                if (read != stated) failures += "$example: description $read != $stated"
            }
            example.expirySeconds?.let {
                expiries++
                val read = parts.field('x')?.number()
                if (read != it) failures += "$example: expiry $read != $it"
            }
        }
        assertEquals(emptyList(), failures, "decompositions that disagree with the document's prose")
        assertEquals(
            listOf(statedTimestamps, statedPaymentHashes, statedDescriptions, statedExpiries),
            listOf(timestamps, hashes, descriptions, expiries),
            "examples fed to the timestamp, payment-hash, description and expiry anchors",
        )
    }

    // ---- (3) the signature groups and the data they cover reproduce every stated breakdown ----

    @JsName("every_signature_breakdown_reproduces_from_the_decomposed_groups")
    @Test
    fun `every signature breakdown reproduces from the decomposed groups`() {
        val breakdowns = examples.mapNotNull { e -> e.signature?.let { e to it } }
        assertEquals(signatureBreakdowns, breakdowns.size, "examples fed to the signature anchor")
        val failures = ArrayList<String>()
        for ((example, stated) in breakdowns) {
            val parts = Bolt11Composer.decompose(example.invoice.lowercase())
            assertEquals(
                Bolt11Composer.SIGNATURE_GROUPS,
                parts.signature.size,
                "$example: Appendix C's signature is ${Bolt11Composer.SIGNATURE_GROUPS} groups",
            )
            // 104 groups is 520 bits, which is 65 bytes exactly: 64 of r||s and one recovery byte.
            val signature = Bolt11Composer.bytesOf(parts.signature)
            if (signature.size != 65) failures += "$example: signature packed to ${signature.size} bytes"
            val rs = TestText.lowerHex(signature.copyOf(64))
            if (rs != stated.signatureHex) failures += "$example: r||s $rs != ${stated.signatureHex}"
            val flag = signature[64].toInt() and 0xff
            if (flag != stated.recoveryFlag) failures += "$example: recovery flag $flag != ${stated.recoveryFlag}"
            val signing = Bolt11Composer.signingData(parts)
            val signingHex = TestText.lowerHex(signing)
            if (signingHex != stated.signingDataHex) failures += "$example: signing data $signingHex"
            // Nenya's own SHA-256, so the anchor runs through the hasher the NIST vectors prove.
            val digest = TestText.lowerHex(sha256(signing))
            if (digest != stated.signingDataSha256Hex) failures += "$example: signing digest $digest"
        }
        assertEquals(emptyList(), failures, "signature breakdowns the composer did not reproduce")
    }

    // ---- (4) derivations ----

    @JsName("derivation_setting_p_changes_only_the_p_groups_and_the_checksum")
    @Test
    fun `derivation - setting p changes only the p groups and the checksum`() {
        val example = lowercaseValid().first { it.paymentHashHex != null }
        val original = example.invoice
        val parts = Bolt11Composer.decompose(original)
        assertEquals(1, parts.fields('p').size, "$example must carry exactly one `p` field for this control")

        // Computed, never typed: seeded preimage bytes, hashed by the platform's own SHA-256.
        val preimage = ByteArray(32).also { JdkRandom(seed).nextBytes(it) }
        val hash = oracleSha256(preimage)
        val derived = Bolt11Composer.compose(parts.withPaymentHash(hash))

        assertNotEquals(original, derived, "the derivation changed nothing")
        assertEquals(original.length, derived.length, "a 52-group `p` field replaced a 52-group one")

        val read = Bolt11Composer.decompose(derived)
        assertEquals(TestText.lowerHex(hash), TestText.lowerHex(read.paymentHash()!!), "the derived payment hash")
        assertEquals(derived, Bolt11Composer.compose(read), "the derived invoice is itself stable")

        val dataAt = original.lastIndexOf(Bolt11Composer.SEPARATOR) + 1
        val before = parts.fields.indexOfFirst { it.type == 'p' }
            .let { at -> parts.fields.take(at).sumOf { 3 + it.groups.size } }
        val hashAt = dataAt + Bolt11Composer.TIMESTAMP_GROUPS + before + 3
        val hashGroups = hashAt until (hashAt + Bolt11Composer.HASH_GROUPS)
        val checksumAt = derived.length - Bolt11Composer.CHECKSUM_GROUPS until derived.length

        val differing = original.indices.filter { original[it] != derived[it] }
        assertTrue(differing.any { it in hashGroups }, "the `p` groups did not change")
        assertEquals(
            emptyList(),
            differing.filter { it !in hashGroups && it !in checksumAt },
            "characters that changed outside the `p` groups and the checksum",
        )
    }

    @JsName("derivation_no_tagged_fields_and_a_truncated_signature_give_a_data_part_of_117_minus_k")
    @Test
    fun `derivation - no tagged fields and a truncated signature give a data part of 117 minus k`() {
        val example = lowercaseValid().first()
        val bare = Bolt11Composer.decompose(example.invoice).withFields(emptyList())
        assertEquals(emptyList(), bare.fields, "every tagged field removed")
        for (k in 0..6) {
            val derived = Bolt11Composer.compose(bare.withSignatureGroups(Bolt11Composer.SIGNATURE_GROUPS - k))
            val separator = derived.lastIndexOf(Bolt11Composer.SEPARATOR)
            assertEquals(bare.hrp, derived.substring(0, separator), "k=$k: the human-readable part is unchanged")
            assertEquals(117 - k, derived.length - separator - 1, "k=$k: data part length including the checksum")
            // A truncated string is a recogniser fixture: decompose refuses to read one back.
            if (k == 0) {
                assertEquals(derived, Bolt11Composer.compose(Bolt11Composer.decompose(derived)), "k=0 still round-trips")
            } else {
                assertFailsWith<IllegalArgumentException>("k=$k") { Bolt11Composer.decompose(derived) }
            }
        }
    }

    @JsName("derivation_the_amount_and_the_timestamp_round_trip_through_the_human_readable_part")
    @Test
    fun `derivation - the amount and the timestamp round-trip through the human-readable part`() {
        val example = lowercaseValid().first()
        val parts = Bolt11Composer.decompose(example.invoice)

        val seconds = JdkRandom(seed).nextLong() and ((1L shl 35) - 1)
        val retimed = parts.withTimestamp(seconds)
        assertEquals(seconds, retimed.timestampSeconds, "the derived timestamp")
        assertEquals(seconds, Bolt11Composer.decompose(Bolt11Composer.compose(retimed)).timestampSeconds)

        // The written amount is another vendored example's, never one typed here.
        val written = examples.mapNotNull { it.amount }.first { it.written != parts.amount }.written
        val repriced = parts.withAmount(written)
        assertEquals(written, repriced.amount, "the derived amount")
        assertEquals(parts.networkPrefix, repriced.networkPrefix, "the network prefix is untouched")
        assertEquals(repriced.hrp, Bolt11Composer.decompose(Bolt11Composer.compose(repriced)).hrp)

        val unpriced = parts.withAmount("")
        assertEquals("", unpriced.amount, "an emptied amount")
        assertEquals(parts.networkPrefix, unpriced.hrp, "an invoice with no amount is just the network prefix")
    }

    // ---- negative controls, all from the vendored vectors ----

    @JsName("negative_control_the_invalid_checksum_example_does_not_recompute_to_what_it_carries")
    @Test
    fun `negative control - the invalid-checksum example does not recompute to what it carries`() {
        val invalid = examples.filter { it.group == Group.INVALID }
        val example = invalid[1]
        assertTrue("checksum is invalid" in example.heading.lowercase(), "invalid example 2 is $example")
        val parts = Bolt11Composer.decompose(example.invoice)
        assertNotEquals(parts.checksum, parts.recomputedChecksum, "$example carries the checksum it should")
        assertNotEquals(example.invoice, Bolt11Composer.compose(parts), "$example recomposed to itself")
    }

    @JsName("negative_control_the_mixed_case_example_lowercased_carries_a_correct_checksum")
    @Test
    fun `negative control - the mixed-case example lowercased carries a correct checksum`() {
        val invalid = examples.filter { it.group == Group.INVALID }
        val example = invalid[3]
        assertTrue("mixed case" in example.heading.lowercase(), "invalid example 4 is $example")
        assertNotEquals(example.invoice, example.invoice.lowercase(), "$example is genuinely mixed-case")
        val lowered = example.invoice.lowercase()
        val parts = Bolt11Composer.decompose(lowered)
        assertEquals(parts.checksum, parts.recomputedChecksum, "$example fails on case alone")
        assertEquals(lowered, Bolt11Composer.compose(parts))
    }

    @JsName("negative_control_one_substituted_character_changes_every_valid_example_s_checksum")
    @Test
    fun `negative control - one substituted character changes every valid example's checksum`() {
        val random = JdkRandom(seed)
        val valid = lowercaseValid()
        assertEquals(Bolt11Examples.VALID_COUNT - 1, valid.size, "valid examples fed to the substitution control")
        val failures = ArrayList<String>()
        for (example in valid) {
            val separator = example.invoice.lastIndexOf(Bolt11Composer.SEPARATOR)
            val hrp = example.invoice.substring(0, separator)
            val data = example.invoice.substring(separator + 1)
            val carried = data.substring(data.length - Bolt11Composer.CHECKSUM_GROUPS)
            val body = Bolt11Composer.groupsOf(data.substring(0, data.length - Bolt11Composer.CHECKSUM_GROUPS))
            if (Bolt11Composer.checksum(hrp, body) != carried) failures += "$example: unaltered checksum"

            val at = random.nextInt(body.size)
            val substitute = (body[at] + 1 + random.nextInt(31)) % 32
            if (substitute == body[at]) failures += "$example: the substitution changed nothing"
            val mutated = body.toMutableList().also { it[at] = substitute }
            if (Bolt11Composer.checksum(hrp, mutated) == carried) failures += "$example: checksum survived at $at"
        }
        assertEquals(emptyList(), failures, "examples whose checksum a substitution did not change")
    }
}
