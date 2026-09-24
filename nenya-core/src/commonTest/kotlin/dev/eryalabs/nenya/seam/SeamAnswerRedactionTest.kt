package dev.eryalabs.nenya.seam

import kotlin.js.JsName
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
 * the values it *cannot* know the type of — and the sharpest of those is a bare [String]. A
 * `String` has no redacting `toString`, so the wrapper is the only thing between a NIP-44 plaintext
 * and a log line. §12 item 11 names decryption keys, preimages and order ids in one sentence as
 * values that MUST NOT appear in a log, a crash report, or the string representation of anything
 * the implementation exposes.
 *
 * So every assertion below wraps a value that would leak on its own. A test whose subject
 * redacts itself cannot tell whether the wrapper does anything at all.
 *
 * ### Two layers, since T35, and each is tested against a bare `String`
 *
 * [Signer.nip44Decrypt] returns `SeamAnswer<Nip44Decryption>` rather than `SeamAnswer<String>`, so
 * a decrypted private message is now behind two redacting `toString`s instead of one. That is
 * belt and braces on purpose and neither is redundant: a caller that unwraps the [SeamAnswer] to
 * branch on the three answers holds a [Nip44Decryption] and will print *that*, so the inner type
 * has to redact too, and a caller that logs the answer whole must not be saved only by what it
 * happens to be holding.
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

    @JsName("a_decrypted_plaintext_wrapped_in_provided_is_not_printed")
    @Test
    fun `a decrypted plaintext wrapped in Provided is not printed`() {
        val answer: SeamAnswer<String> = SeamAnswer.Provided(decryptedPlaintext)

        assertFalse(
            answer.toString().contains(decryptedPlaintext),
            "a bare String does not redact itself, so this wrapper is the only thing between a " +
                "NIP-44 plaintext and a log line (§12 item 11).",
        )
        assertTrue(answer.toString().contains("redacted"))
    }

    /**
     * And the inner layer: [Nip44Decryption.Decrypted] redacts the plaintext it carries.
     *
     * The one a caller actually holds. `Signer.nip44Decrypt`'s answer has to be unwrapped to tell
     * "decrypted" from "refused" from "not attempted", so the [SeamAnswer] wrapper's redaction
     * protects nothing past that point — this is the `toString` a client prints while debugging why
     * a gift wrap would not open, with the whole private rumor inside it.
     *
     * [Nip44Decryption.Refused] is asserted beside it for the opposite reason: it carries no detail
     * at all, so there is nothing a signer could have echoed into it, and its `toString` must say
     * only which case it is.
     */
    @JsName("a_decrypted_nip44_payload_is_not_printed_by_the_case_that_carries_it")
    @Test
    fun `a decrypted NIP-44 payload is not printed by the case that carries it`() {
        val decrypted = Nip44Decryption.Decrypted(decryptedPlaintext)

        assertFalse(
            decrypted.toString().contains(decryptedPlaintext),
            "§12 item 11: a decrypted private message MUST NOT appear in the string representation " +
                "of anything this library exposes, and this is the type a caller unwraps to",
        )
        assertFalse(
            decrypted.toString().contains(SeamFixtures.lowerHex(SeamFixtures.bytes(32, stream = 21L))),
            "including the order id travelling inside it",
        )
        assertTrue(decrypted.toString().contains("redacted"))
        assertEquals(decryptedPlaintext, decrypted.plaintext, "and the value is still reachable on purpose")
        assertEquals("Nip44Decryption.Refused", Nip44Decryption.Refused.toString())
    }

    @JsName("the_order_id_inside_a_decrypted_plaintext_does_not_survive_the_wrapper_either")
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

    @JsName("a_signature_and_a_public_key_wrapped_in_provided_are_not_printed")
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
    @JsName("the_redacted_form_still_names_the_type_it_is_holding")
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
    @JsName("a_null_value_is_named_rather_than_thrown_on")
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
    @JsName("an_unavailable_answer_stays_legible_and_still_echoes_no_input")
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
