package dev.eryalabs.nenya.text

/*
 * The one UTF-8 encoder Nenya uses for bytes that are hashed, measured or compared.
 *
 * A Kotlin String is UTF-16 and can hold an unpaired surrogate - half of a pair, or a pair in the
 * wrong order - which is not a character and has no UTF-8 encoding. A JSON parser produces one
 * readily from a `\uD800` escape. The platforms disagree about what to do with it: the JVM's
 * encoder substitutes `?` (0x3F) and JavaScript's substitutes U+FFFD (EF BF BD). Nenya hashes the
 * UTF-8 of a canonical serialisation to get an event id (NENYA-1 section 4.1), so a lenient
 * encoder would give the same event two different ids on the two platforms. Such text is refused
 * instead, identically on both.
 */

/**
 * True when every high surrogate in [text] is immediately followed by a low surrogate and every
 * low surrogate immediately follows a high one - that is, when [text] has a UTF-8 encoding.
 */
internal fun isWellFormedUtf16(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val unit = text[index]
        when {
            unit.isHighSurrogate() -> {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                index += 2
            }
            unit.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}

/**
 * The UTF-8 bytes of [text], or null when [text] is not well-formed UTF-16.
 *
 * Never substitutes. The explicit check decides, so the answer does not depend on either
 * platform's encoder; `throwOnInvalidSequence = true` is kept as a second line so that a defect
 * in the check could only ever refuse, never substitute.
 */
internal fun strictUtf8OrNull(text: String): ByteArray? {
    if (!isWellFormedUtf16(text)) return null
    return try {
        text.encodeToByteArray(0, text.length, throwOnInvalidSequence = true)
    } catch (refused: CharacterCodingException) {
        null
    }
}
