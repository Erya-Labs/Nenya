package dev.eryalabs.nenya.seam

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reflective half of the non-vacuity floor under `SeamCryptoFakes.kt`.
 *
 * `EphemeralSignerTest` asserts in common Kotlin that [exerciseEveryCryptoFake] returns a pinned
 * set of names. On its own that pair proves less than it looks: both halves are strings, and a
 * fake deleted from the file and from the list together leaves the suite green. This file anchors
 * the names to the **compiled test tree** — every one must be a real class there, and every
 * implementation of the new seam found there must be among them.
 *
 * The sweep is [SeamReflection]'s, for the reasons given where it is declared: `java.lang.reflect`
 * only, `getResources` plural, the `/classes/kotlin/jvm/test/` URL, classes asserted by name.
 */
class SeamFakeCoverageTest {

    @Test
    fun `every name the exercise reports is a real class in the test tree`() {
        val exercised = exerciseEveryCryptoFake()
        val present = SeamReflection.testClasses().map { it.simpleName }.toSet()

        assertTrue(exercised.size >= 8, "the exercise reported only $exercised, which is not the file")
        for (name in exercised) {
            assertTrue(
                name in present,
                "$name is not a class in ${SeamReflection.PACKAGE}'s test output. The exercise " +
                    "reports the runtime names of what it called, so a name absent here means the " +
                    "sweep is looking at the wrong output directory — the one failure mode that " +
                    "would make every assertion in this file vacuous.",
            )
        }
    }

    /**
     * The direction that catches the fake somebody adds and forgets to exercise.
     *
     * Scoped to [EphemeralSigners] rather than to every seam: the fakes for the other six seams
     * predate `SeamCryptoFakes.kt` and are exercised by their own packages' tests, so requiring
     * them here would fail for fakes that are covered elsewhere. This is the seam this task
     * introduces, and every implementation of it must be reachable from a self-test.
     *
     * Anonymous classes are excluded, and that is not a loophole: `SeamFailClosedTest`'s probe is
     * an `object :` expression declared inside the test that falsifies the fail-closed predicate,
     * it has no name to exercise by, and it is exercised there by construction.
     */
    @Test
    fun `every named one-time signer fake in the test tree is exercised by a self-test`() {
        val exercised = exerciseEveryCryptoFake()
        val implementations = SeamReflection.testClasses().filter {
            !it.isInterface &&
                !it.isAnonymousClass &&
                !it.isSynthetic &&
                !Modifier.isAbstract(it.modifiers) &&
                EphemeralSigners::class.java.isAssignableFrom(it)
        }

        assertTrue(
            implementations.size >= 3,
            "found only ${implementations.map { it.simpleName }} — the well-behaved source and the " +
                "misbehaving variants are the minimum, so this sweep is looking at the wrong place",
        )
        for (implementation in implementations) {
            assertTrue(
                implementation.simpleName in exercised,
                "${implementation.simpleName} implements EphemeralSigners and no self-test calls " +
                    "it. A fake nothing exercises is one the envelope tasks would be the first to " +
                    "run, which is the wrong place to discover it does not work.",
            )
        }
    }
}
