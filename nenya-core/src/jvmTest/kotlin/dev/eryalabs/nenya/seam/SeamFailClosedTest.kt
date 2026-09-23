package dev.eryalabs.nenya.seam

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every default answers **unavailable**, for every method it declares — enumerated by reflection
 * rather than listed by hand.
 *
 * A hand-written list of "check the wallet, check the signer, check the clock" is green on the
 * day it is written and silent on the day somebody adds a seventh method with a friendlier
 * default. So the sweep discovers the seams (every interface in this package extending [Seam]),
 * discovers each one's fail-closed default (through its companion, without naming the accessor),
 * discovers each one's methods, synthesises an argument for every parameter, invokes, and
 * asserts the answer is [SeamAnswer.Unavailable].
 *
 * ### It fails on a method it cannot invoke — it never skips one
 *
 * A sweep that quietly skipped what it could not construct arguments for would satisfy the
 * letter of "enumerated by reflection" while defeating the entire point of it: the one method
 * with an exotic parameter is exactly the one nobody wrote a default for. So [argumentFor]
 * fails loudly, and the two constraints that make that affordable are constraints on this
 * package's own API surface, stated in [Seam]'s KDoc: **every seam method returns a value**, and
 * **no seam method takes a parameter this test cannot construct from the JDK and this library's
 * own types**.
 *
 * ### `Unavailable` rather than `false` is this library's own stricter choice
 *
 * VISION permits either — "returns false or an explicit *unavailable*, never true" — and this
 * package takes the stricter answer uniformly, because §17 requires a machine-readable statement
 * of what an implementation actually verifies and `false` is indistinguishable from "checked,
 * and invalid". The mutation that proves it: make a default return a [SeamAnswer.Provided]
 * carrying a negative verdict and these tests turn red, which is them testing this task's own
 * rule rather than a VISION rule.
 *
 * ### Where these tests run
 *
 * The sweep is `java.lang.reflect` over the compiled seam package, so it runs on the JVM only. The
 * one test here that needs no reflection (the fakes answer) is in [PortableSeamFailClosedTest] in
 * `src/commonTest`, which this class extends, so it keeps its `SeamFailClosedTest` name and runs on
 * JavaScript as well.
 */
class SeamFailClosedTest : PortableSeamFailClosedTest() {

    private companion object {

        /**
         * §3's table names five seams — signer, one-time signer, relay transport, wallet, clock
         * and randomness. Seven interfaces here, because two of those rows split: "clock and
         * randomness" into [NenyaClock] and [Randomness], and [Secp256k1Ops] out of the signer,
         * because §17 lets an implementation omit BIP-340 verification while still signing through
         * a NIP-55 signer app. [EphemeralSigners] is **not** such a split — it is §3's own second
         * row, and it is here because §7.1's gift wrap needs a throwaway keypair a NIP-55 signer
         * app cannot mint. By name rather than by count, so a sweep pointed at the wrong classpath
         * entry cannot pass on whatever it happened to find.
         */
        val EXPECTED_SEAMS: Set<String> = setOf(
            "Signer",
            "EphemeralSigners",
            "RelayTransport",
            "Wallet",
            "NenyaClock",
            "Randomness",
            "Secp256k1Ops",
        )

        /**
         * Discovered, not listed: every interface in this package that extends [Seam].
         *
         * The by-name check lives **here** rather than only in its own test, because most
         * assertions in this file are loops over this list and a loop over an empty list is
         * green. Pointing the sweep at the test output directory turns up no seams at all, and
         * without this guard four of these tests would pass with the implementation absent.
         */
        fun seamInterfaces(): List<Class<*>> {
            val found = SeamReflection.mainClasses()
                .filter { it.isInterface && it != Seam::class.java && Seam::class.java.isAssignableFrom(it) }
                .sortedBy { it.name }
            assertEquals(
                EXPECTED_SEAMS,
                found.map { it.simpleName }.toSet(),
                "the sweep must be looking at §3's seams, not at whatever classpath entry it landed " +
                    "on; every loop below is vacuous over the wrong one",
            )
            return found
        }

        /** A seam's own methods. `Object`'s are not part of the seam. */
        fun seamMethods(type: Class<*>): List<Method> = type.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isStatic(it.modifiers) }
            .sortedBy { it.name }

        /**
         * The fail-closed default, reached through the companion object without naming the
         * accessor: the companion must publish **exactly one** no-argument method returning the
         * seam type. A seam added later without a fail-closed default has no such method, and
         * this fails rather than skipping it.
         */
        fun failClosedOf(type: Class<*>): Any {
            val field = runCatching { type.getDeclaredField("Companion") }.getOrElse {
                fail(
                    "${type.simpleName} has no companion object, so it publishes no fail-closed " +
                        "default. Every seam must, or a client that injects nothing gets whatever " +
                        "the call site happened to do instead.",
                )
            }
            field.isAccessible = true
            val companion = field.get(null) ?: fail("${type.simpleName}.Companion is null")
            val accessors = companion.javaClass.methods.filter {
                it.parameterCount == 0 && !it.isSynthetic && type.isAssignableFrom(it.returnType)
            }
            assertEquals(
                1,
                accessors.size,
                "${type.simpleName}'s companion must publish exactly one fail-closed default; found " +
                    "${accessors.map { it.name }}",
            )
            return accessors.single().invoke(companion)
                ?: fail("${type.simpleName}'s fail-closed default is null")
        }

        /**
         * An argument for a declared parameter, or a loud failure.
         *
         * Deliberately narrow. Widening it to "and otherwise try a no-arg constructor" would let
         * a seam quietly acquire a parameter this suite cannot reason about, which is the
         * property [Seam]'s KDoc rules out on purpose.
         */
        /**
         * A value distinctive enough that an implementation echoing it into its own diagnostic is
         * visible. The original sweep passed `""` and `ByteArray(32)`, which made every echo
         * invisible: `Unavailable.detail`'s KDoc promises it "never echoes an input, so it cannot
         * carry a key, a preimage, an order id or an invoice into a log (§12 item 11)", and a
         * default rewritten to interpolate its plaintext parameter stayed green across the whole
         * six-seam surface. Generated rather than typed, and shaped like the values that matter.
         */
        val MARKER: String = SeamFixtures.lowerHex(SeamFixtures.bytes(32, stream = 31L))

        fun argumentFor(parameter: Class<*>, where: String): Any = when (parameter) {
            String::class.java -> MARKER
            ByteArray::class.java -> SeamFixtures.bytes(32, stream = 32L)
            Int::class.javaPrimitiveType, Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            List::class.java -> listOf(MARKER)
            else -> fail(
                "the fail-closed sweep cannot construct a ${parameter.name} for $where, so it " +
                    "cannot invoke it — and it MUST NOT skip it. Either give this sweep a way to " +
                    "build one, or keep the seam's parameters to the JDK and this library's own " +
                    "types, which is what makes this sweep possible at all.",
            )
        }

        /**
         * The predicate the sweep judges every answer by, extracted so the negative control can
         * be fed **this** function rather than a copy of it.
         *
         * Inlined as `entry.answer is SeamAnswer.Unavailable` in the loop below, a control could
         * only ever re-state the check it was meant to falsify, and a sweep whose predicate had
         * been loosened would take the control down with it silently. Nothing is removed by the
         * extraction: the loop asserts on exactly this.
         */
        fun failsClosed(answer: Any?): Boolean = answer is SeamAnswer.Unavailable

        /** Every (seam, method) pair, invoked on the fail-closed default. */
        fun sweep(): List<SweptMethod> = seamInterfaces().flatMap { type ->
            val failClosed = failClosedOf(type)
            seamMethods(type).map { method ->
                val where = "${type.simpleName}.${method.name.substringBefore('-')}"
                val arguments = method.parameterTypes.map { argumentFor(it, where) }
                method.isAccessible = true
                SweptMethod(type, method, where, method.invoke(failClosed, *arguments.toTypedArray()))
            }
        }
    }

    private class SweptMethod(
        val type: Class<*>,
        val method: Method,
        val where: String,
        val answer: Any?,
    )

    @Test
    fun `the sweep finds every seam §3 names, by name`() {
        val found = seamInterfaces().map { it.simpleName }.toSet()

        assertEquals(
            EXPECTED_SEAMS,
            found,
            "the set of seams must match §3's table exactly. A missing one means the sweep stopped " +
                "seeing the package; an extra one is a seam nothing in §3 authorises this library " +
                "to consult.",
        )
    }

    @Test
    fun `every seam method on every default answers an explicit unavailable`() {
        val swept = sweep()

        assertTrue(
            swept.size >= EXPECTED_SEAMS.size,
            "the sweep invoked only ${swept.size} methods across ${EXPECTED_SEAMS.size} seams",
        )
        for (entry in swept) {
            assertTrue(
                failsClosed(entry.answer),
                "${entry.where} answered ${entry.answer}. Every default in this package must fail " +
                    "closed with an explicit unavailable — never a value, and never a negative " +
                    "verdict, which a caller cannot tell apart from 'checked, and it failed'.",
            )
        }
    }

    /**
     * The negative control under the sweep: a seam that does **not** fail closed is caught by
     * [failsClosed] — the sweep's own predicate, not a re-statement of it.
     *
     * `EphemeralSigners.FAIL_CLOSED` is a companion `val` and cannot be altered, so the control
     * cannot mutate the real default and watch the sweep go red. It declares a probe
     * implementation of the same interface instead, hands it to the same predicate the loop above
     * judges every answer by, and asserts the predicate rejects it. Without this, "every default
     * answers unavailable" would be equally true of a predicate rewritten to `true`.
     */
    @Test
    fun `the sweep's own predicate rejects a seam that answers rather than failing closed`() {
        val probe: EphemeralSigners = object : EphemeralSigners {
            override fun fresh(): SeamAnswer<Signer> = SeamAnswer.Provided(FakeSigner())
        }

        val answered = probe.fresh()
        val failedClosed = EphemeralSigners.FAIL_CLOSED.fresh()

        assertFalse(
            failsClosed(answered),
            "the sweep's predicate accepted $answered, so 'every default fails closed' is a " +
                "statement about nothing: a default rewritten to hand out a signer would pass it",
        )
        assertTrue(
            failsClosed(failedClosed),
            "and the same predicate must still accept the real default, or the control above is " +
                "measuring a predicate that rejects everything",
        )
        assertEquals(
            SeamCapability.EPHEMERAL_SIGNER,
            (failedClosed as SeamAnswer.Unavailable).capability,
        )
    }

    @Test
    fun `every seam contributed at least one method to the sweep`() {
        val perSeam = sweep().groupBy { it.type.simpleName }.mapValues { it.value.size }

        for (seam in EXPECTED_SEAMS) {
            assertTrue(
                (perSeam[seam] ?: 0) > 0,
                "$seam contributed no methods, so 'every method answers unavailable' is vacuously " +
                    "true of it; found $perSeam",
            )
        }
    }

    @Test
    fun `no seam method returns Unit, because a Unit method can only fail closed by throwing`() {
        for (type in seamInterfaces()) {
            for (method in seamMethods(type)) {
                assertFalse(
                    method.returnType == Void.TYPE,
                    "${type.simpleName}.${method.name.substringBefore('-')} returns Unit. A reflective " +
                        "sweep cannot tell a deliberate fail-closed throw from a bug, so the rule is " +
                        "that every seam method returns a value.",
                )
            }
        }
    }

    /**
     * §17's capability surface is what a caller's UI branches on, so *which* operation was declined
     * is the payload — not merely that one of them was.
     *
     * The bijection test below is invariant under a permutation: swapping the capabilities reported
     * by `Signer.publicKey` and `Signer.signEvent` keeps the reported set equal to
     * `SeamCapability.entries` with no duplicates, and stays green. Only two of the constants were
     * otherwise pinned to their method. This pins all fourteen.
     */
    @Test
    fun `each capability is reported by the specific method it names`() {
        val expected = mapOf(
            "Signer.publicKey" to SeamCapability.SIGNER_PUBLIC_KEY,
            "Signer.signEvent" to SeamCapability.EVENT_SIGNATURE,
            "Signer.nip44Encrypt" to SeamCapability.NIP44_ENCRYPTION,
            "Signer.nip44Decrypt" to SeamCapability.NIP44_DECRYPTION,
            "EphemeralSigners.fresh" to SeamCapability.EPHEMERAL_SIGNER,
            "RelayTransport.publish" to SeamCapability.RELAY_PUBLISH,
            "RelayTransport.request" to SeamCapability.RELAY_REQUEST,
            "Wallet.payInvoice" to SeamCapability.WALLET_PAYMENT,
            "Wallet.paymentStatus" to SeamCapability.WALLET_PAYMENT_STATUS,
            "Wallet.spendableBalance" to SeamCapability.WALLET_BALANCE,
            "Wallet.issueInvoice" to SeamCapability.WALLET_INVOICE_ISSUANCE,
            "NenyaClock.now" to SeamCapability.CLOCK_READING,
            "Randomness.randomBytes" to SeamCapability.RANDOM_BYTES,
            "Secp256k1Ops.verifySchnorr" to SeamCapability.BIP340_VERIFICATION,
        )

        val actual = sweep().associate { it.where to (it.answer as SeamAnswer.Unavailable).capability }

        assertEquals(
            expected,
            actual,
            "a caller told a capability was unavailable must be able to act on it; a permuted " +
                "surface reports the wrong operation for every one of them and no set-equality " +
                "assertion can see it",
        )
        assertEquals(
            SeamCapability.entries.size,
            expected.size,
            "every capability constant must appear above, or this pin has a hole in it",
        )
    }

    @Test
    fun `every capability the surface declares is answered by exactly one seam method`() {
        val swept = sweep()
        val reported = swept.map { (it.answer as SeamAnswer.Unavailable).capability }

        assertEquals(
            SeamCapability.entries.toSet(),
            reported.toSet(),
            "§17's capability surface must be complete in both directions: a constant no method " +
                "reports is a claim about nothing, and a method whose capability is missing is a " +
                "capability a caller cannot branch on",
        )
        assertEquals(
            reported.size,
            reported.toSet().size,
            "two seam methods reporting the same capability would make the surface ambiguous — a " +
                "caller told BIP340_VERIFICATION could not tell which operation was declined. " +
                "Duplicates: ${reported.groupingBy { it }.eachCount().filterValues { it > 1 }}",
        )
    }

    @Test
    fun `every unavailable answer carries a detail naming its capability`() {
        for (entry in sweep()) {
            val unavailable = entry.answer as SeamAnswer.Unavailable

            assertTrue(unavailable.detail.isNotBlank(), "${entry.where} gave no detail")
            assertTrue(
                unavailable.detail.contains(unavailable.capability.what),
                "${entry.where}'s detail must say which capability was not attempted; §17 requires " +
                    "the statement be actionable rather than merely present",
            )
        }
    }

    /**
     * And no default echoes what it was handed — across the whole surface, not one method of it.
     *
     * Every seam method is invoked with [MARKER] (or marker bytes) in every parameter it declares,
     * and the answer's `detail` and `toString` must contain none of it. This is the assertion the
     * test above was *named* for and could not make, because it fed the sweep `""`.
     * §12 item 11 covers key material, decryption keys, preimages and order ids; §12 item 2 adds
     * the invoice string, which reaches `Wallet.payInvoice` and `Wallet.paymentStatus` here.
     */
    @Test
    fun `no default echoes its arguments into the diagnostic it returns`() {
        val swept = sweep()
        // Every *rendering* a byte parameter could reach a log in, not just the canonical one. A
        // sweep looking only for the hex form misses `contentToString()`, which shares no substring
        // with it — and `Secp256k1Ops.verifySchnorr`'s `message` is a §4.1 **event id**, the 32
        // bytes §12 item 11's rule about identifiers is written over. Found by a reviewer's
        // surviving mutation.
        val bytes = SeamFixtures.bytes(32, stream = 32L)
        val renderings = listOf(
            MARKER,
            SeamFixtures.lowerHex(bytes),
            bytes.contentToString(),
            bytes.joinToString(""),
        )

        assertTrue(swept.size >= EXPECTED_SEAMS.size, "the sweep invoked only ${swept.size} methods")
        for (entry in swept) {
            val unavailable = entry.answer as SeamAnswer.Unavailable

            for (secret in renderings) {
                assertFalse(
                    unavailable.detail.contains(secret),
                    "${entry.where}'s detail echoes its argument. An Unavailable is authored in this " +
                        "library precisely so it can be logged; one that interpolates a plaintext, a " +
                        "key or an invoice carries it straight into the log (§12 items 2 and 11).",
                )
                assertFalse(
                    unavailable.toString().contains(secret),
                    "${entry.where}'s toString echoes its argument",
                )
            }
        }
    }

    /**
     * The floor under the test above: the marker must actually be the thing being passed, or
     * "no detail contains it" is true of any string at all.
     */
    @Test
    fun `the echo control really does hand every seam method something distinctive`() {
        assertEquals(64, MARKER.length, "the marker is 32 generated bytes in hex")

        val stringParameters = seamInterfaces().sumOf { type ->
            seamMethods(type).sumOf { method ->
                method.parameterTypes.count { it == String::class.java || it == ByteArray::class.java }
            }
        }

        assertTrue(
            stringParameters >= 6,
            "only $stringParameters String/ByteArray parameters across the seam surface — the echo " +
                "control would be nearly vacuous",
        )
    }

    /**
     * The other half of the non-vacuity floor the queue asks for: every seam interface has at
     * least one fake in the test tree. Without one, "the default fails closed" is a claim about an
     * interface nobody has ever implemented differently, and the fail-closed answer could be the
     * *only* answer the type can express.
     */
    @Test
    fun `every seam interface has at least one fake in the test tree`() {
        val fakes = SeamReflection.testClasses()

        for (seam in seamInterfaces()) {
            val implementations = fakes.filter {
                !it.isInterface && it != seam && seam.isAssignableFrom(it)
            }
            assertTrue(
                implementations.isNotEmpty(),
                "${seam.simpleName} has no fake in the test tree. A seam with no fake is a seam no " +
                    "later task can prove anything about without a device, a network or a keystore.",
            )
        }
    }
}
