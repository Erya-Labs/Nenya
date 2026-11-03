package dev.eryalabs.nenya.js

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Decision P held by a scan: no code line in the JavaScript layer throws a library exception or names
 * a rejection constant.
 *
 * ### Why a scan
 *
 * Decision P says the layer "adds no rule", and any check it makes "must be the same call the JVM side
 * makes". T82 moved the last checks the layer made of its own into `channel.OrderBinding`. A check
 * written back into `js/` would show as one of two things: a `throw …Exception(` the layer raises
 * itself, or a `…Rejection.CONSTANT` it names to refuse with. So every `.kt` file in the layer is held
 * to neither, on every line that is code. KDoc and comments may name a rejection — the doors' own KDoc
 * says what the library refuses with — and the layer may throw [JsCrossing], its one crossing failure.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest`. This one cannot be there: it reads the layer's
 * source from disk, which a common test cannot do. The scan takes text as a parameter, so every
 * control below runs on text built in memory, and only one test opens the directory.
 */
class JsTranslationOnlyTest {

    private companion object {

        /** Gradle runs JVM tests with the module directory as the working directory. */
        const val LAYER_PATH: String = "src/commonMain/kotlin/dev/eryalabs/nenya/js"

        /** The layer's file count when this test was written; fewer means the scan lost files. */
        const val MINIMUM_FILES: Int = 11

        val LIBRARY_THROW: Regex = Regex("""throw\s+[A-Z]\w*Exception\b""")

        val REJECTION_CONSTANT: Regex = Regex("""\b[A-Z]\w*Rejection\.[A-Z][A-Z0-9_]+\b""")

        /** A comment or KDoc line: after whitespace, it starts with a star, two slashes, or a comment opener. */
        fun isComment(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }

        /**
         * The code lines of [text] that throw a library exception or name a rejection constant, each
         * as `line number: line`, in order. Comment and KDoc lines are never reported.
         */
        fun violations(text: String): List<String> =
            text.lines().withIndex()
                .filter { (_, line) -> !isComment(line) }
                .filter { (_, line) -> LIBRARY_THROW.containsMatchIn(line) || REJECTION_CONSTANT.containsMatchIn(line) }
                .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        /**
         * Every `.kt` file directly in [directory], by name. A missing directory, or fewer than
         * [MINIMUM_FILES] files, fails; it never skips.
         */
        fun layerFiles(directory: File): List<File> {
            assertTrue(
                directory.isDirectory,
                "${directory.path} was expected at ${directory.absoluteFile.normalize()}; the working directory is " +
                    File(".").absoluteFile.normalize(),
            )
            val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".kt") }.orEmpty()
                .sortedBy { it.name }
            assertTrue(
                files.size >= MINIMUM_FILES,
                "${directory.path} holds ${files.size} .kt files, fewer than the $MINIMUM_FILES the layer had: " +
                    files.map { it.name },
            )
            return files
        }

        /** Fails naming every violating line of every file in [directory]. */
        fun assertTranslationOnly(directory: File) {
            val found = layerFiles(directory).associate { it.name to violations(it.readText(Charsets.UTF_8)) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap(), found, "code lines in the JavaScript layer that throw or name a rejection")
        }

        /** Runs [block] and fails unless it fails with an assertion. */
        fun assertScanFails(block: () -> Unit) {
            val failed = try {
                block()
                false
            } catch (_: AssertionError) {
                true
            }
            if (!failed) fail("the scan was expected to fail and passed")
        }
    }

    @Test
    fun `no code line in the JavaScript layer throws a library exception or names a rejection`() {
        assertTranslationOnly(File(LAYER_PATH))
    }

    @Test
    fun `a code line throwing a library exception is reported`() {
        val text = "fun f() {\n    throw ChannelException(ChannelRejection.WRONG_SENDER, null)\n}"
        assertEquals(listOf("2: throw ChannelException(ChannelRejection.WRONG_SENDER, null)"), violations(text))
        assertEquals(1, violations("        throw  ChannelException(").size)
    }

    @Test
    fun `a code line naming a rejection constant is reported`() {
        assertEquals(listOf("1: ChannelRejection.DIFFERENT_ORDER,"), violations("        ChannelRejection.DIFFERENT_ORDER,"))
        assertEquals(1, violations("val r = TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER").size)
    }

    @Test
    fun `the same lines inside KDoc or a comment are not reported`() {
        val text = listOf(
            "/**",
            " * throw ChannelException(",
            " * `ChannelRejection.DIFFERENT_ORDER`,",
            " */",
            "// throw ChannelException(ChannelRejection.DIFFERENT_ORDER)",
            "    /* ChannelRejection.DIFFERENT_ORDER */",
        ).joinToString("\n")
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun `throwing JsCrossing is not reported`() {
        assertEquals(emptyList(), violations("    throw JsCrossing(JsCrossing.UNCONSTRUCTIBLE_VALUE)"))
    }

    @Test
    fun `a missing directory or too few files fails and never skips`() {
        val root = File("build/tmp/js-translation-only-${System.nanoTime()}")
        assertScanFails { assertTranslationOnly(File(root, "absent")) }
        try {
            assertTrue(root.mkdirs())
            repeat(MINIMUM_FILES - 1) { File(root, "Clean$it.kt").writeText("fun f$it() = $it\n") }
            assertScanFails { assertTranslationOnly(root) }
            File(root, "Clean${MINIMUM_FILES - 1}.kt").writeText("fun g() = 0\n")
            assertTranslationOnly(root)
            File(root, "Dirty.kt").writeText("fun h(): Nothing = throw ChannelException(\n")
            assertScanFails { assertTranslationOnly(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
