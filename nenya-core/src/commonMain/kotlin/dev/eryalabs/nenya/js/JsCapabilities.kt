package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.conformance.ConformanceItem
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// §17's capability surface — T50's one. `Capabilities` is the "machine-readable statement of what it
// verifies" §17 requires of an implementation that omits a check, and §16.2's "explicit unsupported
// state"; until this entry point a page could read none of it. Translated and nothing more: no status
// and no note is written here, every value is read off `Capabilities` at the moment a getter runs.
// =================================================================================================

/**
 * §17's capability surface, as [Capabilities] publishes it: every item, the derived not-performed
 * set, and the report text. Takes nothing and never refuses — the surface is a statement about this
 * library, not about any event.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun capabilities(): JsCapabilities = JsCapabilities()

/**
 * What [capabilities] answered.
 *
 * Not evidence and not a warrant, exactly as [Capabilities] is not: it publishes absences. A page
 * reading `"PERFORMED_HERE"` on item 6 has learned what this library does, not that any payment
 * settled (§9.1).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsCapabilities internal constructor() {

    /** [Capabilities.SPECIFICATION]: `"NENYA-1"`. */
    public val specification: String get() = Capabilities.SPECIFICATION

    /** [Capabilities.ITEMS], every one of them, in §17's order. */
    public val items: Array<JsConformanceItem>
        get() = Array(Capabilities.ITEMS.size) { i -> JsConformanceItem(Capabilities.ITEMS[i]) }

    /**
     * [Capabilities.NOT_PERFORMED_HERE] in its declaration order, each constant qualified by its enum
     * through [Capabilities.qualify] — `"PaymentCheck.INVOICE_AMOUNT"` — so two constants of one name
     * in two enums cannot collapse into one string.
     */
    public val notPerformedHere: Array<String>
        get() = Capabilities.NOT_PERFORMED_HERE.map(Capabilities::qualify).toTypedArray()

    /** [Capabilities.report], verbatim. */
    public val report: String get() = Capabilities.report()

    override fun toString(): String = "JsCapabilities(${Capabilities.ITEMS.size} items)"
}

/**
 * One of §17's ten items, as [ConformanceItem] publishes it. Each set crosses as an array in the
 * set's own order; `notPerformed` is qualified exactly as [JsCapabilities.notPerformedHere] is.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsConformanceItem internal constructor(private val item: ConformanceItem) {

    /** §17's own numbering, `1`–`10`. */
    public val number: Int get() = item.number

    /** `ConformanceStatus.name`: `"PERFORMED_HERE"`, `"PARTIAL"` or `"NOT_PERFORMED_HERE"`. */
    public val status: String get() = item.status.name

    /** The sections the item cites, as bare `x.y` strings without the `§`. */
    public val specSections: Array<String> get() = item.specSections.toTypedArray()

    /** Fully-qualified names of the classes that do this item's work. */
    public val evidenceClasses: Array<String> get() = item.evidenceClasses.toTypedArray()

    /** The checks this item is missing, each qualified by its enum. */
    public val notPerformed: Array<String> get() = item.notPerformed.map(Capabilities::qualify).toTypedArray()

    /** The item's paragraph, verbatim. */
    public val note: String get() = item.note

    override fun toString(): String = "JsConformanceItem(#$number, $status)"
}
