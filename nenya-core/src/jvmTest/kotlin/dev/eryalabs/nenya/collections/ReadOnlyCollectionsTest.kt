package dev.eryalabs.nenya.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The read-only wrapper tests that need the JVM, on top of the common ones in
 * [PortableReadOnlyCollectionsTest].
 *
 * Both attempt every mutation, including through the JDK's default methods (`removeIf`,
 * `replaceAll`, `java.util.Collections.sort`), which common Kotlin does not have.
 */
class ReadOnlyCollectionsTest : PortableReadOnlyCollectionsTest() {

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
}
