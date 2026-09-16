package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.SpecAnchor
import java.io.File
import kotlin.test.fail

/**
 * §11.1 and §11.2 of `spec/NENYA-1.md`, parsed out of the specification at test time.
 *
 * Nothing here is transcribed. The ten state tokens, which of them §11.1 marks terminal,
 * the four reserved tokens and every legal transition are all read from the document the
 * tests run against, so a specification revision that changed any of them turns the suite
 * red instead of leaving a stale copy agreeing with itself.
 *
 * Every accessor fails loudly — naming the absolute path it read and what it expected to
 * find — so a wrong working directory or a restructured section cannot make a test pass
 * vacuously. Gradle runs the tests with the module directory as the working directory, so
 * the specification is one level up; [SpecAnchor] owns that path and reports it on failure.
 */
internal object Section11 {

    /** One legal `(from, to)` pair of §11.2, together with the line it was read from. */
    internal data class Transition(val specLine: Int, val from: String, val to: String) {
        override fun toString(): String = "$specLine | $from | $to"
    }

    /** §11.2's genesis row has no from-state: an order comes into existence at `proposed`. */
    internal const val GENESIS: String = "—"

    private const val TRANSITIONS_PATH: String = "src/jvmTest/resources/spec/nenya-1-11.2-transitions.psv"

    private const val STATES_HEADING: String = "### 11.1 States"

    private const val TRANSITIONS_HEADING: String = "### 11.2 Transitions"

    private const val RESERVED_PREFIX: String = "Reserved, and MUST NOT be emitted"

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /**
     * §11.1's ten canonical state tokens, in table order, each mapped to whether §11.1's
     * meaning cell marks it **Terminal**.
     */
    internal val states: Map<String, Boolean> by lazy {
        val rows = tableRows(STATES_HEADING, expectedColumns = 2, expectedFirstHeaderCell = "State")
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
    internal val transitions: Set<Transition> by lazy {
        val rows = tableRows(TRANSITIONS_HEADING, expectedColumns = 3, expectedFirstHeaderCell = "From")
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
        expanded.toSet()
    }

    /** The transcribed table, in file order, so duplicate lines remain visible. */
    internal fun transcribed(): List<Transition> {
        val file = File(TRANSITIONS_PATH)
        if (!file.isFile) {
            fail(
                "the transcribed §11.2 table was expected at ${file.absolutePath} but is not " +
                    "there. The tests run with the module directory as the working directory; " +
                    "this one is ${File(".").absoluteFile.normalize()}.",
            )
        }
        val rows = file.readLines()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .map { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size != 3) {
                    fail(
                        "every data line of ${file.absolutePath} is " +
                            "`<spec line> | <from> | <to>`; this one has ${parts.size} " +
                            "field(s): $line",
                    )
                }
                val lineNumber = parts[0].toIntOrNull()
                    ?: fail("the first field of ${file.absolutePath} is a spec line number: $line")
                Transition(lineNumber, parts[1], parts[2])
            }
        if (rows.isEmpty()) {
            fail("${file.absolutePath} carries no data lines at all")
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

    internal fun specPath(): String = SpecAnchor.specFile().absolutePath

    /**
     * The data rows of the first `|`-delimited block after [heading], each with its 1-based
     * line number: the header row and the `|---|` separator dropped, every other row split
     * into trimmed cells.
     */
    private fun tableRows(
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
