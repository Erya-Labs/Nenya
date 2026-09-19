package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.TestText
import kotlin.test.fail

/**
 * A **test-side** BOLT-11 composer: it takes a vendored invoice apart, lets a test change one
 * part, and puts it back together with the bech32 checksum recomputed.
 *
 * ### Why this exists, and what stops it inventing its own dialect
 *
 * The 26 vendored examples (`vectors/bolt11/11-payment-encoding.md`, read through
 * [Bolt11Examples]) are the only invoices this repository is allowed to use — nothing here may be
 * typed by hand. But they are not enough on their own. None publishes a preimage, so no receipt
 * built from one can satisfy §9.2 check 3 once the payment hash is read out of the invoice; and a
 * parser's negative controls need shapes no example carries — two correct-length `p` fields, both
 * a `d` and an `h`, an amount above the supply cap, an `x` at or above `2^64`, a data part below
 * the recogniser's floor. Each of those is one vendored invoice with one part changed.
 *
 * The obvious danger in a test-side encoder is that it and the decoder written later share the
 * same wrong constant and agree with each other perfectly. So this file is never held to Nenya's
 * decoder. It is held to **externally authored strings**: `Bolt11ComposerTest` requires
 * `compose(decompose(x)) == x`, character for character, for every all-lowercase valid example,
 * and requires the decomposition to agree with the timestamps, payment hashes, descriptions,
 * expiries and signature breakdowns the upstream document *states in prose* beside each one. A
 * wrong alphabet, a wrong checksum constant, a wrong split point or a wrong bit order fails there
 * immediately. For the same reason the bech32 alphabet below is declared here rather than taken
 * from `Bolt11Reference`: sharing a constant with production code is exactly the coupling that
 * would let one mistake pass twice.
 *
 * ### The checksum constant is BIP-173's, not bech32m's
 *
 * `polymod(…) xor 1`. BOLT-11 predates bech32m and never moved: `xor 0x2bc830a3` fails every
 * vendored vector. There is also **no 90-character limit** here — BIP-173 imposes one on
 * addresses and BOLT-11 explicitly does not, and every vendored example is far longer.
 *
 * ### Signatures go stale, and that is fine
 *
 * Nothing in this file signs anything, and there is no secp256k1 in this repository to sign with.
 * The 104 signature groups are carried across a derivation **unchanged**, so a derived invoice's
 * signature no longer commits to its contents. That is deliberate and harmless: Nenya verifies no
 * invoice signature anywhere (Appendix C leaves it optional for a payer that is not doing key
 * recovery, and §9.2's evidence rule is the preimage, never the invoice's signature). A derived
 * invoice is a *parser* fixture and never evidence of anything.
 *
 * [Bolt11Parts.withSignatureGroups] goes further and truncates the signature, which exists for
 * exactly one caller: a control that needs a data part below `Bolt11Reference.MIN_DATA_CHARACTERS`
 * built from a vendored string rather than typed. A 7-character timestamp, a full signature and a
 * 6-character checksum already make 117 characters, so the only way down is to drop every tagged
 * field and then shorten the signature. **A truncated string is for a recogniser only**:
 * [Bolt11Composer.decompose] does not read one back, and the two floors are **not** the same number.
 * This one is 117: a timestamp, a full signature and the checksum. `Bolt11Reference`'s is 111 —
 * timestamp plus signature, with no checksum term, measured against a data part that carries one.
 * The six-character gap is what the truncation is for: it is the band a recogniser-floor control
 * lands in, still long enough for the recogniser and already too short to decompose.
 *
 * ### Pure values
 *
 * No clock, no randomness and no I/O: every derivation returns new [Bolt11Parts] and mutates
 * nothing. Runs on the JVM and on JavaScript alike.
 */
internal object Bolt11Composer {

    /**
     * BIP-173's bech32 character set, in BIP-173's own order, declared here rather than shared
     * with production code — see this object's note on coupling.
     */
    const val ALPHABET: String = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Appendix C: the first 35 bits of the data part, which is seven bech32 characters. */
    const val TIMESTAMP_GROUPS: Int = 7

    /** Appendix C: "the final 104 characters (512-bit signature + 1 recovery byte)". */
    const val SIGNATURE_GROUPS: Int = 104

    /** BIP-173's checksum is the last six characters of the data part. */
    const val CHECKSUM_GROUPS: Int = 6

    /** A 256-bit `p` or `s` field: 52 groups, which is 260 bits — 256 plus four bits of padding. */
    const val HASH_GROUPS: Int = 52

    /** BIP-173's separator, and Appendix C's: the **last** `1` in the string. */
    const val SEPARATOR: Char = '1'

    /** BIP-173, not bech32m. See this object's note. */
    private const val CHECKSUM_CONSTANT: Int = 1

    /** BIP-173's `bech32_polymod` generator. */
    private val GENERATOR: IntArray =
        intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** Appendix C's optional amount: a decimal figure and an optional multiplier letter. */
    private val AMOUNT = Regex("""^[0-9]+[munp]?$""")

    /**
     * Split [invoice] into its parts at the **last** [SEPARATOR].
     *
     * The last, never the first: the bech32 alphabet carries no `1`, so the separator is the only
     * `1` that can appear after the human-readable part — while the human-readable part itself may
     * hold one inside Appendix C's optional amount. Valid example 15 (`lnbc10m1…`) and invalid
     * example 8 (`lnbc2500000001p1…`) both split wrongly on a first-`1` rule.
     *
     * Refuses a string whose data part cannot hold Appendix C's timestamp, signature and checksum
     * — including anything [Bolt11Parts.withSignatureGroups] truncated, which is a recogniser
     * fixture and is not meant to be read back.
     */
    fun decompose(invoice: String): Bolt11Parts {
        val separator = invoice.lastIndexOf(SEPARATOR)
        require(separator >= 0) { "no '$SEPARATOR' separator in the invoice" }
        val hrp = invoice.substring(0, separator)
        val data = invoice.substring(separator + 1)
        val floor = TIMESTAMP_GROUPS + SIGNATURE_GROUPS + CHECKSUM_GROUPS
        require(data.length >= floor) {
            "a data part holds a $TIMESTAMP_GROUPS-group timestamp, a $SIGNATURE_GROUPS-group " +
                "signature and a $CHECKSUM_GROUPS-group checksum, so it cannot be shorter than " +
                "$floor characters; this one is ${data.length}"
        }
        val checksum = data.substring(data.length - CHECKSUM_GROUPS)
        val body = groupsOf(data.substring(0, data.length - CHECKSUM_GROUPS))
        val timestamp = body.subList(0, TIMESTAMP_GROUPS)
        val signature = body.subList(body.size - SIGNATURE_GROUPS, body.size)
        val middle = body.subList(TIMESTAMP_GROUPS, body.size - SIGNATURE_GROUPS)

        val fields = ArrayList<Bolt11TaggedField>()
        var at = 0
        while (at < middle.size) {
            require(at + 3 <= middle.size) {
                "a tagged field is a type group and a two-group data_length, and only " +
                    "${middle.size - at} groups are left"
            }
            val type = ALPHABET[middle[at]]
            val length = middle[at + 1] * 32 + middle[at + 2]
            require(at + 3 + length <= middle.size) {
                "tagged field '$type' states a data_length of $length, which runs past the " +
                    "${middle.size - at - 3} groups left before the signature"
            }
            fields.add(Bolt11TaggedField(type, middle.subList(at + 3, at + 3 + length).toList()))
            at += 3 + length
        }
        return Bolt11Parts(hrp, timestamp.toList(), fields.toList(), signature.toList(), checksum)
    }

    /**
     * [parts] as an invoice string, with the bech32 checksum **recomputed** — never the one
     * [decompose] read. That is what makes `compose(decompose(x)) == x` evidence: the six
     * characters at the end were derived from the rest, and matching the vendored string means the
     * derivation is right.
     */
    fun compose(parts: Bolt11Parts): String {
        val groups = dataGroups(parts)
        return parts.hrp + SEPARATOR + charactersOf(groups) + checksum(parts.hrp, groups)
    }

    /** The data part's groups before the checksum: timestamp, then every tagged field, then the signature. */
    fun dataGroups(parts: Bolt11Parts): List<Int> = coveredGroups(parts) + parts.signature

    /**
     * The groups a BOLT-11 signature covers: the timestamp and every tagged field, and not the
     * signature itself.
     */
    fun coveredGroups(parts: Bolt11Parts): List<Int> {
        val out = ArrayList<Int>(parts.timestamp.size + 16)
        out.addAll(parts.timestamp)
        for (field in parts.fields) {
            val length = field.groups.size
            require(length < 1024) { "a data_length is ten bits; '${field.type}' has $length groups" }
            val type = ALPHABET.indexOf(field.type)
            require(type >= 0) { "'${field.type}' is not a bech32 character" }
            out.add(type)
            out.add(length / 32)
            out.add(length % 32)
            out.addAll(field.groups)
        }
        return out
    }

    /**
     * BIP-173's checksum over [hrp] and [dataGroups], as its six bech32 characters.
     *
     * Exposed on its own so a negative control can recompute a checksum over groups it altered
     * without going back through [decompose] — a substituted character can change a tagged field's
     * `data_length` and make the string undecomposable, which would test the wrong thing.
     */
    fun checksum(hrp: String, dataGroups: List<Int>): String {
        val values = ArrayList<Int>(2 * hrp.length + 1 + dataGroups.size + CHECKSUM_GROUPS)
        for (character in hrp) values.add(character.code shr 5)
        values.add(0)
        for (character in hrp) values.add(character.code and 31)
        values.addAll(dataGroups)
        repeat(CHECKSUM_GROUPS) { values.add(0) }
        val polymod = polymod(values) xor CHECKSUM_CONSTANT
        return (0 until CHECKSUM_GROUPS)
            .map { ALPHABET[(polymod shr (5 * (CHECKSUM_GROUPS - 1 - it))) and 31] }
            .joinToString("")
    }

    /**
     * The bytes BOLT-11 signs over: the human-readable part's own bytes, then the timestamp and
     * every tagged field as a bit stream, "with 0 bits appended to pad to a byte boundary" (the
     * vendored document's words). The signature itself is not covered, which is why it stops
     * where it does.
     */
    fun signingData(parts: Bolt11Parts): ByteArray =
        TestText.utf8(parts.hrp) + paddedBytesOf(coveredGroups(parts))

    /** The bech32 characters [groups] stand for. */
    fun charactersOf(groups: List<Int>): String {
        val out = StringBuilder(groups.size)
        for (group in groups) {
            require(group in 0..31) { "a bech32 group is five bits; found $group" }
            out.append(ALPHABET[group])
        }
        return out.toString()
    }

    /** [characters] as five-bit groups. Fails on anything outside the alphabet, including uppercase. */
    fun groupsOf(characters: String): List<Int> = characters.map {
        val group = ALPHABET.indexOf(it)
        require(group >= 0) { "'$it' is not a bech32 character" }
        group
    }

    /**
     * [groups] as bytes, **dropping** the leftover bits at the end.
     *
     * That is the right reading for a field's value: Appendix C pads a field's data with zero bits
     * up to the next five-bit boundary, so a 256-bit payment hash occupies 52 groups (260 bits)
     * and the four trailing bits are padding rather than value.
     */
    fun bytesOf(groups: List<Int>): ByteArray {
        val out = ArrayList<Byte>(groups.size * 5 / 8)
        var accumulator = 0
        var bits = 0
        for (group in groups) {
            require(group in 0..31) { "a bech32 group is five bits; found $group" }
            accumulator = (accumulator shl 5) or group
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((accumulator shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    /** [groups] as bytes, **zero-padding** the last partial byte — BOLT-11's signing-data rule. */
    fun paddedBytesOf(groups: List<Int>): ByteArray {
        val bits = groups.size * 5
        val out = ByteArray((bits + 7) / 8)
        var accumulator = 0
        var held = 0
        var at = 0
        for (group in groups) {
            require(group in 0..31) { "a bech32 group is five bits; found $group" }
            accumulator = (accumulator shl 5) or group
            held += 5
            while (held >= 8) {
                held -= 8
                out[at++] = ((accumulator shr held) and 0xff).toByte()
            }
        }
        if (held > 0) out[at] = ((accumulator shl (8 - held)) and 0xff).toByte()
        return out
    }

    /** [bytes] as five-bit groups, zero-padded at the end to the next group boundary. */
    fun groupsOfBytes(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>((bytes.size * 8 + 4) / 5)
        var accumulator = 0
        var bits = 0
        for (byte in bytes) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((accumulator shr bits) and 31)
            }
        }
        if (bits > 0) out.add((accumulator shl (5 - bits)) and 31)
        return out
    }

    /** [groups] read big-endian as one number, which is how Appendix C reads a timestamp or an `x`. */
    fun numberOf(groups: List<Int>): Long {
        require(groups.size <= 12) { "${groups.size} groups is more than a Long can hold" }
        var value = 0L
        for (group in groups) {
            require(group in 0..31) { "a bech32 group is five bits; found $group" }
            value = (value shl 5) or group.toLong()
        }
        return value
    }

    /** [value] as exactly [size] five-bit groups, big-endian. */
    fun groupsOfNumber(value: Long, size: Int): List<Int> {
        require(size in 1..12) { "$size groups is not a number this writes" }
        require(value >= 0 && value < (1L shl (5 * size))) { "$value does not fit in $size five-bit groups" }
        return (0 until size).map { ((value shr (5 * (size - 1 - it))) and 31L).toInt() }
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum shr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (bit in 0..4) if ((top shr bit) and 1 == 1) checksum = checksum xor GENERATOR[bit]
        }
        return checksum
    }

    /** Appendix C's optional amount, as written — digits and an optional multiplier letter. */
    fun requireAmount(written: String): String {
        require(written.isEmpty() || AMOUNT.matches(written)) {
            "'$written' is not Appendix C's decimal amount and optional `m`/`u`/`n`/`p` multiplier"
        }
        return written
    }

    /**
     * [msat] millisatoshis written the way Appendix C writes an amount: a figure and, where one is
     * needed, a multiplier letter.
     *
     * ### Why this is here and not in a fixture file
     *
     * §9.2 check 4 compares an invoice's amount against what a payee is owed, so a fixture for it
     * has to be an invoice **for a chosen amount** — and there is no vendored example for
     * `price_msat`. This is the derivation that produces one: [Bolt11Parts.withAmount] takes the
     * written form, and this turns a figure into it. It sits beside the rest of the composer
     * because that is where the round-trip proof can reach it, which is the whole reason a derived
     * invoice is allowed in this repository at all (decision D).
     *
     * ### It is held to the vendored document, like everything else here
     *
     * The multiplier rows are Appendix C's own, parsed out of `spec/NENYA-1.md` at test time by
     * [AppendixC] — never a table written here — and `Bolt11ComposerTest` requires that for **every**
     * amount the vendored document states in prose, converting that stated figure to millisatoshis
     * and back through this function reproduces the document's own spelling character for
     * character. A wrong factor, a wrong rounding or a wrong choice of letter fails there.
     *
     * ### The coarsest exact unit wins, which is what the document writes
     *
     * Appendix C's notation is ambiguous — 100 000 000 msat is `1m` and `1000u` and `1000000n` —
     * so a writer has to choose. This one takes the largest unit that divides the figure exactly,
     * which is the spelling all thirteen vendored amounts use: `2500u` rather than `25000000n`
     * because 250 000 000 msat is not a whole number of milli-bitcoin, and `9678785340p` because
     * 967 878 534 msat is not a whole number of nano-bitcoin either. The round-trip test is what
     * says so rather than this paragraph.
     *
     * There is deliberately no notation for zero: BOLT-11's amount is "a positive decimal integer
     * with no leading zeroes", and an invoice that asks for nothing is the **absent** amount — the
     * "any amount" form, which is `withAmount("")` and which §9.2 check 4 rejects on sight.
     */
    fun writtenAmount(msat: Long): String {
        require(msat > 0) {
            "BOLT-11 writes no amount of $msat: a present amount is a positive decimal integer, " +
                "and an invoice that names no figure is the absent `withAmount(\"\")` form"
        }
        for (row in coarsestFirst()) {
            // The pico row multiplies before it divides, and the supply cap in pico-bitcoin is
            // above `Long.MAX_VALUE` — so the figure that cannot be written is skipped rather than
            // silently wrapped into one that can.
            if (msat > Long.MAX_VALUE / row.denominator) continue
            val scaled = msat * row.denominator
            if (scaled % row.numerator != 0L) continue
            return "${scaled / row.numerator}${row.letter ?: ""}"
        }
        fail("$msat msat is not a whole number of units under any row of Appendix C's table")
    }

    /**
     * Appendix C's rows, largest unit first, so [writtenAmount] meets the coarsest exact one first.
     *
     * Compared as a cross-multiplication rather than as a quotient, because the `p` row's
     * millisatoshis-per-unit is a tenth and integer division would flatten it to zero — putting the
     * finest unit joint-first with nothing and making the choice of spelling depend on sort
     * stability.
     */
    private fun coarsestFirst(): List<AppendixC.Multiplier> = AppendixC.multipliers.sortedWith { a, b ->
        (b.numerator * a.denominator).compareTo(a.numerator * b.denominator)
    }
}

/**
 * One tagged field: its type character, the `data_length` its two length groups state, and that
 * many five-bit groups.
 *
 * The length is not stored separately because [decompose][Bolt11Composer.decompose] uses it to
 * decide how many groups to take, so the two can never disagree — and
 * [dataGroups][Bolt11Composer.dataGroups] writes it back out from [groups] alone.
 */
internal class Bolt11TaggedField(val type: Char, val groups: List<Int>) {

    /** The `data_length` those two groups carry. */
    val dataLength: Int get() = groups.size

    /** The field's value with Appendix C's trailing padding bits dropped. */
    fun bytes(): ByteArray = Bolt11Composer.bytesOf(groups)

    /** The field's value read big-endian as one number, which is how Appendix C reads an `x`. */
    fun number(): Long = Bolt11Composer.numberOf(groups)

    override fun toString(): String = "tagged field '$type' ($dataLength groups)"
}

/**
 * A vendored invoice taken apart: the human-readable part, the seven timestamp groups, the tagged
 * fields in order, the signature groups and the six checksum characters **as they were read**.
 *
 * [checksum] is what the string carried; [recomputedChecksum] is what the rest of the parts imply.
 * They differ for exactly one vendored example — invalid example 2, whose defect is its checksum —
 * and a test that wants a derived invoice always takes [Bolt11Composer.compose], which recomputes.
 *
 * Every `with…` returns a new value. The signature is carried across unchanged and goes stale; see
 * [Bolt11Composer]'s note on why that is harmless here.
 */
internal class Bolt11Parts(
    val hrp: String,
    val timestamp: List<Int>,
    val fields: List<Bolt11TaggedField>,
    val signature: List<Int>,
    val checksum: String,
) {

    init {
        require(timestamp.size == Bolt11Composer.TIMESTAMP_GROUPS) {
            "Appendix C's timestamp is ${Bolt11Composer.TIMESTAMP_GROUPS} groups; found ${timestamp.size}"
        }
        require(signature.size <= Bolt11Composer.SIGNATURE_GROUPS) {
            "a signature is at most ${Bolt11Composer.SIGNATURE_GROUPS} groups; found ${signature.size}"
        }
    }

    /** The human-readable part up to its first digit: `ln` and the network prefix. */
    val networkPrefix: String get() {
        val at = hrp.indexOfFirst { it in '0'..'9' }
        return if (at < 0) hrp else hrp.substring(0, at)
    }

    /** Appendix C's OPTIONAL amount, as written — empty when the invoice states none. */
    val amount: String get() = hrp.substring(networkPrefix.length)

    /** The 35-bit timestamp, in seconds since the unix epoch. */
    val timestampSeconds: Long get() = Bolt11Composer.numberOf(timestamp)

    /** The checksum the rest of these parts implies, which is the one [Bolt11Composer.compose] writes. */
    val recomputedChecksum: String get() = Bolt11Composer.checksum(hrp, Bolt11Composer.dataGroups(this))

    /** Every tagged field of that type, in order. Appendix C allows repeats, and example 14 has them. */
    fun fields(type: Char): List<Bolt11TaggedField> = fields.filter { it.type == type }

    /** The first tagged field of that type, or null. */
    fun field(type: Char): Bolt11TaggedField? = fields.firstOrNull { it.type == type }

    /**
     * The 256-bit payment hash of the first correct-length `p` field, or null when there is none.
     *
     * "Correct-length" is [Bolt11Composer.HASH_GROUPS], because BOLT-11's reader rule — which
     * Appendix C follows — is to **skip** a tagged field of the wrong length rather than reject the
     * invoice, and vendored example 14 carries several wrong-length `p` fields for exactly that
     * reason.
     */
    fun paymentHash(): ByteArray? =
        fields('p').firstOrNull { it.dataLength == Bolt11Composer.HASH_GROUPS }?.bytes()?.copyOf(32)

    /** These parts with [fields] in place of the tagged fields — `emptyList()` removes them all. */
    fun withFields(fields: List<Bolt11TaggedField>): Bolt11Parts =
        Bolt11Parts(hrp, timestamp, fields, signature, checksum)

    /**
     * These parts with the **first** tagged field of that type carrying [groups] instead.
     *
     * Fails when there is none: a test that meant to append should say so, and silently appending
     * here would let a derivation quietly stop changing what it names.
     */
    fun withField(type: Char, groups: List<Int>): Bolt11Parts {
        val at = fields.indexOfFirst { it.type == type }
        require(at >= 0) { "no tagged field '$type' to replace" }
        return withFields(fields.toMutableList().also { it[at] = Bolt11TaggedField(type, groups) })
    }

    /** These parts with every tagged field of that type removed. */
    fun withoutField(type: Char): Bolt11Parts = withFields(fields.filter { it.type != type })

    /** These parts with one more tagged field, after the ones already there. */
    fun plusField(type: Char, groups: List<Int>): Bolt11Parts =
        withFields(fields + Bolt11TaggedField(type, groups))

    /** These parts with Appendix C's optional amount set, or removed when [written] is empty. */
    fun withAmount(written: String): Bolt11Parts =
        Bolt11Parts(networkPrefix + Bolt11Composer.requireAmount(written), timestamp, fields, signature, checksum)

    /** These parts with the 35-bit timestamp set to [seconds]. */
    fun withTimestamp(seconds: Long): Bolt11Parts {
        require(seconds >= 0 && seconds < (1L shl (5 * Bolt11Composer.TIMESTAMP_GROUPS))) {
            "$seconds does not fit Appendix C's 35-bit timestamp"
        }
        return Bolt11Parts(
            hrp,
            Bolt11Composer.groupsOfNumber(seconds, Bolt11Composer.TIMESTAMP_GROUPS),
            fields,
            signature,
            checksum,
        )
    }

    /**
     * These parts with the first `p` field set from [hash] — 32 bytes as 256 bits plus four zero
     * bits of padding, which is [Bolt11Composer.HASH_GROUPS] groups.
     */
    fun withPaymentHash(hash: ByteArray): Bolt11Parts {
        require(hash.size == 32) { "a payment hash is 32 bytes; found ${hash.size}" }
        val groups = Bolt11Composer.groupsOfBytes(hash)
        check(groups.size == Bolt11Composer.HASH_GROUPS) { "32 bytes became ${groups.size} groups" }
        return withField('p', groups)
    }

    /**
     * These parts with the signature cut to its first [count] groups.
     *
     * For a recogniser control and nothing else — the result is no longer decomposable once it
     * drops below Appendix C's floor, and its signature was already stale.
     */
    fun withSignatureGroups(count: Int): Bolt11Parts {
        require(count in 0..signature.size) { "$count is not within this signature's ${signature.size} groups" }
        return Bolt11Parts(hrp, timestamp, fields, signature.subList(0, count).toList(), checksum)
    }

    /** Deliberately carries no invoice: §12 keeps a BOLT-11 string out of anything printed. */
    override fun toString(): String = "Bolt11Parts(${fields.size} tagged fields, redacted)"
}
