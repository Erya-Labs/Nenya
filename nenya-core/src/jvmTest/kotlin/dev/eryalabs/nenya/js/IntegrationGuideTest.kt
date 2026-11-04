package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.conformance.MainClasses
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

        const val FEE_RECIPIENT_HEADING: String = "## The fee recipient's side"

        val TRADE_HEADINGS: List<String> = listOf(BUYER_HEADING, PROVIDER_HEADING, FEE_RECIPIENT_HEADING)

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

        /**
         * T74: the entry points each buyer step must call so that a page retypes nothing a library door
         * gives — step 4 opens the order from the proposal, step 7 takes §8.4's proposal and acceptance
         * points from their doors, and step 8 adds the fee request's.
         */
        val BUYER_DOOR_CALLS: Map<Int, Set<String>> = mapOf(
            4 to setOf("openOrderFromProposal"),
            7 to setOf("proposalFeeSighting", "acceptanceFeeSighting"),
            8 to setOf("paymentRequestFeeSighting"),
        )

        /** Fails unless each buyer step in [BUYER_DOOR_CALLS] names its doors on its `Calls:` line. */
        fun assertBuyerTakesDoors(steps: List<TradeStep>) {
            val missing = BUYER_DOOR_CALLS.mapValues { (number, doors) ->
                doors - (steps.singleOrNull { it.number == number }?.calls?.toSet() ?: emptySet())
            }.filterValues { it.isNotEmpty() }
            assertEquals(emptyMap(), missing, "`$BUYER_HEADING`: step to the doors its `$CALLS` line omits")
        }

        /**
         * T78: the door each buyer step receiving a message steps the order through, so that a page
         * passes no `fromParty` and retypes no commitment or release — step 5 the acceptance, step 6
         * the commitment and step 9 the release.
         */
        val BUYER_RECEIVING_DOORS: Map<Int, String> = mapOf(
            5 to "stepOrderWithStatusUpdate",
            6 to "stepOrderWithCommitment",
            9 to "stepOrderWithRelease",
        )

        /** Fails unless each buyer step in [BUYER_RECEIVING_DOORS] names its door on its `Calls:` line. */
        fun assertBuyerStepsReceived(steps: List<TradeStep>) {
            val missing = BUYER_RECEIVING_DOORS.filter { (number, door) ->
                door !in (steps.singleOrNull { it.number == number }?.calls ?: emptyList())
            }
            assertEquals(emptyMap(), missing, "`$BUYER_HEADING`: step to the receiving door its `$CALLS` line omits")
        }

        /**
         * T102: the refusal each buyer step's prose must name — step 9 the early release's, which a
         * buyer's order still `awaiting_payment` answers because the provider does not wait for the fee.
         */
        val BUYER_STEP_REFUSALS: Map<Int, String> = mapOf(
            9 to "RELEASE_ORDER_NOT_PAID",
        )

        /** Fails unless each buyer step in [BUYER_STEP_REFUSALS] names its refusal in backticks in its prose. */
        fun assertBuyerStepsNameRefusals(steps: List<TradeStep>) {
            val missing = BUYER_STEP_REFUSALS.filter { (number, refusal) ->
                refusal !in codeSpans(steps.singleOrNull { it.number == number }?.prose ?: emptyList())
            }
            assertEquals(emptyMap(), missing, "`$BUYER_HEADING`: step to the refusal its prose does not name")
        }

        /** [text] with [name] removed from the one buyer `Calls:` line that names it. */
        fun withoutBuyerCall(text: String, name: String): String {
            val lines = text.lines().toMutableList()
            val start = lines.indexOf(BUYER_HEADING)
            val end = lines.indexOf(PROVIDER_HEADING)
            val index = (start + 1 until end).single { lines[it].trimStart().startsWith(CALLS) && "`$name`" in lines[it] }
            lines[index] = lines[index].replace(", `$name`", "").replace("`$name`, ", "")
            return lines.joinToString("\n")
        }

        /**
         * T88: decision T's two doors, each pinned to the step that uses it — the buyer's step 7 sends
         * §8.7's copy through `proposalForFeeRecipient`, and the fee recipient's step 2 builds its request
         * from that copy through `buildFeeRequestFromProposal`.
         */
        val FEE_RECIPIENT_DOORS: Map<Pair<String, Int>, String> = mapOf(
            (BUYER_HEADING to 7) to "proposalForFeeRecipient",
            (FEE_RECIPIENT_HEADING to 2) to "buildFeeRequestFromProposal",
        )

        /** Fails unless each step in [FEE_RECIPIENT_DOORS] names its door on its `Calls:` line. */
        fun assertFeeRecipientDoors(text: String) {
            val missing = FEE_RECIPIENT_DOORS.filter { (step, door) ->
                val (heading, number) = step
                door !in (tradeSteps(text, heading).singleOrNull { it.number == number }?.calls ?: emptyList())
            }
            assertEquals(emptyMap(), missing, "(section, step) to the decision T door its `$CALLS` line omits")
        }

        /** [text] with [name] removed from the one `Calls:` line under [heading] that names it. */
        fun withoutCall(text: String, heading: String, name: String): String {
            val lines = text.lines().toMutableList()
            val start = lines.indexOf(heading)
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
            val index = (start + 1 until end).single { lines[it].trimStart().startsWith(CALLS) && "`$name`" in lines[it] }
            lines[index] = lines[index].replace(", `$name`", "").replace("`$name`, ", "")
            return lines.joinToString("\n")
        }

        /** Every check above, over every trade section of [text]. */
        fun assertTradeSections(text: String) {
            for (heading in TRADE_HEADINGS) assertStepsHeld(heading, tradeSteps(text, heading))
            assertProviderReleasesAfterPayment(tradeSteps(text, PROVIDER_HEADING))
            assertBuyerTakesDoors(tradeSteps(text, BUYER_HEADING))
            assertBuyerStepsReceived(tradeSteps(text, BUYER_HEADING))
            assertBuyerStepsNameRefusals(tradeSteps(text, BUYER_HEADING))
            assertFeeRecipientDoors(text)
        }

        /** A backticked text that reads as a constant's name: all of it, not a part of it. */
        val CONSTANT_TOKEN: Regex = Regex("[A-Z][A-Z0-9_]{3,}")

        /**
         * T81: every backticked text in [text] that is wholly a [CONSTANT_TOKEN], in order of first
         * appearance. Fenced code blocks are skipped — their lines are code, not inline spans — and an
         * inline span may run across a line break, as Markdown allows. A text whose backticks outside
         * the fences do not pair up fails rather than being read off by one.
         */
        fun constantTokens(text: String): Set<String> {
            val prose = mutableListOf<String>()
            var fenced = false
            for (line in text.lines()) {
                if (line.trimStart().startsWith("```")) fenced = !fenced else if (!fenced) prose += line
            }
            if (fenced) fail("the guide has an unclosed code fence")
            val parts = prose.joinToString("\n").split('`')
            if (parts.size % 2 == 0) fail("the guide's backticks outside code fences do not pair up")
            return parts.filterIndexed { index, _ -> index % 2 == 1 }
                .filter { CONSTANT_TOKEN.matches(it) }
                .toCollection(LinkedHashSet())
        }

        /**
         * Every constant of every enum this library publishes, by name — the class walk `JsSurfaceTest`
         * uses, over every package `java.lang.reflect` finds in the main output.
         */
        val PUBLISHED_ENUM_CONSTANTS: Set<String> by lazy {
            MainClasses.allPackages().flatMap { packageName ->
                MainClasses.published(packageName).filter { it.isEnum }.flatMap { type ->
                    type.enumConstants.map { (it as Enum<*>).name }
                }
            }.toSet()
        }

        /** Fails unless every constant-like token in [text] is a published enum constant, naming the rest. */
        fun assertTokensAreConstants(text: String) {
            assertEquals(
                emptySet(),
                constantTokens(text) - PUBLISHED_ENUM_CONSTANTS,
                "backticked names in the guide that no published enum declares",
            )
        }

        /** T103: the phrase each side's section must hold, saying a fee is not enforced (decision U). */
        val FEE_NOT_ENFORCED_PHRASES: Map<String, String> = mapOf(
            PROVIDER_HEADING to "open no order machine",
            FEE_RECIPIENT_HEADING to "holds no acceptance",
        )

        /** [lines] joined with every run of whitespace collapsed to one space, so a phrase may wrap. */
        fun collapsed(lines: List<String>): String = lines.joinToString(" ").split(Regex("\\s+")).joinToString(" ")

        /** Fails unless each section in [FEE_NOT_ENFORCED_PHRASES] holds its phrase, naming the ones missing. */
        fun assertFeeNotEnforcedSaid(text: String) {
            val missing = FEE_NOT_ENFORCED_PHRASES.filter { (heading, phrase) -> phrase !in collapsed(section(text, heading)) }
            assertEquals(emptyMap(), missing, "section to the phrase it does not hold")
        }

        /** T104: the sentence the guide, the overview and the README each hold (decision U). */
        const val FEE_NOT_ENFORCED: String = "A fee is not enforced against the buyer"

        /** T104: each document that must say [FEE_NOT_ENFORCED], by its path from `nenya-core`. */
        val FEE_NOT_ENFORCED_DOCUMENTS: List<String> = listOf(GUIDE_PATH, "../spec/NENYA-1-overview.md", "../README.md")

        /** Fails unless each text in [texts], keyed by path, holds [FEE_NOT_ENFORCED], naming the ones that do not. */
        fun assertFeeNotEnforcedEverywhere(texts: Map<String, String>) {
            val missing = texts.filterValues { FEE_NOT_ENFORCED !in collapsed(it.lines()) }.keys
            assertEquals(emptySet(), missing, "documents that do not say `$FEE_NOT_ENFORCED`")
        }

        /** T105: the companion, by its path from `nenya-core`. */
        const val COMPANION_PATH: String = "../spec/NENYA-1-companion.md"

        /** T105: the sentence Appendix A's step 8 holds (decision U). */
        const val PROVIDER_DOES_NOT_WAIT: String = "P does not see F's receipt and does not wait for it."

        /** Fails unless [text] holds [PROVIDER_DOES_NOT_WAIT], whitespace collapsed. */
        fun assertProviderDoesNotWait(text: String) {
            assertTrue(PROVIDER_DOES_NOT_WAIT in collapsed(text.lines()), "the companion says `$PROVIDER_DOES_NOT_WAIT`")
        }

        /** T106: the phrase each figure must hold, by its path from `nenya-core` (decision U). */
        val FIGURE_PHRASES: Map<String, String> = mapOf(
            "../docs/trade-sequence.svg" to "the fee recipient holds no order",
            "../docs/order-lifecycle.svg" to "A provider releases on its own receipt",
        )

        /** Fails unless each text in [texts], keyed by path, holds its phrase in [FIGURE_PHRASES], naming the ones that do not. */
        fun assertFiguresSay(texts: Map<String, String>) {
            val missing = texts.filter { (path, text) -> FIGURE_PHRASES.getValue(path) !in collapsed(text.lines()) }.keys
            assertEquals(emptySet(), missing, "figures that do not hold their phrase")
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

    /**
     * T74: the buyer's step 4 calls `openOrderFromProposal`, step 7 the proposal's and the acceptance's
     * sighting doors, and step 8 the fee request's, and the steps around them still hold.
     */
    @Test
    fun `the buyer's steps take the order and every sighting from the library's doors`() {
        val steps = tradeSteps(guide, BUYER_HEADING)
        assertBuyerTakesDoors(steps)
        assertStepsHeld(BUYER_HEADING, steps)
        for ((number, doors) in BUYER_DOOR_CALLS) {
            assertTrue(doors.all { it in JsSurfaceTest.ENTRY_POINTS }, "step $number's doors are exported: $doors")
        }
    }

    /**
     * Negative control: any one door dropped from its buyer step's `Calls:` line — among them
     * `paymentRequestFeeSighting` from step 8 — fails, while every other step's line is untouched.
     */
    @Test
    fun `the buyer's steps with any one door dropped fail`() {
        for ((number, doors) in BUYER_DOOR_CALLS) {
            for (door in doors) {
                val lines = guide.lines().toMutableList()
                val start = lines.indexOf(BUYER_HEADING)
                val index = (start + 1 until lines.size).single {
                    lines[it].trimStart().startsWith(CALLS) && "`$door`" in lines[it] && lines.indexOf(PROVIDER_HEADING) > it
                }
                lines[index] = lines[index].replace(", `$door`", "").replace("`$door`, ", "")
                val text = lines.joinToString("\n")
                val step = tradeSteps(text, BUYER_HEADING).single { it.number == number }
                assertTrue(door !in step.calls, "`$door` must be gone from step $number's `$CALLS` line")
                assertFailsWith<AssertionError>("step $number without `$door` must fail") {
                    assertBuyerTakesDoors(tradeSteps(text, BUYER_HEADING))
                }
            }
        }
    }

    /**
     * T78: the buyer's steps receiving the acceptance, the commitment and the release each name the
     * door that steps the order from that message, the doors are exported, and the steps still hold.
     */
    @Test
    fun `the buyer's receiving steps step the order through the doors that read the message`() {
        val steps = tradeSteps(guide, BUYER_HEADING)
        assertBuyerStepsReceived(steps)
        assertStepsHeld(BUYER_HEADING, steps)
        assertTrue(
            BUYER_RECEIVING_DOORS.values.all { it in JsSurfaceTest.ENTRY_POINTS },
            "the receiving doors are exported: ${BUYER_RECEIVING_DOORS.values}",
        )
    }

    /**
     * Negative control: any one receiving door dropped from its buyer step's `Calls:` line — among them
     * `stepOrderWithRelease` from step 9 — fails.
     */
    @Test
    fun `the buyer's receiving steps with any one door dropped fail`() {
        for ((number, door) in BUYER_RECEIVING_DOORS) {
            val text = withoutBuyerCall(guide, door)
            val step = tradeSteps(text, BUYER_HEADING).single { it.number == number }
            assertTrue(door !in step.calls, "`$door` must be gone from step $number's `$CALLS` line")
            assertFailsWith<AssertionError>("step $number without `$door` must fail") {
                assertBuyerStepsReceived(tradeSteps(text, BUYER_HEADING))
            }
        }
    }

    /**
     * T102: the buyer's step 9 names `RELEASE_ORDER_NOT_PAID`, the refusal an early release meets while
     * the buyer's order awaits the fee, and that name is a published constant.
     */
    @Test
    fun `the buyer's release step names the early release's refusal`() {
        assertBuyerStepsNameRefusals(tradeSteps(guide, BUYER_HEADING))
        assertTrue(
            BUYER_STEP_REFUSALS.values.all { it in PUBLISHED_ENUM_CONSTANTS },
            "the refusals are published constants: ${BUYER_STEP_REFUSALS.values}",
        )
        assertTokensAreConstants(guide)
    }

    /** Negative control: `RELEASE_ORDER_NOT_PAID` misspelt fails, and so does step 9 without the name. */
    @Test
    fun `the buyer's release step with its refusal misspelt or dropped fails`() {
        val misspelt = guide.replace("`RELEASE_ORDER_NOT_PAID`", "`RELEASE_ORDER_UNPAID`")
        assertNotEquals(guide, misspelt, "the guide names `RELEASE_ORDER_NOT_PAID`")
        assertFailsWith<AssertionError> { assertTokensAreConstants(misspelt) }
        assertFailsWith<AssertionError> { assertBuyerStepsNameRefusals(tradeSteps(misspelt, BUYER_HEADING)) }

        val dropped = guide.replace("refuses it `RELEASE_ORDER_NOT_PAID`", "refuses it")
        assertNotEquals(guide, dropped, "step 9 names `RELEASE_ORDER_NOT_PAID`")
        assertTokensAreConstants(dropped)
        assertFailsWith<AssertionError> { assertBuyerStepsNameRefusals(tradeSteps(dropped, BUYER_HEADING)) }
    }

    /**
     * T103: the provider's section says its steps open no order machine, and the fee recipient's that it
     * holds no acceptance, each outside the numbered steps, which still hold (decision U).
     */
    @Test
    fun `the provider's and fee recipient's sections say a fee is not enforced`() {
        assertFeeNotEnforcedSaid(guide)
        for ((heading, phrase) in FEE_NOT_ENFORCED_PHRASES) {
            assertStepsHeld(heading, tradeSteps(guide, heading))
            val inSteps = tradeSteps(guide, heading).any { phrase in collapsed(it.prose) }
            assertTrue(!inSteps, "`$phrase` stands outside the numbered steps of `$heading`")
        }
    }

    /** Negative control: deleting either sentence's phrase fails. */
    @Test
    fun `the provider's or fee recipient's section without its phrase fails`() {
        for ((heading, phrase) in FEE_NOT_ENFORCED_PHRASES) {
            assertEquals(1, guide.split(phrase).size - 1, "`$phrase` stands once in the guide, on one line")
            val text = guide.replace(phrase, "")
            assertFailsWith<AssertionError>("`$heading` without `$phrase` must fail") { assertFeeNotEnforcedSaid(text) }
        }
    }

    /** T104: the guide, the overview and the README each say a fee is not enforced against the buyer (decision U). */
    @Test
    fun `the guide, the overview and the README say a fee is not enforced`() {
        assertFeeNotEnforcedEverywhere(FEE_NOT_ENFORCED_DOCUMENTS.associateWith { read(File(it)) })
    }

    /** Negative control: deleting the sentence from any one of the three fails, naming that one. */
    @Test
    fun `any one document without the fee sentence fails`() {
        val texts = FEE_NOT_ENFORCED_DOCUMENTS.associateWith { collapsed(read(File(it)).lines()) }
        for (path in FEE_NOT_ENFORCED_DOCUMENTS) {
            val deleted = texts + (path to texts.getValue(path).replace(FEE_NOT_ENFORCED, ""))
            val failure = assertFailsWith<AssertionError>("`$path` without the sentence must fail") {
                assertFeeNotEnforcedEverywhere(deleted)
            }
            assertTrue(path in failure.message.orEmpty(), "the failure names `$path`")
        }
    }

    /** T105: the companion's worked order says the provider does not see or wait for the fee receipt (decision U). */
    @Test
    fun `the companion's worked order says the provider does not wait for the fee receipt`() {
        assertProviderDoesNotWait(read(File(COMPANION_PATH)))
    }

    /** Negative control: the companion without that sentence fails. */
    @Test
    fun `the companion without the provider's sentence fails`() {
        val text = collapsed(read(File(COMPANION_PATH)).lines())
        val deleted = text.replace(PROVIDER_DOES_NOT_WAIT, "")
        assertNotEquals(text, deleted, "the companion holds the sentence")
        assertFailsWith<AssertionError> { assertProviderDoesNotWait(deleted) }
    }

    /** T106: the trade sequence says the fee recipient holds no order, and the lifecycle whose order it draws (decision U). */
    @Test
    fun `the figures say whose order it is and that the fee recipient holds none`() {
        assertFiguresSay(FIGURE_PHRASES.keys.associateWith { read(File(it)) })
    }

    /** Negative control: deleting the phrase from either figure fails, naming that figure. */
    @Test
    fun `either figure without its phrase fails`() {
        val texts = FIGURE_PHRASES.keys.associateWith { collapsed(read(File(it)).lines()) }
        for ((path, phrase) in FIGURE_PHRASES) {
            val deleted = texts + (path to texts.getValue(path).replace(phrase, ""))
            assertNotEquals(texts, deleted, "`$path` holds `$phrase`")
            val failure = assertFailsWith<AssertionError>("`$path` without its phrase must fail") { assertFiguresSay(deleted) }
            assertTrue(path in failure.message.orEmpty(), "the failure names `$path`")
        }
    }

    /**
     * T88: the buyer's step 7 calls `proposalForFeeRecipient` and the fee recipient's step 2
     * `buildFeeRequestFromProposal`; the fee recipient's section follows the provider's and comes before
     * `## What Nenya does not check`, and its steps hold.
     */
    @Test
    fun `decision T's doors are pinned to the buyer's step 7 and the fee recipient's step 2`() {
        assertFeeRecipientDoors(guide)
        assertStepsHeld(FEE_RECIPIENT_HEADING, tradeSteps(guide, FEE_RECIPIENT_HEADING))
        assertTrue(FEE_RECIPIENT_DOORS.values.all { it in JsSurfaceTest.ENTRY_POINTS }, "the doors are exported")
        val lines = guide.lines()
        val at = lines.indexOf(FEE_RECIPIENT_HEADING)
        assertTrue(at > lines.indexOf(PROVIDER_HEADING), "`$FEE_RECIPIENT_HEADING` follows `$PROVIDER_HEADING`")
        assertTrue(at < lines.indexOf("## What Nenya does not check"), "and comes before `## What Nenya does not check`")
    }

    /** Negative control: either door dropped from its step's `Calls:` line fails. */
    @Test
    fun `decision T's doors with either one dropped from its Calls line fail`() {
        for ((step, door) in FEE_RECIPIENT_DOORS) {
            val (heading, number) = step
            val text = withoutCall(guide, heading, door)
            assertTrue(door !in tradeSteps(text, heading).single { it.number == number }.calls, "`$door` must be gone")
            assertFailsWith<AssertionError>("step $number of `$heading` without `$door` must fail") {
                assertFeeRecipientDoors(text)
            }
        }
    }

    /**
     * T81: every backticked text in the guide that reads as a constant's name is one — a constant of an
     * enum the library publishes — so a page branching on a `reason` the guide names branches on a
     * value the library can answer. T79's and T80's refusals are among them.
     */
    @Test
    fun `every constant the guide names is a published enum constant`() {
        val tokens = constantTokens(guide)
        val refusals = listOf(
            "NOT_THIS_ORDERS_PROPOSAL",
            "PROVIDER_NOT_THIS_ORDERS",
            "WRONG_SENDER",
            "CANNOT_SAY",
            "NOT_ADDRESSED_TO_BUYER",
            "FEE_RECIPIENT_IS_BUYER",
        )
        for (named in refusals) {
            assertTrue(named in tokens, "the guide names `$named`: $tokens")
        }
        assertTrue("spec/NENYA-1.md" !in tokens && "nenya.mjs" !in tokens, "only whole constant-like spans are read")
        assertTokensAreConstants(guide)
    }

    /**
     * Negative control: the guide with `NOT_THIS_ORDERS_PROPOSAL`, `NOT_ADDRESSED_TO_BUYER` or `FEE_RECIPIENT_IS_BUYER` misspelt fails, and so does any other
     * name no enum declares; a span only partly constant-like, or a constant inside a code fence, is not read.
     */
    @Test
    fun `the guide naming a constant no enum declares fails`() {
        val misspelt = guide.replace("`NOT_THIS_ORDERS_PROPOSAL`", "`NOT_THIS_ORDER_PROPOSAL`")
        assertNotEquals(guide, misspelt, "the guide names `NOT_THIS_ORDERS_PROPOSAL`")
        assertFailsWith<AssertionError> { assertTokensAreConstants(misspelt) }
        // T91: T89's refusal, which the fee recipient's step 2 names.
        val misspeltT89 = guide.replace("`NOT_ADDRESSED_TO_BUYER`", "`NOT_ADDRESSED_TO_THE_BUYER`")
        assertNotEquals(guide, misspeltT89, "the guide names `NOT_ADDRESSED_TO_BUYER`")
        assertFailsWith<AssertionError> { assertTokensAreConstants(misspeltT89) }
        // T95: T93's refusal, which the buyer's step 4 names.
        val misspeltT93 = guide.replace("`FEE_RECIPIENT_IS_BUYER`", "`FEE_RECIPIENT_IS_THE_BUYER`")
        assertNotEquals(guide, misspeltT93, "the guide names `FEE_RECIPIENT_IS_BUYER`")
        assertFailsWith<AssertionError> { assertTokensAreConstants(misspeltT93) }
        assertFailsWith<AssertionError> { assertTokensAreConstants("$guide\nRefused with `ESCROW_HELD`.\n") }

        assertEquals(emptySet(), constantTokens("A `reason` such as `NOT_THIS_ORDERS_PROPOSAL.name` or `\"VALID\"`."))
        assertEquals(emptySet(), constantTokens("```js\nconst `ESCROW_HELD` = 1;\n```\n"))
        assertEquals(setOf("WRONG_SENDER"), constantTokens("Refused with\n`WRONG_SENDER`, `BID`."))
        assertFailsWith<AssertionError> { constantTokens("An unpaired ` backtick.") }
        assertFailsWith<AssertionError> { constantTokens("```js\nno closing fence\n") }
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
