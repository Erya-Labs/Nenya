package dev.eryalabs.nenya.js

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Decision P held by a scan: no code in the JavaScript layer throws a library exception or names a
 * rejection constant.
 *
 * ### Why a scan
 *
 * Decision P says the layer "adds no rule", and any check it makes "must be the same call the JVM side
 * makes". T82 moved the last checks the layer made of its own into `channel.OrderBinding`. A check
 * written back into `js/` would show as one of four things: a `throw …Exception(` the layer raises
 * itself, a `throw e` (or `throw (e)`, `throw(e)`, ``throw `e` ``) rethrowing a library exception it
 * caught, a `…Rejection.CONSTANT` it names to refuse with (whitespace or a line break around the dot
 * included), or an `import` of such a constant so it can name it bare, or of the type under an alias
 * so it can name `R.CONSTANT` (T96). So every `.kt` file in the
 * layer is held to none of these, in every part of it that is code. KDoc and comments may name a
 * rejection — the doors' own KDoc says what the library refuses with — and the layer may throw
 * [JsCrossing], its one crossing failure.
 *
 * ### What counts as code
 *
 * T83's first scan judged a line by how it starts, so a code line starting with `*` passed as KDoc and
 * a `throw` with its exception on the next line passed as two clean lines. T92 reads the text once,
 * tracking `/* … */` (nested, as Kotlin nests them), `//` and string and character literals, and blanks
 * only the comments. String contents stay in: a rejection name in a string is still reported. The rules
 * then run over the whole of what is left, so `\s` crosses a line break — and a blanked comment — the
 * way the compiler does, and each hit is reported on the line where it starts.
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

        /** Every rule a code part of the layer must not match. */
        val RULES: List<Regex> = listOf(
            // a library exception thrown, its name on the same line or a later one
            Regex("""throw\s+[A-Z]\w*Exception\b"""),
            // a caught value rethrown: `throw e`, `throw (e)`, `throw(e)`, ``throw `e` ``
            Regex("""\bthrow(\s+[a-z_`]|\s*\()"""),
            // a rejection constant named, with or without whitespace or a line break around the dot
            Regex("""\b[A-Z]\w*Rejection\s*\.\s*[A-Z][A-Z0-9_]+\b"""),
            // a rejection's constants imported, all of them or one, so they can be named bare
            Regex("""\bimport\s+[\w.]*\b[A-Z]\w*Rejection\s*\.\s*(\*|[A-Z][A-Z0-9_]*\b)"""),
            // a rejection type imported under another name, so `R.CONSTANT` names it unseen
            Regex("""\bimport\s+[\w.]*\b[A-Z]\w*Rejection\s+as\b"""),
        )

        /**
         * [text] with every comment replaced by spaces, line breaks kept, so offsets and line numbers
         * are unchanged. String and character literals are kept, and a comment opener inside one opens
         * no comment; a string template's `${ … }` is read as code again.
         */
        fun codeOnly(text: String): String {
            val out = StringBuilder(text)
            // Each entry is the mode of one nesting level; a template's entry counts its open braces.
            val modes = ArrayDeque<Mode>().apply { addLast(Mode(Kind.CODE)) }
            var i = 0
            fun blank(from: Int, until: Int) {
                for (j in from until until) if (out[j] != '\n') out[j] = ' '
            }
            while (i < text.length) {
                val mode = modes.last()
                when (mode.kind) {
                    Kind.CODE, Kind.TEMPLATE -> when {
                        text.startsWith("//", i) -> {
                            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                            blank(i, end)
                            i = end
                        }
                        text.startsWith("/*", i) -> {
                            var depth = 0
                            var j = i
                            while (j < text.length) {
                                if (text.startsWith("/*", j)) {
                                    depth++
                                    j += 2
                                } else if (text.startsWith("*/", j)) {
                                    depth--
                                    j += 2
                                    if (depth == 0) break
                                } else {
                                    j++
                                }
                            }
                            blank(i, j)
                            i = j
                        }
                        text.startsWith("\"\"\"", i) -> {
                            modes.addLast(Mode(Kind.RAW_STRING))
                            i += 3
                        }
                        text[i] == '"' -> {
                            modes.addLast(Mode(Kind.STRING))
                            i++
                        }
                        text[i] == '\'' -> {
                            var j = i + 1
                            if (j < text.length && text[j] == '\\') j += 2 else j++
                            while (j < text.length && text[j] != '\'' && text[j] != '\n') j++
                            i = j + 1
                        }
                        text[i] == '{' && mode.kind == Kind.TEMPLATE -> {
                            mode.braces++
                            i++
                        }
                        text[i] == '}' && mode.kind == Kind.TEMPLATE -> {
                            if (mode.braces == 0) modes.removeLast() else mode.braces--
                            i++
                        }
                        else -> i++
                    }
                    Kind.STRING -> when {
                        text[i] == '\\' -> i += 2
                        text[i] == '"' -> {
                            modes.removeLast()
                            i++
                        }
                        text.startsWith("\${", i) -> {
                            modes.addLast(Mode(Kind.TEMPLATE))
                            i += 2
                        }
                        else -> i++
                    }
                    Kind.RAW_STRING -> when {
                        text.startsWith("\"\"\"", i) -> {
                            // A run of more than three quotes keeps the extra ones as content.
                            var j = i
                            while (j < text.length && text[j] == '"') j++
                            modes.removeLast()
                            i = j
                        }
                        text.startsWith("\${", i) -> {
                            modes.addLast(Mode(Kind.TEMPLATE))
                            i += 2
                        }
                        else -> i++
                    }
                }
            }
            return out.toString()
        }

        enum class Kind { CODE, TEMPLATE, STRING, RAW_STRING }

        class Mode(val kind: Kind, var braces: Int = 0)

        /**
         * The lines of [text] on which code throws a library exception, rethrows a value, names a
         * rejection constant or imports one, each as `line number: line`, once, in order. Comment and
         * KDoc text is never reported.
         */
        fun violations(text: String): List<String> {
            val code = codeOnly(text)
            val lines = text.lines()
            val hits = RULES.flatMap { rule -> rule.findAll(code).map { it.range.first }.toList() }
                .map { offset -> code.substring(0, offset).count { it == '\n' } }
                .toSortedSet()
            return hits.map { index -> "${index + 1}: ${lines[index].trim()}" }
        }

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
            " * throw e",
            " * import dev.eryalabs.nenya.channel.ChannelRejection.*",
            " */",
            "// throw ChannelException(ChannelRejection.DIFFERENT_ORDER)",
            "    /* ChannelRejection.DIFFERENT_ORDER */",
            "/* outer /* nested */ throw e still a comment */",
        ).joinToString("\n")
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun `throwing JsCrossing is not reported`() {
        assertEquals(emptyList(), violations("    throw JsCrossing(JsCrossing.UNCONSTRUCTIBLE_VALUE)"))
        assertEquals(emptyList(), violations("    else -> throw\n        JsCrossing(JsCrossing.UNCONSTRUCTIBLE_VALUE)"))
        assertEquals(emptyList(), violations("val a = throwaway(it)\nval b = \"throwaway.signEvent\""))
    }

    @Test
    fun `a throw split across lines is reported on the line it starts`() {
        assertEquals(listOf("2: else -> throw"), violations("fun f() = when {\n    else -> throw\n        ChannelException(\n}"))
        assertEquals(listOf("1: throw // why"), violations("throw // why\n    // still why\n    ChannelException("))
        assertEquals(listOf("1: throw /* why"), violations("throw /* why\n */ ChannelException("))
    }

    @Test
    fun `a rethrown value is reported`() {
        assertEquals(listOf("1: } catch (e: ChannelException) { throw e }"), violations("} catch (e: ChannelException) { throw e }"))
        assertEquals(listOf("1: throw"), violations("throw\n    caught"))
    }

    @Test
    fun `an import of a rejection's constants is reported`() {
        assertEquals(listOf("1: import dev.eryalabs.nenya.channel.ChannelRejection.*"), violations("import dev.eryalabs.nenya.channel.ChannelRejection.*"))
        assertEquals(
            listOf("2: import dev.eryalabs.nenya.channel.ChannelRejection.DIFFERENT_ORDER as D"),
            violations("package x\nimport dev.eryalabs.nenya.channel.ChannelRejection.DIFFERENT_ORDER as D"),
        )
        assertEquals(emptyList(), violations("import dev.eryalabs.nenya.delivery.DeliveryRejection"))
    }

    @Test
    fun `a code line starting with a star is code`() {
        assertEquals(listOf("2: * ChannelRejection.DIFFERENT_ORDER.ordinal"), violations("val x = 2\n    * ChannelRejection.DIFFERENT_ORDER.ordinal"))
        assertEquals(emptyList(), violations("/**\n * ChannelRejection.DIFFERENT_ORDER\n */\nval x = 2"))
    }

    @Test
    fun `a comment opener inside a string hides nothing after it`() {
        assertEquals(listOf("1: val u = \"wss://relay\"; throw e"), violations("val u = \"wss://relay\"; throw e"))
        assertEquals(listOf("1: val u = \"/*\"; throw e"), violations("val u = \"/*\"; throw e"))
        assertEquals(listOf("1: val q = '\"'; throw e"), violations("val q = '\"'; throw e"))
        assertEquals(listOf("1: val t = \"\${f(\"//\")}\"; throw e"), violations("val t = \"\${f(\"//\")}\"; throw e"))
        assertEquals(listOf("1: val r = \"\"\"//\"\"\"; throw e"), violations("val r = \"\"\"//\"\"\"; throw e"))
        assertEquals(listOf("1: val s = \"ChannelRejection.DIFFERENT_ORDER\""), violations("val s = \"ChannelRejection.DIFFERENT_ORDER\""))
    }

    @Test
    fun `a value rethrown in parentheses or backticks is reported`() {
        assertEquals(listOf("1: } catch (e: ChannelException) { throw (e) }"), violations("} catch (e: ChannelException) { throw (e) }"))
        assertEquals(listOf("1: } catch (e: ChannelException) { throw(e) }"), violations("} catch (e: ChannelException) { throw(e) }"))
        assertEquals(listOf("1: } catch (`e`: ChannelException) { throw `e` }"), violations("} catch (`e`: ChannelException) { throw `e` }"))
        assertEquals(listOf("1: throw"), violations("throw\n    (caught)"))
    }

    @Test
    fun `a rejection constant with whitespace or a line break around the dot is reported`() {
        assertEquals(listOf("1: ChannelRejection"), violations("ChannelRejection\n    .DIFFERENT_ORDER"))
        assertEquals(listOf("1: val r = ChannelRejection . DIFFERENT_ORDER"), violations("val r = ChannelRejection . DIFFERENT_ORDER"))
        assertEquals(listOf("1: val r = ChannelRejection // why"), violations("val r = ChannelRejection // why\n    .DIFFERENT_ORDER"))
    }

    @Test
    fun `an aliased import of a rejection type is reported`() {
        assertEquals(
            listOf("2: import dev.eryalabs.nenya.channel.ChannelRejection as R"),
            violations("package x\nimport dev.eryalabs.nenya.channel.ChannelRejection as R\nval r = R.DIFFERENT_ORDER"),
        )
        assertEquals(emptyList(), violations("import dev.eryalabs.nenya.channel.ChannelRejection"))
        assertEquals(emptyList(), violations("import dev.eryalabs.nenya.channel.ChannelRejectionAssert"))
    }

    @Test
    fun `T96's shapes inside KDoc or a comment are not reported`() {
        val shapes = listOf(
            "throw (e)",
            "throw(e)",
            "throw `e`",
            "ChannelRejection\n.DIFFERENT_ORDER",
            "ChannelRejection . DIFFERENT_ORDER",
            "import dev.eryalabs.nenya.channel.ChannelRejection as R",
        )
        for (shape in shapes) {
            val kdoc = "/**\n" + shape.lines().joinToString("\n") { " * $it" } + "\n */\nval x = 1"
            assertEquals(emptyList(), violations(kdoc), kdoc)
            val comment = shape.lines().joinToString("\n") { "// $it" } + "\nval x = 1"
            assertEquals(emptyList(), violations(comment), comment)
            assertTrue(violations(shape).isNotEmpty(), "outside a comment, $shape is reported")
        }
    }

    @Test
    fun `code after a closed block comment is scanned`() {
        assertEquals(listOf("2: */ throw e"), violations("/* a\n */ throw e"))
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
            File(root, "Dirty.kt").writeText("fun h(): Nothing = throw\n    ChannelException(\n")
            assertScanFails { assertTranslationOnly(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
