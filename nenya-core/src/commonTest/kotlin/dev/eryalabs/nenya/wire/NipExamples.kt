package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.VendoredFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The worked envelope examples in NIP-59 and NIP-17, parsed out of the vendored reference documents
 * **at test time**.
 *
 * §18 names them as the vector source for the gift-wrap round trip, and says exactly what can be
 * checked against them with no cryptography at all: that all four parse as event JSON under §7.1's
 * object rules, and that each one's `id` recomputes from §4.1's serialisation. Decrypting them would
 * need keys the examples do not publish.
 *
 * Nothing here is transcribed, for the reason [Section41] gives about §4.1: a hand-copied fixture
 * proves that the copy agrees with the test that copied it. These are externally authored — the
 * pubkeys, the ids, the signatures and the base64 payloads are all somebody else's — so a
 * recomputation that agrees with them is evidence from outside this repository. Parsing rather than
 * transcribing also means a vendored document that drifts turns the suite red.
 *
 * ### NIP-59's worked wrap is the odd one out, deliberately
 *
 * §18: "NIP-59's worked **wrap** carries a trailing comma and is not strict JSON: it is a
 * documentation example, not a vector, and a parser that accepts it is a parser that accepts
 * malformed input from a relay." So it is here as a **negative** control ([nip59Wrap]) and as a
 * repaired positive one ([nip59WrapWithoutItsTrailingComma]), and §18's own advice — use NIP-17's
 * two for the wrap layer — is what [nip17Wraps] is for.
 *
 * Every accessor fails loudly, naming the file it read, so a moved heading or a renamed fence can
 * never make a test pass vacuously.
 */
internal object NipExamples {

    private const val NIP59_PATH: String = "spec/reference/nip59.md"

    private const val NIP17_PATH: String = "spec/reference/nip17.md"

    /** The opening fence of a JSON example. Markdown's own marker, not something this file invents. */
    private const val JSON_FENCE: String = "```json"

    private const val FENCE: String = "```"

    /** NIP-59 "1. Create an event": the unsigned `kind:1` rumor, with **no `sig` key at all**. */
    fun nip59Rumor(): String = blocksAfter(NIP59_PATH, "### 1. Create an event", 1).single()

    /** NIP-59 "2. Seal the rumor": the signed `kind:13` seal, `tags` empty per §7.1 step 2. */
    fun nip59Seal(): String = blocksAfter(NIP59_PATH, "### 2. Seal the rumor", 1).single()

    /** NIP-59 "3. Wrap the seal": the `kind:1059` wrap §18 warns is not strict JSON. */
    fun nip59Wrap(): String = blocksAfter(NIP59_PATH, "### 3. Wrap the seal", 1).single()

    /** NIP-17's Examples section: the wrap to the receiver and the wrap to the sender, in that order. */
    fun nip17Wraps(): List<String> = blocksAfter(NIP17_PATH, "## Examples", 2)

    /**
     * [nip59Wrap] with its one trailing comma removed, and nothing else touched.
     *
     * The repair is mechanical and asserted rather than eyeballed: the last `,` before the closing
     * `}` is deleted, having first checked that only whitespace separates the two — so this can
     * only ever remove a *trailing* comma, and the result is proved to be exactly one character
     * shorter than the original with that character a comma. It is not a fixture somebody typed:
     * every other byte, including the id this test then recomputes, is still the vendored file's.
     */
    fun nip59WrapWithoutItsTrailingComma(): String {
        val raw = nip59Wrap()
        val brace = raw.lastIndexOf('}')
        assertTrue(brace > 0, "NIP-59's worked wrap has no closing brace at all")
        val comma = raw.lastIndexOf(',', brace)
        assertTrue(comma > 0, "NIP-59's worked wrap carries no comma before its closing brace")
        assertTrue(
            raw.substring(comma + 1, brace).isBlank(),
            "the last comma in NIP-59's worked wrap is not a trailing one — something other than " +
                "whitespace separates it from the closing brace, so this repair would change a value",
        )
        val repaired = raw.substring(0, comma) + raw.substring(comma + 1)
        assertEquals(
            raw.length - 1,
            repaired.length,
            "the repair must delete exactly one character",
        )
        return repaired
    }

    /**
     * The first [count] fenced JSON blocks under [heading], stopping at the next Markdown heading.
     *
     * Stopping at the next heading matters: NIP-59 prints three skeleton events with placeholder
     * `content` before its worked example, and a scan that ran past its own section would pick one
     * of those up and check an `id` that is not there.
     */
    private fun blocksAfter(path: String, heading: String, count: Int): List<String> {
        val file = VendoredFile(path)
        val lines = file.readLines()
        val start = lines.indexOfFirst { it.trim() == heading }
        if (start < 0) fail("no line reading \"$heading\" in ${file.location}")

        val blocks = mutableListOf<String>()
        var index = start + 1
        while (index < lines.size && !lines[index].startsWith("#")) {
            if (lines[index].trim() != JSON_FENCE) {
                index++
                continue
            }
            index++
            val body = StringBuilder()
            var closed = false
            while (index < lines.size) {
                if (lines[index].trim() == FENCE) {
                    closed = true
                    index++
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[index])
                index++
            }
            if (!closed) fail("an unterminated $JSON_FENCE block under \"$heading\" in ${file.location}")
            blocks += body.toString()
        }

        assertEquals(
            count,
            blocks.size,
            "\"$heading\" in ${file.location} must carry $count fenced JSON block(s) before the " +
                "next heading; the parse found ${blocks.size}",
        )
        for (block in blocks) {
            assertTrue(
                block.trimStart().startsWith("{"),
                "a block under \"$heading\" in ${file.location} is not a JSON object",
            )
        }
        return blocks
    }
}
