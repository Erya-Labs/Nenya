package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentHash
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §8.6's non-custodial rule and §9.1's evidence rule, enforced by reflection rather than by review.
 *
 * The sweep is the one `PaymentStructureTest` specifies, over `dev/eryalabs/nenya/settlement`, and
 * every note there applies unchanged:
 *
 * - **`java.lang.reflect` only.** `kotlin-reflect` is not on any classpath and STOP RULE 11 forbids
 *   adding one. `KClass.members` and `KClass.constructors` compile — with a warning — and then throw
 *   `KotlinReflectionNotSupportedError` at run time, while `kotlin.reflect.full.*` does not compile
 *   at all, so reaching for it burns a strike on a red build rather than a red test.
 * - **`getResources`, plural, then the `/classes/kotlin/jvm/main/` URL.** The singular form resolves
 *   to the **test** output directory, because `build/classes/kotlin/jvm/test` precedes
 *   `build/classes/kotlin/jvm/main` on the test runtime classpath and this repo mirrors package
 *   names into its test tree. A sweep built on it would enumerate this very file's class, never see
 *   [Settlement], and pass with the implementation entirely absent.
 * - **Classes asserted by name.** A count is satisfied by whatever the wrong classpath entry
 *   happened to contain.
 *
 * ### Every type inspection here walks the generic signature
 *
 * §8.6's rule is the reason that matters in this package rather than a general tidiness point. "An
 * implementation MUST NOT construct, accept, or honour a single invoice covering `total_msat` that
 * some party then splits" is made structural by there being no shape here that names two payees at
 * once — and `List<Payee>` **erases to `List`**, so a sweep reading `parameterTypes`/`returnType`
 * would pass over exactly the shape §8.6 forbids, wrapped in a collection. Every type-inspecting
 * assertion below goes through [mentions], which walks `genericParameterTypes` and
 * `genericReturnType` and matches on `typeName`, and [PayeeCollectionProbe] is what proves it can
 * fail.
 */
class SettlementStructureTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.settlement"

        /** The classes this sweep is *about*, by name rather than by count. */
        val EXPECTED_BY_NAME: Set<String> = setOf(
            "Bolt11Reference",
            "Bolt11Reference\$Companion",
            "PaymentMedium",
            "PaymentRequest",
            "PaymentRequest\$Companion",
            "PaymentRequestStore",
            "PaymentRequestStore\$Companion",
            "AcceptedPaymentRequest",
            "AcceptedPaymentRequest\$Companion",
            "PaymentReceipt",
            "PaymentReceipt\$Companion",
            "Settlement",
            "Settlement\$Companion",
            "Settlement\$Evidenced",
            "Settlement\$Unverified",
            "SettlementRejection",
            "SettlementException",
            "SettlementVocabulary",
            "SettlementTags",
            // §8.4's model and its answer.
            "FeeTermRequirement",
            "FeeTermPoint",
            "FeeTermPoint\$Companion",
            "FeeTermElement",
            "FeeTermSighting",
            "FeeTermSighting\$Companion",
            "FeeTermAgreement",
            "FeeTermAgreement\$Companion",
            "FeeTermAgreement\$Agreed",
            "FeeTermAgreement\$Diverged",
        )

        /**
         * Every published member of this package that returns a bare `boolean`, pinned by name.
         *
         * §8.4 requires a divergence be "surfaced to the user as a **terms mismatch**, not as a
         * transient error", and forbids "take the newest", "take the smaller" and silent
         * renegotiation — three answers a caller told `false` with no point to name is exactly the
         * caller that reaches for. So the answer to "did the fee term match" is
         * [FeeTermAgreement], and this pinned set is what stops a `Boolean` one appearing beside
         * it later: a new entry turns this red and forces a human to say why it is not that
         * answer in the shape §8.4's wording rules out. Same reading as §9.1's rule on the payment
         * question one test down.
         *
         * The one permitted entry is a vocabulary question and not an evidence one: §9.4 asks
         * whether a *rail* has a verification rule at all, which is a fact about NENYA-1 v1 and
         * not about any order.
         */
        val BOOLEAN_RETURNS_PERMITTED: Set<String> = setOf(
            "PaymentMedium.getHasVerificationRule",
        )

        /** The published members whose answer **is** §8.4's, pinned for the same reason. */
        val FEE_TERM_ANSWERS: Set<String> = setOf(
            "FeeTermAgreement\$Companion.across",            // §8.4, reported
            "Settlement\$Companion.checkFeePaymentRequest",  // §8.4 and §8.7, refused
            // §8.4 requires a divergence be surfaced to the user as a terms mismatch, so the
            // point and the side have to survive the throw rather than be flattened into text.
            "SettlementException.getFeeTermDivergence",
        )

        /** See `every published String parameter is on the pinned list` for what this is for. */
        val STRING_PARAMETERS_PERMITTED: Set<String> = setOf(
            "Bolt11Reference.<init>",              // §8.6's opaque reference, recognised in `init`
            "Bolt11Reference\$Companion.recognise", // the same value, through the named door
            "PaymentMedium\$Companion.of",          // §9.4's `<medium>` token
            "PaymentMedium.valueOf",               // generated by the Kotlin compiler for enums
            "SettlementRejection.valueOf",         // ditto
            "SettlementException.<init>",          // the tag name and the rejection message
            "PaymentRequest.<init>",               // §8.6's fee-recipient pubkey, already validated
            "PaymentReceipt.<init>",               // §9.2's medium reference, compared and not read
            // `internal` members: Kotlin publishes them to the JVM, so the sweep sees them even
            // though no client can name them. Each takes a §8.6 **tag name** or a clause of the
            // document quoted into a refusal — never a value a counterparty chose.
            "SettlementTags.single",
            "SettlementTags.missing",
            "SettlementTags\$DecodedPayee.<init>", // §8.6's fee-recipient pubkey, already validated
            // Generated by the Kotlin compiler for §8.4's three vocabularies. Note what is *not*
            // here: no member of the fee-term model takes a String, because §8.4's operand is a
            // whole tag and §8.7's is a seal pubkey this package reads off an attributed rumor
            // rather than being handed.
            "FeeTermPoint.valueOf",
            "FeeTermRequirement.valueOf",
            "FeeTermElement.valueOf",
            // The same, for Appendix C's two vocabularies. `Bolt11Invoice` itself is absent from
            // this list on purpose and that is the point worth reading: it is a sealed interface
            // with a private implementation, so it has no constructor for the sweep to see and no
            // published member takes a String. Its `parse` takes a `Bolt11Reference` — a value this
            // library has already recognised — and never a string a counterparty chose.
            "Bolt11Multiplier.valueOf",
            "Bolt11Field.valueOf",
        )

        /** `Payee` as it appears inside a `typeName`, bare or parameterised. */
        val PAYEE_TYPE: String = Payee::class.java.name

        /** A member whose name says it answers "was this payment made" (§9.1, §9.2). */
        val EVIDENCE_ANSWER = Regex("""(?i)^(verify|settle|evidence|isPaid|isSettled)""")

        /** `Any`'s three, which every class declares and none of which answers anything. */
        val OBJECT_METHODS: Set<String> = setOf("toString", "equals", "hashCode")

        fun mainClasses(): List<Class<*>> {
            val loader = SettlementStructureTest::class.java.classLoader
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
            // The class files this package compiled to when it was written, pinned as a floor so a
            // partial output directory goes red instead of sweeping less than it claims. A floor is
            // only ever raised.
            val pinned = 42
            assertTrue(
                files.size >= pinned,
                "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were pinned",
            )
            return files.sortedBy { it.name }
                .map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
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
            val member = if (executable is Constructor<*>) "<init>" else executable.name
            return "$simple.$member"
        }

        /**
         * The **generic** type names [executable] mentions — parameters and return alike.
         *
         * `typeName` prints the parameterised form, `java.util.List<dev.eryalabs.nenya.payment.Payee>`,
         * so a `contains` over it sees what an erased-type check cannot.
         */
        fun mentions(executable: Executable): List<String> =
            (
                executable.genericParameterTypes.toList() +
                    listOfNotNull((executable as? Method)?.genericReturnType)
                ).map { it.typeName }

        /**
         * The first mention of a **collection** of payees in [executable]'s generic signature, or
         * `null`.
         *
         * A bare `Payee` is exactly what §8.6 requires — one payee per request, one per key — so the
         * rule is about a type that names more than one at a time: anything that mentions `Payee`
         * and is not the bare type itself.
         */
        fun payeeCollectionIn(executable: Executable): String? =
            mentions(executable).firstOrNull { it != PAYEE_TYPE && PAYEE_TYPE in it }
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

    // -----------------------------------------------------------------------------------------
    // §8.6: two invoices, never one.
    // -----------------------------------------------------------------------------------------

    /**
     * §8.6 made structural: no published member of this package names more than one payee at a
     * time, so a single invoice covering `total_msat` has no shape to arrive in.
     */
    @Test
    fun `no published member takes or returns a collection of payees`() {
        val found = publishedClasses().map { it.name.removePrefix("$PACKAGE.") }.toSet()
        assertTrue(
            EXPECTED_BY_NAME.any { it in found },
            "the sweep must be looking at $EXPECTED_BY_NAME, not at whatever classpath entry it " +
                "landed on; it found $found",
        )

        var inspected = 0
        var namedOne = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                if (mentions(executable).any { PAYEE_TYPE in it }) namedOne++
                assertNull(
                    payeeCollectionIn(executable),
                    "${label(type, executable)} mentions ${payeeCollectionIn(executable)}. §8.6: " +
                        "the buyer pays the provider and the fee recipient with two separate " +
                        "payments to two separate invoices, and an implementation MUST NOT " +
                        "construct, accept or honour a single invoice covering total_msat that some " +
                        "party then splits",
                )
            }
        }

        assertTrue(inspected > 30, "the sweep inspected only $inspected members, which is not the package")
        assertTrue(
            namedOne > 0,
            "the sweep must find members that name a single Payee, or the rule above is a fact " +
                "about a package that mentions no payee at all",
        )
    }

    /**
     * The control that proves the sweep above is not erasure-blind.
     *
     * Without it, "no published member takes a collection of payees" is satisfied by a predicate
     * that never matches anything — which is precisely what an erased-type check would be, because
     * `List<Payee>` erases to `List`.
     */
    @Test
    fun `the payee sweep catches a Payee hidden inside a generic type`() {
        val probe = PayeeCollectionProbe::class.java
        for (name in listOf("both", "keyed", "parameter", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                payeeCollectionIn(method),
                "PayeeCollectionProbe.$name names more than one payee and the sweep missed it — " +
                    "${method.genericReturnType.typeName} erases past an erased-type check",
            )
        }
        val single = probe.methods.single { it.name == "one" }
        assertNull(payeeCollectionIn(single), "a bare Payee is exactly what §8.6 requires")
    }

    /** A probe, not a fixture: four shapes §8.6 forbids, and the one it requires. */
    @Suppress("unused")
    private class PayeeCollectionProbe {
        fun both(): List<Payee> = emptyList()
        fun keyed(): Map<Payee, String> = emptyMap()
        fun parameter(payees: Set<Payee>): Int = payees.size
        fun nested(): List<List<Payee>> = emptyList()
        fun one(): Payee = Payee.PROVIDER
    }

    // -----------------------------------------------------------------------------------------
    // §9.1 and STOP RULE 12: the evidence surface.
    // -----------------------------------------------------------------------------------------

    /** T3's shape, and the two near-misses it names, asserted on this package's evidence type. */
    @Test
    fun `Settlement has no constructor for a client to reach`() {
        val type = mainClasses().single { it.name == "$PACKAGE.Settlement" }

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
            "the JVM PermittedSubclasses attribute is what stops another module implementing this " +
                "interface; it is emitted only from JDK 17 upward, so a toolchain drop would " +
                "silently remove it while every other assertion here stayed green",
        )
    }

    /** The same, for the record §17 item 6 requires be persisted. */
    @Test
    fun `AcceptedPaymentRequest has no constructor for a client to reach`() {
        val type = mainClasses().single { it.name == "$PACKAGE.AcceptedPaymentRequest" }

        assertTrue(type.isInterface)
        assertEquals(0, type.constructors.size)
        assertTrue(type.declaredConstructors.none { it.isSynthetic })
        assertTrue(type.isSealed)
    }

    /**
     * The assertion that discriminates the shape T3 forbids: the ones above are trivially true of
     * any interface at all and would pass over a public implementation.
     *
     * Two kinds of subtype exist for [Settlement] and each gets the strongest statement available to
     * it: an implementing **class** must be non-public, so no client can construct one; an
     * implementing **interface** — [Settlement.Evidenced] and [Settlement.Unverified], which a
     * caller has to be able to name — must itself be `sealed`, so no client can implement one
     * either.
     */
    @Test
    fun `every implementation of the evidence types is unreachable from a client`() {
        val classes = mainClasses()
        for (name in listOf("Settlement", "AcceptedPaymentRequest")) {
            val type = classes.single { it.name == "$PACKAGE.$name" }
            val implementations = classes.filter { it != type && type.isAssignableFrom(it) }

            assertTrue(
                implementations.isNotEmpty(),
                "the sweep must actually find an implementation of $name; the assertions above are " +
                    "true of any interface at all",
            )
            assertTrue(
                implementations.any { !it.isInterface },
                "the sweep must find a concrete implementation of $name, or the class rule below " +
                    "checks nothing",
            )
            for (implementation in implementations) {
                if (implementation.isInterface) {
                    assertTrue(
                        implementation.isSealed,
                        "${implementation.name} refines $name and is not sealed, so another module " +
                            "can implement it and mint evidence nobody checked",
                    )
                } else {
                    assertFalse(
                        Modifier.isPublic(implementation.modifiers),
                        "${implementation.name} implements $name and is JVM-public, so a client can " +
                            "construct evidence of a payment that was never made",
                    )
                }
            }
        }
    }

    /** §9.1's shape: a verifier that can be told the answer is not a verifier. */
    @Test
    fun `no published constructor or method accepts a Boolean`() {
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                for (mentioned in executable.genericParameterTypes.map { it.typeName }) {
                    assertFalse(
                        mentioned == "boolean" || mentioned == "java.lang.Boolean",
                        "${label(type, executable)} takes a Boolean. §9.1: a wallet's isPaid is not " +
                            "evidence, and a function that can be told a payment was made is not " +
                            "performing §9.2's checks.",
                    )
                }
            }
        }
        assertTrue(inspected > 30, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * §9.2's answer is the sealed type, and never a `Boolean` or a status-shaped `String`.
     *
     * The sweep is by *name*, over every published member whose name says it answers "was this
     * payment made", so a second one added later is caught rather than assumed absent — and the set
     * is pinned exactly, because a regex that matched nothing would pass over a codec that returned
     * `boolean` from every one of them.
     */
    @Test
    fun `the answer to whether a payment was made is the sealed type`() {
        val answers = mutableListOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (method.name in OBJECT_METHODS) continue
                if (!EVIDENCE_ANSWER.containsMatchIn(method.name)) continue
                answers += label(type, method)
                assertEquals(
                    "$PACKAGE.Settlement",
                    method.genericReturnType.typeName,
                    "${label(type, method)} answers §9.2 and must return the sealed type",
                )
            }
        }

        assertEquals(
            setOf("Settlement\$Companion.verify", "Settlement\$Companion.verifyFeeReceipt"),
            answers.toSet(),
            "the set of members answering §9.2 must match the pinned list exactly; a new one may be " +
                "the same question asked in a looser shape, and a missing one means the sweep " +
                "stopped seeing the package",
        )
        // And nothing on the answer type itself hands a caller a bare verdict back out.
        val cases = publishedClasses().filter { it.name.startsWith("$PACKAGE.Settlement\$") }
        assertTrue(cases.size > 1, "the sweep must find the sealed type's cases; found $cases")
        for (type in cases) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (method.name in OBJECT_METHODS) continue
                val returned = method.genericReturnType.typeName
                assertFalse(
                    returned == "boolean" || returned == "java.lang.Boolean",
                    "${label(type, method)} returns $returned; §9.2's outcome is the type itself",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // §8.4: the fee term matched, and which point it did not.
    // -----------------------------------------------------------------------------------------

    /**
     * §8.4's answer is a named outcome carrying the point that diverged, and never a `Boolean`.
     *
     * The sweep is over **every** published member's return type rather than over a name pattern,
     * with the one permitted `boolean` pinned: a rule scoped to members whose name says "fee"
     * would be satisfied by an implementation that called its comparator `matches`, and a rule
     * scoped to members whose name says "match" would be satisfied by one that called it `agrees`.
     * A caller told `false` cannot say whether the counterparty re-quoted the fee or substituted
     * itself for the fee recipient, and §8.4 requires the divergence be surfaced to the user as a
     * terms mismatch rather than as a transient error — which is a sentence a `Boolean` cannot
     * carry.
     */
    @Test
    fun `no published member answers a §8_4 question with a bare Boolean`() {
        val booleans = mutableSetOf<String>()
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (method.name in OBJECT_METHODS) continue
                inspected++
                val returned = method.genericReturnType.typeName
                if (returned == "boolean" || returned == "java.lang.Boolean") {
                    booleans += label(type, method)
                }
            }
        }

        assertTrue(inspected > 30, "the sweep inspected only $inspected members, which is not the package")
        assertEquals(
            BOOLEAN_RETURNS_PERMITTED,
            booleans,
            "the set of published members returning a bare Boolean must match the pinned list " +
                "exactly. §8.4 requires a fee-term divergence be surfaced as a terms mismatch " +
                "naming the point; §9.1 says a wallet's isPaid is not evidence. Neither answer " +
                "may arrive here as a flag.",
        )
    }

    /**
     * And the answer that **is** §8.4's is the sealed type, from every member that gives one.
     *
     * The control that proves the sweep above is not vacuous: without it, "no member returns a
     * Boolean" is satisfied by a package that answers §8.4 nowhere at all.
     */
    @Test
    fun `the answer to whether the fee term matched is the sealed type`() {
        val answers = mutableSetOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                val returned = method.genericReturnType.typeName
                if (returned.startsWith("$PACKAGE.FeeTermAgreement")) answers += label(type, method)
            }
        }

        assertEquals(
            FEE_TERM_ANSWERS,
            answers,
            "the set of members answering §8.4 must match the pinned list exactly; a missing one " +
                "means the sweep stopped seeing the package and the Boolean rule above is a fact " +
                "about a package that answers nothing",
        )
        val agreement = mainClasses().single { it.name == "$PACKAGE.FeeTermAgreement" }
        assertTrue(agreement.isInterface, "an interface has no constructor to synthesise an accessor for")
        assertEquals(0, agreement.constructors.size)
        assertTrue(
            agreement.isSealed,
            "PermittedSubclasses is what stops another module minting an Agreed for terms nobody " +
                "compared",
        )
        val cases = mainClasses().filter { it != agreement && agreement.isAssignableFrom(it) }
        assertTrue(cases.isNotEmpty(), "the sweep must find the sealed type's cases")
        assertTrue(cases.any { !it.isInterface }, "and a concrete one, or the class rule below checks nothing")
        for (case in cases) {
            if (case.isInterface) {
                assertTrue(case.isSealed, "${case.name} refines FeeTermAgreement and is not sealed")
            } else {
                assertFalse(
                    Modifier.isPublic(case.modifiers),
                    "${case.name} implements FeeTermAgreement and is JVM-public, so a client can " +
                        "assert that a fee term agreed when nothing compared it",
                )
            }
        }
    }

    /**
     * §9.2's operands, pinned: the verifier takes a receipt, the hash it is checked against, the
     * store it is compared with and the order's own §8.3 split — and nothing a counterparty could
     * say.
     *
     * The `FeeSplit` is check 4's expected amount and is on this list for the same reason the store
     * is: it is something **this implementation** computed from terms it accepted, not a figure a
     * message carried. `FeeSplit`'s own constructor is `internal`, so there is no route by which a
     * counterparty's number reaches one without passing through §8.3's arithmetic first.
     */
    @Test
    fun `the verifier takes a receipt, a payment hash, the store and the split, and nothing that can assert`() {
        val companion = mainClasses().single { it.name == "$PACKAGE.Settlement\$Companion" }
        val verify = companion.methods.single { it.name == "verify" && '$' !in it.name }

        assertEquals(
            listOf(
                PaymentReceipt::class.java,
                PaymentHash::class.java,
                PaymentRequestStore::class.java,
                FeeSplit::class.java,
            ),
            verify.parameterTypes.toList(),
            "anything else on this parameter list is something a counterparty could say",
        )
        assertEquals("$PACKAGE.Settlement", verify.genericReturnType.typeName)
    }

    /**
     * The same, for §9.2 check 6's path: the three operands above, plus the two the check needs —
     * this implementation's own order (§8.5) and §8.4's ordered sequence of points.
     *
     * Pinned as an **order** type and not as a status token, which is the whole of §8.5's third of
     * check 6: `Order` is producible only by `OrderMachine`, so the state this reads is one this
     * library's own transition function reached. A `String` or an `OrderState` here would be a
     * caller's assertion about where the order is, which is §9.1's shape one rule over.
     */
    @Test
    fun `the fee-receipt verifier takes the order itself and §8_4's points, and nothing that can assert`() {
        val companion = mainClasses().single { it.name == "$PACKAGE.Settlement\$Companion" }
        val verify = companion.methods.single { it.name == "verifyFeeReceipt" }

        assertEquals(
            listOf(
                PaymentReceipt::class.java,
                PaymentHash::class.java,
                PaymentRequestStore::class.java,
                Order::class.java,
                List::class.java,
            ),
            verify.parameterTypes.toList(),
            "anything else on this parameter list is something a counterparty could say",
        )
        assertEquals(
            "java.util.List<${PACKAGE}.FeeTermSighting>",
            verify.genericParameterTypes.last().typeName,
            "§8.4 compares a **sequence** of points; a pair would pass every single-comparison " +
                "test and miss a divergence at the acceptance",
        )
        assertEquals("$PACKAGE.Settlement", verify.genericReturnType.typeName)
    }

    /**
     * Every place on this package's published surface where a `String` may be a parameter, pinned
     * by name.
     *
     * A new one turns this red and forces a human to say why it is not a status string arriving as
     * evidence that a payment was made (§9.1, STOP RULE 12). Each entry below is either the BOLT-11
     * reference — which is an **opaque** string this library compares and never believes — a
     * diagnostic message, a §5.3 tag name, or a compiler-generated enum lookup.
     */
    @Test
    fun `every published String parameter is on the pinned list`() {
        val found = mutableSetOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                if (executable.genericParameterTypes.any { it.typeName == String::class.java.name }) {
                    found += label(type, executable)
                }
            }
        }

        assertEquals(
            STRING_PARAMETERS_PERMITTED,
            found,
            "the set of published members taking a String must match the pinned list exactly. A new " +
                "entry may be a counterparty's claim arriving as evidence (§9.1); a missing entry " +
                "means the sweep stopped seeing the package.",
        )
    }
}
