package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.delivery.DeliveryRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T73: `DeliveryFailure.of` names §10.4's step for exactly the two digest refusals that are one.
 *
 * The answer is held over **every** `DeliveryRejection` rather than over the two that map, because
 * the failure worth catching is a third one mapping — above all `SERVED_HASH_MISMATCH` and
 * `PLAINTEXT_HASH_MISMATCH`, §10.3's identity check, whose names sit one word from the step tokens
 * and which compare two signed statements rather than a digest of any bytes.
 */
class DeliveryFailureOfTest {

    /** The two that map, each to its own step and never to the other's. */
    @Test
    @JsName("each_digest_refusal_names_its_own_section_10_4_step")
    fun `each digest refusal names its own §10_4 step`() {
        assertEquals(
            DeliveryFailure.SERVED_BYTES_HASH_MISMATCH,
            DeliveryFailure.of(DeliveryRejection.SERVED_BYTES_MISMATCH),
            "§10.4 step 1: the served bytes are not `x`",
        )
        assertEquals(
            DeliveryFailure.PLAINTEXT_BYTES_HASH_MISMATCH,
            DeliveryFailure.of(DeliveryRejection.PLAINTEXT_BYTES_MISMATCH),
            "§10.4 step 3: the plaintext is not `ox`",
        )
    }

    /** Over all entries, exactly those two answer non-null — and step 2 is never an answer. */
    @Test
    @JsName("no_other_delivery_rejection_is_given_a_step")
    fun `no other delivery rejection is given a step`() {
        val mapped = DeliveryRejection.entries.filter { DeliveryFailure.of(it) != null }.toSet()
        assertEquals(
            setOf(DeliveryRejection.SERVED_BYTES_MISMATCH, DeliveryRejection.PLAINTEXT_BYTES_MISMATCH),
            mapped,
            "§10.4 names a step for the two digest checks and for nothing else this package refuses",
        )
        for (named in listOf(
            DeliveryRejection.SERVED_HASH_MISMATCH,
            DeliveryRejection.PLAINTEXT_HASH_MISMATCH,
            DeliveryRejection.SIZE_DISAGREEMENT,
            DeliveryRejection.SIZE_LIMIT_EXCEEDED,
        )) {
            assertNull(DeliveryFailure.of(named), "$named is refused apart from §10.4's steps")
        }
        assertEquals(
            emptyList(),
            DeliveryRejection.entries.filter { DeliveryFailure.of(it) == DeliveryFailure.BLOB_DID_NOT_DECRYPT },
            "this library decrypts nothing, so none of its refusals is step 2's",
        )
    }
}
