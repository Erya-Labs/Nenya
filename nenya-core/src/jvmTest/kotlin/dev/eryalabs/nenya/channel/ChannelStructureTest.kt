package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.wire.CheckedEvent
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §7.2's two structural rules, enforced by reflection rather than by review: the attribution result
 * is unforgeable through the published API, and the gift wrap's ephemeral pubkey is unrepresentable
 * here.
 *
 * The sweep is the one `PaymentStructureTest` specifies, over `dev/eryalabs/nenya/channel`, and
 * every note there applies unchanged:
 *
 * - **`java.lang.reflect` only.** `kotlin-reflect` is not on any classpath and STOP RULE 11 forbids
 *   adding one. `KClass.members` and `KClass.constructors` compile — with a warning — and then
 *   throw `KotlinReflectionNotSupportedError` at run time, while `kotlin.reflect.full.*` does not
 *   compile at all, so reaching for it burns a strike on a red build rather than a red test.
 * - **`getResources`, plural, then the `/classes/kotlin/jvm/main/` URL.** The singular form resolves
 *   to the **test** output directory, because `build/classes/kotlin/jvm/test` precedes
 *   `build/classes/kotlin/jvm/main` on the test runtime classpath and this repo mirrors package
 *   names into its test tree. A sweep built on it would enumerate this very file's class, never see
 *   [AttributedRumor], and pass with the implementation entirely absent.
 * - **Classes asserted by name.** A count is satisfied by whatever the wrong classpath entry
 *   happened to contain, which is exactly the failure the plural form exists to avoid.
 *
 * ### Every type inspection here walks the generic signature
 *
 * T11's review found this sweep shape elsewhere in the repository reading the **erased**
 * `parameterTypes`/`returnType`, where a published `fun ids(): List<OrderId>` erases to `List` and
 * satisfies the assertion while handing a caller exactly the thing the rule forbids, wrapped in a
 * collection. Every type-inspecting assertion below goes through [mentions], which walks
 * `genericParameterTypes` and `genericReturnType` and matches on `typeName` — not just the first
 * one, which is the blind spot T11 found and T12 then wrote fresh into a seventh file. Counting a
 * constructor's arity with `parameterTypes.size` is not a type inspection and is untouched by this.
 */
class ChannelStructureTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.channel"

        /** The classes this sweep is *about*, by name rather than by count. */
        val EXPECTED_BY_NAME: Set<String> = setOf(
            "AttributedRumor",
            "AttributedRumor\$Chat",
            "AttributedRumor\$Bound",
            "AttributedRumor\$Companion",
            "Attribution",
            "RumorKind",
            "OrderMessageKind",
            "ChannelTags",
            "ChannelRejection",
            "ChannelException",
            "ChannelVocabulary",
            // §7.5 and §7.6.
            "OrderProposal",
            "OrderProposal\$Companion",
            "OrderStatusMessage",
            "OrderStatusMessage\$Companion",
            "Acceptance",
            "Acceptance\$Accepted",
            "Acceptance\$CounterProposal",
            "Acceptance\$NotAnAcceptance",
            "SignedTerms",
            "ChannelTerms",
            // §10.1, §10.2 and §10.3.
            "DeliveryCommitmentMessage",
            "DeliveryCommitmentMessage\$Companion",
            "DeliverableReleaseMessage",
            "DeliverableReleaseMessage\$Companion",
            "DeliveryTags",
            "DeliveryTerms",
            "DeliverableTags",
        )

        /**
         * Every place on the published surface where a `String` may be a parameter, pinned by name.
         *
         * A new one turns this red and forces a human to say why it is not a counterparty's claim
         * arriving as a term. There are five categories now, and each entry below says which it is
         * and why:
         *
         * 1. **A key the caller resolved outside the message being judged** — §7.2's seal pubkey,
         *    §7.6's provider pubkey, and that same checked key carried back on the answer;
         * 2. **a diagnostic message**, which decides nothing;
         * 3. **a compiler-generated enum lookup**, which nobody wrote;
         * 4. **§10.1's blob URL**, which is a counterparty's value and deliberately not a term:
         *    nothing here decides on it and nothing fetches it;
         * 5. **§10.3's operand values**, which are strings precisely because §10.3 compares bytes.
         *
         * The assertion is set **equality** in both directions, so this list gets no weaker as it
         * grows: a stale entry turns it red exactly as a new one does.
         */
        val STRING_PARAMETERS_PERMITTED: Set<String> = setOf(
            "AttributedRumor\$Companion.attribute", // §7.2's seal pubkey, 64 hex characters
            "ChannelException.<init>",              // the tag name and the rejection message
            "Attribution.valueOf",                  // generated by the Kotlin compiler for enums
            "ChannelRejection.valueOf",             // ditto
            "OrderMessageKind.valueOf",             // ditto
            "RumorKind.valueOf",                    // ditto
            // §7.6, revision `1.5`: the provider's pubkey **as the caller resolved it**, 64 hex
            // characters, read the way §4.3 reads one. It is the opposite of the shape this list
            // guards against — not a claim a counterparty made and this codec believed, but a fact
            // the caller established outside both messages and this codec now checks the seal
            // against. It could not be a richer type without inventing one for "64 hex characters"
            // that `AttributedRumor.attribute` above does not use either.
            "OrderProposal.accepts",
            "Acceptance\$Accepted.<init>",          // that same checked key, carried on the answer
            // §10.1's `["url", …]`, the one value a delivery message publishes as a bare string.
            // It *is* something a counterparty wrote, and it is deliberately not a term: nothing in
            // this library decides anything on it, nothing fetches it (§12 item 10, STOP RULE 13),
            // and §10.4's own answer to "were these the committed bytes" is the SHA-256 of whatever
            // the caller downloaded against `x` — a blob from anywhere that hashes to `x` is the
            // committed blob, wherever this string pointed. A richer type would be a type for
            // "a URL", which would be a thing to be tempted to fetch; `DeliverableCommitment`
            // refuses to hold one at all for exactly that reason, and this is the other half of
            // that decision rather than a hole in it.
            "DeliveryCommitmentMessage.<init>",
            // §10.3's four operands, held as the **values** the two messages carried. Strings on
            // purpose and in the one shape this list is not about: §10.3 says the release's value
            // MUST be byte-identical to the commitment's, so holding anything richer would be
            // holding something already normalised, which is the comparison §10.3 exists to
            // prevent — `SignedTerms` is the same shape for §7.6 one rule over, and is off this
            // list only because §7.6's operands are whole tag arrays. Nothing here is a claim this
            // codec believes: an instance is only ever compared against another instance, both
            // built by `decode` from tags T13 already attributed, and the class is `internal` so a
            // Kotlin caller cannot mint one to compare against.
            "DeliverableTags.<init>",
        )

        /**
         * §7.2: "The gift wrap's `pubkey` is random and carries **no** identity. Implementations
         * MUST NOT display it, index by it, or use it in any comparison."
         *
         * A rule expressed as an absence needs a test that fails when the absence ends, so no
         * published class or member of this package may be named after the wrap or its ephemeral
         * key. `subject` is here for the same reason one rule down: §7.4 makes it display metadata
         * and forbids deriving any term or state from it, and the way to keep that is to have no
         * member for a caller to read one out of.
         */
        val FORBIDDEN_NAMES = Regex("""(?i)wrap|ephemeral|subject""")

        /** A member whose name says it answers §7.6's "were these terms accepted". */
        val ACCEPTANCE_ANSWER = Regex("""(?i)^(is|was|has)?accept""")

        /** A member whose name says it hands back an order `status` (§11.1). */
        val STATUS_SHAPED = Regex("""(?i)status|state""")

        /** `Any`'s three, which every class declares and none of which answers anything. */
        val OBJECT_METHODS: Set<String> = setOf("toString", "equals", "hashCode")

        fun mainClasses(): List<Class<*>> {
            val loader = ChannelStructureTest::class.java.classLoader
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
            // partial output directory goes red instead of sweeping less than it claims. Raised
            // from 18 by §7.5's and §7.6's types and from 28 by §10.1's and §10.3's; a floor is
            // only ever raised.
            val pinned = 37
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
         * `typeName` prints the parameterised form, `java.util.List<dev.eryalabs.nenya.seam.OrderId>`,
         * so a `contains` over it sees what an erased-type check cannot.
         */
        fun mentions(executable: Executable): List<String> =
            (
                executable.genericParameterTypes.toList() +
                    listOfNotNull((executable as? Method)?.genericReturnType)
                ).map { it.typeName }
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

    /** T3's shape, and the two near-misses it names, asserted on this package's evidence type. */
    @Test
    fun `AttributedRumor has no constructor for a client to reach`() {
        val type = mainClasses().single { it.name == "$PACKAGE.AttributedRumor" }

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

    /**
     * The assertion that discriminates the shape T3 forbids: the two above are trivially true of
     * any interface at all and would pass over a public implementation.
     *
     * Two kinds of subtype exist here and each gets the strongest statement available to it: an
     * implementing **class** must be non-public, so no client can construct one; an implementing
     * **interface** — [AttributedRumor.Chat] and [AttributedRumor.Bound], which a caller has to be
     * able to name — must itself be `sealed`, so no client can implement one either.
     */
    @Test
    fun `every implementation of AttributedRumor is unreachable from a client`() {
        val classes = mainClasses()
        val type = classes.single { it.name == "$PACKAGE.AttributedRumor" }
        val implementations = classes.filter { it != type && type.isAssignableFrom(it) }

        assertTrue(
            implementations.isNotEmpty(),
            "the sweep must actually find an implementation; the assertions above are true of any " +
                "interface at all",
        )
        assertTrue(
            implementations.any { !it.isInterface },
            "the sweep must find a concrete implementation, or the class rule below checks nothing",
        )
        for (implementation in implementations) {
            if (implementation.isInterface) {
                assertTrue(
                    implementation.isSealed,
                    "${implementation.name} refines AttributedRumor and is not sealed, so another " +
                        "module can implement it and mint an attribution nobody checked",
                )
            } else {
                assertFalse(
                    Modifier.isPublic(implementation.modifiers),
                    "${implementation.name} implements AttributedRumor and is JVM-public, so a " +
                        "client can construct a rumor attributed to a key it never sealed with",
                )
            }
        }
    }

    /**
     * §7.2 made unrepresentable: the entry point takes the seal's pubkey and the rumor, and there
     * is no parameter a gift wrap's ephemeral pubkey could arrive in.
     */
    @Test
    fun `the entry point takes one pubkey, a checked event and a limit, and nothing else`() {
        val companion = mainClasses().single { it.name == "$PACKAGE.AttributedRumor\$Companion" }
        val attribute = companion.methods.single { it.name == "attribute" && '$' !in it.name }

        assertEquals(
            listOf(String::class.java, CheckedEvent::class.java, TagLimits::class.java),
            attribute.parameterTypes.toList(),
            "§7.2 compares the seal's pubkey with the rumor's; a second pubkey parameter is where " +
                "the gift wrap's ephemeral key gets in, and §7.2 forbids using it in any comparison",
        )
        assertEquals(
            1,
            attribute.parameterTypes.count { it == String::class.java },
            "exactly one hex string goes in, and it is the seal's",
        )
        assertEquals(
            "$PACKAGE.AttributedRumor",
            attribute.genericReturnType.typeName,
            "the door produces the sealed type and nothing looser",
        )
        assertTrue(
            mentions(attribute).none { FORBIDDEN_NAMES.containsMatchIn(it) },
            "the entry point mentions ${mentions(attribute)}",
        )
    }

    /** No published member of this package is named after a wrap, an ephemeral key or a subject. */
    @Test
    fun `nothing published here names a gift wrap, an ephemeral key or a subject`() {
        var inspected = 0
        for (type in publishedClasses()) {
            assertFalse(
                FORBIDDEN_NAMES.containsMatchIn(type.name.removePrefix("$PACKAGE.")),
                "${type.name} is named after something §7.2 or §7.4 forbids deriving anything from",
            )
            for (executable in publishedExecutables(type)) {
                inspected++
                assertFalse(
                    FORBIDDEN_NAMES.containsMatchIn(label(type, executable)),
                    "${label(type, executable)} is a member §7.2 or §7.4 forbids",
                )
                for (mentioned in mentions(executable)) {
                    assertFalse(
                        FORBIDDEN_NAMES.containsMatchIn(mentioned.removePrefix("$PACKAGE.")),
                        "${label(type, executable)} mentions $mentioned",
                    )
                }
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * §7.4's `kind:14` rule, made structural: "an implementation MAY carry `order` on a `kind:14`
     * and MUST NOT derive anything from its presence or absence."
     *
     * So no member reachable on an [AttributedRumor.Chat] — declared or inherited — mentions an
     * [OrderId] anywhere in its generic signature, while [AttributedRumor.Bound] does. The second
     * half is what keeps the first from being a fact about a sweep that finds nothing.
     */
    @Test
    fun `a chat exposes no order id and a bound rumor does`() {
        val classes = mainClasses()
        val chat = classes.single { it.name == "$PACKAGE.AttributedRumor\$Chat" }
        val bound = classes.single { it.name == "$PACKAGE.AttributedRumor\$Bound" }
        val chatImplementations = classes.filter { !it.isInterface && chat.isAssignableFrom(it) }

        assertTrue(chatImplementations.isNotEmpty(), "the sweep found no kind:14 implementation")
        for (type in listOf(chat) + chatImplementations) {
            for (method in type.methods.filter { !it.isSynthetic && !it.isBridge }) {
                assertTrue(
                    mentions(method).none { OrderId::class.java.name in it },
                    "${type.name}.${method.name} exposes an order id on a kind:14 rumor: " +
                        "${mentions(method)}",
                )
            }
        }
        assertTrue(
            bound.methods.any { method -> mentions(method).any { OrderId::class.java.name in it } },
            "AttributedRumor.Bound must expose the order id §7.4 binds it to, or the assertion " +
                "above is about a sweep that cannot find one anywhere",
        )
        assertFalse(
            bound.isAssignableFrom(chat) || chat.isAssignableFrom(bound),
            "the two are alternatives: a kind:14 must not be usable where a bound rumor is",
        )
    }

    /** The narrowest door: every published function returning one is one §7.2's check produced. */
    @Test
    fun `the only published function returning an attributed rumor is attribute`() {
        val doors = mutableListOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val returned = (executable as? Method)?.genericReturnType?.typeName ?: continue
                if (returned.startsWith("$PACKAGE.AttributedRumor")) doors += label(type, executable)
            }
        }

        assertEquals(listOf("AttributedRumor\$Companion.attribute"), doors)
    }

    /** §9.1's shape, one layer over: a decoder that can be told the answer is not a decoder. */
    @Test
    fun `no published constructor or method accepts a Boolean`() {
        val published = publishedClasses()
        val found = published.map { it.name.removePrefix("$PACKAGE.") }.toSet()
        assertTrue(
            EXPECTED_BY_NAME.all { it in found },
            "the Boolean sweep must be looking at $EXPECTED_BY_NAME, not at whatever classpath " +
                "entry it happened to land on; it found $found",
        )

        var inspected = 0
        for (type in published) {
            for (executable in publishedExecutables(type)) {
                inspected++
                // Parameters only. A Boolean *return* is an answer this package computed — §7.4's
                // `carriesOrderId` is one — and a Boolean parameter is an answer it was handed.
                for (mentioned in executable.genericParameterTypes.map { it.typeName }) {
                    assertFalse(
                        mentioned == "boolean" || mentioned == "java.lang.Boolean",
                        "${label(type, executable)} takes a Boolean. A rumor is attributed by " +
                            "comparing two keys this library was handed; a function that can be " +
                            "told the answer is not performing §7.2's check.",
                    )
                }
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * §7.6's answer is a sealed type, and never a `Boolean` or a status-shaped `String`.
     *
     * §7.6 names three outcomes with three different correct responses — settle in, re-propose with
     * a new order id, or ignore — and a `Boolean` collapses the second into the third. A
     * status-shaped `String` hands back a decision that has already been decoded once, which is the
     * shape STOP RULE 12 and §11.1 both exist to prevent.
     *
     * The sweep is by *name*, over every published member of the package whose name says it answers
     * the acceptance question, so a second one added later is caught rather than assumed absent —
     * and it is asserted non-empty, because a regex that matched nothing would pass over a codec
     * that returned `boolean` from every one of them.
     */
    @Test
    fun `the answer to whether terms were accepted is a sealed type, not a Boolean or a String`() {
        val answers = mutableListOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (method.name in OBJECT_METHODS) continue
                if (!ACCEPTANCE_ANSWER.containsMatchIn(method.name)) continue
                answers += label(type, method)
                assertEquals(
                    "$PACKAGE.Acceptance",
                    method.genericReturnType.typeName,
                    "${label(type, method)} answers §7.6 and must return the sealed type",
                )
            }
        }

        assertEquals(
            setOf("OrderProposal.accepts"),
            answers.toSet(),
            "the set of members answering §7.6 must match the pinned list exactly; a new one may " +
                "be the same question asked in a looser shape, and a missing one means the sweep " +
                "stopped seeing the package",
        )
        // And nothing on the answer type itself hands a caller a bare verdict back out: no member
        // of Acceptance or its cases returns a Boolean, and none returns a String **except** the
        // one that is not a verdict at all.
        //
        // `Accepted.getProvider` is that exception and it is pinned by name rather than waved
        // through by a pattern: it is §4.3's 64-hex pubkey the caller resolved and §7.6 checked the
        // seal against, carried so §8.6's `type=2` rule has the same operand. A String there is a
        // key, not an answer — the shape this sweep exists to refuse is a *decision* handed back as
        // text, which is what `divergentTerms` being a list of tag names and `status` being an
        // `OrderState` keep out. A new String accessor on any of these types has to be looked at,
        // which is why the list is exact.
        val cases = publishedClasses().filter { it.name.startsWith("$PACKAGE.Acceptance") }
        assertTrue(cases.size > 1, "the sweep must find the sealed type and its cases; found $cases")
        val stringAccessors = mutableSetOf<String>()
        for (type in cases) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (method.name in OBJECT_METHODS) continue
                val returned = method.genericReturnType.typeName
                assertFalse(
                    returned == "boolean" || returned == "java.lang.Boolean",
                    "${label(type, method)} returns $returned; §7.6's outcome is the type itself",
                )
                if (returned == String::class.java.name) stringAccessors += label(type, method)
            }
        }
        assertEquals(
            setOf("Acceptance\$Accepted.getProvider"),
            stringAccessors,
            "the only String an Acceptance case may hand back is the checked provider key; a " +
                "second one is a decision arriving as text (STOP RULE 12, §11.1), and a missing " +
                "one means the sweep stopped seeing the package",
        )
    }

    /**
     * §11.1's vocabulary crossing the wire boundary: a decoded order `status` is an `OrderState`
     * and never the token a stranger wrote.
     *
     * The conflation §11.1 forbids — one `status` codec serving both the order and the listing
     * vocabularies — is reachable through a `String` accessor and through nothing else, because a
     * caller holding the raw token has to decide for itself which vocabulary to read it under.
     */
    @Test
    fun `a decoded status is an OrderState and never the raw token`() {
        val type = mainClasses().single { it.name == "$PACKAGE.OrderStatusMessage" }
        val status = type.methods.single { it.name == "getStatus" && '$' !in it.name }

        assertEquals(OrderState::class.java.name, status.genericReturnType.typeName)
        for (executable in publishedExecutables(type)) {
            val method = executable as? Method ?: continue
            assertFalse(
                method.genericReturnType.typeName == String::class.java.name &&
                    STATUS_SHAPED.containsMatchIn(method.name),
                "${method.name} hands a status token back as a String",
            )
        }
    }

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
            "the set of published members taking a String must match the pinned list exactly. A " +
                "new entry may be a counterparty's claim arriving as a term; a missing entry means " +
                "the sweep stopped seeing the package.",
        )
    }
}
