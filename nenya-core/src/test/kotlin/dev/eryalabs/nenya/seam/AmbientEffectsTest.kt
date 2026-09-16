package dev.eryalabs.nenya.seam

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sweep that makes injection real rather than stylistic: **no source line under
 * `src/main/kotlin` reads an ambient clock or an ambient random source.**
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
 * `src/main/kotlin` contains a `//` inside a string today, and the failure mode of that gap is a
 * false *negative* on a construction no reviewer would let through anyway.
 */
class AmbientEffectsTest {

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

        const val MAIN: String = "src/main/kotlin"

        /** The `.kt` files under [MAIN] at commit 6432814, pinned as a floor for [mainSources]. */
        const val MIN_MAIN_SOURCES: Int = 27

        /**
         * Every way this library could learn the time or draw a random number without being
         * given one. Each is a regex over a non-comment source line.
         */
        val FORBIDDEN: List<Pair<String, Regex>> = listOf(
            "System.currentTimeMillis" to Regex("""System\.currentTimeMillis"""),
            "System.nanoTime" to Regex("""System\.nanoTime"""),
            "Instant.now" to Regex("""Instant\.now"""),
            "LocalDate*.now" to Regex("""LocalDate\w*\.now|LocalTime\.now|OffsetDateTime\.now|ZonedDateTime\.now"""),
            "Clock.system" to Regex("""Clock\.system"""),
            "Math.random" to Regex("""Math\.random"""),
            "SecureRandom" to Regex("""SecureRandom"""),
            "a bare Random()" to Regex("""\bRandom\(\)"""),
            // The six below were added after a mutation pass landed `java.util.Date()` in a shared
            // diagnostic string and this sweep stayed green. The KDoc above claims to enumerate
            // *every* way this library could learn the time or draw a number without being given
            // one, and a blacklist that names eight spellings of that is a claim about spellings.
            "java.util.Date" to Regex("""\bDate\(\)|java\.util\.Date"""),
            "Calendar.getInstance" to Regex("""Calendar\.getInstance"""),
            "UUID.randomUUID" to Regex("""UUID\.randomUUID"""),
            "ThreadLocalRandom" to Regex("""ThreadLocalRandom"""),
            // `\bRandom\(\)` catches only the *constructor*; Kotlin's companion-style RNG is
            // `Random.nextInt(...)` / `Random.Default.nextBytes(...)` and reads nothing like it.
            "a companion-style Random" to Regex("""\bRandom(\.Default)?\.next"""),
            "System.getenv or getProperty" to Regex("""System\.(getenv|getProperty)"""),
        )

        /**
         * The types that may never be *named* under `src/main/kotlin` at all, in any form.
         *
         * The allowlist half of the rule above, and the durable half: a blacklist of call spellings
         * is one new idiom away from being incomplete, whereas an import of `java.util.UUID` into a
         * library that is supposed to take all its randomness by injection has no innocent reading.
         */
        val FORBIDDEN_TYPES: List<String> = listOf(
            "java.util.Date",
            "java.util.UUID",
            "java.util.concurrent.ThreadLocalRandom",
            "java.security.SecureRandom",
            "kotlin.random",
        )

        /** The files this task adds, asserted by name. Several `.kt` files already existed. */
        val EXPECTED_SEAM_SOURCES: Set<String> = setOf("SeamAnswer.kt", "Seams.kt", "OrderId.kt")

        fun mainSources(): List<File> {
            val root = File(MAIN)
            if (!root.isDirectory) {
                fail(
                    "$MAIN was expected at ${root.absolutePath} but is not a directory. The tests run " +
                        "with the module directory as the working directory; this one is " +
                        "${File(".").absoluteFile.normalize()}. A sweep that silently found no files " +
                        "would pass over anything at all.",
                )
            }
            val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            // A directory that exists but holds fewer sources than it did is the quiet failure: after
            // code moves to another source root, both sweeps here and KindConstantTest's would pass
            // over whatever was left behind. 27 is the main tree at commit 6432814, a floor.
            assertTrue(
                sources.size >= MIN_MAIN_SOURCES,
                "${root.absolutePath} holds ${sources.size} .kt file(s); at least $MIN_MAIN_SOURCES " +
                    "were pinned. A sweep over part of the main tree passes over the rest.",
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
         * The stripper proper, over lines rather than a file, so its own control can exercise it on
         * a literal without writing a scratch file — `/tmp` is read-only in this session and a
         * probe file must not survive a task in any case.
         */
        fun codeLines(lines: List<String>): List<Pair<Int, String>> {
            val out = mutableListOf<Pair<Int, String>>()
            var inBlock = false
            lines.forEachIndexed { index, raw ->
                // Whatever survives comment removal on this line — which is not always nothing.
                // An earlier form of this stripper discarded any line that closed a block comment,
                // and any line whose block comment opened and closed on it, *whole*. So both
                // `/* note */ val t = Instant.now()` and `*/ val t = Instant.now()` passed the
                // sweep silently. Both are now reduced to their code tail rather than dropped.
                var rest = raw
                val code = StringBuilder()
                while (rest.isNotEmpty()) {
                    if (inBlock) {
                        val close = rest.indexOf("*/")
                        if (close < 0) { rest = ""; break }
                        inBlock = false
                        rest = rest.substring(close + 2)
                    } else {
                        val lineComment = rest.indexOf("//")
                        val blockOpen = rest.indexOf("/*")
                        when {
                            blockOpen >= 0 && (lineComment < 0 || blockOpen < lineComment) -> {
                                code.append(rest, 0, blockOpen)
                                inBlock = true
                                rest = rest.substring(blockOpen + 2)
                            }
                            lineComment >= 0 -> { code.append(rest, 0, lineComment); rest = "" }
                            else -> { code.append(rest); rest = "" }
                        }
                    }
                }
                val stripped = code.toString().trim()
                if (stripped.isNotBlank()) out += (index + 1) to stripped
            }
            return out
        }
    }

    @Test
    fun `the sweep reads the whole main tree, and this task's files by name`() {
        val sources = mainSources()

        assertTrue(sources.isNotEmpty(), "the sweep found no Kotlin sources under $MAIN")
        val names = sources.map { it.name }.toSet()
        for (expected in EXPECTED_SEAM_SOURCES) {
            assertTrue(
                expected in names,
                "$expected was not among the sources swept; found $names. Several .kt files already " +
                    "existed under $MAIN before this task — seven, not the three T5's text guessed " +
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
     * The allowlist half: these types may not be *named* under `src/main/kotlin` in any form.
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
                for (type in FORBIDDEN_TYPES) {
                    if (line.contains(type)) offences += "${file.path}:$number names $type — $line"
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
     * The stripper's own control for the two false negatives a mutation pass found in it: a line
     * that *closes* a block comment, and a line whose block comment opens and closes on it, were
     * each discarded whole — so `/* note */ val t = Instant.now()` passed the sweep silently.
     */
    @Test
    fun `code sharing a line with a block comment is still swept`() {
        val source = """
            val a = 1 /* trailing note */ + Instant.now()
            /* opens here
               and closes here */ val b = SecureRandom()
            /** doc mentioning SecureRandom */
            val c = 2 // Instant.now() in a line comment
        """.trimIndent().lines()

        val code = codeLines(source).map { it.second }

        assertTrue(code.any { it.contains("Instant.now") }, "a tail after */ on one line must be kept")
        assertTrue(code.any { it.contains("SecureRandom()") }, "a tail after a multi-line */ must be kept")
        assertTrue(code.none { it.contains("trailing note") }, "the comment body itself must be dropped")
        assertTrue(code.none { it.contains("doc mentioning") }, "KDoc must still be dropped")
        assertTrue(
            code.none { it.contains("in a line comment") },
            "and a // tail must still be dropped, or the KDoc exclusion this sweep relies on breaks",
        )
        assertEquals(
            2,
            code.count { line -> FORBIDDEN.any { (_, pattern) -> pattern.containsMatchIn(line) } },
            "both hidden effects must be visible to the sweep; before this fix neither was",
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
