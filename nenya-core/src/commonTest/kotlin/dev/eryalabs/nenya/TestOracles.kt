package dev.eryalabs.nenya

/**
 * SHA-256 from the **platform**, never from this library: `java.security.MessageDigest` on the
 * JVM and Node's `crypto.createHash` on JavaScript.
 *
 * The suite's fixtures compute every expected digest with an implementation that shares no code
 * with `dev.eryalabs.nenya.crypto.Sha256`. Before tests could run as JavaScript that was
 * `MessageDigest` called inline; this is the same oracle behind one common name, so a moved test
 * keeps an independent hasher on both targets instead of quietly checking the library against
 * itself.
 */
internal expect fun oracleSha256(bytes: ByteArray): ByteArray

/** `toByteArray(Charsets.UTF_8)` for common tests: [TestText.utf8]. */
internal fun String.utf8Bytes(): ByteArray = TestText.utf8(this)

/** `toByteArray(Charsets.ISO_8859_1)` for common tests: [TestText.latin1]. */
internal fun String.latin1Bytes(): ByteArray = TestText.latin1(this)

/**
 * Text helpers for common tests that the JDK used to provide, each written independently of the
 * library's own `dev.eryalabs.nenya.text` code so a fixture's bytes are not the code under test's
 * bytes. `CommonTestOraclesTest` (jvmTest) checks every one against the JDK call it replaces.
 */
internal object TestText {

    /**
     * The UTF-8 bytes of [text], encoded here by hand — the replacement for
     * `toByteArray(Charsets.UTF_8)`.
     *
     * Not `encodeToByteArray()`: that is what production code calls, and an oracle built on the
     * same call as the code under test would share its flaws. For well-formed text this agrees
     * with the JDK byte for byte. Text with an unpaired surrogate has no UTF-8 encoding, and this
     * refuses it rather than inventing one (the JDK substitutes `?`), so a fixture can never
     * silently carry one.
     */
    fun utf8(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var index = 0
        while (index < text.length) {
            val unit = text[index].code
            val codePoint: Int
            if (unit in 0xD800..0xDBFF) {
                val low = if (index + 1 < text.length) text[index + 1].code else -1
                require(low in 0xDC00..0xDFFF) { "unpaired high surrogate at $index has no UTF-8 encoding" }
                codePoint = 0x10000 + ((unit - 0xD800) shl 10) + (low - 0xDC00)
                index += 2
            } else {
                require(unit !in 0xDC00..0xDFFF) { "unpaired low surrogate at $index has no UTF-8 encoding" }
                codePoint = unit
                index += 1
            }
            when {
                codePoint < 0x80 -> out.add(codePoint.toByte())
                codePoint < 0x800 -> {
                    out.add((0xC0 or (codePoint shr 6)).toByte())
                    out.add((0x80 or (codePoint and 0x3F)).toByte())
                }
                codePoint < 0x10000 -> {
                    out.add((0xE0 or (codePoint shr 12)).toByte())
                    out.add((0x80 or ((codePoint shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (codePoint and 0x3F)).toByte())
                }
                else -> {
                    out.add((0xF0 or (codePoint shr 18)).toByte())
                    out.add((0x80 or ((codePoint shr 12) and 0x3F)).toByte())
                    out.add((0x80 or ((codePoint shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (codePoint and 0x3F)).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * `toByteArray(Charsets.ISO_8859_1)` as the JDK encodes it: a character up to `0xFF` is its own
     * byte, and every unmappable character becomes `?` — a surrogate **pair** becomes one `?`,
     * because the JDK's encoder replaces the whole malformed-or-unmappable input sequence.
     */
    fun latin1(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var index = 0
        while (index < text.length) {
            val unit = text[index].code
            if (unit <= 0xFF) {
                out.add(unit.toByte())
                index += 1
            } else if (unit in 0xD800..0xDBFF && index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF) {
                out.add('?'.code.toByte())
                index += 2
            } else {
                out.add('?'.code.toByte())
                index += 1
            }
        }
        return out.toByteArray()
    }

    /** `String(Character.toChars(codePoint))`: one code point as its one or two UTF-16 units. */
    fun codePoint(codePoint: Int): String {
        require(codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) { "not a scalar value: $codePoint" }
        if (codePoint < 0x10000) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        return charArrayOf(
            (0xD800 + (offset shr 10)).toChar(),
            (0xDC00 + (offset and 0x3FF)).toChar(),
        ).concatToString()
    }

    /**
     * `java.io.File.readLines()` over text: split on `\r\n`, `\n` or `\r`, with no empty entry after a
     * final line terminator. Line indexes are therefore the file's own 1-based line numbers minus one.
     */
    fun lines(text: String): List<String> {
        val split = text.lines()
        return if (split.isNotEmpty() && split.last().isEmpty()) split.dropLast(1) else split
    }

    /** Lowercase, unpadded hex — §4.3's canonical form. */
    fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
