package dev.eryalabs.nenya.envelope

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §7.1's and §7.2's structural rules over the envelope package, enforced by reflection rather than
 * by review — the sweep that keeps `GiftWrap.seal` and `GiftWrap.open` honest after the tasks that
 * wrote them are checked off.
 *
 * The sweep is the one `PaymentStructureTest` specifies, over `dev/eryalabs/nenya/envelope`, and
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
 *   [GiftWrap], and pass with the implementation entirely absent. [classesIn] fails with the
 *   **absolute** path of every directory it did look at, so that failure reads as what it is rather
 *   than as a missing class, and the control below proves it by asking for a marker no output tree
 *   has.
 * - **Classes asserted by name.** A count is satisfied by whatever the wrong classpath entry
 *   happened to contain, which is exactly the failure the plural form exists to avoid.
 *
 * ### Every type inspection here walks the generic signature
 *
 * `getReturnType()` and `getParameterTypes()` are **erasures**, so a published
 * `fun verified(): List<Boolean>` reports as plain `List` and satisfies an erased-type check while
 * handing a caller exactly the bare verdict §7.2 and §17 forbid. Every type-inspecting assertion
 * below goes through [mentions], which reads `genericParameterTypes`/`genericReturnType` and matches
 * on `typeName` — and the Boolean control below feeds it a `List<Boolean>` that only the generic
 * form catches.
 *
 * ### The predicates are shared with the probes, not re-stated for them
 *
 * Three rules here are rules about an **absence**, and an absence is what a sweep with a broken
 * predicate also reports. So each is one function — [throwawayKeyNameIn], [booleanIn],
 * [unreachableImplementationProblem] — run over the real package in one test and over a deliberately
 * broken probe class in the test tree in another. A probe is not a fixture: it exists so the sweep
 * has something it is *required* to catch.
 *
 * The three pinned sets — [STRING_PARAMETERS_PERMITTED], [BYTE_ARRAY_PARAMETERS_PERMITTED] and
 * [TEXT_RETURNED_BY_RESULT_TYPES] — get no probe and need none: they are set **equalities**, so they
 * fail on a stale entry exactly as they do on a new one, and a sweep that stopped seeing the package
 * turns them red rather than green. Each carries its own `inspected` floor besides.
 */
class EnvelopeStructureTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.envelope"

        /** The classes this sweep is *about*, by name rather than by count. */
        val EXPECTED_BY_NAME: Set<String> = setOf(
            "GiftWrap",
            "SealedMessage",
            "OutgoingWrap",
            "OpenedMessage",
            "SignatureCheck",
            "EnvelopeSide",
            "EnvelopeRejection",
            "EnvelopeException",
            "EnvelopeLimits",
        )

        /**
         * The class files this package compiled to when this sweep was written, pinned as a floor
         * so a partial output directory goes red instead of sweeping the remainder. A floor is only
         * ever raised, and only in the commit that deliberately removes a class.
         */
        const val MIN_MAIN_CLASSES: Int = 18

        /**
         * §7.2: "The gift wrap's `pubkey` is random and carries **no** identity. Implementations
         * MUST NOT display it, index by it, or use it in any comparison."
         *
         * Deliberately narrower than `ChannelStructureTest`'s bare `wrap|ephemeral`, because this is
         * the package whose whole subject *is* the wrap: [OutgoingWrap], [GiftWrap.seal] and
         * [OpenedMessage.wrapId] are all names §7.1 requires it to keep. What §7.2 forbids is the
         * **key**, so the pattern is the pairing — a wrap, a throwaway or an ephemeral something
         * ending in `key`, and the same pair the other way round — which is why
         * `dev.eryalabs.nenya.seam.EphemeralSigners`, a *signer* the client plugs in and never a
         * key, is correctly not matched, and why `wrapId` is not either.
         */
        val THROWAWAY_KEY_NAMED = Regex(
            """(?i)(wrap|throwaway|ephemeral|onetime|oneshot)[a-z]*key|key[a-z]*(wrap|throwaway|ephemeral)""",
        )

        /**
         * §7.1's three result types, and the only members of them that may hand a caller text.
         *
         * [THROWAWAY_KEY_NAMED] alone is not enough, and the hole is worth naming because the
         * mutation T34 chose happens to fall on the side it catches. That pattern needs the prefix
         * word and `key` in one identifier, so `OutgoingWrap.wrapPubkey` is caught and
         * `OutgoingWrap.pubkey` — the **nostr field name**, and the likelier spelling of "so a test
         * can assert the two copies differ" — is not: the `.` in the label stops `[a-z]*` bridging
         * from the class name to the member's. Nor can a type check close it, because the throwaway
         * key is §4.3 hex and so is every legitimate key on this surface: a `String` accessor is a
         * `String` accessor.
         *
         * So the "returns" half of §7.2's rule is pinned rather than pattern-matched. Every
         * published member of [OutgoingWrap], [SealedMessage] and [OpenedMessage] whose **generic**
         * signature mentions a `String` anywhere — bare, or inside a `List<String>` — must be on
         * this list, and there are exactly two. Anything else is a new door for the one value §7.2
         * says MUST NOT be displayed, indexed by, or used in any comparison, and a human has to say
         * why it is not.
         *
         * [OutgoingWrap.json] is on the list and is the honest edge of the claim: the wrap's own
         * `pubkey` field is in those bytes, because §7.1 step 3 signs the wrap with the throwaway
         * key and the relay has to receive it. What the shape buys is that reading it out is a thing
         * a client does on purpose, with a JSON parse, rather than a thing the API hands it.
         */
        val TEXT_RETURNED_BY_RESULT_TYPES: Set<String> = setOf(
            // §7.1's object form of the signed `kind:1059`, ready to hand to a relay transport.
            "OutgoingWrap.getJson",
            // The key this copy is encrypted and addressed to: the recipient's on `toRecipient` and
            // the sender's own on `toSelf`, both in §4.3 hex, neither of them the throwaway key.
            "OutgoingWrap.getAddressee",
        )

        /**
         * §7.1's three result types, by name — and a fourth does **not** join the pin above on its
         * own, which is stated rather than implied because nothing here makes it happen.
         *
         * [EXPECTED_BY_NAME] is a containment check and [MIN_MAIN_CLASSES] is a floor, so a new
         * `sealed interface ReopenedMessage` compiles, passes both, and is swept by the name, the
         * parameter and the Boolean assertions — but not by the returns pin, because that pin is
         * a set of *members* and it would have no member in it. Deriving the set instead (every
         * public sealed interface in the package, say) would make the pin close itself, and would
         * also silently widen [TEXT_RETURNED_BY_RESULT_TYPES]'s meaning the day somebody adds a
         * sealed interface that is not a §7.1 result. So it is a human's job, and this is the note
         * telling the next task it is theirs.
         */
        val RESULT_TYPES: Set<String> = setOf("OutgoingWrap", "SealedMessage", "OpenedMessage")

        /**
         * `String` anywhere in a `typeName`, including inside a generic's arguments.
         *
         * `\b` after `String` is what keeps `java.lang.StringBuilder` from matching: `g` and `B` are
         * both word characters, so there is no boundary between them.
         */
        val STRING_TYPE = Regex("""\bjava\.lang\.String\b""")

        /** `ByteArray` anywhere in a `typeName` — bare `byte[]`, or `Pair<String, byte[]>`. */
        val BYTE_ARRAY_TYPE = Regex("""\bbyte\[]""")

        /** §17's forbidden verdict shape, primitive and boxed, as each appears in a `typeName`. */
        val BOOLEAN = Regex("""\b(boolean|java\.lang\.Boolean)\b""")

        /** `Any`'s three, which every class declares and none of which answers anything §7.1 asks. */
        val OBJECT_METHODS: Set<String> = setOf("toString", "equals", "hashCode")

        /**
         * Every place on the published surface where a `String` may be a parameter, pinned by name.
         *
         * A new one turns this red and forces a human to say why it is not a key, a ciphertext or a
         * counterparty's claim arriving somewhere §7.1 does not put one. Each entry below is either
         * §4.3 hex the caller resolved, an opaque wire string, a diagnostic message, or a
         * compiler-generated enum lookup.
         *
         * The assertion is set **equality** in both directions, so the list gets no weaker as it
         * grows: a stale entry turns it red exactly as a new one does.
         *
         * ### Kotlin's `internal` is JVM-public, and this list is where that becomes visible
         *
         * `GiftWrap.OneShotSigner` is declared `internal`, which the Kotlin compiler enforces at
         * compile time and the JVM does not enforce at all: the class file carries `ACC_PUBLIC`, so
         * [publishedClasses] sees it and a Java caller on the same classloader can name it. The
         * three entries it contributes are listed rather than filtered out, because "filter out the
         * ones Kotlin calls internal" is a filter this sweep cannot implement without
         * `kotlin-reflect` (STOP RULE 11) and would be a hole the day somebody made something
         * internal to get it past this list.
         */
        val STRING_PARAMETERS_PERMITTED: Set<String> = setOf(
            // §7.1's write procedure: the recipient's key in §4.3 hex, the one thing `seal` is told
            // about the counterparty. Everything else it needs it asks a seam for.
            "GiftWrap.seal",
            // §7.1's read procedure: one gift wrap as a relay served it. Hostile input by
            // definition, parsed by `EventJson.read` under §7.1's object rules and never trusted.
            "GiftWrap.open",
            // The human-readable rejection message, authored in this library and echoing no input.
            "EnvelopeException.<init>",
            "EnvelopeSide.valueOf",      // generated by the Kotlin compiler for enums
            "EnvelopeRejection.valueOf", // ditto
            "SignatureCheck.valueOf",    // ditto
            // `GiftWrap.OneShotSigner`, which is Kotlin-`internal` and JVM-public; see above. Each
            // of the three is an override of the `Signer` contract with the same parameters that
            // contract already fixes — §4.1's canonical serialisation, a counterparty key in §4.3
            // hex, a plaintext, a NIP-44 payload — passed through to the injected signer after a
            // reuse check, and nothing here is a key of this library's own. The wrapper only ever
            // *restricts*: one signature, one encryption, and a decryption that always refuses,
            // because §7.1 step 3 gives every gift wrap its own keypair.
            "GiftWrap\$OneShotSigner.signEvent",
            "GiftWrap\$OneShotSigner.nip44Encrypt",
            "GiftWrap\$OneShotSigner.nip44Decrypt",
        )

        /**
         * Every place on the published surface where a `ByteArray` may be a parameter — and it is
         * **empty**, which is the strongest form this claim has.
         *
         * §12 item 11 and §3 both say secret key material must not cross this library's surface, and
         * §7.1's envelope is where a seal's key, a throwaway key and a NIP-44 conversation key all
         * pass through. None of them arrives here: the client's key lives behind [Signer] and the
         * throwaway keys behind `EphemeralSigners`, so no published member of this package takes
         * bytes at all. An empty pinned set makes any first one a deliberate, reviewed addition
         * rather than a signature nobody looked twice at.
         */
        val BYTE_ARRAY_PARAMETERS_PERMITTED: Set<String> = emptySet()

        /**
         * Every class Gradle wrote for this package from `src/commonMain/kotlin` and
         * `src/jvmMain/kotlin`.
         */
        fun mainClasses(): List<Class<*>> = classesIn("/classes/kotlin/jvm/main/", MIN_MAIN_CLASSES)

        /**
         * The classes in the output directory [marker] selects, or a loud failure naming — in
         * absolute form — every directory this sweep actually looked at.
         *
         * Absolute, because the two ways this goes wrong are a build that never ran and a source
         * layout that moved, and a relative path distinguishes neither.
         */
        fun classesIn(marker: String, pinned: Int): List<Class<*>> {
            val loader = EnvelopeStructureTest::class.java.classLoader
            val urls = loader.getResources(PACKAGE.replace('.', '/')).toList()
            val chosen = urls.firstOrNull { it.path.contains(marker) }
                ?: fail(
                    "no $marker directory for $PACKAGE. The directories on this test's classpath " +
                        "are ${urls.map(::describe)} — none of them is the one asked for, so the " +
                        "sweep would otherwise enumerate the test output tree and pass with the " +
                        "implementation entirely absent.",
                )
            val directory = File(chosen.toURI())
            val files = directory.listFiles { file: File -> file.name.endsWith(".class") }
                ?: fail("${directory.absolutePath} is not a readable directory")
            assertTrue(files.isNotEmpty(), "${directory.absolutePath} holds no classes")
            assertTrue(
                files.size >= pinned,
                "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were " +
                    "pinned. A sweep over a partial output directory passes while checking less " +
                    "than it claims.",
            )
            return files.sortedBy { it.name }
                .map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
        }

        /** A classpath entry as a human can check it: an absolute path where there is one. */
        fun describe(url: URL): String =
            if (url.protocol == "file") File(url.toURI()).absolutePath else url.toString()

        /** Every directory the classloader resolves for this package, in absolute form. */
        fun directoriesLookedAt(): List<String> =
            EnvelopeStructureTest::class.java.classLoader
                .getResources(PACKAGE.replace('.', '/')).toList()
                .filter { it.protocol == "file" }
                .map { File(it.toURI()).absolutePath }

        fun publishedClasses(): List<Class<*>> =
            mainClasses().filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

        /** Constructors and own public methods, minus everything a client cannot name. */
        fun publishedExecutables(type: Class<*>): List<Executable> =
            type.constructors.toList() +
                type.methods.filter {
                    it.declaringClass == type && !it.isSynthetic && !it.isBridge && '$' !in it.name
                }

        /**
         * The name a human reads. Kotlin mangles a public function whose parameter list contains an
         * inline class, so the hash is stripped — or every label here would change the day a
         * parameter type did.
         */
        fun label(type: Class<*>, executable: Executable): String {
            val simple = type.name.removePrefix("$PACKAGE.")
            val member = when (executable) {
                is Constructor<*> -> "<init>"
                else -> executable.name.substringBefore('-')
            }
            return "$simple.$member"
        }

        /**
         * The **generic** type names [executable] mentions — parameters and return alike.
         *
         * `typeName` prints the parameterised form, `java.util.List<java.lang.Boolean>`, so a match
         * over it sees what an erased-type check cannot.
         */
        fun mentions(executable: Executable): List<String> =
            (
                executable.genericParameterTypes.toList() +
                    listOfNotNull((executable as? Method)?.genericReturnType)
                ).map { it.typeName }

        /** A member's own label and every type it mentions: everything this member *names*. */
        fun namesOf(type: Class<*>, executable: Executable): List<String> =
            listOf(label(type, executable)) + mentions(executable).map { it.removePrefix("$PACKAGE.") }

        /** The first of [names] that says it is the gift wrap's throwaway key, or `null`. */
        fun throwawayKeyNameIn(names: List<String>): String? =
            names.firstOrNull { THROWAWAY_KEY_NAMED.containsMatchIn(it) }

        /** Whether [executable]'s generic signature mentions [type] anywhere, arguments included. */
        fun signatureMentions(executable: Executable, type: Regex): Boolean =
            mentions(executable).any { type.containsMatchIn(it) }

        /** As [signatureMentions], but over the parameters only — what a caller can push in. */
        fun parametersMention(executable: Executable, type: Regex): Boolean =
            executable.genericParameterTypes.any { type.containsMatchIn(it.typeName) }

        /** The first type in [executable]'s generic signature that is a bare verdict, or `null`. */
        fun booleanIn(executable: Executable): String? =
            mentions(executable).firstOrNull { BOOLEAN.containsMatchIn(it) }

        /**
         * `null` when [evidence] cannot be forged or implemented from outside this package, and a
         * sentence naming the failure otherwise.
         *
         * Four rules, in the order they can go wrong. The first three are true of *any* interface,
         * which is why the fourth exists: the sweep must find a real implementation, and every
         * implementing **class** must be non-public so no client can construct one, while every
         * implementing **interface** must itself be `sealed` so no client can implement one either.
         *
         * [candidates] is a parameter rather than a call to [mainClasses] so the probes below go
         * through this same function. A predicate proved only against the code it passes on is a
         * predicate nobody has seen fail.
         */
        fun unreachableImplementationProblem(evidence: Class<*>, candidates: List<Class<*>>): String? {
            if (!evidence.isInterface) {
                return "${evidence.name} is not an interface, so it has a constructor for the " +
                    "compiler to synthesise a public accessor for"
            }
            if (evidence.constructors.isNotEmpty()) {
                return "${evidence.name} publishes ${evidence.constructors.size} constructor(s)"
            }
            if (evidence.declaredConstructors.any { it.isSynthetic }) {
                return "${evidence.name} has a synthetic constructor, which a Java client can call " +
                    "with a null DefaultConstructorMarker"
            }
            if (!evidence.isSealed) {
                return "${evidence.name} is not sealed, so another module can implement it and mint " +
                    "a value §7.1's procedure never produced"
            }
            val implementations = candidates.filter { it != evidence && evidence.isAssignableFrom(it) }
            if (implementations.isEmpty()) {
                return "no implementation of ${evidence.name} was found, so the rules above are " +
                    "true of any interface at all and this proves nothing"
            }
            for (implementation in implementations) {
                if (implementation.isInterface) {
                    if (!implementation.isSealed) {
                        return "${implementation.name} refines ${evidence.simpleName} and is not " +
                            "sealed, so another module can implement it"
                    }
                } else if (Modifier.isPublic(implementation.modifiers)) {
                    return "${implementation.name} implements ${evidence.simpleName} and is " +
                        "JVM-public, so a client can construct one §7.1's procedure never ran"
                }
            }
            return null
        }
    }

    // -----------------------------------------------------------------------------------------
    // The sweep is looking at the envelope package, and says so if it is not.
    // -----------------------------------------------------------------------------------------

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

    /**
     * The failure mode this whole file is built around, proved able to fire: ask for a marker no
     * output tree has, and the refusal must name — in absolute form — every directory it did read.
     *
     * Without this, "no main URL found" is a branch nobody has ever taken, and the day the source
     * layout moves it is the branch that decides whether a human sees a missing output tree or a
     * missing class.
     */
    @Test
    fun `a missing main output directory fails with the absolute paths it looked at`() {
        val looked = directoriesLookedAt()
        assertTrue(
            looked.isNotEmpty(),
            "the classloader resolved no file directory for $PACKAGE, so this control cannot check " +
                "what the refusal names",
        )

        val thrown = assertFailsWith<AssertionError> {
            classesIn("/classes/kotlin/jvm/no-such-tree/", MIN_MAIN_CLASSES)
        }

        val message = thrown.message ?: fail("the refusal carried no message at all")
        for (directory in looked) {
            assertTrue(
                directory in message,
                "the refusal must name $directory, in absolute form, so a human can see which " +
                    "output tree the sweep landed on: $message",
            )
        }
        // Containment alone would still hold if `describe` regressed to `url.toString()`, because
        // `file:/home/…/envelope` contains the absolute path inside it. This is the half that
        // discriminates: a path, not a URL, is what a human pastes into `ls`.
        assertFalse(
            "file:" in message,
            "the refusal renders classpath entries as URLs rather than as paths: $message",
        )
    }

    // -----------------------------------------------------------------------------------------
    // §7.2's throwaway key, as an absence.
    // -----------------------------------------------------------------------------------------

    /**
     * §7.2's prohibition made structural: nothing a client can reach in this package returns or is
     * named after the gift wrap's throwaway pubkey.
     *
     * The key that signed a wrap is reachable only by parsing [OutgoingWrap.json], which is a thing
     * a client does on purpose rather than a thing it is handed. A rule expressed as an absence
     * needs a test that fails when the absence ends — which is the day somebody adds
     * `OutgoingWrap.wrapPubkey` "so the tests can assert the two copies differ".
     */
    @Test
    fun `nothing published here returns or names the gift wrap's throwaway key`() {
        val published = publishedClasses()
        val found = published.map { it.name.removePrefix("$PACKAGE.") }.toSet()
        assertTrue(
            EXPECTED_BY_NAME.all { it in found },
            "this sweep must be looking at $EXPECTED_BY_NAME, not at whatever classpath entry it " +
                "happened to land on; it found $found",
        )

        var inspected = 0
        for (type in published) {
            assertNull(
                throwawayKeyNameIn(listOf(type.name.removePrefix("$PACKAGE."))),
                "${type.name} is named after the key §7.2 forbids displaying, indexing by or " +
                    "comparing",
            )
            for (executable in publishedExecutables(type)) {
                inspected++
                assertNull(
                    throwawayKeyNameIn(namesOf(type, executable)),
                    "${label(type, executable)} names the gift wrap's throwaway key: " +
                        "${namesOf(type, executable)}. §7.2: it is random, it carries no identity, " +
                        "and implementations MUST NOT display it, index by it, or use it in any " +
                        "comparison.",
                )
            }
        }
        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * The other half of §7.2's rule — **returns**, not just names — pinned rather than
     * pattern-matched, because no pattern and no type check can close it.
     *
     * See [TEXT_RETURNED_BY_RESULT_TYPES] for why: `OutgoingWrap.pubkey` is the nostr field name,
     * the likeliest way the throwaway key ever gets published, and [THROWAWAY_KEY_NAMED] does not
     * catch it. A type check cannot either — the throwaway key is §4.3 hex and so is `addressee`.
     * What is left is to pin every member of §7.1's three result types that hands a caller text, and
     * there are two.
     */
    @Test
    fun `every published member of §7·1's result types that returns text is on the pinned list`() {
        val classes = mainClasses()
        val types = RESULT_TYPES.map { name ->
            classes.single { it.name == "$PACKAGE.$name" }
        }

        val found = mutableSetOf<String>()
        var inspected = 0
        for (type in types) {
            for (executable in publishedExecutables(type)) {
                if (executable is Method && executable.name in OBJECT_METHODS) continue
                inspected++
                if (signatureMentions(executable, STRING_TYPE)) found += label(type, executable)
            }
        }

        assertTrue(
            inspected > 5,
            "the sweep inspected only $inspected members across $RESULT_TYPES, which is not the " +
                "three types §7.1's two procedures produce",
        )
        assertEquals(
            TEXT_RETURNED_BY_RESULT_TYPES,
            found,
            "the set of members of ${RESULT_TYPES.sorted()} whose signature mentions a String must " +
                "match the pinned list exactly. §7.2: the gift wrap's `pubkey` is random, carries " +
                "no identity, and MUST NOT be displayed, indexed by, or used in any comparison — " +
                "and a new String accessor on one of these types is the shape it would arrive in, " +
                "whatever it is called. A missing entry means the sweep stopped seeing the package.",
        )
    }

    /**
     * The control that proves [throwawayKeyNameIn] discriminates, and the mutation T34 names: add
     * `fun wrapPubkey(): String` and the sweep must turn red — while [OpenedMessage.wrapId], a name
     * §7.1 requires this package to keep, must not.
     */
    @Test
    fun `the throwaway-key sweep catches a wrapPubkey accessor and leaves wrapId alone`() {
        val probe = ForbiddenNameProbe::class.java

        for (name in listOf("wrapPubkey", "throwawayKey", "ephemeralPublicKey", "keyOfWrap")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                throwawayKeyNameIn(namesOf(probe, method)),
                "ForbiddenNameProbe.$name is the accessor §7.2 forbids and the sweep missed it",
            )
        }
        for (name in listOf("wrapId", "addressee")) {
            val method = probe.methods.single { it.name == name }
            assertNull(
                throwawayKeyNameIn(namesOf(probe, method)),
                "ForbiddenNameProbe.$name is a name §7.1 requires this package to keep, and a " +
                    "pattern that flags it would be turned off rather than obeyed",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two results of §7.1, unforgeable through the published API.
    // -----------------------------------------------------------------------------------------

    /**
     * [SealedMessage], [OpenedMessage] and [OutgoingWrap] each have no constructor a client can
     * reach and no public implementing class.
     *
     * The trap the payment, delivery and channel packages were each pulled out of: the factory idiom
     * protects nothing on its own, because a `private constructor` plus a companion factory emits a
     * **public synthetic** constructor with a trailing `DefaultConstructorMarker` that a Java client
     * can call with `null`. A public `sealed interface` whose implementations are `private` classes
     * has no constructor to synthesise an accessor for at all. The claim is "unforgeable **through
     * the published API**", not unforgeable full stop: a `private` nested class compiles to a
     * package-private JVM class with a public constructor, so a client that declares itself into
     * this package on the same classloader can still reach it — and Nenya protects its user against
     * counterparties and relays, not against the client embedding it.
     */
    @Test
    fun `nothing a client can reach constructs a sealed, opened or outgoing message`() {
        val classes = mainClasses()

        for (name in listOf("SealedMessage", "OpenedMessage", "OutgoingWrap")) {
            val evidence = classes.single { it.name == "$PACKAGE.$name" }
            assertNull(
                unreachableImplementationProblem(evidence, classes),
                "$name is forgeable: ${unreachableImplementationProblem(evidence, classes)}. It is " +
                    "what §7.1's procedure produced, and a client that can mint one holds a message " +
                    "no seal ever covered.",
            )
        }
    }

    /**
     * The control that proves [unreachableImplementationProblem] discriminates, and the second
     * probe T34 names: a public class implementing the evidence type must be caught, and the same
     * type with only an unreachable implementation must not be.
     *
     * The probes are fed to the same function the test above runs, rather than to a restatement of
     * it — the whole point of a rule about an absence.
     */
    @Test
    fun `the implementation sweep catches a public implementation and passes an unreachable one`() {
        val evidence = ProbeEvidence::class.java

        val problem = unreachableImplementationProblem(
            evidence,
            listOf(evidence, PublicProbeImplementation::class.java),
        )
        assertNotNull(problem, "a public implementing class with a public constructor must be caught")
        assertTrue(
            "PublicProbeImplementation" in problem,
            "the refusal must name what it caught: $problem",
        )
        assertTrue(
            Modifier.isPublic(PublicProbeImplementation::class.java.modifiers) &&
                PublicProbeImplementation::class.java.constructors.isNotEmpty(),
            "the probe must actually be public and constructible, or it is not the shape this " +
                "sweep is about",
        )

        assertNull(
            unreachableImplementationProblem(
                evidence,
                listOf(evidence, unreachableProbeImplementation()),
            ),
            "an implementation a client cannot construct must pass, or the assertion above is a " +
                "predicate that refuses everything",
        )
    }

    // -----------------------------------------------------------------------------------------
    // What may cross the surface: strings, bytes, and never a bare verdict.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every published String parameter is on the pinned list`() {
        val found = mutableSetOf<String>()
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                // Generic-aware: a `fun keys(names: List<String>)` reads
                // `java.util.List<java.lang.String>`, and an equality check on the top-level name
                // would let it past the one list that exists to make a human look.
                if (parametersMention(executable, STRING_TYPE)) found += label(type, executable)
            }
        }

        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
        assertEquals(
            STRING_PARAMETERS_PERMITTED,
            found,
            "the set of published members taking a String must match the pinned list exactly. A new " +
                "entry may be a key, a ciphertext or a counterparty's claim arriving where §7.1 " +
                "does not put one; a missing entry means the sweep stopped seeing the package.",
        )
    }

    /**
     * The secret-key boundary, made structural. §3 and §12 item 11 both say key material must not
     * cross this library's surface, and §7.1's envelope is where a seal's key, a throwaway key and a
     * NIP-44 conversation key would each be tempting to pass.
     */
    @Test
    fun `every published ByteArray parameter is on the pinned list, and the list is empty`() {
        val found = mutableSetOf<String>()
        var inspected = 0
        for (type in publishedClasses()) {
            for (executable in publishedExecutables(type)) {
                inspected++
                // Generic-aware for the same reason, and it matters more here: this list's whole
                // strength is that it is empty, so a `Pair<String, ByteArray>` slipping past the
                // top-level name would be the one hole nobody would be asked to look at.
                if (parametersMention(executable, BYTE_ARRAY_TYPE)) found += label(type, executable)
            }
        }

        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
        assertEquals(
            BYTE_ARRAY_PARAMETERS_PERMITTED,
            found,
            "no published member of the envelope package takes bytes: the client's key lives behind " +
                "Signer and the throwaway keys behind EphemeralSigners, so a first ByteArray here " +
                "is the one boundary §3 says key material must never cross.",
        )
    }

    /**
     * §17 and §7.2's shared rule, one layer down from where `SignatureCheck` states it: no published
     * member of this package hands back — or can be handed — a bare `Boolean`.
     *
     * §17 requires "an implementation's UI MUST distinguish *signature verified* from *decrypted and
     * structurally valid*", and §7.2 adds that an implementation verifying for some messages and not
     * others MUST distinguish them, "because a single label covering both claims the stronger
     * property for the weaker case". A `Boolean verified` reads `false` for both "checked and bad" —
     * which produces no [OpenedMessage] at all — and "never checked", which is exactly the
     * conflation both sections forbid; [SignatureCheck] and `notPerformedHere` are the two shapes
     * that keep them apart. A `Boolean` **parameter** is the same failure from the other side: a
     * §7.1 procedure that can be *told* whether a signature verified is not performing the check.
     */
    @Test
    fun `no published member of the envelope package answers with or accepts a Boolean`() {
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
                // `Any`'s three are declared by the compiler on data classes and answer nothing
                // §7.1 asks; `equals` is the one that would otherwise put a boolean on every list.
                if (executable is Method && executable.name in OBJECT_METHODS) continue
                inspected++
                assertNull(
                    booleanIn(executable),
                    "${label(type, executable)} mentions ${booleanIn(executable)}. §17 and §7.2: a " +
                        "single Boolean cannot distinguish *signature verified* from *decrypted and " +
                        "structurally valid*, and a procedure that can be told the answer is not " +
                        "performing the check.",
                )
            }
        }
        assertTrue(inspected > 10, "the sweep inspected only $inspected members, which is not the package")
    }

    /**
     * The control that proves the Boolean sweep is not erasure-blind, and the third probe T34 names:
     * a bare `boolean`, a `List<Boolean>`, a `Map<String, Boolean>` and a `Boolean` parameter must
     * all be caught, while a member answering with [SignatureCheck] must not be.
     *
     * `List<Boolean>` is the one that matters: `getReturnType()` reports plain `List` for it, so a
     * sweep reading the erasure passes over exactly the shape §17 forbids, wrapped in a collection.
     */
    @Test
    fun `the Boolean sweep catches a verdict hidden inside a generic type`() {
        val probe = BooleanProbe::class.java

        for (name in listOf("bare", "wrapped", "keyed", "told")) {
            val method = probe.methods.single { it.name == name }
            assertNotNull(
                booleanIn(method),
                "BooleanProbe.$name is a bare verdict and the sweep missed it: it mentions " +
                    "${mentions(method)}, and an erased-type check sees only List and Map there",
            )
        }
        val clean = probe.methods.single { it.name == "checked" }
        assertNull(booleanIn(clean), "an answer that is a SignatureCheck must not be flagged")
    }

    // -----------------------------------------------------------------------------------------
    // The probes: not fixtures, but shapes the predicates above are required to catch.
    // -----------------------------------------------------------------------------------------

    /**
     * The accessor §7.2 forbids, in four spellings, beside two names §7.1 requires kept.
     *
     * Named `ForbiddenNameProbe` and not `ThrowawayKeyProbe` on purpose: [namesOf] includes the
     * member's own label, which carries the declaring class, so a probe named after the thing the
     * pattern matches would match through **every** one of its members and the two negative controls
     * below would be reporting the class name rather than the method name.
     */
    @Suppress("unused")
    class ForbiddenNameProbe {
        fun wrapPubkey(): String = ""
        fun throwawayKey(): String = ""
        fun ephemeralPublicKey(): String = ""
        fun keyOfWrap(): String = ""
        fun wrapId(): String = ""
        fun addressee(): String = ""
    }

    /** A stand-in for [SealedMessage]: sealed, constructor-free, and implemented two ways below. */
    sealed interface ProbeEvidence

    /** What §7.1's results must never be: public, and constructible by anyone. */
    class PublicProbeImplementation : ProbeEvidence

    /** What they are: package-private on the JVM, so no client can name it. */
    private class UnreachableProbeImplementation : ProbeEvidence

    /** Four shapes §17 and §7.2 forbid, and one they require. */
    @Suppress("unused")
    class BooleanProbe {
        fun bare(): Boolean = false
        fun wrapped(): List<Boolean> = emptyList()
        fun keyed(): Map<String, Boolean> = emptyMap()
        fun told(verified: Boolean): Int = if (verified) 1 else 0
        fun checked(): SignatureCheck = SignatureCheck.NOT_CHECKED
    }

    /** Reached through a function so the `private` class is used and the compiler keeps it. */
    private fun unreachableProbeImplementation(): Class<*> =
        UnreachableProbeImplementation::class.java
}
