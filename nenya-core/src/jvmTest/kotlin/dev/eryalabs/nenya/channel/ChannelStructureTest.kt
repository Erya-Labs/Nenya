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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * collection. Every type-inspecting assertion below walks `genericParameterTypes` and
 * `genericReturnType` and matches on `typeName` — not just the first one, which is the blind spot
 * T11 found and T12 then wrote fresh into a seventh file. No read of the erased `parameterTypes`
 * or `returnType` is left in this file at all.
 *
 * ### Reading the generic signature is not the same as seeing inside it
 *
 * T36's correction, and the reason this file changed a second time: a sweep can walk the generic
 * signature and still be blind, because `java.util.List<java.lang.Boolean>` is not **equal** to
 * `"java.lang.Boolean"` and does not **start with** `dev.eryalabs.nenya.channel.AttributedRumor`.
 * Every predicate here therefore looks for the forbidden type *anywhere* in the printed name, and
 * on a whole type token: through [namesType], through [namesTypeUnder] where a nested case counts
 * too, or through the plain `contains` [orderIdMentions] keeps and explains for being the wider of
 * the two. A forbidden type hidden one collection deep is caught by all three.
 *
 * Every such predicate is a **named function** in the companion below, and every one of them has a
 * probe — a test-only class carrying the shapes the rule forbids, fed to *that same function*. A
 * control that exercised a second copy of the check would prove nothing about the sweep, which is
 * the exact failure mode T36 exists to close. The probes are one class per predicate for the same
 * reason: one shared probe would make every control but one tautological.
 *
 * Two assertions deliberately keep exact `typeName` equality, and both say so where they stand:
 * "every published `String` parameter is on the pinned list" and the `String`-accessor half of
 * §7.6's answer. Their rule is about a `String` **itself** — `SignedTerms` and `divergentTerms`
 * hold `List<String>` on purpose and are documented as a different shape — so widening either to
 * the token match would not sharpen the rule, it would replace it (STOP RULE 4).
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
            // T41's write half of §7.4, §7.5, §6.1 and §10.
            "RumorEnvelope",
            "RumorBuild",
            "RumorBuild\$Built",
            "RumorBuild\$Refused",
            "RumorWriter",
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
         * 6. **Values a client of this library AUTHORS**, which T41's write half adds, and it is
         *    the category this list was hardest to reason about. Every `RumorEnvelope` and
         *    `RumorWriter` entry below is a value the embedding app chose for a message it is about
         *    to seal with its own key — the exact opposite of the shape this list guards against,
         *    which is a counterparty's claim arriving as a term and being believed. No code in this
         *    library reads one as evidence of anything: they are written into tags, and when a
         *    counterparty's copy of the same message comes back it is *that* copy the decoders read,
         *    through §7.2's attribution and §4.3's Encoding column. The same reading
         *    `ListingStructureTest` records for `AuthoredListing.<init>` one package over.
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
            // Category 6, T41's write half. §4.1's author pubkey and §4.1's `content`, both of
            // them values the app is about to sign its own name to. The pubkey is read by the same
            // `readPubkeyHex` a `p` tag goes through and refused as MALFORMED_EVENT_FIELD if it is
            // not 64 hex characters; `content` carries no machine meaning to any codec here.
            "RumorEnvelope.<init>",
            // The by-value equivalent of the `ChannelException.<init>` pair two entries up: the tag
            // name a refusal is about and the reason in words. Decides nothing.
            "RumorBuild\$Refused.<init>",
            // §8.1's `fee` recipient, on each of the three messages that can carry a fee term. It
            // is the third element of a tag the AUTHOR is stating, not one being believed — §8.4's
            // comparison of a counterparty's copy against it is `FeeTermAgreement.across`, which
            // takes whole tags and is on no list here.
            "RumorWriter.proposal",
            "RumorWriter.statusUpdate",
            "RumorWriter.privateBid",
            // §10.1's blob URL again, and this is the write side of the entry four up: the same
            // decision about not having a type for "a URL", made once for the value the decoder
            // publishes and once for the value the writer is handed.
            "RumorWriter.commitment",
            // §10.2's decryption key and nonce, which this writer holds to the 64 and 24 LOWERCASE
            // hex characters §10.2 makes mandatory on write. They are strings because §10.2
            // specifies an encoding rather than a structure, and a richer type would be a type that
            // *holds* key material — which §12 item 11 and STOP RULE 14 are precisely about not
            // doing. `RumorWriter.release` puts both into tags and onto no field of anything, so
            // there is no member from which one could reach a log.
            "RumorWriter.release",
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
            // from 18 by §7.5's and §7.6's types, from 28 by §10.1's and §10.3's, and from 37 by
            // T41's write half — `RumorEnvelope`, `RumorBuild` with its two cases, `RumorWriter`
            // and the private `Message` carrier; a floor is only ever raised.
            //
            // Measured after a clean `--rerun-tasks` compile, deliberately: an incrementally-built
            // output directory here still held one stale class from a previous run and reported 45,
            // which would have pinned this floor one above what the package actually compiles to and
            // turned every sweep in this file red on the next clean build.
            val pinned = 44
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

        /** The **generic** parameter type names of [executable], the return deliberately excluded. */
        fun parameterMentions(executable: Executable): List<String> =
            executable.genericParameterTypes.map { it.typeName }

        /**
         * Whether [typeName] names [sought] itself or names it inside a generic's type arguments.
         *
         * A bare `contains` is wrong: `java.lang.StringBuilder` contains `java.lang.String`. So the
         * match is on a whole type token — the character either side must not continue an
         * identifier. `SealPubkeyStringProbe.builder` is the control for that, fed to the real
         * predicate rather than to this function directly.
         *
         * No `Regex`: STOP RULE 11's sibling rule for this round forbids one in a converted sweep,
         * and a hand-written scan is what a reviewer can read without running it.
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

        /**
         * Whether [typeName] names a type whose own name **begins** with [prefix] — the type
         * itself, a nested case of it, or one held inside a generic's type arguments.
         *
         * The left boundary is checked and the right is not, and that asymmetry is the rule: a
         * `dev.eryalabs.nenya.channel.AttributedRumor$Bound` is the same door under another name,
         * while a hypothetical `…ReattributedRumor` must not match on a suffix. [namesType] cannot
         * express this, because `$` continues an identifier there on purpose.
         */
        fun namesTypeUnder(typeName: String, prefix: String): Boolean {
            var from = 0
            while (true) {
                val at = typeName.indexOf(prefix, from)
                if (at < 0) return false
                if (at == 0 || !continuesIdentifier(typeName[at - 1])) return true
                from = at + 1
            }
        }

        private fun continuesIdentifier(c: Char): Boolean =
            c.isLetterOrDigit() || c == '.' || c == '$' || c == '_'

        /** Everything [executable] mentions that §7.2 or §7.4 forbids it being named after. */
        fun forbiddenNameMentions(executable: Executable): List<String> =
            mentions(executable).filter { FORBIDDEN_NAMES.containsMatchIn(it) }

        /**
         * Everything [executable] mentions that names an [OrderId] — inside a collection as well.
         *
         * `contains` rather than [namesType] here, and on purpose: it is the *wider* of the two, so
         * an `OrderIdRange` would be caught by it and this is the side of the trade §7.4 wants.
         */
        fun orderIdMentions(executable: Executable): List<String> =
            mentions(executable).filter { OrderId::class.java.name in it }

        /** The parameters of [executable] that name a `String`, a `List<String>` included. */
        fun stringNamingParameters(executable: Executable): List<String> =
            parameterMentions(executable).filter { namesType(it, String::class.java.name) }

        /**
         * The parameters of [executable] that name a `Boolean`.
         *
         * Both spellings, because they are the same rule: a bare Kotlin `Boolean` parameter erases
         * to the JVM primitive whose `typeName` is `boolean`, and inside a generic the very same
         * type is boxed and reads `java.lang.Boolean`. Checking one of the two is the T36 defect
         * with an extra step.
         */
        fun booleanParameters(executable: Executable): List<String> =
            parameterMentions(executable).filter { namesBoolean(it) }

        /** [method]'s return type name if it hands a `Boolean` back, however deeply wrapped. */
        fun booleanVerdict(method: Method): String? =
            method.genericReturnType.typeName.takeIf { namesBoolean(it) }

        private fun namesBoolean(typeName: String): Boolean =
            namesType(typeName, "boolean") || namesType(typeName, "java.lang.Boolean")

        /** Whether [method] hands an [AttributedRumor] back — as itself, as a case, or in a list. */
        fun opensAnAttributedRumorDoor(method: Method): Boolean =
            namesTypeUnder(method.genericReturnType.typeName, "$PACKAGE.AttributedRumor")

        /** Whether [method]'s name says it answers with a status and its return names a `String`. */
        fun handsBackAStatusToken(method: Method): Boolean =
            STATUS_SHAPED.containsMatchIn(method.name) &&
                namesType(method.genericReturnType.typeName, String::class.java.name)
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
     *
     * The parameter list is pinned by **generic** type name, whole and in order. A pin like that
     * needs no probe of its own and none was forgotten: an exact whole-list equality cannot be
     * erasure-blind in the dangerous direction, because a smuggled `List<String>` parameter prints
     * as `java.util.List<java.lang.String>` and fails the pin outright. The expected side is built
     * from `typeName` and never from `name`, because `name` prints an array as `[B` while
     * `genericParameterTypes` prints it as `byte[]`, which would red-line every array parameter a
     * later revision added here.
     */
    @Test
    fun `the entry point takes one pubkey, a checked event and a limit, and nothing else`() {
        val companion = mainClasses().single { it.name == "$PACKAGE.AttributedRumor\$Companion" }
        val attribute = companion.methods.single { it.name == "attribute" && '$' !in it.name }

        assertEquals(
            listOf(
                String::class.java.typeName,
                CheckedEvent::class.java.typeName,
                TagLimits::class.java.typeName,
            ),
            attribute.genericParameterTypes.map { it.typeName },
            "§7.2 compares the seal's pubkey with the rumor's; a second pubkey parameter is where " +
                "the gift wrap's ephemeral key gets in, and §7.2 forbids using it in any comparison",
        )
        assertEquals(
            1,
            stringNamingParameters(attribute).size,
            "exactly one hex string goes in, and it is the seal's — and a second one arriving as " +
                "a `List<String>` is still a second one, which is why this counts type *names* " +
                "and not erased `String` classes",
        )
        assertEquals(
            "$PACKAGE.AttributedRumor",
            attribute.genericReturnType.typeName,
            "the door produces the sealed type and nothing looser",
        )
        assertTrue(
            forbiddenNameMentions(attribute).isEmpty(),
            "the entry point mentions ${mentions(attribute)}",
        )
    }

    /**
     * The control that proves the count above is not erasure-blind.
     *
     * Without it, "exactly one hex string goes in" is satisfied by a predicate that never matches a
     * `List<String>` — which is precisely what the erased `parameterTypes.count { it == String }`
     * it replaced was. The probe is fed [stringNamingParameters], the sweep's own predicate, rather
     * than to a second copy of the check: a control over a copy proves nothing about the original.
     */
    @Test
    fun `the seal pubkey count sees a String hidden inside a generic parameter`() {
        val probe = SealPubkeyStringProbe::class.java
        for (name in listOf("wrapped", "keyed", "parameter", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                stringNamingParameters(method).isNotEmpty(),
                "SealPubkeyStringProbe.$name hides a String and the count missed it — " +
                    "${parameterMentions(method)} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(stringNamingParameters(probe.methods.single { it.name == "direct" }).isNotEmpty())
        // Not merely "reject every generic", and not a `contains` either: `java.lang.StringBuilder`
        // contains `java.lang.String` and is not a String.
        assertTrue(stringNamingParameters(probe.methods.single { it.name == "permitted" }).isEmpty())
        assertTrue(stringNamingParameters(probe.methods.single { it.name == "builder" }).isEmpty())
    }

    /** A probe, not a fixture: four shapes the count must see through, and two it must not. */
    @Suppress("unused")
    private class SealPubkeyStringProbe {
        fun wrapped(keys: List<String>): Int = keys.size
        fun keyed(keys: Map<String, String>): Int = keys.size
        fun parameter(keys: Set<String>): Int = keys.size
        fun nested(keys: List<List<String>>): Int = keys.size
        fun direct(key: String): Int = key.length
        fun permitted(keys: List<Int>): Int = keys.size
        fun builder(key: StringBuilder): Int = key.length
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
                val forbidden = forbiddenNameMentions(executable)
                assertTrue(
                    forbidden.isEmpty(),
                    "${label(type, executable)} mentions $forbidden",
                )
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * The control that proves the two name sweeps above are not erasure-blind.
     *
     * Without it, "nothing published here names a gift wrap" is satisfied by a member returning
     * `List<EphemeralKeyCarrier>`, because the erased type is `java.util.List` and the forbidden
     * word is only in the type argument. The probe is fed [forbiddenNameMentions] — the same
     * function both sweeps run — so this cannot pass while they stay blind.
     */
    @Test
    fun `the forbidden-name sweep catches a wrap named inside a generic type`() {
        val probe = GiftWrapNameProbe::class.java
        for (name in listOf("wrapped", "keyed", "parameter", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                forbiddenNameMentions(method).isNotEmpty(),
                "GiftWrapNameProbe.$name names something §7.2 forbids and the sweep missed it — " +
                    "${mentions(method)} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(forbiddenNameMentions(probe.methods.single { it.name == "direct" }).isNotEmpty())
        // Not merely "reject every generic".
        assertTrue(forbiddenNameMentions(probe.methods.single { it.name == "permitted" }).isEmpty())
    }

    /** A probe, not a fixture: four shapes §7.2 and §7.4 forbid, and one they permit. */
    @Suppress("unused")
    private class GiftWrapNameProbe {
        fun wrapped(): List<EphemeralKeyCarrier> = emptyList()
        fun keyed(): Map<String, EphemeralKeyCarrier> = emptyMap()
        fun parameter(keys: Set<EphemeralKeyCarrier>): Int = keys.size
        fun nested(): List<List<EphemeralKeyCarrier>> = emptyList()
        fun direct(): EphemeralKeyCarrier? = null
        fun permitted(): List<String> = emptyList()
    }

    /** Named for what §7.2 forbids a published member being named after; never instantiated. */
    private class EphemeralKeyCarrier

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
                    orderIdMentions(method).isEmpty(),
                    "${type.name}.${method.name} exposes an order id on a kind:14 rumor: " +
                        "${mentions(method)}",
                )
            }
        }
        assertTrue(
            bound.methods.any { orderIdMentions(it).isNotEmpty() },
            "AttributedRumor.Bound must expose the order id §7.4 binds it to, or the assertion " +
                "above is about a sweep that cannot find one anywhere",
        )
        assertFalse(
            bound.isAssignableFrom(chat) || chat.isAssignableFrom(bound),
            "the two are alternatives: a kind:14 must not be usable where a bound rumor is",
        )
    }

    /**
     * The control that proves the `kind:14` sweep is not erasure-blind.
     *
     * Without it, "no member reachable on a Chat mentions an OrderId" is satisfied by
     * `fun orders(): List<OrderId>`, which erases to `java.util.List` and hands a caller exactly
     * the derivation §7.4 forbids, one collection deep. The probe is fed [orderIdMentions], the
     * predicate both halves of the sweep above run.
     */
    @Test
    fun `the kind 14 sweep catches an order id hidden inside a generic type`() {
        val probe = OrderIdCollectionProbe::class.java
        for (name in listOf("wrapped", "keyed", "parameter", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                orderIdMentions(method).isNotEmpty(),
                "OrderIdCollectionProbe.$name hides an order id and the sweep missed it — " +
                    "${mentions(method)} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(orderIdMentions(probe.methods.single { it.name == "direct" }).isNotEmpty())
        // Not merely "reject every generic".
        assertTrue(orderIdMentions(probe.methods.single { it.name == "permitted" }).isEmpty())
    }

    /** A probe, not a fixture: four shapes §7.4 forbids on a `kind:14`, and one it permits. */
    @Suppress("unused")
    private class OrderIdCollectionProbe {
        fun wrapped(): List<OrderId> = emptyList()
        fun keyed(): Map<String, OrderId> = emptyMap()
        fun parameter(orders: Set<OrderId>): Int = orders.size
        fun nested(): List<List<OrderId>> = emptyList()
        fun direct(): OrderId? = null
        fun permitted(): List<String> = emptyList()
    }

    /**
     * The narrowest door: every published function returning one is one §7.2's check produced.
     *
     * `startsWith` was not enough even over the generic name, which is T36's own correction: a
     * `fun rumors(): List<AttributedRumor>` prints as `java.util.List<…AttributedRumor>`, starts
     * with `java.util`, and walked straight past the sweep while handing a caller a rumor nobody's
     * seal was compared against. [opensAnAttributedRumorDoor] matches the token wherever it sits.
     */
    @Test
    fun `the only published function returning an attributed rumor is attribute`() {
        val doors = mutableListOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                val method = executable as? Method ?: continue
                if (opensAnAttributedRumorDoor(method)) doors += label(type, method)
            }
        }

        assertEquals(listOf("AttributedRumor\$Companion.attribute"), doors)
    }

    /**
     * The control that proves the door sweep is not erasure-blind.
     *
     * Without it, "the only published function returning an attributed rumor is attribute" is
     * satisfied by a second door that returns a collection of them — the shape §7.2 cares about
     * most, because a caller cannot tell by looking that nothing checked their seals.
     */
    @Test
    fun `the door sweep catches an attributed rumor hidden inside a generic return`() {
        val probe = AttributedRumorDoorProbe::class.java
        for (name in listOf("wrapped", "keyed", "nested", "cased")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                opensAnAttributedRumorDoor(method),
                "AttributedRumorDoorProbe.$name is a door and the sweep missed it — " +
                    "${method.genericReturnType.typeName} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(opensAnAttributedRumorDoor(probe.methods.single { it.name == "direct" }))
        // Not merely "reject every generic".
        assertFalse(opensAnAttributedRumorDoor(probe.methods.single { it.name == "permitted" }))
    }

    /** A probe, not a fixture: four doors §7.2 forbids a second of, and one shape it permits. */
    @Suppress("unused")
    private class AttributedRumorDoorProbe {
        fun wrapped(): List<AttributedRumor> = emptyList()
        fun keyed(): Map<String, AttributedRumor> = emptyMap()
        fun nested(): List<List<AttributedRumor>> = emptyList()
        fun cased(): List<AttributedRumor.Bound> = emptyList()
        fun direct(): AttributedRumor? = null
        fun permitted(): List<String> = emptyList()
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
                val booleans = booleanParameters(executable)
                assertTrue(
                    booleans.isEmpty(),
                    "${label(type, executable)} takes a Boolean ($booleans). A rumor is attributed " +
                        "by comparing two keys this library was handed; a function that can be " +
                        "told the answer is not performing §7.2's check.",
                )
            }
        }
        assertTrue(inspected > 20, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * The control that proves the Boolean sweep is not erasure-blind.
     *
     * Without it, "no published member takes a Boolean" is satisfied by a predicate that never
     * matches a `List<Boolean>` — which is precisely what the equality check it replaced was, and
     * what STOP RULE 12 is about: a decoder that can be handed the verdict is not a decoder,
     * whether the verdict arrives bare or one collection deep.
     */
    @Test
    fun `the boolean sweep catches a Boolean hidden inside a generic type`() {
        val probe = BooleanParameterProbe::class.java
        for (name in listOf("wrapped", "keyed", "parameter", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                booleanParameters(method).isNotEmpty(),
                "BooleanParameterProbe.$name hides a Boolean and the sweep missed it — " +
                    "${parameterMentions(method)} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(booleanParameters(probe.methods.single { it.name == "direct" }).isNotEmpty())
        // Not merely "reject every generic".
        assertTrue(booleanParameters(probe.methods.single { it.name == "permitted" }).isEmpty())
    }

    /** A probe, not a fixture: four shapes §9.1 forbids as a parameter, and one it permits. */
    @Suppress("unused")
    private class BooleanParameterProbe {
        fun wrapped(flags: List<Boolean>): Int = flags.size
        fun keyed(flags: Map<String, Boolean>): Int = flags.size
        fun parameter(flags: Set<Boolean>): Int = flags.size
        fun nested(flags: List<List<Boolean>>): Int = flags.size
        fun direct(flag: Boolean): Int = if (flag) 1 else 0
        fun permitted(names: List<String>): Int = names.size
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
                assertNull(
                    booleanVerdict(method),
                    "${label(type, method)} returns $returned; §7.6's outcome is the type itself",
                )
                // Exact equality, and deliberately not the token match one line up: this half of
                // the rule is about a decision handed back **as text**, and a `List<String>` is the
                // shape §7.6's answer uses to say which terms diverged without saying anything
                // about them. `CounterProposal.divergentTerms` is that list; widening this to
                // `namesType` would not sharpen the rule, it would delete it and replace it with a
                // different one (STOP RULE 4), so there is no probe here and none is missing.
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
     * The control that proves §7.6's verdict sweep is not erasure-blind.
     *
     * Without it, "no member of Acceptance or its cases returns a Boolean" is satisfied by
     * `fun agreed(): List<Boolean>`, which erases to `java.util.List` and collapses §7.6's three
     * outcomes back into the two a `Boolean` can carry — one collection deep, where the equality
     * check it replaced could not see. Its own probe class rather than the parameter sweep's,
     * because a shared one would make one of the two controls tautological.
     */
    @Test
    fun `the verdict sweep catches a Boolean hidden inside a generic return`() {
        val probe = BooleanVerdictProbe::class.java
        for (name in listOf("wrapped", "keyed", "nested")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                booleanVerdict(method),
                "BooleanVerdictProbe.$name hands a verdict back and the sweep missed it — " +
                    "${method.genericReturnType.typeName} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertNotNull(booleanVerdict(probe.methods.single { it.name == "direct" }))
        assertNotNull(booleanVerdict(probe.methods.single { it.name == "boxed" }))
        // Not merely "reject every generic".
        assertNull(booleanVerdict(probe.methods.single { it.name == "permitted" }))
    }

    /** A probe, not a fixture: five verdicts §7.6 forbids handing back, and one shape it permits. */
    @Suppress("unused")
    private class BooleanVerdictProbe {
        fun wrapped(): List<Boolean> = emptyList()
        fun keyed(): Map<String, Boolean> = emptyMap()
        fun nested(): List<List<Boolean>> = emptyList()
        fun direct(): Boolean = false
        fun boxed(): Boolean? = null
        fun permitted(): List<String> = emptyList()
    }

    /**
     * §11.1's vocabulary crossing the wire boundary: a decoded order `status` is an `OrderState`
     * and never the token a stranger wrote.
     *
     * The conflation §11.1 forbids — one `status` codec serving both the order and the listing
     * vocabularies — is reachable through a `String` accessor and through nothing else, because a
     * caller holding the raw token has to decide for itself which vocabulary to read it under.
     *
     * "Through a `String` accessor" includes one handing back a *collection* of them, which is why
     * [handsBackAStatusToken] matches the token rather than the whole name: a
     * `fun statuses(): List<String>` is the same conflation, once per element.
     */
    @Test
    fun `a decoded status is an OrderState and never the raw token`() {
        val type = mainClasses().single { it.name == "$PACKAGE.OrderStatusMessage" }
        val status = type.methods.single { it.name == "getStatus" && '$' !in it.name }

        assertEquals(OrderState::class.java.name, status.genericReturnType.typeName)
        for (executable in publishedExecutables(type)) {
            val method = executable as? Method ?: continue
            assertFalse(
                handsBackAStatusToken(method),
                "${method.name} hands a status token back as a String",
            )
        }
    }

    /**
     * The control that proves the status sweep is not erasure-blind.
     *
     * Without it, "a decoded status is an OrderState and never the raw token" is satisfied by
     * `fun statuses(): List<String>` — the vocabulary conflation §11.1 forbids, wrapped in a list.
     * Both halves of the predicate are controlled: a status-shaped name returning no `String` and
     * a `String` returned under a name that says nothing about status are both permitted, so this
     * is not the weaker rule "no member returns a String".
     */
    @Test
    fun `the status sweep catches a raw token hidden inside a generic return`() {
        val probe = StatusTokenProbe::class.java
        for (name in listOf("getStatuses", "getStateByOrder", "getNestedStatuses")) {
            val method = probe.methods.single { it.name == name }
            assertTrue(
                handsBackAStatusToken(method),
                "StatusTokenProbe.$name hands a status token back and the sweep missed it — " +
                    "${method.genericReturnType.typeName} erases past an erased-type check",
            )
        }
        // Caught by the old predicate too: this is what shows the two controls test different things.
        assertTrue(handsBackAStatusToken(probe.methods.single { it.name == "getStatus" }))
        // Neither half of the rule on its own: a status-shaped name that hands back no token, and
        // a token-shaped return under a name that claims nothing about status.
        assertFalse(handsBackAStatusToken(probe.methods.single { it.name == "getStatusCode" }))
        assertFalse(handsBackAStatusToken(probe.methods.single { it.name == "getProvider" }))
    }

    /** A probe, not a fixture: four shapes §11.1 forbids, and two it permits. */
    @Suppress("unused")
    private class StatusTokenProbe {
        fun getStatuses(): List<String> = emptyList()
        fun getStateByOrder(): Map<String, String> = emptyMap()
        fun getNestedStatuses(): List<List<String>> = emptyList()
        fun getStatus(): String = ""
        fun getStatusCode(): Int = 0
        fun getProvider(): String = ""
    }

    /**
     * Exact `typeName` equality, and the one place in this file where that is the rule rather than
     * a leftover.
     *
     * §7.5's `SignedTerms` and §10.3's `DeliverableTags` hold their operands as whole tag arrays —
     * `List<String>` — precisely so that §7.6's and §10.3's comparisons run over the bytes both
     * messages carried, and the pinned list above says so for `DeliverableTags` in as many words.
     * Matching the `String` *token* here would pull `SignedTerms.<init>` onto the list and make the
     * assertion state a different rule from the one its own KDoc explains (STOP RULE 4). So this
     * sweep is exact by design; there is no probe for it and none is missing.
     */
    @Test
    fun `every published String parameter is on the pinned list`() {
        val found = mutableSetOf<String>()
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                if (parameterMentions(executable).any { it == String::class.java.name }) {
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
