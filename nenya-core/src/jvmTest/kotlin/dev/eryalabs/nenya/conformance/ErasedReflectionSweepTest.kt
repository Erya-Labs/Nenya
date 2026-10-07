package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.seam.AmbientEffectsTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The durable half of T36: every non-comment read of `Method.parameterTypes` or `Method.returnType`
 * anywhere in the test tree is one of **two** permitted uses, each pinned by file and line.
 *
 * ### Why this exists
 *
 * Those two properties report the type **after erasure**. A sweep that reads them sees `java.util.List`
 * for `fun x(): List<OrderEvent>`, so "no `OrderEvent` is reachable from this package" passes while the
 * surface hands a caller exactly what §6 forbids, wrapped in a collection. The same reading defeats
 * every other rule the structure sweeps state: `List<Boolean>` satisfies "no `Boolean` as evidence of
 * payment" (STOP RULE 12), and `Map<String, Boolean>` satisfies it too.
 *
 * That defect has now been found and fixed **nine** times — T11 found it, T12 wrote it fresh into a
 * seventh file before fixing it there, and T36 converted the rest. A tenth is a matter of somebody
 * reaching for the shorter property name once. Nothing was watching, which is the only reason it kept
 * coming back. This is what watches: the cure is `genericParameterTypes` / `genericReturnType` matched
 * on `typeName`, and from here on the erased forms may appear only where this file says they may.
 *
 * ### The two categories, and why it is two and not one
 *
 * An "arity count only" rule would be red on arrival against code T36 never named and must not change.
 *
 * *Category one, arity counting* ([ARITY_COUNTING_PERMITTED]) — the expression **immediately consumed
 * by `.size`**. Counting a constructor's parameters is not a type inspection, so erasure cannot lose
 * anything: `List<Boolean>` and `List<String>` are one parameter either way. This category is pinned by
 * file and line *and* structurally verified, so an entry here cannot quietly become a type inspection.
 *
 * *Category two, erased-`Class` semantics that generics cannot express* ([ERASED_CLASS_PERMITTED]) — a
 * `Class<*>` is the value actually needed: to synthesise an argument for `Method.invoke`, to dispatch
 * through `isAssignableFrom`, to compare against `Void.TYPE`, or to test membership in a set of `Class`
 * objects. `HostileInputSweepTest`'s own KDoc says the erasure is deliberate there — "a filter written
 * against the parameterised one would see nothing at all". There is no generic form of any of these.
 *
 * **Neither category may be widened to get green**, which is T36's rule and the reason the pins are by
 * file and line rather than by count: a non-arity, non-reflective-dispatch use is a conversion to the
 * generic form, not a new exception. A new occurrence turns this red and asks a human.
 *
 * ### The property form, not the Java getter
 *
 * The needles are `.parameterTypes` and `.returnType`. No Kotlin in this repository writes
 * `getParameterTypes()` or `getReturnType()`, so a sweep looking for the Java spelling would match zero
 * lines, pass forever with every sweep still erasure-blind, and take its own negative control green with
 * it. That the Java spelling is absent is not left as a belief: the test below named "no test source
 * reaches the erased property through its Java getter spelling" closes that evasion, because a read
 * written that way is one these needles cannot see.
 *
 * ### This file excludes itself, by name
 *
 * Both needles appear here as **string literals**, and [AmbientEffectsTest]'s stripper says in its own
 * KDoc that it removes line comments and block comments and does not reason about strings. (That
 * sentence is worded around the block-comment opener on purpose: written literally inside a KDoc it
 * opens a nested comment in Kotlin.) Left unexcluded the sweep
 * would flag itself, and the obvious repair — widening a category — defeats the test. So [OWN_FILE] is
 * skipped by name, that name is asserted to have actually been found among the files walked, and the
 * sweep is asserted to still match occurrences elsewhere.
 *
 * ### Where this runs
 *
 * It reads the test tree from disk through `java.io.File`, so it is JVM-only for the same reason
 * [AmbientEffectsTest]'s and `StaleNarrowingTest`'s sweeps are. The walk's roots are a **parameter**, so
 * the scratch-file control runs against a temporary directory instead of writing a probe into
 * `src/jvmTest/kotlin` and deleting it afterwards — a run killed between those two steps would leave a
 * scratch file STOP RULE 8 forbids and the next `:jvmTest` compiles.
 */
class ErasedReflectionSweepTest {

    /**
     * One non-comment read of an erased reflection property: where it is, and what consumes it.
     *
     * [consumedBySize] is the structural half of category one. It is computed from the code text rather
     * than taken from the pin, so a pinned arity use that stops being one turns this red.
     */
    private data class Occurrence(
        val path: String,
        val line: Int,
        val needle: String,
        val code: String,
        val consumedBySize: Boolean,
    ) {
        val at: String get() = "$path:$line"

        override fun toString(): String = "$at — $needle in `$code`"
    }

    /** What one walk read: the files it opened, and every occurrence in them. */
    private data class Swept(val files: List<String>, val occurrences: List<Occurrence>)

    private companion object {

        /**
         * The erased properties, in the only spelling Kotlin here uses for them.
         *
         * `.parameterTypes` does not match inside `.genericParameterTypes` and `.returnType` does not
         * match inside `.genericReturnType`: in both the cured forms the character after `generic` is a
         * capital, so the needle's leading `.` cannot land there. That is what lets the sweep distinguish
         * the defect from its fix with a plain substring search.
         */
        val NEEDLES: List<String> = listOf(".parameterTypes", ".returnType")

        /**
         * The Java getter spellings, which reach the same erased array without a leading `.` —
         * asserted absent rather than assumed absent.
         */
        val JAVA_GETTER_SPELLINGS: List<String> = listOf("getParameterTypes()", "getReturnType()")

        /**
         * The same two properties with **no receiver dot at all**, which is the other way around
         * [NEEDLES] and the one a reader is least likely to picture: a callable reference
         * (`Method::parameterTypes`, a `:` before the name) and an implicit receiver
         * (`with(method) { parameterTypes }`, `method.run { returnType }`, a space before it). Both are
         * ordinary Kotlin, both are the erased read, and neither carries the dot the needles require.
         *
         * Lower-case first letter, so neither token matches inside `genericParameterTypes` or
         * `genericReturnType` — the cured forms are spelled with a capital there.
         *
         * **Do not name a local or a parameter `parameterTypes` or `returnType`** in either test root.
         * `val returnType = method.genericReturnType.typeName` is a correct, converted read, but the
         * declaration's left-hand side is a bare token with no receiver dot, so this check fires and the
         * failure message advises the very fix the author already applied. It fails *closed* — an
         * innocent red, never a silent green — and nothing in the tree is named either way today (the
         * converted files use `val returned`). Picking another name is cheaper than teaching a substring
         * search to parse Kotlin declarations, which is the trade this whole file is built on.
         */
        val UNDOTTED_TOKENS: List<String> = listOf("parameterTypes", "returnType")

        /**
         * Every test source root this sweep reads. Both, because a test that only runs on the JVM proves
         * nothing about the JavaScript build (STOP RULE 15) and an erasure-blind read is just as wrong in
         * `src/commonTest` — where it would be a compile error on JS and so is caught differently, but the
         * sweep must still *look*, or "the tree is clean" is a claim about half of it.
         */
        val TEST_ROOTS: List<String> = listOf("src/commonTest/kotlin", "src/jvmTest/kotlin")

        /**
         * A test root on disk that this sweep deliberately does not read, with the reason — declared
         * rather than silently skipped, because a root nobody reads is where the next erased read lands.
         */
        val ROOTS_DELIBERATELY_UNSWEPT: Map<String, String> = mapOf(
            "src/jsTest/kotlin" to
                "Kotlin/JS has no java.lang.reflect at all, so neither needle can be written there; " +
                "a read of either property in that root would not compile, which is a stronger guard " +
                "than this sweep.",
        )

        /** This file, skipped because both needles appear in it as string literals. */
        const val OWN_FILE: String = "ErasedReflectionSweepTest.kt"

        /** A `src/commonTest` file asserted among those read, so quietly skipping common test code fails. */
        const val COMMON_TEST_WITNESS: String = "NenyaProtocolTest.kt"

        /**
         * The files T36 required the sweep to prove it read: the seven packages whose `*StructureTest`
         * carried the defect, **and the two a `*StructureTest` glob would have missed** —
         * `CapabilitySurfaceTest`, which states this rule over the `conformance` surface, and
         * `LyingWalletTest`, which pins `VerifiedPayment.verify`'s parameter list and sits on the
         * evidence path, where this defect costs the most.
         *
         * These are files *read*, not files with occurrences: after conversion most have none, and that
         * is the point. Asserting over the read set is what makes "no occurrences" mean something.
         */
        val FILES_PROVEN_READ: Set<String> = setOf(
            "BidStructureTest.kt",
            "DeliveryStructureTest.kt",
            "ListingStructureTest.kt",
            "OrderStructureTest.kt",
            "PaymentStructureTest.kt",
            "SeamStructureTest.kt",
            "WireStructureTest.kt",
            // The two the glob would have missed.
            "CapabilitySurfaceTest.kt",
            "LyingWalletTest.kt",
        )

        /**
         * *Category one.* The expression is immediately consumed by `.size`, so erasure loses nothing:
         * a parameter is one parameter whatever its type arguments say. Every entry is additionally
         * asserted to really be followed by `.size`, so this list cannot shelter a type inspection.
         */
        val ARITY_COUNTING_PERMITTED: Map<String, String> = mapOf(
            "src/jvmTest/kotlin/dev/eryalabs/nenya/delivery/DeliveryStructureTest.kt:360" to
                "picks the synthetic two-argument constructor by its arity",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/delivery/DeliveryStructureTest.kt:379" to
                "picks the synthetic two-argument constructor by its arity",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamStructureTest.kt:215" to
                "picks the synthetic two-argument constructor by its arity",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/wire/WireStructureTest.kt:484" to
                "picks the synthetic two-argument constructor by its arity",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/wire/WireStructureTest.kt:503" to
                "picks the synthetic two-argument constructor by its arity",
        )

        /**
         * *Category two.* The erased `Class<*>` is the value the code actually needs, and there is no
         * parameterised form of any of these operations.
         */
        val ERASED_CLASS_PERMITTED: Map<String, String> = mapOf(
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamStructureTest.kt:195" to
                "collects returned types as Class objects for a membership test",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamFailClosedTest.kt:115" to
                "dispatches through Class.isAssignableFrom",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamFailClosedTest.kt:174" to
                "synthesises one argument per declared parameter type for Method.invoke",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamFailClosedTest.kt:272" to
                "compares against Void.TYPE, which only exists as a Class",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/seam/SeamFailClosedTest.kt:414" to
                "tests membership against String::class.java and ByteArray::class.java",
            // Both moved down when T40 added the three ListingWriter targets and their helpers to
            // that file: the reads are the same two, unchanged, at 477 and 558 rather than 449 and
            // 486. A pin is by file and line, so a line-shifting edit above one is a fixture change
            // (STOP RULE 1) and this is it — declared rather than silenced, and the sweep asserting
            // every pin is still found is what forced it.
            "src/jvmTest/kotlin/dev/eryalabs/nenya/conformance/HostileInputSweepTest.kt:477" to
                "dispatches through Enum::class.java.isAssignableFrom",
            "src/jvmTest/kotlin/dev/eryalabs/nenya/conformance/HostileInputSweepTest.kt:558" to
                "tests membership in hostileShapes(), a set of Class objects; its KDoc says the " +
                "erasure is deliberate, because a filter written against the parameterised type " +
                "would see nothing at all",
        )

        /** The probe for the scratch-file control: one live erased read, and one in a comment. */
        const val PROBE_FILE: String = "ErasedProbe.kt"

        /** The line of [PROBE_SOURCE] carrying the live read. Asserted against the text, not trusted. */
        const val PROBE_LIVE_LINE: Int = 5

        /** The line carrying the commented read, which the stripper must hide. */
        const val PROBE_COMMENTED_LINE: Int = 3

        /**
         * A `.kt` file with a **non-arity** erased read on [PROBE_LIVE_LINE] and a commented one on
         * [PROBE_COMMENTED_LINE]. Written into a temporary directory, never into the source tree.
         */
        val PROBE_SOURCE: String = listOf(
            "package dev.eryalabs.nenya.probe",
            "",
            "// A commented .returnType, which the stripper must keep out of the sweep.",
            "class ErasedProbe {",
            "    fun erased(method: java.lang.reflect.Method): Class<*> = method.returnType",
            "}",
        ).joinToString("\n")

        /** Every permitted occurrence, by file and line, with the category that admits it. */
        fun permitted(): Map<String, String> =
            ARITY_COUNTING_PERMITTED.mapValues { (_, why) -> "arity counting — $why" } +
                ERASED_CLASS_PERMITTED.mapValues { (_, why) -> "erased-Class semantics — $why" }

        /** The enumeration a failure prints: every exception this file grants, and why. */
        fun permittedReport(): String =
            permitted().entries.sortedBy { it.key }.joinToString("\n") { (at, why) -> "    $at  [$why]" }
    }

    /**
     * Every occurrence in [lines], attributed to [path].
     *
     * Taking lines rather than a file is what lets the controls below feed this **the same** matcher the
     * real sweep runs, instead of a second copy of it — the failure mode T36 is about.
     */
    private fun readsIn(path: String, lines: List<String>): List<Occurrence> {
        val found = mutableListOf<Occurrence>()
        for ((number, code) in AmbientEffectsTest.codeLines(lines)) {
            for (needle in NEEDLES) {
                var from = 0
                while (true) {
                    val at = code.indexOf(needle, from)
                    if (at < 0) break
                    from = at + 1
                    val afterAt = at + needle.length
                    // A whole property token: `.returnTypeName` is not `.returnType`. A following `.`
                    // must still match, or `.parameterTypes.size` would be invisible.
                    val after = if (afterAt < code.length) code[afterAt] else ' '
                    if (after.isLetterOrDigit() || after == '_') continue
                    found += Occurrence(
                        path = path,
                        line = number,
                        needle = needle,
                        code = code,
                        consumedBySize = code.startsWith(".size", afterAt),
                    )
                }
            }
        }
        return found
    }

    /**
     * Whether [code] names [token] with **no receiver dot**, which is the read [NEEDLES] cannot see.
     *
     * A whole token either side, and the character before must not be a `.`: `method.parameterTypes` is
     * the dotted form the sweep proper already handles, while `Method::parameterTypes` (a `:` before)
     * and `with(method) { parameterTypes }` (a space before) are the two this catches.
     */
    private fun undottedReadIn(code: String, token: String): Boolean {
        var from = 0
        while (true) {
            val at = code.indexOf(token, from)
            if (at < 0) return false
            from = at + 1
            val before = if (at == 0) ' ' else code[at - 1]
            val afterAt = at + token.length
            val after = if (afterAt < code.length) code[afterAt] else ' '
            val wholeToken = !before.isLetterOrDigit() && before != '_' &&
                !after.isLetterOrDigit() && after != '_'
            if (wholeToken && before != '.') return true
        }
    }

    /** Every `.kt` file under [roots], read — never skipped — with every occurrence in it. */
    private fun sweep(roots: List<File>): Swept {
        val files = mutableListOf<String>()
        val occurrences = mutableListOf<Occurrence>()
        for (root in roots) {
            val sources = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.invariantSeparatorsPath }
            for (file in sources) {
                val relative = file.invariantSeparatorsPath
                files += relative
                if (file.name == OWN_FILE) continue
                val lines = try {
                    file.readLines()
                } catch (failure: Exception) {
                    // T5's rule: fail hard on a file that cannot be read, never skip it. A skipped
                    // file passes every assertion below over everything in it.
                    fail(
                        "could not read ${file.absolutePath}: ${failure::class.simpleName}: " +
                            "${failure.message}. A sweep that skips a file it cannot read is green " +
                            "over whatever that file says.",
                    )
                }
                occurrences += readsIn(relative, lines)
            }
        }
        return Swept(files, occurrences)
    }

    /** The two roots, each asserted present, with any undeclared test root on disk refused. */
    private fun testRoots(): List<File> {
        val roots = TEST_ROOTS.map { path ->
            val root = File(path)
            if (!root.isDirectory) {
                fail(
                    "$path was expected at ${root.absolutePath} but is not a directory. These tests run " +
                        "with the module directory as the working directory; this one is " +
                        "${File(".").absoluteFile.normalize()}. A sweep that silently skipped a test " +
                        "root would pass over every erased read in it.",
                )
            }
            root
        }
        val onDisk = File("src").listFiles { candidate: File -> candidate.isDirectory && candidate.name.endsWith("Test") }
            .orEmpty()
            .map { "src/${it.name}/kotlin" }
            .filter { File(it).isDirectory }
        val undeclared = onDisk.filter { it !in TEST_ROOTS && it !in ROOTS_DELIBERATELY_UNSWEPT }
        assertTrue(
            undeclared.isEmpty(),
            "test source root(s) $undeclared exist on disk but are neither swept nor declared in " +
                "ROOTS_DELIBERATELY_UNSWEPT ${ROOTS_DELIBERATELY_UNSWEPT.keys}. An erased read in an " +
                "unread root is exactly what this sweep exists to stop.",
        )
        return roots
    }

    @Test
    fun `every erased reflection read in the test tree is one of the two pinned permitted uses`() {
        val swept = sweep(testRoots())
        val allowed = permitted()

        val violations = swept.occurrences.filter { it.at !in allowed }

        assertEquals(
            emptyList(),
            violations.map { it.toString() },
            "`parameterTypes` and `returnType` report the type AFTER erasure, so a rule written over " +
                "them is satisfied by the forbidden type inside a collection: `List<Boolean>` passes " +
                "\"no Boolean as evidence of payment\" and `List<OrderEvent>` passes \"no OrderEvent " +
                "is reachable\". Read `genericParameterTypes` / `genericReturnType` and match on " +
                "`typeName` instead.\n" +
                "Neither permitted category may be widened to get green (T36). The exceptions this " +
                "file grants, in full:\n${permittedReport()}",
        )
    }

    /**
     * The pins are pinned to something real, the two categories are disjoint, and category one's
     * structural claim actually holds.
     *
     * Without this the test above is green over a pin set naming lines that no longer exist — which is
     * how a conversion silently un-converts. And `consumedBySize` is recomputed from the code text here,
     * so an "arity count" that became a type inspection cannot keep its exemption.
     */
    @Test
    fun `both permitted categories are pinned to reads that are really there, and do not overlap`() {
        val swept = sweep(testRoots())
        val byLocation = swept.occurrences.groupBy { it.at }

        val overlap = ARITY_COUNTING_PERMITTED.keys intersect ERASED_CLASS_PERMITTED.keys
        assertEquals(
            emptySet(),
            overlap,
            "an occurrence admitted by both categories is admitted by neither in particular: $overlap",
        )

        val stale = permitted().keys.filter { it !in byLocation }
        assertEquals(
            emptyList(),
            stale,
            "these permitted occurrences were not found in the tree. A pin naming a line that is gone " +
                "is an exemption nobody is using and a conversion nobody is watching; delete it, or " +
                "find where the read moved to.",
        )

        for (at in ARITY_COUNTING_PERMITTED.keys) {
            val occurrences = byLocation.getValue(at)
            assertTrue(
                occurrences.all { it.consumedBySize },
                "$at is pinned as arity counting, but the read is not immediately consumed by `.size`: " +
                    "${occurrences.map { it.code }}. Counting parameters is erasure-safe; inspecting " +
                    "their types is not, so this is a conversion and not an exception.",
            )
        }
        for (at in ERASED_CLASS_PERMITTED.keys) {
            val occurrences = byLocation.getValue(at)
            assertTrue(
                occurrences.none { it.consumedBySize },
                "$at is pinned as erased-`Class` semantics, but the read is consumed by `.size`, which " +
                    "is category one: ${occurrences.map { it.code }}",
            )
        }
    }

    /**
     * The non-vacuity floor. Every assertion above is equally true of a sweep pointed at an empty
     * directory, and an empty directory is exactly what a source-layout move produces.
     */
    @Test
    fun `the sweep reads both test roots, common test code included, and the two files a glob would miss`() {
        val roots = testRoots()
        val swept = sweep(roots)

        assertTrue(swept.files.isNotEmpty(), "the sweep found no Kotlin sources under $TEST_ROOTS")
        for (root in roots) {
            val fromRoot = swept.files.count { it.startsWith("${root.invariantSeparatorsPath}/") }
            assertTrue(
                fromRoot > 0,
                "${root.absolutePath} contributed no .kt file. A total over both roots cannot tell " +
                    "\"both roots were read\" from \"one root was read twice as hard\".",
            )
        }

        val names = swept.files.map { File(it).name }.toSet()
        assertTrue(
            COMMON_TEST_WITNESS in names,
            "$COMMON_TEST_WITNESS lives in src/commonTest/kotlin and was not among the " +
                "${swept.files.size} files read. A sweep that quietly skips common test code must not pass.",
        )
        val missing = FILES_PROVEN_READ - names
        assertEquals(
            emptySet(),
            missing,
            "T36 required these files be proven read, the last two because a `*StructureTest` glob " +
                "would have missed them: $missing were not among the ${swept.files.size} read.",
        )

        assertTrue(
            swept.occurrences.isNotEmpty(),
            "the sweep matched no occurrence anywhere, so \"every occurrence is permitted\" is a claim " +
                "about nothing — and the needles ${NEEDLES} would be the first thing to doubt.",
        )
    }

    /**
     * This file is excluded by name, the exclusion is **load-bearing**, and the sweep still sees the tree.
     *
     * All three halves matter: an exclusion that matched nothing is a comment, and an exclusion that
     * swallowed the tree is a green sweep over nothing.
     *
     * The middle half is asserted by running the matcher over this file's own text, rather than by
     * filtering the swept occurrences for it — that filter is empty by construction, because [sweep]
     * skips the file before the matcher ever sees it, so it would pass even if the needles had been
     * deleted. What must be true is that this file **would** have been flagged: the needles really are
     * in it as string literals, the stripper really does not hide them, and removing the exclusion
     * really would turn the sweep red against itself.
     */
    @Test
    fun `the sweep excludes its own source file by name, and the exclusion is load-bearing`() {
        val swept = sweep(testRoots())

        val ownFile = assertNotNull(
            swept.files.map { File(it) }.singleOrNull { it.name == OWN_FILE },
            "exactly one $OWN_FILE was expected among the ${swept.files.size} files walked; excluding a " +
                "name that is absent excludes nothing, and a second copy would be swept by neither.",
        )
        assertEquals(
            emptyList(),
            swept.occurrences.filter { File(it.path).name == OWN_FILE }.map { it.toString() },
            "$OWN_FILE holds both needles as string literals and the stripper does not reason about " +
                "strings, so it must contribute no occurrence.",
        )
        assertTrue(
            readsIn(OWN_FILE, ownFile.readLines()).isNotEmpty(),
            "unexcluded, $OWN_FILE must be flagged by its own matcher — the needles are in it as " +
                "string literals and the stripper does not reason about strings. If it is not, the " +
                "exclusion above is dead weight hiding nothing, and the next reader will trust it " +
                "to be hiding something.",
        )
        assertTrue(
            swept.occurrences.isNotEmpty(),
            "with its own file excluded the sweep found nothing at all, which is the exclusion " +
                "swallowing the tree rather than one file.",
        )
    }

    /**
     * The negative control: a non-arity erased read in a file under a temporary root is caught, and
     * named by file and line.
     *
     * The probe goes into `Files.createTempDirectory` under the module's own `build/` — not
     * `src/jvmTest/kotlin`, because a run killed before the cleanup would leave a scratch file STOP
     * RULE 8 forbids and the next `:jvmTest` compiles; and not the default temporary directory, because
     * `java.io.tmpdir` resolves to `/tmp`, which is read-only in the loop's sandbox. Removed in a
     * `finally`, so a failing assertion cannot leave it behind either.
     *
     * The probe carries **two** needles — one live, one in a comment — so this also pins that the live
     * one is caught and the commented one is not.
     */
    @Test
    fun `a non-arity erased read in a file under a temporary root is caught, named by file and line`() {
        assertTrue(
            PROBE_SOURCE.lines()[PROBE_LIVE_LINE - 1].contains(NEEDLES[1]) &&
                PROBE_SOURCE.lines()[PROBE_COMMENTED_LINE - 1].contains(NEEDLES[1]),
            "the probe must really carry a live read on line $PROBE_LIVE_LINE and a commented one on " +
                "line $PROBE_COMMENTED_LINE, or this control proves nothing: ${PROBE_SOURCE.lines()}",
        )

        val temporary = Files.createTempDirectory(File("build").toPath(), "erased-read-sweep").toFile()
        try {
            val probe = File(temporary, PROBE_FILE)
            probe.writeText(PROBE_SOURCE)

            val swept = sweep(listOf(temporary))
            val allowed = permitted()
            val violations = swept.occurrences.filter { it.at !in allowed }

            assertEquals(
                listOf("${probe.invariantSeparatorsPath}:$PROBE_LIVE_LINE"),
                violations.map { it.at },
                "the sweep must flag the live read on line $PROBE_LIVE_LINE, by file and line, and must " +
                    "not flag the commented one on line $PROBE_COMMENTED_LINE. It found " +
                    "${swept.occurrences.map { it.toString() }}",
            )
            assertEquals(
                false,
                violations.single().consumedBySize,
                "the probe is deliberately a non-arity read; if it reads as arity counting then " +
                    "category one would admit it and this control would be testing the wrong thing.",
            )
            assertTrue(
                swept.files.any { File(it).name == PROBE_FILE },
                "the sweep must have read the probe's file rather than inferred it: ${swept.files}",
            )
        } finally {
            temporary.deleteRecursively()
        }
    }

    /**
     * The paired positive control that gives the one above its meaning: every real constructor-arity
     * read in the source tree is **not** flagged.
     *
     * Asserted over the set the sweep itself enumerated rather than over a number, so the sweep that
     * would have red-lined legitimate code is demonstrably not this one — and so that a sweep which
     * matched nothing cannot pass by finding no arity uses to leave alone.
     */
    @Test
    fun `every constructor-arity read the sweep enumerated is left alone`() {
        val swept = sweep(testRoots())
        val allowed = permitted()

        val arity = swept.occurrences.filter { it.consumedBySize }

        assertTrue(
            arity.isNotEmpty(),
            "the sweep enumerated no arity-counting read at all, so \"legitimate code is not flagged\" " +
                "is a claim about an empty set. T36 found five, in delivery, seam and wire.",
        )
        assertEquals(
            ARITY_COUNTING_PERMITTED.keys,
            arity.map { it.at }.toSet(),
            "the arity-counting reads the sweep found are not the ones pinned as category one. A new " +
                "one needs an entry; a missing one moved or was converted.",
        )
        assertEquals(
            emptyList(),
            arity.filter { it.at !in allowed }.map { it.toString() },
            "counting a constructor's parameters is erasure-safe and must never be flagged; flagging it " +
                "is how a sweep like this one gets deleted instead of fixed.",
        )
    }

    /**
     * Every way around [NEEDLES] this file knows of, closed.
     *
     * [NEEDLES] require a leading `.`, so three ordinary Kotlin spellings reach the same erased array
     * and match nothing: the Java getter (`method.getReturnType()`), a callable reference
     * (`Method::parameterTypes`) and an implicit receiver (`with(method) { parameterTypes }`). A sweep
     * that matches zero lines passes forever with every assertion in the tree still erasure-blind,
     * taking its own controls green with it — T36's "a sweep is only as good as the inputs that survive
     * to reach it". No Kotlin here writes any of the three; this is what keeps that true, and it is the
     * reason the needles may stay as cheap as a substring search.
     */
    @Test
    fun `no test source reaches the erased property by a spelling the needles cannot see`() {
        val roots = testRoots()
        var codeLinesRead = 0
        val found = mutableListOf<String>()
        for (root in roots) {
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                if (file.name == OWN_FILE) continue
                for ((number, code) in AmbientEffectsTest.codeLines(file.readLines())) {
                    codeLinesRead++
                    val where = "${file.invariantSeparatorsPath}:$number — $code"
                    for (spelling in JAVA_GETTER_SPELLINGS) {
                        if (spelling in code) found += where
                    }
                    for (token in UNDOTTED_TOKENS) {
                        if (undottedReadIn(code, token)) found += where
                    }
                }
            }
        }

        assertTrue(
            codeLinesRead > 0,
            "the sweep read $codeLinesRead code lines, and \"the spelling is absent\" is true of nothing",
        )
        assertEquals(
            emptyList(),
            found,
            "$JAVA_GETTER_SPELLINGS and $UNDOTTED_TOKENS reach the same erased array in spellings the " +
                "needles $NEEDLES cannot see — a getter call, a callable reference, an implicit " +
                "receiver — so a read written any of those ways would be exempt from this whole file " +
                "by accident. Use `genericParameterTypes` / `genericReturnType`, or if the erased " +
                "`Class` is genuinely needed, write the dotted property form and pin it as category two.",
        )
    }

    /**
     * The stripper's half, pinned over an inline sample rather than over the tree.
     *
     * Every assertion above would hold just as well if [AmbientEffectsTest.codeLines] returned nothing:
     * a sweep sees no occurrence and is green. This pins both directions — a commented read really is
     * invisible, and a live one really is not — through the same matcher the real sweep runs.
     */
    @Test
    fun `a commented erased read is invisible to the sweep and a live one is not`() {
        val sample = listOf(
            "/** A KDoc mentioning .returnType and .parameterTypes, twice over. */",
            "val erased = method.returnType // and .parameterTypes again, in a tail comment",
            "/* opened and closed here */ val arity = constructor.parameterTypes.size",
            "    .parameterTypes.size == 2",
        )

        val found = readsIn("sample.kt", sample)

        assertEquals(
            listOf(2 to false, 3 to true, 4 to true),
            found.map { it.line to it.consumedBySize },
            "exactly three reads are code: the live `.returnType` on line 2, the arity count on line " +
                "3, and the chain continuation on line 4. The KDoc's two, and the tail comment's one, " +
                "must be invisible; the arity count shares its line with a block comment, which this " +
                "stripper was fixed twice to handle. Found $found",
        )
    }

    /**
     * The dotted and undotted matchers divide the same lines between them, with no line reported twice
     * and no spelling falling between them.
     *
     * The case worth pinning is the **chain continuation**: [AmbientEffectsTest.codeLines] trims, so
     * `constructor\n    .parameterTypes.size` reaches the matchers as the code line
     * `.parameterTypes.size`. Trimming removes the indentation but **not** the dot, so the token sits at
     * index 1 with a `.` before it — the dotted needle claims it and [undottedReadIn] correctly declines.
     * A review of this file predicted the opposite (a spurious red on legitimate pinned code, one
     * reformat away), which is worth an executable answer rather than an argument: a confusing red here
     * would invite the next implementer to widen an exception, the one move T36 forbids.
     *
     * The genuine implicit receiver trims to column 0 with no dot at all, and must still be caught.
     */
    @Test
    fun `a chain continuation belongs to the dotted matcher and an implicit receiver to the undotted one`() {
        val chainContinuation = ".parameterTypes.size == 2"
        val implicitReceiver = "parameterTypes"
        val callableReference = "val read = Method::parameterTypes"
        val converted = "val names = executable.genericParameterTypes.map { it.typeName }"

        assertEquals(
            listOf(1 to true),
            readsIn("sample.kt", listOf(chainContinuation)).map { it.line to it.consumedBySize },
            "a trimmed chain continuation keeps its leading dot, so the dotted needle must claim it " +
                "and it must read as arity counting",
        )
        assertEquals(
            false,
            undottedReadIn(chainContinuation, "parameterTypes"),
            "and the undotted matcher must decline it, or every multi-line chain in the tree becomes a " +
                "spurious red on code that is already pinned",
        )

        assertTrue(
            undottedReadIn(implicitReceiver, "parameterTypes"),
            "an implicit receiver trims to column 0 with no dot, and is the read the needles cannot see",
        )
        assertTrue(
            undottedReadIn(callableReference, "parameterTypes"),
            "a callable reference puts `:` before the name, which is not a receiver dot",
        )
        assertEquals(
            emptyList(),
            readsIn("sample.kt", listOf(converted)).map { it.toString() },
            "and the cured form must be invisible to the dotted needle: `genericParameterTypes` spells " +
                "the property with a capital P, so `.parameterTypes` cannot match inside it",
        )
        assertEquals(
            false,
            undottedReadIn(converted, "parameterTypes"),
            "nor may the cured form trip the undotted matcher, which would make conversion itself red",
        )
    }
}
