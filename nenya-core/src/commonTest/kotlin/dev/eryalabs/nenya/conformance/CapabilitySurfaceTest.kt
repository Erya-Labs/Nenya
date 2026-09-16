package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.SeamCapability
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of `CapabilitySurfaceTest` that is common Kotlin: §17's items and the sections each cites,
 * parsed out of the specification and held equal to the surface's; the not-performed union's
 * exclusions; and the machine-readable report.
 *
 * Abstract, and run as `CapabilitySurfaceTest` on every target, so every test keeps its name. The
 * anchors that need reflection (evidence classes found in the main output tree, the union re-derived
 * from the enums' constants, and the package sweeps) are `java.lang.reflect`, and stay in the JVM
 * `CapabilitySurfaceTest`, whose KDoc explains the three anchors.
 */
abstract class PortableCapabilitySurfaceTest {

    private companion object {

        /** §17 prints ten items. Asserted as a control on the parser, not consumed as the count. */
        const val ITEMS_IN_SECTION_17: Int = 10
    }

    /**
     * The parser control, run before anything consumes it: §17 must parse to ten items, and a
     * parse yielding zero must fail loudly rather than making every set equality below
     * "empty equals empty".
     */
    @JsName("s17_parses_to_its_ten_numbered_items")
    @Test
    fun `§17 parses to its ten numbered items`() {
        val parsed = Section17.items

        assertTrue(
            parsed.isNotEmpty(),
            "§17's numbered list parsed to zero items in ${Section17.specPath()}; every anchor in " +
                "this file would then be vacuously true",
        )
        assertEquals(
            ITEMS_IN_SECTION_17,
            parsed.size,
            "§17 in ${Section17.specPath()} parsed to ${parsed.size} items: " +
                "${parsed.map { it.number }}. If the specification genuinely gained or lost one, " +
                "Capabilities.ITEMS is stale and a human must say what the new item's status is.",
        )
        assertEquals((1..ITEMS_IN_SECTION_17).toList(), parsed.map { it.number })
        // Item 1 wraps onto a second line and item 5 over six. A line-per-item parse finds ten
        // items and truncates their text, which is where the section references live.
        assertTrue(
            parsed.single { it.number == 5 }.text.length > 200,
            "§17 item 5 parsed to ${parsed.single { it.number == 5 }.text.length} characters, so " +
                "its continuation lines were dropped and its section references are incomplete",
        )
    }

    /** §17's MAY-omit clause is the MUST this package implements. If it went, this must be seen. */
    @JsName("s17_still_requires_a_capability_surface")
    @Test
    fun `§17 still requires a capability surface`() {
        val clause = Section17.capabilitySurfaceClause()

        assertTrue(
            "machine-readable" in clause,
            "§17's capability-surface clause in ${Section17.specPath()} no longer calls the " +
                "surface machine-readable: $clause",
        )
    }

    /** The item set is derived from the parse on one side and from the library on the other. */
    @JsName("the_surface_publishes_exactly_the_items_s17_numbers")
    @Test
    fun `the surface publishes exactly the items §17 numbers`() {
        val parsed = Section17.items.map { it.number }.toSet()
        val published = Capabilities.ITEMS.map { it.number }.toSet()

        assertEquals(
            parsed,
            published,
            "the capability surface must publish §17's items exactly — not a subset, which would " +
                "quietly drop an obligation, and not a superset, which would claim one §17 does " +
                "not make",
        )
        assertEquals(Capabilities.ITEMS.size, Capabilities.itemCount)
        for (number in parsed) assertNotNull(Capabilities.item(number), "item($number) was null")
        assertNull(Capabilities.item(ITEMS_IN_SECTION_17 + 1), "§17 has no item beyond its last")
    }

    /**
     * The anchor that makes the item set more than a count: two lists of ten integers agree by
     * arithmetic coincidence, and two lists of section references do not.
     */
    @JsName("every_item_cites_the_sections_s17_cites_for_it")
    @Test
    fun `every item cites the sections §17 cites for it`() {
        for (item in Capabilities.ITEMS) {
            assertEquals(
                Section17.sectionsCitedBy(item.number),
                item.specSections,
                "§17 item ${item.number} in ${Section17.specPath()} cites a different set of " +
                    "sections than the surface claims. The specification is the contract: if an " +
                    "obligation moved, the status behind it may have moved too.",
            )
        }
    }

    /** What the library performs is excluded, so the surface is not simply "everything". */
    @JsName("the_union_excludes_the_checks_the_library_does_perform")
    @Test
    fun `the union excludes the checks the library does perform`() {
        for (performed in VerifiedPayment.CHECKS_PERFORMED_HERE) {
            assertFalse(
                performed in Capabilities.NOT_PERFORMED_HERE,
                "$performed is performed here and must not be published as not performed",
            )
        }
        for (performed in DeliveryEvidence.CHECKS_PERFORMED_HERE) {
            assertFalse(performed in Capabilities.NOT_PERFORMED_HERE, "$performed is performed here")
        }
        assertTrue(
            PaymentCheck.PREIMAGE_HASH_COMPARISON in VerifiedPayment.CHECKS_PERFORMED_HERE,
            "the check §9 calls the load-bearing rule must be on the performed side, or the " +
                "exclusion above is excluding nothing",
        )
    }

    /** Every constant an item names is one the union publishes: no private vocabulary. */
    @JsName("every_constant_an_item_names_is_in_the_union")
    @Test
    fun `every constant an item names is in the union`() {
        var named = 0
        for (item in Capabilities.ITEMS) {
            for (constant in item.notPerformed) {
                named++
                assertTrue(
                    constant in Capabilities.NOT_PERFORMED_HERE,
                    "§17 item ${item.number} names ${Capabilities.qualify(constant)}, which the " +
                        "union does not publish",
                )
            }
        }
        assertTrue(named > 0, "no item named a not-performed constant, so this test proved nothing")
    }

    /** Every seam capability is not-performed here, and the surface says so without subtraction. */
    @JsName("no_seam_capability_is_claimed_as_performed_by_this_library")
    @Test
    fun `no seam capability is claimed as performed by this library`() {
        assertEquals(
            SeamCapability.entries.toSet(),
            Capabilities.SEAM_CAPABILITIES_NOT_PERFORMED,
            "this library performs none of §3's seam operations itself: every one is delegated to " +
                "an injected interface whose default answers Unavailable",
        )
        assertTrue(SeamCapability.BIP340_VERIFICATION in Capabilities.NOT_PERFORMED_HERE)
    }

    /** The surface is machine-readable, which is the adjective §17 uses. */
    @JsName("the_report_names_every_item_and_every_not_performed_constant")
    @Test
    fun `the report names every item and every not-performed constant`() {
        val report = Capabilities.report()
        val lines = report.trim().lines()

        assertEquals(
            Capabilities.ITEMS.size + Capabilities.NOT_PERFORMED_HERE.size,
            lines.size,
            "one line per §17 item and one per not-performed constant: $report",
        )
        for (item in Capabilities.ITEMS) {
            assertTrue(
                lines.any { it.startsWith("NENYA-1 §17.${item.number} ${item.status.name} ") },
                "item ${item.number} is not in the report: $report",
            )
        }
        for (constant in Capabilities.NOT_PERFORMED_HERE) {
            assertTrue(
                lines.any { it == "NENYA-1 not-performed ${Capabilities.qualify(constant)}" },
                "${Capabilities.qualify(constant)} is not in the report: $report",
            )
        }
        // Qualified, so INVOICE_AMOUNT and a future constant of that name elsewhere stay apart.
        assertTrue("PaymentCheck.INVOICE_AMOUNT" in report)
        assertTrue("SeamCapability.BIP340_VERIFICATION" in report)
    }
}
