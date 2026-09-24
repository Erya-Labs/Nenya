package dev.eryalabs.nenya.seam

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of `SeamFailClosedTest` that is common Kotlin: the fakes in the test tree answer, so
 * [SeamAnswer.Unavailable] is a choice the fail-closed defaults make rather than the only answer a
 * seam type can express.
 *
 * Abstract, and run as `SeamFailClosedTest` on every target, so the test keeps its name. The
 * reflective sweep over every default, which this test is the positive control for, is
 * `java.lang.reflect` and stays in the JVM `SeamFailClosedTest`.
 */
abstract class PortableSeamFailClosedTest {

    /**
     * And the fakes are not fail-closed themselves — they answer. A test tree full of fakes that
     * all said `Unavailable` would make every fail-closed assertion in the JVM `SeamFailClosedTest`
     * true of a type that cannot say anything else.
     */
    @JsName("the_fakes_answer_so_unavailable_is_a_choice_the_defaults_make_rather_than_the_only_option")
    @Test
    fun `the fakes answer, so unavailable is a choice the defaults make rather than the only option`() {
        assertEquals("", FakeSigner().nip44Encrypt("", "").provided())
        assertEquals(
            listOf(RelayAcknowledgement.CLAIMS_ACCEPTED, RelayAcknowledgement.CLAIMS_ACCEPTED),
            FakeRelayTransport().publish("{}").provided().map { it.acknowledgement },
        )
        assertTrue(
            FakeSigner().nip44Decrypt("", "").provided() is Nip44Decryption.Decrypted,
            "a seam that could only ever answer `not attempted` would make every fail-closed " +
                "assertion in the JVM sweep true of a type rather than of a default",
        )
        assertEquals(
            WalletPaymentState.CLAIMS_SETTLED,
            LyingWallet("00").payInvoice(SeamFixtures.NOT_AN_INVOICE).provided().state,
        )
        assertEquals(0L, FakeClock(0L).now().provided())
        assertEquals(4, RecordingRandomness().randomBytes(4).provided().size)
        assertEquals(
            SignatureVerdict.VALID,
            OptimisticSecp256k1Ops().verifySchnorr(ByteArray(32), ByteArray(32), ByteArray(64)).provided(),
        )
    }
}
