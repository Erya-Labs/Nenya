package dev.eryalabs.nenya.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `file#JsName` that [StateMachineCasesTest.CASES], [MoneyVectorCasesTest.PREIMAGE_CASES] and
 * [MoneyVectorCasesTest.FEE_CASES] pin to a §18 case is a test that exists: the file is under
 * [StateMachineCasesTest.TEST_ROOT] and carries `@JsName("…")` with that name.
 *
 * ### In `jvmTest`, and why that is not a contradiction
 *
 * STOP RULE 15 puts new tests in `src/commonTest`. This one cannot be there: it reads the test sources
 * from disk, as `SpecCitationTest` reads the source tree, which a common test cannot do. Everything
 * else about the named cases is checked in common code by [StateMachineCasesTest] and [MoneyVectorCasesTest]. [problems] takes its
 * root and entries as parameters, so every control below runs against a directory or entry built here.
 */
class StateMachineCasesSourceTest {

    /** Gradle runs JVM tests with the module directory as the working directory. */
    private val root = File(StateMachineCasesTest.TEST_ROOT)

    private val entries: List<String> = StateMachineCasesTest.CASES.values.flatten() +
        MoneyVectorCasesTest.PREIMAGE_CASES.values.flatten() + MoneyVectorCasesTest.FEE_CASES.values.flatten()

    /** What is wrong with each of [entries] under [root]: a missing file, or a `JsName` absent from it. Never skips one. */
    private fun problems(root: File, entries: List<String>): List<String> = entries.mapNotNull { entry ->
        val (path, name) = entry.split("#").also { require(it.size == 2) { "malformed entry $entry" } }
        val file = root.resolve(path)
        when {
            !file.isFile -> "$entry: no file at ${file.path}"
            "@JsName(\"$name\")" !in file.readText() -> "$entry: no @JsName(\"$name\") in ${file.path}"
            else -> null
        }
    }

    @Test
    fun `every pinned test exists in its file`() {
        assertTrue(root.isDirectory, "the common test root ${root.absolutePath} must exist")
        assertTrue(entries.isNotEmpty())
        assertEquals(emptyList(), problems(root, entries))
    }

    @Test
    fun `an entry naming a JsName absent from its file fails`() {
        val absent = "order/OrderControlsTest.kt#a_fee_bearing_order_reaches_paid_without_its_fee_receipt"
        assertEquals(1, problems(root, entries + absent).size)
        assertTrue(problems(root, listOf(absent)).single().contains("no @JsName"))
    }

    @Test
    fun `an entry naming a missing file fails and is never skipped`() {
        val missing = "order/NoSuchTest.kt#a_fee_bearing_order_reaches_awaiting_payment_and_then_paid"
        assertEquals(1, problems(root, entries + missing).size)
        assertTrue(problems(root, listOf(missing)).single().contains("no file"))
        assertEquals(entries.size, problems(File("no-such-root"), entries).size, "a wrong root fails every entry")
    }

    @Test
    fun `a money entry naming an absent JsName or a missing file fails and is never skipped`() {
        val absent = "payment/PaymentEvidenceTest.kt#a_thirty_two_byte_preimage_is_rejected_as_the_wrong_length"
        val missing = "money/NoSuchFeeTest.kt#at_0_bps_the_fee_is_zero_at_every_price_up_to_the_supply_cap"
        assertTrue(problems(root, listOf(absent)).single().contains("no @JsName"))
        assertTrue(problems(root, listOf(missing)).single().contains("no file"))
        assertEquals(2, problems(root, entries + absent + missing).size)
    }
}
