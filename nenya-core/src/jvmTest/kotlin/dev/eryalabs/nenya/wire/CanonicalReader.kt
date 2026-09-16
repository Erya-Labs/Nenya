package dev.eryalabs.nenya.wire

import kotlin.test.fail

/**
 * The independent oracle: RFC 8259's string rules read in the **opposite** direction, sharing no
 * code with the serialiser under test.
 *
 * Two algorithms agreeing is evidence; one algorithm agreeing with itself is not. Everything
 * `WireEvent.canonicalSerialisation` does is an *encoding* decision, and the only way to catch a
 * consistent-but-wrong encoder — one that escapes `/`, or emits uppercase `\u00XX` digits, or
 * drops a tag — is to decode its output with something that was written from RFC 8259 rather
 * than from the encoder.
 *
 * So this reader is deliberately **more permissive than §4.1**: it accepts `\/`, accepts
 * uppercase hex in a `\u` escape, and accepts the six-character form for characters §4.1 says
 * MUST use a shortcut. Every one of those is legal JSON and forbidden output, and the tests
 * assert the narrower rule on top of what this reader reports. A reader that rejected them
 * would report "malformed" where the interesting answer is "well-formed JSON, wrong Nenya".
 *
 * It also reports three things a decode alone cannot:
 *
 * - [Parsed.escapeForms] — every two-character escape prefix it consumed, so a test can assert
 *   the set is inside §4.1's eight legal forms and that `\/` is never among them;
 * - [Parsed.whitespaceOutsideStrings] — whitespace between structural tokens, which RFC 8259
 *   permits and §4.1's "no insignificant whitespace anywhere" forbids. The reader knows where
 *   the strings end, so it is the only thing that can tell the two apart;
 * - [Parsed.rawStrings] — the exact bytes between each pair of quotes, before unescaping, which
 *   is what the escape-table probe compares against the specification.
 *
 * It parses the canonical *event* grammar specifically — six elements, tags as an array of
 * arrays of strings — and fails loudly on anything else, so a serialiser that emitted an object
 * or a seventh element is a failure here rather than a silent pass.
 */
internal object CanonicalReader {

    enum class Kind { NUMBER, STRING, ARRAY }

    class Parsed(
        val elementKinds: List<Kind>,
        val prefix: Long,
        val pubkey: String,
        val createdAt: Long,
        val kind: Long,
        val tags: List<List<String>>,
        val content: String,
        val rawPubkey: String,
        val rawTagValues: List<List<String>>,
        val rawContent: String,
        val numberTokens: List<String>,
        val escapeForms: Set<String>,
        val whitespaceOutsideStrings: Int,
    ) {

        /** Every string body exactly as it was written, in the order the serialisation lists them. */
        val rawStrings: List<String>
            get() = listOf(rawPubkey) + rawTagValues.flatten() + rawContent
    }

    fun read(text: String): Parsed {
        val cursor = Cursor(text)
        val kinds = mutableListOf<Kind>()

        cursor.expect('[')
        kinds += Kind.NUMBER
        val prefix = cursor.readNumber()
        cursor.expect(',')
        kinds += Kind.STRING
        val pubkey = cursor.readString()
        cursor.expect(',')
        kinds += Kind.NUMBER
        val createdAt = cursor.readNumber()
        cursor.expect(',')
        kinds += Kind.NUMBER
        val kind = cursor.readNumber()
        cursor.expect(',')
        kinds += Kind.ARRAY
        val tags = cursor.readTags()
        cursor.expect(',')
        kinds += Kind.STRING
        val content = cursor.readString()
        cursor.expect(']')
        if (cursor.index != text.length) {
            fail("trailing input after the closing bracket at ${cursor.index} of ${text.length}")
        }

        val raw = cursor.rawStrings
        val rawTagValues = mutableListOf<List<String>>()
        var taken = 1
        for (tag in tags) {
            rawTagValues += raw.subList(taken, taken + tag.size).toList()
            taken += tag.size
        }
        return Parsed(
            elementKinds = kinds,
            prefix = prefix,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            rawPubkey = raw.first(),
            rawTagValues = rawTagValues,
            rawContent = raw.last(),
            numberTokens = cursor.numberTokens,
            escapeForms = cursor.escapeForms,
            whitespaceOutsideStrings = cursor.whitespace,
        )
    }

    private class Cursor(val text: String) {

        var index: Int = 0
        var whitespace: Int = 0
        val escapeForms: MutableSet<String> = mutableSetOf()
        val rawStrings: MutableList<String> = mutableListOf()
        val numberTokens: MutableList<String> = mutableListOf()

        /**
         * RFC 8259 permits whitespace between structural tokens; §4.1 forbids it. Skipped and
         * **counted** rather than rejected, so the test asserts §4.1's rule rather than this
         * reader's opinion of it.
         */
        private fun skipWhitespace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\t' ||
                    text[index] == '\n' || text[index] == '\r')
            ) {
                whitespace++
                index++
            }
        }

        fun expect(character: Char) {
            skipWhitespace()
            if (index >= text.length || text[index] != character) {
                fail("expected '$character' at $index but found ${describe()}")
            }
            index++
        }

        private fun describe(): String =
            if (index >= text.length) "end of input" else "'${text[index]}' (0x${text[index].code.toString(16)})"

        fun readNumber(): Long {
            skipWhitespace()
            val start = index
            if (index < text.length && text[index] == '-') index++
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == start) fail("expected a number at $start but found ${describe()}")
            val token = text.substring(start, index)
            numberTokens += token
            return token.toLong()
        }

        fun readTags(): List<List<String>> {
            expect('[')
            val tags = mutableListOf<List<String>>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return tags
            }
            while (true) {
                expect('[')
                val values = mutableListOf<String>()
                skipWhitespace()
                if (index < text.length && text[index] == ']') {
                    index++
                } else {
                    while (true) {
                        values += readString()
                        skipWhitespace()
                        if (index < text.length && text[index] == ',') {
                            index++
                        } else {
                            expect(']')
                            break
                        }
                    }
                }
                tags += values
                skipWhitespace()
                if (index < text.length && text[index] == ',') {
                    index++
                } else {
                    expect(']')
                    return tags
                }
            }
        }

        /** RFC 8259 section 7, read backwards. Records the raw body and every escape prefix. */
        fun readString(): String {
            expect('"')
            val bodyStart = index
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string from $bodyStart")
                val character = text[index]
                when {
                    character == '"' -> {
                        rawStrings += text.substring(bodyStart, index)
                        index++
                        return out.toString()
                    }
                    character == '\\' -> {
                        if (index + 1 >= text.length) fail("truncated escape at $index")
                        val marker = text[index + 1]
                        escapeForms += "\\$marker"
                        when (marker) {
                            '"' -> { out.append('"'); index += 2 }
                            '\\' -> { out.append('\\'); index += 2 }
                            '/' -> { out.append('/'); index += 2 }
                            'b' -> { out.append(0x08.toChar()); index += 2 }
                            'f' -> { out.append(0x0C.toChar()); index += 2 }
                            'n' -> { out.append(0x0A.toChar()); index += 2 }
                            'r' -> { out.append(0x0D.toChar()); index += 2 }
                            't' -> { out.append(0x09.toChar()); index += 2 }
                            'u' -> {
                                if (index + 6 > text.length) fail("truncated \\u escape at $index")
                                val digits = text.substring(index + 2, index + 6)
                                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail("\\u escape at $index is not four hex digits")
                                }
                                out.append(digits.toInt(16).toChar())
                                index += 6
                            }
                            else -> fail(
                                "illegal escape '\\$marker' at $index — RFC 8259 names eight two-character " +
                                    "escapes plus \\u, and this is none of them",
                            )
                        }
                    }
                    character.code < 0x20 -> fail(
                        "an unescaped control character (0x${character.code.toString(16)}) at $index; " +
                            "RFC 8259 forbids one inside a string, and it is the fatal property of " +
                            "NIP-01's literal reading that §4.1 exists to avoid",
                    )
                    else -> { out.append(character); index++ }
                }
            }
        }
    }
}
