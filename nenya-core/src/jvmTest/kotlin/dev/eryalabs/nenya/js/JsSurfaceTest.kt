package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.conformance.MainClasses
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import java.lang.reflect.Executable
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rule the compiler does not enforce: **no Kotlin type crosses this boundary.**
 *
 * ### Why this test has to exist
 *
 * `NON_EXPORTABLE_TYPE` is a **warning**, never an error. A `@JsExport` function taking a `Long`, an
 * `Msat` or a sealed type compiles green and exports it to JavaScript as `any` — so decision **P**'s
 * translation rules are held by this sweep and by nothing else. The warning is read once, by whoever
 * wrote the line; this runs on every commit.
 *
 * A value class over a `Long` reflects as the primitive `long` on the JVM, which is why banning `long`
 * catches `Msat` as well as a bare timestamp, and why the ban is written against the **generic** type
 * name rather than against an erased `Class`: a published `List<Long>` erases to `List` and would walk
 * straight past a comparison with `Long::class.java`. [FORBIDDEN] is matched over `typeName`, and
 * [ForbiddenTypeProbe] below is what proves the matcher is not erasure-blind.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest` so they run on JavaScript too, and the three other
 * files T42 adds are there. This one cannot be: it is `java.lang.reflect` over `build/classes/kotlin/
 * jvm/main`, which is exactly the exception that rule names. It is a sweep over the *compiled shape* of
 * the facade, and the thing it is about — a signature nobody noticed — is visible from either target.
 * `JsFacadeEqualityTest`, `JsCrossingTest` and `JsSeamsTest` carry the behaviour, on both.
 *
 * `kotlin-reflect` is not on any classpath and STOP RULE 11 forbids adding one, so `internal` cannot be
 * told from `public` by reflection: both are `public` in the class file. That is why the classification
 * below is by name. The union assertion is what keeps it honest — a class added to this package turns
 * this red until somebody says which side of the boundary it is on.
 */
class JsSurfaceTest {

    private companion object {

        const val PACKAGE: String = "dev.eryalabs.nenya.js"

        /**
         * The types a page constructs: every one declares a `public constructor`, so both their
         * constructors and their methods cross and both are swept.
         */
        val CROSSED_IN: Set<String> = setOf(
            "JsAuthoredListing",
            "JsRumorEnvelope",
            "JsOrderTerms",
            "JsCommitment",
            "JsRelease",
            "JsSigner",
            "JsEnvironment",
            "JsDecryption",
            // T43's four: §11.2's machine configuration and its event union, §9.2's store seam,
            // and §8.4's sighting.
            "JsOrderMachine",
            "JsOrderEvent",
            "JsPaymentRequestStore",
            "JsFeeTermSighting",
        )

        /**
         * The types a page only ever *reads*: every one declares an `internal constructor`, so only
         * their methods cross.
         *
         * Their constructors are excluded rather than swept, and the exclusion is the one place this
         * file takes something on trust. Kotlin cannot mangle a constructor's name the way it mangles an
         * `internal` function's, so `JsWireEvent(event: WireEvent)` is a `public` constructor in the
         * class file and would fail the sweep — while being unreachable from JavaScript, because
         * Kotlin/JS exports no `internal` declaration. The `internal` keyword on each is checkable by
         * reading eight lines of source; what is *not* checkable by reading is a getter, which is why
         * every method of these types is swept and why the field sweeps further down exist.
         */
        val CROSSED_OUT: Set<String> = setOf(
            "JsSeamAnswer",
            "JsWireEvent",
            "JsText",
            "JsBuildResult",
            "JsListing",
            "JsBid",
            "JsListingResult",
            "JsBidResult",
            // T43's ten. Six are the answers §7.1, §9.2 and §11.2 produce; four are **handles** —
            // `JsRumor`, `JsSettlement`, `JsAcceptedRequest` and `JsOrder` each hold a Kotlin value
            // whose constructor is `internal` precisely so the library's own door is the only way
            // to one, and a page passes them back as arguments without reading them apart. Their
            // `internal val` accessors are mangled with the module name and so are dropped by
            // `MainClasses.methods`, which is why only their published getters reach the sweep.
            "JsOutgoingWrap",
            "JsSealResult",
            "JsRumor",
            "JsOpenResult",
            "JsSettlement",
            "JsSettlementResult",
            "JsAcceptedRequest",
            "JsOrder",
            "JsOrderResult",
            "JsOrderOutcome",
        )

        /**
         * **Every** file class the package compiles to. All four are swept, and that is a fix.
         *
         * The first version of this test swept only the two files holding entry points and listed
         * `JsBoundaryKt` and `JsSeamsKt` as "does not cross", on the strength of a comment saying every
         * top-level declaration in them was `internal` or `private`. The reviewer disproved it in one
         * move: appending an `@JsExport public fun jsLeakedEntryPoint(amount: Long): Long` to
         * `JsSeams.kt` left all 50 tests green — a new entry point taking and returning a bare `Long`,
         * with no equality proof, no refusal-by-value proof and no forbidden-type sweep, in the one
         * guard T42 names as the enforcer of its central rule. A file class that is not enumerated is a
         * file anybody can add an export to.
         */
        val FILE_CLASSES: Set<String> = setOf(
            "JsBoundaryKt",
            "JsListingEntriesKt",
            "JsChannelEntriesKt",
            "JsSeamsKt",
            "JsEnvelopeEntriesKt",
            "JsSettlementEntriesKt",
            "JsOrderEntriesKt",
        )

        /**
         * Every top-level function in [FILE_CLASSES] that is **not** an entry point, with the number of
         * overloads each has.
         *
         * ### Why a list, and why the counts
         *
         * Kotlin mangles an `internal` **member** function's name with the module name, which
         * `MainClasses.methods` already drops — but it does **not** mangle an `internal` *top-level*
         * one, so these sit in the file classes beside the thirteen exported functions and are
         * indistinguishable from them by reflection. `@JsExport` cannot settle it either: it is
         * `@OptionalExpectation`, so no JVM declaration of it exists and no annotation reaches the class
         * file. The split therefore has to be by name, and the equality in
         * `every top-level function is an entry point or a named helper` is what keeps it honest.
         *
         * The **counts** close the hole a bare name set leaves. A name set cannot see a second method
         * with a name it already holds, so `@JsExport fun crossedPayee(amount: Long): Long` added
         * beside the real `crossedPayee` would have hidden behind it. Pinning the overload count means
         * any new declaration — exported or not, new name or not — turns this red. Each number is
         * checkable by reading the function it names.
         *
         * `asJsBuildResult` has two: `RumorBuild`'s and `PaymentBuild`'s. Everything else has one.
         */
        val NON_EXPORTED_HELPERS: Map<String, Int> = mapOf(
            // JsBoundary.kt — the codecs and the refusal-translating wrappers.
            "jsSeamAnswer" to 1,
            "jsTextOk" to 1,
            "jsText" to 1,
            "jsBuilt" to 1,
            "jsRefused" to 1,
            "jsBuild" to 1,
            "crossedNumber" to 1,
            "crossedNumberOrNull" to 1,
            "crossedAmount" to 1,
            "crossedSeconds" to 1,
            "crossedSecondsOrNull" to 1,
            "asTagRows" to 1,
            "asTagArray" to 1,
            // T43 moved the package's whole catch list into `jsGuarded` and left `jsBuild` as one
            // line over it: two lists that must stay in step is the shape that goes stale the
            // first time a module starts raising a type only one of them names.
            "jsGuarded" to 1,
            // JsEnvelopeEntries.kt — §7.4's bound/chat split, which every decoder below it needs.
            "asBoundRumor" to 1,
            // JsSettlementEntries.kt — §9.2's store seam, §8.4's sighting, and the one reader of a
            // closed enum by name.
            "asPaymentRequestStore" to 1,
            "asFeeTermSighting" to 1,
            "crossedConstant" to 1,
            // JsOrderEntries.kt — §11.2's machine configuration and its fourteen-way event union.
            "asOrderMachine" to 1,
            "asOrderEvent" to 1,
            // JsChannelEntries.kt — the §7.4, §8.3, §10.1 and §10.3 translations.
            "asJsBuildResult" to 2,
            "asRumorEnvelope" to 1,
            "asOrderTerms" to 1,
            "asDeliverableCommitment" to 1,
            "asDeliverableRelease" to 1,
            "asCrossedPubkeyRef" to 1,
            "crossedPayee" to 1,
            // JsSeams.kt — the five seam adapters.
            "asSigner" to 1,
            "asClock" to 1,
            "asRandomness" to 1,
            "asSecp" to 1,
            "asEphemeralSigners" to 1,
        )

        /**
         * Everything in the package that does **not** cross: the `internal` codecs, the file classes
         * holding only `internal` helpers, and the seam adapters.
         *
         * Listed rather than derived for the reason this class's note gives. `JsDecimal`, `JsHex` and
         * `JsCrossing` are `internal` classes and `CrossedSigner` is `private`, so Kotlin/JS exports
         * none of them. The file classes are **not** here: they are in [FILE_CLASSES] and every one of
         * them is swept, for the reason that list records.
         *
         * There is deliberately no `JsListingsKt`: `JsListings.kt` declares only classes, and Kotlin
         * emits a file class only for a file with top-level members. Naming one would make the
         * classification equality assert over something that is not there, which is the half of that
         * equality a reader is least likely to check.
         */
        val DOES_NOT_CROSS: Set<String> = setOf(
            "JsDecimal",
            "JsHex",
            "JsCrossing",
            "CrossedSigner",
            // T43's two. `JsRefusal` is the `internal` shape `jsGuarded` reduces a thrown refusal
            // to, so the catch list is written once for all nineteen entry points rather than once
            // per result class; `CrossedPaymentRequestStore` is `private`, the §9.2 store seam's
            // adapter, and the sibling of `CrossedSigner`.
            "JsRefusal",
            "CrossedPaymentRequestStore",
        )

        /**
         * The nineteen entry points this package exports: T42's thirteen and T43's six.
         *
         * Pinned so the sweep cannot pass over a package whose entry points were renamed or removed,
         * and so the counts in T42's and T43's text are facts about the compiled library rather than
         * about their prose.
         */
        val ENTRY_POINTS: Set<String> = setOf(
            // T42 — the decode-and-build half.
            "decodeListing",
            "decodeBid",
            "buildListingRequest",
            "buildListingOffer",
            "buildListingDraft",
            "buildChat",
            "buildProposal",
            "buildStatusUpdate",
            "buildCommitment",
            "buildRelease",
            "buildPrivateBid",
            "buildPaymentRequest",
            "buildReceipt",
            // T43 — the half that turns the page from a board into a trade.
            "seal",
            "open",
            "verifySettlement",
            "verifyFeeReceipt",
            "openOrder",
            "stepOrder",
        )

        /** T42's half of [ENTRY_POINTS], so each task's own count stays a fact and not a comment. */
        val T42_ENTRY_POINTS: Set<String> = setOf(
            "decodeListing",
            "decodeBid",
            "buildListingRequest",
            "buildListingOffer",
            "buildListingDraft",
            "buildChat",
            "buildProposal",
            "buildStatusUpdate",
            "buildCommitment",
            "buildRelease",
            "buildPrivateBid",
            "buildPaymentRequest",
            "buildReceipt",
        )

        /**
         * Every type decision **P** forbids at this boundary, as it appears in a `typeName`.
         *
         * - `long` / `java.lang.Long` — a 64-bit integer, and a value class over one (`Msat` erases to
         *   `long`). Money, timestamps and byte counts cross as decimal strings instead.
         * - `double` / `float` and their boxes — §14 item 12, and the reason the decimal rule exists.
         * - `java.util.*` containers — every Kotlin collection erases to one, and decision **P** says
         *   none crosses. An `Array` does, and is not a `java.util` type.
         * - `dev.eryalabs.nenya.` — the strongest form of the rule: no type of this library's own,
         *   sealed or otherwise. A `SeamAnswer`, a `FeeTerm`, a `ListingBuild` or a `WireEvent` in an
         *   exported signature is the divergence decision **P** forbids.
         */
        val FORBIDDEN: List<Pair<String, Regex>> = listOf(
            "a 64-bit integer" to Regex("""\b(long|java\.lang\.Long)\b"""),
            "floating-point" to Regex("""\b(double|float|java\.lang\.Double|java\.lang\.Float)\b"""),
            "a Kotlin collection" to Regex("""\bjava\.util\.(List|Map|Set|Collection|Iterator|Iterable)\b"""),
            "a type of this library's own" to Regex("""\bdev\.eryalabs\.nenya\.(?!js\.)"""),
        )

        /** `toString`, which every type here overrides and no page calls as part of the crossing. */
        val NOT_PART_OF_THE_CROSSING: Set<String> = setOf("toString")

        /**
         * The first forbidden type [executable]'s **generic** signature mentions, or `null`.
         *
         * `typeName` prints the parameterised form — `java.util.List<java.lang.String>` — so a match
         * over it sees what a comparison against an erased `Class` cannot.
         */
        fun forbiddenIn(executable: Executable): String? {
            val mentioned = executable.genericParameterTypes.map { it.typeName } +
                listOfNotNull((executable as? Method)?.genericReturnType?.typeName)
            for (type in mentioned) {
                for ((what, pattern) in FORBIDDEN) {
                    if (pattern.containsMatchIn(type)) return "$what, as `$type`"
                }
            }
            return null
        }

        /** The methods of [type] that are part of the crossing. */
        fun crossingMethods(type: Class<*>): List<Method> =
            MainClasses.methods(type).filter { it.name !in NOT_PART_OF_THE_CROSSING }

        /** A getter's property name — `getIdHex` becomes `idHex`, `isOpen` becomes `isOpen`. */
        fun propertyName(method: Method): String? = when {
            method.parameterCount != 0 -> null
            method.name.length > 3 && method.name.startsWith("get") ->
                method.name[3].lowercaseChar() + method.name.substring(4)
            else -> null
        }
    }

    // -----------------------------------------------------------------------------------------
    // The package is classified, and the sweep is looking at it.
    // -----------------------------------------------------------------------------------------

    /**
     * Every class this package compiles to is on one side of the boundary or the other.
     *
     * The anchor the rest of this file rests on. Without it, a type added to the package would be swept
     * by nobody and every assertion below would stay green while the new type exported a `Long`.
     */
    @Test
    fun `every class in the package is classified as crossing or not crossing`() {
        val found = reflectedNames()
        val classified = CROSSED_IN + CROSSED_OUT + FILE_CLASSES + DOES_NOT_CROSS

        assertEquals(
            classified,
            found,
            "every class under $PACKAGE must be named in exactly one of CROSSED_IN, CROSSED_OUT, " +
                "FILE_CLASSES or DOES_NOT_CROSS. A class in neither is a type nothing here sweeps; a " +
                "name in the lists and not on disk is a sweep over something that is no longer there.",
        )
        assertEquals(
            classified.size,
            CROSSED_IN.size + CROSSED_OUT.size + FILE_CLASSES.size + DOES_NOT_CROSS.size,
            "a class name appears in two of the four lists, so one of the two sweeps it belongs to " +
                "is deciding what the other sweeps",
        )
        assertTrue(found.size > 20, "${found.size} class(es) is not this package")
    }

    /**
     * Every top-level function in the package is either one of T42's thirteen entry points or a named
     * helper, and each has exactly the number of overloads pinned for it.
     *
     * This is the anchor that was missing. It covers **all four** file classes, not only the two holding
     * entry points, and it compares `(name, overload count)` rather than names alone — so an export
     * cannot hide in an unswept file, and cannot hide as a second method behind a helper's name either.
     * See [FILE_CLASSES] and [NON_EXPORTED_HELPERS] for the two holes this closes and how each was
     * demonstrated.
     */
    @Test
    fun `every top-level function is an entry point or a named helper`() {
        val counts = mutableMapOf<String, Int>()
        for (name in FILE_CLASSES) {
            for (method in crossingMethods(typeNamed(name))) {
                counts[method.name] = (counts[method.name] ?: 0) + 1
            }
        }

        assertEquals(
            ENTRY_POINTS + NON_EXPORTED_HELPERS.keys,
            counts.keys,
            "every top-level function in $FILE_CLASSES must be either one of T42's thirteen entry " +
                "points or a named helper. A name in neither list is a function nothing here sweeps — " +
                "and if it carries @JsExport it is an entry point reaching JavaScript with no equality " +
                "proof behind it. See NON_EXPORTED_HELPERS on why the split is by name.",
        )
        assertEquals(
            ENTRY_POINTS.associateWith { 1 } + NON_EXPORTED_HELPERS,
            counts,
            "the overload count of every top-level function is pinned. A second method sharing a name " +
                "this list already holds is invisible to a comparison of names alone, which is how an " +
                "export would hide behind a helper.",
        )
        assertEquals(13, T42_ENTRY_POINTS.size, "T42's text says thirteen")
        assertEquals(19, ENTRY_POINTS.size, "T43's six on top of T42's thirteen")
        assertEquals(
            6,
            (ENTRY_POINTS - T42_ENTRY_POINTS).size,
            "T43's text says six: seal, open, the two settlement checks, openOrder and stepOrder",
        )
        assertTrue(T42_ENTRY_POINTS.all { it in ENTRY_POINTS }, "T42's thirteen are still exported")
        assertTrue(
            ENTRY_POINTS.none { it in NON_EXPORTED_HELPERS },
            "an entry point is also listed as a helper, so one of the two lists is not what it says",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The rule.
    // -----------------------------------------------------------------------------------------

    /**
     * No exported signature in this package mentions a type decision **P** forbids.
     *
     * Constructors **and** methods for the types a page constructs; methods only for the types it reads,
     * for the reason [CROSSED_OUT] gives. The entry points themselves are swept through their file
     * classes.
     */
    @Test
    fun `no exported signature mentions a Long a collection or a type of this library's own`() {
        var inspected = 0

        for (name in CROSSED_IN) {
            val type = typeNamed(name)
            for (executable in type.constructors.toList() + crossingMethods(type)) {
                inspected++
                assertNull(
                    forbiddenIn(executable),
                    "$name.${executable.name} crosses ${forbiddenIn(executable)}. Decision P: money, " +
                        "timestamps and byte counts cross as decimal strings, sealed results as plain " +
                        "objects with a `kind`, and no Kotlin type crosses at all. " +
                        "NON_EXPORTABLE_TYPE is only a warning, which is why this test is here.",
                )
            }
        }

        for (name in CROSSED_OUT) {
            val type = typeNamed(name)
            for (method in crossingMethods(type)) {
                inspected++
                assertNull(
                    forbiddenIn(method),
                    "$name.${method.name} crosses ${forbiddenIn(method)}. See the note above.",
                )
            }
        }

        // The entry points themselves, by name — the file classes also hold `internal` helpers that
        // reflection cannot tell apart from them, and those legitimately take Kotlin types. Which names
        // are entry points and which are helpers is pinned by `every top-level function is an entry
        // point or a named helper`, overload counts and all, so a leaked export cannot reach this loop
        // disguised as a helper without turning that test red first.
        var entryPointsSwept = 0
        for (name in FILE_CLASSES) {
            val type = typeNamed(name)
            for (method in crossingMethods(type).filter { it.name in ENTRY_POINTS }) {
                inspected++
                entryPointsSwept++
                assertNull(
                    forbiddenIn(method),
                    "$name.${method.name} crosses ${forbiddenIn(method)}. See the note above.",
                )
            }
        }

        assertEquals(
            ENTRY_POINTS.size,
            entryPointsSwept,
            "all ${ENTRY_POINTS.size} entry points must be swept; only $entryPointsSwept were, so one " +
                "is in a class $FILE_CLASSES does not name",
        )
        assertTrue(
            inspected > 240,
            "the sweep inspected only $inspected member(s) across " +
                "${CROSSED_IN + CROSSED_OUT + FILE_CLASSES}, which is not this facade. The floor " +
                "was 120 at T42's thirteen entry points and eight types a side; T43 doubled the " +
                "package, so a floor that stayed where it was would pass over half of it.",
        )
    }

    /**
     * The control that proves the sweep is not erasure-blind, fed to the sweep's **own** predicate.
     *
     * A control exercising a re-implementation proves nothing about the one that runs, so every shape
     * here goes through [forbiddenIn]. The four wrapped shapes are the ones an erased check waves
     * through: `List<Long>`, `Map<String, Msat>` and `List<List<WireEvent>>` all erase to their raw
     * container. `bareLong` and `valueClass` are caught by an erased check too, which is what shows the
     * controls test different things — and `valueClass` is the one the whole rule turns on, because
     * `Msat` *is* a `long` in the class file and reads like neither in the source.
     */
    @Test
    fun `the sweep catches a Long a value class and a Kotlin type hidden inside a generic`() {
        for (name in listOf("bareLong", "valueClass", "wrappedLong", "keyedMoney", "nestedEvent", "sealedTerm")) {
            val method = probeMethod(name)
            assertTrue(
                forbiddenIn(method) != null,
                "ForbiddenTypeProbe.$name is a type decision P forbids and the sweep missed it — " +
                    "${method.genericReturnType.typeName} / " +
                    "${method.genericParameterTypes.map { it.typeName }}",
            )
        }
        for (name in listOf("strings", "rows", "nullableInt", "ownType")) {
            assertNull(
                forbiddenIn(probeMethod(name)),
                "a String, an Array of Arrays, a boxed Int and this package's own translation type " +
                    "are what the boundary is made of; flagging one would make the rule unsatisfiable",
            )
        }
    }

    /**
     * One probe method by name, allowing for the suffix Kotlin may add.
     *
     * A function whose signature mentions a value class can be given a `-<hash>` suffix in the class
     * file — which is itself part of what this sweep is about, since `Msat` is a `long` there — so the
     * lookup matches the prefix and says what it found if it finds nothing.
     */
    private fun probeMethod(name: String): Method {
        val probe = ForbiddenTypeProbe::class.java
        val candidates = probe.declaredMethods.filter { it.name == name || it.name.startsWith("$name-") }
        return candidates.singleOrNull() ?: fail(
            "ForbiddenTypeProbe has ${candidates.size} method(s) matching `$name`; it declares " +
                probe.declaredMethods.map { it.name },
        )
    }

    /**
     * A probe, not a fixture: six shapes decision **P** forbids and four it is built from.
     *
     * Declared here precisely so the sweep has something it is required to catch. Without it, "no
     * exported signature mentions a `Long`" is satisfied by a predicate that never matches anything.
     */
    @Suppress("unused")
    private class ForbiddenTypeProbe {
        fun bareLong(): Long = 0L
        fun valueClass(): Msat = Msat.ZERO
        fun wrappedLong(): List<Long> = emptyList()
        fun keyedMoney(): Map<String, Msat> = emptyMap()
        fun nestedEvent(): List<List<WireEvent>> = emptyList()
        fun sealedTerm(term: dev.eryalabs.nenya.money.FeeTerm): Int = term.basisPoints
        fun strings(): String = ""
        fun rows(): Array<Array<String>> = emptyArray()
        fun nullableInt(): Int? = null
        fun ownType(): JsText? = null
    }

    /** No class that crosses is a nested one, so filtering compiler-generated `$` names loses nothing. */
    @Test
    fun `no type that crosses is nested inside another`() {
        for (name in CROSSED_IN + CROSSED_OUT) {
            val type = typeNamed(name)
            assertNull(
                type.declaringClass,
                "$name is nested inside ${type.declaringClass?.simpleName}. The classification above " +
                    "drops every name containing `$` as compiler-generated, so a nested exported type " +
                    "would be swept by nobody.",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The crossing's own vocabulary, pinned.
    // -----------------------------------------------------------------------------------------

    /**
     * [JsCrossing] owns exactly five refusals, and the equality tests reach all five.
     *
     * A sixth appearing without anybody noticing is how a translation layer grows into a second
     * rulebook — the divergence STOP RULE 5 exists to prevent — so the set is held equal to the
     * constants the class declares, and the vocabulary name is held apart from every rejection enum
     * this library publishes.
     *
     * **Three at T42, five at T43, and the test turning red is how the widening was declared.**
     * Both additions are the same shape as the first three and neither is a rule about a value:
     * `NOT_A_BOUND_RUMOR` says the decoder's own parameter type has nothing to be handed (§7.4's
     * `kind:14` is outside the bound half), and `UNCONSTRUCTIBLE_VALUE` says the Kotlin value a
     * page named cannot be built from the fields that crossed. See each constant's note.
     */
    @Test
    fun `the crossing's vocabulary is five refusals and a name no section uses`() {
        assertEquals(
            setOf(
                JsCrossing.MALFORMED_DECIMAL,
                JsCrossing.WRONG_ROW_ARITY,
                JsCrossing.UNKNOWN_PAYEE_TOKEN,
                JsCrossing.NOT_A_BOUND_RUMOR,
                JsCrossing.UNCONSTRUCTIBLE_VALUE,
            ),
            JsCrossing.REASONS,
            "every reason this boundary can report must be one of the constants it declares",
        )
        assertEquals(5, JsCrossing.REASONS.size, "five refusals about the form of a crossing")

        // The vocabulary name must not collide with a rejection enum a caller could be branching on: a
        // page that read `JsCrossing` as a protocol refusal would act on a rule NENYA-1 does not have.
        for (packageName in MainClasses.allPackages()) {
            for (type in MainClasses.published(packageName)) {
                if (type.enumConstants == null) continue
                assertNotEquals(
                    JsCrossing.VOCABULARY,
                    type.simpleName,
                    "${type.name} is an enum this library publishes and it shares its name with the " +
                        "crossing's own vocabulary",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The field sweeps: the equality proof's "every field" is derived, not claimed.
    // -----------------------------------------------------------------------------------------

    /**
     * `JsFacadeEqualityTest` compares every field `JsListing` publishes. This is what makes "every"
     * true: the names it compares are held equal to the getters the compiled class has.
     *
     * Without this, "every field is compared" is a claim about the hand that wrote the list — the
     * vacuity every other vocabulary in this repository is anchored against. A getter added to
     * `JsListing` and not to `JsBoundaryFixtures.listingFields` turns this red, which is the only point
     * at which anybody is told.
     */
    @Test
    fun `the listing field comparison covers every value JsListing publishes`() {
        val event = ListingWriterFixtures.built(
            ListingWriterFixtures.build(NenyaKind.OFFER, ListingWriterFixtures.minimal(NenyaKind.OFFER)),
            "the minimal offer",
        )
        val answer = decodeListing(JsBoundaryFixtures.json(event))
        val crossed = answer.listing ?: fail("the minimal offer must decode: ${answer.reason}")
        val decoded = Listing.decode(CheckedEvent.checkEventId(EventId.of(event).toHex(), event))

        assertEquals(
            publishedPropertyNames(typeNamed("JsListing")),
            JsBoundaryFixtures.listingFields(crossed, decoded).map { it.name }.toSet(),
            "JsBoundaryFixtures.listingFields must name every value JsListing publishes and no other",
        )
    }

    /** The same for `JsBid`, on the same terms. */
    @Test
    fun `the bid field comparison covers every value JsBid publishes`() {
        val fixture = BidFixtures.bids(1).single()
        val event = WireEvent(
            TagFixtures.pubkeyFor(fixture.index),
            BidFixtures.CREATED_AT,
            NenyaKind.PUBLIC_BID,
            fixture.tags,
            fixture.content,
        )
        val answer = decodeBid(JsBoundaryFixtures.json(event))
        val crossed = answer.bid ?: fail("the generated bid must decode: ${answer.reason}")
        val decoded = Bid.decode(CheckedEvent.checkEventId(EventId.of(event).toHex(), event))

        assertEquals(
            publishedPropertyNames(typeNamed("JsBid")),
            JsBoundaryFixtures.bidFields(crossed, decoded).map { it.name }.toSet(),
            "JsBoundaryFixtures.bidFields must name every value JsBid publishes and no other",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Why the supply cap alone is not a control.
    // -----------------------------------------------------------------------------------------

    /**
     * The arithmetic behind `JsCrossingTest`'s choice of anchors, demonstrated rather than asserted in
     * prose: §4.4's supply cap survives a `Double` round trip and the value one below it does not.
     *
     * This is why T42's mutation — rounding one facade money value through a `Double` — has to be
     * checked against a sub-cap value, and why that file brackets each anchor instead of sitting on it.
     * A test suite whose only large money case was the cap itself would have stayed green through
     * exactly the defect the decimal-string rule exists to prevent.
     *
     * In `jvmTest` because it is about `Double` conversion semantics, and the JVM's are the ones this
     * reasoning was done against. Nothing in `src/commonMain` performs either conversion —
     * `no exported signature mentions ...` above is what holds that — so this proves a fact about the
     * trap, not about the library.
     */
    @Test
    fun `the supply cap survives a Double round trip and the value below it does not`() {
        val cap = Msat.SUPPLY_CAP_MSAT
        assertEquals(
            cap,
            cap.toDouble().toLong(),
            "the cap is a multiple of 256 and so exactly representable; a control sitting on it would " +
                "pass against a boundary that carried money as a JavaScript number",
        )
        assertNotEquals(
            cap - 1L,
            (cap - 1L).toDouble().toLong(),
            "one millisatoshi below the cap is where the low bits go, and so where a control belongs",
        )
        assertEquals(
            cap,
            (cap - 1L).toDouble().toLong(),
            "and it does not merely differ: it rounds up onto the cap itself, which is why a `>=` " +
                "sanity check on a rounded amount would see nothing wrong",
        )
        // `Long.MAX_VALUE` is the other trap: the conversion back saturates onto it, so it round-trips
        // while its neighbour does not.
        assertEquals(Long.MAX_VALUE, Long.MAX_VALUE.toDouble().toLong())
        assertNotEquals(Long.MAX_VALUE - 1L, (Long.MAX_VALUE - 1L).toDouble().toLong())
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /**
     * The simple names of every class this package compiles to, minus the compiler-generated ones.
     *
     * `$` drops companion objects, the anonymous `object :` seam adapters and the `…$default` carriers
     * Kotlin emits for default arguments. `no type that crosses is nested inside another` is what makes
     * that drop safe.
     */
    private fun reflectedNames(): Set<String> =
        classes.map { it.name.removePrefix("$PACKAGE.") }.filter { '$' !in it }.toSet()

    /** [name] as the class the main output tree holds for it. */
    private fun typeNamed(name: String): Class<*> =
        classes.firstOrNull { it.name == "$PACKAGE.$name" }
            ?: fail("$PACKAGE.$name is not in the main output tree")

    /** The package's classes, read once: `MainClasses.of` lists a directory and loads every entry. */
    private val classes: List<Class<*>> by lazy { MainClasses.of(PACKAGE) }

    /** Every property [type] publishes, by name, excluding `toString` and the mangled `internal` ones. */
    private fun publishedPropertyNames(type: Class<*>): Set<String> {
        val methods = crossingMethods(type)
        val properties = methods.mapNotNull { propertyName(it) }.toSet()
        assertEquals(
            methods.size,
            properties.size,
            "${type.simpleName} publishes a method that is not a no-argument getter: " +
                methods.filter { propertyName(it) == null }.map { it.name } +
                ". The field sweep reads properties, so a published function would go unchecked.",
        )
        assertTrue(properties.size > 10, "${type.simpleName} publishes only $properties")
        return properties
    }
}
