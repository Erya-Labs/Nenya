package dev.eryalabs.nenya.seam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SeamAnswer.Provided] redacts **unconditionally**, including values that do not redact themselves.
 *
 * ### Why this file exists, stated plainly because it was found by a surviving mutation
 *
 * The order id and the wallet's claimed preimage each carry their own redacting `toString`, and
 * `OrderIdTest` and `LyingWalletTest` each assert that wrapping one in a [SeamAnswer.Provided]
 * does not leak it. Both of those assertions pass **because of the inner type**, not because of
 * the wrapper: replacing this wrapper's `toString` with a plain `"Provided($value)"` left the
 * entire suite green, because `OrderId.toString()` is already `"OrderId(redacted)"` and
 * `WalletPaymentClaim.toString()` already says `redacted`.
 *
 * That gap matters because the wrapper's KDoc claims the redaction is load-bearing precisely for
 * the values it *cannot* know the type of — and the sharpest of those is a bare [String].
 * [Signer.nip44Decrypt] returns `SeamAnswer<String>`, which is a decrypted private message; a
 * `String` has no redacting `toString`, so the wrapper is the only thing between a NIP-44
 * plaintext and a log line. §12 item 11 names decryption keys, preimages and order ids in one
 * sentence as values that MUST NOT appear in a log, a crash report, or the string representation
 * of anything the implementation exposes.
 *
 * So every assertion below wraps a value that would leak on its own. A test whose subject
 * redacts itself cannot tell whether the wrapper does anything at all.
 */
class SeamAnswerRedactionTest {

    private companion object {

        /**
         * Generated rather than typed, and shaped like the thing that would actually hurt: the
         * plaintext side of a NIP-44 decryption, carrying an order id in hex the way a real rumor
         * does (§7.4's `["order", ...]` tag).
         */
        val decryptedPlaintext: String =
            """{"order":"${SeamFixtures.lowerHex(SeamFixtures.bytes(32, stream = 21L))}","status":"paid"}"""
    }

    @Test
    fun `a decrypted plaintext wrapped in Provided is not printed`() {
        val answer: SeamAnswer<String> = SeamAnswer.Provided(decryptedPlaintext)

        assertFalse(
            answer.toString().contains(decryptedPlaintext),
            "Signer.nip44Decrypt returns SeamAnswer<String> and that String is a decrypted private " +
                "message. A String does not redact itself, so this wrapper is the only thing between " +
                "a NIP-44 plaintext and a log line (§12 item 11).",
        )
        assertTrue(answer.toString().contains("redacted"))
    }

    @Test
    fun `the order id inside a decrypted plaintext does not survive the wrapper either`() {
        val orderIdHex = SeamFixtures.lowerHex(SeamFixtures.bytes(32, stream = 21L))
        val answer: SeamAnswer<String> = SeamAnswer.Provided(decryptedPlaintext)

        assertFalse(
            answer.toString().contains(orderIdHex),
            "§12 item 11 names order ids explicitly, and an order id travelling inside a plaintext " +
                "is the same value in a thinner disguise",
        )
    }

    @Test
    fun `a signature and a public key wrapped in Provided are not printed`() {
        val signer = FakeSigner()

        val publicKey = signer.publicKey().provided()
        val signature = signer.signEvent("[0,\"\",0,0,[],\"\"]").provided()

        assertFalse(SeamAnswer.Provided(publicKey).toString().contains(publicKey))
        assertFalse(SeamAnswer.Provided(signature).toString().contains(signature))
    }

    /**
     * The wrapper still says *what kind of thing* it is holding, which is what makes the redaction
     * usable in a diagnostic rather than merely safe. A `toString` returning a constant would pass
     * every assertion above and tell a reader nothing.
     */
    @Test
    fun `the redacted form still names the type it is holding`() {
        assertTrue(SeamAnswer.Provided("x").toString().contains("String"))
        assertTrue(SeamAnswer.Provided(0L).toString().contains("Long"))
        assertEquals(
            "SeamAnswer.Provided(OrderId, redacted)",
            SeamAnswer.Provided(OrderId.mint(RecordingRandomness())).toString(),
        )
    }

    /** A null value must not blow up the diagnostic path — a `toString` may never throw. */
    @Test
    fun `a null value is named rather than thrown on`() {
        val answer: SeamAnswer<String?> = SeamAnswer.Provided(null)

        assertEquals("SeamAnswer.Provided(null, redacted)", answer.toString())
    }

    /**
     * The paired positive control. [SeamAnswer.Unavailable] is the answer §17's capability surface
     * is read from, so it must stay legible — and it carries no caller input to leak, because its
     * detail is authored in this library.
     */
    @Test
    fun `an unavailable answer stays legible, and still echoes no input`() {
        val secret = decryptedPlaintext
        val answer = Signer.FAIL_CLOSED.nip44Decrypt(secret, secret).unavailable()

        assertEquals(SeamCapability.NIP44_DECRYPTION, answer.capability)
        assertTrue(answer.toString().contains("NIP44_DECRYPTION"))
        assertFalse(
            answer.toString().contains(secret),
            "an Unavailable's detail is authored here and must never echo what it was handed; an " +
                "exception message and a log line are the same crash report waiting to happen",
        )
    }
}
