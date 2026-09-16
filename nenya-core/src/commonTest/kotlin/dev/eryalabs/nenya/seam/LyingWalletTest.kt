package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.1 and stop rule 12, executable: a wallet that lies about everything produces no evidence.
 *
 * This is the headline of the seam task and the reason the seams exist as types at all. §3 says
 * the wallet MUST NOT be believed when it claims a payment succeeded; §9.1 says the same thing
 * with more force; §17 item 6 says an implementation advances to `paid` only on evidence it
 * verified itself. All three are statements about code that does not exist yet — the state
 * machine is T7's — so what can be proved *here* is the half that will still be true then: the
 * wallet seam has no path to a `VerifiedPayment`, by value or by type.
 *
 * The scoping is deliberate and is named in the task rather than papered over. "And advances no
 * order" has no subject until the state machine exists, and that half belongs to T7.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as `LyingWalletTest` on
 * each target, so every test keeps its `LyingWalletTest` name. The two type-level sweeps (nothing in
 * the seam package can produce or hold a `VerifiedPayment`, and the verifier takes nothing a seam
 * supplies) are `java.lang.reflect`, and are in the JVM `LyingWalletTest` (`src/jvmTest`) beside
 * `SeamReflection`.
 */
abstract class PortableLyingWalletTest {

    private companion object {

        /** The real pair: a generated preimage and the payment hash a real invoice would carry. */
        val realPreimage: Preimage = PaymentFixtures.preimages(1).single()

        /**
         * 32 well-formed bytes that are **not** [realPreimage]. Generated, never typed, and
         * deliberately valid hex of the right length: a lie that failed §9.2 check 2 would be
         * caught by the shape rules and would prove nothing about check 3.
         */
        val fabricatedPreimageHex: String = PaymentFixtures.preimageHex(2).last()
    }

    @JsName("the_wallet_claims_settled_hands_over_a_preimage_and_evidences_nothing")
    @Test
    fun `the wallet claims settled, hands over a preimage, and evidences nothing`() {
        val paymentHash = PaymentFixtures.paymentHashOf(realPreimage)
        val wallet = LyingWallet(fabricatedPreimageHex)

        val paid = wallet.payInvoice(SeamFixtures.NOT_AN_INVOICE)
        val status = wallet.paymentStatus(SeamFixtures.NOT_AN_INVOICE)
        val balance = wallet.spendableBalance()

        // Everything the wallet says is maximally encouraging.
        val paidClaim = paid.provided()
        val statusClaim = status.provided()
        assertEquals(WalletPaymentState.CLAIMS_SETTLED, paidClaim.state)
        assertEquals(WalletPaymentState.CLAIMS_SETTLED, statusClaim.state)
        assertEquals(Msat.SUPPLY_CAP, balance.provided())
        assertEquals(fabricatedPreimageHex, paidClaim.claimedPreimageHex)

        // And the claimed preimage is well-formed, so the refusal below is about §9.2 check 3
        // and not about check 2 — a lie caught by the shape rules would prove nothing.
        val claimed = Preimage.ofHex(paidClaim.claimedPreimageHex ?: fail("the wallet offered no preimage"))

        val refused = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(Payee.PROVIDER, paymentHash, claimed)
        }
        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "a wallet that reports every payment settled and hands over preimage-shaped bytes must " +
                "still produce no evidence; §9.2 check 3 is the only thing standing between the two",
        )
    }

    @JsName("the_same_code_path_accepts_the_real_preimage_so_the_refusal_above_is_not_vacuous")
    @Test
    fun `the same code path accepts the real preimage, so the refusal above is not vacuous`() {
        val paymentHash = PaymentFixtures.paymentHashOf(realPreimage)

        val evidence = VerifiedPayment.verify(Payee.PROVIDER, paymentHash, realPreimage)

        assertEquals(Payee.PROVIDER, evidence.payee)
        assertEquals(paymentHash, evidence.paymentHash)
    }

    @JsName("the_wallet_s_claimed_preimage_is_redacted_in_every_string_representation")
    @Test
    fun `the wallet's claimed preimage is redacted in every string representation`() {
        val wallet = LyingWallet(fabricatedPreimageHex)
        val claim = wallet.payInvoice(SeamFixtures.NOT_AN_INVOICE).provided()

        assertFalse(
            claim.toString().contains(fabricatedPreimageHex),
            "§12 item 11 names preimages alongside key material and order ids: none may appear in a " +
                "log, a crash report, or the string representation of anything this library exposes",
        )
        assertFalse(
            SeamAnswer.Provided(claim).toString().contains(fabricatedPreimageHex),
            "wrapping a claim in a SeamAnswer must not be a way around §12 item 11",
        )
        assertTrue(claim.toString().contains("redacted"))

        // Pinned whole, not merely checked for the full hex. §12 item 11 admits no partial form,
        // and a `contains(<the whole 64 characters>)` assertion passes a toString that discloses a
        // 48-character prefix — which is 24 bytes of a 32-byte preimage. `OrderId` is pinned this
        // way already (`OrderIdTest`), and the asymmetry is what let this one through.
        assertEquals(
            "WalletPaymentClaim(state=CLAIMS_SETTLED, claimedPreimage=redacted)",
            claim.toString(),
        )
        for (window in fabricatedPreimageHex.windowed(8)) {
            assertFalse(
                claim.toString().contains(window),
                "no 8-character window of the preimage may survive, not just the whole of it",
            )
        }
    }

    /** The absent case is distinguishable from the redacted one, and still leaks nothing. */
    @JsName("a_claim_carrying_no_preimage_says_so_without_inventing_one")
    @Test
    fun `a claim carrying no preimage says so without inventing one`() {
        val claim = WalletPaymentClaim(WalletPaymentState.CLAIMS_FAILED, null)

        assertEquals(
            "WalletPaymentClaim(state=CLAIMS_FAILED, claimedPreimage=absent)",
            claim.toString(),
            "'absent' and 'redacted' are different facts and a diagnostic that conflated them would " +
                "make a wallet offering nothing look like one offering something",
        )
    }
}
