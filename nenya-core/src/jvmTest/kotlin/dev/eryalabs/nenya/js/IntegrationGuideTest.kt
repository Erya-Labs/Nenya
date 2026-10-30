package dev.eryalabs.nenya.js

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `docs/INTEGRATING.md`'s `## Entry points` index names every function the JavaScript build exports,
 * each exactly once, and nothing else.
 *
 * ### Why the index is held to the compiled surface
 *
 * A guide listing entry points by hand goes stale the first time a task adds one. So the index is held
 * by exact set equality to [JsSurfaceTest.ENTRY_POINTS], which `JsSurfaceTest` itself holds to the
 * top-level functions of the compiled file classes. An export added without a bullet, a bullet for a
 * name nothing exports, and a name listed twice each turn this red.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest`. This one cannot be there: the pin it compares
 * against is reflection over the JVM build output, which is that rule's own named exception, and the
 * guide is read from disk, which a common test cannot do. The parser takes the text as a parameter, so
 * every control below runs on text built in memory, and only one test opens the file.
 */
class IntegrationGuideTest {

    private companion object {

        /** Gradle runs JVM tests with the module directory as the working directory. */
        const val GUIDE_PATH: String = "../docs/INTEGRATING.md"

        const val HEADING: String = "## Entry points"

        /**
         * The names [text]'s `## Entry points` section lists, in order and with repeats kept, so a
         * duplicate is visible to the caller.
         *
         * The section runs from its heading to the next `## ` heading or the end of the text. Every
         * line in it that starts with `- ` must be a ``- `name` — …`` bullet: a malformed one fails
         * here rather than being skipped, because a skipped bullet would let a name drop out of the
         * comparison silently. A text with no such section, or a section with no bullets, fails too.
         */
        fun entryPoints(text: String): List<String> {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trimEnd() == HEADING }
            if (start < 0) fail("the guide has no `$HEADING` section")
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }

            val names = section.filter { it.startsWith("- ") }.map { line ->
                val name = line.removePrefix("- `").substringBefore("` — ", missingDelimiterValue = "")
                if (!line.startsWith("- `") || name.isEmpty() || '`' in name || ' ' in name) {
                    fail("`$line` in `$HEADING` is not a ``- `name` — …`` bullet")
                }
                name
            }
            if (names.isEmpty()) fail("`$HEADING` lists no entry point")
            return names
        }

        /** The guide on disk. A missing file fails; it never skips. */
        fun read(file: File): String {
            assertTrue(
                file.isFile,
                "${file.path} was expected at ${file.absoluteFile.normalize()}; the working directory is " +
                    File(".").absoluteFile.normalize(),
            )
            return file.readText(Charsets.UTF_8)
        }

        /**
         * Fails unless [names] lists every entry point exactly once and nothing else, naming what is
         * missing, what is extra and what is repeated.
         */
        fun assertIndexed(names: List<String>) {
            val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertEquals(emptySet(), repeated, "names listed more than once in `$HEADING`")
            assertEquals(
                emptySet<String>() to emptySet(),
                (JsSurfaceTest.ENTRY_POINTS - names.toSet()) to (names.toSet() - JsSurfaceTest.ENTRY_POINTS),
                "(entry points the guide does not list) to (names it lists that nothing exports)",
            )
        }

        /** [text] with the bullet for [name] removed. */
        fun withoutBullet(text: String, name: String): String {
            val lines = text.lines()
            val bullet = lines.single { it.startsWith("- `$name` — ") }
            return (lines - bullet).joinToString("\n")
        }

        /** [text] with [bullet] inserted directly after the section heading. */
        fun withBullet(text: String, bullet: String): String =
            text.replaceFirst("$HEADING\n", "$HEADING\n$bullet\n")
    }

    private val guide: String by lazy { read(File(GUIDE_PATH)) }

    @Test
    fun `the guide's entry point index equals the exported entry points`() {
        val names = entryPoints(guide)
        assertIndexed(names)
        assertEquals(JsSurfaceTest.ENTRY_POINTS.size, names.size, "one bullet per entry point")
    }

    /** Negative control: every single dropped bullet is reported as exactly that missing name. */
    @Test
    fun `the index with any one bullet dropped fails`() {
        for (name in JsSurfaceTest.ENTRY_POINTS) {
            val names = entryPoints(withoutBullet(guide, name))
            assertNotEquals(JsSurfaceTest.ENTRY_POINTS, names.toSet(), "dropping `$name` changed nothing")
            assertFailsWith<AssertionError>("an index without `$name` must fail") { assertIndexed(names) }
        }
    }

    /** Negative control: a bullet for a name nothing exports fails. */
    @Test
    fun `the index with an unexported name added fails`() {
        val names = entryPoints(withBullet(guide, "- `buildEscrow` — not a function this library exports."))
        assertTrue("buildEscrow" in names, "the added bullet must be parsed")
        assertFailsWith<AssertionError> { assertIndexed(names) }
    }

    /** Negative control: an entry point listed twice fails, though the name set is unchanged. */
    @Test
    fun `the index with a name listed twice fails`() {
        val names = entryPoints(withBullet(guide, "- `seal` — listed a second time."))
        assertEquals(JsSurfaceTest.ENTRY_POINTS, names.toSet(), "the set alone cannot see a repeat")
        assertFailsWith<AssertionError> { assertIndexed(names) }
    }

    /** Negative control: a missing file fails rather than skipping, and so does a missing section. */
    @Test
    fun `a missing guide or a missing section fails and never skips`() {
        assertFailsWith<AssertionError> { read(File("../docs/NO-SUCH-GUIDE.md")) }
        assertFailsWith<AssertionError> { entryPoints(guide.replace(HEADING, "## Functions")) }
        assertFailsWith<AssertionError> { entryPoints("$HEADING\n\nNothing listed.\n") }
    }

    /** Negative control: a malformed bullet in the section fails rather than being skipped. */
    @Test
    fun `a malformed bullet in the index fails`() {
        assertFailsWith<AssertionError> { entryPoints(withBullet(guide, "- seal — no backticks.")) }
        assertFailsWith<AssertionError> { entryPoints(withBullet(guide, "- `seal`, missing the dash.")) }
    }
}
