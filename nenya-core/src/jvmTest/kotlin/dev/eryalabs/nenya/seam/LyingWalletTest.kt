package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The §9.1 type-level sweeps that need the JVM, on top of the common tests in
 * [PortableLyingWalletTest].
 *
 * Both read the compiled seam and payment packages through `java.lang.reflect` ([SeamReflection]),
 * which common Kotlin has no equivalent of.
 */
class LyingWalletTest : PortableLyingWalletTest() {

    @Test
    fun `no published member of the seam package can produce or hold payment evidence`() {
        val published = SeamReflection.publishedClasses()
        // Its own by-name guard. An empty offender list is what this test asserts, so it is
        // exactly the shape that stays green when the sweep resolves to the wrong output
        // directory — the trap the delivery package's Boolean sweep fell into and was pulled out of.
        assertTrue(
            published.map { it.simpleName }.containsAll(listOf("Wallet", "WalletPaymentClaim", "SeamAnswer")),
            "the sweep must be looking at the seam package; it found ${published.map { it.simpleName }}",
        )

        val offenders = mutableListOf<String>()
        for (type in published) {
            for (method in SeamReflection.publishedMethods(type)) {
                // Generic-aware. `getReturnType()` is the erasure, so a
                // `Wallet.evidence(): SeamAnswer<VerifiedPayment>` reports `SeamAnswer` and slips
                // straight past an erasure-only check — and `SeamAnswer<T>` is the shape *every*
                // seam method returns, so it is exactly the wrapper that would defeat this sweep.
                if (SeamReflection.mentions(method.genericReturnType, VerifiedPayment::class.java)) {
                    offenders += "${SeamReflection.label(type, method)} returns ${method.genericReturnType}"
                }
            }
            for (field in type.fields) {
                if (SeamReflection.mentions(field.genericType, VerifiedPayment::class.java)) {
                    offenders += "${type.name}.${field.name} holds ${field.genericType}"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a seam is a place a lie enters. §9.2's verifier takes a Preimage and a PaymentHash and " +
                "nothing else, and the day something in this package can hand back a VerifiedPayment " +
                "is the day a wallet's claim becomes evidence by a shorter route",
        )
    }

    /**
     * §9.1's door into payment evidence, pinned and then scanned.
     *
     * Both halves read `genericParameterTypes`. `getParameterTypes()` is the erasure, so a
     * `List<WalletPaymentClaim>` parameter reports as plain `List` and a scan built on it sees
     * nothing — which is the shortest route there is from a wallet's claim to evidence.
     *
     * The pin needs no probe of its own: a whole-list equality cannot be erasure-blind in the
     * dangerous direction, because a `List<PaymentHash>` parameter erases to `List` and fails the
     * pin outright. The scan below it can be, so it has one: the probe test that follows.
     */
    @Test
    fun `the payment verifier takes nothing a seam supplies`() {
        val companion = Class.forName("dev.eryalabs.nenya.payment.VerifiedPayment\$Companion")
        val verify = companion.methods.single { it.name == "verify" && !it.isSynthetic && !it.isBridge }

        assertEquals(
            listOf(
                Payee::class.java.typeName,
                Class.forName("dev.eryalabs.nenya.payment.PaymentHash").typeName,
                Preimage::class.java.typeName,
            ),
            verify.genericParameterTypes.map { it.typeName },
            "the only door into payment evidence must stay closed to the seams: a Wallet, a " +
                "WalletPaymentClaim or a SeamAnswer on this parameter list would be §9.1 undone",
        )
        for (parameter in verify.genericParameterTypes) {
            assertFalse(
                seamTypeIn(parameter),
                "${parameter.typeName} is a seam type and is on VerifiedPayment.verify's parameter list",
            )
        }
    }

    /**
     * The control that proves the scan above is not erasure-blind.
     *
     * Without it, "no seam type on this parameter list" is satisfied by a check that never matches
     * a `List<Wallet>` — which is precisely what the erased-type check it replaced was. The probe
     * is fed to [seamTypeIn], the scan's **own** predicate, so a control that passed while the scan
     * stayed blind is not expressible: there is one implementation of the rule, not two.
     *
     * All three of the rule's clauses are probed inside a generic, because the third one — a type
     * declared in the seam package that is neither a [Seam] nor a [SeamAnswer], of which
     * [WalletPaymentClaim] is the one that matters here — is the clause [SeamReflection.mentions]
     * does not cover and [SeamReflection.mentionsPackage] was added for.
     */
    @Test
    fun `the seam-type scan catches a seam hidden inside a generic parameter`() {
        val probe = SeamParameterProbe::class.java
        // `single` throws if the member is missing, so a probe that never compiled cannot pass here.
        fun parameterOf(name: String): java.lang.reflect.Type =
            probe.methods.single { it.name == name }.genericParameterTypes.single()

        for (name in listOf("wrapped", "claimed", "keyed", "nested")) {
            val hidden = parameterOf(name)
            assertTrue(
                seamTypeIn(hidden),
                "SeamParameterProbe.$name hides a seam type and the scan missed it — " +
                    "${hidden.typeName} erases to a bare collection, which is what walks past an " +
                    "erased-type check",
            )
        }
        // Caught by the old erased check too: this is what shows the two read different things.
        assertTrue(seamTypeIn(parameterOf("direct")))
        // Not merely "reject every generic".
        assertFalse(seamTypeIn(parameterOf("permitted")))
    }

    /**
     * A probe, not a fixture: four parameter shapes the scan forbids, one it forbids on the erasure
     * alone, and one it permits.
     *
     * Declared here and never in `src/jvmMain`, and read by reflection rather than by name, so a
     * probe that failed to reach the test output cannot make its control pass vacuously — `single`
     * throws when the member is absent.
     */
    @Suppress("unused")
    private class SeamParameterProbe {

        /** A [Seam] inside a collection — invisible to `getParameterTypes()`. */
        fun wrapped(wallets: List<Wallet>): Int = wallets.size

        /** A seam-package type that is neither a [Seam] nor a [SeamAnswer], inside a collection. */
        fun claimed(claims: List<WalletPaymentClaim>): Int = claims.size

        /** A [SeamAnswer] as a map's value type — the wrapper every seam method returns. */
        fun keyed(answers: Map<String, SeamAnswer<String>>): Int = answers.size

        /** Two levels down, so the walk has to recurse rather than look one argument deep. */
        fun nested(wallets: List<List<Wallet>>): Int = wallets.size

        /** The shape the erased check caught as well. */
        fun direct(wallet: Wallet): Int = wallet.hashCode()

        /** A generic of a permitted type: the scan must not simply reject every parameterised type. */
        fun permitted(names: List<String>): Int = names.size
    }

    private companion object {

        /**
         * Whether [type] is, or hides anywhere inside its type arguments, something a seam supplies.
         *
         * The three clauses are the ones the erased scan stated and all it stated — a [Seam], a
         * [SeamAnswer], or any type declared in the seam package — each now read through the
         * generic form. Named, rather than left inline, so that the scan and its probe run the same
         * code: a control exercising a second copy of the check proves nothing about the first.
         */
        fun seamTypeIn(type: java.lang.reflect.Type): Boolean =
            SeamReflection.mentions(type, Seam::class.java) ||
                SeamReflection.mentions(type, SeamAnswer::class.java) ||
                SeamReflection.mentionsPackage(type, SeamReflection.PACKAGE)
    }
}

/**
 * The reflection sweep this package shares, in the shape `PaymentStructureTest` specifies and
 * for the reasons it gives, over `dev/eryalabs/nenya/seam`.
 *
 * - **`java.lang.reflect` only.** `kotlin-reflect` is not on any classpath and stop rule 11
 *   forbids adding one. `KClass.members` and `KClass.constructors` compile and then throw
 *   `KotlinReflectionNotSupportedError` at run time; `kotlin.reflect.full.*` does not compile at
 *   all, so reaching for it burns a strike on a red build rather than a red test.
 * - **`getResources`, plural.** The singular form resolves to the **test** output directory,
 *   because `build/classes/kotlin/jvm/test` precedes `build/classes/kotlin/jvm/main` on the test runtime
 *   classpath and this repo mirrors package names into its test tree. A sweep built on it would
 *   enumerate this very file's class, never see [Signer], and pass with the implementation
 *   entirely absent. It fails loudly when no `/classes/kotlin/jvm/main/` URL is found.
 * - **Classes asserted by name**, never by count, which is what the plural form exists to make
 *   meaningful.
 *
 * Extracted into its own object rather than duplicated per test class because four files in this
 * package need it. The delivery and payment packages each keep their own copy; sharing across
 * packages would mean one sweep deciding which package it is about at run time, which is exactly
 * the ambiguity the by-name assertions exist to remove.
 */
internal object SeamReflection {

    const val PACKAGE: String = "dev.eryalabs.nenya.seam"

    /** Every class Gradle wrote for this package from `src/commonMain/kotlin` and `src/jvmMain/kotlin`. */
    fun mainClasses(): List<Class<*>> = classesIn("/classes/kotlin/jvm/main/", MIN_MAIN_CLASSES)

    /**
     * Every class Gradle wrote for this package from `src/commonTest/kotlin` and
     * `src/jvmTest/kotlin` — where the fakes live. The JVM test compilation writes both to one directory.
     */
    fun testClasses(): List<Class<*>> = classesIn("/classes/kotlin/jvm/test/", MIN_TEST_CLASSES)

    /**
     * The class files each output directory held for this package at commit 6432814, pinned as
     * floors: a partial directory after a source-layout move must go red, not sweep the remainder.
     */
    private const val MIN_MAIN_CLASSES: Int = 33
    private const val MIN_TEST_CLASSES: Int = 30

    fun publishedClasses(): List<Class<*>> =
        mainClasses().filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    /** Own public methods, minus bridges, synthetics and everything a client cannot name. */
    fun publishedMethods(type: Class<*>): List<java.lang.reflect.Method> =
        type.methods.filter {
            it.declaringClass == type && !it.isSynthetic && !it.isBridge && '$' !in it.name
        }

    /**
     * The name a human reads. Kotlin mangles a public function whose parameter list contains an
     * inline class — `Wallet.issueInvoice(Msat)` is emitted as `issueInvoice-xTAHlNM` — so the
     * hash is stripped, or every label in this file would change the day a parameter type did.
     */
    fun label(type: Class<*>, executable: java.lang.reflect.Executable): String {
        val simple = type.name.removePrefix("$PACKAGE.")
        val member = when (executable) {
            is java.lang.reflect.Constructor<*> -> "<init>"
            else -> executable.name.substringBefore('-')
        }
        return "$simple.$member"
    }

    fun byName(name: String): Class<*> = mainClasses().single { it.name == "$PACKAGE.$name" }

    /**
     * Whether [type] mentions [sought] anywhere, including inside a generic's type arguments.
     *
     * `Method.getReturnType()` and `Field.getType()` are erasures, so `SeamAnswer<VerifiedPayment>`
     * reports as plain `SeamAnswer`. Every seam method returns `SeamAnswer<T>`, which makes that
     * wrapper the one shape capable of defeating a sweep built on the erasure — so the sweeps read
     * `genericReturnType`/`genericType` and this walks what they return.
     */
    fun mentions(type: java.lang.reflect.Type, sought: Class<*>): Boolean = when (type) {
        is Class<*> -> sought.isAssignableFrom(type)
        is java.lang.reflect.ParameterizedType ->
            mentions(type.rawType, sought) || type.actualTypeArguments.any { mentions(it, sought) }
        is java.lang.reflect.WildcardType ->
            type.upperBounds.any { mentions(it, sought) } || type.lowerBounds.any { mentions(it, sought) }
        is java.lang.reflect.GenericArrayType -> mentions(type.genericComponentType, sought)
        else -> false
    }

    /**
     * Whether [type] names a class whose package is [prefix], anywhere including inside a generic's
     * type arguments.
     *
     * [mentions] answers a subtyping question, which cannot express "declared over there": a
     * `WalletPaymentClaim` is neither a [Seam] nor a [SeamAnswer], and a rule that reads "nothing
     * from the seam package" needs it caught all the same. The recursion is [mentions]' own, walked
     * again rather than shared through a common higher-order walker, because three other files in
     * this package call [mentions] and a refactor of its body to serve this one is a change to code
     * they depend on for the sake of code they do not.
     */
    fun mentionsPackage(type: java.lang.reflect.Type, prefix: String): Boolean = when (type) {
        is Class<*> -> type.name.startsWith(prefix)
        is java.lang.reflect.ParameterizedType ->
            mentionsPackage(type.rawType, prefix) ||
                type.actualTypeArguments.any { mentionsPackage(it, prefix) }
        is java.lang.reflect.WildcardType ->
            type.upperBounds.any { mentionsPackage(it, prefix) } ||
                type.lowerBounds.any { mentionsPackage(it, prefix) }
        is java.lang.reflect.GenericArrayType -> mentionsPackage(type.genericComponentType, prefix)
        else -> false
    }

    private fun classesIn(marker: String, pinned: Int): List<Class<*>> {
        val loader = SeamReflection::class.java.classLoader
        val urls = loader.getResources(PACKAGE.replace('.', '/')).toList()
        val chosen = urls.firstOrNull { it.path.contains(marker) }
            ?: fail(
                "no $marker URL for $PACKAGE among $urls — a sweep that fell back to the other " +
                    "output directory would pass with the code it is about entirely absent",
            )
        val directory = File(chosen.toURI())
        val files = directory.listFiles { file: File -> file.name.endsWith(".class") }
            ?: fail("${directory.absolutePath} is not a readable directory")
        assertTrue(files.isNotEmpty(), "${directory.absolutePath} holds no classes")
        assertTrue(
            files.size >= pinned,
            "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were pinned",
        )
        return files.map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
    }
}
