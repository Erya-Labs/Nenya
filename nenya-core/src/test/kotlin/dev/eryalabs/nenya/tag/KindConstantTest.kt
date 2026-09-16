package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.seam.AmbientEffectsTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §5.2's one testable structural rule: the request kind is exposed as a **single named constant**
 * and the literal is not hardcoded in more than one place.
 *
 * §5.2 gives its own reason, which is not the one an implementer expects: "not because the value
 * may change (Appendix B forbids that), but because a wire constant duplicated across a codebase
 * is how two call sites end up disagreeing." A second copy is not a maintenance smell here — it is
 * two versions of the board, one of which cannot see the other's requests.
 *
 * The value itself is **parsed out of §5.2 at test time** rather than transcribed, for the reason
 * `SpecAnchor` gives about the NIP-44 digest: a transcription proves only that the test file
 * agrees with the test file. §5.2 states the kind in prose before it appears in any example, so
 * the anchor is that sentence and not a bare five-digit regex, which would match the JSON example
 * and the `item` row's Notes cell first.
 *
 * The comment stripper is [AmbientEffectsTest]'s, reused rather than rewritten. That one was fixed
 * twice — for a line that closes a block comment, and for a block comment that opens and closes on
 * one line, both of which it used to discard whole — and a second copy would start out with both
 * defects.
 */
class KindConstantTest {

    private companion object {

        /**
         * The literal, in either spelling Kotlin permits for it. A digit-group underscore would be
         * the obvious way to smuggle a second copy past a naive string search, so the pattern
         * catches `30_404` as well — a duplicate spelled differently is still a duplicate.
         */
        val REQUEST_KIND_LITERAL = Regex("""\b30_?404\b""")

        fun occurrences(): List<String> =
            AmbientEffectsTest.mainSources().flatMap { file ->
                AmbientEffectsTest.codeLines(file)
                    .filter { (_, line) -> REQUEST_KIND_LITERAL.containsMatchIn(line) }
                    .map { (number, line) -> "${file.path}:$number — $line" }
            }
    }

    @Test
    fun `the request kind is the one the specification decided`() {
        assertEquals(
            Section53.requestKind(),
            NenyaKind.REQUEST,
            "§5.2 closed `OPEN-1` on this value and Appendix B forbids it changing after the " +
                "first public release; read from ${Section53.specPath()}",
        )
    }

    @Test
    fun `the request kind literal appears exactly once in a non-comment main source line`() {
        val found = occurrences()

        assertEquals(
            1,
            found.size,
            "§5.2 requires `${NenyaKind.REQUEST}` be exposed as a single named constant and MUST " +
                "NOT be hardcoded in more than one place. Found: $found",
        )
        assertTrue(
            found.single().contains("NenyaKind.kt"),
            "the one occurrence must be the named constant's own declaration; it is at ${found.single()}",
        )
    }

    /**
     * The sweep's own control.
     *
     * If the stripper were broken — returning nothing, say — the assertion above would be green
     * over a source tree with the literal in it ten times, and green again over one with no
     * implementation at all. This pins both halves: the tree really is being read, and a
     * commented-out copy really is invisible while a live one is not.
     */
    @Test
    fun `the sweep reads the main tree and discriminates comments from code`() {
        val sources = AmbientEffectsTest.mainSources()

        assertTrue(sources.isNotEmpty(), "the sweep found no Kotlin sources at all")
        assertTrue(
            sources.any { it.name == "NenyaKind.kt" },
            "this task's own file must be among the sources swept; found ${sources.map { it.name }}",
        )

        val sample = """
            /** The Nenya request kind is 30404, decided as OPEN-1. */
            public const val REQUEST: Int = 30404 // and 30404 again, in a comment
            val other = 30_404
        """.trimIndent().lines()
        val code = AmbientEffectsTest.codeLines(sample).map { it.second }

        assertEquals(
            2,
            code.count { REQUEST_KIND_LITERAL.containsMatchIn(it) },
            "the KDoc copy and the trailing-comment copy must be invisible to the sweep, and the " +
                "underscored spelling must not be: $code",
        )
    }
}
