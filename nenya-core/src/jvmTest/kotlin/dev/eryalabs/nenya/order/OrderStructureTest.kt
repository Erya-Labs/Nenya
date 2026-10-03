package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.OrderId
import java.io.File
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
 * The `java.lang.reflect` class sweep for `dev/eryalabs/nenya/order`.
 *
 * `kotlin-reflect` is not on any classpath and STOP RULE 11 forbids adding one. The two failure
 * modes differ and both are traps: `KClass.members` and `KClass.constructors` compile — with a
 * warning — and then throw `KotlinReflectionNotSupportedError` at run time, while
 * `kotlin.reflect.full.*` does not compile at all. So: `getConstructors()`, `getMethods()`,
 * `getParameterTypes()`, `Modifier`, and nothing else.
 *
 * ### Why `getResources`, plural
 *
 * `ClassLoader.getResource("dev/eryalabs/nenya/order")` resolves to the **test** output directory,
 * because `build/classes/kotlin/jvm/test` precedes `build/classes/kotlin/jvm/main` on the test runtime
 * classpath and this repo mirrors package names into its test tree. A sweep built on the singular
 * form would enumerate this very file's class, never see [OrderMachine], and pass with the
 * implementation entirely absent. [mainClasses] therefore takes the plural form and selects the
 * `/classes/kotlin/jvm/main/` URL, failing loudly if there is none.
 */
internal object OrderStructure {

    const val PACKAGE: String = "dev.eryalabs.nenya.order"

    fun mainClasses(): List<Class<*>> {
        val loader = OrderStructure::class.java.classLoader
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
        val pinned = 37
        assertTrue(
            files.size >= pinned,
            "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were pinned",
        )
        return files.map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
    }
}

/**
 * The structural half of §9.1, §11.3 and STOP RULE 12, over the package that actually advances
 * orders.
 *
 * T3's sweep covers `payment`, T4's `delivery` and T5's `seam`. Without this one the round's
 * headline rule — no `Boolean` and no status-shaped `String` as evidence — would reach every
 * package except the one where an order becomes `paid`, which is the only place it finally
 * matters. A code review can assert that today's code obeys it; this file asserts it about
 * whatever is in the package tomorrow, which is when somebody adds
 * `ReceiptsVerified(..., walletSaysPaid: Boolean)` in a hurry.
 *
 * ### What "the published surface" means here
 *
 * A JVM-public class, and on it: its public constructors, plus its public methods declared on the
 * class itself whose names carry no `$`. The `$` filter removes Kotlin's synthetic accessors and
 * its name-mangled `internal` members, which are public in the bytecode but are not something a
 * client can call by writing the name down. Inherited `java.lang.Object` and `java.lang.Enum`
 * methods are excluded by the declaring-class filter.
 *
 * ### The generic form, never the erased one
 *
 * `Method.parameterTypes` and `Method.returnType` report the type **after erasure**, so
 * `fun x(flags: List<Boolean>)` satisfies "no published member accepts a Boolean" and
 * `fun x(states: List<OrderState>)` satisfies "neither door accepts a state", while handing a
 * caller exactly the thing the rule forbids with a collection wrapped round it. Every type
 * inspection below therefore reads `genericParameterTypes` / `genericReturnType` and matches on
 * `typeName`, which prints the parameterised form `java.util.List<java.lang.Boolean>`.
 *
 * Each **predicate** sweep is a named function in the companion below and carries a probe class at
 * the foot of this file that proves it: the probe is fed to the sweep's **own** predicate, because
 * a control exercising a second copy of the check proves nothing about the first — which is the
 * failure mode the conversion exists to remove. Each **pin** — a whole-list equality over a
 * parameter list — carries no probe and none is missing: an exact equality cannot be erasure-blind
 * in the dangerous direction, because a smuggled `List<PaymentHash>` parameter erases to `List` and
 * fails the pin outright.
 */
class OrderStructureTest {

    private companion object {

        val PACKAGE: String = OrderStructure.PACKAGE

        /** The binary name `Type.getTypeName()` renders for §9.2's evidence, nested class and all. */
        const val EVIDENCED: String = "dev.eryalabs.nenya.settlement.Settlement\$Evidenced"

        /** The type that must appear nowhere in this package's generic signatures. */
        const val VERIFIED_PAYMENT: String = "dev.eryalabs.nenya.payment.VerifiedPayment"

        /** T24's stored `type=2` record — what `committed → awaiting_payment` now consumes. */
        const val ACCEPTED_REQUEST: String = "dev.eryalabs.nenya.settlement.AcceptedPaymentRequest"

        /** §8.6's payee role, which must appear in **no** parameter of this package as a set. */
        const val PAYEE: String = "dev.eryalabs.nenya.payment.Payee"

        /**
         * The classes this sweep is *about*. Asserted by name rather than by count: a count is
         * satisfied by whatever the wrong classpath entry happened to contain, which is exactly
         * the failure the plural `getResources` exists to avoid — and three `.kt` files were
         * already in this package before T7 wrote a line.
         */
        val EXPECTED_BY_NAME: Set<String> = setOf(
            "Order",
            "OrderMachine",
            "OrderOutcome",
            "OrderEvent",
            "OrderTerms",
            "OrderState",
            "OrderStatusCodec",
            "TransitionRejection",
            "DisputeGround",
            "DeliveryFailure",
            "Party",
        )

        /**
         * Every place on the published surface where a `String` may be a parameter, pinned by
         * name. A new one turns this red and forces a human to say why it is not a status string
         * arriving as a decision (§11.1, §11.3 invariant 5).
         */
        val STRING_PARAMETERS_PERMITTED: Set<String> = setOf(
            "OrderStatusCodec.read",           // §11.1's own codec — returns UNKNOWN, decides nothing
            "OrderStateException.<init>",      // the human-readable rejection message
            "OrderState.valueOf",              // generated by the Kotlin compiler for enums
            "OrderStateRejection.valueOf",     // ditto
            "TransitionRejection.valueOf",     // ditto
            "DisputeGround.valueOf",           // ditto
            "DeliveryFailure.valueOf",         // ditto
            "Party.valueOf",                   // ditto
        )

        fun publishedClasses(): List<Class<*>> =
            OrderStructure.mainClasses().filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

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
         * The generic type names on [executable]'s parameter list — `java.util.List<java.lang.Boolean>`
         * where `parameterTypes` would have said `java.util.List`.
         */
        fun parameterTypeNames(executable: Executable): List<String> =
            executable.genericParameterTypes.map { it.typeName }

        /** The generic type name [executable] returns, or `null` when it is a constructor. */
        fun returnTypeName(executable: Executable): String? =
            (executable as? Method)?.genericReturnType?.typeName

        /**
         * Every generic type name [executable] mentions — parameters and return alike — for the
         * rules that are about what this package can *hand out* as well as what it can be told.
         */
        fun mentions(executable: Executable): List<String> =
            parameterTypeNames(executable) + listOfNotNull(returnTypeName(executable))

        /**
         * Whether [typeName] names [sought] itself or names it inside a generic's type arguments.
         *
         * A bare `contains` is wrong: `java.lang.StringBuilder` contains `java.lang.String`, and
         * `OrderStateRejection` contains `OrderState`. So the match is on a whole type token — the
         * character either side must not continue an identifier. A `Regex` would say the same thing
         * and the round's stop rule forbids one here.
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

        /**
         * The first parameter of [executable] that mentions a Boolean anywhere in its generic type,
         * or `null` if none does. The sweep and its probe both call this, and nothing re-implements
         * it.
         *
         * **Both spellings.** A bare Kotlin `Boolean` parameter erases to the JVM primitive, whose
         * `typeName` is `boolean`; inside a generic it is boxed and reads `java.lang.Boolean`. A
         * predicate checking only one of the two would be a fresh instance of the same defect.
         *
         * Parameters only, which is the rule as §9.1 states it: `equals(Any?): Boolean` returns one
         * and is not a door a wallet's `isPaid` can arrive through.
         */
        fun booleanParameterIn(executable: Executable): String? =
            parameterTypeNames(executable).firstOrNull {
                namesType(it, "boolean") || namesType(it, "java.lang.Boolean")
            }

        /**
         * The first parameter of [executable] that mentions a `String` anywhere in its generic type,
         * or `null` if none does. Shared by the pinned-list sweep and its probe.
         */
        fun stringParameterIn(executable: Executable): String? =
            parameterTypeNames(executable).firstOrNull { namesType(it, "java.lang.String") }

        /**
         * The first generic type name on [executable] — parameter or return — naming
         * [VERIFIED_PAYMENT], or `null` if none does.
         *
         * Deliberately a plain `contains` rather than [namesType], and this is the one place the
         * difference matters in the permissive direction: a `VerifiedPaymentClaim` on this surface
         * would be the same door under a longer name, the check this replaces caught it, and a
         * converted sweep may not forbid less than the one it replaces.
         */
        fun verifiedPaymentIn(executable: Executable): String? =
            mentions(executable).firstOrNull { VERIFIED_PAYMENT in it }

        /**
         * The first parameter of [executable] that is a **set** of §8.6 payee roles, or `null`.
         *
         * Narrower than the rest on purpose: §11.2's `committed → awaiting_payment` is allowed a
         * [Payee] — one checked role — and is not allowed the set of them that stood in for the
         * stored `type=2` records. `contains` rather than [namesType] for [verifiedPaymentIn]'s
         * reason.
         */
        fun payeeSetIn(executable: Executable): String? =
            parameterTypeNames(executable).firstOrNull {
                it.startsWith("java.util.Set<") && PAYEE in it
            }

        /**
         * The first parameter of [executable] that names an [OrderState] anywhere in its generic
         * type, or `null` if none does.
         *
         * [namesType] rather than `contains` here because this package really does hold
         * `OrderStateException` and `OrderStateRejection`, neither of which is a state a caller can
         * hand in, and both of which a substring match would flag.
         */
        fun orderStateParameterIn(executable: Executable): String? =
            parameterTypeNames(executable).firstOrNull {
                namesType(it, OrderState::class.java.typeName)
            }

        /**
         * The first parameter of [executable] shaped like a time, or `null` if none is.
         *
         * A time in this library is a `Long` of unix seconds, in both spellings for
         * [booleanParameterIn]'s reason: bare it erases to the primitive `long`, and inside a
         * generic it is boxed to `java.lang.Long`.
         */
        fun timeParameterIn(executable: Executable): String? =
            parameterTypeNames(executable).firstOrNull {
                namesType(it, "long") || namesType(it, "java.lang.Long")
            }
    }

    @Test
    fun `the sweep finds the classes it is about, by name`() {
        val found = OrderStructure.mainClasses().map { it.name.removePrefix("$PACKAGE.") }.toSet()

        for (expected in EXPECTED_BY_NAME) {
            assertTrue(
                expected in found,
                "$expected was not among the main classes of $PACKAGE; found $found",
            )
        }
    }

    /**
     * The headline of the mutation T7 names: `awaiting_payment → paid` must not be able to accept
     * a `Boolean`. §9.1 — a wallet's `isPaid` is not evidence, and a function that can be told the
     * answer is not a verifier.
     *
     * Read through [booleanParameterIn] over the **generic** parameter types, because the erased
     * read this replaced was satisfied by `fun x(flags: List<Boolean>)`: the wallet's answer with a
     * collection wrapped round it is still the wallet's answer.
     */
    @Test
    fun `no published constructor or method accepts a Boolean`() {
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                val offender = booleanParameterIn(executable)
                assertNull(
                    offender,
                    "${label(type, executable)} takes a Boolean, as `$offender`. §9.1 and §11.3 " +
                        "invariant 5: a wallet's isPaid and a counterparty's status update are not " +
                        "evidence, and a state machine that can be told the answer is not one — " +
                        "nor does wrapping the answer in a collection make it evidence",
                )
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

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
            "the set of published members taking a String must match the pinned list exactly — and " +
                "a `List<String>` or a `Map<String, ...>` is taking a String. A new entry may be a " +
                "status-shaped string arriving as a decision; a missing entry means the sweep " +
                "stopped seeing the package.",
        )
    }

    /**
     * [Order] and [OrderOutcome] have no constructor a client can reach, so an embedding client
     * cannot assert an order into `paid` or fabricate an advance.
     */
    @Test
    fun `Order and OrderOutcome have no constructor for a client to reach`() {
        for (name in listOf("Order", "OrderOutcome")) {
            val type = OrderStructure.mainClasses().single { it.name == "$PACKAGE.$name" }

            assertTrue(type.isInterface, "$name must be an interface: an interface has no " +
                "constructor to synthesise an accessor for")
            assertEquals(
                0,
                type.constructors.size,
                "a private constructor plus a companion factory emits a *public synthetic* " +
                    "constructor with a trailing DefaultConstructorMarker, which a Java client can " +
                    "call with null",
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
    }

    /**
     * The assertion that discriminates the shape: an interface with a **public** implementation
     * would pass both assertions above and be forgeable anyway.
     */
    @Test
    fun `every implementing class of Order and OrderOutcome is non-public`() {
        val classes = OrderStructure.mainClasses()
        for (name in listOf("Order", "OrderOutcome")) {
            val type = classes.single { it.name == "$PACKAGE.$name" }
            val implementations =
                classes.filter { it != type && !it.isInterface && type.isAssignableFrom(it) }

            assertTrue(
                implementations.isNotEmpty(),
                "the sweep must actually find an implementation of $name; the constructor " +
                    "assertions are true of any interface at all and would pass over a public one",
            )
            for (implementation in implementations) {
                assertFalse(
                    Modifier.isPublic(implementation.modifiers),
                    "${implementation.name} implements $name and is JVM-public, so a client can " +
                        "construct an order that is already `paid`, or an advance that never " +
                        "happened",
                )
            }
        }
    }

    /**
     * §11.2's `awaiting_payment → paid` consumes verified evidence and nothing else, asserted on
     * the parameter list rather than on the body: the event carries a set of settlement results
     * and no second parameter a counterparty could fill in.
     *
     * Three exact pins — two parameter lists and a return type — so no probe stands beside them and
     * none is missing; see the class KDoc. They are read in the generic form anyway, which is what
     * turns the last from a statement about `java.util.Set` (true of a set of anything,
     * `Set<Boolean>` included) into a statement about a set of §9.2 evidence. The expected names
     * are built with `typeName` rather
     * than `name` because `ByteArray::class.java.name` is `"[B"` while its `typeName` is
     * `"byte[]"`, and it is the latter that `genericParameterTypes` reports.
     *
     * The `? extends` in the pinned element type is not noise and must not be tidied away: Kotlin
     * declares `Set<out E>`, so a `Set<Settlement.Evidenced>` **parameter** compiles to a
     * wildcard — `Evidenced` is a sealed interface rather than a final class, and Kotlin omits the
     * wildcard only for final ones. Writing the bare element type here would red-line a green tree.
     */
    @Test
    fun `the transition function takes an order and an event, and nothing that can assert`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }
        val on = machine.methods.single { it.name == "on" }

        assertEquals(
            listOf(Order::class.java.typeName, OrderEvent::class.java.typeName),
            on.genericParameterTypes.map { it.typeName },
            "anything else on this parameter list is something a counterparty or a wallet could " +
                "say (§9.1, §11.3 invariant 5)",
        )
        assertEquals(OrderOutcome::class.java.typeName, on.genericReturnType.typeName)

        val receipts = OrderStructure.mainClasses()
            .single { it.name == "$PACKAGE.OrderEvent\$ReceiptsVerified" }
        assertEquals(
            listOf("java.util.Set<? extends $EVIDENCED>"),
            receipts.constructors.single().genericParameterTypes.map { it.typeName },
            "the only input to `awaiting_payment → paid` is the set of receipts this library " +
                "verified for itself (§9.2, STOP RULE 12)",
        )
    }

    /**
     * The element type of that set, read off the **generic** signature — which is where the two
     * probes this task closes were expressible.
     *
     * `parameterTypes` erases `Set<VerifiedPayment>` and `Set<Settlement.Evidenced>` to the same
     * `java.util.Set`, so the assertion above is true of both and says nothing about either. T13's
     * rule is therefore applied here: walk `genericParameterTypes` and match on `typeName`.
     *
     * What the element type buys, stated as the two shapes it makes unrepresentable. A bare
     * `VerifiedPayment` needs a preimage and a payment hash the caller chose and knows nothing of
     * *which* invoice or *whose*; so a receipt for an invoice the buyer issued to itself reached
     * `paid`, and so did one whose payee label the caller swapped on the way in. A
     * `Settlement.Evidenced` carries the order the `kind:17` named, the payee its own
     * `["payee", …]` tag carried and the invoice the store held. Neither probe can be written any
     * more, which is why the second half of this test sweeps the **whole** package for
     * `VerifiedPayment` in any generic position rather than only this one constructor: a second
     * door taking one would restore both.
     *
     * That second half runs through [verifiedPaymentIn] so that the probe below can be fed the same
     * predicate rather than a second copy of it — a control against a copy says nothing about the
     * sweep. The element-type assertions above it are a pin and need no probe.
     */
    @Test
    fun `the receipts event takes settlement evidence, and nothing here names a bare VerifiedPayment`() {
        val receipts = OrderStructure.mainClasses()
            .single { it.name == "$PACKAGE.OrderEvent\$ReceiptsVerified" }
        val parameters = receipts.constructors.single().genericParameterTypes.map { it.typeName }

        assertEquals(1, parameters.size, "one parameter: the receipts, and nothing beside them")
        val element = parameters.single()
        assertTrue(
            element.startsWith("java.util.Set<"),
            "the receipts must arrive as a Set — §8.6 is one invoice per payee: $element",
        )
        assertTrue(
            EVIDENCED in element,
            "the element type must be $EVIDENCED, which cannot exist without §9.2 check 1 having " +
                "compared the receipt's BOLT-11 string against the stored `type=2` for the same " +
                "order and payee. It was $element",
        )

        var swept = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                swept++
                val named = verifiedPaymentIn(executable)
                assertNull(
                    named,
                    "${label(type, executable)} names $VERIFIED_PAYMENT in a generic " +
                        "position ($named). A door into this package taking one re-opens both " +
                        "probes: evidence with no invoice behind it, and a payee a caller " +
                        "chose rather than the receipt's own tag",
                )
            }
        }
        assertTrue(swept > 20, "the sweep inspected only $swept members, which is not the package")
    }

    /**
     * Gap G3's remaining half, made structural: neither of the two transitions this task closes has
     * a parameter a caller can assert into.
     *
     * Two assertions, and each is about a different way of asserting.
     *
     * - **`committed → awaiting_payment` takes no `Set<Payee>`, anywhere in the package.** That was
     *   three enum constants a caller wrote down to mean "I accepted a request from each", and a
     *   caller that had run none of §8.4, §8.6, §8.7 or §9.2 checks 4 and 5 produced exactly the
     *   same value as one that had run them all. The sweep is over the **generic** signatures for
     *   the reason the `VerifiedPayment` one is: `Set<Payee>` and `Set<AcceptedPaymentRequest>`
     *   erase to the same `java.util.Set`, so `parameterTypes` cannot tell them apart, and it
     *   covers the whole package rather than one constructor because a second door taking one
     *   would restore the shape. The element type that *is* there is checked too — an
     *   `AcceptedPaymentRequest` exists only where T24 accepted and stored a real `type=2`.
     * - **`proposed → accepted` takes a checked `Acceptance.Accepted` and nothing else.** No
     *   `Party`, no `OrderTerms`, no `OrderState`, no `String`: every operand §7.6 names was
     *   compared by `OrderProposal.accepts` over a seal and raw tags, and a parameter here for any
     *   of them would be a caller's chance to disagree with that comparison after the fact. The
     *   `createdAt` a `Rumor` carries is the only other parameter, and nothing reads it (§4.6).
     *
     * The first half runs through [payeeSetIn] so the probe below exercises the sweep's own
     * predicate; the second is a whole-list pin, read in the generic form and needing no probe.
     */
    @Test
    fun `the two codec-fed transitions take checked values and nothing assertable`() {
        var swept = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                swept++
                val named = payeeSetIn(executable)
                assertNull(
                    named,
                    "${label(type, executable)} takes a $named. §11.2's " +
                        "`committed → awaiting_payment` consumes the `type=2` records T24 " +
                        "accepted and stored, not payee labels a caller chose: a set of roles " +
                        "is the same value whether or not §8.4, §8.6, §8.7 and §9.2 checks 4 " +
                        "and 5 were ever run",
                )
            }
        }
        assertTrue(swept > 20, "the sweep inspected only $swept members, which is not the package")

        val requests = OrderStructure.mainClasses()
            .single { it.name == "$PACKAGE.OrderEvent\$PaymentRequestsReceived" }
        val element = requests.constructors.single { !it.isSynthetic }
            .genericParameterTypes.first().typeName
        assertTrue(
            element.startsWith("java.util.Set<") && ACCEPTED_REQUEST in element,
            "the payment requests must arrive as a Set of $ACCEPTED_REQUEST, which only " +
                "`AcceptedPaymentRequest.accept` mints. It was $element",
        )

        val acceptance = OrderStructure.mainClasses()
            .single { it.name == "$PACKAGE.OrderEvent\$AcceptanceReceived" }
        assertEquals(
            listOf(Acceptance.Accepted::class.java.typeName, java.lang.Long::class.java.typeName),
            acceptance.constructors.single { !it.isSynthetic }
                .genericParameterTypes.map { it.typeName },
            "§7.6's checked answer and a rumor's `created_at`, and nothing else: a Party, an " +
                "OrderTerms or a status-shaped String here is a caller overriding the comparison " +
                "`OrderProposal.accepts` made over the seal and the raw tags",
        )
    }

    /**
     * The one door that produces an order carries no state, no token and no flag — it produces
     * `proposed` and nothing else (§11.2's genesis row), and the sink door produces `unknown` and
     * nothing else (§11.1).
     *
     * Two whole-list pins, which carry no probe for the reason the class KDoc gives, and one
     * predicate sweep — [orderStateParameterIn], which does. The sweep is the half that needed
     * converting: `OrderState::class.java in method.parameterTypes` is an erased read, and a
     * `fun x(states: List<OrderState>)` on this machine is a state a caller handed in just as
     * surely as a bare one, while satisfying the membership test perfectly.
     */
    @Test
    fun `neither door into an order accepts a state`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }

        assertEquals(
            listOf(OrderEvent.Proposal::class.java.typeName),
            machine.methods.single { it.name == "open" }.genericParameterTypes.map { it.typeName },
        )
        assertEquals(
            // The order id joins the sink door for the reason it joined the genesis one: an order
            // that has forgotten which thread it belongs to cannot be told apart from another at
            // the same price. It is an `OrderId` and never a `String`, so §4.3's length and hex
            // rules were applied before it got here.
            listOf(OrderId::class.java.typeName, OrderTerms::class.java.typeName),
            machine.methods.single { it.name == "unrecognised" }
                .genericParameterTypes.map { it.typeName },
        )
        for (method in machine.methods.filter { it.declaringClass == machine }) {
            val offender = orderStateParameterIn(method)
            assertNull(
                offender,
                "${method.name} takes an OrderState, as `$offender`: a state a caller can hand in " +
                    "is a state a caller can assert, which is §11.3 invariant 5 with an extra step",
            )
        }
    }

    /**
     * §4.6 made structural: no method on [OrderMachine] accepts a time.
     *
     * Every deadline in NENYA-1 is evaluated against the injected clock and never against a
     * counterparty's `created_at` (§7.1 randomises gift-wrap timestamps into the past on purpose).
     * A rule stated that way is a rule to remember; a parameter list with nowhere to put a time
     * is a rule the compiler keeps. A time in this library is a `Long` of unix seconds, so the
     * sweep refuses a `long` or `Long` parameter of any kind — which a method on the machine has
     * no other use for. The clock arrives on the **constructor**, which is the seam, and is
     * deliberately not swept here. (The name predates the switch from `java.time.Instant` to unix
     * seconds, and is kept.)
     *
     * Read through [timeParameterIn] over the generic parameter types. The erased filter this
     * replaced compared each parameter against `long` and `java.lang.Long` and so was satisfied by
     * `fun x(deadlines: List<Long>)` — a counterparty's `created_at` arrives through a list of them
     * exactly as well as through one.
     */
    @Test
    fun `no method on the machine accepts an Instant`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }

        for (method in machine.methods.filter { it.declaringClass == machine && '$' !in it.name }) {
            val timeShaped = timeParameterIn(method)
            assertNull(
                timeShaped,
                "${method.name} takes a Long, as `$timeShaped`, which is how this library carries " +
                    "unix seconds. §4.6: the only time this library may know comes " +
                    "from the injected clock, and a parameter is a door for a counterparty's " +
                    "created_at to arrive through",
            )
        }
    }

    /**
     * The control that proves the Boolean sweep above is not erasure-blind.
     *
     * Without it, "no published member accepts a Boolean" is satisfied by a predicate that never
     * matches a `List<Boolean>` — which is precisely what the erased-type check it replaced was.
     * The probe is fed to [booleanParameterIn], the sweep's own predicate; a control run against a
     * second copy of the check would say nothing about the one the sweep runs.
     */
    @Test
    fun `the Boolean sweep catches a Boolean hidden inside a generic type`() {
        val probe = BooleanCollectionProbe::class.java

        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                booleanParameterIn(method),
                "BooleanCollectionProbe.$name hides a Boolean and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases past an erased-type check",
            )
        }
        assertNotNull(
            booleanParameterIn(probe.methods.single { it.name == "direct" }),
            "the converted sweep must still catch everything the erased one caught, a bare " +
                "`boolean` parameter first of all",
        )
        assertNull(
            booleanParameterIn(probe.methods.single { it.name == "permitted" }),
            "the rule is about Booleans, not about generics; a sweep that rejected every " +
                "parameterised parameter would pass its probe and forbid the wrong thing",
        )
    }

    /**
     * The control that proves the String sweep above is not erasure-blind.
     *
     * The pinned list is only as good as the predicate that populates it: a `List<String>`
     * parameter that erases to `java.util.List` never reaches the list, so a status-shaped string
     * could arrive as a decision inside a collection and the exact-set assertion would stay green.
     * Fed to [stringParameterIn], the sweep's own predicate.
     */
    @Test
    fun `the String sweep catches a String hidden inside a generic type`() {
        val probe = StringCollectionProbe::class.java

        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                stringParameterIn(method),
                "StringCollectionProbe.$name hides a String and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases past an erased-type check",
            )
        }
        assertNotNull(
            stringParameterIn(probe.methods.single { it.name == "direct" }),
            "the converted sweep must still catch a bare String parameter, which is what the " +
                "pinned list was written about",
        )
        assertNull(
            stringParameterIn(probe.methods.single { it.name == "permitted" }),
            "`java.lang.StringBuilder` contains `java.lang.String`; the match is on a whole type " +
                "token, not on a substring, or the pinned list would fill up with false entries",
        )
    }

    /**
     * The control that proves the `VerifiedPayment` sweep is not merely reading the one constructor
     * it can see.
     *
     * The sweep was already written over the generic signatures — that is what the element-type
     * assertion beside it is for — but it had no control, so nothing said whether it would notice a
     * `List<VerifiedPayment>` or a `Map<String, VerifiedPayment>`. Fed to [verifiedPaymentIn],
     * including on a return type: a door **out** of this package handing one back re-opens the same
     * two probes as a door in.
     */
    @Test
    fun `the VerifiedPayment sweep catches one hidden inside a generic type`() {
        val probe = VerifiedPaymentCollectionProbe::class.java

        for (name in listOf("wrapped", "keyed", "returned")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                verifiedPaymentIn(method),
                "VerifiedPaymentCollectionProbe.$name names a VerifiedPayment and the sweep " +
                    "missed it — a collection of bare receipts is a caller-chosen payee and an " +
                    "invoice nobody stored, exactly as one receipt is",
            )
        }
        assertNotNull(
            verifiedPaymentIn(probe.methods.single { it.name == "direct" }),
            "the sweep must still catch the bare parameter the rule was written about",
        )
        assertNull(
            verifiedPaymentIn(probe.methods.single { it.name == "permitted" }),
            "the rule names one type; a sweep that flagged every generic would forbid the " +
                "`Set<Settlement.Evidenced>` this package is built on",
        )
    }

    /**
     * The control for the `Set<Payee>` half of the codec-fed transitions.
     *
     * Its shape differs from the Boolean and String controls on purpose, because the rule does:
     * §11.2 forbids the **set** of roles that stood in for the stored `type=2` records, not the
     * role. So a bare [Payee] is asserted **not** caught, which is where the line is and where
     * moving it would be a decision rather than an accident. Fed to [payeeSetIn].
     */
    @Test
    fun `the payee-set sweep catches a set of roles and leaves a single checked role alone`() {
        val probe = PayeeSetProbe::class.java

        for (name in listOf("wrapped", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                payeeSetIn(method),
                "PayeeSetProbe.$name takes a set of payee roles and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} is the same value whether " +
                    "or not §8.4, §8.6, §8.7 and §9.2 checks 4 and 5 were ever run",
            )
        }
        assertNull(
            payeeSetIn(probe.methods.single { it.name == "bare" }),
            "§8.6's role itself is a checked value and stays legal; the rule is about the set of " +
                "them standing in for the requests T24 accepted",
        )
        assertNull(
            payeeSetIn(probe.methods.single { it.name == "permitted" }),
            "a `Set` of something else is not a set of roles, and a sweep that rejected every set " +
                "would reject the `Set<AcceptedPaymentRequest>` this transition is built on",
        )
    }

    /**
     * The control that proves the state sweep is not erasure-blind.
     *
     * `OrderState::class.java in method.parameterTypes` is a membership test over erased classes,
     * so a `List<OrderState>` parameter passed it untouched while being exactly the thing §11.3
     * invariant 5 forbids: a state a caller handed in. Fed to [orderStateParameterIn], whose
     * `neighbour` case also pins the whole-token match — `OrderStateRejection` contains
     * `OrderState` as a substring and is not one.
     */
    @Test
    fun `the OrderState sweep catches a state hidden inside a generic type`() {
        val probe = OrderStateCollectionProbe::class.java

        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                orderStateParameterIn(method),
                "OrderStateCollectionProbe.$name hands in a state and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases to a class the " +
                    "membership test it replaced never held",
            )
        }
        assertNotNull(
            orderStateParameterIn(probe.methods.single { it.name == "direct" }),
            "the converted sweep must still catch the bare parameter the membership test caught",
        )
        assertNull(
            orderStateParameterIn(probe.methods.single { it.name == "neighbour" }),
            "OrderStateRejection is a rejection reason, not a state; a substring match would flag " +
                "it and the sweep would be about the wrong thing",
        )
        assertNull(
            orderStateParameterIn(probe.methods.single { it.name == "permitted" }),
            "the rule is about states, not about generics",
        )
    }

    /**
     * The control that proves the §4.6 time sweep is not erasure-blind.
     *
     * A `List<Long>` or a `Map<String, Long>` on this machine is a door for a counterparty's
     * `created_at`, and the erased filter — parameter equal to `long` or to `java.lang.Long` —
     * saw `java.util.List` and let it through. Fed to [timeParameterIn]; the `direct` case holds
     * the sweep to both spellings, since a bare Kotlin `Long` is the primitive and a boxed one
     * inside a generic is not.
     */
    @Test
    fun `the unix-seconds sweep catches a Long hidden inside a generic type`() {
        val probe = UnixSecondsProbe::class.java

        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                timeParameterIn(method),
                "UnixSecondsProbe.$name carries a time and the sweep missed it — " +
                    "${method.genericParameterTypes.single().typeName} erases past an erased-type check",
            )
        }
        assertNotNull(
            timeParameterIn(probe.methods.single { it.name == "direct" }),
            "the converted sweep must still catch a bare `long`, which is how §4.6's rule was " +
                "broken the obvious way",
        )
        assertNull(
            timeParameterIn(probe.methods.single { it.name == "permitted" }),
            "the rule is about unix seconds, not about generics",
        )
    }

    /**
     * A probe, not a fixture: three shapes §9.1 forbids and the erased check could not see, one it
     * could, and one the rule permits. Members are found by reflection, so a probe that failed to
     * compile into the test output cannot let its control pass vacuously — `single` throws instead.
     */
    @Suppress("unused")
    private class BooleanCollectionProbe {
        fun wrapped(flags: List<Boolean>): Int = flags.size
        fun keyed(flags: Map<String, Boolean>): Int = flags.size
        fun nested(flags: List<List<Boolean>>): Int = flags.size
        fun direct(flag: Boolean): Int = if (flag) 1 else 0
        fun permitted(sizes: List<Long>): Int = sizes.size
    }

    /** The same five shapes for the pinned String list. Its `permitted` also pins the token match. */
    @Suppress("unused")
    private class StringCollectionProbe {
        fun wrapped(status: List<String>): Int = status.size
        fun keyed(status: Map<String, Long>): Int = status.size
        fun nested(status: List<List<String>>): Int = status.size
        fun direct(status: String): Int = status.length
        fun permitted(builder: StringBuilder): Int = builder.length
    }

    /** §9.2's forbidden evidence type, in the four positions a door could hide it and one it may not. */
    @Suppress("unused")
    private class VerifiedPaymentCollectionProbe {
        fun wrapped(payments: List<VerifiedPayment>): Int = payments.size
        fun keyed(payments: Map<String, VerifiedPayment>): Int = payments.size
        fun returned(): List<VerifiedPayment> = emptyList()
        fun direct(payment: VerifiedPayment): Int = payment.hashCode()
        fun permitted(names: List<String>): Int = names.size
    }

    /** §8.6's role: forbidden as a set, legal as one checked value — the probe holds both halves. */
    @Suppress("unused")
    private class PayeeSetProbe {
        fun wrapped(payees: Set<Payee>): Int = payees.size
        fun nested(payees: Set<Set<Payee>>): Int = payees.size
        fun bare(payee: Payee): Int = payee.ordinal
        fun permitted(tokens: Set<String>): Int = tokens.size
    }

    /** §11.3 invariant 5's state, plus the neighbouring name a substring match would wrongly flag. */
    @Suppress("unused")
    private class OrderStateCollectionProbe {
        fun wrapped(states: List<OrderState>): Int = states.size
        fun keyed(states: Map<String, OrderState>): Int = states.size
        fun nested(states: List<List<OrderState>>): Int = states.size
        fun direct(state: OrderState): Int = state.ordinal
        fun neighbour(rejection: OrderStateRejection): Int = rejection.ordinal
        fun permitted(tokens: List<String>): Int = tokens.size
    }

    /** §4.6's time, in the four shapes a `created_at` could reach the machine through, and one it cannot. */
    @Suppress("unused")
    private class UnixSecondsProbe {
        fun wrapped(times: List<Long>): Int = times.size
        fun keyed(times: Map<String, Long>): Int = times.size
        fun nested(times: List<List<Long>>): Int = times.size
        fun direct(at: Long): Int = at.toInt()
        fun permitted(tokens: List<String>): Int = tokens.size
    }
}
