package dev.eryalabs.nenya.collections

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.wire.WireEvent
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
 */
class ReadOnlyCollectionsTest {

    private fun refused(block: () -> Unit) {
        assertFailsWith<UnsupportedOperationException> { block() }
    }

    @Test
    fun `a read-only list refuses every mutation, through a cast, an iterator, a sub-list and the JDK`() {
        val list = readOnlyListOf(listOf(3, 1, 2))
        @Suppress("UNCHECKED_CAST")
        val mutable = list as MutableList<Int>

        refused { mutable.add(4) }
        refused { mutable.add(0, 4) }
        refused { mutable.addAll(listOf(4)) }
        refused { mutable.addAll(0, listOf(4)) }
        refused { mutable.clear() }
        refused { mutable.remove(1) }
        refused { mutable.removeAll(listOf(1)) }
        refused { mutable.removeAt(0) }
        refused { mutable.retainAll(listOf(1)) }
        refused { mutable[0] = 9 }
        refused { mutable.iterator().apply { next() }.remove() }
        refused { mutable.listIterator().apply { next() }.set(9) }
        refused { mutable.listIterator(1).add(9) }
        refused { mutable.listIterator().apply { next() }.remove() }
        refused { mutable.subList(0, 2).clear() }
        refused { mutable.subList(0, 2)[0] = 9 }
        refused { mutable.removeIf { true } }
        refused { mutable.replaceAll { 9 } }
        refused { java.util.Collections.sort(mutable) }

        assertEquals(listOf(3, 1, 2), list, "and after all of that the list is unchanged")
    }

    @Test
    fun `a read-only set refuses every mutation, through a cast, an iterator and the JDK`() {
        val set = readOnlySetOf(listOf("b", "a"))
        @Suppress("UNCHECKED_CAST")
        val mutable = set as MutableSet<String>

        refused { mutable.add("c") }
        refused { mutable.addAll(listOf("c")) }
        refused { mutable.clear() }
        refused { mutable.remove("a") }
        refused { mutable.removeAll(listOf("a")) }
        refused { mutable.retainAll(listOf("a")) }
        refused { mutable.iterator().apply { next() }.remove() }
        refused { mutable.removeIf { true } }

        assertEquals(listOf("b", "a"), set.toList(), "and after all of that the set is unchanged, in order")
    }

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
                "$name is a ${collection::class.java.name}, not one of the read-only wrappers",
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
