package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.order.OrderState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §11.1 listing-status test that needs the JVM, on top of the common ones in
 * [PortableListingStatusTest].
 *
 * It asserts through `java.lang.Class.isAssignableFrom` that neither type is assignable to the other,
 * which common Kotlin has no reflection to ask.
 */
class ListingStatusTest : PortableListingStatusTest() {

    /**
     * The structural half of "one codec MUST NOT serve both": the two types have no assignability
     * relationship at all, so no call site can pass one where the other is expected. The sweep in
     * `ListingStructureTest` carries the rest — that nothing in this package takes or returns an
     * order-package type.
     */
    @Test
    fun `a listing status is not an order state`() {
        assertFalse(OrderState::class.java.isAssignableFrom(ListingStatus::class.java))
        assertFalse(ListingStatus::class.java.isAssignableFrom(OrderState::class.java))
        assertTrue(
            ListingStatus.entries.map { it.name }.containsAll(listOf("CANCELLED", "UNKNOWN")),
            "the two names they share are the two worth asserting are separate constants",
        )
    }
}
