package dev.eryalabs.nenya.settlement

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No `.kt` file under either test source root may carry anything that looks like a BOLT-11 invoice.
 *
 * ### The failure this exists to make impossible
 *
 * The Definition of done has forbidden hand-written encoded values from the start, and it has been
 * broken anyway: three separate reviews of an earlier draft of the work queue caught fabricated
 * invoices, one of them a *pair* asserting the opposite of what it tested, because bech32 splits at
 * the **last** `1` and neither string was parseable at all. Reasoning about an encoding instead of
 * computing it is unreliable in a way that looks entirely fine on the page, and no reviewer catches
 * it reliably. So it is a test.
 *
 * Decision D says every invoice in a test comes from the vendored BOLT-11 examples or is derived
 * from them by a composer proven to rebuild every one of them exactly. This sweep is the half of
 * that rule a machine can check: it cannot tell a *derived* invoice from a typed one — both are
 * computed at run time and neither appears in the source — but it can say that no invoice appears
 * in the source at all, which is the only way a typed one could get in.
 *
 * ### Why a source sweep, and therefore why `src/jvmTest`
 *
 * It reads the source tree, which a common test cannot: a Kotlin/JS test has no filesystem. The
 * subject is the *text of the tests*, so there is nothing about it that needs to run on JavaScript
 * — what would be lost by a common version of this is exactly nothing, and what would be gained is
 * a test that cannot do its job. STOP RULE 15's "a test belongs in `src/jvmTest` only when it
 * genuinely cannot be common" is satisfied in its strongest form.
 *
 * ### The predicate, and why it is loose
 *
 * `ln`, a prefix, a `1`, then at least [MINIMUM_DATA_CHARACTERS] bech32 characters, case-insensitive
 * — deliberately looser than [Bolt11Reference.recognise], because this is not deciding whether a
 * string is a *valid* invoice. It is deciding whether somebody wrote one down, and the fabricated
 * strings that have already got past a human review were not valid either. A shorter floor would
 * start matching ordinary identifiers; Appendix C's own floor is a 7-character timestamp plus a
 * 104-character signature, so a hundred is comfortably below anything real and comfortably above
 * anything accidental.
 */
class InvoiceLiteralSweepTest {

    private companion object {

        /** Gradle runs JVM tests with the module directory as the working directory. */
        val SOURCE_ROOTS: List<String> = listOf("src/commonTest", "src/jvmTest")

        /**
         * This file, excluded because it is the one place the pattern is written out — and because
         * the negative control below constructs a file containing a real invoice.
         *
         * A **path** and not a bare file name: an exclusion on the name alone would exempt any
         * future file that happened to be called this, anywhere under either root, which is a hole
         * in the one guard that has no second line of defence.
         *
         * Asserted to have been *found* rather than merely skipped: an exclusion that matched
         * nothing would mean the sweep was reading the wrong directory, and every other assertion
         * here would then pass over an empty set.
         */
        val THIS_FILE: File =
            File("src/jvmTest/kotlin/dev/eryalabs/nenya/settlement/InvoiceLiteralSweepTest.kt")

        /** Appendix C's floor is 111 data characters; a hundred is below it and far above noise. */
        const val MINIMUM_DATA_CHARACTERS: Int = 100

        /**
         * How many `.kt` files the two roots must hold at least.
         *
         * A floor rather than an exact count, because files are added every task — but a floor all
         * the same: a sweep that walked an empty tree would pass every assertion below, and this is
         * what makes "no file carries an invoice" a statement about something.
         */
        const val MINIMUM_FILES: Int = 100

        /**
         * `ln`, a network prefix, Appendix C's optional amount, the separator, then a long run of
         * bech32.
         *
         * Case-insensitive because the document's all-uppercase example is an invoice too, and a
         * guard that only caught the lowercase spelling would be one `uppercase()` away from
         * useless. The bech32 set is written out here rather than taken from `Bolt11Reference`,
         * for the reason `Bolt11Composer` gives about sharing constants with the code under test:
         * a guard that read its alphabet from the thing it is guarding would stop guarding the
         * moment that alphabet was wrong.
         */
        val INVOICE_SHAPED: Regex =
            Regex("(?i)ln[a-z]+[0-9]*[munp]?1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{$MINIMUM_DATA_CHARACTERS,}")
    }

    /** Every `.kt` file under [SOURCE_ROOTS], with the file this sweep lives in left in. */
    private fun sources(): List<File> = SOURCE_ROOTS
        .map { File(it) }
        .onEach { assertTrue(it.isDirectory, "$it is not a directory; the working directory is ${File(".").absolutePath}") }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `no test source carries a string that looks like a BOLT-11 invoice`() {
        val files = sources()
        assertTrue(
            files.size >= MINIMUM_FILES,
            "the sweep found only ${files.size} `.kt` files under $SOURCE_ROOTS, which is below the " +
                "floor of $MINIMUM_FILES — it is reading the wrong tree, and would pass over an " +
                "empty one without noticing",
        )
        assertEquals(
            1,
            files.count { it.path == THIS_FILE.path },
            "${THIS_FILE.path} must be among the files the sweep walked, or the exclusion below is " +
                "excluding nothing and this test is not reading its own source root",
        )

        val offenders = files
            .filter { it.path != THIS_FILE.path }
            .filter { INVOICE_SHAPED.containsMatchIn(it.readText()) }
            .map { it.path }

        assertEquals(
            emptyList(),
            offenders,
            "a BOLT-11 invoice may never be written into a test. Every one comes from " +
                "`vectors/bolt11/11-payment-encoding.md` through `Bolt11Examples`, or is derived " +
                "from one by `Bolt11Composer` (decision D). A string reasoned about rather than " +
                "computed is wrong in a way that looks right, and this repository has three " +
                "reviews' worth of evidence for that",
        )

        // What the sweep read, reported rather than left implicit: a floor that a shrinking tree
        // could drift under is worth seeing in the log before it does.
        println("InvoiceLiteralSweepTest read ${files.size} .kt files under $SOURCE_ROOTS")
    }

    /**
     * The negative control: the predicate really does catch a pasted invoice.
     *
     * Without it, "no file matched" is equally true of a pattern that matches nothing at all — and
     * a pattern that matches nothing is exactly what a small edit to the character class or the
     * length floor would produce. The invoice comes from the vendored document through
     * [Bolt11Examples], so even the control is not a typed string.
     *
     * The temporary directory's parent is under the module's `build/`: this sandbox has no writable
     * `/tmp`, and a control that could only run somewhere else is a control that does not run.
     */
    @Test
    fun `the predicate catches a vendored invoice pasted into a source file`() {
        val invoice = Bolt11Examples.extract()
            .first { it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase() }
            .invoice
        assertTrue(
            invoice.length > MINIMUM_DATA_CHARACTERS,
            "a vendored example must be longer than the floor, or the control proves nothing",
        )

        val parent = File("build/tmp/invoice-literal-sweep").also { it.mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "pasted")
        try {
            val planted = directory.resolve("Planted.kt").toFile()
            // Written, never checked in: the file this creates is exactly what the sweep exists to
            // refuse, so it may not survive the test (STOP RULE 8).
            planted.writeText("package scratch\n\nval invoice: String = \"" + invoice + "\"\n")

            val caught = directory.toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { INVOICE_SHAPED.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()

            assertEquals(
                listOf("Planted.kt"),
                caught,
                "the predicate must catch an invoice pasted into a `.kt` file, or the sweep above " +
                    "is a pattern that matches nothing dressed up as a guarantee",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
