package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §5.3's tag vocabulary table and §5.2's request kind, parsed out of `spec/NENYA-1.md` at test
 * time.
 *
 * Nothing here is transcribed. Every tag name, cardinality, requirement, side and encoding cell is
 * read from the document the tests run against, exactly as `Section11` reads §11.1 and §11.2, so a
 * specification revision that added a row, renamed a tag or changed a cardinality turns the suite
 * red instead of leaving a stale copy in `NenyaTags` agreeing with itself.
 *
 * ### Four mechanical traps in this particular table, all four load-bearing
 *
 * 1. **Escaped pipes.** The `status` row's Notes cell contains ``  `active` \| `sold`  `` twice
 *    over. A naive `split("|")` mis-columns that row and every assertion about it then tests the
 *    wrong cell — quietly, because the row still parses. [cells] splits on **unescaped** `|` only
 *    and unescapes `\|` into a literal pipe.
 * 2. **Non-ASCII cardinalities.** The Card. column uses U+2013 EN DASH in `0–1` and `0–n`, and
 *    U+2265 in `≥2`. An ASCII transcription of either is a different string and the set equality
 *    in `TagVocabularyTest` is what says so.
 * 3. **Conditional requirement prose.** The `alt` row's Requirement cell is
 *    `MUST on requests, SHOULD on offers`, so Requirement cannot be a flat RFC-2119 enum. It is
 *    carried across as the cell text and modelled per listing kind on the codec's side.
 * 4. **Rows with no encoding.** `g` and `location` carry Card. `0` and an em dash in the Encoding
 *    column, and `item` carries Side `neither`. "No encoding" is a case, not a parse failure.
 *
 * Every accessor fails loudly, naming the absolute path it read, so a wrong working directory
 * cannot make a test pass vacuously.
 */
internal object Section53 {

    /** One row of §5.3's table, cell for cell, with the 1-based line it was read from. */
    internal data class Row(
        val specLine: Int,
        val tag: String,
        val side: String,
        val cardinality: String,
        val requirement: String,
        val encoding: String,
        val notes: String,
    )

    private const val HEADING: String = "### 5.3 Tag vocabulary"

    /** §5.3's table has six columns. The `status` row is the one that proves the splitter works. */
    internal const val COLUMNS: Int = 6

    /** §5.2 states the request kind in prose before it appears in any example. */
    private val REQUEST_KIND = Regex("""The Nenya request kind is `([0-9]+)`""")

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /** The 1-based line numbers and raw text of §5.3's `|`-delimited block, header and all. */
    internal val block: List<Pair<Int, String>> by lazy { readBlock() }

    /** §5.3's data rows, in table order. */
    internal val rows: List<Row> by lazy {
        val header = cells(block[0].second)
        if (header.size != COLUMNS || header[0] != "Tag") {
            fail(
                "§5.3's table header in ${specPath()} parsed to ${header.size} cell(s) opening with " +
                    "`${header.firstOrNull()}`; $COLUMNS opening with `Tag` were expected: $header",
            )
        }
        val separator = cells(block[1].second)
        if (separator.any { cell -> cell.isEmpty() || cell.any { it != '-' && it != ':' } }) {
            fail("the second line of §5.3's table in ${specPath()} is not a `|---|` separator")
        }
        val parsed = block.drop(2).map { (lineNumber, text) ->
            val cells = cells(text)
            if (cells.size != COLUMNS) {
                fail(
                    "line $lineNumber of ${specPath()} parsed to ${cells.size} cell(s) where " +
                        "$COLUMNS were expected. An unescaped-pipe split is the usual cause: the " +
                        "`status` row carries `\\|` inside its Notes cell. Row: $text",
                )
            }
            Row(
                specLine = lineNumber,
                tag = unquote(cells[0]),
                side = cells[1],
                cardinality = cells[2],
                requirement = cells[3],
                encoding = cells[4],
                notes = cells[5],
            )
        }
        if (parsed.isEmpty()) fail("§5.3's row block parsed to zero rows in ${specPath()}")
        parsed
    }

    /** The row §5.3 gives [tag], or a loud failure naming what was found instead. */
    internal fun row(tag: String): Row =
        rows.singleOrNull { it.tag == tag }
            ?: fail("§5.3 in ${specPath()} carries ${rows.count { it.tag == tag }} rows for `$tag`")

    /** §5.2's decided request kind, read from its own prose rather than from an example. */
    internal fun requestKind(): Int {
        val matches = specLines.mapNotNull { REQUEST_KIND.find(it)?.groupValues?.get(1) }
        if (matches.size != 1) {
            fail(
                "§5.2 must state the request kind exactly once in the form \"The Nenya request " +
                    "kind is `<n>`\"; found ${matches.size} in ${specPath()}",
            )
        }
        return matches.single().toInt()
    }

    internal fun specPath(): String = SpecAnchor.specFile().absolutePath

    /**
     * A markdown table row split into cells on **unescaped** `|`, each trimmed, with `\|`
     * unescaped into a literal pipe.
     *
     * The leading and trailing delimiters produce an empty cell at each end, which is dropped —
     * so this returns the row's real columns and nothing else.
     */
    internal fun cells(row: String): List<String> {
        val text = row.trim()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' && index + 1 < text.length && text[index + 1] == '|' -> {
                    // A markdown-escaped pipe: a literal character inside the cell, not a delimiter.
                    current.append('|')
                    index++
                }
                character == '|' -> {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        out += current.toString().trim()
        if (out.size < 2 || out.first().isNotEmpty() || out.last().isNotEmpty()) {
            fail("a §5.3 table row must open and close with `|`; this one did not: $row")
        }
        return out.subList(1, out.size - 1)
    }

    /** The naive split this file exists to avoid, so a control can show the two disagree. */
    internal fun naiveCells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun readBlock(): List<Pair<Int, String>> {
        val headingIndex = specLines.indexOfFirst { it.trim() == HEADING }
        if (headingIndex < 0) fail("no `$HEADING` heading in ${specPath()}")
        var index = headingIndex + 1
        // §5.3 opens with prose, so scan forward for the table — but never past the next heading,
        // or a restructured section would silently pick up somebody else's table.
        while (index < specLines.size &&
            !specLines[index].trimStart().startsWith("|") &&
            !specLines[index].trimStart().startsWith("#")
        ) {
            index++
        }
        if (index >= specLines.size || !specLines[index].trimStart().startsWith("|")) {
            fail("no `|`-delimited table between `$HEADING` and the next heading in ${specPath()}")
        }
        val block = mutableListOf<Pair<Int, String>>()
        while (index < specLines.size && specLines[index].trimStart().startsWith("|")) {
            block += (index + 1) to specLines[index]
            index++
        }
        if (block.size < 3) {
            fail(
                "§5.3's table in ${specPath()} parsed to ${block.size} line(s); a header, a " +
                    "separator and at least one data row were expected",
            )
        }
        return block
    }

    private fun unquote(cell: String): String = cell.trim().removeSurrounding("`").trim()
}
