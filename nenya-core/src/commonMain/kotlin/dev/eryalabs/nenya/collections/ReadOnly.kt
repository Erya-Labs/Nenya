package dev.eryalabs.nenya.collections

/*
 * Common-Kotlin read-only collections, replacing java.util.Collections.unmodifiableList/Set.
 *
 * A published List or Set in this library is often shared state: the check sets on
 * VerifiedPayment and DeliveryEvidence are process-wide statics, and a caller that could remove
 * INVOICE_AMOUNT from one would make every payment claim the amount was checked. A plain
 * toList()/toSet() copy is not enough, because on the JVM it is a java.util.ArrayList or
 * LinkedHashSet that a Java caller - or a Kotlin caller with one cast - mutates freely.
 *
 * Each wrapper reads through `List<T> by delegate` / `Set<T> by delegate` over a private
 * defensive copy that nothing else holds.
 *
 * ### Why the wrappers also declare the mutable interface, and refuse every mutation
 *
 * A wrapper implementing only the read-only interface is not what Collections.unmodifiable*
 * was, and the difference is observable: on the JVM a Kotlin `as MutableSet` cast of it throws
 * ClassCastException rather than succeeding and then throwing UnsupportedOperationException on
 * `add`. This repository's immutability tests (DeliveryVerificationTest, PaymentEvidenceTest)
 * pin the second behaviour, and a first version of this file failed both. So the wrappers are
 * typed MutableList/MutableSet internally - every published signature still says List/Set - and
 * every mutator, on the collection, its iterators and its sub-lists, throws
 * UnsupportedOperationException. That is exactly the unmodifiable* contract, and on Kotlin/JS,
 * where mutable and read-only collection interfaces cannot be told apart by a cast, it is the
 * same typed refusal rather than a missing-method TypeError.
 *
 * equals, hashCode and toString are forwarded explicitly: interface delegation does not forward
 * members of Any, and a read-only list must still equal a plain list with the same elements.
 */

private const val READ_ONLY: String = "this collection is read-only; Nenya never hands out mutable state"

internal class ReadOnlyList<T>(private val delegate: List<T>) : MutableList<T>, List<T> by delegate {

    override fun iterator(): MutableIterator<T> = ReadOnlyListIterator(delegate.listIterator())

    override fun listIterator(): MutableListIterator<T> = ReadOnlyListIterator(delegate.listIterator())

    override fun listIterator(index: Int): MutableListIterator<T> =
        ReadOnlyListIterator(delegate.listIterator(index))

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> =
        ReadOnlyList(delegate.subList(fromIndex, toIndex))

    override fun add(element: T): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun add(index: Int, element: T): Unit = throw UnsupportedOperationException(READ_ONLY)
    override fun addAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun addAll(index: Int, elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun clear(): Unit = throw UnsupportedOperationException(READ_ONLY)
    override fun remove(element: T): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun removeAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun removeAt(index: Int): T = throw UnsupportedOperationException(READ_ONLY)
    override fun retainAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun set(index: Int, element: T): T = throw UnsupportedOperationException(READ_ONLY)

    override fun equals(other: Any?): Boolean = other === this || delegate == other

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = delegate.toString()
}

internal class ReadOnlySet<T>(private val delegate: Set<T>) : MutableSet<T>, Set<T> by delegate {

    override fun iterator(): MutableIterator<T> = ReadOnlyIterator(delegate.iterator())

    override fun add(element: T): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun addAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun clear(): Unit = throw UnsupportedOperationException(READ_ONLY)
    override fun remove(element: T): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun removeAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)
    override fun retainAll(elements: Collection<T>): Boolean = throw UnsupportedOperationException(READ_ONLY)

    override fun equals(other: Any?): Boolean = other === this || delegate == other

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = delegate.toString()
}

private class ReadOnlyIterator<T>(delegate: Iterator<T>) : MutableIterator<T>, Iterator<T> by delegate {
    override fun remove(): Unit = throw UnsupportedOperationException(READ_ONLY)
}

private class ReadOnlyListIterator<T>(delegate: ListIterator<T>) :
    MutableListIterator<T>, ListIterator<T> by delegate {
    override fun add(element: T): Unit = throw UnsupportedOperationException(READ_ONLY)
    override fun remove(): Unit = throw UnsupportedOperationException(READ_ONLY)
    override fun set(element: T): Unit = throw UnsupportedOperationException(READ_ONLY)
}

/** A read-only list over a private copy of [values], in their order. */
internal fun <T> readOnlyListOf(values: Collection<T>): List<T> = ReadOnlyList(ArrayList(values))

/** A read-only set over a private copy of [values], keeping first-seen order. */
internal fun <T> readOnlySetOf(values: Iterable<T>): Set<T> {
    val copy = LinkedHashSet<T>()
    for (value in values) copy.add(value)
    return ReadOnlySet(copy)
}
