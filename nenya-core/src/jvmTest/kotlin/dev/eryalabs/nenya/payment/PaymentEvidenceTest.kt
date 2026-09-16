package dev.eryalabs.nenya.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The §9.2 evidence tests that need the JVM, on top of the common ones in
 * [PortablePaymentEvidenceTest].
 *
 * A private Kotlin constructor plus a companion factory still emits a JVM-public synthetic
 * constructor. Both tests reach it through `java.lang.reflect`; the constructor, and the hole, exist
 * only on the JVM.
 */
class PaymentEvidenceTest : PortablePaymentEvidenceTest() {

    @Test
    fun `a wrong-length preimage cannot be built through the JVM-public synthetic constructor`() {
        // A private Kotlin constructor plus a companion factory still emits a JVM-public
        // constructor with a trailing DefaultConstructorMarker, which Java reaches as
        // `new Preimage(bytes, null)`. If the length check lived only in ofHex, a three-byte
        // Preimage would exist and PaymentCheck.PREIMAGE_SHAPE would be a false claim about
        // it. The invariant is in `init`, so every construction path runs it.
        for (type in listOf(Preimage::class.java, PaymentHash::class.java)) {
            val constructor = type.constructors.single()
            for (size in listOf(0, 3, 31, 33)) {
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    constructor.newInstance(ByteArray(size), null)
                }
                val cause = thrown.cause
                assertTrue(cause is PaymentException, "${type.simpleName}($size bytes) threw $cause")
                assertEquals(PaymentRejection.WRONG_LENGTH, cause.reason)
            }
        }
    }

    @Test
    fun `the synthetic constructor copies its array, so a later mutation cannot rewrite evidence`() {
        // The other half of the same hole: that constructor is public, so its caller keeps a
        // reference to the array. Without a copy on the way in, `bytes.fill(0)` after the
        // fact would silently rewrite a Preimage somebody is about to verify.
        val bytes = anchorPreimage().bytes()
        val preimage = Preimage::class.java.constructors.single().newInstance(bytes, null) as Preimage
        val hash = PaymentHash::class.java.constructors.single().newInstance(bytes, null) as PaymentHash

        bytes.fill(0)

        assertEquals(anchorPreimageHex(), PaymentFixtures.lowerHex(preimage.bytes()))
        assertEquals(anchorPreimageHex(), hash.toHex())
        assertEquals(
            Payee.PROVIDER,
            VerifiedPayment.verify(Payee.PROVIDER, anchorPaymentHash(), preimage).payee,
            "the copied preimage must still be the one whose SHA-256 is the anchor payment hash",
        )
    }
}
