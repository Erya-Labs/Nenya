package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.conformance.ConformanceItem
import dev.eryalabs.nenya.conformance.ConformanceStatus
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T50's crossing — [capabilities] — held equal to [Capabilities] field for field: every item, the
 * derived not-performed set by exact set equality, and the report text.
 *
 * The comparison is [differences], one function both the equality and its two negative controls run,
 * so a probe that slipped past it would be slipping past the comparison the suite actually makes.
 */
class JsCapabilitiesEqualityTest {

    private companion object {

        /** One item's crossed values as plain Kotlin, so a probe can spoil one and keep the rest. */
        data class Snapshot(
            val number: Int,
            val status: String,
            val specSections: List<String>,
            val evidenceClasses: List<String>,
            val notPerformed: List<String>,
            val note: String,
        )

        fun crossed(item: JsConformanceItem): Snapshot = Snapshot(
            number = item.number,
            status = item.status,
            specSections = item.specSections.toList(),
            evidenceClasses = item.evidenceClasses.toList(),
            notPerformed = item.notPerformed.toList(),
            note = item.note,
        )

        /**
         * Every field on which [crossed] departs from [item], by name; empty when they agree.
         *
         * Sets are compared as sets **and** by size, so an array carrying a value twice — which is
         * what a collapsed qualification would produce — is a difference even when the sets agree.
         */
        fun differences(crossed: Snapshot, item: ConformanceItem): List<String> {
            val found = mutableListOf<String>()
            if (crossed.number != item.number) found += "number"
            if (crossed.status != item.status.name) found += "status"
            if (!sameSet(crossed.specSections, item.specSections)) found += "specSections"
            if (!sameSet(crossed.evidenceClasses, item.evidenceClasses)) found += "evidenceClasses"
            if (!sameSet(crossed.notPerformed, item.notPerformed.map(Capabilities::qualify).toSet())) {
                found += "notPerformed"
            }
            if (crossed.note != item.note) found += "note"
            return found
        }

        fun sameSet(crossed: List<String>, expected: Set<String>): Boolean =
            crossed.size == expected.size && crossed.toSet() == expected

        fun crossedItems(): List<JsConformanceItem> = capabilities().items.toList()
    }

    @JsName("every_crossed_item_equals_its_conformance_item_field_for_field")
    @Test
    fun `every crossed item equals its ConformanceItem field for field`() {
        val items = crossedItems()

        assertEquals(Capabilities.ITEMS.size, items.size, "every item crosses, none is dropped")
        assertEquals(Capabilities.itemCount, items.size)
        for ((index, item) in Capabilities.ITEMS.withIndex()) {
            assertEquals(
                emptyList(),
                differences(crossed(items[index]), item),
                "item #${item.number} crossed differently",
            )
        }
        assertEquals(Capabilities.ITEMS.map { it.number }, items.map { it.number }, "§17's order")
        // Non-vacuity: the comparison above saw every status and a non-empty value in every set field.
        assertEquals(
            ConformanceStatus.entries.filter { s -> Capabilities.ITEMS.any { it.status == s } }.map { it.name }.toSet(),
            items.map { it.status }.toSet(),
        )
        assertTrue(items.any { it.notPerformed.isNotEmpty() }, "some item names a not-performed constant")
        assertTrue(items.all { it.specSections.isNotEmpty() }, "every item cites a section")
    }

    @JsName("the_not_performed_set_crosses_by_exact_set_equality_and_in_declaration_order")
    @Test
    fun `the not-performed set crosses by exact set equality and in declaration order`() {
        val expected = Capabilities.NOT_PERFORMED_HERE.map(Capabilities::qualify)
        val crossed = capabilities().notPerformedHere.toList()

        assertEquals(expected.toSet(), crossed.toSet())
        assertEquals(expected, crossed, "the union's declaration order")
        assertEquals(Capabilities.NOT_PERFORMED_HERE.size, crossed.toSet().size, "no two constants collapsed")
        assertTrue(crossed.isNotEmpty(), "this library omits checks, so the set is not empty")
    }

    @JsName("each_crossed_constant_is_qualified_by_its_enum")
    @Test
    fun `each crossed constant is qualified by its enum`() {
        val enums = setOf("PaymentCheck", "DeliveryCheck", "SeamCapability")
        val everywhere = capabilities().notPerformedHere.toList() + crossedItems().flatMap { it.notPerformed.toList() }

        for (constant in everywhere) {
            val (enum, name) = constant.split('.').also { assertEquals(2, it.size, constant) }
            assertTrue(enum in enums, "$constant is not qualified by one of $enums")
            assertTrue(name.isNotEmpty() && name == name.uppercase(), constant)
        }
        assertEquals(enums, capabilities().notPerformedHere.map { it.substringBefore('.') }.toSet())
        // Every item's constants are drawn from the union, as `ConformanceItem.notPerformed` says.
        val union = capabilities().notPerformedHere.toSet()
        for (item in crossedItems()) {
            assertTrue(union.containsAll(item.notPerformed.toList()), "item #${item.number}")
        }
    }

    @JsName("the_report_and_the_specification_cross_verbatim")
    @Test
    fun `the report and the specification cross verbatim`() {
        val surface = capabilities()

        assertEquals(Capabilities.report(), surface.report)
        assertEquals(Capabilities.SPECIFICATION, surface.specification)
        assertEquals("JsCapabilities(${Capabilities.ITEMS.size} items)", surface.toString())
        assertEquals("JsConformanceItem(#1, ${Capabilities.ITEMS[0].status.name})", crossedItems()[0].toString())
    }

    /** Negative control: a crossing that raised one item's status to PERFORMED_HERE is caught. */
    @JsName("a_probe_raising_a_crossed_status_to_performed_here_is_a_difference")
    @Test
    fun `a probe raising a crossed status to PERFORMED_HERE is a difference`() {
        val items = crossedItems()
        var probed = 0
        for ((index, item) in Capabilities.ITEMS.withIndex()) {
            if (item.status == ConformanceStatus.PERFORMED_HERE) continue
            val raised = crossed(items[index]).copy(status = ConformanceStatus.PERFORMED_HERE.name)
            assertEquals(listOf("status"), differences(raised, item), "item #${item.number}")
            probed++
        }
        assertTrue(probed > 0, "no item is below PERFORMED_HERE, so this control probed nothing")
    }

    /** Negative control: a crossing that dropped one constant from an item's `notPerformed` is caught. */
    @JsName("a_probe_dropping_a_constant_from_a_crossed_not_performed_is_a_difference")
    @Test
    fun `a probe dropping a constant from a crossed notPerformed is a difference`() {
        val items = crossedItems()
        var probed = 0
        for ((index, item) in Capabilities.ITEMS.withIndex()) {
            val snapshot = crossed(items[index])
            for (constant in snapshot.notPerformed) {
                val dropped = snapshot.copy(notPerformed = snapshot.notPerformed - constant)
                assertEquals(listOf("notPerformed"), differences(dropped, item), "item #${item.number} without $constant")
                // A constant appearing twice — what two constants collapsing onto one name produce —
                // leaves the set unchanged and is caught by the size half of `sameSet`.
                val collapsed = snapshot.copy(notPerformed = snapshot.notPerformed + constant)
                assertEquals(listOf("notPerformed"), differences(collapsed, item), "item #${item.number} $constant twice")
                probed++
            }
        }
        assertTrue(probed > 0, "no item names a not-performed constant, so this control probed nothing")
    }
}
