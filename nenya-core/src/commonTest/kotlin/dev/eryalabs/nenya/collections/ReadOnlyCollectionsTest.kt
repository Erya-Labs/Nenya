package dev.eryalabs.nenya.collections

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The common read-only wrappers that replaced `java.util.Collections.unmodifiableList/Set`, and
 * every place the library publishes one.
 *
 * The contract is the one `unmodifiable*` had, because this repository's older immutability
 * tests pin it: a caller may cast the collection to its mutable type, and every mutation it then
 * attempts — on the collection, on an iterator, on a sub-list, through a JDK default method —
 * throws `UnsupportedOperationException` and changes nothing.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as
 * `ReadOnlyCollectionsTest` on each target, so every test keeps its `ReadOnlyCollectionsTest` name.
 * The two exhaustive mutation tests also go through the JDK's own default methods (`removeIf`,
 * `replaceAll`, `Collections.sort`), which exist only on the JVM, and are in the JVM
 * `ReadOnlyCollectionsTest` (`src/jvmTest`).
 */
abstract class PortableReadOnlyCollectionsTest {

    @JsName("the_wrappers_copy_on_the_way_in_keep_order_and_collapse_duplicates_only_for_a_set")
    @Test
    fun `the wrappers copy on the way in, keep order and collapse duplicates only for a set`() {
        val source = mutableListOf("x", "y", "x")
        val list = readOnlyListOf(source)
        val set = readOnlySetOf(source)

        source.clear()
        source += "z"

        assertEquals(listOf("x", "y", "x"), list, "a list that wrapped without copying follows its source")
        assertEquals(listOf("x", "y"), set.toList(), "first-seen order, one of each")
    }

    @JsName("a_read_only_collection_equals_a_plain_one_with_the_same_elements_in_both_directions")
    @Test
    fun `a read-only collection equals a plain one with the same elements, in both directions`() {
        val list = readOnlyListOf(listOf(1, 2))
        val set = readOnlySetOf(listOf(1, 2))

        assertTrue(list == listOf(1, 2) && listOf(1, 2) == list, "list equality must be symmetric")
        assertTrue(set == setOf(2, 1) && setOf(2, 1) == set, "set equality must be symmetric")
        assertEquals(listOf(1, 2).hashCode(), list.hashCode())
        assertEquals(setOf(1, 2).hashCode(), set.hashCode())
        assertEquals("[1, 2]", list.toString())
        assertEquals("[1, 2]", list.subList(0, 2).toString())
        assertEquals("[1, 2]", set.toString())
    }

    /**
     * Every site that used `Collections.unmodifiable*` before the multiplatform move, by name,
     * so a site that quietly went back to a plain copy fails here rather than nowhere.
     */
    @JsName("every_collection_the_library_publishes_as_read_only_refuses_mutation_and_stays_intact")
    @Test
    fun `every collection the library publishes as read-only refuses mutation and stays intact`() {
        val event = WireEvent("ab".repeat(32), 1L, 1, listOf(listOf("t", "nenya")), "")
        val published: Map<String, Collection<*>> = buildMap {
            put("Capabilities.ITEMS", Capabilities.ITEMS)
            put("Capabilities.PAYMENT_CHECKS_NOT_PERFORMED", Capabilities.PAYMENT_CHECKS_NOT_PERFORMED)
            put("Capabilities.DELIVERY_CHECKS_NOT_PERFORMED", Capabilities.DELIVERY_CHECKS_NOT_PERFORMED)
            put("Capabilities.SEAM_CAPABILITIES_NOT_PERFORMED", Capabilities.SEAM_CAPABILITIES_NOT_PERFORMED)
            put("Capabilities.NOT_PERFORMED_HERE", Capabilities.NOT_PERFORMED_HERE)
            for (item in Capabilities.ITEMS) {
                put("ConformanceItem#${item.number}.specSections", item.specSections)
                put("ConformanceItem#${item.number}.evidenceClasses", item.evidenceClasses)
                put("ConformanceItem#${item.number}.notPerformed", item.notPerformed)
            }
            put("DeliveryEvidence.CHECKS_PERFORMED_HERE", DeliveryEvidence.CHECKS_PERFORMED_HERE)
            put("DeliveryEvidence.CHECKS_NOT_PERFORMED_HERE", DeliveryEvidence.CHECKS_NOT_PERFORMED_HERE)
            put("VerifiedPayment.CHECKS_PERFORMED_HERE", VerifiedPayment.CHECKS_PERFORMED_HERE)
            put("VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER", VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_PROVIDER)
            put("VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE", VerifiedPayment.CHECKS_NOT_PERFORMED_FOR_FEE)
            put("WireEvent.tags", event.tags)
            put("WireEvent.tags[0]", event.tags[0])
        }

        for ((name, collection) in published) {
            assertTrue(
                collection is ReadOnlyList<*> || collection is ReadOnlySet<*>,
                "$name is a ${collection::class.simpleName}, not one of the read-only wrappers",
            )
            val before = collection.toList()
            @Suppress("UNCHECKED_CAST")
            val mutable = collection as MutableCollection<Any?>
            assertFailsWith<UnsupportedOperationException>("$name accepted clear()") { mutable.clear() }
            assertFailsWith<UnsupportedOperationException>("$name accepted add()") { mutable.add(null) }
            if (before.isNotEmpty()) {
                assertFailsWith<UnsupportedOperationException>("$name accepted iterator().remove()") {
                    mutable.iterator().apply { next() }.remove()
                }
            }
            assertEquals(before, collection.toList(), "$name changed under an attempted mutation")
        }
        assertTrue(published.size >= 15, "the site list shrank to ${published.size}")
    }
}
