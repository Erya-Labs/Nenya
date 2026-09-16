package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.1 and stop rule 12, executable: a wallet that lies about everything produces no evidence.
 *
 * This is the headline of the seam task and the reason the seams exist as types at all. §3 says
 * the wallet MUST NOT be believed when it claims a payment succeeded; §9.1 says the same thing
 * with more force; §17 item 6 says an implementation advances to `paid` only on evidence it
 * verified itself. All three are statements about code that does not exist yet — the state
 * machine is T7's — so what can be proved *here* is the half that will still be true then: the
 * wallet seam has no path to a `VerifiedPayment`, by value or by type.
 *
 * The scoping is deliberate and is named in the task rather than papered over. "And advances no
 * order" has no subject until the state machine exists, and that half belongs to T7.
 */
class LyingWalletTest {

    private companion object {

        /** The real pair: a generated preimage and the payment hash a real invoice would carry. */
        val realPreimage: Preimage = PaymentFixtures.preimages(1).single()

        /**
         * 32 well-formed bytes that are **not** [realPreimage]. Generated, never typed, and
         * deliberately valid hex of the right length: a lie that failed §9.2 check 2 would be
         * caught by the shape rules and would prove nothing about check 3.
         */
        val fabricatedPreimageHex: String = PaymentFixtures.preimageHex(2).last()
    }

    @Test
    fun `the wallet claims settled, hands over a preimage, and evidences nothing`() {
        val paymentHash = PaymentFixtures.paymentHashOf(realPreimage)
        val wallet = LyingWallet(fabricatedPreimageHex)

        val paid = wallet.payInvoice(SeamFixtures.NOT_AN_INVOICE)
        val status = wallet.paymentStatus(SeamFixtures.NOT_AN_INVOICE)
        val balance = wallet.spendableBalance()

        // Everything the wallet says is maximally encouraging.
        val paidClaim = paid.provided()
        val statusClaim = status.provided()
        assertEquals(WalletPaymentState.CLAIMS_SETTLED, paidClaim.state)
        assertEquals(WalletPaymentState.CLAIMS_SETTLED, statusClaim.state)
        assertEquals(Msat.SUPPLY_CAP, balance.provided())
        assertEquals(fabricatedPreimageHex, paidClaim.claimedPreimageHex)

        // And the claimed preimage is well-formed, so the refusal below is about §9.2 check 3
        // and not about check 2 — a lie caught by the shape rules would prove nothing.
        val claimed = Preimage.ofHex(paidClaim.claimedPreimageHex ?: fail("the wallet offered no preimage"))

        val refused = assertFailsWith<PaymentException> {
            VerifiedPayment.verify(Payee.PROVIDER, paymentHash, claimed)
        }
        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "a wallet that reports every payment settled and hands over preimage-shaped bytes must " +
                "still produce no evidence; §9.2 check 3 is the only thing standing between the two",
        )
    }

    @Test
    fun `the same code path accepts the real preimage, so the refusal above is not vacuous`() {
        val paymentHash = PaymentFixtures.paymentHashOf(realPreimage)

        val evidence = VerifiedPayment.verify(Payee.PROVIDER, paymentHash, realPreimage)

        assertEquals(Payee.PROVIDER, evidence.payee)
        assertEquals(paymentHash, evidence.paymentHash)
    }

    @Test
    fun `no published member of the seam package can produce or hold payment evidence`() {
        val published = SeamReflection.publishedClasses()
        // Its own by-name guard. An empty offender list is what this test asserts, so it is
        // exactly the shape that stays green when the sweep resolves to the wrong output
        // directory — the trap the delivery package's Boolean sweep fell into and was pulled out of.
        assertTrue(
            published.map { it.simpleName }.containsAll(listOf("Wallet", "WalletPaymentClaim", "SeamAnswer")),
            "the sweep must be looking at the seam package; it found ${published.map { it.simpleName }}",
        )

        val offenders = mutableListOf<String>()
        for (type in published) {
            for (method in SeamReflection.publishedMethods(type)) {
                // Generic-aware. `getReturnType()` is the erasure, so a
                // `Wallet.evidence(): SeamAnswer<VerifiedPayment>` reports `SeamAnswer` and slips
                // straight past an erasure-only check — and `SeamAnswer<T>` is the shape *every*
                // seam method returns, so it is exactly the wrapper that would defeat this sweep.
                if (SeamReflection.mentions(method.genericReturnType, VerifiedPayment::class.java)) {
                    offenders += "${SeamReflection.label(type, method)} returns ${method.genericReturnType}"
                }
            }
            for (field in type.fields) {
                if (SeamReflection.mentions(field.genericType, VerifiedPayment::class.java)) {
                    offenders += "${type.name}.${field.name} holds ${field.genericType}"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a seam is a place a lie enters. §9.2's verifier takes a Preimage and a PaymentHash and " +
                "nothing else, and the day something in this package can hand back a VerifiedPayment " +
                "is the day a wallet's claim becomes evidence by a shorter route",
        )
    }

    @Test
    fun `the payment verifier takes nothing a seam supplies`() {
        val companion = Class.forName("dev.eryalabs.nenya.payment.VerifiedPayment\$Companion")
        val verify = companion.methods.single { it.name == "verify" && !it.isSynthetic && !it.isBridge }

        assertEquals(
            listOf(
                Payee::class.java,
                Class.forName("dev.eryalabs.nenya.payment.PaymentHash"),
                Preimage::class.java,
            ),
            verify.parameterTypes.toList(),
            "the only door into payment evidence must stay closed to the seams: a Wallet, a " +
                "WalletPaymentClaim or a SeamAnswer on this parameter list would be §9.1 undone",
        )
        for (parameter in verify.parameterTypes) {
            assertFalse(
                Seam::class.java.isAssignableFrom(parameter) ||
                    SeamAnswer::class.java.isAssignableFrom(parameter) ||
                    parameter.name.startsWith(SeamReflection.PACKAGE),
                "${parameter.name} is a seam type and is on VerifiedPayment.verify's parameter list",
            )
        }
    }

    @Test
    fun `the wallet's claimed preimage is redacted in every string representation`() {
        val wallet = LyingWallet(fabricatedPreimageHex)
        val claim = wallet.payInvoice(SeamFixtures.NOT_AN_INVOICE).provided()

        assertFalse(
            claim.toString().contains(fabricatedPreimageHex),
            "§12 item 11 names preimages alongside key material and order ids: none may appear in a " +
                "log, a crash report, or the string representation of anything this library exposes",
        )
        assertFalse(
            SeamAnswer.Provided(claim).toString().contains(fabricatedPreimageHex),
            "wrapping a claim in a SeamAnswer must not be a way around §12 item 11",
        )
        assertTrue(claim.toString().contains("redacted"))

        // Pinned whole, not merely checked for the full hex. §12 item 11 admits no partial form,
        // and a `contains(<the whole 64 characters>)` assertion passes a toString that discloses a
        // 48-character prefix — which is 24 bytes of a 32-byte preimage. `OrderId` is pinned this
        // way already (`OrderIdTest`), and the asymmetry is what let this one through.
        assertEquals(
            "WalletPaymentClaim(state=CLAIMS_SETTLED, claimedPreimage=redacted)",
            claim.toString(),
        )
        for (window in fabricatedPreimageHex.windowed(8)) {
            assertFalse(
                claim.toString().contains(window),
                "no 8-character window of the preimage may survive, not just the whole of it",
            )
        }
    }

    /** The absent case is distinguishable from the redacted one, and still leaks nothing. */
    @Test
    fun `a claim carrying no preimage says so without inventing one`() {
        val claim = WalletPaymentClaim(WalletPaymentState.CLAIMS_FAILED, null)

        assertEquals(
            "WalletPaymentClaim(state=CLAIMS_FAILED, claimedPreimage=absent)",
            claim.toString(),
            "'absent' and 'redacted' are different facts and a diagnostic that conflated them would " +
                "make a wallet offering nothing look like one offering something",
        )
    }
}

/**
 * The reflection sweep this package shares, in the shape `PaymentStructureTest` specifies and
 * for the reasons it gives, over `dev/eryalabs/nenya/seam`.
 *
 * - **`java.lang.reflect` only.** `kotlin-reflect` is not on any classpath and stop rule 11
 *   forbids adding one. `KClass.members` and `KClass.constructors` compile and then throw
 *   `KotlinReflectionNotSupportedError` at run time; `kotlin.reflect.full.*` does not compile at
 *   all, so reaching for it burns a strike on a red build rather than a red test.
 * - **`getResources`, plural.** The singular form resolves to the **test** output directory,
 *   because `build/classes/kotlin/jvm/test` precedes `build/classes/kotlin/jvm/main` on the test runtime
 *   classpath and this repo mirrors package names into its test tree. A sweep built on it would
 *   enumerate this very file's class, never see [Signer], and pass with the implementation
 *   entirely absent. It fails loudly when no `/classes/kotlin/jvm/main/` URL is found.
 * - **Classes asserted by name**, never by count, which is what the plural form exists to make
 *   meaningful.
 *
 * Extracted into its own object rather than duplicated per test class because four files in this
 * package need it. The delivery and payment packages each keep their own copy; sharing across
 * packages would mean one sweep deciding which package it is about at run time, which is exactly
 * the ambiguity the by-name assertions exist to remove.
 */
internal object SeamReflection {

    const val PACKAGE: String = "dev.eryalabs.nenya.seam"

    /** Every class Gradle wrote for `src/jvmMain/kotlin`'s copy of this package. */
    fun mainClasses(): List<Class<*>> = classesIn("/classes/kotlin/jvm/main/", MIN_MAIN_CLASSES)

    /** Every class Gradle wrote for `src/jvmTest/kotlin`'s copy — where the fakes live. */
    fun testClasses(): List<Class<*>> = classesIn("/classes/kotlin/jvm/test/", MIN_TEST_CLASSES)

    /**
     * The class files each output directory held for this package at commit 6432814, pinned as
     * floors: a partial directory after a source-layout move must go red, not sweep the remainder.
     */
    private const val MIN_MAIN_CLASSES: Int = 33
    private const val MIN_TEST_CLASSES: Int = 30

    fun publishedClasses(): List<Class<*>> =
        mainClasses().filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    /** Own public methods, minus bridges, synthetics and everything a client cannot name. */
    fun publishedMethods(type: Class<*>): List<java.lang.reflect.Method> =
        type.methods.filter {
            it.declaringClass == type && !it.isSynthetic && !it.isBridge && '$' !in it.name
        }

    /**
     * The name a human reads. Kotlin mangles a public function whose parameter list contains an
     * inline class — `Wallet.issueInvoice(Msat)` is emitted as `issueInvoice-xTAHlNM` — so the
     * hash is stripped, or every label in this file would change the day a parameter type did.
     */
    fun label(type: Class<*>, executable: java.lang.reflect.Executable): String {
        val simple = type.name.removePrefix("$PACKAGE.")
        val member = when (executable) {
            is java.lang.reflect.Constructor<*> -> "<init>"
            else -> executable.name.substringBefore('-')
        }
        return "$simple.$member"
    }

    fun byName(name: String): Class<*> = mainClasses().single { it.name == "$PACKAGE.$name" }

    /**
     * Whether [type] mentions [sought] anywhere, including inside a generic's type arguments.
     *
     * `Method.getReturnType()` and `Field.getType()` are erasures, so `SeamAnswer<VerifiedPayment>`
     * reports as plain `SeamAnswer`. Every seam method returns `SeamAnswer<T>`, which makes that
     * wrapper the one shape capable of defeating a sweep built on the erasure — so the sweeps read
     * `genericReturnType`/`genericType` and this walks what they return.
     */
    fun mentions(type: java.lang.reflect.Type, sought: Class<*>): Boolean = when (type) {
        is Class<*> -> sought.isAssignableFrom(type)
        is java.lang.reflect.ParameterizedType ->
            mentions(type.rawType, sought) || type.actualTypeArguments.any { mentions(it, sought) }
        is java.lang.reflect.WildcardType ->
            type.upperBounds.any { mentions(it, sought) } || type.lowerBounds.any { mentions(it, sought) }
        is java.lang.reflect.GenericArrayType -> mentions(type.genericComponentType, sought)
        else -> false
    }

    private fun classesIn(marker: String, pinned: Int): List<Class<*>> {
        val loader = SeamReflection::class.java.classLoader
        val urls = loader.getResources(PACKAGE.replace('.', '/')).toList()
        val chosen = urls.firstOrNull { it.path.contains(marker) }
            ?: fail(
                "no $marker URL for $PACKAGE among $urls — a sweep that fell back to the other " +
                    "output directory would pass with the code it is about entirely absent",
            )
        val directory = File(chosen.toURI())
        val files = directory.listFiles { file: File -> file.name.endsWith(".class") }
            ?: fail("${directory.absolutePath} is not a readable directory")
        assertTrue(files.isNotEmpty(), "${directory.absolutePath} holds no classes")
        assertTrue(
            files.size >= pinned,
            "${directory.absolutePath} holds ${files.size} class file(s); at least $pinned were pinned",
        )
        return files.map { Class.forName("$PACKAGE.${it.name.removeSuffix(".class")}", false, loader) }
    }
}
