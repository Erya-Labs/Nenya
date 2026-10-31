package dev.eryalabs.nenya.js

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `docs/INTEGRATING.md`'s `## Entry points` index names every function the JavaScript build exports,
 * each exactly once, and nothing else.
 *
 * ### Why the index is held to the compiled surface
 *
 * A guide listing entry points by hand goes stale the first time a task adds one. So the index is held
 * by exact set equality to [JsSurfaceTest.ENTRY_POINTS], which `JsSurfaceTest` itself holds to the
 * top-level functions of the compiled file classes. An export added without a bullet, a bullet for a
 * name nothing exports, and a name listed twice each turn this red.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest`. This one cannot be there: the pin it compares
 * against is reflection over the JVM build output, which is that rule's own named exception, and the
 * guide is read from disk, which a common test cannot do. The parser takes the text as a parameter, so
 * every control below runs on text built in memory, and only one test opens the file.
 */
class IntegrationGuideTest {

    private companion object {

        /** Gradle runs JVM tests with the module directory as the working directory. */
        const val GUIDE_PATH: String = "../docs/INTEGRATING.md"

        const val HEADING: String = "## Entry points"

        /**
         * The names [text]'s `## Entry points` section lists, in order and with repeats kept, so a
         * duplicate is visible to the caller.
         *
         * The section runs from its heading to the next `## ` heading or the end of the text. Every
         * line in it that starts with `- ` must be a ``- `name` — …`` bullet: a malformed one fails
         * here rather than being skipped, because a skipped bullet would let a name drop out of the
         * comparison silently. A text with no such section, or a section with no bullets, fails too.
         */
        fun entryPoints(text: String): List<String> {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trimEnd() == HEADING }
            if (start < 0) fail("the guide has no `$HEADING` section")
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }

            val names = section.filter { it.startsWith("- ") }.map { line ->
                val name = line.removePrefix("- `").substringBefore("` — ", missingDelimiterValue = "")
                if (!line.startsWith("- `") || name.isEmpty() || '`' in name || ' ' in name) {
                    fail("`$line` in `$HEADING` is not a ``- `name` — …`` bullet")
                }
                name
            }
            if (names.isEmpty()) fail("`$HEADING` lists no entry point")
            return names
        }

        /** The guide on disk. A missing file fails; it never skips. */
        fun read(file: File): String {
            assertTrue(
                file.isFile,
                "${file.path} was expected at ${file.absoluteFile.normalize()}; the working directory is " +
                    File(".").absoluteFile.normalize(),
            )
            return file.readText(Charsets.UTF_8)
        }

        /**
         * Fails unless [names] lists every entry point exactly once and nothing else, naming what is
         * missing, what is extra and what is repeated.
         */
        fun assertIndexed(names: List<String>) {
            val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertEquals(emptySet(), repeated, "names listed more than once in `$HEADING`")
            assertEquals(
                emptySet<String>() to emptySet(),
                (JsSurfaceTest.ENTRY_POINTS - names.toSet()) to (names.toSet() - JsSurfaceTest.ENTRY_POINTS),
                "(entry points the guide does not list) to (names it lists that nothing exports)",
            )
        }

        /** [text] with the bullet for [name] removed. */
        fun withoutBullet(text: String, name: String): String {
            val lines = text.lines()
            val bullet = lines.single { it.startsWith("- `$name` — ") }
            return (lines - bullet).joinToString("\n")
        }

        /** [text] with [bullet] inserted directly after the section heading. */
        fun withBullet(text: String, bullet: String): String =
            text.replaceFirst("$HEADING\n", "$HEADING\n$bullet\n")

        const val BUYER_HEADING: String = "## One trade, in order"

        const val PROVIDER_HEADING: String = "## The provider's side"

        val TRADE_HEADINGS: List<String> = listOf(BUYER_HEADING, PROVIDER_HEADING)

        const val CALLS: String = "Calls:"

        /** One numbered step of a trade section: its number, its prose lines, and its `Calls:` names. */
        data class TradeStep(val number: Int, val prose: List<String>, val calls: List<String>)

        /** [text]'s section under [heading], to the next `## ` heading or the end. A missing one fails. */
        fun section(text: String, heading: String): List<String> {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trimEnd() == heading }
            if (start < 0) fail("the guide has no `$heading` section")
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        }

        /** The step number a line opens, `null` for a line that opens no numbered step. */
        fun stepNumber(line: String): Int? {
            val digits = line.takeWhile { it.isDigit() }
            return if (digits.isNotEmpty() && line.startsWith("$digits. ")) digits.toInt() else null
        }

        /**
         * The numbered steps of [text]'s section under [heading], in order.
         *
         * A step runs from its `N. ` line through the blank and indented lines after it, and ends at
         * the next line that is neither. Each step must have **exactly one** `Calls:` line, and it must
         * be the step's last non-blank line, written ``Calls: `a`, `b`.``. A step without one, with
         * two, or with one that is not last or not in that form fails here; so does a `Calls:` line
         * outside any step, steps not numbered `1, 2, 3, …`, and a section with no steps. A missing
         * line fails rather than being skipped, because a skipped one would let a step's calls drop
         * out of the comparison silently.
         */
        fun tradeSteps(text: String, heading: String): List<TradeStep> {
            val steps = mutableListOf<Pair<Int, MutableList<String>>>()
            var open = false
            for (line in section(text, heading)) {
                val number = stepNumber(line)
                when {
                    number != null -> {
                        steps += number to mutableListOf(line)
                        open = true
                    }
                    open && (line.isBlank() || line.first().isWhitespace()) -> steps.last().second += line
                    else -> {
                        open = false
                        if (line.trimStart().startsWith(CALLS)) {
                            fail("`$line` in `$heading` is a `$CALLS` line outside any numbered step")
                        }
                    }
                }
            }
            if (steps.isEmpty()) fail("`$heading` has no numbered step")
            val numbers = steps.map { it.first }
            assertEquals((1..steps.size).toList(), numbers, "the steps of `$heading` are numbered 1, 2, 3, …")

            return steps.map { (number, lines) ->
                val callsLines = lines.filter { it.trimStart().startsWith(CALLS) }
                if (callsLines.size != 1) {
                    fail("step $number of `$heading` has ${callsLines.size} `$CALLS` lines, not exactly one")
                }
                val callsLine = callsLines.single()
                if (lines.last { it.isNotBlank() } != callsLine) {
                    fail("step $number of `$heading` does not end with its `$CALLS` line")
                }
                TradeStep(number, lines - callsLine, calls(callsLine.trim(), "step $number of `$heading`"))
            }
        }

        /** The names on one ``Calls: `a`, `b`.`` line. Any other form fails. */
        fun calls(line: String, where: String): List<String> {
            val list = line.removePrefix("$CALLS ")
            if (list == line || !list.endsWith(".")) fail("`$line` in $where is not a ``$CALLS `a`, `b`.`` line")
            return list.removeSuffix(".").split(", ").map { item ->
                val name = item.removeSurrounding("`")
                if (name == item || name.isEmpty() || '`' in name || ' ' in name) {
                    fail("`$item` in `$line` ($where) is not one backticked name")
                }
                name
            }
        }

        /** The backticked spans in [lines] — every inline code span, entry point or not. */
        fun codeSpans(lines: List<String>): Set<String> =
            lines.flatMap { line -> line.split('`').filterIndexed { index, _ -> index % 2 == 1 } }.toSet()

        /**
         * Fails unless every name on each step's `Calls:` line is an exported entry point, and every
         * entry point the step's prose names in backticks is on that line, naming the step and the
         * names on each side.
         */
        fun assertStepsHeld(heading: String, steps: List<TradeStep>) {
            val unexported = steps.associate { it.number to (it.calls.toSet() - JsSurfaceTest.ENTRY_POINTS) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap(), unexported, "`$heading`: step to the names on its `$CALLS` line nothing exports")
            val unlisted = steps.associate {
                it.number to (codeSpans(it.prose).intersect(JsSurfaceTest.ENTRY_POINTS) - it.calls.toSet())
            }.filterValues { it.isNotEmpty() }
            assertEquals(emptyMap(), unlisted, "`$heading`: step to the entry points its prose names and its `$CALLS` line omits")
        }

        /** Fails unless the provider's section names T65's evidence-gated release on a `Calls:` line. */
        fun assertProviderReleasesAfterPayment(steps: List<TradeStep>) {
            assertTrue(
                steps.any { "buildReleaseAfterPayment" in it.calls },
                "`$PROVIDER_HEADING` must call `buildReleaseAfterPayment`; it calls ${steps.flatMap { it.calls }.distinct()}",
            )
        }

        /** Every check above, over both trade sections of [text]. */
        fun assertTradeSections(text: String) {
            for (heading in TRADE_HEADINGS) assertStepsHeld(heading, tradeSteps(text, heading))
            assertProviderReleasesAfterPayment(tradeSteps(text, PROVIDER_HEADING))
        }

        /** [text] with its line at [index] removed. */
        fun withoutLine(text: String, index: Int): String =
            text.lines().filterIndexed { i, _ -> i != index }.joinToString("\n")

        /** [text] with the first line under [heading] that starts with [prefix], once trimmed, replaced by [transform] of it. */
        fun withLine(text: String, heading: String, prefix: String, transform: (String) -> String): String {
            val lines = text.lines().toMutableList()
            val start = lines.indexOfFirst { it.trimEnd() == heading }
            val index = (start + 1 until lines.size).first { lines[it].trimStart().startsWith(prefix) }
            lines[index] = transform(lines[index])
            return lines.joinToString("\n")
        }
    }

    private val guide: String by lazy { read(File(GUIDE_PATH)) }

    @Test
    fun `the guide's entry point index equals the exported entry points`() {
        val names = entryPoints(guide)
        assertIndexed(names)
        assertEquals(JsSurfaceTest.ENTRY_POINTS.size, names.size, "one bullet per entry point")
    }

    /** Negative control: every single dropped bullet is reported as exactly that missing name. */
    @Test
    fun `the index with any one bullet dropped fails`() {
        for (name in JsSurfaceTest.ENTRY_POINTS) {
            val names = entryPoints(withoutBullet(guide, name))
            assertNotEquals(JsSurfaceTest.ENTRY_POINTS, names.toSet(), "dropping `$name` changed nothing")
            assertFailsWith<AssertionError>("an index without `$name` must fail") { assertIndexed(names) }
        }
    }

    /** Negative control: a bullet for a name nothing exports fails. */
    @Test
    fun `the index with an unexported name added fails`() {
        val names = entryPoints(withBullet(guide, "- `buildEscrow` — not a function this library exports."))
        assertTrue("buildEscrow" in names, "the added bullet must be parsed")
        assertFailsWith<AssertionError> { assertIndexed(names) }
    }

    /** Negative control: an entry point listed twice fails, though the name set is unchanged. */
    @Test
    fun `the index with a name listed twice fails`() {
        val names = entryPoints(withBullet(guide, "- `seal` — listed a second time."))
        assertEquals(JsSurfaceTest.ENTRY_POINTS, names.toSet(), "the set alone cannot see a repeat")
        assertFailsWith<AssertionError> { assertIndexed(names) }
    }

    /** Negative control: a missing file fails rather than skipping, and so does a missing section. */
    @Test
    fun `a missing guide or a missing section fails and never skips`() {
        assertFailsWith<AssertionError> { read(File("../docs/NO-SUCH-GUIDE.md")) }
        assertFailsWith<AssertionError> { entryPoints(guide.replace(HEADING, "## Functions")) }
        assertFailsWith<AssertionError> { entryPoints("$HEADING\n\nNothing listed.\n") }
    }

    /** Negative control: a malformed bullet in the section fails rather than being skipped. */
    @Test
    fun `a malformed bullet in the index fails`() {
        assertFailsWith<AssertionError> { entryPoints(withBullet(guide, "- seal — no backticks.")) }
        assertFailsWith<AssertionError> { entryPoints(withBullet(guide, "- `seal`, missing the dash.")) }
    }

    /**
     * T66: every numbered step of both trade sections ends with one `Calls:` line, every name on it
     * is exported, every entry point its prose names is on it, and the provider's section, which
     * follows the buyer's, releases through `buildReleaseAfterPayment`.
     */
    @Test
    fun `each trade step's calls are held to the exported entry points`() {
        assertTradeSections(guide)
        val lines = guide.lines()
        assertTrue(
            lines.indexOf(PROVIDER_HEADING) > lines.indexOf(BUYER_HEADING),
            "`$PROVIDER_HEADING` follows `$BUYER_HEADING`",
        )
        val callsLines = lines.count { it.trimStart().startsWith(CALLS) }
        assertEquals(TRADE_HEADINGS.sumOf { tradeSteps(guide, it).size }, callsLines, "one `$CALLS` line per step, none elsewhere")
    }

    /** Negative control: removing any one step's `Calls:` line, in either section, fails. */
    @Test
    fun `the trade sections with any one Calls line removed fail`() {
        val indices = guide.lines().indices.filter { guide.lines()[it].trimStart().startsWith(CALLS) }
        assertTrue(indices.size >= 2 * TRADE_HEADINGS.size, "both sections have steps to remove from")
        for (index in indices) {
            assertFailsWith<AssertionError>("removing `${guide.lines()[index].trim()}` must fail") {
                assertTradeSections(withoutLine(guide, index))
            }
        }
    }

    /** Negative control: a name nothing exports on a `Calls:` line fails, in either section. */
    @Test
    fun `a Calls line naming an unexported function fails`() {
        for (heading in TRADE_HEADINGS) {
            val text = withLine(guide, heading, CALLS) { it.replaceFirst("$CALLS `", "$CALLS `buildEscrow`, `") }
            assertTrue("buildEscrow" in tradeSteps(text, heading).flatMap { it.calls }, "the added name must be parsed")
            assertFailsWith<AssertionError>("`buildEscrow` under `$heading` must fail") {
                assertStepsHeld(heading, tradeSteps(text, heading))
            }
        }
    }

    /** Negative control: an entry point the step's prose names but its `Calls:` line omits fails. */
    @Test
    fun `a Calls line omitting an entry point its step names fails`() {
        val text = withLine(guide, PROVIDER_HEADING, CALLS) { it.replace("`decodeListing`, ", "") }
        assertNotEquals(guide, text, "the provider's first step names `decodeListing`")
        assertFailsWith<AssertionError> { assertStepsHeld(PROVIDER_HEADING, tradeSteps(text, PROVIDER_HEADING)) }
    }

    /**
     * Negative control: the guide without the provider's section fails, and so does a provider's
     * section that never calls `buildReleaseAfterPayment` though its steps are otherwise held.
     */
    @Test
    fun `the guide without the provider's section or its evidence-gated release fails`() {
        assertFailsWith<AssertionError> { assertTradeSections(guide.replace(PROVIDER_HEADING, "## The seller's notes")) }

        val withoutGate = "$PROVIDER_HEADING\n\n1. **Release.** Build it with `buildRelease`.\n\n   $CALLS `buildRelease`.\n"
        val steps = tradeSteps(withoutGate, PROVIDER_HEADING)
        assertStepsHeld(PROVIDER_HEADING, steps)
        assertFailsWith<AssertionError> { assertProviderReleasesAfterPayment(steps) }
    }

    /** Negative control: a misplaced, doubled, stray or malformed `Calls:` line fails rather than being skipped. */
    @Test
    fun `a misplaced or malformed Calls line fails`() {
        val step = "$PROVIDER_HEADING\n\n1. **Bid.** Use `buildBid`.\n"
        tradeSteps("$step\n   $CALLS `buildBid`.\n", PROVIDER_HEADING)
        val broken = mapOf(
            "two lines" to "$step\n   $CALLS `buildBid`.\n   $CALLS `buildBid`.\n",
            "not last" to "$step\n   $CALLS `buildBid`.\n   Then publish it.\n",
            "no period" to "$step\n   $CALLS `buildBid`\n",
            "no backticks" to "$step\n   $CALLS buildBid.\n",
            "outside a step" to "$step\n   $CALLS `buildBid`.\n\n$CALLS `seal`.\n",
            "misnumbered" to "$step\n   $CALLS `buildBid`.\n3. **Seal.**\n\n   $CALLS `seal`.\n",
            "no step" to "$PROVIDER_HEADING\n\nNothing numbered.\n",
        )
        for ((what, text) in broken) {
            assertFailsWith<AssertionError>("a section with a `$CALLS` line $what must fail") {
                tradeSteps(text, PROVIDER_HEADING)
            }
        }
    }
}
