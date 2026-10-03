package dev.eryalabs.nenya.payment

import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The structural half of §9.1 and STOP RULE 12, enforced by reflection rather than by review.
 *
 * §9.1 says a wallet's boolean, a counterparty's status string and a relay's acceptance are
 * not evidence. A code review can only assert that today's code obeys that; this file asserts
 * it about whatever is in the package tomorrow, which is when somebody adds
 * `verify(..., walletSaysPaid: Boolean)` in a hurry.
 *
 * ### Every type inspection here walks the generic signature
 *
 * This file is the shape the other packages' structural sweeps were copied from, and several of
 * their KDocs say so by name, so the correction belongs here in full rather than as a line in each
 * of them.
 *
 * A sweep that reads `Method.parameterTypes` or `Constructor.parameterTypes` sees the **erased**
 * type. `List<Boolean>` erases to `List`, `Map<String, Boolean>` erases to `Map` — so the rule
 * below, "no published constructor or method accepts a Boolean", was satisfied by
 * `verify(..., walletSaysPaid: List<Boolean>)`, which is §9.1 undone by the addition of a pair of
 * angle brackets. The same reading let a status string in as `List<String>` past the pinned list.
 * Both scans therefore read `genericParameterTypes` and match on `typeName`, which prints the
 * parameterised form `java.util.List<java.lang.Boolean>`, through [namesType] — a whole-token
 * matcher, because a bare `contains` for `java.lang.String` also matches
 * `java.lang.StringBuilder`. [BooleanEvidenceProbe] and [StringEvidenceProbe] are what prove the
 * two predicates can fail: each is fed to the sweep's **own** named predicate, never to a second
 * copy of the check, since a control exercising a copy proves nothing about the original.
 *
 * Two deliberate narrowings, stated so a later reader does not read them as oversights.
 * **Parameters only, no return type** — both rules are about what a member *accepts*, and
 * `PaymentHash.equals` returns `boolean` while `PaymentHash.toHex` returns `String`, so a sweep
 * that also walked `genericReturnType` would be a different rule and a false one. Widening either
 * to the return side is a change to the rule, not to the reflection. And **the pinned signature of
 * [VerifiedPayment.Companion.verify] carries no probe**: an exact whole-list equality cannot be
 * erasure-blind in the dangerous direction, because a `List<PaymentHash>` parameter erases to
 * `List` and fails the pin outright.
 *
 * ### `java.lang.reflect` only
 *
 * `kotlin-reflect` is not on any classpath and STOP RULE 11 forbids adding one. The two
 * failure modes differ and both are traps: `KClass.members` and `KClass.constructors` compile
 * and then throw `KotlinReflectionNotSupportedError` at run time, while `kotlin.reflect.full.*`
 * does not compile at all. So: `getConstructors()`, `getMethods()`,
 * `getGenericParameterTypes()`, `getGenericReturnType()`, `Modifier`, and nothing else.
 *
 * ### Why `getResources`, plural
 *
 * `ClassLoader.getResource("dev/eryalabs/nenya/payment")` resolves to the **test** output
 * directory, because `build/classes/kotlin/jvm/test` precedes `build/classes/kotlin/jvm/main` on the
 * test runtime classpath and this repo mirrors package names into its test tree. A sweep built
 * on the singular form would enumerate this very file's class, never see [VerifiedPayment],
 * and pass with the implementation entirely absent. [mainClasses] therefore takes the plural
 * form and selects the `/classes/kotlin/jvm/main/` URL, failing loudly if there is none.
 *
 * ### What "the published surface" means here
 *
 * A JVM-public class, and on it: its public constructors, plus its public methods declared on
 * the class itself whose names carry no `$`. The `$` filter removes Kotlin's synthetic
 * accessors and its name-mangled `internal` members, which are public in the bytecode but are
 * not something a client can call by writing the name down. Inherited `java.lang.Object` and
 * `java.lang.Enum` methods are excluded by the declaring-class filter.
 */
class PaymentStructureTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.payment"

        /**
         * The classes this sweep is *about*. Asserted by name rather than by count: a count is
         * satisfied by whatever the wrong classpath entry happened to contain, which is exactly
         * the failure the plural `getResources` above exists to avoid.
         */
        val EXPECTED_BY_NAME: Set<String> =
            setOf("VerifiedPayment", "Preimage", "PaymentHash", "Payee")

        /**
         * Every place on the published surface where a `String` may be a parameter, pinned by
         * name. A new one turns this red and forces a human to say why it is not a status
         * string being smuggled in as evidence. Each of these is either a hex reader (§4.3's
         * encoding, not a status), a diagnostic message, or a compiler-generated enum lookup.
         */
        val STRING_PARAMETERS_PERMITTED: Set<String> = setOf(
            "PaymentHash\$Companion.ofHex",       // §4.3 hex, 64 characters, either case
            "Preimage\$Companion.ofHex",          // §4.3 hex, 64 characters, lowercase only
            "PaymentException.<init>",            // the human-readable rejection message
            "Payee.valueOf",                      // generated by the Kotlin compiler for enums
            "PaymentCheck.valueOf",               // ditto
            "PaymentRejection.valueOf",           // ditto
        )

        fun mainClasses(): List<Class<*>> {
            val loader = PaymentStructureTest::class.java.classLoader
            val urls = loader.getResources(PACKAGE.replace('.', '/')).toList()
            val main = urls.firstOrNull { it.path.contains("/classes/kotlin/jvm/main/") }
                ?: fail(
                    "no /classes/kotlin/jvm/main/ URL for $PACKAGE among $urls — the sweep would " +
                        "otherwise enumerate the test output directory and pass with no implementation",
                )
            val directory = File(main.toURI())
            val files = directory.listFiles { file: File -> file.name.endsWith(".class") }
                ?: fail("${directory.absolutePath} is not a readable directory")
            assertTrue(files.isNotEmpty(), "${directory.absolutePath} holds no classes")
            // The class files this package compiled to at commit 6432814, pinned as a floor so a
            // partial output directory after a source-layout move goes red instead of sweeping less.
            val pinned = 16
            assertTrue(
                files.size >= pinned,
                "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were pinned",
            )
            return files.map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
        }

        fun publishedClasses(): List<Class<*>> =
            mainClasses().filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

        /** Constructors and own public methods, minus everything a client cannot name. */
        fun publishedExecutables(type: Class<*>): List<Executable> =
            type.constructors.toList() +
                type.methods.filter {
                    it.declaringClass == type && !it.isSynthetic && !it.isBridge && '$' !in it.name
                }

        fun label(type: Class<*>, executable: Executable): String {
            val simple = type.name.removePrefix("$PACKAGE.")
            val member = if (executable is java.lang.reflect.Constructor<*>) "<init>" else executable.name
            return "$simple.$member"
        }

        /**
         * The **generic** type names [executable] accepts, one per parameter.
         *
         * `typeName` prints the parameterised form — `java.util.List<java.lang.Boolean>` where the
         * erased `Class` prints `interface java.util.List` — which is the whole point: the two
         * rules below are about a type this package forbids, and a forbidden type wrapped in a
         * collection is still handed to the caller.
         *
         * Parameters only. See this file's KDoc: the return side is a different rule, and a false
         * one here, because `PaymentHash.equals` returns `boolean` and `toHex` returns `String`.
         */
        fun parametersOf(executable: Executable): List<String> =
            executable.genericParameterTypes.map { it.typeName }

        /**
         * Both spellings of the type §9.1 forbids, derived from the two `Class` objects the erased
         * check used to compare against so that the new predicate demonstrably covers the old one.
         *
         * A bare Kotlin `Boolean` parameter erases to the JVM primitive, whose `typeName` is
         * `boolean`; inside a generic it is boxed and reads `java.lang.Boolean`. A converted
         * predicate that checked only one of the two would be a regression in the other direction.
         */
        val BOOLEAN_TYPES: List<String> = listOf(
            java.lang.Boolean.TYPE.typeName,
            java.lang.Boolean::class.java.typeName,
        )

        /** `String` as it appears inside a `typeName`, bare or as a generic's type argument. */
        val STRING_TYPE: String = String::class.java.typeName

        /**
         * The first parameter of [executable] that names a Boolean, in its generic form, or `null`.
         *
         * Returned rather than asserted so the sweep's failure message can print the shape it
         * found, and so the probe below exercises *this* function rather than a copy of it.
         */
        fun booleanParameterIn(executable: Executable): String? =
            parametersOf(executable).firstOrNull { parameter ->
                BOOLEAN_TYPES.any { namesType(parameter, it) }
            }

        /** The first parameter of [executable] that names a String, in its generic form, or `null`. */
        fun stringParameterIn(executable: Executable): String? =
            parametersOf(executable).firstOrNull { namesType(it, STRING_TYPE) }

        /**
         * Whether [typeName] names [sought] itself or names it inside a generic's type arguments.
         *
         * A bare `contains` is wrong: `java.lang.StringBuilder` contains `java.lang.String`, and a
         * sweep that flagged a `StringBuilder` parameter as a status string would be loosened by
         * the first person who hit it. So the match is on a whole type token — the character
         * either side must not continue an identifier. `java.util.List<java.lang.Boolean>` matches
         * on `<` and `>`, `boolean[]` matches on `[`, and a bare type matches at either end.
         *
         * Plain string operations rather than a `Regex`: the reflection helper each package keeps
         * is deliberately its own copy, and a shared one would be a single sweep deciding at run
         * time which package it is about.
         */
        fun namesType(typeName: String, sought: String): Boolean {
            var from = 0
            while (true) {
                val at = typeName.indexOf(sought, from)
                if (at < 0) return false
                val before = if (at == 0) ' ' else typeName[at - 1]
                val afterAt = at + sought.length
                val after = if (afterAt >= typeName.length) ' ' else typeName[afterAt]
                if (!continuesIdentifier(before) && !continuesIdentifier(after)) return true
                from = at + 1
            }
        }

        private fun continuesIdentifier(c: Char): Boolean =
            c.isLetterOrDigit() || c == '.' || c == '$' || c == '_'
    }

    @Test
    fun `the sweep finds the classes it is about, by name`() {
        val found = mainClasses().map { it.name.removePrefix("$PACKAGE.") }.toSet()

        for (expected in EXPECTED_BY_NAME) {
            assertTrue(
                expected in found,
                "$expected was not among the main classes of $PACKAGE; found $found",
            )
        }
    }

    @Test
    fun `VerifiedPayment has no constructor for a client to reach`() {
        val type = mainClasses().single { it.name == "$PACKAGE.VerifiedPayment" }

        assertTrue(type.isInterface, "an interface has no constructor to synthesise an accessor for")
        assertEquals(
            0,
            type.constructors.size,
            "a private constructor plus a companion factory emits a *public synthetic* constructor " +
                "with a trailing DefaultConstructorMarker, which a Java client can call with null",
        )
        assertTrue(
            type.declaredConstructors.none { it.isSynthetic },
            "not even a synthetic constructor may exist on ${type.name}",
        )
        assertTrue(
            type.isSealed,
            "the JVM PermittedSubclasses attribute is what stops another module implementing " +
                "this interface; it is emitted only from JDK 17 upward, so a toolchain drop " +
                "would silently remove it while every other assertion here stayed green",
        )
    }

    @Test
    fun `every implementation of VerifiedPayment is non-public`() {
        val classes = mainClasses()
        val type = classes.single { it.name == "$PACKAGE.VerifiedPayment" }
        val implementations = classes.filter { it != type && type.isAssignableFrom(it) }

        assertTrue(
            implementations.isNotEmpty(),
            "the sweep must actually find an implementation; the two assertions above are true of " +
                "any interface at all and would pass over a public one",
        )
        for (implementation in implementations) {
            assertFalse(
                Modifier.isPublic(implementation.modifiers),
                "${implementation.name} implements VerifiedPayment and is JVM-public, so a client " +
                    "can construct evidence of a payment that was never made",
            )
        }
    }

    /**
     * STOP RULE 12 and §9.1, read over the **generic** parameter list: `List<Boolean>` erases to
     * `List`, so the erased form of this sweep passed over the exact shape it exists to forbid.
     */
    @Test
    fun `no published constructor or method accepts a Boolean`() {
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                val offending = booleanParameterIn(executable)
                assertNull(
                    offending,
                    "${label(type, executable)} takes a Boolean, as $offending. §9.1: a wallet's " +
                        "isPaid is not evidence, and a function that can be told the answer is not " +
                        "a verifier",
                )
            }
        }
        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * The control that proves the sweep above is not erasure-blind.
     *
     * Without it, "no published member takes a Boolean" is satisfied by a predicate that never
     * matches a `List<Boolean>` — which is precisely what the erased-type check it replaced was.
     * The probe is fed to [booleanParameterIn], the sweep's own predicate, and not to a copy: a
     * control exercising a second implementation of the check proves nothing about the first.
     */
    @Test
    fun `the Boolean sweep catches a Boolean hidden inside a generic type`() {
        val probe = BooleanEvidenceProbe::class.java
        for (name in listOf("wrapped", "keyed", "nested", "arrayed")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                booleanParameterIn(method),
                "BooleanEvidenceProbe.$name hides a Boolean and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases past an erased-type check",
            )
        }
        // Caught by the old erased check too: this is what shows the two controls differ.
        assertNotNull(booleanParameterIn(probe.methods.single { it.name == "direct" }))
        // Not merely "reject every generic".
        assertNull(booleanParameterIn(probe.methods.single { it.name == "permitted" }))
    }

    /** A probe, not a fixture: four shapes §9.1 forbids, the bare one, and one it permits. */
    @Suppress("unused")
    private class BooleanEvidenceProbe {
        fun wrapped(flags: List<Boolean>): Int = flags.size
        fun keyed(byPayee: Map<String, Boolean>): Int = byPayee.size
        fun nested(flags: List<List<Boolean>>): Int = flags.size
        fun arrayed(flags: BooleanArray): Int = flags.size
        fun direct(walletSaysPaid: Boolean): Int = if (walletSaysPaid) 1 else 0
        fun permitted(hashes: List<PaymentHash>): Int = hashes.size
    }

    /**
     * §9.1's other half, over the generic parameter list for the same reason as the Boolean rule:
     * a counterparty's status token wrapped in a `List<String>` or keyed into a
     * `Map<String, Int>` is still a status token, and the erased form of this scan admitted both
     * without adding a line to the pinned list.
     */
    @Test
    fun `every published String parameter is on the pinned list`() {
        val found = mutableSetOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                if (stringParameterIn(executable) != null) {
                    found += label(type, executable)
                }
            }
        }

        assertEquals(
            STRING_PARAMETERS_PERMITTED,
            found,
            "the set of published members taking a String must match the pinned list exactly. A new " +
                "entry may be a status string arriving as evidence (§9.1); a missing entry means the " +
                "sweep stopped seeing the package.",
        )
    }

    /**
     * The control that proves the sweep above is not erasure-blind — and, separately, that it is
     * not blind in the *other* direction either.
     *
     * A whole-token matcher is what makes both halves true at once. `contains` alone would flag
     * `StringBuilder`, and the person who hit that would loosen the rule rather than the matcher;
     * an erased `Class` comparison misses every `String` inside a generic. [StringEvidenceProbe]
     * asserts both against [stringParameterIn] itself.
     */
    @Test
    fun `the String sweep catches a String hidden inside a generic type, and no lookalike`() {
        val probe = StringEvidenceProbe::class.java
        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                stringParameterIn(method),
                "StringEvidenceProbe.$name hides a String and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases past an erased-type check",
            )
        }
        // Caught by the old erased check too: this is what shows the two controls differ.
        assertNotNull(stringParameterIn(probe.methods.single { it.name == "direct" }))
        // Not merely "reject every generic".
        assertNull(stringParameterIn(probe.methods.single { it.name == "permitted" }))
        // And not a bare `contains`: java.lang.StringBuilder is not a status string.
        assertNull(stringParameterIn(probe.methods.single { it.name == "lookalike" }))
    }

    /** A probe, not a fixture: three hidden Strings, the bare one, and two the rule permits. */
    @Suppress("unused")
    private class StringEvidenceProbe {
        fun wrapped(statuses: List<String>): Int = statuses.size
        fun keyed(byStatus: Map<String, Int>): Int = byStatus.size
        fun nested(statuses: List<List<String>>): Int = statuses.size
        fun direct(status: String): Int = status.length
        fun permitted(hashes: List<PaymentHash>): Int = hashes.size
        fun lookalike(text: StringBuilder): Int = text.length
    }

    /**
     * The one assertion here that carries **no probe**, and deliberately.
     *
     * An exact whole-list equality cannot be erasure-blind in the dangerous direction: a
     * `List<PaymentHash>` parameter erases to `List` and fails the pin outright, so there is no
     * hidden shape for a control to demonstrate. It is still written against the generic signature,
     * because the expected side must be the same language as the actual one and because a future
     * parameterised entry should read as what it is. The expected names come from
     * `::class.java.typeName` rather than `.name`: for an array the two differ — `[B` against
     * `byte[]` — and `genericParameterTypes` reports the latter.
     */
    @Test
    fun `the verifier takes a preimage and a payment hash, and nothing that can assert`() {
        val companion = mainClasses().single { it.name == "$PACKAGE.VerifiedPayment\$Companion" }
        val verify = companion.methods.single { it.name == "verify" }

        assertEquals(
            listOf(
                Payee::class.java.typeName,
                PaymentHash::class.java.typeName,
                Preimage::class.java.typeName,
            ),
            verify.genericParameterTypes.map { it.typeName },
            "§9.2 check 3 takes a preimage and the hash to check it against; anything else on this " +
                "parameter list is something a counterparty could say",
        )
        assertEquals(VerifiedPayment::class.java.typeName, verify.genericReturnType.typeName)
    }
}
