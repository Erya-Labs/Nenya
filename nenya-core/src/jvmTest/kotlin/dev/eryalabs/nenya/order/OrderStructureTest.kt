package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.seam.OrderId
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 */
class OrderStructureTest {

    private companion object {

        val PACKAGE: String = OrderStructure.PACKAGE

        /** The binary name `Type.getTypeName()` renders for §9.2's evidence, nested class and all. */
        const val EVIDENCED: String = "dev.eryalabs.nenya.settlement.Settlement\$Evidenced"

        /** The type that must appear nowhere in this package's generic signatures. */
        const val VERIFIED_PAYMENT: String = "dev.eryalabs.nenya.payment.VerifiedPayment"

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
     */
    @Test
    fun `no published constructor or method accepts a Boolean`() {
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                for (parameter in executable.parameterTypes) {
                    assertFalse(
                        parameter == java.lang.Boolean.TYPE || parameter == java.lang.Boolean::class.java,
                        "${label(type, executable)} takes a Boolean. §9.1 and §11.3 invariant 5: a " +
                            "wallet's isPaid and a counterparty's status update are not evidence, " +
                            "and a state machine that can be told the answer is not one",
                    )
                }
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

    @Test
    fun `every published String parameter is on the pinned list`() {
        val found = mutableSetOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                if (executable.parameterTypes.any { it == String::class.java }) {
                    found += label(type, executable)
                }
            }
        }

        assertEquals(
            STRING_PARAMETERS_PERMITTED,
            found,
            "the set of published members taking a String must match the pinned list exactly. A " +
                "new entry may be a status-shaped string arriving as a decision; a missing entry " +
                "means the sweep stopped seeing the package.",
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
     */
    @Test
    fun `the transition function takes an order and an event, and nothing that can assert`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }
        val on = machine.methods.single { it.name == "on" }

        assertEquals(
            listOf(Order::class.java, OrderEvent::class.java),
            on.parameterTypes.toList(),
            "anything else on this parameter list is something a counterparty or a wallet could " +
                "say (§9.1, §11.3 invariant 5)",
        )
        assertEquals(OrderOutcome::class.java, on.returnType)

        val receipts = OrderStructure.mainClasses()
            .single { it.name == "$PACKAGE.OrderEvent\$ReceiptsVerified" }
        assertEquals(
            listOf(Set::class.java),
            receipts.constructors.single().parameterTypes.toList(),
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
                val signature = executable.genericParameterTypes.map { it.typeName } +
                    (executable as? java.lang.reflect.Method)?.genericReturnType?.typeName.orEmpty()
                for (named in signature) {
                    assertFalse(
                        VERIFIED_PAYMENT in named,
                        "${label(type, executable)} names $VERIFIED_PAYMENT in a generic " +
                            "position ($named). A door into this package taking one re-opens both " +
                            "probes: evidence with no invoice behind it, and a payee a caller " +
                            "chose rather than the receipt's own tag",
                    )
                }
            }
        }
        assertTrue(swept > 20, "the sweep inspected only $swept members, which is not the package")
    }

    /**
     * The one door that produces an order carries no state, no token and no flag — it produces
     * `proposed` and nothing else (§11.2's genesis row), and the sink door produces `unknown` and
     * nothing else (§11.1).
     */
    @Test
    fun `neither door into an order accepts a state`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }

        assertEquals(
            listOf(OrderEvent.Proposal::class.java),
            machine.methods.single { it.name == "open" }.parameterTypes.toList(),
        )
        assertEquals(
            // The order id joins the sink door for the reason it joined the genesis one: an order
            // that has forgotten which thread it belongs to cannot be told apart from another at
            // the same price. It is an `OrderId` and never a `String`, so §4.3's length and hex
            // rules were applied before it got here.
            listOf(OrderId::class.java, OrderTerms::class.java),
            machine.methods.single { it.name == "unrecognised" }.parameterTypes.toList(),
        )
        for (method in machine.methods.filter { it.declaringClass == machine }) {
            assertFalse(
                OrderState::class.java in method.parameterTypes,
                "${method.name} takes an OrderState: a state a caller can hand in is a state a " +
                    "caller can assert, which is §11.3 invariant 5 with an extra step",
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
     */
    @Test
    fun `no method on the machine accepts an Instant`() {
        val machine = OrderStructure.mainClasses().single { it.name == "$PACKAGE.OrderMachine" }

        for (method in machine.methods.filter { it.declaringClass == machine && '$' !in it.name }) {
            val timeShaped = method.parameterTypes.filter {
                it == Long::class.javaPrimitiveType || it == Long::class.javaObjectType
            }
            assertTrue(
                timeShaped.isEmpty(),
                "${method.name} takes a Long, which is how this library carries unix seconds. " +
                    "§4.6: the only time this library may know comes " +
                    "from the injected clock, and a parameter is a door for a counterparty's " +
                    "created_at to arrive through",
            )
        }
    }
}
