package dev.eryalabs.nenya.seam

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of `AmbientEffectsTest` that is common Kotlin: the forbidden patterns, the forbidden type
 * names, the comment stripper, and the two in-memory controls proving each discriminates.
 *
 * Abstract, and run as `AmbientEffectsTest` on every target, so the controls keep their names. The
 * sweeps they control read the main source roots from disk through `java.io.File`, and stay in the
 * JVM `AmbientEffectsTest`, whose KDoc gives the rule and why comments are excluded.
 */
abstract class PortableAmbientEffectsTest {

    /** `internal` so the JVM sweeps, and `KindConstantTest` through them, reuse the one stripper. */
    internal companion object {

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
            // Common Kotlin and Kotlin/JS spell the same effects differently, and every pattern
            // above is a JVM spelling: a clock read moved to src/commonMain as
            // `Clock.System.now()` would have passed straight over them. Proven by a scratch
            // file under build/ fed to this sweep before these were added (green) and after (red),
            // and pinned permanently by the in-memory control
            // `every common-Kotlin and JavaScript spelling of an ambient effect is caught`.
            "TimeSource.Monotonic" to Regex("""TimeSource\.Monotonic"""),
            // kotlin.time.Clock.System (stdlib, Kotlin 2.1.20+) and kotlinx.datetime.Clock.System
            // read identically at a call site; one pattern covers both, qualified or not.
            "Clock.System (kotlin.time or kotlinx-datetime)" to Regex("""\bClock\.System\b"""),
            // measureTime/measureTimedValue read TimeSource.Monotonic without naming it, and the
            // kotlin.system helpers read the wall or monotonic clock the same way.
            "measureTime and friends" to
                Regex("""\bmeasureTime(dValue)?\b|\bmeasureTimeMillis\b|\bmeasureNanoTime\b|\bgetTime(Millis|Nanos)\b"""),
            // `kotlin.random.Random` is imported by default, so the unseeded companion needs no
            // import to reach: `Random.Default`, `shuffled(Random)`, `val r: Random = Random`. Any
            // `Random` not followed by `(` is it; `Random(seed)` is deterministic and allowed.
            "the unseeded Random companion or Random.Default" to Regex("""\bRandom\b(?!\s*\()"""),
            // The collection helpers whose no-argument forms draw from Random.Default.
            "an unseeded random()/shuffled()" to Regex("""\.(random|randomOrNull|shuffled|shuffle)\(\s*\)"""),
            "Uuid.random" to Regex("""\bUuid\.random"""),
            // JavaScript, whether reached through kotlin.js.Date or inside a js("...") string.
            "Date.now" to Regex("""\bDate\.now\b"""),
            "performance.now or process.hrtime" to Regex("""\bperformance\.now\b|\bprocess\.hrtime\b"""),
            "Web Crypto or node:crypto randomness" to
                Regex("""\bcrypto\.(getRandomValues|randomUUID|randomBytes|randomInt)\b"""),
        )

        /**
         * The types that may never be *named* under any of `AmbientEffectsTest.MAIN_ROOTS` at all, in any form.
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
            // Kotlin/JS's wrapper over the JavaScript Date object, whose no-argument constructor
            // and `Date.now()` are the wall clock.
            "kotlin.js.Date",
        )

        /**
         * The type-name matcher the sweep below applies to every code line: which of
         * [FORBIDDEN_TYPES] [line] names. One function, so the control that probes it exercises the
         * very matcher the sweep runs rather than a copy of it.
         */
        fun typesNamedIn(line: String): List<String> = FORBIDDEN_TYPES.filter { line.contains(it) }

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

    /**
     * The stripper's own control for the two false negatives a mutation pass found in it: a line
     * that *closes* a block comment, and a line whose block comment opens and closes on it, were
     * each discarded whole — so `/* note */ val t = Instant.now()` passed the sweep silently.
     */
    @JsName("code_sharing_a_line_with_a_block_comment_is_still_swept")
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
     * The control for the common-Kotlin and JavaScript half of [FORBIDDEN].
     *
     * Every pattern that list carried before the multiplatform move was a JVM spelling, so a clock
     * read in `src/commonMain` as `Clock.System.now()` — or in a future `src/jsMain` as
     * `Date.now()` — passed the sweep. Each line below is one such read and must be caught; the two
     * after it must not be, or the new patterns are simply matching everything. In memory, like
     * the stripper control above, so no probe file can outlive the test.
     *
     * Each probe names the pattern that must catch it, and there is one probe per **alternation**,
     * not per pattern: a pattern such as `measureTime and friends` is five spellings, and a probe
     * of one of them says nothing about a typo in the other four. Naming the pattern also stops a
     * probe being satisfied by some unrelated, broader pattern that happens to match it.
     */
    @JsName("every_common_kotlin_and_javascript_spelling_of_an_ambient_effect_is_caught")
    @Test
    fun `every common-Kotlin and JavaScript spelling of an ambient effect is caught`() {
        val measure = "measureTime and friends"
        val hrtime = "performance.now or process.hrtime"
        val crypto = "Web Crypto or node:crypto randomness"
        val unseeded = "an unseeded random()/shuffled()"
        val ambient = listOf(
            "TimeSource.Monotonic" to "val a = TimeSource.Monotonic.markNow()",
            "Clock.System (kotlin.time or kotlinx-datetime)" to "val b = kotlin.time.Clock.System.now()",
            "Clock.System (kotlin.time or kotlinx-datetime)" to "val c = Clock.System.now()",
            "the unseeded Random companion or Random.Default" to "val d = Random.Default",
            "the unseeded Random companion or Random.Default" to "val e = listOf(1, 2).shuffled(Random)",
            unseeded to "val f = listOf(1, 2).random()",
            unseeded to "val f2 = listOf(1, 2).randomOrNull()",
            unseeded to "val f3 = listOf(1, 2).shuffled()",
            unseeded to "val f4 = mutableListOf(1, 2).shuffle()",
            "Date.now" to "val g = Date.now()",
            hrtime to "val h = js(\"performance.now()\")",
            hrtime to "val h2 = js(\"process.hrtime.bigint()\")",
            measure to "val i = measureTime { }",
            measure to "val i2 = measureTimedValue { 1 }",
            measure to "val i3 = measureTimeMillis { }",
            measure to "val i4 = measureNanoTime { }",
            measure to "val i5 = getTimeMillis()",
            measure to "val i6 = getTimeNanos()",
            "Uuid.random" to "val j = Uuid.random()",
            crypto to "val k = js(\"crypto.getRandomValues(new Uint8Array(32))\")",
            crypto to "val k2 = js(\"crypto.randomUUID()\")",
            crypto to "val k3 = js(\"crypto.randomBytes(32)\")",
            crypto to "val k4 = js(\"crypto.randomInt(6)\")",
        )
        val innocent = listOf(
            "val seeded = Random(42)",
            "fun draw(source: Randomness, clock: NenyaClock) = source",
        )
        fun caught(line: String): List<String> =
            FORBIDDEN.filter { (_, pattern) -> pattern.containsMatchIn(line) }.map { it.first }

        val names = FORBIDDEN.map { it.first }.toSet()
        for ((pattern, line) in ambient) {
            assertTrue(pattern in names, "probe names a pattern FORBIDDEN does not carry: $pattern")
            assertTrue(
                pattern in caught(line),
                "the pattern '$pattern' must itself catch this ambient read: $line (caught by ${caught(line)})",
            )
        }
        for (line in innocent) {
            assertEquals(emptyList(), caught(line), "the sweep must not flag this line: $line")
        }

        // The type half: run the sweep's own matcher over the stripped line, not a list lookup.
        val importLine = codeLines(listOf("import kotlin.js.Date")).single().second
        assertEquals(
            listOf("kotlin.js.Date"),
            typesNamedIn(importLine),
            "the JavaScript Date type must be caught by name by the matcher the sweep runs",
        )
        assertEquals(
            emptyList(),
            typesNamedIn("import dev.eryalabs.nenya.seam.NenyaClock"),
            "and the matcher must not flag an innocent import",
        )
    }
}
