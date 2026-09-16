package dev.eryalabs.nenya.seam

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sweep that makes injection real rather than stylistic: **no source line under
 * `src/commonMain/kotlin`, `src/jvmMain/kotlin` or `src/jsMain/kotlin` reads an ambient clock or
 * an ambient random source.** Every production root is swept together, so code moved between
 * them cannot escape the rule by moving.
 *
 * Every module in this library takes its clock and its randomness by injection, and the reason is
 * not style. §4.6 requires every deadline be evaluated against the implementation's own injected
 * clock, and says in as many words that this is what makes every deadline rule testable offline.
 * §7.4 requires the order id come from a cryptographically secure source the client supplies.
 * Both rules are defeated the same way — one `Instant.now()` in a default value, one
 * `SecureRandom()` in a lazily-initialised field — and neither is defeated *visibly*: a test that
 * injects a fake clock passes straight over a code path that never consulted it.
 *
 * `Clock.system` is on the list for a specific reason. `java.time.Clock` is the obvious type for
 * a clock seam, and a *default* built from `Clock.systemUTC()` or `Clock.systemDefaultZone()` is
 * a live effect that every injected-fake test steps over. That is the hole a `java.time`-typed
 * seam walks straight into, and it is why this library's clock seam is named [NenyaClock] and its
 * default reports no time at all.
 *
 * ### Comment and KDoc lines are excluded, deliberately
 *
 * §7.4 requires the order id come from a cryptographically secure source, so the KDoc on
 * [Randomness] names `SecureRandom` as the thing a client ought to inject — which is exactly
 * where a client implementing that interface will look. A sweep that red-lined its own
 * documentation would be "fixed" by deleting the documentation, which is a worse outcome than the
 * one it was guarding against.
 *
 * The cost is stated rather than hidden: stripping `//` also strips anything after a `//` inside
 * a string literal, so a forbidden token hiding there would be missed. Nothing under
 * the main source roots contains a `//` inside a string today, and the failure mode of that gap is a
 * false *negative* on a construction no reviewer would let through anyway.
 *
 * ### Where these tests run
 *
 * The sweeps read the main source roots from disk through `java.io.File`, so they run on the JVM
 * only. The patterns they apply ([PortableAmbientEffectsTest.FORBIDDEN], [typesNamedIn]), the comment
 * stripper, and the two in-memory controls that prove the patterns and the stripper discriminate are
 * common Kotlin: they are in [PortableAmbientEffectsTest] in `src/commonTest`, which this class
 * extends, so the controls keep their `AmbientEffectsTest` names and run on JavaScript as well.
 */
class AmbientEffectsTest : PortableAmbientEffectsTest() {

    /**
     * `internal` rather than `private` so the sweeps in other packages can reuse [codeLines] and
     * [mainSources] instead of growing a second comment stripper.
     *
     * This one was fixed twice — once for a line that *closes* a block comment and once for a
     * block comment that opens and closes on one line, both of which it used to discard whole —
     * and a second copy would start out with both defects. §5.2's "expose `30404` once" sweep
     * (`KindConstantTest`) is the first caller; the queue's T9 says in as many words not to write
     * another.
     */
    internal companion object {

        /**
         * Every production source root, relative to the module directory. Since the move to
         * Kotlin Multiplatform nearly all production code is in `src/commonMain/kotlin`;
         * `src/jvmMain/kotlin` and `src/jsMain/kotlin` hold only the platform `actual`s. All three
         * compile into the same library and answer to the same sweeps. [mainSources] also fails
         * when a `src/<name>Main/kotlin` directory exists that this list does not name, so a new
         * target's root cannot be added without joining the sweep.
         */
        val MAIN_ROOTS: List<String> = listOf("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/jsMain/kotlin")

        /**
         * The `.kt` files under all of [MAIN_ROOTS] together, pinned as a floor for [mainSources].
         * 27 was the whole main tree at commit 6432814. Raised to 33 when production code moved to
         * `src/commonMain/kotlin`: the 27, plus `collections/ReadOnly.kt`, `crypto/Sha256.kt`,
         * `text/Utf8.kt`, and `platform/TypeNames.kt` with its JVM and JavaScript `actual` files.
         */
        const val MIN_MAIN_SOURCES: Int = 33

        /**
         * Per-root floors. A total over all roots cannot tell "the code is in common" from "the
         * code is in a root nobody reads": after the move a sweep over an almost-empty
         * `src/jvmMain/kotlin` must go red, not green. 31 is `src/commonMain/kotlin` after the move.
         */
        val MIN_SOURCES_PER_ROOT: Map<String, Int> = mapOf(
            "src/commonMain/kotlin" to 31,
            "src/jvmMain/kotlin" to 1,
            "src/jsMain/kotlin" to 1,
        )

        /** The files this task adds, asserted by name. Several `.kt` files already existed. */
        val EXPECTED_SEAM_SOURCES: Set<String> = setOf("SeamAnswer.kt", "Seams.kt", "OrderId.kt")

        fun mainSources(): List<File> {
            val roots = MAIN_ROOTS.map { path ->
                val root = File(path)
                if (!root.isDirectory) {
                    fail(
                        "$path was expected at ${root.absolutePath} but is not a directory. The tests " +
                            "run with the module directory as the working directory; this one is " +
                            "${File(".").absoluteFile.normalize()}. A sweep that silently skipped a " +
                            "source root would pass over everything in it.",
                    )
                }
                root
            }
            // A production root on disk that MAIN_ROOTS does not name is swept by nobody.
            val onDisk = File("src").listFiles { f: File -> f.isDirectory && f.name.endsWith("Main") }
                .orEmpty()
                .map { "src/${it.name}/kotlin" }
                .filter { File(it).isDirectory }
            val unswept = onDisk.filter { it !in MAIN_ROOTS }
            assertTrue(
                unswept.isEmpty(),
                "production source root(s) $unswept exist but are not in MAIN_ROOTS $MAIN_ROOTS; " +
                    "everything in them would pass these sweeps unread.",
            )
            val sources = roots.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            for ((path, floor) in MIN_SOURCES_PER_ROOT) {
                val count = File(path).walkTopDown().count { it.isFile && it.extension == "kt" }
                assertTrue(
                    count >= floor,
                    "$path holds $count .kt file(s); at least $floor were pinned. The production " +
                        "code is not where these sweeps were told it is.",
                )
            }
            // Roots that exist but hold fewer sources than the tree did are the quiet failure: after
            // code moves to a root this list does not name, both sweeps here and KindConstantTest's
            // would pass over it. 27 is the main tree at commit 6432814, a floor over all roots.
            assertTrue(
                sources.size >= MIN_MAIN_SOURCES,
                "${roots.map { it.absolutePath }} hold ${sources.size} .kt file(s); at least " +
                    "$MIN_MAIN_SOURCES were pinned. A sweep over part of the main tree passes over the rest.",
            )
            return sources
        }

        /**
         * The lines of [file] that are actually code: block comments and KDoc dropped whole, `//`
         * tails removed, blank remainders skipped. Returns one-based line numbers so a failure
         * names a place a reviewer can open.
         */
        fun codeLines(file: File): List<Pair<Int, String>> = codeLines(file.readLines())

        /**
         * The stripper proper, [PortableAmbientEffectsTest.codeLines], under this object's name so the
         * sweeps in other packages (`KindConstantTest`) reach both overloads in one place.
         */
        fun codeLines(lines: List<String>): List<Pair<Int, String>> = PortableAmbientEffectsTest.codeLines(lines)
    }

    @Test
    fun `the sweep reads the whole main tree, and this task's files by name`() {
        val sources = mainSources()

        assertTrue(sources.isNotEmpty(), "the sweep found no Kotlin sources under $MAIN_ROOTS")
        val names = sources.map { it.name }.toSet()
        assertTrue(
            "NenyaProtocol.kt" in names,
            "NenyaProtocol.kt lives in src/commonMain/kotlin and was not among the sources swept; " +
                "found $names. Common code must answer to the same sweep as JVM code.",
        )
        for (expected in EXPECTED_SEAM_SOURCES) {
            assertTrue(
                expected in names,
                "$expected was not among the sources swept; found $names. Several .kt files already " +
                    "existed under the main tree before this task — seven, not the three T5's text guessed " +
                    "— so a bare count is satisfied well before this task writes a line.",
            )
        }
        assertTrue(
            sources.sumOf { codeLines(it).size } > 100,
            "the comment stripper left almost nothing to check, which would make every assertion " +
                "below vacuously true",
        )
    }

    @Test
    fun `no non-comment source line reads an ambient clock or an ambient random source`() {
        val offences = mutableListOf<String>()

        for (file in mainSources()) {
            for ((number, line) in codeLines(file)) {
                for ((name, pattern) in FORBIDDEN) {
                    if (pattern.containsMatchIn(line)) {
                        offences += "${file.path}:$number mentions $name — $line"
                    }
                }
            }
        }

        assertEquals(
            emptyList(),
            offences,
            "§4.6 makes the injected clock authoritative for every deadline and §7.4 requires the " +
                "order id come from an injected cryptographically secure source. A library that " +
                "reads either ambiently is untestable offline and, worse, is untestable *invisibly* " +
                "— an injected-fake test passes straight over a path that never consulted the fake.",
        )
    }

    /**
     * The allowlist half: these types may not be *named* under any main source root in any form.
     *
     * A blacklist of call spellings is one new idiom away from being incomplete — `java.util.Date()`
     * defeated the original eight. An import of `java.util.UUID` into a library that takes all its
     * randomness by injection has no innocent reading, so the type is banned outright rather than
     * the handful of ways to call it.
     */
    @Test
    fun `no forbidden time or randomness type is named under the main tree at all`() {
        val offences = mutableListOf<String>()

        for (file in mainSources()) {
            for ((number, line) in codeLines(file)) {
                for (type in typesNamedIn(line)) {
                    offences += "${file.path}:$number names $type — $line"
                }
            }
        }

        assertEquals(
            emptyList(),
            offences,
            "§4.6 and §7.4 make the clock and the randomness injected. A main source that so much " +
                "as names one of these types is either reading an ambient effect or about to.",
        )
    }

    /**
     * The stripper's own control. If it were broken — dropping every line, say — the sweep above
     * would be green over any source tree at all, and this is the assertion that would notice.
     */
    @Test
    fun `the comment stripper keeps code and drops documentation`() {
        val seams = mainSources().single { it.name == "Seams.kt" }
        val code = codeLines(seams).map { it.second }

        assertTrue(
            code.any { it.contains("interface Randomness") },
            "the stripper dropped a declaration it should have kept",
        )
        assertTrue(
            code.none { it.contains("SecureRandom") },
            "the KDoc on the randomness seam names SecureRandom as what a client ought to inject, " +
                "and the stripper must not surface it as code",
        )
        assertTrue(
            code.none { it.contains("Clock.systemUTC") },
            "the KDoc on the clock seam names Clock.systemUTC() as the hole it exists to avoid",
        )
        assertTrue(
            mainSources().single { it.name == "Seams.kt" }.readText().contains("SecureRandom"),
            "…and that KDoc must actually still be there, or this control proves nothing",
        )
    }
}
