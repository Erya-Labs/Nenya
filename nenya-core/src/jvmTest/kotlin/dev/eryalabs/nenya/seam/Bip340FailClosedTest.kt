package dev.eryalabs.nenya.seam

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The signature seam, fed real BIP-340 vectors, must answer **unavailable** — never `true`, and
 * never `false`.
 *
 * §4.1 makes every Nenya event an ordinary nostr event, which means BIP-340 Schnorr over
 * secp256k1; §7.2's seal/rumor pubkey equality check and §8.7's fee-invoice check both rest on
 * it. This library has no secp256k1 and may not add one, so it verifies no signature at all —
 * and §17 says that is conformant *provided it does not report unverified things as verified*
 * and exposes a machine-readable statement of what it does verify.
 *
 * So the proof is adversarial in a specific way: the default is handed the vectors it would
 * most plausibly get right by accident. The nine **valid** rows are the ones a broken
 * implementation returning a cheerful `true` would pass; the ten **invalid** rows are the ones a
 * fail-closed-as-`false` implementation would pass. Both sets must come back
 * [SeamAnswer.Unavailable], because only that answer distinguishes "not checked" from "checked,
 * and invalid" — the distinction §17's last paragraph requires an implementation's UI to draw.
 *
 * Nothing here is typed. The keys, messages and signatures come from
 * `src/commonTest/resources/vectors/bip340-vectors.csv`, vendored from the BIP-340 reference vectors
 * and externally authored; the CSV's header names its columns, so this file parses **by name**
 * rather than indexing blind, and asserts it found all nineteen data rows so a broken reader
 * cannot pass with zero.
 */
class Bip340FailClosedTest {

    private companion object {

        const val VECTORS: String = "src/commonTest/resources/vectors/bip340-vectors.csv"

        /** The BIP-340 reference vector file has nineteen data rows, nine of them valid. */
        const val EXPECTED_ROWS: Int = 19
        const val EXPECTED_VALID: Int = 9
        const val EXPECTED_INVALID: Int = 10

        val rows: List<Bip340Row> by lazy { readVectors() }

        fun readVectors(): List<Bip340Row> {
            val file = File(VECTORS)
            if (!file.isFile) {
                fail(
                    "the vendored BIP-340 vectors were expected at ${file.absolutePath} but are not " +
                        "there. The tests run with the module directory as the working directory; " +
                        "this one is ${File(".").absoluteFile.normalize()}.",
                )
            }
            val lines = file.readLines().filter { it.isNotBlank() }
            assertTrue(lines.size >= 2, "${file.absolutePath} carries no data rows")

            val headers = lines.first().split(",").map { it.trim() }
            fun column(name: String): Int = headers.indexOf(name).also {
                assertTrue(it >= 0, "the vectors must carry a '$name' column; found $headers")
            }
            val index = column("index")
            val publicKey = column("public key")
            val message = column("message")
            val signature = column("signature")
            val result = column("verification result")

            return lines.drop(1).map { line ->
                // Limited split, so a comment containing a comma cannot shift the columns left.
                val cells = line.split(",", limit = headers.size)
                assertEquals(
                    headers.size,
                    cells.size,
                    "row '${cells.firstOrNull()}' has ${cells.size} cells and the header has " +
                        "${headers.size}",
                )
                Bip340Row(
                    index = cells[index].trim(),
                    publicKey = hex(cells[publicKey].trim()),
                    message = hex(cells[message].trim()),
                    signature = hex(cells[signature].trim()),
                    expectedValid = when (val verdict = cells[result].trim().uppercase()) {
                        "TRUE" -> true
                        "FALSE" -> false
                        else -> fail("row ${cells[index]} has verification result '$verdict'")
                    },
                )
            }
        }

        /** The vectors are uppercase hex; this reader is the test's own and normalises. */
        fun hex(text: String): ByteArray {
            assertTrue(text.length % 2 == 0, "a hex cell must have an even length; '$text' does not")
            return ByteArray(text.length / 2) { i ->
                val high = Character.digit(text[2 * i], 16)
                val low = Character.digit(text[2 * i + 1], 16)
                assertTrue(high >= 0 && low >= 0, "a hex cell must be hexadecimal")
                ((high shl 4) or low).toByte()
            }
        }
    }

    private data class Bip340Row(
        val index: String,
        val publicKey: ByteArray,
        val message: ByteArray,
        val signature: ByteArray,
        val expectedValid: Boolean,
    )

    @Test
    fun `the vectors parse by column name, all nineteen rows of them`() {
        assertEquals(
            EXPECTED_ROWS,
            rows.size,
            "a reader that found no rows would make every assertion in this file vacuously true",
        )
        assertEquals(EXPECTED_VALID, rows.count { it.expectedValid })
        assertEquals(EXPECTED_INVALID, rows.count { !it.expectedValid })
        assertTrue(
            rows.all { it.publicKey.size == 32 && it.signature.size == 64 },
            "every BIP-340 row carries a 32-byte x-only key and a 64-byte signature",
        )
        assertTrue(
            rows.map { it.message.size }.toSet().size > 1,
            "BIP-340 is defined over arbitrary-length messages and the vendored file exercises " +
                "several; a reader that produced one length has lost the variable-length rows",
        )
    }

    @Test
    fun `every valid row comes back unavailable, and specifically never valid`() {
        val valid = rows.filter { it.expectedValid }
        assertEquals(EXPECTED_VALID, valid.size)

        for (row in valid) {
            val answer = Secp256k1Ops.FAIL_CLOSED.verifySchnorr(row.publicKey, row.message, row.signature)

            assertFalse(
                answer is SeamAnswer.Provided,
                "row ${row.index} is a genuinely valid BIP-340 signature and this library cannot " +
                    "verify it; answering anything else would be reporting an unverified thing as " +
                    "verified (§17). Got $answer",
            )
            assertEquals(SeamCapability.BIP340_VERIFICATION, answer.unavailable().capability)
        }
    }

    @Test
    fun `every invalid row comes back unavailable too, and specifically not INVALID`() {
        val invalid = rows.filter { !it.expectedValid }
        assertEquals(EXPECTED_INVALID, invalid.size)

        for (row in invalid) {
            val answer = Secp256k1Ops.FAIL_CLOSED.verifySchnorr(row.publicKey, row.message, row.signature)

            assertFalse(
                answer is SeamAnswer.Provided,
                "row ${row.index} must come back unavailable rather than INVALID. A seam that says " +
                    "'invalid' for everything is accidentally right on these ten rows and wrong on " +
                    "the other nine, and a caller cannot tell the two apart. Got $answer",
            )
            assertEquals(SeamCapability.BIP340_VERIFICATION, answer.unavailable().capability)
        }
    }

    /**
     * The floor under both tests above. `assertTrue(answer is Unavailable)` would be satisfied by
     * a seam whose return type could not express a verdict at all, which is the vacuous version
     * of "it never says valid". [OptimisticSecp256k1Ops] is an implementation of the same
     * interface that does say it, over the same vectors.
     */
    @Test
    fun `the seam type can express a verdict, so the default's refusal to give one means something`() {
        val optimistic = OptimisticSecp256k1Ops()

        assertEquals(EXPECTED_ROWS, rows.size, "a loop over no rows proves nothing about either")
        for (row in rows) {
            val answer = optimistic.verifySchnorr(row.publicKey, row.message, row.signature)
            assertEquals(SignatureVerdict.VALID, answer.provided())
        }
    }

    @Test
    fun `the capability surface names BIP-340 verification as something this library does not do`() {
        val answer = Secp256k1Ops.FAIL_CLOSED.verifySchnorr(
            rows.first().publicKey,
            rows.first().message,
            rows.first().signature,
        ).unavailable()

        assertEquals(SeamCapability.BIP340_VERIFICATION, answer.capability)
        assertTrue(
            answer.detail.isNotBlank(),
            "§17 requires a machine-readable statement of what is verified; the capability is that " +
                "statement and the detail is what a UI shows beside it",
        )
    }
}
