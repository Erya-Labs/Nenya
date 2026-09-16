package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.SpecAnchor
import dev.eryalabs.nenya.tag.Section53
import kotlin.test.fail

/**
 * §7.4's two tables and its fenced required-tags block, parsed out of `spec/NENYA-1.md` at test
 * time.
 *
 * Nothing here is transcribed. Every rumor kind, every `type` number and every required tag name is
 * read from the document the tests run against, exactly as `Section53` reads §5.3 and `Section11`
 * reads §11.2, so a specification revision that added a row, renumbered a `type` or changed the
 * required set turns the suite red instead of leaving a stale copy in this package agreeing with
 * itself.
 *
 * ### Four mechanical traps in this particular parse, each a silent wrong answer rather than a crash
 *
 * 1. **Two adjacent tables in one section.** §7.4 prints the rumor-kind table and the `type` table
 *    five lines apart, so "the first table after §7.4" finds one of them and "the table after that"
 *    depends on which. Each is anchored on its **own header cells** instead, and
 *    `RumorVocabularyTest` asserts the two parses returned different column counts — 2 and 4 — so a
 *    parser that found the same table twice cannot pass.
 * 2. **§7.5 and §7.6 are `####` subsections *inside* §7.4.** A slice running from `### 7.4` to the
 *    next `###` swallows both and picks up §7.5's worked JSON, whose fenced block would then be
 *    read as the required-tags block. [sectionLines] stops at the next line opening with **any**
 *    number of `#`, and [requiredTagNames] asserts no ```` ```json ```` fence is inside the slice.
 * 3. **The `type=4` row's Sender cell is an em dash**, U+2014 — not empty, and not the ASCII `-`.
 *    An ASCII transcription of it is a different string, and the control in `RumorVocabularyTest`
 *    is what says so.
 * 4. **The fenced block carries trailing `#` comments and one continuation line.** Two of the three
 *    comments run past where a naive quote search would stop, and the continuation line
 *    (`# 32 bytes, 64 lowercase hex chars`) carries no tag at all. Each line is cut at its first
 *    `#` and the tag name is the first quoted token of what is left; a line with none contributes
 *    nothing, and exactly three names must come out.
 *
 * The cell splitter is [Section53.cells], reused rather than rewritten. That one was written for
 * §5.3's markdown-escaped pipes, and a second copy here would start out without that fix even
 * though §7.4 has no escaped pipe today.
 *
 * Every accessor fails loudly, naming the file it read, so a wrong path cannot make a test pass
 * vacuously.
 */
internal object Section74 {

    /** One parsed table row: its 1-based spec line and its cells, in table order. */
    internal data class Row(val specLine: Int, val cells: List<String>)

    private const val HEADING: String = "### 7.4 Order message types"

    /** The first table's header, cell for cell. Two columns. */
    private val RUMOR_KIND_HEADER: List<String> = listOf("Rumor kind", "Meaning")

    /** The second table's header, cell for cell. Four columns, and the first cell is backticked. */
    private val TYPE_HEADER: List<String> = listOf("type", "Name", "Sender", "Nenya use")

    /** A quoted token — the tag name inside a required-tags line's first pair of quotes. */
    private val QUOTED = Regex("\"([^\"]+)\"")

    /** A markdown heading of any level, which is what bounds §7.4. Column zero, `#` then a space. */
    private val HEADING_LINE = Regex("""^#{1,6} """)

    private val specLines: List<String> by lazy { SpecAnchor.specFile().readLines() }

    /**
     * §7.4 proper: from its heading to the next line opening with `#`, whatever its level.
     *
     * Stopping at any `#` and not at `###` is trap 2 above: §7.5 and §7.6 are `####` subsections of
     * §7.4, and a slice that ran to the next `###` would carry §7.5's worked JSON.
     */
    internal val sectionLines: List<Pair<Int, String>> by lazy { readSection() }

    /** §7.4's first table: the four rumor kinds. */
    internal val rumorKindRows: List<Row> by lazy { rows(RUMOR_KIND_HEADER) }

    /** §7.4's second table: the six `kind:16` `type` values. */
    internal val typeRows: List<Row> by lazy { rows(TYPE_HEADER) }

    /** The kind numbers of the first table's first column, in table order. */
    internal fun rumorKinds(): List<Int> = rumorKindRows.map { number(it, "a rumor kind") }

    /** The `type` numbers of the second table's first column, in table order. */
    internal fun types(): List<Int> = typeRows.map { number(it, "a `type`") }

    /** The second table's row for [type], or a loud failure. */
    internal fun typeRow(type: Int): Row =
        typeRows.singleOrNull { number(it, "a `type`") == type }
            ?: fail("§7.4 in ${specPath()} carries no single row for `type` $type")

    /**
     * The tag names in §7.4's fenced required-tags block, in the order it prints them.
     *
     * See trap 4 above for the two things that make this more than a regex over the block.
     */
    internal val requiredTagNames: List<String> by lazy { readRequiredTagNames() }

    internal fun specPath(): String = SpecAnchor.specFile().location

    private fun readSection(): List<Pair<Int, String>> {
        val headingIndex = specLines.indexOfFirst { it.trim() == HEADING }
        if (headingIndex < 0) fail("no `$HEADING` heading in ${specPath()}")
        val out = mutableListOf<Pair<Int, String>>()
        var index = headingIndex + 1
        // A **heading**, matched at column zero, and not any line whose first non-blank character
        // is a `#`: §7.4's own required-tags block carries a continuation line that is nothing but
        // an indented `#` comment, and a `trimStart()` test cuts the slice in half at it — leaving
        // the block's opening fence in and its closing fence out.
        while (index < specLines.size && !HEADING_LINE.containsMatchIn(specLines[index])) {
            out += (index + 1) to specLines[index]
            index++
        }
        if (out.isEmpty()) fail("`$HEADING` in ${specPath()} is followed by no body at all")
        return out
    }

    /**
     * The `|`-delimited block whose header cells are [header], as data rows.
     *
     * Anchored on the header rather than on position, and required to match **exactly once**: two
     * matches would mean the anchor does not discriminate, and zero would mean §7.4 no longer
     * carries the table this package models.
     */
    private fun rows(header: List<String>): List<Row> {
        val blocks = pipeBlocks().filter { block -> unquotedCells(block.first().second) == header }
        if (blocks.size != 1) {
            fail(
                "§7.4 in ${specPath()} must carry exactly one `|`-delimited table whose header is " +
                    "$header; found ${blocks.size}. The headers found were " +
                    "${pipeBlocks().map { unquotedCells(it.first().second) }}",
            )
        }
        val block = blocks.single()
        if (block.size < MINIMUM_TABLE_LINES) {
            fail(
                "the §7.4 table headed $header in ${specPath()} parsed to ${block.size} line(s); a " +
                    "header, a separator and at least one data row were expected",
            )
        }
        val separator = Section53.cells(block[1].second)
        if (separator.any { cell -> cell.isEmpty() || cell.any { it != '-' && it != ':' } }) {
            fail("the second line of the §7.4 table headed $header in ${specPath()} is not a `|---|` separator")
        }
        return block.drop(2).map { (line, text) ->
            val cells = Section53.cells(text)
            if (cells.size != header.size) {
                fail(
                    "line $line of ${specPath()} parsed to ${cells.size} cell(s) where " +
                        "${header.size} were expected. Row: $text",
                )
            }
            Row(line, cells)
        }
    }

    /** Every run of consecutive `|`-opening lines inside §7.4, header and all. */
    private fun pipeBlocks(): List<List<Pair<Int, String>>> {
        val blocks = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        for (line in sectionLines) {
            if (line.second.trimStart().startsWith("|")) {
                current += line
            } else if (current.isNotEmpty()) {
                blocks += current.toList()
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) blocks += current.toList()
        if (blocks.isEmpty()) fail("§7.4 in ${specPath()} carries no `|`-delimited table at all")
        return blocks
    }

    private fun unquotedCells(row: String): List<String> =
        Section53.cells(row).map { it.trim().removeSurrounding("`").trim() }

    private fun number(row: Row, what: String): Int {
        val cell = row.cells.first().trim().removeSurrounding("`").trim()
        return cell.toIntOrNull()
            ?: fail("line ${row.specLine} of ${specPath()} opens with `$cell`, which is not $what")
    }

    private fun readRequiredTagNames(): List<String> {
        val fenceLines = sectionLines.map { it.second }
        if (fenceLines.any { it.trimStart().startsWith("```json") }) {
            fail(
                "§7.4's slice in ${specPath()} reaches a ```json fence, so it swallowed §7.5 — the " +
                    "worked proposal's tags would then be read as §7.4's required set",
            )
        }
        val opens = fenceLines.withIndex().filter { it.value.trim() == FENCE }.map { it.index }
        if (opens.size != 2) {
            fail(
                "§7.4's required-tags block in ${specPath()} must be one fenced block; found " +
                    "${opens.size} plain ``` fence line(s)",
            )
        }
        val names = fenceLines.subList(opens[0] + 1, opens[1]).mapNotNull { line ->
            // Cut the trailing `#` comment first: two of the three run long, and one line is a
            // comment continuation carrying no tag at all.
            QUOTED.find(line.substringBefore('#'))?.groupValues?.get(1)
        }
        if (names.size != REQUIRED_TAG_NAMES) {
            fail(
                "§7.4's required-tags block in ${specPath()} parsed to ${names.size} tag name(s) " +
                    "($names) where $REQUIRED_TAG_NAMES were expected",
            )
        }
        return names
    }

    /** A header, a separator and at least one data row. */
    private const val MINIMUM_TABLE_LINES: Int = 3

    /** §7.4's required-tags block is plain-fenced, which is what tells it from §7.5's `json` one. */
    private const val FENCE: String = "```"

    /** §7.4's block names `nenya`, `p` and `order`, and the count is asserted rather than assumed. */
    private const val REQUIRED_TAG_NAMES: Int = 3
}
