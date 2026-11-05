package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.conformance.MainClasses
import dev.eryalabs.nenya.conformance.ResidualRisksTest
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

        /** T109: §8.1's SHOULD-warn answer, which the buyer's and the provider's sections each name. */
        const val FEE_RECIPIENT_IS_PROVIDER: String = "feeRecipientIsProvider"

        /** T109: the sections that must name [FEE_RECIPIENT_IS_PROVIDER]. */
        val FEE_RECIPIENT_IS_PROVIDER_HEADINGS: List<String> = listOf(BUYER_HEADING, PROVIDER_HEADING)

        /** Fails unless each section in [FEE_RECIPIENT_IS_PROVIDER_HEADINGS] names it, naming the ones that do not. */
        fun assertFeeRecipientIsProviderNamed(text: String) {
            val missing = FEE_RECIPIENT_IS_PROVIDER_HEADINGS.filter {
                "`$FEE_RECIPIENT_IS_PROVIDER`" !in collapsed(section(text, it))
            }
            assertEquals(emptyList(), missing, "sections that do not name `$FEE_RECIPIENT_IS_PROVIDER`")
        }

        /** T112: the section whose bullets are §13's residual risks. */
        const val NOT_CHECKED_HEADING: String = "## What Nenya does not check"

        /**
         * The bullets of [lines], in order, each whitespace collapsed: a bullet opens a line with `- ` and
         * runs on over the indented, non-blank lines that follow it.
         */
        fun bullets(lines: List<String>): List<String> {
            val bullets = mutableListOf<MutableList<String>>()
            var open = false
            for (line in lines) {
                when {
                    line.startsWith("- ") -> { bullets += mutableListOf(line.removePrefix("- ")); open = true }
                    open && line.isNotBlank() && line.first().isWhitespace() -> bullets.last() += line
                    else -> open = false
                }
            }
            return bullets.map { collapsed(it).trim() }
        }

        /** Fails unless [NOT_CHECKED_HEADING]'s bullets in [text] are exactly [risks], once each, naming the difference. */
        fun assertResidualRisksListed(text: String, risks: List<String>) {
            val listed = bullets(section(text, NOT_CHECKED_HEADING))
            assertEquals(listed.size, listed.toSet().size, "a bullet listed twice in `$NOT_CHECKED_HEADING`: $listed")
            assertEquals(
                emptySet<String>() to emptySet(),
                (risks.toSet() - listed.toSet()) to (listed.toSet() - risks.toSet()),
                "(§13 residual risks `$NOT_CHECKED_HEADING` does not list) to (bullets that are none of them)",
            )
        }

        /** T114: the getter [NOT_CHECKED_HEADING] names in prose, as `JsCapabilities` publishes it. */
        const val OPEN_ITEMS: String = "openItems"

        /**
         * Fails unless [NOT_CHECKED_HEADING] in [text] names [OPEN_ITEMS] in its prose, says the client
         * shows them rather than choosing a value, and names it in no bullet (those are §13's alone).
         */
        fun assertOpenItemsNamed(text: String) {
            val lines = section(text, NOT_CHECKED_HEADING)
            val prose = collapsed(lines)
            assertTrue("`$OPEN_ITEMS`" in prose, "`$NOT_CHECKED_HEADING` does not name `$OPEN_ITEMS`")
            assertTrue(
                "rather than choosing a value" in prose,
                "`$NOT_CHECKED_HEADING` does not say the client shows the open items rather than choosing a value",
            )
            assertEquals(emptyList(), bullets(lines).filter { OPEN_ITEMS in it }, "bullets naming `$OPEN_ITEMS`")
        }

        /** [text] with every [span] inside the section under [heading] removed, and nothing else changed. */
        fun withoutInSection(text: String, heading: String, span: String): String {
            val lines = text.lines().toMutableList()
            val start = lines.indexOfFirst { it.trimEnd() == heading }
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
            for (i in start + 1 until end) lines[i] = lines[i].replace(span, "")
            return lines.joinToString("\n")
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

        /** T121: the citation that marks the buyer's paragraphs on waiting for the fee request (decision V). */
        const val FEE_REQUEST_WAIT: String = "§8.8"

        /** T121: the spans those paragraphs must name, together, in backticks. */
        val FEE_REQUEST_WAIT_SPANS: List<String> = listOf(
            "feePayeeRequired",
            "buildStatusUpdate",
            "stepOrderWithStatusUpdate",
            "clock_checked",
            "FEE_TERM_MISMATCH",
        )

        /** RFC 2119's keywords, which the guide does not use (decision Q). */
        val RFC_2119_KEYWORD: Regex = Regex("\\b(MUST|SHALL|SHOULD|REQUIRED|RECOMMENDED|MAY|OPTIONAL)\\b")

        /**
         * The line indices in [text] of each column-0 paragraph of [heading] that cites
         * [FEE_REQUEST_WAIT]: a paragraph opens on a non-blank line that starts at column 0 and opens no
         * numbered step, and runs to the next blank line. A step's indented prose is never one.
         */
        fun feeRequestWaitParagraphs(text: String, heading: String = BUYER_HEADING): List<List<Int>> {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trimEnd() == heading }
            if (start < 0) fail("the guide has no `$heading` section")
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
            val paragraphs = mutableListOf<MutableList<Int>>()
            var open = false
            for (i in start + 1 until end) {
                val line = lines[i]
                when {
                    line.isBlank() -> open = false
                    open -> paragraphs.last() += i
                    !line.first().isWhitespace() && stepNumber(line) == null -> {
                        paragraphs += mutableListOf(i)
                        open = true
                    }
                }
            }
            return paragraphs.filter { indices -> indices.any { FEE_REQUEST_WAIT in lines[it] } }
        }

        /**
         * Fails unless [text]'s buyer section has a column-0 paragraph citing [FEE_REQUEST_WAIT], those
         * paragraphs name every span in [FEE_REQUEST_WAIT_SPANS] in backticks and none uses an RFC 2119
         * keyword, naming what is missing.
         */
        fun assertFeeRequestWaitSaid(text: String) {
            val lines = text.lines()
            val paragraphs = feeRequestWaitParagraphs(text).map { indices -> indices.map { lines[it] } }
            assertTrue(paragraphs.isNotEmpty(), "`$BUYER_HEADING` has no column-0 paragraph citing `$FEE_REQUEST_WAIT`")
            val spans = codeSpans(paragraphs.flatten())
            assertEquals(
                emptyList(),
                FEE_REQUEST_WAIT_SPANS.filter { it !in spans },
                "`$BUYER_HEADING`: spans its `$FEE_REQUEST_WAIT` paragraphs do not name",
            )
            assertEquals(
                emptyList(),
                paragraphs.map { collapsed(it) }.filter { RFC_2119_KEYWORD.containsMatchIn(it) },
                "`$BUYER_HEADING`: `$FEE_REQUEST_WAIT` paragraphs using an RFC 2119 keyword",
            )
        }

        /** [text] with every backticked [span] removed from the lines of its [FEE_REQUEST_WAIT] paragraphs only. */
        fun withoutInFeeRequestWait(text: String, span: String): String {
            val lines = text.lines().toMutableList()
            for (i in feeRequestWaitParagraphs(text).flatten()) lines[i] = lines[i].replace("`$span`", "")
            return lines.joinToString("\n")
        }

        /** T122: the overview, by its path from `nenya-core`. */
        const val OVERVIEW_PATH: String = "../spec/NENYA-1-overview.md"

        /** T122: the overview's sentences on `expiration` and on the wait for the fee request (decision V). */
        val OVERVIEW_WAIT_SENTENCES: List<String> = listOf(
            "An order not yet `paid` whose `expiration` passes is `expired`.",
            "If no valid fee request arrives, the buyer's order does not pass `committed`.",
            "The client shows this and offers a cancellation (§8.8).",
            "The buyer can then propose a new order.",
        )

        /** T122: revision `1.15`'s overview sentence, which §7.5's `1.16` clause made too narrow. */
        const val OVERVIEW_STALE_SENTENCE: String = "A proposed order whose `expiration` passes is `expired`."

        /** T122: the sentences [PROVIDER_HEADING]'s column-0 paragraphs citing [FEE_REQUEST_WAIT] hold. */
        val PROVIDER_CANCEL_SENTENCES: List<String> = listOf(
            "A buyer can cancel before its own order is `paid` (§8.8).",
            "read it with `decodeStatusUpdate`.",
            "An order machine you keep can read `cancelled` even after you were paid, because each party's " +
                "order is its own, so do not show that as unpaid.",
            "A cancel changes nothing `buildReleaseAfterPayment` checks.",
            "If the buyer proposes a new order, it needs a new file key (§10.2), a new commitment and a new invoice.",
        )

        /** T122: the sentence [FEE_RECIPIENT_HEADING] holds on a cancel it is not told of. */
        const val FEE_RECIPIENT_NOT_TOLD: String =
            "A buyer that cancels tells only the provider (§8.8), so a fee recipient that is not also the provider is not told."

        /** Fails unless [lines], whitespace collapsed, hold every one of [sentences], naming [where] and the ones missing. */
        fun assertSentencesHeld(where: String, lines: List<String>, sentences: List<String>) {
            val prose = collapsed(lines)
            assertEquals(emptyList(), sentences.filter { it !in prose }, "`$where`: sentences it does not hold")
        }

        /**
         * Fails unless [overview] holds [OVERVIEW_WAIT_SENTENCES] and not [OVERVIEW_STALE_SENTENCE], and
         * [guide]'s provider paragraphs citing [FEE_REQUEST_WAIT] hold [PROVIDER_CANCEL_SENTENCES] with no
         * RFC 2119 keyword, and its fee recipient's section holds [FEE_RECIPIENT_NOT_TOLD].
         */
        fun assertCancelSaidBeyondTheBuyer(guide: String, overview: String) {
            assertSentencesHeld(OVERVIEW_PATH, overview.lines(), OVERVIEW_WAIT_SENTENCES)
            assertTrue(OVERVIEW_STALE_SENTENCE !in collapsed(overview.lines()), "the overview still says `$OVERVIEW_STALE_SENTENCE`")
            val lines = guide.lines()
            val provider = feeRequestWaitParagraphs(guide, PROVIDER_HEADING).flatten().map { lines[it] }
            assertSentencesHeld(PROVIDER_HEADING, provider, PROVIDER_CANCEL_SENTENCES)
            assertTrue(!RFC_2119_KEYWORD.containsMatchIn(collapsed(provider)), "`$PROVIDER_HEADING`: an RFC 2119 keyword")
            assertSentencesHeld(FEE_RECIPIENT_HEADING, section(guide, FEE_RECIPIENT_HEADING), listOf(FEE_RECIPIENT_NOT_TOLD))
        }

        /**
         * [text] with [heading]'s column-0 paragraphs citing [FEE_REQUEST_WAIT] joined onto one line,
         * whitespace collapsed, and [transform] applied to it, so a wrapped sentence can be removed whole.
         */
        fun withFeeRequestWaitRewritten(text: String, heading: String, transform: (String) -> String): String {
            val lines = text.lines()
            val indices = feeRequestWaitParagraphs(text, heading).flatten()
            if (indices.isEmpty()) fail("`$heading` has no column-0 paragraph citing `$FEE_REQUEST_WAIT`")
            val joined = transform(collapsed(indices.map { lines[it] }))
            return lines.flatMapIndexed { i, line ->
                when (i) {
                    indices.first() -> listOf(joined)
                    in indices -> emptyList()
                    else -> listOf(line)
                }
            }.joinToString("\n")
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

    /** T109: the buyer's and the provider's sections each name §8.1's `feeRecipientIsProvider` warning. */
    @Test
    fun `the buyer's and provider's sections name feeRecipientIsProvider`() {
        assertFeeRecipientIsProviderNamed(guide)
        for (heading in FEE_RECIPIENT_IS_PROVIDER_HEADINGS) assertStepsHeld(heading, tradeSteps(guide, heading))
    }

    /** Negative control: the name deleted from either section fails, naming that section. */
    @Test
    fun `either section without feeRecipientIsProvider fails`() {
        for (heading in FEE_RECIPIENT_IS_PROVIDER_HEADINGS) {
            val text = withoutInSection(guide, heading, "`$FEE_RECIPIENT_IS_PROVIDER`")
            assertNotEquals(guide, text, "`$heading` names `$FEE_RECIPIENT_IS_PROVIDER`")
            val failure = assertFailsWith<AssertionError>("`$heading` without the name must fail") {
                assertFeeRecipientIsProviderNamed(text)
            }
            assertTrue(heading in failure.message.orEmpty(), "the failure names `$heading`")
        }
    }

    /** T112: `## What Nenya does not check` lists §13's residual risks, read from the vendored specification, as its bullets. */
    @Test
    fun `the not-checked section lists section 13's residual risks`() {
        val risks = ResidualRisksTest.residualRisks(ResidualRisksTest.vendored)
        assertEquals(5, risks.size, "revision 1.15's §13 names five residual risks")
        assertResidualRisksListed(guide, risks)
        assertTrue(
            "showing them to your user is your client's job" in collapsed(section(guide, NOT_CHECKED_HEADING)),
            "the section says surfacing them is the client's",
        )
    }

    /** Negative control: any one bullet deleted, or a risk added to §13 the guide does not list, fails; so does a bullet that wraps but differs. */
    @Test
    fun `the not-checked section missing a residual risk fails`() {
        val risks = ResidualRisksTest.residualRisks(ResidualRisksTest.vendored)
        for (risk in risks) {
            val lines = guide.lines()
            val text = withoutLine(guide, lines.indexOfFirst { it == "- $risk" }.also { assertTrue(it >= 0, "`- $risk` is a line") })
            assertFailsWith<AssertionError>("the guide without `$risk` must fail") { assertResidualRisksListed(text, risks) }
        }
        assertFailsWith<AssertionError> { assertResidualRisksListed(guide, risks + "a mint can refuse to redeem") }
        assertFailsWith<AssertionError> { assertResidualRisksListed(guide.replace("- ${risks[0]}", "- ${risks[0]}\n- ${risks[0]}"), risks) }
        assertFailsWith<AssertionError> { assertResidualRisksListed(guide.replace(NOT_CHECKED_HEADING, "## Caveats"), risks) }
        assertEquals(listOf("a wrapped bullet", "next"), bullets(listOf("- a wrapped", "  bullet", "", "  not part", "- next")))
    }

    /** T114: `## What Nenya does not check` names `openItems` in prose, and its bullets stay §13's alone. */
    @Test
    fun `the not-checked section names openItems in prose`() {
        assertOpenItemsNamed(guide)
        // The name the guide gives is the getter's: this line compiles only while `openItems` exists.
        assertTrue(capabilities().openItems.isNotEmpty(), "`$OPEN_ITEMS` answers §16.2's open items")
        assertResidualRisksListed(guide, ResidualRisksTest.residualRisks(ResidualRisksTest.vendored))
    }

    /** Negative control: `openItems` deleted from the section fails; so does a bullet naming it. */
    @Test
    fun `the not-checked section without openItems fails`() {
        val without = withoutInSection(guide, NOT_CHECKED_HEADING, "`$OPEN_ITEMS`")
        assertNotEquals(guide, without, "`$NOT_CHECKED_HEADING` names `$OPEN_ITEMS`")
        assertFailsWith<AssertionError>("the section without `$OPEN_ITEMS` must fail") { assertOpenItemsNamed(without) }
        val bulleted = guide.replace("- a `kind:5` deletion is advisory", "- a `kind:5` deletion is advisory\n- `$OPEN_ITEMS`")
        assertNotEquals(guide, bulleted)
        assertFailsWith<AssertionError>("a bullet naming `$OPEN_ITEMS` must fail") { assertOpenItemsNamed(bulleted) }
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

    /**
     * T121: the buyer's section says, in column-0 paragraphs citing §8.8, how to read and show the wait
     * for the fee request, its deadline, a terms mismatch that is not the wait, and the cancel.
     */
    @Test
    fun `the buyer's section says how to read, show and cancel the wait for the fee request`() {
        assertFeeRequestWaitSaid(guide)
        assertTrue(
            listOf("buildStatusUpdate", "stepOrderWithStatusUpdate").all { it in JsSurfaceTest.ENTRY_POINTS },
            "the doors the paragraphs name are exported",
        )
        // The getter the paragraphs name is `JsOrder`'s: this line compiles only while it exists.
        assertEquals("feePayeeRequired", JsOrder::feePayeeRequired.name)
        assertTrue(listOf("FEE_TERM_MISMATCH", "NO_DEADLINE_TO_CHECK", "STATE_IS_TERMINAL").all { it in PUBLISHED_ENUM_CONSTANTS })
        assertTradeSections(guide)
    }

    /**
     * Negative control: any one span removed from the §8.8 paragraphs fails, though lines outside them
     * still name three of the spans; so does an RFC 2119 keyword in one, or no such paragraph at all.
     */
    @Test
    fun `the buyer's section without a span of the fee request wait fails`() {
        for (span in FEE_REQUEST_WAIT_SPANS) {
            val text = withoutInFeeRequestWait(guide, span)
            assertNotEquals(guide, text, "the §8.8 paragraphs name `$span`")
            val failure = assertFailsWith<AssertionError>("the paragraphs without `$span` must fail") {
                assertFeeRequestWaitSaid(text)
            }
            assertTrue(span in failure.message.orEmpty(), "the failure names `$span`")
        }
        val inside = feeRequestWaitParagraphs(guide).flatten().toSet()
        val lines = guide.lines()
        val buyer = lines.indexOf(BUYER_HEADING)
        val outside = (buyer + 1 until lines.indexOf(PROVIDER_HEADING)).filter { it !in inside }.map { lines[it] }
        assertTrue(
            listOf("buildStatusUpdate", "stepOrderWithStatusUpdate", "clock_checked").all { "`$it`" in collapsed(outside) },
            "lines outside the §8.8 paragraphs still name three of the spans",
        )
        val keyword = guide.replace("Show it as waiting for the fee request", "You MUST show it as waiting for the fee request")
        assertNotEquals(guide, keyword)
        assertFailsWith<AssertionError> { assertFeeRequestWaitSaid(keyword) }
        assertFailsWith<AssertionError> { assertFeeRequestWaitSaid(guide.replace(FEE_REQUEST_WAIT, "§8.7")) }
    }

    /** In memory: only column-0 paragraphs citing §8.8 are read, never a step's prose or another paragraph. */
    @Test
    fun `only the buyer's column-0 paragraphs citing section 8_8 are read`() {
        val text = listOf(
            BUYER_HEADING,
            "",
            "1. **Step** (§8.8). Read `feePayeeRequired`.",
            "",
            "   Indented (§8.8) `buildStatusUpdate`.",
            "",
            "   Calls: `buildStatusUpdate`.",
            "A column-0 paragraph naming `stepOrderWithStatusUpdate`",
            "and wrapping.",
            "",
            "The wait (§8.8) names `clock_checked`",
            "and `FEE_TERM_MISMATCH` on its second line.",
            "",
            PROVIDER_HEADING,
            "",
            "Not the buyer's (§8.8) `feePayeeRequired`.",
        ).joinToString("\n")
        assertEquals(listOf(listOf(10, 11)), feeRequestWaitParagraphs(text))
        val failure = assertFailsWith<AssertionError> { assertFeeRequestWaitSaid(text) }
        for (span in listOf("feePayeeRequired", "buildStatusUpdate", "stepOrderWithStatusUpdate")) {
            assertTrue(span in failure.message.orEmpty(), "`$span` is reported missing")
        }
        assertFeeRequestWaitSaid(text.replace("and wrapping.\n\nThe wait", "and wrapping.\n\nThe wait `feePayeeRequired`, `buildStatusUpdate`, `stepOrderWithStatusUpdate`"))
    }

    private val overview: String by lazy { read(File(OVERVIEW_PATH)) }

    /**
     * T122: the overview says an order not yet `paid` expires and that a buyer's order waits for the fee
     * request and can be cancelled; the provider's section says what a buyer's cancel means to it, and
     * the fee recipient's that it is not told (decision V).
     */
    @Test
    fun `the overview, the provider's and the fee recipient's sections say what a cancel is`() {
        assertCancelSaidBeyondTheBuyer(guide, overview)
        assertTrue(
            listOf("decodeStatusUpdate", "buildReleaseAfterPayment").all { it in JsSurfaceTest.ENTRY_POINTS },
            "the doors the provider's paragraph names are exported",
        )
        assertTradeSections(guide)
    }

    /**
     * Negative control: any one sentence removed from the overview or the provider's paragraph fails,
     * naming it; so do the overview's old `expiration` sentence restored, the provider's paragraph moved
     * into step 6's indented prose, and the fee recipient's sentence changed.
     */
    @Test
    fun `the overview or the provider's paragraph without a sentence fails`() {
        val flatOverview = collapsed(overview.lines())
        for (sentence in OVERVIEW_WAIT_SENTENCES) {
            val text = flatOverview.replace(sentence, "")
            assertNotEquals(flatOverview, text, "the overview holds `$sentence`")
            val failure = assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(guide, text) }
            assertTrue(sentence in failure.message.orEmpty(), "the failure names `$sentence`")
        }
        val stale = flatOverview.replace(OVERVIEW_WAIT_SENTENCES.first(), OVERVIEW_STALE_SENTENCE)
        assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(guide, stale) }
        assertCancelSaidBeyondTheBuyer(withFeeRequestWaitRewritten(guide, PROVIDER_HEADING) { it }, overview)
        for (sentence in PROVIDER_CANCEL_SENTENCES) {
            val text = withFeeRequestWaitRewritten(guide, PROVIDER_HEADING) { it.replace(sentence, "") }
            assertNotEquals(withFeeRequestWaitRewritten(guide, PROVIDER_HEADING) { it }, text, "the paragraph holds `$sentence`")
            val failure = assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(text, overview) }
            assertTrue(sentence in failure.message.orEmpty(), "the failure names `$sentence`")
        }
        val indented = withFeeRequestWaitRewritten(guide, PROVIDER_HEADING) { "   $it" }
        assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(indented, overview) }
        val keyword = withFeeRequestWaitRewritten(guide, PROVIDER_HEADING) { "$it The provider MUST read it." }
        assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(keyword, overview) }
        val told = guide.replace("tells only the provider (§8.8)", "tells everyone (§8.8)")
        assertNotEquals(guide, told)
        val failure = assertFailsWith<AssertionError> { assertCancelSaidBeyondTheBuyer(told, overview) }
        assertTrue(FEE_RECIPIENT_NOT_TOLD in failure.message.orEmpty(), "the failure names the fee recipient's sentence")
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
