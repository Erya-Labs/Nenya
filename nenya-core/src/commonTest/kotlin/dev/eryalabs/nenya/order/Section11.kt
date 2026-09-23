package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.SpecAnchor
import dev.eryalabs.nenya.VendoredFile
import kotlin.test.fail

/**
 * §11.1 and §11.2 of `spec/NENYA-1.md`, parsed out of the specification at test time.
 *
 * Nothing here is transcribed. The ten state tokens, which of them §11.1 marks terminal,
 * the four reserved tokens and every legal transition are all read from the document the
 * tests run against, so a specification revision that changed any of them turns the suite
 * red instead of leaving a stale copy agreeing with itself.
 *
 * Every accessor fails loudly — naming the file it read and what it expected to find — so a
 * wrong path or a restructured section cannot make a test pass vacuously. The files are
 * compiled into the tests as text (see [SpecAnchor]); [SpecAnchor] owns the specification path
 * and reports it on failure.
 */
internal object Section11 {

    /** One legal `(from, to)` pair of §11.2, together with the line it was read from. */
    internal data class Transition(val specLine: Int, val from: String, val to: String) {
        override fun toString(): String = "$specLine | $from | $to"
    }

    /** §11.2's genesis row has no from-state: an order comes into existence at `proposed`. */
    internal const val GENESIS: String = "—"

    /**
     * What one triple-for-triple comparison of a transcription against a specification found.
     *
     * The comparison used to be written inline in `TransitionTableTest` over the two live files.
     * It is one function of `(specification text, psv lines)` so that a control can feed it a psv
     * it built — a stale one, say — and be measured by **the** comparison rather than by a second
     * implementation of it that could drift into agreeing with whatever it was handed.
     */
    internal data class Comparison(
        /** Every triple §11.2 states, as parsed from the specification text it was given. */
        val spec: Set<Transition>,
        /** Every triple the psv lines carried, in file order, so a duplicate stays visible. */
        val transcribed: List<Transition>,
        /** In the specification and not transcribed, by ascending specification line. */
        val missing: List<Transition>,
        /** Transcribed and in no such line of the specification, by ascending line. */
        val extra: List<Transition>,
    ) {

        /** Whether the two agree exactly — the assertion `TransitionTableTest` makes. */
        val agrees: Boolean get() = missing.isEmpty() && extra.isEmpty()

        /** Everything the failure names, which is what a control asserts over. */
        val named: Set<Transition> get() = (missing + extra).toSet()

        /** The failure message, authored here so the test and any control report identically. */
        fun report(specPath: String): String = buildString {
            append("the transcribed §11.2 table and $specPath disagree.")
            if (missing.isNotEmpty()) append("\n  in the specification, not transcribed: $missing")
            if (extra.isNotEmpty()) append("\n  transcribed, not in the specification: $extra")
        }
    }

    /**
     * Parse both sides and diff them. Neither side is read from disk here: both arrive as text,
     * which is what lets a control vary one of them.
     */
    internal fun compare(specificationLines: List<String>, psvLines: List<String>): Comparison {
        val spec = parseTransitions(specificationLines)
        val transcribed = parseTranscribed(psvLines, "the psv lines supplied")
        val asSet = transcribed.toSet()
        return Comparison(
            spec = spec,
            transcribed = transcribed,
            missing = (spec - asSet).sortedBy { it.specLine },
            extra = (asSet - spec).sortedBy { it.specLine },
        )
    }

    /** The specification as the tests read it, for a caller that wants to vary the other side. */
    internal fun specificationLines(): List<String> = specLines

    /** The committed psv as the tests read it, line for line and comments included. */
    internal fun transcribedLines(): List<String> = VendoredFile(TRANSITIONS_PATH).readLines()

    private const val TRANSITIONS_PATH: String = "nenya-core/src/commonTest/resources/spec/nenya-1-11.2-transitions.psv"

    private const val STATES_HEADING: String = "### 11.1 States"

    private const val TRANSITIONS_HEADING: String = "### 11.2 Transitions"

    private const val RESERVED_PREFIX: String = "Reserved, and MUST NOT be emitted"

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /**
     * §11.1's ten canonical state tokens, in table order, each mapped to whether §11.1's
     * meaning cell marks it **Terminal**.
     */
    internal val states: Map<String, Boolean> by lazy {
        val rows = tableRows(specLines, STATES_HEADING, expectedColumns = 2, expectedFirstHeaderCell = "State")
        rows.associate { (_, cells) -> unquote(cells[0]) to ("Terminal" in cells[1]) }
            .also {
                if (it.isEmpty()) {
                    fail("§11.1's state table parsed to zero rows in ${specPath()}")
                }
            }
    }

    /**
     * The tokens §11.1 reserves for a future escrow revision and forbids a v1 implementation
     * from emitting.
     *
     * Parsed rather than listed, so a revision that reserved a fifth token would extend the
     * control automatically instead of leaving it three-quarters true.
     */
    internal val reservedTokens: List<String> by lazy {
        val start = specLines.indexOfFirst { it.trimStart().startsWith(RESERVED_PREFIX) }
        if (start < 0) {
            fail("no line beginning \"$RESERVED_PREFIX\" in §11.1 of ${specPath()}")
        }
        val paragraph = specLines.drop(start).takeWhile { it.isNotBlank() }.joinToString(" ")
        val listPart = paragraph.substringAfter(": ", "").substringBefore(". ")
        val tokens = Regex("`([a-z_]+)`").findAll(listPart).map { it.groupValues[1] }.toList()
        if (tokens.size < 2) {
            fail(
                "§11.1's reserved-token sentence parsed to ${tokens.size} token(s) in " +
                    "${specPath()}; the sentence read: $listPart",
            )
        }
        tokens
    }

    /**
     * Every legal transition §11.2 states, pre-expanded so a row naming two or three
     * destinations yields two or three entries — all carrying that row's line number.
     */
    internal val transitions: Set<Transition> by lazy { parseTransitions(specLines) }

    /** The transcribed table, in file order, so duplicate lines remain visible. */
    internal fun transcribed(): List<Transition> {
        // A missing path fails inside VendoredFile, naming every file that was generated.
        val file = VendoredFile(TRANSITIONS_PATH)
        return parseTranscribed(file.readLines(), file.location)
    }

    /** §11.2's row block, expanded, out of the specification text it is given. */
    private fun parseTransitions(lines: List<String>): Set<Transition> {
        val rows = tableRows(lines, TRANSITIONS_HEADING, expectedColumns = 3, expectedFirstHeaderCell = "From")
        val expanded = rows.flatMap { (lineNumber, cells) ->
            val from = unquote(cells[0])
            cells[1].split("/").map { destination ->
                val to = unquote(destination)
                if (to.isEmpty()) {
                    fail("§11.2 line $lineNumber has an empty destination in ${specPath()}")
                }
                Transition(lineNumber, from, to)
            }
        }
        if (expanded.isEmpty()) {
            fail("§11.2's row block parsed to zero transitions in ${specPath()}")
        }
        return expanded.toSet()
    }

    /** The psv lines it is given, as triples, in the order they arrived. */
    private fun parseTranscribed(psvLines: List<String>, location: String): List<Transition> {
        val rows = psvLines
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .map { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size != 3) {
                    fail(
                        "every data line of $location is " +
                            "`<spec line> | <from> | <to>`; this one has ${parts.size} " +
                            "field(s): $line",
                    )
                }
                val lineNumber = parts[0].toIntOrNull()
                    ?: fail("the first field of $location is a spec line number: $line")
                Transition(lineNumber, parts[1], parts[2])
            }
        if (rows.isEmpty()) {
            fail("$location carries no data lines at all")
        }
        return rows
    }

    /** The text of a 1-based line of the specification, for the line-number cross-check. */
    internal fun specLine(number: Int): String {
        if (number !in 1..specLines.size) {
            fail("${specPath()} has ${specLines.size} lines; line $number does not exist")
        }
        return specLines[number - 1]
    }

    internal fun specPath(): String = SpecAnchor.specFile().location

    /**
     * The data rows of the first `|`-delimited block after [heading], each with its 1-based
     * line number: the header row and the `|---|` separator dropped, every other row split
     * into trimmed cells.
     */
    private fun tableRows(
        specLines: List<String>,
        heading: String,
        expectedColumns: Int,
        expectedFirstHeaderCell: String,
    ): List<Pair<Int, List<String>>> {
        val headingIndex = specLines.indexOfFirst { it.trim() == heading }
        if (headingIndex < 0) {
            fail("no `$heading` heading in ${specPath()}")
        }
        // Both sections open with a line of prose before their table, so scan forward for the
        // first table line — but never past the next heading, or a restructured section would
        // silently pick up a table belonging to somebody else.
        var index = headingIndex + 1
        while (index < specLines.size &&
            !specLines[index].trimStart().startsWith("|") &&
            !specLines[index].trimStart().startsWith("#")
        ) {
            index++
        }
        if (index >= specLines.size || !specLines[index].trimStart().startsWith("|")) {
            fail("no `|`-delimited table between `$heading` and the next heading in ${specPath()}")
        }
        val block = mutableListOf<Pair<Int, String>>()
        while (index < specLines.size && specLines[index].trimStart().startsWith("|")) {
            block += (index + 1) to specLines[index]
            index++
        }
        if (block.size < 3) {
            fail(
                "the table under `$heading` in ${specPath()} parsed to ${block.size} line(s); " +
                    "a header, a separator and at least one data row were expected",
            )
        }
        val header = cells(block[0].second)
        if (header.firstOrNull() != expectedFirstHeaderCell) {
            fail(
                "the table under `$heading` in ${specPath()} opens with a `${header.firstOrNull()}` " +
                    "column where `$expectedFirstHeaderCell` was expected — this is the wrong table",
            )
        }
        val separator = cells(block[1].second)
        if (separator.any { cell -> cell.isEmpty() || cell.any { it != '-' && it != ':' } }) {
            fail(
                "the second line of the table under `$heading` in ${specPath()} is not a " +
                    "`|---|` separator: ${block[1].second}",
            )
        }
        return block.drop(2).map { (lineNumber, text) ->
            val cells = cells(text)
            if (cells.size != expectedColumns) {
                fail(
                    "line $lineNumber of ${specPath()} has ${cells.size} cells where " +
                        "$expectedColumns were expected — a `|` inside a cell would do this: $text",
                )
            }
            lineNumber to cells
        }
    }

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun unquote(cell: String): String = cell.trim().removeSurrounding("`").trim()
}
