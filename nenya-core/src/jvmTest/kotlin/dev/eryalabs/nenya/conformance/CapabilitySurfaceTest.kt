package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.SeamCapability
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §17's last paragraph made executable: the capability surface is checked against the
 * specification and against the compiled library, never against the hand that wrote it.
 *
 * ### The vacuity this file exists to answer
 *
 * A hand-written item-to-status map is a transcription. A test asserting that map against a set
 * the same implementer typed into the test proves the two files agree and nothing else — the
 * failure every other vocabulary in this library was built to avoid (`NenyaTags` is held equal to
 * §5.3 parsed at test time, T6's transition table to §11.2). So three anchors, none of them
 * inside this repository's own opinion:
 *
 * 1. §17's item numbers and the section references each item cites are **parsed out of the
 *    document** and held equal to the surface's, so the count is derived and a revision that moved
 *    an obligation turns this red;
 * 2. every item claimed done names a class that must be found by reflection in
 *    `build/classes/kotlin/jvm/main`, and every item claimed not done names a not-performed constant;
 * 3. the not-performed union is re-derived from the three enums' constants and held equal.
 *
 * Both evidence rules are applied through [evidenceProblem], which is also run against
 * deliberately broken items — so the anchor is proved able to fail rather than assumed to be.
 */
class CapabilitySurfaceTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.conformance"

        /** §17 prints ten items. Asserted as a control on the parser, not consumed as the count. */
        const val ITEMS_IN_SECTION_17: Int = 10

        /** The classes this package's sweep is about, by name rather than by count. */
        val EXPECTED_BY_NAME: Set<String> = setOf(
            "Capabilities",
            "ConformanceItem",
            "ConformanceStatus",
        )

        /** §14 item 12's forbidden types, primitive and boxed, as they appear in a `typeName`. */
        val FLOATING_POINT = Regex("""\b(double|float|java\.lang\.Double|java\.lang\.Float)\b""")

        /** The three enums the union is drawn from, by name, so a rename there turns this red. */
        val CAPABILITY_ENUMS: List<String> = listOf(
            "dev.eryalabs.nenya.payment.PaymentCheck",
            "dev.eryalabs.nenya.delivery.DeliveryCheck",
            "dev.eryalabs.nenya.seam.SeamCapability",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The §17 anchor.
    // -----------------------------------------------------------------------------------------

    /**
     * The parser control, run before anything consumes it: §17 must parse to ten items, and a
     * parse yielding zero must fail loudly rather than making every set equality below
     * "empty equals empty".
     */
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

    // -----------------------------------------------------------------------------------------
    // The evidence anchor, and the controls proving it can fail.
    // -----------------------------------------------------------------------------------------

    /** Every published item's status is backed by something a machine can check. */
    @Test
    fun `every item carries machine-checkable evidence for its own status`() {
        for (item in Capabilities.ITEMS) {
            assertNull(
                evidenceProblem(item),
                "§17 item ${item.number} claims ${item.status} without the evidence that status " +
                    "requires: ${evidenceProblem(item)}",
            )
        }
    }

    /**
     * The control that proves the anchor discriminates, and the mutation T12 names: claim §17
     * item 4 — the §7.2 seal/rumor check and the `kind:10050` publication, neither of which this
     * library does — as performed, and the evidence anchor must refuse it.
     */
    @Test
    fun `an item claimed performed with no class to name fails the evidence anchor`() {
        val real = Capabilities.item(4) ?: fail("§17 item 4 is not published")
        assertEquals(ConformanceStatus.NOT_PERFORMED_HERE, real.status)

        val flipped = ConformanceItem(
            number = real.number,
            status = ConformanceStatus.PERFORMED_HERE,
            specSections = real.specSections,
            evidenceClasses = real.evidenceClasses,
            notPerformed = real.notPerformed,
            note = real.note,
        )

        val problem = evidenceProblem(flipped)
        assertNotNull(
            problem,
            "flipping item 4 to PERFORMED_HERE must fail the evidence anchor: there is no " +
                "gift-wrap machinery in this library for it to name",
        )
        assertTrue("class" in problem, "the refusal must name the missing evidence: $problem")
    }

    /** The other direction: an item claiming nothing was done while pointing at work that was. */
    @Test
    fun `an item claimed not performed while naming a class fails the evidence anchor`() {
        val real = Capabilities.item(8) ?: fail("§17 item 8 is not published")
        assertEquals(ConformanceStatus.PERFORMED_HERE, real.status)

        val flipped = ConformanceItem(
            number = real.number,
            status = ConformanceStatus.NOT_PERFORMED_HERE,
            specSections = real.specSections,
            evidenceClasses = real.evidenceClasses,
            notPerformed = real.notPerformed,
            note = real.note,
        )

        assertNotNull(
            evidenceProblem(flipped),
            "an item claiming nothing was done must not also point at the classes that did it",
        )
    }

    /** A named class that does not exist in `src/jvmMain` fails, so the anchor is not a spelling. */
    @Test
    fun `an item naming a class that is not in src slash main fails the evidence anchor`() {
        val real = Capabilities.item(3) ?: fail("§17 item 3 is not published")

        val invented = ConformanceItem(
            number = real.number,
            status = real.status,
            specSections = real.specSections,
            evidenceClasses = setOf("dev.eryalabs.nenya.bid.PrivateBidCodec"),
            notPerformed = real.notPerformed,
            note = real.note,
        )

        val problem = evidenceProblem(invented)
        assertNotNull(problem, "a class nobody wrote must not count as evidence that it was")
        assertTrue("PrivateBidCodec" in problem, "the refusal must name what it could not find: $problem")
    }

    /**
     * A class that exists only in the **test** tree must not count either — the singular
     * `getResource` failure mode, in the one place it would be invisible.
     */
    @Test
    fun `an item naming a test-tree class fails the evidence anchor`() {
        val real = Capabilities.item(3) ?: fail("§17 item 3 is not published")
        // This very file: public to the JVM, mirrored into the same package name, absent from main.
        val testOnly = ConformanceItem(
            number = real.number,
            status = real.status,
            specSections = real.specSections,
            evidenceClasses = setOf("$PACKAGE.CapabilitySurfaceTest"),
            notPerformed = real.notPerformed,
            note = real.note,
        )

        assertNotNull(
            evidenceProblem(testOnly),
            "a test class satisfying an evidence claim is the singular-getResource failure with a " +
                "capability surface attached to it",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The union.
    // -----------------------------------------------------------------------------------------

    /**
     * The union is re-derived here from the enums' constants through `getEnumConstants()` — the
     * reflective route, against the compiler-generated `entries` the surface uses — and held
     * equal.
     *
     * The two agree on values by construction; what this equality is for is the hand-written list
     * that would replace the derivation on the day somebody finds the derivation inconvenient, and
     * the constant added to one of the three enums that would then never be published. The
     * deletion control below is what proves the equality can fail at all.
     */
    @Test
    fun `the not-performed union is every constant the library does not perform`() {
        val expected = rederivedUnion()

        assertTrue(expected.isNotEmpty(), "the re-derived union is empty, so this proves nothing")
        assertEquals(
            expected,
            Capabilities.NOT_PERFORMED_HERE,
            "the published union must be the three enums' constants minus what the library " +
                "performs. A missing constant is a check this library does not do and does not say " +
                "it does not do, which is the §17 over-claim the surface exists to prevent.",
        )
        // Each group non-empty, so a broken derivation cannot pass by dropping one enum entirely.
        assertTrue(Capabilities.PAYMENT_CHECKS_NOT_PERFORMED.isNotEmpty())
        assertTrue(Capabilities.DELIVERY_CHECKS_NOT_PERFORMED.isNotEmpty())
        assertTrue(Capabilities.SEAM_CAPABILITIES_NOT_PERFORMED.isNotEmpty())
    }

    /** The mutation T12 names: delete one constant from the surface and the equality must fail. */
    @Test
    fun `a capability constant missing from the surface fails the union equality`() {
        val short = Capabilities.NOT_PERFORMED_HERE - PaymentCheck.INVOICE_AMOUNT

        assertNotEquals(
            rederivedUnion(),
            short,
            "dropping PaymentCheck.INVOICE_AMOUNT must break the union equality; it is the check " +
                "a provider sending a receipt for ten times the price is caught by",
        )
        assertTrue(
            PaymentCheck.INVOICE_AMOUNT in Capabilities.NOT_PERFORMED_HERE,
            "and the real surface must carry it",
        )
    }

    /** What the library performs is excluded, so the surface is not simply "everything". */
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

    // -----------------------------------------------------------------------------------------
    // The report, and the structural rules of this package.
    // -----------------------------------------------------------------------------------------

    /** The surface is machine-readable, which is the adjective §17 uses. */
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

    /**
     * The round's headline rule reaching this package too: no published member takes a `Boolean`.
     *
     * A capability surface that could be *told* what it verifies is not a capability surface. The
     * sweep is `PaymentStructureTest`'s, over `dev/eryalabs/nenya/conformance`, asserting its
     * classes by name for the reason that file gives.
     */
    @Test
    fun `no published member of this package accepts a Boolean`() {
        val published = MainClasses.published(PACKAGE)
        val found = published.map { it.name.removePrefix("$PACKAGE.") }.toSet()
        assertTrue(
            EXPECTED_BY_NAME.all { it in found },
            "the sweep must be looking at $EXPECTED_BY_NAME, not at whatever classpath entry it " +
                "landed on; it found $found",
        )

        var inspected = 0
        for (type in published) {
            val executables = type.constructors.toList() + MainClasses.methods(type)
            for (executable in executables) {
                inspected++
                for (parameter in executable.parameterTypes) {
                    assertFalse(
                        parameter == java.lang.Boolean.TYPE || parameter == java.lang.Boolean::class.java,
                        "${type.simpleName}.${executable.name} takes a Boolean. What this library " +
                            "verifies is a fact about its own code; a surface that can be told the " +
                            "answer publishes the caller's opinion.",
                    )
                }
            }
        }
        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * §17 item 10's claim about §14 item 12, made checkable instead of asserted: no published
     * signature anywhere in this library mentions a `Double` or a `Float`.
     *
     * The packages are discovered from the main output tree rather than listed, so a package a
     * later task adds is swept without anybody remembering to add it here.
     *
     * **It reads the generic types, not the erased ones**, and that is not a detail. T11's review
     * found the same sweep shape elsewhere in this repository reading `parameterTypes` and
     * `returnType`, where a published `fun quote(): List<Double>` erases to `List` and satisfies
     * the assertion while handing a caller exactly the thing §14 item 12 forbids, wrapped in a
     * collection. [floatingPointIn] walks `genericParameterTypes`/`genericReturnType` and matches
     * on `typeName`, and the control below proves it catches the wrapped form.
     */
    @Test
    fun `no published signature in this library mentions floating-point money`() {
        val packages = MainClasses.allPackages()
        var inspected = 0
        for (packageName in packages) {
            for (type in MainClasses.published(packageName)) {
                val executables = type.constructors.toList() + MainClasses.methods(type)
                for (executable in executables) {
                    inspected++
                    assertNull(
                        floatingPointIn(executable),
                        "${type.name}.${executable.name} mentions " +
                            "${floatingPointIn(executable)}. §14 item 12 and §4.4: no " +
                            "floating-point money, ever — and a published signature is where one " +
                            "gets in.",
                    )
                }
            }
        }
        assertTrue(
            inspected > 200,
            "the library-wide sweep inspected only $inspected members across $packages, which is " +
                "not this library",
        )
    }

    /**
     * The control that proves the sweep above is not erasure-blind: a bare `Double`, a
     * `List<Double>` and a `Map<String, Float>` must all be caught, and a signature carrying
     * neither must not be.
     *
     * [FloatingPointProbe] is declared in this file precisely so the sweep has something it is
     * required to catch. Without it, "no published signature mentions a Double" is satisfied by a
     * predicate that never matches anything.
     */
    @Test
    fun `the floating-point sweep catches a Double hidden inside a generic type`() {
        val probe = FloatingPointProbe::class.java
        for (name in listOf("bare", "wrapped", "keyed", "parameter")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                floatingPointIn(method),
                "FloatingPointProbe.$name is floating-point money and the sweep missed it — " +
                    "${method.genericReturnType.typeName} erases past an erased-type check",
            )
        }
        val clean = probe.methods.single { it.name == "satoshis" }
        assertNull(floatingPointIn(clean), "an integer signature must not be flagged")
    }

    /** A probe, not a fixture: four shapes §14 item 12 forbids, and one it permits. */
    @Suppress("unused")
    private class FloatingPointProbe {
        fun bare(): Double = 0.0
        fun wrapped(): List<Double> = emptyList()
        fun keyed(): Map<String, Float> = emptyMap()
        fun parameter(price: Double): Long = price.toLong()
        fun satoshis(): Long = 0L
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /**
     * The evidence rule, as one function, so the real items and the deliberately broken ones go
     * through the same code: `null` when [item]'s status is backed by what that status requires,
     * and a sentence naming the failure otherwise.
     *
     * - [ConformanceStatus.PERFORMED_HERE] — at least one class, and no not-performed constants.
     * - [ConformanceStatus.PARTIAL] — at least one of each.
     * - [ConformanceStatus.NOT_PERFORMED_HERE] — at least one constant, and no classes.
     *
     * Every named class must be found by reflection **in the main output tree**, which is what
     * stops an item naming something only the test tree has.
     */
    private fun evidenceProblem(item: ConformanceItem): String? {
        val wantsClasses = item.status != ConformanceStatus.NOT_PERFORMED_HERE
        val wantsConstants = item.status != ConformanceStatus.PERFORMED_HERE

        if (wantsClasses && item.evidenceClasses.isEmpty()) {
            return "status ${item.status} requires at least one class in src/jvmMain and names none"
        }
        if (!wantsClasses && item.evidenceClasses.isNotEmpty()) {
            return "status ${item.status} must name no class, and names ${item.evidenceClasses}"
        }
        if (wantsConstants && item.notPerformed.isEmpty()) {
            return "status ${item.status} requires at least one not-performed constant and names none"
        }
        if (!wantsConstants && item.notPerformed.isNotEmpty()) {
            return "status ${item.status} must name no not-performed constant, and names " +
                item.notPerformed.map(Capabilities::qualify)
        }
        for (name in item.evidenceClasses) {
            val packageName = name.substringBeforeLast('.')
            val found = MainClasses.of(packageName).any { it.name == name }
            if (!found) {
                return "the class $name is not in the main output tree for $packageName, so it is " +
                    "not evidence that anything was implemented"
            }
        }
        for (constant in item.notPerformed) {
            if (constant !in Capabilities.NOT_PERFORMED_HERE) {
                return "${Capabilities.qualify(constant)} is not in the published union"
            }
        }
        return null
    }

    /**
     * The first floating-point type [executable]'s **generic** signature mentions, or `null`.
     *
     * `typeName` prints the parameterised form — `java.util.List<java.lang.Double>` — so a
     * `contains` over it sees what an erased-type check cannot. The word boundaries matter: they
     * keep `java.lang.DoubleStream` and a class named `FloatingPoint` from matching.
     */
    private fun floatingPointIn(executable: java.lang.reflect.Executable): String? {
        val mentioned = executable.genericParameterTypes.map { it.typeName } +
            listOfNotNull((executable as? java.lang.reflect.Method)?.genericReturnType?.typeName)
        return mentioned.firstOrNull { FLOATING_POINT.containsMatchIn(it) }
    }

    /** The union, re-derived through `getEnumConstants()` rather than through `entries`. */
    private fun rederivedUnion(): Set<Enum<*>> {
        val performed: Set<Enum<*>> = buildSet {
            addAll(VerifiedPayment.CHECKS_PERFORMED_HERE)
            addAll(DeliveryEvidence.CHECKS_PERFORMED_HERE)
        }
        val union = LinkedHashSet<Enum<*>>()
        for (name in CAPABILITY_ENUMS) {
            val type = try {
                Class.forName(name, false, javaClass.classLoader)
            } catch (absent: ClassNotFoundException) {
                fail("$name was not found, so the union could not be re-derived: $absent")
            }
            val constants = type.enumConstants
                ?: fail("$name is not an enum, so the surface's derivation cannot be checked")
            assertTrue(constants.isNotEmpty(), "$name declares no constants")
            // Public: a capability surface publishing a constant a caller cannot name is a
            // statement nobody can act on.
            assertTrue(Modifier.isPublic(type.modifiers), "$name is not public")
            for (constant in constants) {
                val value = constant as Enum<*>
                if (value !in performed) union += value
            }
        }
        return union
    }
}
